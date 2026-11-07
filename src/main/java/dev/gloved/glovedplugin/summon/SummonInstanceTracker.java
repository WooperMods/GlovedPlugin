package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SummonInstanceTracker {
    private final GlovedPlugin plugin;
    private final Map<UUID, Set<UUID>> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> entityToPlayer = new ConcurrentHashMap<>();
    /** Cached entity handles so target goals can look up minions without world queries. */
    private final Map<UUID, LivingEntity> activeEntities = new ConcurrentHashMap<>();

    public SummonInstanceTracker(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public void track(UUID playerId, LivingEntity entity, int lifetimeTicks) {
        if (entity == null) return;
        UUID entityId = entity.getUniqueId();
        activeByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(entityId);
        entityToPlayer.put(entityId, playerId);
        activeEntities.put(entityId, entity);
        new BukkitRunnable() {
            @Override
            public void run() {
                despawn(entityId);
            }
        }.runTaskLater(plugin, lifetimeTicks);
    }

    public void despawn(UUID entityId) {
        UUID playerId = entityToPlayer.remove(entityId);
        LivingEntity cached = activeEntities.remove(entityId);
        if (playerId != null) {
            Set<UUID> set = activeByPlayer.get(playerId);
            if (set != null) {
                set.remove(entityId);
                if (set.isEmpty()) activeByPlayer.remove(playerId);
            }
        }
        LivingEntity le = cached;
        if (le == null) {
            var e = Bukkit.getEntity(entityId);
            if (e instanceof LivingEntity living) {
                le = living;
            }
        }
        if (le != null && !le.isDead()) {
            le.remove();
        }
    }

    public boolean isSummoned(UUID entityId) {
        return entityToPlayer.containsKey(entityId);
    }

    public UUID getOwner(UUID entityId) {
        return entityToPlayer.get(entityId);
    }

    public int getActiveCount(UUID playerId) {
        Set<UUID> set = activeByPlayer.get(playerId);
        return set == null ? 0 : set.size();
    }

    /** Live view of all tracked summon entities (weakly consistent, no copy). */
    public java.util.Collection<LivingEntity> getActiveMinions() {
        return activeEntities.values();
    }

    public int getTrackedMinionCount() {
        return activeEntities.size();
    }

    public void removeAllForPlayer(UUID playerId) {
        Set<UUID> set = activeByPlayer.remove(playerId);
        if (set != null) {
            for (UUID eid : set) {
                entityToPlayer.remove(eid);
                LivingEntity le = activeEntities.remove(eid);
                if (le == null) {
                    var e = Bukkit.getEntity(eid);
                    if (e instanceof LivingEntity living) {
                        le = living;
                    }
                }
                if (le != null && !le.isDead()) {
                    le.remove();
                }
            }
        }
    }

    public void removeAll() {
        for (UUID playerId : new HashSet<>(activeByPlayer.keySet())) {
            removeAllForPlayer(playerId);
        }
        activeEntities.clear();
        entityToPlayer.clear();
    }
}
