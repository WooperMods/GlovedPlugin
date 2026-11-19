package dev.gloved.glovedplugin.summon.soul.advancement;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime coordinator for Soul advancements: registry/module-driven hook dispatch, idempotent grants,
 * and shared progression counters. Advancement definitions/hooks are registered in {@link SoulAdvancements}.
 */
public final class SoulAdvancementService implements Listener {

    private final GlovedPlugin plugin;
    private final SoulService soulService;
    private final SoulAdvancementRegistry registry;
    private final ConcurrentHashMap<String, Boolean> missingLogged = new ConcurrentHashMap<>();

    public SoulAdvancementService(GlovedPlugin plugin, SoulService soulService) {
        this.plugin = plugin;
        this.soulService = soulService;
        this.registry = new SoulAdvancementRegistry();
        SoulAdvancements.registerAll(this.registry);
        this.registry.freeze();
        SoulAdvancementRegistry.setActive(this.registry);
    }

    public SoulAdvancementRegistry getRegistry() {
        return registry;
    }

    /** Ensure the root advancement (tab entry) is granted so the Souls tab is visible on join. */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (event == null || event.getPlayer() == null) return;
        Player player = event.getPlayer();
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onPlayerJoin(this, player);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onPlayerJoin hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    /** Grants an advancement idempotently. Returns true if it was newly completed by this call. */
    public boolean grant(Player player, NamespacedKey key) {
        if (player == null || key == null) return false;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> grant(player, key));
            return false;
        }
        try {
            Advancement adv = Bukkit.getAdvancement(key);
            if (adv == null) {
                String k = key.toString();
                if (missingLogged.putIfAbsent(k, Boolean.TRUE) == null) {
                    GlovedPlugin.logDebug("[SoulAdvancements] Advancement not loaded/missing: " + k
                            + " (datapack/resources may not be active yet)");
                }
                return false;
            }
            AdvancementProgress progress = player.getAdvancementProgress(adv);
            if (progress.isDone()) return false;
            for (String criterion : progress.getRemainingCriteria()) {
                try {
                    progress.awardCriteria(criterion);
                } catch (Throwable ignored) {}
            }
            return progress.isDone();
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SoulAdvancements] Failed to grant " + key + " to " + player.getName() + ": " + t);
            return false;
        }
    }

    /** Grants and, if newly completed, awards the one-time Essence reward for the given advancement definition. */
    public boolean grant(Player player, SoulAdvancementDefinition def) {
        if (player == null || def == null) return false;
        if (!Bukkit.isPrimaryThread()) {
            final SoulAdvancementDefinition capturedDef = def;
            Bukkit.getScheduler().runTask(plugin, () -> grant(player, capturedDef));
            return false;
        }
        boolean newlyCompleted = grant(player, def.key());
        if (newlyCompleted && def.essenceReward() > 0) {
            try {
                soulService.addEssence(player.getUniqueId(), def.essenceReward());
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] Failed to award essence reward for "
                        + safeKey(def) + " to " + player.getName() + ": " + t);
            }
        }
        return newlyCompleted;
    }

    public void grant(UUID ownerId, NamespacedKey key) {
        if (ownerId == null || key == null) return;
        Player p = Bukkit.getPlayer(ownerId);
        if (p != null) grant(p, key);
    }

    // ---------- Hook dispatch entry points (confirmed-success semantics preserved) ----------

    /** Any soul acquired into ownership/vault (capture stored/essence-converted, drops/traps collect, admin/give paths as applicable). */
    public void onSoulAcquired(Player player, SoulInstance soul) {
        if (player == null || soul == null) return;
        int total = 0;
        try {
            int cap = defaultVaultCap(player.getUniqueId());
            total = soulService.getRepository().incrementSoulsAcquiredTotal(player.getUniqueId(), cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulAdvancements] Failed to increment souls acquired counter: " + e);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SoulAdvancements] souls acquired counter error: " + t);
        }
        final int totalAcquired = total;
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onSoulAcquired(this, player, soul, totalAcquired);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onSoulAcquired hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    /** First successful soul group activation (not recall). */
    public void onGroupActivated(Player player) {
        if (player == null) return;
        int total = 0;
        try {
            int cap = defaultVaultCap(player.getUniqueId());
            total = soulService.getRepository().incrementGroupActivationsTotal(player.getUniqueId(), cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulAdvancements] Failed to increment group activations counter: " + e);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SoulAdvancements] group activations counter error: " + t);
        }
        final int totalActivations = total;
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onGroupActivated(this, player, totalActivations);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onGroupActivated hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    /** Successful respawn completion. */
    public void onSoulRespawnCompleted(Player player) {
        if (player == null) return;
        int total = 0;
        try {
            int cap = defaultVaultCap(player.getUniqueId());
            total = soulService.getRepository().incrementSoulRespawnsTotal(player.getUniqueId(), cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulAdvancements] Failed to increment soul respawns counter: " + e);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SoulAdvancements] soul respawns counter error: " + t);
        }
        final int totalRespawns = total;
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onSoulRespawnCompleted(this, player, totalRespawns);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onSoulRespawnCompleted hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    /** Any soul reaches mastery level thresholds (5, 20). */
    public void onMasteryLevelReached(Player player, int level) {
        if (player == null) return;
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onMasteryLevelReached(this, player, level);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onMasteryLevelReached hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    /** Codex mastered tier reached (not claimed) for any mob. */
    public void onCodexMasteredReached(Player player) {
        if (player == null) return;
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onCodexMasteredReached(this, player);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onCodexMasteredReached hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    /** Successful Soul Table action: combine/enhance/reforge/salvage. */
    public void onSoulTableActionSucceeded(Player player, SoulTableAction action) {
        if (player == null) return;
        int total = 0;
        int actionTotal = 0;
        try {
            int cap = defaultVaultCap(player.getUniqueId());
            String suffix = action != null ? action.name().toLowerCase(java.util.Locale.ROOT) : null;
            var st = soulService.getRepository().incrementSoulTableActionTotals(player.getUniqueId(), cap, suffix);
            if (st != null) {
                total = st.soulTableActionsTotal();
                actionTotal = switch (action) {
                    case COMBINE -> st.soulTableCombineTotal();
                    case ENHANCE -> st.soulTableEnhanceTotal();
                    case REFORGE -> st.soulTableReforgeTotal();
                    case SALVAGE -> st.soulTableSalvageTotal();
                    case null -> 0;
                };
            }
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulAdvancements] Failed to increment table action counters: " + e);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SoulAdvancements] table action counter error: " + t);
        }
        final int totalActions = total;
        final int actionActions = actionTotal;
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onSoulTableActionSucceeded(this, player, action, totalActions, actionActions);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onSoulTableActionSucceeded hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    /** Backward-compatible overload (treats as generic action without per-action counter). */
    public void onSoulTableActionSucceeded(Player player) {
        // No action type provided; increment overall only and dispatch with null action/0 actionTotal.
        if (player == null) return;
        int total = 0;
        try {
            int cap = defaultVaultCap(player.getUniqueId());
            total = soulService.getRepository().incrementSoulTableActionsTotal(player.getUniqueId(), cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulAdvancements] Failed to increment table actions counter: " + e);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SoulAdvancements] table actions counter error: " + t);
        }
        final int totalActions = total;
        for (SoulAdvancementDefinition def : definitions()) {
            try {
                SoulAdvancementHooks hooks = def != null ? def.hooks() : null;
                if (hooks != null) hooks.onSoulTableActionSucceeded(this, player, null, totalActions, 0);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulAdvancements] onSoulTableActionSucceeded hook failed for " + safeKey(def) + ": " + t);
            }
        }
    }

    private List<SoulAdvancementDefinition> definitions() {
        try {
            return registry.all();
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static String safeKey(SoulAdvancementDefinition def) {
        try { return def != null && def.key() != null ? def.key().toString() : "<null>"; } catch (Throwable ignored) { return "<unknown>"; }
    }

    private int defaultVaultCap(UUID owner) {
        try {
            var cfg = soulService.soulConfig();
            return cfg != null ? cfg.computeVaultCapacity(soulService.getSummoningLevel(owner)) : 30;
        } catch (Throwable t) {
            return 30;
        }
    }
}
