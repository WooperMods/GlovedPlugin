package dev.gloved.glovedplugin.summon.nms.v26p3;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.api.SummonNMS;

/**
 * Bootstrap for Paper 26.3 NMS summon implementation.
 */
public final class SummonNMSBootstrap {
    private SummonNMSBootstrap() {}

    public static SummonNMS create(GlovedPlugin plugin) {
        try {
            return new SummonNMS26p3(plugin);
        } catch (Throwable t) {
            plugin.getLogger().warning("[SummonNMS] Failed to initialize 26.3 NMS backend: " + t);
            return null;
        }
    }
}
