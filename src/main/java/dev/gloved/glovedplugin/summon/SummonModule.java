package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.runtime.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.runtime.SummonManager;

/**
 * Summon feature module/facade (scaffolding for target structure).
 * Current runtime components remain owned/wired by {@link GlovedPlugin} to preserve behavior/order.
 */
public final class SummonModule {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private final SummonInstanceTracker tracker;
    private final SummonCombatService summonAI;
    private final SummonManager manager;

    public SummonModule(GlovedPlugin plugin, SummonConfig config, SummonInstanceTracker tracker, SummonCombatService summonAI, SummonManager manager) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.summonAI = summonAI;
        this.manager = manager;
    }

    public GlovedPlugin plugin() { return plugin; }
    public SummonConfig config() { return config; }
    public SummonInstanceTracker tracker() { return tracker; }
    public SummonCombatService summonAI() { return summonAI; }
    public SummonManager manager() { return manager; }
}
