package dev.gloved.glovedplugin.summon.spawn;

import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Spawn request/context scaffold for D2. Captures inputs shared by summon spawn entry paths.
 */
public record SummonSpawnRequest(
        Player player,
        SummonProfile profile,
        SoulInstance soul,
        LivingEntity target,
        Location spawnLocation,
        int requestedCount,
        boolean allowReplace
) {
}
