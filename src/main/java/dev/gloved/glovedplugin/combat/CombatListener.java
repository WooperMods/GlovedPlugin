package dev.gloved.glovedplugin.combat;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.event.player.PlayerArmSwingEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side Better Combat-lite: arc expansion, combo stages, sweep falloff,
 * dual-wield alternation, hold-to-spam queue, per-weapon reach.
 *
 * <p>Design rules (EliteMobs-safe): the vanilla damage event is never
 * cancelled and never re-dealt — the primary hit keeps flowing through every
 * other plugin's damage pipeline (enchants, effects, custom formulas). We only
 * scale its amount early ({@code LOW}) and add secondary arc targets via
 * normal {@code damage()} calls so those fire standard events too. Swing FX
 * fires on every arm swing, hit or miss.
 */
public final class CombatListener implements Listener {

    private final GlovedPlugin plugin;
    private final WeaponRegistry registry;
    private final ComboTracker combos = new ComboTracker();
    /** Attacker -> last arc tick, to suppress vanilla sweep double-hits. */
    private final Map<UUID, Long> lastArcTick = new HashMap<>();
    /** Entities hit by our arc this tick (suppress chained sweep events). */
    private final Set<UUID> arcVictims = new HashSet<>();
    /** Targets currently being damaged by us (skip nested echoes). */
    private final Set<UUID> syntheticTargets = new HashSet<>();

    public CombatListener(GlovedPlugin plugin, WeaponRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Hold-to-spam pump: fire queued swings once cooldown is ready.
        Bukkit.getScheduler().runTaskTimer(plugin, this::pumpQueuedSwings, 2L, 2L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyReach(player);
        }
    }

    private static long tick(Player player) {
        return player.getWorld().getFullTime();
    }

    // --- Swing: FX + combo advance (every swing, hit or miss) ---

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        if (!registry.isEnabled()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        // Skip item-use animations (eating, bow draw, shield block).
        try {
            if (player.isHandRaised() || player.isBlocking()) return;
        } catch (NoSuchMethodError ignored) {
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) return;

        WeaponAttributes attrs = registry.forMaterial(mainHand.getType());
        ComboTracker.State state = combos.state(player.getUniqueId());
        long now = tick(player);

        // Burst tracking for hold-to-spam: repeated swings in a short window.
        if (now - state.lastSwingTick <= 12) {
            state.burst++;
        } else {
            state.burst = 1;
        }

        int stageIdx = combos.nextStage(player.getUniqueId(), now,
                registry.getComboResetTicks(), attrs.comboSize());
        boolean useOffhand = pickHand(player, state, attrs);
        state.armedStage = stageIdx;
        state.armedTick = now;
        state.armedOffhand = useOffhand;

        WeaponAttributes useAttrs = useOffhand
                ? registry.forMaterial(player.getInventory().getItemInOffHand().getType())
                : attrs;
        AttackStage stage = useAttrs.stage(stageIdx);
        if (registry.isTrailFx()) {
            TrailFX.play(player, stage);
        }
        FmmBridge.onSwing(player, useOffhand);

        if (registry.isHoldToSpam()) {
            state.pendingQueued = true;
            state.pendingTick = now;
        }
    }

    // --- Vanilla damage -> arc (never cancelled, never re-dealt) ---

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!registry.isEnabled()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity primary)) return;
        // Our own secondary/synthetic damage re-enters here; leave it alone
        // so EliteMobs and vanilla handle it exactly once.
        if (syntheticTargets.contains(primary.getUniqueId())) return;

        ItemStack mainHand = attacker.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) return;

        WeaponAttributes attrs = registry.forMaterial(mainHand.getType());
        long now = tick(attacker);

        // Suppress chained sweep echoes from our own arc earlier this tick.
        Long lastArc = lastArcTick.get(attacker.getUniqueId());
        if (lastArc != null && now - lastArc < 2 && arcVictims.contains(primary.getUniqueId())) {
            return;
        }

        ComboTracker.State state = combos.state(attacker.getUniqueId());
        int stageIdx;
        boolean useOffhand;
        if (now - state.armedTick <= 10 && state.armedStage >= 0) {
            // Normal path: swing armed the stage a moment ago; consume it.
            stageIdx = state.armedStage;
            useOffhand = state.armedOffhand;
            state.armedStage = -1;
        } else {
            // No swing seen (synthetic damage, sweep, other plugin); advance.
            stageIdx = combos.nextStage(attacker.getUniqueId(), now,
                    registry.getComboResetTicks(), attrs.comboSize());
            useOffhand = pickHand(attacker, state, attrs);
        }

        ItemStack weapon = useOffhand
                ? attacker.getInventory().getItemInOffHand()
                : mainHand;
        WeaponAttributes useAttrs = useOffhand
                ? registry.forMaterial(weapon.getType()) : attrs;
        AttackStage stage = useAttrs.stage(stageIdx);

        double baseDamage = event.getDamage();
        if (useOffhand) {
            baseDamage = scaleOffhandDamage(mainHand, weapon, baseDamage);
        }

        if (stage.upswingTicks() > 0) {
            // Delayed landing: the only path that cancels, since vanilla has
            // no notion of upswing. Defaults keep this at 0 (instant).
            event.setCancelled(true);
            state.pendingQueued = false;
            scheduleUpswing(attacker, primary, useAttrs, stage, baseDamage);
        } else {
            event.setDamage(baseDamage * stage.damageMultiplier());
            dealSecondaries(attacker, primary, useAttrs, stage, baseDamage);
        }
    }

    private void scheduleUpswing(Player attacker, LivingEntity primary,
                                 WeaponAttributes attrs, AttackStage stage,
                                 double baseDamage) {
        UUID attackerId = attacker.getUniqueId();
        UUID primaryId = primary.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(attackerId);
            var primaryEntity = Bukkit.getEntity(primaryId);
            if (p == null || !(primaryEntity instanceof LivingEntity le) || le.isDead()) return;
            guardedDamage(le, baseDamage * stage.damageMultiplier(), p);
            dealSecondaries(p, le, attrs, stage, baseDamage);
            if (registry.isTrailFx()) {
                TrailFX.play(p, stage);
            }
            FmmBridge.onSwing(p, false);
        }, Math.max(1, stage.upswingTicks()));
    }

    /**
     * Damage every arc target except the primary (which flows through the
     * vanilla event untouched). Each call fires a standard damage event so
     * enchant/effect plugins process secondaries normally.
     */
    private void dealSecondaries(Player attacker, LivingEntity primary,
                                 WeaponAttributes attrs, AttackStage stage,
                                 double baseDamage) {
        List<ArcHitbox.ArcHit> hits = ArcHitbox.collect(
                attacker, primary, attrs.rangeBonus(), stage, attrs.hitbox());

        int sweeping = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.SWEEPING_EDGE);

        long now = tick(attacker);
        lastArcTick.put(attacker.getUniqueId(), now);

        for (ArcHitbox.ArcHit hit : hits) {
            LivingEntity target = hit.entity();
            if (target.equals(primary)) continue;
            double amount = baseDamage * stage.damageMultiplier();
            if (sweeping <= 0) {
                amount *= registry.getSweepFalloff();
            }
            arcVictims.add(target.getUniqueId());
            guardedDamage(target, amount, attacker);
        }
    }

    private void guardedDamage(LivingEntity target, double amount, Player attacker) {
        UUID id = target.getUniqueId();
        syntheticTargets.add(id);
        try {
            target.damage(amount, attacker);
        } catch (Exception ignored) {
        } finally {
            syntheticTargets.remove(id);
        }
    }

    // --- Dual wield ---

    private boolean pickHand(Player attacker, ComboTracker.State state, WeaponAttributes mainAttrs) {
        if (!registry.isDualWield()) {
            state.nextIsOffhand = false;
            return false;
        }
        Material main = attacker.getInventory().getItemInMainHand().getType();
        Material off = attacker.getInventory().getItemInOffHand().getType();
        if (mainAttrs.twoHanded()) return false;
        if (!TrailFX.isOneHandedWeapon(main)) return false;
        if (!TrailFX.isOneHandedWeapon(off)) {
            state.nextIsOffhand = false;
            return false;
        }
        if (TrailFX.blocksOffhandUse(off)) {
            state.nextIsOffhand = false;
            return false;
        }
        boolean useOff = state.nextIsOffhand;
        state.nextIsOffhand = !state.nextIsOffhand;
        return useOff;
    }

    static double scaleOffhandDamage(ItemStack main, ItemStack off, double mainDamage) {
        double mainBase = baseDamage(main.getType());
        double offBase = baseDamage(off.getType());
        if (mainBase <= 0) return mainDamage;
        double ratio = offBase / mainBase;
        int sharp = off.getEnchantmentLevel(Enchantment.SHARPNESS);
        double sharpBonus = sharp > 0 ? 1.0 + (sharp - 1) * 0.5 : 0.0;
        return mainDamage * Math.clamp(ratio, 0.3, 1.5) * (sharpBonus > 0 ? (offBase + sharpBonus) / offBase : 1.0);
    }

    static double baseDamage(Material mat) {
        if (mat == null) return 1.0;
        return switch (mat) {
            case WOODEN_SWORD -> 4.0;
            case STONE_SWORD -> 5.0;
            case GOLDEN_SWORD -> 4.0;
            case IRON_SWORD -> 6.0;
            case DIAMOND_SWORD -> 7.0;
            case NETHERITE_SWORD -> 8.0;
            case WOODEN_AXE -> 7.0;
            case STONE_AXE -> 9.0;
            case IRON_AXE -> 9.0;
            case GOLDEN_AXE -> 7.0;
            case DIAMOND_AXE -> 9.0;
            case NETHERITE_AXE -> 10.0;
            case TRIDENT -> 9.0;
            case MACE -> 6.0;
            case DIAMOND_HOE, IRON_HOE -> 2.0;
            default -> {
                String n = mat.name();
                if (n.endsWith("_SWORD")) yield 5.0;
                if (n.endsWith("_AXE")) yield 7.0;
                if (n.equals("STICK") || n.equals("BLAZE_ROD")) yield 2.0;
                yield 1.0;
            }
        };
    }

    // --- Per-weapon reach (extends the vanilla trigger range) ---

    private NamespacedKey reachKey() {
        return new NamespacedKey(plugin, "weapon_reach");
    }

    /** Adds the held weapon's range bonus to entity interaction range. */
    public void applyReach(Player player) {
        AttributeInstance inst;
        try {
            inst = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        } catch (NoSuchFieldError | Exception e) {
            return;
        }
        if (inst == null) return;
        NamespacedKey key = reachKey();
        try {
            inst.removeModifier(key);
        } catch (Exception ignored) {
        }
        if (!registry.isEnabled()) return;
        double bonus = registry.forMaterial(
                player.getInventory().getItemInMainHand().getType()).rangeBonus();
        if (bonus == 0) return;
        try {
            inst.addTransientModifier(new AttributeModifier(key, bonus,
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND));
        } catch (Exception e) {
            GlovedPlugin.logWarning("Could not apply weapon reach: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldChange(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applyReach(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applyReach(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> applyReach(event.getPlayer()));
    }

    // --- Hold to spam (auto-repeat only when really holding) ---

    private void pumpQueuedSwings() {
        if (!registry.isEnabled() || !registry.isHoldToSpam()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            ComboTracker.State state = combos.state(player.getUniqueId());
            if (!state.pendingQueued) continue;
            // Single clicks never auto-repeat: require a real burst (holding).
            if (state.burst < 2) {
                if (tick(player) - state.pendingTick > 20) state.pendingQueued = false;
                continue;
            }
            if (player.getCooledAttackStrength(0.5f) < 1.0f) continue;
            var target = player.getTargetEntity(6);
            if (!(target instanceof LivingEntity le) || le.isDead()) {
                // Nobody in front: drop the queue, don't whiff air forever.
                if (tick(player) - state.pendingTick > 20) {
                    state.pendingQueued = false;
                }
                continue;
            }
            state.pendingQueued = false;
            state.burst = 1;
            ItemStack main = player.getInventory().getItemInMainHand();
            if (main.getType().isAir()) continue;
            WeaponAttributes attrs = registry.forMaterial(main.getType());
            long now = tick(player);
            int stageIdx = combos.nextStage(player.getUniqueId(), now,
                    registry.getComboResetTicks(), attrs.comboSize());
            boolean useOffhand = pickHand(player, state, attrs);
            WeaponAttributes useAttrs = useOffhand
                    ? registry.forMaterial(player.getInventory().getItemInOffHand().getType())
                    : attrs;
            AttackStage stage = useAttrs.stage(stageIdx);
            state.armedStage = -1; // synthetic damage skips the armed path
            double base = baseDamage((useOffhand
                    ? player.getInventory().getItemInOffHand()
                    : main).getType()) + 1.0;
            if (stage.upswingTicks() > 0) {
                scheduleUpswing(player, le, useAttrs, stage, base);
            } else {
                guardedDamage(le, base * stage.damageMultiplier(), player);
                dealSecondaries(player, le, useAttrs, stage, base);
                if (registry.isTrailFx()) {
                    TrailFX.play(player, stage);
                }
                FmmBridge.onSwing(player, useOffhand);
                player.resetCooldown();
            }
        }
    }

    // --- Swing-thru-grass (opt-in) ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!registry.isEnabled() || !registry.isSwingThruGrass()) return;
        if (isZeroHardness(event.getBlock().getType())) {
            Material held = event.getPlayer().getInventory().getItemInMainHand().getType();
            if (TrailFX.isOneHandedWeapon(held)) {
                event.setCancelled(true);
            }
        }
    }

    private static boolean isZeroHardness(Material mat) {
        return switch (mat) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH,
                    DANDELION, POPPY, TORCH, REDSTONE_WIRE, SNOW -> true;
            default -> mat.isAir() || mat.name().endsWith("_BUTTON")
                    || mat.name().endsWith("_PRESSURE_PLATE");
        };
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        combos.clear(event.getPlayer().getUniqueId());
        lastArcTick.remove(event.getPlayer().getUniqueId());
    }

    public WeaponRegistry getRegistry() { return registry; }
    public ComboTracker getCombos() { return combos; }
}
