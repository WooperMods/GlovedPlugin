package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.summon.soul.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.SoulRarity;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code souls.table.*} and {@code souls.stats.*} from summon.yml.
 *
 * <p>Defaults live in three places by repo convention: the bundled {@code summon.yml},
 * the fallbacks in {@link #reload(FileConfiguration)} and the backfill in {@link #backfill}.
 * Instances are reloaded in place so listeners/managers can keep their reference.</p>
 */
public final class SoulTableConfig {

    /** Rarity multiplier, per-stat variance and per-stat caps for the soul stat system. */
    public static final class SoulStatConfig {
        private final EnumMap<SoulRarity, Double> rarityMultipliers = new EnumMap<>(SoulRarity.class);
        private final Map<String, Double> variance = new LinkedHashMap<>();
        private final Map<String, Double> caps = new LinkedHashMap<>();

        public SoulStatConfig() {
            rarityMultipliers.put(SoulRarity.COMMON, 1.00);
            rarityMultipliers.put(SoulRarity.UNCOMMON, 1.05);
            rarityMultipliers.put(SoulRarity.RARE, 1.12);
            rarityMultipliers.put(SoulRarity.EPIC, 1.20);
            rarityMultipliers.put(SoulRarity.LEGENDARY, 1.30);
            rarityMultipliers.put(SoulRarity.MYTHIC, 1.45);

            variance.put(SoulBaseStats.KEY_HEALTH, 0.10);
            variance.put(SoulBaseStats.KEY_ATTACK_DAMAGE, 0.10);
            variance.put(SoulBaseStats.KEY_MOVEMENT_SPEED, 0.05);
            variance.put(SoulBaseStats.KEY_ARMOR, 0.15);
            variance.put(SoulBaseStats.KEY_ARMOR_TOUGHNESS, 0.15);
            variance.put(SoulBaseStats.KEY_KNOCKBACK_RESISTANCE, 0.10);
            variance.put(SoulBaseStats.KEY_ATTACK_KNOCKBACK, 0.10);
            variance.put(SoulBaseStats.KEY_ATTACK_SPEED, 0.10);

            caps.put(SoulBaseStats.KEY_HEALTH, 200.0);
            caps.put(SoulBaseStats.KEY_ATTACK_DAMAGE, 30.0);
            caps.put(SoulBaseStats.KEY_MOVEMENT_SPEED, 0.5);
            caps.put(SoulBaseStats.KEY_ARMOR, 30.0);
            caps.put(SoulBaseStats.KEY_ARMOR_TOUGHNESS, 20.0);
            caps.put(SoulBaseStats.KEY_KNOCKBACK_RESISTANCE, 1.0);
            caps.put(SoulBaseStats.KEY_ATTACK_KNOCKBACK, 5.0);
            caps.put(SoulBaseStats.KEY_ATTACK_SPEED, 8.0);
        }

        public double rarityMultiplier(SoulRarity rarity) {
            if (rarity == null) return 1.0;
            return rarityMultipliers.getOrDefault(rarity, 1.0);
        }

        public double variance(String key) {
            return Math.max(0.0, variance.getOrDefault(key, 0.0));
        }

        public double cap(String key) {
            return Math.max(0.0, caps.getOrDefault(key, 0.0));
        }

        public Map<String, Double> varianceView() { return Map.copyOf(variance); }
        public Map<String, Double> capsView() { return Map.copyOf(caps); }
        public Map<SoulRarity, Double> rarityMultipliersView() { return Map.copyOf(rarityMultipliers); }

        private void loadVariance(ConfigurationSection sec) {
            if (sec == null) return;
            for (String key : new ArrayList<>(variance.keySet())) {
                variance.put(key, clamp(sec.getDouble(key, variance.getOrDefault(key, 0.0)), 0.0, 1.0));
            }
        }

        private void loadCaps(ConfigurationSection sec) {
            if (sec == null) return;
            for (String key : new ArrayList<>(caps.keySet())) {
                caps.put(key, Math.max(0.0, sec.getDouble(key, caps.getOrDefault(key, 0.0))));
            }
        }

        private void loadRarity(ConfigurationSection sec) {
            if (sec == null) return;
            for (SoulRarity rarity : SoulRarity.values()) {
                rarityMultipliers.put(rarity,
                        clamp(sec.getDouble(rarity.name(), rarityMultipliers.getOrDefault(rarity, 1.0)), 0.0, 10.0));
            }
        }
    }

    // --- table toggles -------------------------------------------------------
    private boolean enabled = true;
    private Material baseBlock = Material.SMITHING_TABLE;
    private boolean usePublic = true;
    private boolean breakOwnerOnly = true;

    // --- costs --------------------------------------------------------------
    private final EnumMap<SoulRarity, Integer> combineCosts = new EnumMap<>(SoulRarity.class);
    private int enhanceBase = 10;
    private int enhanceExponent = 2;
    private int enhanceMaxLevel = 10;
    private double enhancePerLevelBonus = 0.025;
    private final EnumMap<SoulRarity, Integer> reforgeCosts = new EnumMap<>(SoulRarity.class);
    private int salvageConfirmationSeconds = 5;

    // --- recipe -------------------------------------------------------------
    private boolean recipeEnabled = true;
    private final List<String> recipePattern = new ArrayList<>(List.of("SSS", "PLP", "ONO"));
    private final Map<Character, Material> recipeIngredients = new LinkedHashMap<>();

    // --- stats --------------------------------------------------------------
    private boolean statsEnabled = true;
    private boolean backfillLegacy = true;
    private final SoulStatConfig statConfig = new SoulStatConfig();

    public SoulTableConfig() {
        combineCosts.put(SoulRarity.UNCOMMON, 10);
        combineCosts.put(SoulRarity.RARE, 25);
        combineCosts.put(SoulRarity.EPIC, 75);
        combineCosts.put(SoulRarity.LEGENDARY, 200);
        combineCosts.put(SoulRarity.MYTHIC, 500);

        reforgeCosts.put(SoulRarity.COMMON, 20);
        reforgeCosts.put(SoulRarity.UNCOMMON, 40);
        reforgeCosts.put(SoulRarity.RARE, 80);
        reforgeCosts.put(SoulRarity.EPIC, 160);
        reforgeCosts.put(SoulRarity.LEGENDARY, 320);
        reforgeCosts.put(SoulRarity.MYTHIC, 640);

        recipeIngredients.put('S', Material.SOUL_SAND);
        recipeIngredients.put('P', Material.POLISHED_BLACKSTONE);
        recipeIngredients.put('L', Material.SOUL_LANTERN);
        recipeIngredients.put('O', Material.OBSIDIAN);
        recipeIngredients.put('N', Material.NETHERITE_SCRAP);
    }

    public static SoulTableConfig load(FileConfiguration yaml) {
        SoulTableConfig cfg = new SoulTableConfig();
        cfg.reload(yaml);
        return cfg;
    }

    /** Re-reads all values into this instance (keeps the reference valid across reloads). */
    public void reload(FileConfiguration yaml) {
        if (yaml == null) return;

        enabled = yaml.getBoolean("souls.table.enabled", true);
        baseBlock = material(yaml.getString("souls.table.base-block"), Material.SMITHING_TABLE);
        usePublic = yaml.getBoolean("souls.table.use-public", true);
        breakOwnerOnly = yaml.getBoolean("souls.table.break-owner-only", true);

        for (SoulRarity rarity : SoulRarity.values()) {
            combineCosts.put(rarity, Math.max(0,
                    yaml.getInt("souls.table.costs.combine." + rarity.name(), combineCosts.getOrDefault(rarity, 0))));
            reforgeCosts.put(rarity, Math.max(0,
                    yaml.getInt("souls.table.costs.reforge." + rarity.name(), reforgeCosts.getOrDefault(rarity, 0))));
        }
        enhanceBase = Math.max(1, yaml.getInt("souls.table.costs.enhance.base", 10));
        enhanceExponent = Math.max(1, yaml.getInt("souls.table.costs.enhance.exponent", 2));
        enhanceMaxLevel = Math.max(0, yaml.getInt("souls.table.costs.enhance.max-level", 10));
        enhancePerLevelBonus = clamp(yaml.getDouble("souls.table.costs.enhance.per-level-bonus", 0.025), 0.0, 1.0);
        salvageConfirmationSeconds = Math.max(0, yaml.getInt("souls.table.costs.salvage.confirmation-seconds", 5));

        recipeEnabled = yaml.getBoolean("souls.table.recipe.enabled", true);
        List<String> pattern = yaml.getStringList("souls.table.recipe.pattern");
        if (pattern != null && pattern.size() == 3) {
            recipePattern.clear();
            for (String row : pattern) recipePattern.add(row == null ? "" : row);
        }
        ConfigurationSection ingredients = yaml.getConfigurationSection("souls.table.recipe.ingredients");
        if (ingredients != null) {
            Map<Character, Material> parsed = new LinkedHashMap<>();
            for (String key : ingredients.getKeys(false)) {
                if (key == null || key.isEmpty()) continue;
                Material mat = material(ingredients.getString(key), null);
                if (mat != null) parsed.put(key.charAt(0), mat);
            }
            if (!parsed.isEmpty()) {
                recipeIngredients.clear();
                recipeIngredients.putAll(parsed);
            }
        }

        statsEnabled = yaml.getBoolean("souls.stats.enabled", true);
        backfillLegacy = yaml.getBoolean("souls.stats.backfill-legacy", true);
        statConfig.loadRarity(yaml.getConfigurationSection("souls.stats.rarity-multipliers"));
        statConfig.loadVariance(yaml.getConfigurationSection("souls.stats.variance"));
        statConfig.loadCaps(yaml.getConfigurationSection("souls.stats.caps"));
    }

    /**
     * Adds any missing {@code souls.table.*} / {@code souls.stats.*} keys to summon.yml without
     * touching values the admin already set.
     */
    public static void backfill(FileConfiguration cfg) {
        if (cfg == null) return;
        set(cfg, "souls.table.enabled", true);
        set(cfg, "souls.table.base-block", "SMITHING_TABLE");
        set(cfg, "souls.table.use-public", true);
        set(cfg, "souls.table.break-owner-only", true);
        for (Map.Entry<SoulRarity, Integer> e : Map.of(
                SoulRarity.UNCOMMON, 10, SoulRarity.RARE, 25, SoulRarity.EPIC, 75,
                SoulRarity.LEGENDARY, 200, SoulRarity.MYTHIC, 500).entrySet()) {
            set(cfg, "souls.table.costs.combine." + e.getKey().name(), e.getValue());
        }
        set(cfg, "souls.table.costs.enhance.base", 10);
        set(cfg, "souls.table.costs.enhance.exponent", 2);
        set(cfg, "souls.table.costs.enhance.max-level", 10);
        set(cfg, "souls.table.costs.enhance.per-level-bonus", 0.025);
        set(cfg, "souls.table.costs.reforge.COMMON", 20);
        set(cfg, "souls.table.costs.reforge.UNCOMMON", 40);
        set(cfg, "souls.table.costs.reforge.RARE", 80);
        set(cfg, "souls.table.costs.reforge.EPIC", 160);
        set(cfg, "souls.table.costs.reforge.LEGENDARY", 320);
        set(cfg, "souls.table.costs.reforge.MYTHIC", 640);
        set(cfg, "souls.table.costs.salvage.confirmation-seconds", 5);
        set(cfg, "souls.table.recipe.enabled", true);
        if (!cfg.isList("souls.table.recipe.pattern")) {
            cfg.set("souls.table.recipe.pattern", List.of("SSS", "PLP", "ONO"));
        }
        set(cfg, "souls.table.recipe.ingredients.S", "SOUL_SAND");
        set(cfg, "souls.table.recipe.ingredients.P", "POLISHED_BLACKSTONE");
        set(cfg, "souls.table.recipe.ingredients.L", "SOUL_LANTERN");
        set(cfg, "souls.table.recipe.ingredients.O", "OBSIDIAN");
        set(cfg, "souls.table.recipe.ingredients.N", "NETHERITE_SCRAP");

        set(cfg, "souls.stats.enabled", true);
        set(cfg, "souls.stats.backfill-legacy", true);
        SoulStatConfig defaults = new SoulStatConfig();
        for (SoulRarity rarity : SoulRarity.values()) {
            set(cfg, "souls.stats.rarity-multipliers." + rarity.name(), defaults.rarityMultiplier(rarity));
        }
        for (Map.Entry<String, Double> e : defaults.varianceView().entrySet()) {
            set(cfg, "souls.stats.variance." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Double> e : defaults.capsView().entrySet()) {
            set(cfg, "souls.stats.caps." + e.getKey(), e.getValue());
        }
    }

    private static void set(FileConfiguration cfg, String path, Object value) {
        if (!cfg.isSet(path)) cfg.set(path, value);
    }

    private static Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        Material mat = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return mat != null ? mat : fallback;
    }

    private static double clamp(double v, double min, double max) {
        if (!Double.isFinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------ getters

    public boolean isEnabled() { return enabled; }
    public Material getBaseBlock() { return baseBlock; }
    public boolean isUsePublic() { return usePublic; }
    public boolean isBreakOwnerOnly() { return breakOwnerOnly; }

    public int combineCost(SoulRarity target) {
        if (target == null) return Integer.MAX_VALUE;
        return combineCosts.getOrDefault(target, Integer.MAX_VALUE);
    }

    /** Cost to go from {@code currentLevel} to {@code currentLevel + 1}: base * (L + 1)^exponent. */
    public int enhanceCost(int currentLevel) {
        int level = Math.max(0, currentLevel);
        return (int) Math.max(0L, (long) enhanceBase * (long) Math.round(Math.pow(level + 1, enhanceExponent)));
    }

    public int getEnhanceMaxLevel() { return enhanceMaxLevel; }
    public double getEnhancePerLevelBonus() { return enhancePerLevelBonus; }

    public int reforgeCost(SoulRarity rarity) {
        if (rarity == null) return Integer.MAX_VALUE;
        return reforgeCosts.getOrDefault(rarity, Integer.MAX_VALUE);
    }

    public int getSalvageConfirmationSeconds() { return salvageConfirmationSeconds; }

    public boolean isRecipeEnabled() { return recipeEnabled; }
    public List<String> getRecipePattern() { return List.copyOf(recipePattern); }
    public Map<Character, Material> getRecipeIngredients() { return Map.copyOf(recipeIngredients); }

    public boolean isStatsEnabled() { return statsEnabled; }
    public boolean isBackfillLegacy() { return backfillLegacy; }
    public SoulStatConfig getStatConfig() { return statConfig; }

    public SoulRarity nextRarity(SoulRarity rarity) {
        if (rarity == null) return null;
        int next = rarity.ordinal() + 1;
        SoulRarity[] values = SoulRarity.values();
        return next >= values.length ? null : values[next];
    }
}
