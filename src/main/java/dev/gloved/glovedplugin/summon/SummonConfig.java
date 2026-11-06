package dev.gloved.glovedplugin.summon;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SummonConfig {
    private final JavaPlugin plugin;
    private final Map<String, SummonProfile> profiles = new HashMap<>();
    private String defaultProfile;
    private boolean enabled;

    private SummonConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static SummonConfig load(JavaPlugin plugin) {
        SummonConfig cfg = new SummonConfig(plugin);
        File file = new File(plugin.getDataFolder(), "summon.yml");
        if (!file.exists()) {
            plugin.saveResource("summon.yml", false);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        backfill(yaml);
        try { yaml.save(file); } catch (Exception ignored) {}
        cfg.enabled = yaml.getBoolean("enabled", true);
        cfg.defaultProfile = yaml.getString("default-profile", "bee_swarm");
        if (yaml.isConfigurationSection("profiles")) {
            for (String key : yaml.getConfigurationSection("profiles").getKeys(false)) {
                var section = yaml.getConfigurationSection("profiles." + key);
                List<String> mobTypes = section.getStringList("mob-types");
                if (mobTypes == null || mobTypes.isEmpty()) {
                    String single = section.getString("mob-type", "BEE");
                    mobTypes = List.of(single);
                }
                String ai = section.getString("ai", "tame_follow");
                boolean tamed = section.getBoolean("tamed", true);
                boolean friendly = section.getBoolean("friendly", true);
                boolean invulnerable = section.getBoolean("invulnerable", true);
                double chance = section.getDouble("chance", 0.3);
                int cooldown = section.getInt("cooldown_ticks", 5);
                int lifetime = section.getInt("lifetime_ticks", 200);
                int minions = section.getInt("minions", 2);
                int maxActive = section.getInt("max-active", 10);
                var validSlots = section.getStringList("valid-slots");
                String targetMode = section.getString("target-mode", "CURRENT_TARGET");
                boolean damageProxy = section.getBoolean("damage-proxy", true);
                double followRange = section.getDouble("follow-range", 16.0);
                double attackRange = section.getDouble("attack-range", 4.0);
                String nametagFormat = section.getString("nametag-format", "{player}'s {mob}");
                String nametagColor = section.getString("nametag-color", "YELLOW");
                boolean nametagVisible = section.getBoolean("nametag-visible", true);
                SummonProfile profile = new SummonProfile(mobTypes, ai, tamed, friendly, invulnerable,
                        chance, cooldown, lifetime, minions, maxActive, validSlots, targetMode, damageProxy, followRange, attackRange,
                        nametagFormat, nametagColor, nametagVisible);
                cfg.profiles.put(key, profile);
            }
        }
        return cfg;
    }

    private static void backfill(FileConfiguration cfg) {
        if (!cfg.isSet("enabled")) cfg.set("enabled", true);
        if (!cfg.isSet("default-profile")) cfg.set("default-profile", "bee_swarm");
        var profiles = cfg.getConfigurationSection("profiles");
        if (profiles == null) return;
        for (String key : profiles.getKeys(false)) {
            var s = profiles.getConfigurationSection(key);
            if (!s.isSet("mob-types") && !s.isSet("mob-type")) s.set("mob-type", "BEE");
            if (!s.isSet("ai")) s.set("ai", "tame_follow");
            if (!s.isSet("tamed")) s.set("tamed", true);
            if (!s.isSet("friendly")) s.set("friendly", true);
            if (!s.isSet("invulnerable")) s.set("invulnerable", true);
            if (!s.isSet("chance")) s.set("chance", 0.3);
            if (!s.isSet("cooldown_ticks")) s.set("cooldown_ticks", 5);
            if (!s.isSet("lifetime_ticks")) s.set("lifetime_ticks", 200);
            if (!s.isSet("minions")) s.set("minions", 2);
            if (!s.isSet("max-active")) s.set("max-active", 10);
            if (!s.isSet("valid-slots")) s.set("valid-slots", java.util.List.of("MAIN_HAND"));
            if (!s.isSet("target-mode")) s.set("target-mode", "CURRENT_TARGET");
            if (!s.isSet("damage-proxy")) s.set("damage-proxy", true);
            if (!s.isSet("follow-range")) s.set("follow-range", 16.0);
            if (!s.isSet("attack-range")) s.set("attack-range", 4.0);
            if (!s.isSet("nametag-format")) s.set("nametag-format", "{player}'s {mob}");
            if (!s.isSet("nametag-color")) s.set("nametag-color", "YELLOW");
            if (!s.isSet("nametag-visible")) s.set("nametag-visible", true);
        }
    }

    public boolean isEnabled() { return enabled; }
    public String getDefaultProfile() { return defaultProfile; }
    public SummonProfile getProfile(String name) { return profiles.get(name); }
    public Map<String, SummonProfile> getProfiles() { return profiles; }
}
