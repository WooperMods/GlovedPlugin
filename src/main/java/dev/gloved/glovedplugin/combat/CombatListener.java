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
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CombatListener implements Listener {

    private final GlovedPlugin plugin;
    private final WeaponRegistry registry;
    private final Map<UUID, Long> lastArcTick = new HashMap<>();
    private final Map<UUID, Long> miningPlayers = new HashMap<>();
    private final ThreadLocal<Boolean> syntheticAttack = ThreadLocal.withInitial(() -> false);

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
        // Suppress arc attacks during active block mining to avoid spam
        Long miningAt = miningPlayers.get(player.getUniqueId());
        if (miningAt != null && System.currentTimeMillis() - miningAt < 250) {
            return;
        }

        // Vanilla cooldown gate: ignore clicks before attack strength recharges.
        // No FX and no damage, but log the denial so a stuck gate is visible.
        float strength = player.getCooledAttackStrength(0.5F);
        if (strength < registry.getMinAttackStrength()) {
            GlovedPlugin.logInfo("[BetterCombat] Swing denied for " + player.getName()
                    + " strength=" + String.format("%.2f", strength)
                    + " required=" + registry.getMinAttackStrength());
            return;
        }

        if (registry.isTrailFx()) {
            WeaponAttributes attrs = registry.forMaterial(mainHand.getType());
            AttackStage stage = new AttackStage(1.0, 1.0, attrs.baseAngleDegrees(), 0, "ENTITY_PLAYER_ATTACK_SWEEP", "SWEEP_ATTACK");
            TrailFX.play(player, stage);
        }
        FmmBridge.onSwing(player, false);

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
        try {
            var meta = mainHand.getItemMeta();
            windBurst = meta != null
                    ? meta.getEnchantLevel(org.bukkit.enchantments.Enchantment.WIND_BURST) : 0;
        } catch (Exception e) {
            GlovedPlugin.logWarning("[BetterCombat] could not read Wind Burst level: " + e.getMessage());
        }
        final float capturedFall = fallAtSwing;
        final int capturedWindBurst = windBurst;
        final boolean smash = capturedFall > SMASH_MIN_FALL;
        List<ArcHitbox.ArcHit> hits = ArcHitbox.collect(player, null, attrs.rangeBonus(), stage, attrs.hitbox());
        GlovedPlugin.logInfo("[BetterCombat] Swing by " + player.getName() + " with " + mainHand.getType() + ", angle=" + attrs.baseAngleDegrees() + ", rangeBonus=" + attrs.rangeBonus() + ", hitsFound=" + hits.size());
        if (hits.isEmpty()) {
            GlovedPlugin.logInfo("[BetterCombat] No arc targets found for " + player.getName());
            return;
        }

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
                    if (mult != 1.0) {
                        att = player.getAttribute(Attribute.ATTACK_DAMAGE);
                        if (att != null) {
                            double base = att.getValue();
                            double add = base * (mult - 1.0);
                            NamespacedKey key = new NamespacedKey(plugin, "arc_damage_mul");
                            att.removeModifier(key);
                            mod = new AttributeModifier(key, add, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND);
                            att.addTransientModifier(mod);
                        }
                    }
                    double baseLog = player.getAttribute(Attribute.ATTACK_DAMAGE) != null ? player.getAttribute(Attribute.ATTACK_DAMAGE).getValue() : -1;
                    GlovedPlugin.logInfo("[BetterCombat] Attack for " + player.getName() + " weapon=" + player.getInventory().getItemInMainHand().getType() + " base=" + baseLog + " mult=" + mult + " first=" + isFirst + " fall=" + String.format("%.1f", capturedFall) + " wb=" + capturedWindBurst);
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
                        }
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
        double bonus = registry.forMaterial(player.getInventory().getItemInMainHand().getType()).rangeBonus();
        if (bonus == 0) return;
        try {
            inst.addTransientModifier(new AttributeModifier(key, bonus, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND));
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (!registry.isEnabled() || !registry.isSwingThruGrass()) return;
        if (isZeroHardness(event.getBlock().getType())) {
            if (TrailFX.isOneHandedWeapon(event.getPlayer().getInventory().getItemInMainHand().getType())) {
                event.setCancelled(true);
            }
        }
        // Track mining to suppress arc attacks during block break
        Player p = event.getPlayer();
        miningPlayers.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            Player p = event.getPlayer();
            miningPlayers.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    private static boolean isZeroHardness(org.bukkit.Material mat) {
        return switch (mat) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH,
                 DANDELION, POPPY, TORCH, REDSTONE_WIRE, SNOW -> true;
            default -> mat.isAir() || mat.name().endsWith("_BUTTON") || mat.name().endsWith("_PRESSURE_PLATE");
        };
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
    }
}
