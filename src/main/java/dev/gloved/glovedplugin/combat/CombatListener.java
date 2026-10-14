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
    private final ThreadLocal<Boolean> syntheticAttack = ThreadLocal.withInitial(() -> false);

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
        List<ArcHitbox.ArcHit> hits = ArcHitbox.collect(player, null, attrs.rangeBonus(), stage, attrs.hitbox());
        GlovedPlugin.logInfo("[BetterCombat] Swing by " + player.getName() + " with " + mainHand.getType() + ", angle=" + attrs.baseAngleDegrees() + ", rangeBonus=" + attrs.rangeBonus() + ", hitsFound=" + hits.size());
        if (hits.isEmpty()) {
            GlovedPlugin.logInfo("[BetterCombat] No arc targets found for " + player.getName());
            return;
        }

        int idx = 0;
        for (ArcHitbox.ArcHit hit : hits) {
            LivingEntity target = hit.entity();
            if (target == null || target.isDead()) {
                GlovedPlugin.logInfo("[BetterCombat] Skipping null/dead target for " + player.getName());
                continue;
            }
            double dist = hit.distance();
            double angle = hit.angleDeg();
            GlovedPlugin.logInfo("[BetterCombat] Attempting attack on " + target.getType() + " id=" + target.getUniqueId() + " dist=" + String.format("%.2f", dist) + " angle=" + String.format("%.1f", angle));
            final LivingEntity t = target;
            final int delay = idx++;
            final AttackStage finalStage = stage;
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
                    player.attack(t);
                    GlovedPlugin.logInfo("[BetterCombat] player.attack called for " + t.getType() + " id=" + t.getUniqueId() + " dmgMult=" + finalStage.damageMultiplier());
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
    }

    private static boolean isZeroHardness(org.bukkit.Material mat) {
        return switch (mat) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH,
                 DANDELION, POPPY, TORCH, REDSTONE_WIRE, SNOW -> true;
            default -> mat.isAir() || mat.name().endsWith("_BUTTON") || mat.name().endsWith("_PRESSURE_PLATE");
        };
    }

    private static boolean isWeaponHeld(Player player) {
        Material mat = player.getInventory().getItemInMainHand().getType();
        if (mat == null || mat.isAir()) return false;
        String name = mat.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || mat == Material.TRIDENT || mat == Material.MACE;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastArcTick.remove(event.getPlayer().getUniqueId());
    }
}
