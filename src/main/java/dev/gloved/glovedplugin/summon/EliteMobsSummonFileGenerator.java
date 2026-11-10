package dev.gloved.glovedplugin.summon;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

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
