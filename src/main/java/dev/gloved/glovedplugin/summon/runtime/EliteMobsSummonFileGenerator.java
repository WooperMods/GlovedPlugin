package dev.gloved.glovedplugin.summon.runtime;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import dev.gloved.glovedplugin.summon.config.SummonProfile;

/**
 * Legacy EliteMobs summon enchantment marker generator (profile/enchant-name based).
 * Disabled in the fully-dynamic soul system.
 */
@Deprecated(forRemoval = false)
public final class EliteMobsSummonFileGenerator {
    private EliteMobsSummonFileGenerator() {}

    public static void generate(JavaPlugin plugin, Map<String, SummonProfile> profiles) {
        // no-op: dynamic soul system does not use EliteMobs enchant markers
    }
}
