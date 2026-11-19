package dev.gloved.glovedplugin.summon.soul.advancement;

import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import org.bukkit.entity.Player;

/** Behavior hooks for a soul advancement definition. All methods are no-op by default. */
public interface SoulAdvancementHooks {
    SoulAdvancementHooks NONE = new SoulAdvancementHooks() {};

    default void onPlayerJoin(SoulAdvancementService service, Player player) {}

    default void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {}

    default void onGroupActivated(SoulAdvancementService service, Player player, int totalActivations) {}

    default void onSoulRespawnCompleted(SoulAdvancementService service, Player player, int totalRespawns) {}

    default void onMasteryLevelReached(SoulAdvancementService service, Player player, int level) {}

    default void onCodexMasteredReached(SoulAdvancementService service, Player player) {}

    default void onSoulTableActionSucceeded(SoulAdvancementService service, Player player, SoulTableAction action, int totalActions, int actionTotal) {}
}
