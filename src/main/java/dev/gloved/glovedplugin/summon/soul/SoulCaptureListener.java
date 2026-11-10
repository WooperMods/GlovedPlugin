package dev.gloved.glovedplugin.summon.soul;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

/** Capture dynamic souls on eligible mob kills attributed to a player (direct or via owned minion). */
public class SoulCaptureListener implements Listener {
    private final GlovedPlugin plugin;
    private final SoulService soulService;

    public SoulCaptureListener(GlovedPlugin plugin, SoulService soulService) {
        this.plugin = plugin;
        this.soulService = soulService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        try {
            if (soulService == null) return;
            LivingEntity victim = event.getEntity();
            if (victim == null) return;

            // Never capture tracked summons
            if (isTrackedSummon(victim)) return;

            Player killer = victim.getKiller();
            boolean fromMinion = false;

            if (killer == null) {
                // Try minion-attribution if enabled
                SoulConfig cfg = soulService.soulConfig();
                if (cfg == null || !cfg.isCaptureMinionKills()) return;
                UUID ownerId = resolveMinionKillOwner(victim);
                if (ownerId == null) return;
                killer = plugin.getServer().getPlayer(ownerId);
                if (killer == null || !killer.isOnline()) return;
                fromMinion = true;
            } else {
                // direct player kill: ignore if victim is somehow special handled above
            }

            SoulService.CaptureResult res = soulService.tryCapture(killer, victim, fromMinion);
            if (res.captured()) {
                GlovedPlugin.logDebug("[Souls] Soul captured for " + killer.getName() + ": " + res.reason() + " minionKill=" + fromMinion + " "
                        + (res.soul() != null ? SoulService.describe(res.soul()) : ""));
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Souls] Capture listener error: " + t);
        }
    }

    private boolean isTrackedSummon(Entity entity) {
        if (entity == null) return false;
        try {
            var pdc = entity.getPersistentDataContainer();
            return pdc.has(new NamespacedKey(plugin, "summon_owner"), PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private UUID resolveMinionKillOwner(LivingEntity victim) {
        try {
            EntityDamageEvent last = victim.getLastDamageCause();
            if (!(last instanceof EntityDamageByEntityEvent ede)) return null;
            Entity damager = ede.getDamager();
            LivingEntity source = null;
            if (damager instanceof LivingEntity le) {
                source = le;
            } else if (damager instanceof Projectile proj) {
                ProjectileSource shooter = proj.getShooter();
                if (shooter instanceof LivingEntity le) source = le;
            }
            if (source == null) return null;
            if (!isTrackedSummon(source)) return null;
            String ownerStr = source.getPersistentDataContainer().get(new NamespacedKey(plugin, "summon_owner"), PersistentDataType.STRING);
            if (ownerStr == null) return null;
            return UUID.fromString(ownerStr);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
