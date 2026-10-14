package dev.gloved.glovedplugin.combat;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side Better Combat-lite: arc expansion, combo stages, sweep falloff,
 * dual-wield alternation, hold-to-spam queue, optional upswing delay.
 *
 * <p>Phase 1+2 in one listener (Paper-only, no hard packet/model deps).
 * Visuals are {@link TrailFX} particles + sounds; {@link FmmBridge} adds
 * model animations when FreeMinecraftModels is installed.
 */
public final class CombatListener implements Listener {

    private final GlovedPlugin plugin;
    private final WeaponRegistry registry;
    private final ComboTracker combos = new ComboTracker();
    /** Attacker -> last arc tick, to suppress vanilla sweep double-hits. */
    private final Map<UUID, Long> lastArcTick = new HashMap<>();
    /** Entities hit by our arc this tick (suppress chained sweep events). */
    private final Set<UUID> arcVictims = new HashSet<>();

    public CombatListener(GlovedPlugin plugin, WeaponRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Hold-to-spam pump: fire queued swings once cooldown is ready.
        Bukkit.getScheduler().runTaskTimer(plugin, this::pumpQueuedSwings, 2L, 2L);
    }

    // --- Vanilla damage -> arc ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!registry.isEnabled()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity primary)) return;

        ItemStack mainHand = attacker.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) return;

        WeaponAttributes attrs = registry.forMaterial(mainHand.getType());
        long now = attacker.getWorld().getFullTime();

        // Suppress chained sweep echoes from our own arc earlier this tick.
        Long lastArc = lastArcTick.get(attacker.getUniqueId());
        if (lastArc != null && now - lastArc < 2 && arcVictims.contains(primary.getUniqueId())) {
            return;
        }

        float cooled = attacker.getCooledAttackStrength(0.5f);
        ComboTracker.State state = combos.state(attacker.getUniqueId());
        if (cooled < 0.9f) {
            if (registry.isHoldToSpam()) {
                // Queue for hold-spam instead of dealing a weak hit.
                state.pendingQueued = true;
                state.pendingTick = now;
                event.setCancelled(true);
            }
            return;
        }

        int stageIdx = combos.nextStage(attacker.getUniqueId(), now,
                registry.getComboResetTicks(), attrs.comboSize());

        boolean useOffhand = pickHand(attacker, state, attrs);
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
            event.setCancelled(true);
            scheduleUpswing(attacker, primary, useAttrs, stage, baseDamage, stageIdx);
        } else {
            event.setDamage(primaryDamage(baseDamage, stage));
            applyArc(attacker, primary, useAttrs, stage, baseDamage);
            TrailFX.play(attacker, stage);
            FmmBridge.onSwing(attacker, useOffhand);
        }
    }

    private void scheduleUpswing(Player attacker, LivingEntity primary,
                                 WeaponAttributes attrs, AttackStage stage,
                                 double baseDamage, int stageIdx) {
        UUID attackerId = attacker.getUniqueId();
        UUID primaryId = primary.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(attackerId);
            var primaryEntity = Bukkit.getEntity(primaryId);
            if (p == null || !(primaryEntity instanceof LivingEntity le) || le.isDead()) return;
            try {
                le.damage(primaryDamage(baseDamage, stage), p);
            } catch (Exception ignored) {
            }
            applyArcExtra(p, le, attrs, stage, baseDamage);
            TrailFX.play(p, stage);
            FmmBridge.onSwing(p, false);
        }, Math.max(1, stage.upswingTicks()));
    }

    void applyArc(Player attacker, LivingEntity primary, WeaponAttributes attrs,
                  AttackStage stage, double baseDamage) {
        double rangeBonus = attrs.rangeBonus();
        List<ArcHitbox.ArcHit> hits = ArcHitbox.collect(
                attacker, primary, rangeBonus, stage, attrs.hitbox());

        int sweeping = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.SWEEPING_EDGE);
        boolean fullCleave = sweeping > 0;

        long now = attacker.getWorld().getFullTime();
        lastArcTick.put(attacker.getUniqueId(), now);
        arcVictims.clear();

        boolean first = true;
        for (ArcHitbox.ArcHit hit : hits) {
            LivingEntity target = hit.entity();
            double amount = baseDamage * stage.damageMultiplier();
            if (!first && !fullCleave) {
                amount *= registry.getSweepFalloff();
            }
            arcVictims.add(target.getUniqueId());
            if (first && target.equals(primary)) {
                // Primary reuses the vanilla event's damage pipeline (armor,
                // crits, enchant procs) — just scale it to the combo stage.
                // Event damage already set by caller; for delayed upswing the
                // event was cancelled, so deal directly.
                first = false;
                continue;
            }
            try {
                target.damage(amount, attacker);
            } catch (Exception ignored) {
            }
            first = false;
        }
        // Scale the primary hit on the original event path. The caller sets it
        // after this returns; delayed upswing deals it here instead.
    }

    /** Primary-hit scaling for the live-event path. Called after applyArc. */
    public static double primaryDamage(double baseDamage, AttackStage stage) {
        return baseDamage * stage.damageMultiplier();
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

    // --- Hold to spam ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        if (!registry.isEnabled() || !registry.isHoldToSpam()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        // Mark activity so the pump knows the player is still engaged.
        ComboTracker.State state = combos.state(event.getPlayer().getUniqueId());
        if (event.getPlayer().getCooledAttackStrength(0.5f) < 0.9f) {
            state.pendingQueued = true;
            state.pendingTick = event.getPlayer().getWorld().getFullTime();
        }
    }

    private void pumpQueuedSwings() {
        if (!registry.isEnabled() || !registry.isHoldToSpam()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            ComboTracker.State state = combos.state(player.getUniqueId());
            if (!state.pendingQueued) continue;
            if (player.getCooledAttackStrength(0.5f) < 1.0f) continue;
            var target = player.getTargetEntity((int) 6);
            if (!(target instanceof LivingEntity le) || le.isDead()) {
                // Nobody in front: drop the queue, don't whiff air forever.
                if (player.getWorld().getFullTime() - state.pendingTick > 20) {
                    state.pendingQueued = false;
                }
                continue;
            }
            state.pendingQueued = false;
            ItemStack main = player.getInventory().getItemInMainHand();
            if (main.getType().isAir()) continue;
            WeaponAttributes attrs = registry.forMaterial(main.getType());
            long now = player.getWorld().getFullTime();
            int stageIdx = combos.nextStage(player.getUniqueId(), now,
                    registry.getComboResetTicks(), attrs.comboSize());
            boolean useOffhand = pickHand(player, state, attrs);
            WeaponAttributes useAttrs = useOffhand
                    ? registry.forMaterial(player.getInventory().getItemInOffHand().getType())
                    : attrs;
            AttackStage stage = useAttrs.stage(stageIdx);
            double base = baseDamage((useOffhand
                    ? player.getInventory().getItemInOffHand()
                    : main).getType()) + 1.0;
            if (stage.upswingTicks() > 0) {
                scheduleUpswing(player, le, useAttrs, stage, base, stageIdx);
            } else {
                le.damage(base * stage.damageMultiplier(), player);
                applyArcExtra(player, le, useAttrs, stage, base);
                TrailFX.play(player, stage);
                FmmBridge.onSwing(player, useOffhand);
                player.resetCooldown();
            }
        }
    }

    private void applyArcExtra(Player attacker, LivingEntity primary,
                               WeaponAttributes attrs, AttackStage stage, double baseDamage) {
        // Primary already damaged by caller; hit the rest of the arc.
        List<ArcHitbox.ArcHit> hits = ArcHitbox.collect(
                attacker, primary, attrs.rangeBonus(), stage, attrs.hitbox());
        int sweeping = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.SWEEPING_EDGE);
        boolean first = true;
        for (ArcHitbox.ArcHit hit : hits) {
            if (first && hit.entity().equals(primary)) {
                first = false;
                continue;
            }
            first = false;
            double amount = baseDamage * stage.damageMultiplier();
            if (sweeping <= 0) amount *= registry.getSweepFalloff();
            try {
                hit.entity().damage(amount, attacker);
            } catch (Exception ignored) {
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
