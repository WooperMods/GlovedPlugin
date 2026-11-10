package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class SummonListener implements Listener {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private final SummonManager manager;
    private final SummonInstanceTracker tracker;
    private final SummonTargetCoordinator coordinator;
    private final Random rand = new Random();

    public SummonListener(GlovedPlugin plugin, SummonConfig config, SummonManager manager,
                          SummonInstanceTracker tracker, SummonTargetCoordinator coordinator) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.tracker = tracker;
        this.coordinator = coordinator;
    }

    public SummonListener(GlovedPlugin plugin, SummonConfig config, SummonManager manager) {
        this(plugin, config, manager, null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            if (event.getPlayer() == null) return;
            java.util.UUID id = event.getPlayer().getUniqueId();
            try { if (plugin.getSummonProgression() != null) plugin.getSummonProgression().onPlayerQuit(id); } catch (Throwable ignored) {}
            try { if (plugin.getSummonAbilityService() != null) plugin.getSummonAbilityService().onPlayerQuit(id); } catch (Throwable ignored) {}
            if (tracker == null) return;
            if (coordinator != null) coordinator.invalidateOwner(id);
            tracker.removeAllForPlayer(id);
            GlovedPlugin.logDebug("[Summon] Removed minions/state for quitting player " + event.getPlayer().getName());
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[Summon] Error cleaning up minions on quit", t);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        try {
            if (!config.isEnabled()) return;
            if (event.getHand() != EquipmentSlot.HAND) return;
            Action action = event.getAction();
            if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
            if (action == Action.RIGHT_CLICK_BLOCK) {
                var block = event.getClickedBlock();
                if (block != null && block.getType().isInteractable()) {
                    return;
                }
            }
            Player player = event.getPlayer();
            if (player.isSneaking()) return;

            ItemStack item = event.getItem();
            if (item == null || item.getType().isAir()) {
                item = player.getInventory().getItemInMainHand();
            }
            if (item == null || item.getType().isAir()) return;

            String profileName = SummonEnchant.getProfile(item);
            if (profileName == null) return;
            SummonProfile profile = config.getProfile(profileName);
            if (profile == null) {
                SummonEnchant.clearProfile(item);
                return;
            }

            if (!manager.canSummon(player, profile, SummonTrigger.ABILITY)) return;
            int spawned = manager.spawn(player, profile, null);
            if (spawned <= 0) return;
            manager.setCooldown(player, profile, SummonTrigger.ABILITY);

            // Activation feedback (subtle, non-spammy)
            try {
                player.playSound(player.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.35f, 1.7f);
                player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1.0, 0), 10, 0.35, 0.45, 0.35, 0.02);
            } catch (Throwable ignored) {}

            GlovedPlugin.logDebug("[Summon] Right-click ability triggered for " + player.getName() + " profile " + profileName + " spawned=" + spawned);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[Summon] Error in onPlayerInteract (suppressed)", t);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageByEntityEvent event) {
        try {
        if (!config.isEnabled()) {
            GlovedPlugin.logDebug("[Summon Armor] Config disabled");
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        LivingEntity damager = null;
        if (event.getDamager() instanceof LivingEntity le) {
            damager = le;
        } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof LivingEntity le) {
            damager = le;
        }
        if (damager == null) {
            GlovedPlugin.logDebug("[Summon Armor] Damager not living");
            return;
        }
        if (player.isSneaking()) {
            GlovedPlugin.logDebug("[Summon Armor] Player sneaking, abort");
            return;
        }
        // Avoid triggering on own minions
        NamespacedKey mobTypeKey = new NamespacedKey(plugin, "summon_mobtype");
        if (damager.getPersistentDataContainer().has(mobTypeKey, PersistentDataType.STRING)) {
            GlovedPlugin.logDebug("[Summon Armor] Damager is a summoned minion, abort");
            return;
        }

        var inv = player.getInventory();
        ItemStack[] armor = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        Set<String> processed = new HashSet<>();

        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item == null || item.getType().isAir()) continue;
            String profileName = SummonEnchant.getProfile(item);
            if (profileName == null) continue;
            if (processed.contains(profileName)) continue;
            processed.add(profileName);

            SummonProfile profile = config.getProfile(profileName);
            if (profile == null) {
                SummonEnchant.clearProfile(item);
                continue;
            }
            EffectiveSummonStats stats = EffectiveSummonStats.resolve(player, profile, config, plugin.getSummonProgression());
            if (!manager.canSummon(player, profile, SummonTrigger.ARMOR)) {
                GlovedPlugin.logDebug("[Summon Armor] CanSummon false for " + player.getName() + " profile " + profileName);
                continue;
            }
            double roll = rand.nextDouble();
            GlovedPlugin.logDebug("[Summon Armor] Chance roll " + roll + " vs " + stats.chance + " (base " + profile.chance + ", lvl " + stats.summoningLevel + ") for profile " + profileName);
            if (roll > stats.chance) {
                GlovedPlugin.logDebug("[Summon Armor] Chance failed for profile " + profileName);
                continue;
            }
            int spawned = manager.spawn(player, profile, damager);
            if (spawned <= 0) {
                GlovedPlugin.logDebug("[Summon Armor] Spawn blocked (mana/cap/other) for " + player.getName() + " profile " + profileName);
                continue;
            }
            manager.setCooldown(player, profile, SummonTrigger.ARMOR);
            GlovedPlugin.logDebug("[Summon Armor] Summon triggered for " + player.getName() + " profile " + profileName + " from armor slot " + slots[i] + " spawned=" + spawned);
        }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[Summon] Error in onPlayerDamaged (suppressed to avoid kick)", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        try {
            if (event.getEntity() instanceof Player player && event.getDamager() instanceof LivingEntity damager) {
                MinionAI.recordRecentDamager(player, damager);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[Summon] Error in onEntityDamageByEntity (suppressed to avoid kick)", t);
        }
    }
}
