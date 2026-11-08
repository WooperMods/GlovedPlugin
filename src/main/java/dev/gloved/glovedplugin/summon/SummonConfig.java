package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SummonConfig {
    private final JavaPlugin plugin;
    private final Map<String, SummonProfile> profiles = new LinkedHashMap<>();
    private boolean enabled;
    private boolean clumpedNametagsEnabled = true;
    private int clumpedNametagsMinCount = 4;
    private double clumpedNametagsRadius = 1.5;
    private int clumpedNametagsUpdateTicks = 10;
    private String clumpedNametagsFormat = "{nametag} x{count}";

    private SummonConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static SummonConfig load(JavaPlugin plugin) {
        SummonConfig cfg = new SummonConfig(plugin);
        cfg.reload();
        return cfg;
    }

    /**
     * Re-reads {@code summon.yml} into this instance. Listeners and managers keep a reference to
     * the same object, so {@code /gloved reload} applies changes without a restart.
     */
    public void reload() {
        profiles.clear();
        File file = new File(plugin.getDataFolder(), "summon.yml");
        if (!file.exists()) {
            plugin.saveResource("summon.yml", false);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        backfill(yaml);
        try { yaml.save(file); } catch (Exception ignored) {}
        enabled = yaml.getBoolean("enabled", true);
        clumpedNametagsEnabled = yaml.getBoolean("clumped-nametags.enabled", true);
        clumpedNametagsMinCount = Math.max(2, yaml.getInt("clumped-nametags.min-count", 4));
        clumpedNametagsRadius = Math.max(0.5, yaml.getDouble("clumped-nametags.radius", 1.5));
        clumpedNametagsUpdateTicks = Math.max(1, yaml.getInt("clumped-nametags.update-ticks", 10));
        clumpedNametagsFormat = yaml.getString("clumped-nametags.format", "{nametag} x{count}");
        // Migrate old generic default to profile-matching format.
        if ("&e{player}'s Minions x{count}".equals(clumpedNametagsFormat)) {
            clumpedNametagsFormat = "{nametag} x{count}";
        }
        if (yaml.isConfigurationSection("profiles")) {
            for (String key : yaml.getConfigurationSection("profiles").getKeys(false)) {
                var section = yaml.getConfigurationSection("profiles." + key);
                if (section.isSet("mob-types") || section.isList("mob-types")) {
                    GlovedPlugin.logInfo("[Summon] Profile '" + key + "': 'mob-types' (list) is invalid; use 'mob-type' (single). "
                            + (section.isSet("mob-type") ? "Ignoring 'mob-types'." : "Falling back to first entry; migrate to 'mob-type'."));
                }
                String mobType = section.getString("mob-type");
                if (mobType == null || mobType.isBlank()) {
                    List<String> legacy = section.getStringList("mob-types");
                    if (legacy != null && !legacy.isEmpty()) mobType = legacy.get(0);
                }
                if (mobType == null || mobType.isBlank()) mobType = "BEE";
                double chance = section.getDouble("chance", 0.3);
                int minionsPerHit = section.getInt("minions-per-hit", section.getInt("minions", 2));
                int maxActive = section.getInt("max-active", 10);
                String nametagColor = section.getString("nametag-color", "YELLOW");
                String enchantName = section.getString("enchant-name");
                if (enchantName == null || enchantName.isBlank()) {
                    enchantName = deriveEnchantName(key);
                }
                SummonProfile profile = new SummonProfile(key, mobType,
                        chance, minionsPerHit, maxActive, nametagColor, enchantName);
                profiles.put(key, profile);
                GlovedPlugin.logDebug("[Summon] Loaded profile " + key + ": enchant-name=\"" + profile.enchantName
                        + "\", minions-per-hit=" + profile.minionsPerHit
                        + ", max-active=" + profile.maxActive + ", chance=" + profile.chance
                        + ", mob-type=" + profile.mobType);
            }
        }
        // Auto-generate missing EliteMobs enchantment marker files for configured profiles.
        EliteMobsSummonFileGenerator.generate(plugin, profiles);
    }

    private static void backfill(FileConfiguration cfg) {
        if (!cfg.isSet("enabled")) cfg.set("enabled", true);
        if (!cfg.isSet("clumped-nametags.enabled")) cfg.set("clumped-nametags.enabled", true);
        if (!cfg.isSet("clumped-nametags.min-count")) cfg.set("clumped-nametags.min-count", 4);
        if (!cfg.isSet("clumped-nametags.radius")) cfg.set("clumped-nametags.radius", 1.5);
        if (!cfg.isSet("clumped-nametags.update-ticks")) cfg.set("clumped-nametags.update-ticks", 10);
        if (!cfg.isSet("clumped-nametags.format")) cfg.set("clumped-nametags.format", "{nametag} x{count}");
        // Migrate old generic default to profile-matching format.
        if ("&e{player}'s Minions x{count}".equals(cfg.getString("clumped-nametags.format"))) {
            cfg.set("clumped-nametags.format", "{nametag} x{count}");
        }
        // Remove top-level settings that are now hardcoded/unused.
        for (String deprecated : List.of("default-profile")) {
            if (cfg.isSet(deprecated)) cfg.set(deprecated, null);
        }
        var profiles = cfg.getConfigurationSection("profiles");
        if (profiles == null) return;
        for (String key : profiles.getKeys(false)) {
            var s = profiles.getConfigurationSection(key);
            // Migrate the renamed key: minions -> minions-per-hit.
            if (s.isSet("minions") && !s.isSet("minions-per-hit")) {
                s.set("minions-per-hit", s.getInt("minions", 2));
            }
            // Remove settings that are now hardcoded in SummonProfile or no longer used.
            for (String deprecated : List.of("ai", "damage-proxy", "tamed", "friendly",
                    "minions", "invulnerable", "cooldown_ticks", "lifetime_ticks",
                    "follow-range", "attack-range", "target-mode", "nametag-format",
                    "nametag-visible", "valid-slots")) {
                if (s.isSet(deprecated)) s.set(deprecated, null);
            }
            // mob-types (list) is invalid; only mob-type (single) is supported.
            if (s.isSet("mob-types") || s.isList("mob-types")) {
                if (!s.isSet("mob-type") || String.valueOf(s.get("mob-type")).isBlank()) {
                    var legacy = s.getStringList("mob-types");
                    if (legacy != null && !legacy.isEmpty()) {
                        s.set("mob-type", legacy.get(0));
                    }
                }
                s.set("mob-types", null);
                GlovedPlugin.logInfo("[Summon] Profile '" + key + "': migrated invalid 'mob-types' -> 'mob-type'.");
            }
            if (!s.isSet("mob-type") || String.valueOf(s.get("mob-type")).isBlank()) s.set("mob-type", "BEE");
            if (!s.isSet("chance")) s.set("chance", 0.3);
            if (!s.isSet("minions-per-hit")) s.set("minions-per-hit", 2);
            if (!s.isSet("max-active")) s.set("max-active", 10);
            if (!s.isSet("nametag-color")) s.set("nametag-color", "YELLOW");
            // Required: the enchant/display name that must appear in lore (formatting stripped).
            if (!s.isSet("enchant-name") || String.valueOf(s.get("enchant-name")).isBlank()) {
                s.set("enchant-name", deriveEnchantName(key));
            }
        }
    }

    private static String deriveEnchantName(String profileKey) {
        if (profileKey == null || profileKey.isBlank()) return "";
        String[] parts = profileKey.toLowerCase().replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    public boolean isEnabled() { return enabled; }
    public SummonProfile getProfile(String name) { return profiles.get(name); }
    public Map<String, SummonProfile> getProfiles() { return profiles; }

    public boolean isClumpedNametagsEnabled() { return clumpedNametagsEnabled; }
    public int getClumpedNametagsMinCount() { return clumpedNametagsMinCount; }
    public double getClumpedNametagsRadius() { return clumpedNametagsRadius; }
    public int getClumpedNametagsUpdateTicks() { return clumpedNametagsUpdateTicks; }
    public String getClumpedNametagsFormat() { return clumpedNametagsFormat; }
}
