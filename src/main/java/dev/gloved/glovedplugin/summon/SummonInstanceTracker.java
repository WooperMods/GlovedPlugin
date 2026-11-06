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

    public SummonInstanceTracker(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public void track(UUID playerId, UUID entityId, int lifetimeTicks) {
        activeByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(entityId);
        entityToPlayer.put(entityId, playerId);
        new BukkitRunnable() {
            @Override
            public void run() {
                despawn(entityId);
            }
        }.runTaskLater(plugin, lifetimeTicks);
    }

    public void despawn(UUID entityId) {
        UUID playerId = entityToPlayer.remove(entityId);
        if (playerId != null) {
            Set<UUID> set = activeByPlayer.get(playerId);
            if (set != null) {
                set.remove(entityId);
                if (set.isEmpty()) activeByPlayer.remove(playerId);
            }
        }
        var e = Bukkit.getEntity(entityId);
        if (e instanceof LivingEntity le && !le.isDead()) {
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

    public void removeAllForPlayer(UUID playerId) {
        Set<UUID> set = activeByPlayer.remove(playerId);
        if (set != null) {
            for (UUID eid : set) {
                entityToPlayer.remove(eid);
                var e = Bukkit.getEntity(eid);
                if (e instanceof LivingEntity le && !le.isDead()) {
                    le.remove();
                }
            }
        }
    }

    public void removeAll() {
        for (UUID playerId : new HashSet<>(activeByPlayer.keySet())) {
            removeAllForPlayer(playerId);
        }
    }
}
