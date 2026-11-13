package dev.gloved.glovedplugin.summon.runtime;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Level;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.ai.EngagedOwnerTargetGoal;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;

/**
 * Summon event listener: cleanup, owner-damage protection, and enemy engagement targeting.
 */
public class SummonListener implements Listener {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;

    public SummonListener(GlovedPlugin plugin, SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            if (event.getPlayer() == null) return;
            UUID id = event.getPlayer().getUniqueId();
            try { if (plugin.getSummonProgression() != null) plugin.getSummonProgression().onPlayerQuit(id); } catch (Throwable ignored) {}
            try { if (plugin.getSummonAbilityService() != null) plugin.getSummonAbilityService().onPlayerQuit(id); } catch (Throwable ignored) {}
            try { if (plugin.getSoulService() != null) plugin.getSoulService().onPlayerQuit(id); } catch (Throwable ignored) {}
            if (tracker == null) return;
            tracker.removeAllForPlayer(id);
            GlovedPlugin.logDebug("[Summon] Removed minions/state for quitting player " + event.getPlayer().getName());
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Summon] Error cleaning up minions on quit", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        try {
            LivingEntity damager = resolveDamager(event.getDamager());

            // Minions are not damageable by their owner, and cannot be damaged by other players
            // (or their minions) when PvP is disabled between the parties or in non-PvP zones.
            if (tracker != null && event.getEntity() instanceof LivingEntity victim) {
                UUID ownerId = tracker.getOwner(victim.getUniqueId());
                if (ownerId != null) {
                    if (damager instanceof Player damagerPlayer) {
                        // Owner can't damage their own minion
                        if (damagerPlayer.getUniqueId().equals(ownerId)) {
                            event.setCancelled(true);
                            return;
                        }
                        // Other players can't damage this minion in non-PvP contexts
                        if (CombatTargetRules.isProtected(plugin, damagerPlayer, victim)) {
                            event.setCancelled(true);
                            return;
                        }
                    } else if (damager != null) {
                        // Damage caused by another summoned minion: apply the same PvP/zone rules
                        UUID damagerOwnerId = tracker.getOwner(damager.getUniqueId());
                        if (damagerOwnerId != null && !damagerOwnerId.equals(ownerId)) {
                            Player damagerOwner = Bukkit.getPlayer(damagerOwnerId);
                            if (damagerOwner == null
                                    || CombatTargetRules.isProtected(plugin, damagerOwner, victim)) {
                                event.setCancelled(true);
                                return;
                            }
                        }
                    }
                }
            }

            // Damage reduction for summoned minions: owner's toughness + soul TENACIOUS affix + BULWARK trait
            if (tracker != null && event.getEntity() instanceof LivingEntity victim
                    && tracker.isSummoned(victim.getUniqueId())) {
                double totalReduction = 0.0;

                // Owner's AuraSkills Toughness stat
                var summonConfig = plugin.getSummonConfig();
                if (summonConfig != null && summonConfig.isToughnessDamageReductionEnabled()) {
                    UUID ownerId = tracker.getOwner(victim.getUniqueId());
                    if (ownerId != null) {
                        var stats = plugin.getSummonProgression().resolveOwnerCombatStats(ownerId);
                        if (stats.available() && stats.toughnessStatLevel() > 0.0) {
                            double reduction = Math.min(
                                    stats.toughnessStatLevel() * summonConfig.getToughnessDamageReductionPerPoint(),
                                    summonConfig.getToughnessDamageReductionMaxBonus()
                            );
                            if (reduction > 0.0) totalReduction += reduction;
                        }
                    }
                }

                // TENACIOUS soul affix (damage reduction/defense) + BULWARK trait (tanky buff)
                try {
                    String soulIdStr = victim.getPersistentDataContainer()
                            .get(new org.bukkit.NamespacedKey(plugin, "summon_soul_id"),
                                    org.bukkit.persistence.PersistentDataType.STRING);
                    if (soulIdStr != null && !soulIdStr.isBlank()) {
                        UUID soulId = UUID.fromString(soulIdStr);
                        var soulService = plugin.getSoulService();
                        if (soulService != null) {
                            double tenacity = Math.max(0.0, Math.min(0.6, soulService.getSnapshot(soulId).tenacityBonus()));
                            if (tenacity > 0.0) totalReduction += tenacity;
                            var soul = soulService.getSoulCached(soulId);
                            if (soul != null && soul.hasTrait(SoulTrait.BULWARK)) {
                                totalReduction += 0.10; // Bulwark: defensive buff (10% damage reduction)
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                if (totalReduction > 0.0) {
                    double cappedReduction = Math.max(0.0, Math.min(0.8, totalReduction));
                    event.setDamage(event.getDamage() * (1.0 - cappedReduction));
                }
            }

            // Track who damages minions so they can prioritize hitting back
            if (tracker != null && event.getEntity() instanceof LivingEntity victim
                    && tracker.isSummoned(victim.getUniqueId()) && damager != null) {
                SummonCombatService.recordRecentDamager(victim, damager);
            }

            // Track recent damagers for minion AI and engagement
            if (event.getEntity() instanceof Player player) {
                if (damager != null) SummonCombatService.recordRecentDamager(player, damager);
                if (damager instanceof Mob mob) {
                    ensureEngagedTargetGoal(mob, player.getUniqueId());
                }
            }

            // Player damaging a mob while having summons => engaged
            if (damager instanceof Player player && event.getEntity() instanceof Mob mob && tracker != null) {
                if (!tracker.isSummoned(mob.getUniqueId()) && !tracker.getActiveMinions(player.getUniqueId()).isEmpty()) {
                    ensureEngagedTargetGoal(mob, player.getUniqueId());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Summon] Error in onEntityDamageByEntity (suppressed)", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        try {
            if (tracker == null) return;
            if (!(event.getEntity() instanceof Mob mob)) return;
            if (tracker.isSummoned(mob.getUniqueId())) return;
            if (event.getTarget() instanceof Player player) {
                if (!tracker.getActiveMinions(player.getUniqueId()).isEmpty()) {
                    ensureEngagedTargetGoal(mob, player.getUniqueId());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Summon] Error in onEntityTarget (suppressed)", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        try {
            if (tracker == null) return;
            LivingEntity entity = event.getEntity();
            if (tracker.isSummoned(entity.getUniqueId())) {
                UUID owner = tracker.getOwner(entity.getUniqueId());
                tracker.despawn(entity.getUniqueId());
                GlovedPlugin.logDebug("[Summon] Minion died and was cleaned up: " + entity.getUniqueId());
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Summon] Error in onEntityDeath (suppressed)", t);
        }
    }

    private void ensureEngagedTargetGoal(Mob mob, UUID ownerId) {
        if (mob == null || ownerId == null || tracker == null) return;
        try {
            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline()) return;
            if (tracker.isSummoned(mob.getUniqueId())) return;
            if (tracker.getActiveMinions(ownerId).isEmpty()) return;
            MobGoals goals = plugin.getServer().getMobGoals();
            if (goals == null) return;
            // Avoid duplicate registration by checking existing goals' key name
            for (var g : goals.getAllGoals(mob)) {
                try {
                    if (g != null && g.getKey() != null && g.getKey().getNamespacedKey() != null
                            && "engaged_target".equals(g.getKey().getNamespacedKey().getKey())) {
                        return;
                    }
                } catch (Throwable ignored) {}
            }
            goals.addGoal(mob, 2, new EngagedOwnerTargetGoal(plugin, tracker, mob, ownerId));
            GlovedPlugin.logDebug("[Summon] Added engaged target goal to " + mob.getType() + " for owner " + ownerId);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Failed to add engaged target goal: " + t);
        }
    }

    private static LivingEntity resolveDamager(org.bukkit.entity.Entity damager) {
        if (damager instanceof LivingEntity le) return le;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof LivingEntity le) return le;
        return null;
    }
}
