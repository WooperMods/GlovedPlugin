package dev.gloved.glovedplugin.combat;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CombatListener implements Listener {

    private final GlovedPlugin plugin;
    private final WeaponRegistry registry;
    private final Map<UUID, Long> lastArcTick = new HashMap<>();
    private final ThreadLocal<Boolean> syntheticAttack = ThreadLocal.withInitial(() -> false);
    /** One queued hold-to-spam swing per player, fired when cooldown recharges. */
    private final Map<UUID, BukkitTask> pendingSpam = new HashMap<>();
    /** Last crosshair entity from PrePlayerAttack, so arc never loses a vanilla hit. */
    private record PrimaryHit(UUID entityId, long atMillis) {}
    private final Map<UUID, PrimaryHit> lastPrimary = new HashMap<>();

    /** Minimum fall distance (blocks) for a mace smash attack, per vanilla. */
    private static final float SMASH_MIN_FALL = 1.5F;

    public CombatListener(GlovedPlugin plugin, WeaponRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyReach(player);
        }
    }

    private static long tick(Player player) {
        return player.getWorld().getFullTime();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreAttack(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
        if (!registry.isEnabled()) return;
        if (Boolean.TRUE.equals(syntheticAttack.get())) return;
        Player player = event.getPlayer();
        if (!isWeaponHeld(player)) return;
        // Remember the crosshair target: the arc below must include it even
        // at the arc edge, otherwise cancelling here would eat a vanilla hit.
        try {
            if (event.getAttacked() != null) {
                lastPrimary.put(player.getUniqueId(),
                        new PrimaryHit(event.getAttacked().getUniqueId(), System.currentTimeMillis()));
            }
        } catch (Exception ignored) {}
        // Cancel vanilla attacks, we handle arc via player.attack ourselves
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttackCooldownReset(PlayerAttackEntityCooldownResetEvent event) {
        if (Boolean.TRUE.equals(syntheticAttack.get())) {
            // Keep attack strength at pre-attack value for synthetic arc hits
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        if (!registry.isEnabled()) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        try {
            if (player.isHandRaised() || player.isBlocking()) return;
        } catch (NoSuchMethodError ignored) {}
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) return;
        if (!isWeaponHeld(player)) return;

        // Vanilla cooldown gate: ignore clicks before attack strength recharges.
        // With hold-to-spam, queue one swing for when the cooldown recharges
        // instead of silently dropping spam-clicks (slow weapons like maces
        // otherwise feel like they "can't attack" while mining/clicking fast).
        float strength = player.getCooledAttackStrength(0.5F);
        if (strength < registry.getMinAttackStrength()) {
            GlovedPlugin.logInfo("[BetterCombat] Swing denied for " + player.getName()
                    + " strength=" + String.format("%.2f", strength)
                    + " required=" + registry.getMinAttackStrength());
            queueHoldToSpam(player);
            return;
        }

        executeArcSwing(player);
    }

    /**
     * Full arc path: smash capture, target collection, FX, synthetic attacks.
     * Called directly from {@link #onSwing} and once per cooldown from the
     * hold-to-spam queue.
     */
    private void executeArcSwing(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir() || !isWeaponHeld(player)) return;

        // Capture smash state synchronously: the synthetic attack below runs a
        // tick later, after landing has already zeroed fall distance, which
        // would silently drop vanilla smash damage and Wind Burst.
        float fallAtSwing = 0.0F;
        try {
            fallAtSwing = player.getFallDistance();
        } catch (Exception e) {
            GlovedPlugin.logWarning("[BetterCombat] could not read fall distance, smash disabled: " + e.getMessage());
        }
        int windBurst = 0;
        int density = 0;
        try {
            var meta = mainHand.getItemMeta();
            if (meta != null) {
                windBurst = meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.WIND_BURST);
                density = meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.DENSITY);
            }
        } catch (Exception e) {
            GlovedPlugin.logWarning("[BetterCombat] could not read mace enchant levels: " + e.getMessage());
        }
        final float capturedFall = fallAtSwing;
        final int capturedWindBurst = windBurst;
        final int capturedDensity = density;
        final boolean smash = capturedFall > SMASH_MIN_FALL;
        boolean glideCheck = false;
        try {
            glideCheck = player.isGliding();
        } catch (Exception ignored) {}
        final boolean gliding = glideCheck;

        // Allow arc attacks in all gamemodes
        // if (player.getGameMode() != org.bukkit.GameMode.SURVIVAL && player.getGameMode() != org.bukkit.GameMode.ADVENTURE) {
        //     return;
        // }

        long now = tick(player);
        UUID id = player.getUniqueId();
        // Throttle removed to allow continuous arc attacks; uncomment to rate limit
        // Long last = lastArcTick.get(id);
        // if (last != null && now - last < 2) return;
        lastArcTick.put(id, now);

        WeaponAttributes attrs = registry.forMaterial(mainHand.getType());
        AttackStage stage = attrs.stage(0);
        double rangeBonus = attrs.rangeBonus();
        if (mainHand.getType() == Material.MACE && (capturedFall > 0.5F || gliding)) {
            rangeBonus += registry.getMaceFallingRangeBonus();
        }
        // Refresh reach so the delayed synthetic attacks inherit arc range.
        applyReach(player);
        LivingEntity primary = resolvePrimary(player);
        List<ArcHitbox.ArcHit> hits = ArcHitbox.collect(player, primary, rangeBonus, stage, attrs.hitbox());
        GlovedPlugin.logInfo("[BetterCombat] Swing by " + player.getName() + " with " + mainHand.getType() + ", angle=" + attrs.baseAngleDegrees() + ", rangeBonus=" + rangeBonus + ", hitsFound=" + hits.size());
        if (hits.isEmpty()) {
            // Whiffs stay quiet like vanilla (no sweep FX while mining grass
            // or swinging at air); log the targeted block to diagnose
            // "can't attack while looking at X" reports.
            String looking = "AIR";
            try {
                var block = player.getTargetBlockExact(6);
                if (block != null) looking = block.getType().name();
            } catch (Exception ignored) {}
            GlovedPlugin.logInfo("[BetterCombat] No arc targets found for " + player.getName() + " looking=" + looking);
            return;
        }

        // FX only on swings that connect: every mining swing used to play
        // sweep particles + sounds, which felt like attack spam on grass/dirt.
        if (registry.isTrailFx()) {
            WeaponAttributes fxAttrs = registry.forMaterial(mainHand.getType());
            if (mainHand.getType() == Material.MACE) {
                TrailFX.playCone(player, fxAttrs.stage(0), smash ? 1.6 : 1.0);
            } else {
                AttackStage fxStage = new AttackStage(1.0, 1.0, fxAttrs.baseAngleDegrees(), 0, "ENTITY_PLAYER_ATTACK_SWEEP", "SWEEP_ATTACK");
                TrailFX.play(player, fxStage);
            }
        }
        FmmBridge.onSwing(player, false);

        int idx = 0;
        boolean first = true;
        for (ArcHitbox.ArcHit hit : hits) {
            LivingEntity target = hit.entity();
            if (target == null || target.isDead()) {
                GlovedPlugin.logInfo("[BetterCombat] Skipping null/dead target for " + player.getName());
                continue;
            }
            if (target instanceof org.bukkit.entity.Player targetPlayer) {
                boolean attackerPvpDisabled = plugin.isPvpDisabled(player);
                boolean targetPvpDisabled = plugin.isPvpDisabled(targetPlayer);
                if (attackerPvpDisabled || targetPvpDisabled) {
                    GlovedPlugin.logInfo("[BetterCombat] Skipping PvP target " + targetPlayer.getName() + " due to PvP toggle");
                    continue;
                }
            }
            double dist = hit.distance();
            double angle = hit.angleDeg();
            GlovedPlugin.logInfo("[BetterCombat] Attempting attack on " + target.getType() + " id=" + target.getUniqueId() + " dist=" + String.format("%.2f", dist) + " angle=" + String.format("%.1f", angle));
            final LivingEntity t = target;
            final int delay = 0;
            final AttackStage finalStage = stage;
            final boolean isFirst = first;
            if (first) first = false;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                syntheticAttack.set(true);
                AttributeInstance att = null;
                AttributeModifier mod = null;
                try {
                    double mult = finalStage.damageMultiplier();
                    // Glide dives never accumulate fall distance, so vanilla
                    // Density sees nothing. Convert dive speed into equivalent
                    // fallen blocks (capped) at the vanilla 0.5/block/level.
                    double densityBonus = 0.0;
                    if (!smash && gliding && capturedDensity > 0) {
                        double vy = 0.0;
                        try {
                            vy = player.getVelocity().getY();
                        } catch (Exception ignored) {}
                        double equivFall = Math.min(15.0, Math.max(0.0, -vy) * 20.0);
                        densityBonus = equivFall * 0.5 * capturedDensity;
                    }
                    if (mult != 1.0 || densityBonus != 0.0) {
                        att = player.getAttribute(Attribute.ATTACK_DAMAGE);
                        if (att != null) {
                            double base = att.getValue();
                            double add = base * (mult - 1.0) + densityBonus;
                            NamespacedKey key = new NamespacedKey(plugin, "arc_damage_mul");
                            att.removeModifier(key);
                            mod = new AttributeModifier(key, add, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND);
                            att.addTransientModifier(mod);
                        }
                    }
                    double baseLog = player.getAttribute(Attribute.ATTACK_DAMAGE) != null ? player.getAttribute(Attribute.ATTACK_DAMAGE).getValue() : -1;
                    GlovedPlugin.logInfo("[BetterCombat] Attack for " + player.getName() + " weapon=" + player.getInventory().getItemInMainHand().getType() + " base=" + baseLog + " mult=" + mult + " first=" + isFirst + " fall=" + String.format("%.1f", capturedFall) + " wb=" + capturedWindBurst + " densBonus=" + String.format("%.1f", densityBonus));
                    if (smash) {
                        // Restore pre-landing fall distance so vanilla sees the
                        // smash: bonus damage plus Wind Burst trigger.
                        try {
                            player.setFallDistance(capturedFall);
                        } catch (Exception ignored) {}
                    }
                    // Force attack strength to full via multiple methods
                    forceFullAttackStrength(player);
                    player.attack(t);
                    if (smash) {
                        // A successful smash negates the fall. Vanilla resets
                        // this itself on smash; enforce it so a landed tick
                        // can't eat the fall after our delayed attack.
                        try {
                            player.setFallDistance(0.0F);
                        } catch (Exception ignored) {}
                        if (capturedWindBurst > 0) {
                            GlovedPlugin.logInfo("[BetterCombat] Wind Burst smash by " + player.getName() + " level=" + capturedWindBurst);
                            // Our extended arc range can land hits outside the
                            // vanilla burst's lift radius, so if the attacker
                            // is still descending, pop them up manually.
                            // Already-rising players (vanilla burst connected)
                            // are left alone to avoid double-launching.
                            try {
                                double vy = player.getVelocity().getY();
                                if (vy <= 0.05) {
                                    double launchV = Math.min(2.2, 0.9 + 0.4 * (capturedWindBurst - 1));
                                    org.bukkit.util.Vector v = player.getVelocity();
                                    player.setVelocity(new org.bukkit.util.Vector(v.getX(), launchV, v.getZ()));
                                    player.getWorld().spawnParticle(
                                            TrailFX.parseParticle("GUST_EMITTER", org.bukkit.Particle.GUST),
                                            player.getLocation().add(0.0, 1.0, 0.0), 1, 0, 0, 0, 0);
                                    player.getWorld().playSound(player.getLocation(),
                                            TrailFX.parseSound("ENTITY_WIND_CHARGE_BURST", org.bukkit.Sound.ENTITY_GENERIC_EXPLODE),
                                            0.8f, 1.2f);
                                    // Vanilla-faithful burst AoE: knock back
                                    // nearby living entities, no damage.
                                    double burstRadius = 3.5 + 0.5 * capturedWindBurst;
                                    double outward = 0.9 + 0.3 * capturedWindBurst;
                                    org.bukkit.Location center = player.getLocation().add(0.0, 1.0, 0.0);
                                    int knocked = 0;
                                    for (org.bukkit.entity.Entity e : player.getWorld().getNearbyEntities(center,
                                            burstRadius, burstRadius / 2 + 1, burstRadius,
                                            en -> en instanceof LivingEntity le && !le.isDead()
                                                    && !le.getUniqueId().equals(player.getUniqueId()))) {
                                        LivingEntity le = (LivingEntity) e;
                                        if (le instanceof org.bukkit.entity.Player other
                                                && (plugin.isPvpDisabled(player) || plugin.isPvpDisabled(other))) {
                                            continue;
                                        }
                                        org.bukkit.util.Vector away = le.getLocation().add(0.0, 0.5, 0.0)
                                                .toVector().subtract(center.toVector());
                                        if (away.lengthSquared() < 0.0001) {
                                            away = new org.bukkit.util.Vector(0, 1, 0);
                                        } else {
                                            away.normalize();
                                        }
                                        org.bukkit.util.Vector cur = le.getVelocity();
                                        org.bukkit.util.Vector kb = cur.add(away.multiply(outward));
                                        kb.setY(kb.getY() + 1.0);
                                        try {
                                            le.setVelocity(kb);
                                            knocked++;
                                        } catch (Exception ignored) {}
                                    }
                                    GlovedPlugin.logInfo("[BetterCombat] Wind Burst assist pop for " + player.getName() + " knocked=" + knocked);
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    if (!smash && gliding && capturedWindBurst > 0
                            && mainHand.getType() == Material.MACE) {
                        // Elytra glide: vanilla never registers a smash while
                        // gliding, so replicate the burst manually. Glide is
                        // kept, horizontal momentum preserved, Y popped up.
                        try {
                            double launchV = Math.min(2.0, 0.9 + 0.4 * (capturedWindBurst - 1));
                            org.bukkit.util.Vector v = player.getVelocity();
                            player.setVelocity(new org.bukkit.util.Vector(v.getX(), launchV, v.getZ()));
                        } catch (Exception ignored) {}
                        try {
                            player.getWorld().spawnParticle(
                                    TrailFX.parseParticle("GUST_EMITTER", org.bukkit.Particle.GUST),
                                    player.getLocation().add(0.0, 1.0, 0.0), 1, 0, 0, 0, 0);
                            player.getWorld().playSound(player.getLocation(),
                                    TrailFX.parseSound("ENTITY_WIND_CHARGE_BURST", org.bukkit.Sound.ENTITY_GENERIC_EXPLODE),
                                    0.8f, 1.2f);
                        } catch (Exception ignored) {}
                        GlovedPlugin.logInfo("[BetterCombat] Elytra Wind Burst by " + player.getName() + " level=" + capturedWindBurst);
                    }
                    GlovedPlugin.logInfo("[BetterCombat] player.attack called for " + t.getType() + " id=" + t.getUniqueId());
                } catch (Exception e) {
                    GlovedPlugin.logWarning("[BetterCombat] attack failed for " + t.getType() + ": " + e.getMessage());
                } finally {
                    if (att != null && mod != null) {
                        att.removeModifier(mod.getKey());
                    }
                    syntheticAttack.set(false);
                }
            }, delay);
        }
    }

    /**
     * Crosshair entity from the most recent vanilla attack packet, if fresh
     * and plausible. Guarantees the entity vanilla would have hit is always
     * in the arc, even at the arc edge (e.g. looking down past a mob).
     */
    private LivingEntity resolvePrimary(Player player) {
        PrimaryHit ph = lastPrimary.get(player.getUniqueId());
        if (ph == null || System.currentTimeMillis() - ph.atMillis() > 750) return null;
        try {
            var entity = Bukkit.getEntity(ph.entityId());
            if (entity instanceof LivingEntity le && !le.isDead()
                    && le.getWorld().equals(player.getWorld())
                    && le.getLocation().distanceSquared(player.getLocation()) < 100.0) {
                return le;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Hold-to-spam: schedule a single arc swing for when the cooldown
     * recharges. At most one pending per player; the task re-checks weapon
     * and strength at fire time and drops otherwise (no chains, no bypass).
     */
    private void queueHoldToSpam(Player player) {
        if (!registry.isHoldToSpam()) return;
        UUID id = player.getUniqueId();
        if (pendingSpam.containsKey(id)) return;
        double period;
        float strength;
        try {
            period = player.getCooldownPeriod();
            strength = player.getCooledAttackStrength(0.5F);
        } catch (Exception e) {
            return;
        }
        if (period <= 0) period = 20.0;
        double need = Math.max(0.0, registry.getMinAttackStrength() - strength);
        long delay = Math.max(1L, (long) Math.ceil(need * period) + 1L);
        try {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pendingSpam.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) return;
                if (!registry.isEnabled()) return;
                ItemStack held = p.getInventory().getItemInMainHand();
                if (held.getType().isAir() || !isWeaponHeld(p)) return;
                float s;
                try {
                    s = p.getCooledAttackStrength(0.5F);
                } catch (Exception e) {
                    return;
                }
                if (s < registry.getMinAttackStrength()) return;
                executeArcSwing(p);
            }, delay);
            pendingSpam.put(id, task);
        } catch (Exception ignored) {}
    }

    private NamespacedKey reachKey() {
        return new NamespacedKey(plugin, "weapon_reach");
    }

    public void applyReach(Player player) {
        AttributeInstance inst;
        try {
            inst = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        } catch (Exception e) { return; }
        if (inst == null) return;
        NamespacedKey key = reachKey();
        try { inst.removeModifier(key); } catch (Exception ignored) {}
        if (!registry.isEnabled()) return;
        double bonus = effectiveRangeBonus(player);
        if (bonus == 0) return;
        try {
            inst.addTransientModifier(new AttributeModifier(key, bonus, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND));
        } catch (Exception e) {
            GlovedPlugin.logWarning("Could not apply weapon reach: " + e.getMessage());
        }
    }

    /**
     * Arc reach bonus for the held weapon. Mace gains the falling bonus while
     * falling or elytra gliding so the synthetic attack reach matches the arc.
     */
    private double effectiveRangeBonus(Player player) {
        Material held;
        try {
            held = player.getInventory().getItemInMainHand().getType();
        } catch (Exception e) {
            return 0.0;
        }
        double bonus = registry.forMaterial(held).rangeBonus();
        if (held == Material.MACE) {
            try {
                if (player.getFallDistance() > 0.5F || player.isGliding()) {
                    bonus += registry.getMaceFallingRangeBonus();
                }
            } catch (Exception ignored) {}
        }
        return bonus;
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!registry.isEnabled()) return;
        if (isMiningSuppressed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!registry.isEnabled()) return;
        // Safety net for instant-break blocks and chain-break plugins.
        if (isMiningSuppressed(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * No mining with weapons: holding a weapon suppresses block damage and
     * breaks. Sneaking re-enables mining (crouch bypass); creative and
     * spectator are never suppressed; non-weapons (pickaxes, shovels) mine
     * freely so tools keep working.
     */
    private boolean isMiningSuppressed(Player player) {
        try {
            org.bukkit.GameMode mode = player.getGameMode();
            if (mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR) return false;
        } catch (Exception ignored) {}
        if (!registry.isSuppressMiningWithWeapons()) return false;
        if (registry.isSneakBypass()) {
            try {
                if (player.isSneaking()) return false;
            } catch (Exception ignored) {}
        }
        try {
            return isWeaponHeld(player);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWeaponHeld(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return false;
        Material mat = item.getType();
        String name = mat.name();
        if (name.endsWith("_SWORD") || name.endsWith("_AXE") || mat == Material.TRIDENT || mat == Material.MACE) {
            return true;
        }
        // Support EliteMobs and other custom weapons via ATTACK_DAMAGE attribute
        try {
            var meta = item.getItemMeta();
            if (meta != null) {
                var attrs = meta.getAttributeModifiers();
                if (attrs != null) {
                    for (var entry : attrs.asMap().entrySet()) {
                        if (entry.getKey() == org.bukkit.attribute.Attribute.ATTACK_DAMAGE && !entry.getValue().isEmpty()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void forceFullAttackStrength(Player player) {
        // Try Paper API first
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("setCooledAttackStrength", float.class);
            m.invoke(player, 1.0f);
            GlovedPlugin.logInfo("[BetterCombat] setCooledAttackStrength used");
            return;
        } catch (Exception ignored) {}
        // Fallback to NMS handle
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            java.lang.reflect.Method getHandle = craftPlayerClass.getMethod("getHandle");
            Object handle = getHandle.invoke(player);
            int cooldownPeriod = (int) player.getCooldownPeriod();
            // Search for attackStrengthTicker in class hierarchy
            Class<?> cls = handle.getClass();
            java.lang.reflect.Field f = null;
            while (cls != null) {
                try {
                    f = cls.getDeclaredField("attackStrengthTicker");
                    break;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            if (f != null) {
                f.setAccessible(true);
                f.setInt(handle, cooldownPeriod);
                GlovedPlugin.logInfo("[BetterCombat] attackStrengthTicker set to " + cooldownPeriod);
            } else {
                GlovedPlugin.logInfo("[BetterCombat] attackStrengthTicker field not found");
            }
        } catch (Exception e) {
            GlovedPlugin.logInfo("[BetterCombat] forceFullAttackStrength failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastArcTick.remove(event.getPlayer().getUniqueId());
        lastPrimary.remove(event.getPlayer().getUniqueId());
        BukkitTask task = pendingSpam.remove(event.getPlayer().getUniqueId());
        if (task != null) {
            try { task.cancel(); } catch (Exception ignored) {}
        }
    }
}
