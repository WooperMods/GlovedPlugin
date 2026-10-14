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
 * Server-side arc cleave for Paper 26.3.
 * <p>
 * Primary hit is 100% vanilla: we never cancel or re-deal it.
 * We only add secondary targets within the weapon's angle/range arc.
 * All damage for secondaries uses the same base amount as the primary
 * (event.getDamage()) so enchants/effects from EliteMobs etc. still apply
 * via the standard {@link EntityDamageByEntityEvent} pipeline.
 */
public final class CombatListener implements Listener {

    private final GlovedPlugin plugin;
    private final WeaponRegistry registry;
    private final Map<UUID, Long> lastArcTick = new HashMap<>();
    private final Set<UUID> syntheticTargets = new HashSet<>();

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

    // Swing FX only (no combo)
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        if (!registry.isEnabled()) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        try {
            if (player.isHandRaised() || player.isBlocking()) return;
        } catch (NoSuchMethodError ignored) {
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) return;
        if (registry.isTrailFx()) {
            WeaponAttributes attrs = registry.forMaterial(mainHand.getType());
            // Use a default stage for FX
            AttackStage stage = new AttackStage(1.0, 1.0, attrs.baseAngleDegrees(), 0, "ENTITY_PLAYER_ATTACK_SWEEP", "SWEEP_ATTACK");
            TrailFX.play(player, stage);
        }
        FmmBridge.onSwing(player, false);
    }

    // Primary vanilla hit -> add arc secondaries
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!registry.isEnabled()) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity primary)) return;
        // Skip synthetic secondary hits
        if (syntheticTargets.contains(primary.getUniqueId())) return;

        ItemStack mainHand = attacker.getInventory().getItemInMainHand();
        if (mainHand.getType().isAir()) return;

        WeaponAttributes attrs = registry.forMaterial(mainHand.getType());
        long now = tick(attacker);
        Long lastArc = lastArcTick.get(attacker.getUniqueId());
        if (lastArc != null && now - lastArc < 2) {
            return;
        }

        // Arc secondaries
        List<ArcHitbox.ArcHit> hits = ArcHitbox.collect(
                attacker, primary, attrs.rangeBonus(),
                new AttackStage(1.0, 1.0, attrs.baseAngleDegrees(), 0, "ENTITY_PLAYER_ATTACK_SWEEP", "SWEEP_ATTACK"),
                attrs.hitbox());

        int sweeping = attacker.getInventory().getItemInMainHand()
                .getEnchantmentLevel(Enchantment.SWEEPING_EDGE);
        boolean fullCleave = sweeping > 0;

        lastArcTick.put(attacker.getUniqueId(), now);
        for (ArcHitbox.ArcHit hit : hits) {
            LivingEntity target = hit.entity();
            if (target.equals(primary)) continue;
            double amount = event.getDamage();
            if (!fullCleave) {
                amount *= registry.getSweepFalloff();
            }
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

    // Per-weapon reach
    private NamespacedKey reachKey() {
        return new NamespacedKey(plugin, "weapon_reach");
    }

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

    // Swing-thru-grass
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
        lastArcTick.remove(event.getPlayer().getUniqueId());
    }
}
