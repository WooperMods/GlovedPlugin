package dev.gloved.glovedplugin.summon.entity;

import dev.gloved.glovedplugin.summon.data.SummonSharedData;

import java.util.UUID;

/**
 * Marker/contract for NMS summon entities that participate in the goal-based AI system.
 */
public interface SummonEntity {

    /** Configure goal and target selectors for this summon. */
    void setGoals();

    /** Owner UUID for this summon. */
    UUID getOwnerId();

    /** Shared runtime data (owner, soul, combat mode). */
    SummonSharedData getSharedData();

    /** Bukkit-facing entity id for bridge lookups. */
    UUID getBukkitUniqueId();
}
