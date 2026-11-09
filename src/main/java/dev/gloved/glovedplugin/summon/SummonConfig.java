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
    private boolean minionProcSummon = false;
    private boolean clumpedNametagsEnabled = true;
    private int clumpedNametagsMinCount = 4;
    private double clumpedNametagsRadius = 2.0;
    private int clumpedNametagsUpdateTicks = 10;
    private String clumpedNametagsFormat = "{nametag} x{count}";

    // Progression (v1): rewards/progression XP via AuraSkills, no minion stat scaling.
    private boolean progressionEnabled = true;
    private boolean progressionAuraSkillsEnabled = true;
    private String progressionSkillId = "summoning";
    private boolean progressionApplyMultipliers = true;
    private boolean progressionHitXpEnabled = true;
    private double progressionHitXpAmount = 0.5;
    private int progressionHitOwnerCooldownTicks = 10;
    private int progressionHitTargetCooldownTicks = 40;
    private double progressionHitMaxPerMinute = 120.0;
    private boolean progressionKillXpEnabled = true;
    private double progressionKillXpAmount = 8.0;
    private int progressionKillAttributionWindowTicks = 200;
    private double progressionKillMaxPerMinute = 80.0;

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
        minionProcSummon = yaml.getBoolean("minion-proc-summon", false);
        clumpedNametagsEnabled = yaml.getBoolean("clumped-nametags.enabled", true);
        clumpedNametagsMinCount = Math.max(2, yaml.getInt("clumped-nametags.min-count", 4));
        clumpedNametagsRadius = Math.max(0.5, yaml.getDouble("clumped-nametags.radius", 2.0));
        clumpedNametagsUpdateTicks = Math.max(1, yaml.getInt("clumped-nametags.update-ticks", 10));
        clumpedNametagsFormat = yaml.getString("clumped-nametags.format", "{nametag} x{count}");
        // Migrate old generic default to profile-matching format.
        if ("&e{player}'s Minions x{count}".equals(clumpedNametagsFormat)) {
            clumpedNametagsFormat = "{nametag} x{count}";
        }

        progressionEnabled = yaml.getBoolean("progression.enabled", true);
        progressionAuraSkillsEnabled = yaml.getBoolean("progression.auraskills.enabled", true);
        progressionSkillId = yaml.getString("progression.auraskills.skill-id", "summoning");
        if (progressionSkillId == null || progressionSkillId.isBlank()) progressionSkillId = "summoning";
        progressionSkillId = progressionSkillId.trim().toLowerCase(java.util.Locale.ROOT);
        progressionApplyMultipliers = yaml.getBoolean("progression.auraskills.apply-multipliers", true);
        progressionHitXpEnabled = yaml.getBoolean("progression.xp.minion-hit.enabled", true);
        progressionHitXpAmount = safeNonNegative(yaml.getDouble("progression.xp.minion-hit.amount", 0.5));
        progressionHitOwnerCooldownTicks = Math.max(0, yaml.getInt("progression.xp.minion-hit.owner-cooldown-ticks", 10));
        progressionHitTargetCooldownTicks = Math.max(0, yaml.getInt("progression.xp.minion-hit.per-target-cooldown-ticks", 40));
        progressionHitMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.minion-hit.max-per-minute", 120.0));
        progressionKillXpEnabled = yaml.getBoolean("progression.xp.minion-kill.enabled", true);
        progressionKillXpAmount = safeNonNegative(yaml.getDouble("progression.xp.minion-kill.amount", 8.0));
        progressionKillAttributionWindowTicks = Math.max(1, yaml.getInt("progression.xp.minion-kill.attribution-window-ticks", 200));
        progressionKillMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.minion-kill.max-per-minute", 80.0));
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
        if (!cfg.isSet("minion-proc-summon")) cfg.set("minion-proc-summon", false);
        if (!cfg.isSet("clumped-nametags.enabled")) cfg.set("clumped-nametags.enabled", true);
        if (!cfg.isSet("clumped-nametags.min-count")) cfg.set("clumped-nametags.min-count", 4);
        if (!cfg.isSet("clumped-nametags.radius")) cfg.set("clumped-nametags.radius", 2.0);
        if (!cfg.isSet("clumped-nametags.update-ticks")) cfg.set("clumped-nametags.update-ticks", 10);
        if (!cfg.isSet("clumped-nametags.format")) cfg.set("clumped-nametags.format", "{nametag} x{count}");
        // Migrate old generic default to profile-matching format.
        if ("&e{player}'s Minions x{count}".equals(cfg.getString("clumped-nametags.format"))) {
            cfg.set("clumped-nametags.format", "{nametag} x{count}");
        }
        // Progression defaults (v1 rewards/progression only; no minion stat scaling).
        if (!cfg.isSet("progression.enabled")) cfg.set("progression.enabled", true);
        if (!cfg.isSet("progression.auraskills.enabled")) cfg.set("progression.auraskills.enabled", true);
        if (!cfg.isSet("progression.auraskills.skill-id")) cfg.set("progression.auraskills.skill-id", "summoning");
        if (!cfg.isSet("progression.auraskills.apply-multipliers")) cfg.set("progression.auraskills.apply-multipliers", true);
        if (!cfg.isSet("progression.xp.minion-hit.enabled")) cfg.set("progression.xp.minion-hit.enabled", true);
        if (!cfg.isSet("progression.xp.minion-hit.amount")) cfg.set("progression.xp.minion-hit.amount", 0.5);
        if (!cfg.isSet("progression.xp.minion-hit.owner-cooldown-ticks")) cfg.set("progression.xp.minion-hit.owner-cooldown-ticks", 10);
        if (!cfg.isSet("progression.xp.minion-hit.per-target-cooldown-ticks")) cfg.set("progression.xp.minion-hit.per-target-cooldown-ticks", 40);
        if (!cfg.isSet("progression.xp.minion-hit.max-per-minute")) cfg.set("progression.xp.minion-hit.max-per-minute", 120.0);
        if (!cfg.isSet("progression.xp.minion-kill.enabled")) cfg.set("progression.xp.minion-kill.enabled", true);
        if (!cfg.isSet("progression.xp.minion-kill.amount")) cfg.set("progression.xp.minion-kill.amount", 8.0);
        if (!cfg.isSet("progression.xp.minion-kill.attribution-window-ticks")) cfg.set("progression.xp.minion-kill.attribution-window-ticks", 200);
        if (!cfg.isSet("progression.xp.minion-kill.max-per-minute")) cfg.set("progression.xp.minion-kill.max-per-minute", 80.0);
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

    private static double safeNonNegative(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return 0;
        return v;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isMinionProcSummon() { return minionProcSummon; }
    public SummonProfile getProfile(String name) { return profiles.get(name); }
    public Map<String, SummonProfile> getProfiles() { return profiles; }

    public boolean isClumpedNametagsEnabled() { return clumpedNametagsEnabled; }
    public int getClumpedNametagsMinCount() { return clumpedNametagsMinCount; }
    public double getClumpedNametagsRadius() { return clumpedNametagsRadius; }
    public int getClumpedNametagsUpdateTicks() { return clumpedNametagsUpdateTicks; }
    public String getClumpedNametagsFormat() { return clumpedNametagsFormat; }

    public boolean isProgressionEnabled() { return progressionEnabled; }
    public boolean isProgressionAuraSkillsEnabled() { return progressionAuraSkillsEnabled; }
    public String getProgressionSkillId() { return progressionSkillId; }
    public boolean isProgressionApplyMultipliers() { return progressionApplyMultipliers; }
    public boolean isProgressionHitXpEnabled() { return progressionHitXpEnabled; }
    public double getProgressionHitXpAmount() { return progressionHitXpAmount; }
    public int getProgressionHitOwnerCooldownTicks() { return progressionHitOwnerCooldownTicks; }
    public int getProgressionHitTargetCooldownTicks() { return progressionHitTargetCooldownTicks; }
    public double getProgressionHitMaxPerMinute() { return progressionHitMaxPerMinute; }
    public boolean isProgressionKillXpEnabled() { return progressionKillXpEnabled; }
    public double getProgressionKillXpAmount() { return progressionKillXpAmount; }
    public int getProgressionKillAttributionWindowTicks() { return progressionKillAttributionWindowTicks; }
    public double getProgressionKillMaxPerMinute() { return progressionKillMaxPerMinute; }
}
