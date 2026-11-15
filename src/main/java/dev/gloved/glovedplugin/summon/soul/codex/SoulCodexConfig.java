package dev.gloved.glovedplugin.summon.soul.codex;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * Config for account/family codex progression (souls.codex.*).
 * MVP: tier thresholds and simple claim rewards.
 */
public final class SoulCodexConfig {

    public enum RewardType { ESSENCE, NONE }
    public record TierReward(RewardType type, int amount, int minCaptured) {}

    private boolean enabled = true;
    private int refinedEnhanceLevel = 5;
    private int masteredMasteryLevel = 20;
    private TierReward discoveredReward = new TierReward(RewardType.ESSENCE, 5, 1);
    private TierReward collectedReward = new TierReward(RewardType.ESSENCE, 15, 10);
    private TierReward refinedReward = new TierReward(RewardType.ESSENCE, 25, 1);
    private TierReward masteredReward = new TierReward(RewardType.ESSENCE, 50, 1);

    public static SoulCodexConfig load(FileConfiguration yaml) {
        SoulCodexConfig cfg = new SoulCodexConfig();
        if (yaml == null) return cfg;
        ConfigurationSection s = yaml.getConfigurationSection("souls.codex");
        if (s == null) return cfg;
        cfg.enabled = s.getBoolean("enabled", true);
        cfg.refinedEnhanceLevel = Math.max(0, s.getInt("refined-enhance-level", 5));
        cfg.masteredMasteryLevel = Math.max(1, s.getInt("mastered-mastery-level", 20));
        ConfigurationSection r = s.getConfigurationSection("rewards");
        if (r != null) {
            cfg.discoveredReward = readReward(r.getConfigurationSection("discovered"), RewardType.ESSENCE, 5, 1);
            cfg.collectedReward = readReward(r.getConfigurationSection("collected"), RewardType.ESSENCE, 15, 10);
            cfg.refinedReward = readReward(r.getConfigurationSection("refined"), RewardType.ESSENCE, 25, 1);
            cfg.masteredReward = readReward(r.getConfigurationSection("mastered"), RewardType.ESSENCE, 50, 1);
        }
        return cfg;
    }

    private static TierReward readReward(ConfigurationSection s, RewardType defType, int defAmount, int defMinCaptured) {
        if (s == null) return new TierReward(defType, defAmount, defMinCaptured);
        RewardType type = parseType(s.getString("type", defType.name()));
        int amount = Math.max(0, s.getInt("amount", defAmount));
        int minCap = Math.max(1, s.getInt("min-captured", defMinCaptured));
        return new TierReward(type, amount, minCap);
    }

    private static RewardType parseType(String raw) {
        if (raw == null) return RewardType.NONE;
        try { return RewardType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return RewardType.NONE; }
    }

    public static void backfill(FileConfiguration cfg) {
        if (cfg == null) return;
        set(cfg, "souls.codex.enabled", true);
        set(cfg, "souls.codex.refined-enhance-level", 5);
        set(cfg, "souls.codex.mastered-mastery-level", 20);
        set(cfg, "souls.codex.rewards.discovered.type", "ESSENCE");
        set(cfg, "souls.codex.rewards.discovered.amount", 5);
        set(cfg, "souls.codex.rewards.collected.type", "ESSENCE");
        set(cfg, "souls.codex.rewards.collected.amount", 15);
        set(cfg, "souls.codex.rewards.collected.min-captured", 10);
        set(cfg, "souls.codex.rewards.refined.type", "ESSENCE");
        set(cfg, "souls.codex.rewards.refined.amount", 25);
        set(cfg, "souls.codex.rewards.mastered.type", "ESSENCE");
        set(cfg, "souls.codex.rewards.mastered.amount", 50);
    }

    private static void set(FileConfiguration cfg, String path, Object value) {
        if (cfg != null && !cfg.isSet(path)) cfg.set(path, value);
    }

    public boolean isEnabled() { return enabled; }
    public int getRefinedEnhanceLevel() { return Math.max(0, refinedEnhanceLevel); }
    public int getMasteredMasteryLevel() { return Math.max(1, masteredMasteryLevel); }
    public TierReward getDiscoveredReward() { return discoveredReward; }
    public TierReward getCollectedReward() { return collectedReward; }
    public TierReward getRefinedReward() { return refinedReward; }
    public TierReward getMasteredReward() { return masteredReward; }
}
