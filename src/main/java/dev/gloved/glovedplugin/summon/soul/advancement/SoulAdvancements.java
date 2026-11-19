package dev.gloved.glovedplugin.summon.soul.advancement;

import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import org.bukkit.entity.Player;

/** Central module defining built-in Soul advancements and their hook behavior. */
public final class SoulAdvancements {
    private SoulAdvancements() {}

    public static void registerAll(SoulAdvancementRegistry registry) {
        if (registry == null) return;

        // Essence rewards are one-time (advancements can only be completed once).
        registry.register(def(SoulAdvancementKeys.ROOT, 0, 50, new SoulAdvancementHooks() {
            @Override
            public void onPlayerJoin(SoulAdvancementService service, Player player) {
                service.grant(player, self(registry, SoulAdvancementKeys.ROOT));
            }
        }));

        // --- Acquisition milestones (totals are incremented by service before dispatch) ---
        registry.register(def(SoulAdvancementKeys.CAPTURE_FIRST, 10, 100, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_FIRST));
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_MYTHIC, 11, 500, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (soul != null && soul.getRarity() == SoulRarity.MYTHIC) {
                    service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_MYTHIC));
                }
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_10, 12, 150, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (totalAcquired >= 10) service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_10));
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_50, 13, 400, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (totalAcquired >= 50) service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_50));
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_100, 14, 1000, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (totalAcquired >= 100) service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_100));
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_EPIC, 15, 200, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (soul != null && soul.getRarity() == SoulRarity.EPIC) {
                    service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_EPIC));
                }
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_LEGENDARY, 16, 350, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (soul != null && soul.getRarity() == SoulRarity.LEGENDARY) {
                    service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_LEGENDARY));
                }
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_250, 17, 1500, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (totalAcquired >= 250) service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_250));
            }
        }));
        registry.register(def(SoulAdvancementKeys.CAPTURE_500, 18, 3000, new SoulAdvancementHooks() {
            @Override
            public void onSoulAcquired(SoulAdvancementService service, Player player, SoulInstance soul, int totalAcquired) {
                if (totalAcquired >= 500) service.grant(player, self(registry, SoulAdvancementKeys.CAPTURE_500));
            }
        }));

        // --- Group activation ---
        registry.register(def(SoulAdvancementKeys.GROUP_ACTIVATE, 20, 100, new SoulAdvancementHooks() {
            @Override
            public void onGroupActivated(SoulAdvancementService service, Player player, int totalActivations) {
                if (totalActivations >= 1) service.grant(player, self(registry, SoulAdvancementKeys.GROUP_ACTIVATE));
            }
        }));
        registry.register(def(SoulAdvancementKeys.GROUP_10, 21, 250, new SoulAdvancementHooks() {
            @Override
            public void onGroupActivated(SoulAdvancementService service, Player player, int totalActivations) {
                if (totalActivations >= 10) service.grant(player, self(registry, SoulAdvancementKeys.GROUP_10));
            }
        }));
        registry.register(def(SoulAdvancementKeys.GROUP_50, 22, 750, new SoulAdvancementHooks() {
            @Override
            public void onGroupActivated(SoulAdvancementService service, Player player, int totalActivations) {
                if (totalActivations >= 50) service.grant(player, self(registry, SoulAdvancementKeys.GROUP_50));
            }
        }));

        // --- Respawn ---
        registry.register(def(SoulAdvancementKeys.RESPAWN_FIRST, 30, 100, new SoulAdvancementHooks() {
            @Override
            public void onSoulRespawnCompleted(SoulAdvancementService service, Player player, int totalRespawns) {
                if (totalRespawns >= 1) service.grant(player, self(registry, SoulAdvancementKeys.RESPAWN_FIRST));
            }
        }));
        registry.register(def(SoulAdvancementKeys.RESPAWN_10, 31, 400, new SoulAdvancementHooks() {
            @Override
            public void onSoulRespawnCompleted(SoulAdvancementService service, Player player, int totalRespawns) {
                if (totalRespawns >= 10) service.grant(player, self(registry, SoulAdvancementKeys.RESPAWN_10));
            }
        }));

        // --- Mastery ---
        registry.register(def(SoulAdvancementKeys.MASTERY_5, 40, 200, new SoulAdvancementHooks() {
            @Override
            public void onMasteryLevelReached(SoulAdvancementService service, Player player, int level) {
                if (level >= 5) service.grant(player, self(registry, SoulAdvancementKeys.MASTERY_5));
            }
        }));
        registry.register(def(SoulAdvancementKeys.MASTERY_10, 41, 350, new SoulAdvancementHooks() {
            @Override
            public void onMasteryLevelReached(SoulAdvancementService service, Player player, int level) {
                if (level >= 10) service.grant(player, self(registry, SoulAdvancementKeys.MASTERY_10));
            }
        }));
        registry.register(def(SoulAdvancementKeys.MASTERY_20, 42, 750, new SoulAdvancementHooks() {
            @Override
            public void onMasteryLevelReached(SoulAdvancementService service, Player player, int level) {
                if (level >= 20) service.grant(player, self(registry, SoulAdvancementKeys.MASTERY_20));
            }
        }));

        // --- Codex ---
        registry.register(def(SoulAdvancementKeys.CODEX_MASTERED, 50, 5000, new SoulAdvancementHooks() {
            @Override
            public void onCodexMasteredReached(SoulAdvancementService service, Player player) {
                service.grant(player, self(registry, SoulAdvancementKeys.CODEX_MASTERED));
            }
        }));

        // --- Soul Table actions ---
        registry.register(def(SoulAdvancementKeys.TABLE_FIRST, 60, 100, new SoulAdvancementHooks() {
            @Override
            public void onSoulTableActionSucceeded(SoulAdvancementService service, Player player, SoulTableAction action, int totalActions, int actionTotal) {
                if (totalActions >= 1) service.grant(player, self(registry, SoulAdvancementKeys.TABLE_FIRST));
            }
        }));
        registry.register(def(SoulAdvancementKeys.TABLE_ENHANCE, 61, 150, new SoulAdvancementHooks() {
            @Override
            public void onSoulTableActionSucceeded(SoulAdvancementService service, Player player, SoulTableAction action, int totalActions, int actionTotal) {
                if (action == SoulTableAction.ENHANCE && actionTotal >= 1) service.grant(player, self(registry, SoulAdvancementKeys.TABLE_ENHANCE));
            }
        }));
        registry.register(def(SoulAdvancementKeys.TABLE_REFORGE, 62, 200, new SoulAdvancementHooks() {
            @Override
            public void onSoulTableActionSucceeded(SoulAdvancementService service, Player player, SoulTableAction action, int totalActions, int actionTotal) {
                if (action == SoulTableAction.REFORGE && actionTotal >= 1) service.grant(player, self(registry, SoulAdvancementKeys.TABLE_REFORGE));
            }
        }));
        registry.register(def(SoulAdvancementKeys.TABLE_COMBINE, 63, 300, new SoulAdvancementHooks() {
            @Override
            public void onSoulTableActionSucceeded(SoulAdvancementService service, Player player, SoulTableAction action, int totalActions, int actionTotal) {
                if (action == SoulTableAction.COMBINE && actionTotal >= 1) service.grant(player, self(registry, SoulAdvancementKeys.TABLE_COMBINE));
            }
        }));
        registry.register(def(SoulAdvancementKeys.TABLE_SALVAGE, 64, 150, new SoulAdvancementHooks() {
            @Override
            public void onSoulTableActionSucceeded(SoulAdvancementService service, Player player, SoulTableAction action, int totalActions, int actionTotal) {
                if (action == SoulTableAction.SALVAGE && actionTotal >= 1) service.grant(player, self(registry, SoulAdvancementKeys.TABLE_SALVAGE));
            }
        }));
    }

    private static SoulAdvancementDefinition def(org.bukkit.NamespacedKey key, int order, int essenceReward, SoulAdvancementHooks hooks) {
        return SoulAdvancementDefinition.of(key, order, essenceReward, hooks);
    }

    private static SoulAdvancementDefinition self(SoulAdvancementRegistry registry, org.bukkit.NamespacedKey key) {
        if (registry == null || key == null) return null;
        return registry.byKey(key).orElseGet(() -> SoulAdvancementDefinition.of(key, 0, 0, SoulAdvancementHooks.NONE));
    }
}
