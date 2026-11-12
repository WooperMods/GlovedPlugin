package dev.gloved.glovedplugin.summon.entity;

import org.bukkit.entity.LivingEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links NMS summon entities to Bukkit entities/owners for quick lookup.
 * PDC keys remain the source of truth for persistence; this is a runtime index.
 */
public final class SummonEntityBridge {
    private static final WeakHashMap<SummonEntity, LivingEntity> BY_SUMMON = new WeakHashMap<>();
    private static final Map<UUID, SummonEntity> BY_ENTITY_ID = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> OWNER_BY_ENTITY_ID = new ConcurrentHashMap<>();

    private SummonEntityBridge() {}

    public static synchronized void addHolder(SummonEntity entity, LivingEntity bukkitEntity) {
        if (entity == null || bukkitEntity == null) return;
        BY_SUMMON.put(entity, bukkitEntity);
        BY_ENTITY_ID.put(bukkitEntity.getUniqueId(), entity);
        UUID ownerId = entity.getOwnerId();
        if (ownerId != null) OWNER_BY_ENTITY_ID.put(bukkitEntity.getUniqueId(), ownerId);
    }

    public static SummonEntity getByEntityId(UUID entityId) {
        if (entityId == null) return null;
        return BY_ENTITY_ID.get(entityId);
    }

    public static UUID getOwnerByEntityId(UUID entityId) {
        if (entityId == null) return null;
        return OWNER_BY_ENTITY_ID.get(entityId);
    }

    public static boolean isSummon(LivingEntity entity) {
        return entity != null && BY_ENTITY_ID.containsKey(entity.getUniqueId());
    }

    public static Collection<SummonEntity> getAll() {
        return Collections.unmodifiableCollection(BY_ENTITY_ID.values());
    }

    public static synchronized void removeHolder(SummonEntity entity) {
        if (entity == null) return;
        LivingEntity bukkit = BY_SUMMON.remove(entity);
        if (bukkit != null) {
            BY_ENTITY_ID.remove(bukkit.getUniqueId());
            OWNER_BY_ENTITY_ID.remove(bukkit.getUniqueId());
        } else {
            // best-effort cleanup by id
            UUID id = entity.getBukkitUniqueId();
            if (id != null) {
                BY_ENTITY_ID.remove(id);
                OWNER_BY_ENTITY_ID.remove(id);
            }
        }
    }

    public static synchronized void removeByEntityId(UUID entityId) {
        if (entityId == null) return;
        SummonEntity entity = BY_ENTITY_ID.remove(entityId);
        OWNER_BY_ENTITY_ID.remove(entityId);
        if (entity != null) BY_SUMMON.remove(entity);
    }

    public static synchronized void clear() {
        BY_SUMMON.clear();
        BY_ENTITY_ID.clear();
        OWNER_BY_ENTITY_ID.clear();
    }
}
