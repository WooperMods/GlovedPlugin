package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.GlovedPlugin;
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

/**
 * Summon event listener: cleanup, owner-damage protection, and enemy engagement targeting.
 */
public class SummonListener implements Listener {
    private final GlovedPlugin plugin;
    @SuppressWarnings("unused") private final SummonConfig config;
    @SuppressWarnings("unused") private final SummonManager manager;
    private final SummonInstanceTracker tracker;

    public SummonListener(GlovedPlugin plugin, SummonConfig config, SummonManager manager,
                          SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.tracker = tracker;
    }

    public SummonListener(GlovedPlugin plugin, SummonConfig config, SummonManager manager) {
        this(plugin, config, manager, null);
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

            // Minions are not damageable by their owner
            if (tracker != null && event.getEntity() instanceof LivingEntity victim) {
                UUID ownerId = tracker.getOwner(victim.getUniqueId());
                if (ownerId != null && damager instanceof Player owner && owner.getUniqueId().equals(ownerId)) {
                    event.setCancelled(true);
                    return;
                }
            }

            // Track who damages minions so they can prioritize hitting back
            if (tracker != null && event.getEntity() instanceof LivingEntity victim
                    && tracker.isSummoned(victim.getUniqueId()) && damager != null) {
                MinionAI.recordRecentDamager(victim, damager);
            }

            // Track recent damagers for minion AI and engagement
            if (event.getEntity() instanceof Player player) {
                if (damager != null) MinionAI.recordRecentDamager(player, damager);
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
