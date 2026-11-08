package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
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
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> despawnTasks = new ConcurrentHashMap<>();

    public SummonInstanceTracker(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public void track(UUID playerId, LivingEntity entity, int lifetimeTicks) {
        if (entity == null) return;
        UUID entityId = entity.getUniqueId();
        activeByPlayer.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(entityId);
        entityToPlayer.put(entityId, playerId);
        activeEntities.put(entityId, entity);
        resetTimer(entityId, lifetimeTicks);
    }

    public void despawn(UUID entityId) {
        UUID playerId = entityToPlayer.remove(entityId);
        LivingEntity cached = activeEntities.remove(entityId);
        org.bukkit.scheduler.BukkitTask task = despawnTasks.remove(entityId);
        if (task != null) task.cancel();
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

    public void resetTimer(UUID entityId, int lifetimeTicks) {
        org.bukkit.scheduler.BukkitTask existing = despawnTasks.get(entityId);
        if (existing != null) {
            existing.cancel();
        }
        org.bukkit.scheduler.BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                despawn(entityId);
            }
        }.runTaskLater(plugin, lifetimeTicks);
        despawnTasks.put(entityId, task);
    }

    public void resetTimersForPlayerAndType(UUID playerId, String mobType, int lifetimeTicks) {
        Set<UUID> set = activeByPlayer.get(playerId);
        if (set == null) return;
        NamespacedKey mobTypeKey = new NamespacedKey(plugin, "summon_mobtype");
        for (UUID entityId : new HashSet<>(set)) {
            LivingEntity le = activeEntities.get(entityId);
            if (le == null) {
                var e = Bukkit.getEntity(entityId);
                if (e instanceof LivingEntity living) {
                    le = living;
                }
            }
            if (le == null) continue;
            String type = le.getPersistentDataContainer().get(mobTypeKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (type != null && type.equalsIgnoreCase(mobType)) {
                resetTimer(entityId, lifetimeTicks);
            }
        }
    }

    public boolean isSummoned(UUID entityId) {
        return entityToPlayer.containsKey(entityId);
    }

    public UUID getOwner(UUID entityId) {
        return entityToPlayer.get(entityId);
    }

    /**
     * Number of live minions the player has from a specific profile.
     * Dead entities are pruned so they stop counting toward the cap.
     */
    public int getActiveCount(UUID playerId, String profileName) {
        Set<UUID> set = activeByPlayer.get(playerId);
        if (set == null) return 0;
        NamespacedKey profileKey = new NamespacedKey(plugin, "summon_profile");
        int count = 0;
        for (UUID entityId : new HashSet<>(set)) {
            LivingEntity le = activeEntities.get(entityId);
            if (le == null) {
                var e = Bukkit.getEntity(entityId);
                if (e instanceof LivingEntity living) {
                    le = living;
                    activeEntities.put(entityId, living);
                }
            }
            if (le == null || le.isDead()) {
                despawn(entityId);
                continue;
            }
            String profile = le.getPersistentDataContainer().get(profileKey, org.bukkit.persistence.PersistentDataType.STRING);
            if (profile != null && profile.equals(profileName)) {
                count++;
            }
        }
        return count;
    }

    /** Live view of all tracked summon entities (weakly consistent, no copy). */
    public java.util.Collection<LivingEntity> getActiveMinions() {
        return activeEntities.values();
    }

    /** Live snapshot of a player's active tracked minions (dead entities pruned). */
    public List<LivingEntity> getActiveMinions(UUID playerId) {
        Set<UUID> set = activeByPlayer.get(playerId);
        if (set == null || set.isEmpty()) return List.of();
        List<LivingEntity> out = new ArrayList<>();
        for (UUID entityId : new ArrayList<>(set)) {
            LivingEntity le = activeEntities.get(entityId);
            if (le == null) {
                var e = Bukkit.getEntity(entityId);
                if (e instanceof LivingEntity living) {
                    le = living;
                    activeEntities.put(entityId, living);
                }
            }
            if (le == null || le.isDead()) {
                despawn(entityId);
                continue;
            }
            out.add(le);
        }
        return out;
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
