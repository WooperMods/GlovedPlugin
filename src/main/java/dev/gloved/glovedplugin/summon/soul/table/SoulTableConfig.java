package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
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

    /**
     * Auto-partitioned absolute stat bands per rarity (global bounds drive 6 bands with slight overlap).
     * Legacy {@code souls.stats.rarity-multipliers} / {@code souls.stats.variance} keys are deprecated
     * and ignored in favour of {@code souls.stats.bounds.*} and {@code souls.stats.ranges.*}.
     */
    public static final class SoulStatConfig {
        /** Inclusive roll/final range for one stat at one rarity. {@code minExclusive}/{@code maxExclusive}
         *  mark endpoint-only values ({@code S.min} Common-only, {@code S.max} Mythic-only). */
        public record Band(double min, double max, boolean minExclusive, boolean maxExclusive) {
            public double clampRolled(double value) {
                if (!Double.isFinite(value)) value = min;
                if (value < min) value = min;
                if (value > max) value = max;
                if (minExclusive && value <= min) value = Math.nextUp(min);
                if (maxExclusive && value >= max) value = Math.nextDown(max);
                if (value < min) value = min;
                if (value > max) value = max;
                return value;
            }

            public double clampFinal(double value) {
                if (!Double.isFinite(value)) value = min;
                if (minExclusive) {
                    if (value <= min) value = Math.nextUp(min);
                } else {
                    if (value < min) value = min;
                }
                if (maxExclusive) {
                    if (value >= max) value = Math.nextDown(max);
                } else {
                    if (value > max) value = max;
                }
                return value;
            }
        }

        private static final List<String> STAT_KEYS = List.of(
                SoulBaseStats.KEY_HEALTH,
                SoulBaseStats.KEY_ATTACK_DAMAGE,
                SoulBaseStats.KEY_MOVEMENT_SPEED,
                SoulBaseStats.KEY_ATTACK_SPEED
        );
        private static final double DEFAULT_OVERLAP = 0.06;

        private final Map<String, Double> boundsMin = new LinkedHashMap<>();
        private final Map<String, Double> boundsMax = new LinkedHashMap<>();
        private final double[] splitWeights = new double[SoulRarity.values().length];
        private double overlap = DEFAULT_OVERLAP;
        private final Map<String, EnumMap<SoulRarity, Band>> bands = new LinkedHashMap<>();
        private boolean legacyWarned = false;

        public SoulStatConfig() {
            boundsMin.put(SoulBaseStats.KEY_HEALTH, 1.0);
            boundsMin.put(SoulBaseStats.KEY_ATTACK_DAMAGE, 4.0);
            boundsMin.put(SoulBaseStats.KEY_MOVEMENT_SPEED, 1.0 / 43.17);
            boundsMin.put(SoulBaseStats.KEY_ATTACK_SPEED, SoulBaseStats.MIN_ATTACK_SPEED_APS);

            boundsMax.put(SoulBaseStats.KEY_HEALTH, 200.0);
            boundsMax.put(SoulBaseStats.KEY_ATTACK_DAMAGE, 30.0);
            boundsMax.put(SoulBaseStats.KEY_MOVEMENT_SPEED, 14.0 / 43.17);
            boundsMax.put(SoulBaseStats.KEY_ATTACK_SPEED, SoulBaseStats.MAX_ATTACK_SPEED_APS);

            java.util.Arrays.fill(splitWeights, 1.0);
            recomputeBands();
        }

        public double globalMin(String key) { return boundsMin.getOrDefault(key, 0.0); }
        public double globalMax(String key) { return boundsMax.getOrDefault(key, 0.0); }
        /** Back-compat alias: global ceiling for a stat. */
        public double cap(String key) { return globalMax(key); }

        public Band band(String key, SoulRarity rarity) {
            EnumMap<SoulRarity, Band> byRarity = bands.get(key);
            if (byRarity == null) byRarity = bands.get(SoulBaseStats.KEY_HEALTH);
            if (byRarity == null) return new Band(0, 0, false, false);
            SoulRarity r = rarity != null ? rarity : SoulRarity.COMMON;
            Band b = byRarity.get(r);
            return b != null ? b : byRarity.get(SoulRarity.COMMON);
        }

        public Map<String, Double> capsView() {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String key : STAT_KEYS) out.put(key, globalMax(key));
            return Map.copyOf(out);
        }

        public void recomputeBands() {
            for (String key : STAT_KEYS) {
                bands.put(key, computeBandsFor(key));
            }
        }

        private EnumMap<SoulRarity, Band> computeBandsFor(String key) {
            SoulRarity[] rarities = SoulRarity.values();
            double min = boundsMin.getOrDefault(key, 0.0);
            double max = boundsMax.getOrDefault(key, min);
            if (!Double.isFinite(min)) min = 0.0;
            if (!Double.isFinite(max) || max < min) max = min;
            double range = max - min;
            double weightSum = 0.0;
            for (double w : splitWeights) weightSum += Math.max(0.0001, w);
            if (weightSum <= 0.0) weightSum = rarities.length;
            double[] coreMin = new double[rarities.length];
            double[] coreMax = new double[rarities.length];
            double cursor = min;
            for (int i = 0; i < rarities.length; i++) {
                coreMin[i] = cursor;
                double w = Math.max(0.0001, splitWeights[i]);
                double width = (i == rarities.length - 1) ? (max - cursor) : (range * (w / weightSum));
                coreMax[i] = cursor + width;
                cursor = coreMax[i];
            }
            coreMax[rarities.length - 1] = max;
            double ov = clamp(overlap, 0.0, 0.45);
            EnumMap<SoulRarity, Band> out = new EnumMap<>(SoulRarity.class);
            for (int i = 0; i < rarities.length; i++) {
                double prevWidth = i > 0 ? Math.max(0.0, coreMax[i - 1] - coreMin[i - 1]) : 0.0;
                double nextWidth = i + 1 < rarities.length ? Math.max(0.0, coreMax[i + 1] - coreMin[i + 1]) : 0.0;
                double lo = coreMin[i] - (i > 0 ? prevWidth * ov : 0.0);
                double hi = coreMax[i] + (i + 1 < rarities.length ? nextWidth * ov : 0.0);
                // Endpoint exclusivity: only Common may be S.min, only Mythic may be S.max.
                boolean minExclusive = (i > 0);
                boolean maxExclusive = (i < rarities.length - 1);
                if (minExclusive) lo = Math.max(lo, Math.nextUp(min));
                if (maxExclusive) hi = Math.min(hi, Math.nextDown(max));
                lo = clamp(lo, minExclusive ? Math.nextUp(min) : min, hi);
                hi = clamp(hi, lo, maxExclusive ? Math.nextDown(max) : max);
                out.put(rarities[i], new Band(lo, hi, minExclusive, maxExclusive));
            }
            return out;
        }

        public void load(FileConfiguration yaml, java.util.logging.Logger logger) {
            if (yaml == null) return;
            // Legacy keys are deprecated; warn once and ignore their semantics.
            if (!legacyWarned && (yaml.isConfigurationSection("souls.stats.rarity-multipliers")
                    || yaml.isConfigurationSection("souls.stats.variance"))) {
                legacyWarned = true;
                if (logger != null) {
                    logger.warning("[Souls] souls.stats.rarity-multipliers/variance are deprecated and ignored; "
                            + "using souls.stats.bounds/ranges (auto-partitioned absolute bands).");
                }
            }

            loadBound(yaml, "souls.stats.bounds.health", SoulBaseStats.KEY_HEALTH, 1.0, Double.POSITIVE_INFINITY);
            loadBound(yaml, "souls.stats.bounds.attack-damage", SoulBaseStats.KEY_ATTACK_DAMAGE, 4.0, Double.POSITIVE_INFINITY);
            loadBound(yaml, "souls.stats.bounds.movement-speed", SoulBaseStats.KEY_MOVEMENT_SPEED, 0.0, Double.POSITIVE_INFINITY);
            loadBound(yaml, "souls.stats.bounds.attack-speed", SoulBaseStats.KEY_ATTACK_SPEED,
                    SoulBaseStats.MIN_ATTACK_SPEED_APS, SoulBaseStats.MAX_ATTACK_SPEED_APS);

            overlap = clamp(yaml.getDouble("souls.stats.ranges.overlap", DEFAULT_OVERLAP), 0.0, 0.45);
            ConfigurationSection split = yaml.getConfigurationSection("souls.stats.ranges.split");
            if (split != null) {
                SoulRarity[] rarities = SoulRarity.values();
                for (int i = 0; i < rarities.length; i++) {
                    splitWeights[i] = Math.max(0.0001, split.getDouble(rarities[i].name(), splitWeights[i]));
                }
            }

            // Keep bounds sane and respect hard floors/caps.
            boundsMin.put(SoulBaseStats.KEY_HEALTH, Math.max(0.0001, boundsMin.get(SoulBaseStats.KEY_HEALTH)));
            boundsMin.put(SoulBaseStats.KEY_ATTACK_DAMAGE, Math.max(4.0, boundsMin.get(SoulBaseStats.KEY_ATTACK_DAMAGE)));
            boundsMin.put(SoulBaseStats.KEY_MOVEMENT_SPEED, Math.max(0.0, boundsMin.get(SoulBaseStats.KEY_MOVEMENT_SPEED)));
            boundsMin.put(SoulBaseStats.KEY_ATTACK_SPEED, clamp(boundsMin.get(SoulBaseStats.KEY_ATTACK_SPEED),
                    SoulBaseStats.MIN_ATTACK_SPEED_APS, SoulBaseStats.MAX_ATTACK_SPEED_APS));
            boundsMax.put(SoulBaseStats.KEY_ATTACK_SPEED, clamp(boundsMax.get(SoulBaseStats.KEY_ATTACK_SPEED),
                    boundsMin.get(SoulBaseStats.KEY_ATTACK_SPEED), SoulBaseStats.MAX_ATTACK_SPEED_APS));
            for (String key : STAT_KEYS) {
                if (boundsMax.getOrDefault(key, 0.0) < boundsMin.getOrDefault(key, 0.0)) {
                    boundsMax.put(key, boundsMin.getOrDefault(key, 0.0));
                }
            }

            recomputeBands();
        }

        private void loadBound(FileConfiguration yaml, String path, String key, double hardMin, double hardMax) {
            double dMin = boundsMin.getOrDefault(key, 0.0);
            double dMax = boundsMax.getOrDefault(key, dMin);
            if (yaml.isConfigurationSection(path)) {
                ConfigurationSection sec = yaml.getConfigurationSection(path);
                if (sec != null) {
                    dMin = sec.getDouble("min", dMin);
                    dMax = sec.getDouble("max", dMax);
                }
            } else {
                dMin = yaml.getDouble(path + ".min", dMin);
                dMax = yaml.getDouble(path + ".max", dMax);
            }
            dMin = clamp(dMin, hardMin, hardMax);
            dMax = clamp(dMax, hardMin, hardMax);
            if (dMax < dMin) dMax = dMin;
            boundsMin.put(key, dMin);
            boundsMax.put(key, dMax);
        }
    }

    // --- table toggles -------------------------------------------------------
    private boolean enabled = true;
    private Material baseBlock = Material.SMITHING_TABLE;
    private boolean usePublic = true;
    private boolean breakOwnerOnly = true;

    // --- costs --------------------------------------------------------------
    private final EnumMap<SoulRarity, Integer> combineCosts = new EnumMap<>(SoulRarity.class);
    private double enhanceBase = 8.0;
    private double enhanceExponent = 1.8;
    private int enhanceOffset = 1;
    private int enhanceMinCost = 1;
    private int enhanceMaxCost = 0; // <= 0 means no cap
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
        combineCosts.put(SoulRarity.UNCOMMON, 8);
        combineCosts.put(SoulRarity.RARE, 20);
        combineCosts.put(SoulRarity.EPIC, 55);
        combineCosts.put(SoulRarity.LEGENDARY, 150);
        combineCosts.put(SoulRarity.MYTHIC, 425);

        reforgeCosts.put(SoulRarity.COMMON, 12);
        reforgeCosts.put(SoulRarity.UNCOMMON, 20);
        reforgeCosts.put(SoulRarity.RARE, 40);
        reforgeCosts.put(SoulRarity.EPIC, 80);
        reforgeCosts.put(SoulRarity.LEGENDARY, 150);
        reforgeCosts.put(SoulRarity.MYTHIC, 275);

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
        enhanceBase = Math.max(0.0, yaml.getDouble("souls.table.costs.enhance.base", enhanceBase));
        enhanceExponent = Math.max(0.1, yaml.getDouble("souls.table.costs.enhance.exponent", enhanceExponent));
        enhanceOffset = yaml.getInt("souls.table.costs.enhance.offset", enhanceOffset);
        enhanceMinCost = Math.max(0, yaml.getInt("souls.table.costs.enhance.min-cost", enhanceMinCost));
        enhanceMaxCost = yaml.getInt("souls.table.costs.enhance.max-cost", enhanceMaxCost);
        enhanceMaxLevel = Math.max(0, yaml.getInt("souls.table.costs.enhance.max-level", enhanceMaxLevel));
        enhancePerLevelBonus = clamp(yaml.getDouble("souls.table.costs.enhance.per-level-bonus", enhancePerLevelBonus), 0.0, 1.0);
        salvageConfirmationSeconds = Math.max(0, yaml.getInt("souls.table.costs.salvage.confirmation-seconds", salvageConfirmationSeconds));

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
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("GlovedPlugin");
        statConfig.load(yaml, logger);
    }

    /**
     * Adds any missing {@code souls.table.*} / {@code souls.stats.*} keys to summon.yml without
     * touching values the admin already set. Legacy {@code rarity-multipliers}/{@code variance}
     * keys are no longer backfilled.
     */
    public static void backfill(FileConfiguration cfg) {
        if (cfg == null) return;
        set(cfg, "souls.table.enabled", true);
        set(cfg, "souls.table.base-block", "SMITHING_TABLE");
        set(cfg, "souls.table.use-public", true);
        set(cfg, "souls.table.break-owner-only", true);
        for (Map.Entry<SoulRarity, Integer> e : Map.of(
                SoulRarity.UNCOMMON, 8, SoulRarity.RARE, 20, SoulRarity.EPIC, 55,
                SoulRarity.LEGENDARY, 150, SoulRarity.MYTHIC, 425).entrySet()) {
            set(cfg, "souls.table.costs.combine." + e.getKey().name(), e.getValue());
        }
        set(cfg, "souls.table.costs.enhance.base", 8.0);
        set(cfg, "souls.table.costs.enhance.exponent", 1.8);
        set(cfg, "souls.table.costs.enhance.offset", 1);
        set(cfg, "souls.table.costs.enhance.min-cost", 1);
        set(cfg, "souls.table.costs.enhance.max-cost", 0);
        set(cfg, "souls.table.costs.enhance.max-level", 10);
        set(cfg, "souls.table.costs.enhance.per-level-bonus", 0.025);
        set(cfg, "souls.table.costs.reforge.COMMON", 12);
        set(cfg, "souls.table.costs.reforge.UNCOMMON", 20);
        set(cfg, "souls.table.costs.reforge.RARE", 40);
        set(cfg, "souls.table.costs.reforge.EPIC", 80);
        set(cfg, "souls.table.costs.reforge.LEGENDARY", 150);
        set(cfg, "souls.table.costs.reforge.MYTHIC", 275);
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
        set(cfg, "souls.stats.bounds.health.min", 1.0);
        set(cfg, "souls.stats.bounds.health.max", 200.0);
        set(cfg, "souls.stats.bounds.attack-damage.min", 4.0);
        set(cfg, "souls.stats.bounds.attack-damage.max", 30.0);
        set(cfg, "souls.stats.bounds.movement-speed.min", 1.0 / 43.17);
        set(cfg, "souls.stats.bounds.movement-speed.max", 14.0 / 43.17);
        set(cfg, "souls.stats.bounds.attack-speed.min", SoulBaseStats.MIN_ATTACK_SPEED_APS);
        set(cfg, "souls.stats.bounds.attack-speed.max", SoulBaseStats.MAX_ATTACK_SPEED_APS);
        set(cfg, "souls.stats.ranges.overlap", 0.06);
        for (SoulRarity rarity : SoulRarity.values()) {
            set(cfg, "souls.stats.ranges.split." + rarity.name(), 1.0);
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

    /** Cost to go from {@code currentLevel} to {@code currentLevel + 1}: base * (level + offset)^exponent. */
    public int enhanceCost(int currentLevel) {
        int level = Math.max(0, currentLevel);
        double raw = Math.max(0.0, enhanceBase) * Math.pow(level + enhanceOffset, Math.max(0.1, enhanceExponent));
        long cost = Math.round(raw);
        cost = Math.max(Math.max(0, enhanceMinCost), cost);
        if (enhanceMaxCost > 0) {
            cost = Math.min(enhanceMaxCost, cost);
        }
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, cost));
    }

    public double getEnhanceBase() { return enhanceBase; }
    public double getEnhanceExponent() { return enhanceExponent; }
    public int getEnhanceOffset() { return enhanceOffset; }
    public int getEnhanceMinCost() { return enhanceMinCost; }
    public int getEnhanceMaxCost() { return enhanceMaxCost; }
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
