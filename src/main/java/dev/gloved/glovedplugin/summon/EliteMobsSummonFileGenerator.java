package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates/validates EliteMobs enchantment marker files for summon profiles defined in {@code summon.yml}.
 * On each run (plugin load / summon config reload), configured profile markers are checked and overwritten to
 * keep them valid for EliteMobs 10.x (e.g. correct {@code validSlots} enum names) and in sync with the
 * generated format.
 */
public final class EliteMobsSummonFileGenerator {
    private EliteMobsSummonFileGenerator() {}

    public static void generate(JavaPlugin plugin, Map<String, SummonProfile> profiles) {
        if (plugin == null || profiles == null || profiles.isEmpty()) return;
        File pluginsDir = plugin.getDataFolder() != null ? plugin.getDataFolder().getParentFile() : null;
        if (pluginsDir == null) return;
        File eliteMobsDir = new File(pluginsDir, "EliteMobs");
        if (!eliteMobsDir.isDirectory()) {
            GlovedPlugin.logDebug("[Summon] EliteMobs directory not found at " + eliteMobsDir.getPath() + "; skipping enchantment file generation");
            return;
        }
        File enchantDir = new File(eliteMobsDir, "enchantments");
        if (!enchantDir.isDirectory() && !enchantDir.mkdirs()) {
            GlovedPlugin.logInfo("Could not create EliteMobs enchantments directory: " + enchantDir.getPath());
            return;
        }

        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (SummonProfile profile : profiles.values()) {
            if (profile == null || profile.name == null || profile.name.isBlank()) continue;
            String id = sanitizeId(profile.name);
            if (id.isEmpty()) continue;
            File yml = new File(enchantDir, id + ".yml");
            File lua = new File(enchantDir, id + ".lua");
            writeChecked(yml, buildYaml(profile), created, updated, unchanged);
            writeChecked(lua, buildLua(), created, updated, unchanged);
        }

        if (!created.isEmpty() || !updated.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (!created.isEmpty()) parts.add("created " + String.join(", ", created));
            if (!updated.isEmpty()) parts.add("updated " + String.join(", ", updated));
            GlovedPlugin.logInfo("[Summon] EliteMobs enchantment markers: " + String.join("; ", parts)
                    + " (run /em reload if EliteMobs needs to register new/changed enchantments)");
        }
        if (!unchanged.isEmpty()) {
            GlovedPlugin.logDebug("[Summon] EliteMobs enchantment markers unchanged: " + String.join(", ", unchanged));
        }
    }

    private static void writeChecked(File file, String content, List<String> created, List<String> updated, List<String> unchanged) {
        try {
            boolean exists = file.exists();
            if (exists) {
                String current = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                if (current.equals(content)) {
                    unchanged.add(file.getName());
                    return;
                }
            }
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            if (exists) updated.add(file.getName()); else created.add(file.getName());
        } catch (Exception e) {
            GlovedPlugin.logInfo("[Summon] Failed to write EliteMobs enchantment file " + file.getName() + ": " + e.getMessage());
        }
    }

    private static String sanitizeId(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9_\\-]", "_");
    }

    private static String buildYaml(SummonProfile profile) {
        String enchantName = profile.enchantName == null || profile.enchantName.isBlank()
                ? profile.name : profile.enchantName;
        String mob = formatMobName(profile.mobType);
        String description = "Summons " + mob + " on hit";
        // validSlots must use EnchantmentDefinition.Slot enum names (EM 10.x): HEAD,CHEST,LEGS,FEET,MAINHAND,OFFHAND
        return "isEnabled: true\n"
                + "name: " + yamlQuote(enchantName) + "\n"
                + "description: " + yamlQuote(description) + "\n"
                + "maxLevel: 1\n"
                + "validSlots: [MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET]\n"
                + "stacking: source_item\n"
                + "script: " + sanitizeId(profile.name) + ".lua\n"
                + "parameters:\n"
                + "  value: 10\n"
                + "  procedural: false\n";
    }

    private static String formatMobName(String mobType) {
        if (mobType == null || mobType.isBlank()) return "a mob";
        return mobType.trim().toLowerCase().replace('_', ' ');
    }

    private static String buildLua() {
        return "-- GlovedPlugin summon enchant marker\n"
                + "-- This enchant is handled by GlovedPlugin; no EliteMobs Lua effect needed.\n"
                + "return {\n"
                + "  api_version = 1\n"
                + "}\n";
    }

    private static String yamlQuote(String s) {
        if (s == null) return "''";
        String escaped = s.replace("'", "''");
        return "'" + escaped + "'";
    }
}
