package dev.gloved.glovedplugin.summon.soul.mastery;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Config for per-soul mastery/attunement progression (souls.mastery.*).
 * MVP: XP sources (hit/kill/capture), level curve, and claimable milestone rewards.
 */
public final class SoulMasteryConfig {

    public enum RewardType { ESSENCE, NONE }

    public record MilestoneReward(int level, RewardType type, int amount, String label) {}

    public record SourceConfig(boolean enabled, double xp, double minFinalDamage,
                               int perSoulCooldownTicks, int perOwnerCooldownTicks, double perMinuteCap) {}

    private boolean enabled = true;
    private int maxLevel = 20;
    private List<Integer> xpPerLevel = List.of(100,150,220,300,400,520,660,820,1000,1200,1450,1750,2100,2500,2950,3450,4000,4600,5300,6100);
    private SourceConfig hitSource = new SourceConfig(true, 1.0, 0.0, 10, 0, 120.0);
    private SourceConfig killSource = new SourceConfig(true, 12.0, 0.0, 0, 0, 240.0);
    private SourceConfig captureSource = new SourceConfig(false, 5.0, 0.0, 0, 0, 0.0);
    private final NavigableMap<Integer, MilestoneReward> milestones = new TreeMap<>();

    public SoulMasteryConfig() {
        defaults();
    }

    private void defaults() {
        milestones.clear();
        milestones.put(5, new MilestoneReward(5, RewardType.ESSENCE, 25, "Novice Attunement"));
        milestones.put(10, new MilestoneReward(10, RewardType.ESSENCE, 50, "Adept Attunement"));
        milestones.put(15, new MilestoneReward(15, RewardType.ESSENCE, 100, "Expert Attunement"));
        milestones.put(20, new MilestoneReward(20, RewardType.ESSENCE, 200, "Mastered"));
    }

    public static SoulMasteryConfig load(FileConfiguration yaml) {
        SoulMasteryConfig cfg = new SoulMasteryConfig();
        if (yaml == null) return cfg;
        ConfigurationSection s = yaml.getConfigurationSection("souls.mastery");
        if (s == null) return cfg;

        cfg.enabled = s.getBoolean("enabled", true);
        cfg.maxLevel = Math.max(1, Math.min(1000, s.getInt("max-level", 20)));
        List<Integer> curve = s.getIntegerList("xp-per-level");
        if (!curve.isEmpty()) {
            List<Integer> cleaned = new ArrayList<>(curve.size());
            for (Integer v : curve) cleaned.add(Math.max(1, v == null ? 100 : v));
            if (!cleaned.isEmpty()) cfg.xpPerLevel = List.copyOf(cleaned);
        }
        cfg.hitSource = readSource(s, "sources.summon-hit", true, 1.0, 0.0, 10, 0, 120.0);
        cfg.killSource = readSource(s, "sources.summon-kill", true, 12.0, 0.0, 0, 0, 240.0);
        cfg.captureSource = readSource(s, "sources.capture", false, 5.0, 0.0, 0, 0, 0.0);

        ConfigurationSection ms = s.getConfigurationSection("milestones");
        if (ms != null) {
            NavigableMap<Integer, MilestoneReward> parsed = new TreeMap<>();
            for (String key : ms.getKeys(false)) {
                int lvl;
                try { lvl = Integer.parseInt(key.trim()); } catch (Exception e) { continue; }
                if (lvl < 1) continue;
                ConfigurationSection m = ms.getConfigurationSection(key);
                if (m == null) continue;
                RewardType type = parseRewardType(m.getString("type", "ESSENCE"));
                int amount = Math.max(0, m.getInt("amount", 0));
                String label = m.getString("label", "Milestone " + lvl);
                parsed.put(lvl, new MilestoneReward(lvl, type, amount, label == null ? "Milestone " + lvl : label));
            }
            if (!parsed.isEmpty()) {
                cfg.milestones.clear();
                cfg.milestones.putAll(parsed);
            }
        }
        return cfg;
    }

    private static SourceConfig readSource(ConfigurationSection root, String path, boolean defEnabled, double defXp,
                                           double defMinDamage, int defSoulCd, int defOwnerCd, double defPerMin) {
        ConfigurationSection s = root.getConfigurationSection(path);
        if (s == null) return new SourceConfig(defEnabled, defXp, defMinDamage, defSoulCd, defOwnerCd, defPerMin);
        return new SourceConfig(
                s.getBoolean("enabled", defEnabled),
                Math.max(0.0, s.getDouble("xp", defXp)),
                Math.max(0.0, s.getDouble("min-final-damage", defMinDamage)),
                Math.max(0, s.getInt("per-soul-cooldown-ticks", defSoulCd)),
                Math.max(0, s.getInt("per-owner-cooldown-ticks", defOwnerCd)),
                Math.max(0.0, s.getDouble("per-minute-cap", defPerMin))
        );
    }

    private static RewardType parseRewardType(String raw) {
        if (raw == null) return RewardType.NONE;
        try { return RewardType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return RewardType.NONE; }
    }

    public static void backfill(FileConfiguration cfg) {
        if (cfg == null) return;
        set(cfg, "souls.mastery.enabled", true);
        set(cfg, "souls.mastery.max-level", 20);
        if (!cfg.isList("souls.mastery.xp-per-level")) {
            cfg.set("souls.mastery.xp-per-level", List.of(100,150,220,300,400,520,660,820,1000,1200,1450,1750,2100,2500,2950,3450,4000,4600,5300,6100));
        }
        set(cfg, "souls.mastery.sources.summon-hit.enabled", true);
        set(cfg, "souls.mastery.sources.summon-hit.xp", 1.0);
        set(cfg, "souls.mastery.sources.summon-hit.min-final-damage", 0.0);
        set(cfg, "souls.mastery.sources.summon-hit.per-soul-cooldown-ticks", 10);
        set(cfg, "souls.mastery.sources.summon-hit.per-minute-cap", 120.0);
        set(cfg, "souls.mastery.sources.summon-kill.enabled", true);
        set(cfg, "souls.mastery.sources.summon-kill.xp", 12.0);
        set(cfg, "souls.mastery.sources.summon-kill.per-minute-cap", 240.0);
        set(cfg, "souls.mastery.sources.capture.enabled", false);
        set(cfg, "souls.mastery.sources.capture.xp", 5.0);
        if (!cfg.isConfigurationSection("souls.mastery.milestones")) {
            setMilestone(cfg, 5, "ESSENCE", 25, "Novice Attunement");
            setMilestone(cfg, 10, "ESSENCE", 50, "Adept Attunement");
            setMilestone(cfg, 15, "ESSENCE", 100, "Expert Attunement");
            setMilestone(cfg, 20, "ESSENCE", 200, "Mastered");
        }
    }

    private static void setMilestone(FileConfiguration cfg, int level, String type, int amount, String label) {
        String base = "souls.mastery.milestones." + level + ".";
        set(cfg, base + "type", type);
        set(cfg, base + "amount", amount);
        set(cfg, base + "label", label);
    }

    private static void set(FileConfiguration cfg, String path, Object value) {
        if (cfg != null && !cfg.isSet(path)) cfg.set(path, value);
    }

    public boolean isEnabled() { return enabled; }
    public int getMaxLevel() { return Math.max(1, maxLevel); }

    public int xpToNextLevel(int currentLevel) {
        if (xpPerLevel.isEmpty()) return 100;
        int idx = Math.max(0, Math.min(currentLevel, xpPerLevel.size() - 1));
        return Math.max(1, xpPerLevel.get(idx));
    }

    public SourceConfig getHitSource() { return hitSource; }
    public SourceConfig getKillSource() { return killSource; }
    public SourceConfig getCaptureSource() { return captureSource; }
    public NavigableMap<Integer, MilestoneReward> getMilestones() { return Collections.unmodifiableNavigableMap(milestones); }
    public MilestoneReward getMilestone(int level) { return milestones.get(level); }

    public String rankName(int level) {
        if (level >= 20) return "Mastered";
        if (level >= 15) return "Expert";
        if (level >= 10) return "Adept";
        if (level >= 5) return "Novice";
        return "Untrained";
    }
}
