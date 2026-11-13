package dev.gloved.glovedplugin.summon.nms.api;

import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.Set;

/**
 * Version-specific NMS integration for spawning and controlling summon entities.
 */
public interface SummonNMS {

    Set<EntityType> getSupportedEntities();

    default boolean canSpawn(EntityType entityType) {
        return entityType != null && getSupportedEntities().contains(entityType);
    }

    /**
     * Spawn an NMS summon entity, configure its goals, and return the Bukkit entity.
     */
    LivingEntity spawnSummon(SummonProfile profile, SummonSharedData sharedData, Location location);

    void setSaddle(LivingEntity entity);

    void sneak(LivingEntity entity, boolean value);

    boolean hasNavigationPath(LivingEntity entity);
}
