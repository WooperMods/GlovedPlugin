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
                SoulBaseStats.KEY_ATTACK_SPEED,
                SoulBaseStats.KEY_DEFENSE
        );
        private static final double DEFAULT_OVERLAP = 0.06;

        private final Map<String, Double> boundsMin = new LinkedHashMap<>();
        private final Map<String, Double> boundsMax = new LinkedHashMap<>();
        private final double[] splitWeights = new double[SoulRarity.values().length];
        private final Map<String, double[]> splitWeightsByStat = new LinkedHashMap<>();
        private final Map<String, Double> rollSkewByStat = new LinkedHashMap<>();
        private double overlap = DEFAULT_OVERLAP;
        private double defaultRollSkew = 1.0;
        private final Map<String, EnumMap<SoulRarity, Band>> bands = new LinkedHashMap<>();
        private boolean legacyWarned = false;

        public SoulStatConfig() {
            boundsMin.put(SoulBaseStats.KEY_HEALTH, 1.0);
            boundsMin.put(SoulBaseStats.KEY_ATTACK_DAMAGE, 4.0);
            boundsMin.put(SoulBaseStats.KEY_MOVEMENT_SPEED, 1.0 / 43.17);
            boundsMin.put(SoulBaseStats.KEY_ATTACK_SPEED, SoulBaseStats.MIN_ATTACK_SPEED_APS);
            boundsMin.put(SoulBaseStats.KEY_DEFENSE, SoulBaseStats.MIN_DEFENSE);

            boundsMax.put(SoulBaseStats.KEY_HEALTH, 100.0);
            boundsMax.put(SoulBaseStats.KEY_ATTACK_DAMAGE, 30.0);
            boundsMax.put(SoulBaseStats.KEY_MOVEMENT_SPEED, 14.0 / 43.17);
            boundsMax.put(SoulBaseStats.KEY_ATTACK_SPEED, SoulBaseStats.MAX_ATTACK_SPEED_APS);
            boundsMax.put(SoulBaseStats.KEY_DEFENSE, SoulBaseStats.MAX_DEFENSE);

            java.util.Arrays.fill(splitWeights, 1.0);
            // HP: lower rarities get less of the range, higher rarities more, and high rolls are harder.
            setDefaultSplit(SoulBaseStats.KEY_HEALTH, new double[]{0.45, 0.55, 0.80, 1.10, 1.60, 2.50});
            rollSkewByStat.put(SoulBaseStats.KEY_HEALTH, 2.5);
            recomputeBands();
        }

        private void setDefaultSplit(String key, double[] weights) {
            if (key == null || weights == null) return;
            SoulRarity[] rarities = SoulRarity.values();
            double[] copy = new double[rarities.length];
            for (int i = 0; i < rarities.length; i++) {
                copy[i] = i < weights.length ? Math.max(0.0001, weights[i]) : 1.0;
            }
            splitWeightsByStat.put(key, copy);
        }

        public double globalMin(String key) { return boundsMin.getOrDefault(key, 0.0); }
        public double globalMax(String key) { return boundsMax.getOrDefault(key, 0.0); }
        /** Back-compat alias: global ceiling for a stat. */
        public double cap(String key) { return globalMax(key); }

        public double rollSkew(String key) {
            Double v = key != null ? rollSkewByStat.get(key) : null;
            double skew = v != null ? v : defaultRollSkew;
            if (!Double.isFinite(skew) || skew <= 0.0) return 1.0;
            return skew;
        }

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

        private double[] weightsFor(String key) {
            double[] per = key != null ? splitWeightsByStat.get(key) : null;
            return per != null ? per : splitWeights;
        }

        private EnumMap<SoulRarity, Band> computeBandsFor(String key) {
            SoulRarity[] rarities = SoulRarity.values();
            double min = boundsMin.getOrDefault(key, 0.0);
            double max = boundsMax.getOrDefault(key, min);
            if (!Double.isFinite(min)) min = 0.0;
            if (!Double.isFinite(max) || max < min) max = min;
            double range = max - min;
            double[] weights = weightsFor(key);
            double weightSum = 0.0;
            for (double w : weights) weightSum += Math.max(0.0001, w);
            if (weightSum <= 0.0) weightSum = rarities.length;
            double[] coreMin = new double[rarities.length];
            double[] coreMax = new double[rarities.length];
            double cursor = min;
            for (int i = 0; i < rarities.length; i++) {
                coreMin[i] = cursor;
                double w = Math.max(0.0001, weights[i]);
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
            loadBound(yaml, "souls.stats.bounds.defense", SoulBaseStats.KEY_DEFENSE,
                    SoulBaseStats.MIN_DEFENSE, SoulBaseStats.MAX_DEFENSE);

            overlap = clamp(yaml.getDouble("souls.stats.ranges.overlap", DEFAULT_OVERLAP), 0.0, 0.45);

            // Global split (back-compat): souls.stats.ranges.split.<RARITY>
            ConfigurationSection split = yaml.getConfigurationSection("souls.stats.ranges.split");
            SoulRarity[] rarities = SoulRarity.values();
            boolean hasGlobalSplitEntries = false;
            if (split != null) {
                for (SoulRarity r : rarities) {
                    if (split.isSet(r.name()) && !split.isConfigurationSection(r.name())) {
                        hasGlobalSplitEntries = true;
                        break;
                    }
                }
                if (hasGlobalSplitEntries) {
                    for (int i = 0; i < rarities.length; i++) {
                        if (!split.isConfigurationSection(rarities[i].name())) {
                            splitWeights[i] = Math.max(0.0001, split.getDouble(rarities[i].name(), splitWeights[i]));
                        }
                    }
                }
                // Per-stat splits: souls.stats.ranges.split.<stat>.<RARITY>
                for (String key : STAT_KEYS) {
                    if (split.isConfigurationSection(key)) {
                        ConfigurationSection sec = split.getConfigurationSection(key);
                        if (sec == null) continue;
                        double[] arr = new double[rarities.length];
                        double[] base = weightsFor(key);
                        for (int i = 0; i < rarities.length; i++) {
                            arr[i] = Math.max(0.0001, sec.getDouble(rarities[i].name(), base[i]));
                        }
                        splitWeightsByStat.put(key, arr);
                    }
                }
            }

            // Roll skew: global default + per-stat overrides (souls.stats.ranges.roll-skew / roll-skew.<stat>)
            String skewPath = "souls.stats.ranges.roll-skew";
            if (yaml.isConfigurationSection(skewPath)) {
                ConfigurationSection sec = yaml.getConfigurationSection(skewPath);
                if (sec != null) {
                    defaultRollSkew = Math.max(0.01, sec.getDouble("default", defaultRollSkew));
                    for (String key : STAT_KEYS) {
                        if (sec.isSet(key) && !sec.isConfigurationSection(key)) {
                            rollSkewByStat.put(key, Math.max(0.01, sec.getDouble(key, rollSkew(key))));
                        }
                    }
                }
            } else if (yaml.isSet(skewPath)) {
                defaultRollSkew = Math.max(0.01, yaml.getDouble(skewPath, defaultRollSkew));
            }

            // Keep bounds sane and respect hard floors/caps.
            boundsMin.put(SoulBaseStats.KEY_HEALTH, Math.max(0.0001, boundsMin.get(SoulBaseStats.KEY_HEALTH)));
            boundsMin.put(SoulBaseStats.KEY_ATTACK_DAMAGE, Math.max(4.0, boundsMin.get(SoulBaseStats.KEY_ATTACK_DAMAGE)));
            boundsMin.put(SoulBaseStats.KEY_MOVEMENT_SPEED, Math.max(0.0, boundsMin.get(SoulBaseStats.KEY_MOVEMENT_SPEED)));
            boundsMin.put(SoulBaseStats.KEY_ATTACK_SPEED, clamp(boundsMin.get(SoulBaseStats.KEY_ATTACK_SPEED),
                    SoulBaseStats.MIN_ATTACK_SPEED_APS, SoulBaseStats.MAX_ATTACK_SPEED_APS));
            boundsMax.put(SoulBaseStats.KEY_ATTACK_SPEED, clamp(boundsMax.get(SoulBaseStats.KEY_ATTACK_SPEED),
                    boundsMin.get(SoulBaseStats.KEY_ATTACK_SPEED), SoulBaseStats.MAX_ATTACK_SPEED_APS));

            // DEF base/roll cap [1,13.75] (~55% base armor damage negation, toughness 0). Enhancement can exceed caps.
            boundsMin.put(SoulBaseStats.KEY_DEFENSE, clamp(boundsMin.getOrDefault(SoulBaseStats.KEY_DEFENSE, SoulBaseStats.MIN_DEFENSE),
                    SoulBaseStats.MIN_DEFENSE, SoulBaseStats.MAX_DEFENSE));
            boundsMax.put(SoulBaseStats.KEY_DEFENSE, clamp(boundsMax.getOrDefault(SoulBaseStats.KEY_DEFENSE, SoulBaseStats.MAX_DEFENSE),
                    boundsMin.get(SoulBaseStats.KEY_DEFENSE), SoulBaseStats.MAX_DEFENSE));

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

    // --- essence condense ---------------------------------------------------
    private boolean condenseEnabled = true;
    private int condenseRate = 128;
    private boolean condenseReversible = true;
    private Material condensedMaterial = Material.AMETHYST_SHARD;
    private String condensedName = "&bCondensed Soul Essence";

    // --- catalyst -----------------------------------------------------------
    private boolean catalystEnabled = true;
    private Material catalystMaterial = Material.NETHER_STAR;
    private String catalystName = "&dSoul Catalyst";
    private boolean catalystRecipeEnabled = true;
    private final List<String> catalystRecipePattern = new ArrayList<>(List.of("NEN", "ECE", "NEN"));
    private final Map<Character, Material> catalystRecipeIngredients = new LinkedHashMap<>();
    private final Map<Character, String> catalystRecipeSpecialIngredients = new LinkedHashMap<>();
    private int catalystRecipeAmount = 1;

    // --- infusion -----------------------------------------------------------
    private boolean infusionEnabled = true;
    private SoulRarity infusionMinRarity = SoulRarity.EPIC;
    private int infusionMinEnhanceLevel = 5;
    private boolean infusionStrictRollConditions = false;
    private final EnumMap<SoulRarity, Integer> infusionTraitCaps = new EnumMap<>(SoulRarity.class);
    private final EnumMap<SoulRarity, Integer> infusionEssenceAdd = new EnumMap<>(SoulRarity.class);
    private final EnumMap<SoulRarity, Integer> infusionEssenceReplace = new EnumMap<>(SoulRarity.class);
    private int infusionCatalystAdd = 2;
    private int infusionCatalystReplace = 1;

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
        recipeIngredients.put('P', Material.COBBLESTONE);
        recipeIngredients.put('L', Material.SOUL_TORCH);
        recipeIngredients.put('O', Material.OAK_PLANKS);
        recipeIngredients.put('N', Material.IRON_INGOT);

        catalystRecipeIngredients.put('N', Material.NETHERITE_INGOT);
        catalystRecipeIngredients.put('E', Material.ECHO_SHARD);
        catalystRecipeSpecialIngredients.put('C', "CONDENSED_SOUL_ESSENCE");

        infusionTraitCaps.put(SoulRarity.COMMON, 1);
        infusionTraitCaps.put(SoulRarity.UNCOMMON, 1);
        infusionTraitCaps.put(SoulRarity.RARE, 3);
        infusionTraitCaps.put(SoulRarity.EPIC, 3);
        infusionTraitCaps.put(SoulRarity.LEGENDARY, 4);
        infusionTraitCaps.put(SoulRarity.MYTHIC, 5);

        infusionEssenceAdd.put(SoulRarity.COMMON, 0);
        infusionEssenceAdd.put(SoulRarity.UNCOMMON, 0);
        infusionEssenceAdd.put(SoulRarity.RARE, 500);
        infusionEssenceAdd.put(SoulRarity.EPIC, 1200);
        infusionEssenceAdd.put(SoulRarity.LEGENDARY, 3000);
        infusionEssenceAdd.put(SoulRarity.MYTHIC, 7000);

        infusionEssenceReplace.put(SoulRarity.COMMON, 0);
        infusionEssenceReplace.put(SoulRarity.UNCOMMON, 0);
        infusionEssenceReplace.put(SoulRarity.RARE, 300);
        infusionEssenceReplace.put(SoulRarity.EPIC, 750);
        infusionEssenceReplace.put(SoulRarity.LEGENDARY, 1800);
        infusionEssenceReplace.put(SoulRarity.MYTHIC, 4200);
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

        // essence condense
        condenseEnabled = yaml.getBoolean("souls.table.essence.condense.enabled", true);
        condenseRate = Math.max(1, yaml.getInt("souls.table.essence.condense.rate", 128));
        condenseReversible = yaml.getBoolean("souls.table.essence.condense.reversible", true);
        condensedMaterial = material(yaml.getString("souls.table.essence.condense.item.material"), Material.AMETHYST_SHARD);
        condensedName = normalizeLegacyName(yaml.getString("souls.table.essence.condense.item.name", "&bCondensed Soul Essence"));

        // catalyst
        catalystEnabled = yaml.getBoolean("souls.table.catalyst.enabled", true);
        catalystMaterial = material(yaml.getString("souls.table.catalyst.item.material"), Material.NETHER_STAR);
        catalystName = normalizeLegacyName(yaml.getString("souls.table.catalyst.item.name", "&dSoul Catalyst"));
        catalystRecipeEnabled = yaml.getBoolean("souls.table.catalyst.recipe.enabled", true);
        List<String> catPattern = yaml.getStringList("souls.table.catalyst.recipe.shape");
        if (catPattern != null && catPattern.size() == 3) {
            catalystRecipePattern.clear();
            for (String row : catPattern) catalystRecipePattern.add(row == null ? "" : row);
        }
        catalystRecipeAmount = Math.max(1, yaml.getInt("souls.table.catalyst.recipe.amount", 1));
        ConfigurationSection catIngredients = yaml.getConfigurationSection("souls.table.catalyst.recipe.ingredients");
        if (catIngredients != null) {
            Map<Character, Material> parsedMat = new LinkedHashMap<>();
            Map<Character, String> parsedSpecial = new LinkedHashMap<>();
            for (String key : catIngredients.getKeys(false)) {
                if (key == null || key.isEmpty()) continue;
                char c = key.charAt(0);
                String raw = catIngredients.getString(key);
                if (raw == null || raw.isBlank()) continue;
                if ("CONDENSED_SOUL_ESSENCE".equalsIgnoreCase(raw.trim())) {
                    parsedSpecial.put(c, "CONDENSED_SOUL_ESSENCE");
                } else {
                    Material mat = material(raw, null);
                    if (mat != null) parsedMat.put(c, mat);
                }
            }
            if (!parsedMat.isEmpty() || !parsedSpecial.isEmpty()) {
                catalystRecipeIngredients.clear();
                catalystRecipeIngredients.putAll(parsedMat);
                catalystRecipeSpecialIngredients.clear();
                catalystRecipeSpecialIngredients.putAll(parsedSpecial);
            }
        }

        // infusion
        infusionEnabled = yaml.getBoolean("souls.table.infusion.enabled", true);
        infusionMinRarity = SoulRarity.fromString(yaml.getString("souls.table.infusion.min-rarity"), SoulRarity.EPIC);
        infusionMinEnhanceLevel = Math.max(0, yaml.getInt("souls.table.infusion.min-enhance-level", 5));
        infusionStrictRollConditions = yaml.getBoolean("souls.table.infusion.strict-roll-conditions", false);
        for (SoulRarity rarity : SoulRarity.values()) {
            infusionTraitCaps.put(rarity, Math.max(0,
                    yaml.getInt("souls.table.infusion.trait-cap." + rarity.name(), infusionTraitCaps.getOrDefault(rarity, 0))));
            infusionEssenceAdd.put(rarity, Math.max(0,
                    yaml.getInt("souls.table.costs.infusion.essence.ADD." + rarity.name(), infusionEssenceAdd.getOrDefault(rarity, 0))));
            infusionEssenceReplace.put(rarity, Math.max(0,
                    yaml.getInt("souls.table.costs.infusion.essence.REPLACE." + rarity.name(), infusionEssenceReplace.getOrDefault(rarity, 0))));
        }
        infusionCatalystAdd = Math.max(0, yaml.getInt("souls.table.costs.infusion.catalysts.ADD", 2));
        infusionCatalystReplace = Math.max(0, yaml.getInt("souls.table.costs.infusion.catalysts.REPLACE", 1));

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
        set(cfg, "souls.table.recipe.ingredients.P", "COBBLESTONE");
        set(cfg, "souls.table.recipe.ingredients.L", "SOUL_TORCH");
        set(cfg, "souls.table.recipe.ingredients.O", "OAK_PLANKS");
        set(cfg, "souls.table.recipe.ingredients.N", "IRON_INGOT");
        set(cfg, "souls.table.essence.condense.enabled", true);
        set(cfg, "souls.table.essence.condense.rate", 128);
        set(cfg, "souls.table.essence.condense.reversible", true);
        set(cfg, "souls.table.essence.condense.item.material", "AMETHYST_SHARD");
        set(cfg, "souls.table.essence.condense.item.name", "&bCondensed Soul Essence");
        set(cfg, "souls.table.catalyst.enabled", true);
        set(cfg, "souls.table.catalyst.item.material", "NETHER_STAR");
        set(cfg, "souls.table.catalyst.item.name", "&dSoul Catalyst");
        set(cfg, "souls.table.catalyst.recipe.enabled", true);
        if (!cfg.isList("souls.table.catalyst.recipe.shape")) {
            cfg.set("souls.table.catalyst.recipe.shape", List.of("NEN", "ECE", "NEN"));
        }
        set(cfg, "souls.table.catalyst.recipe.ingredients.N", "NETHERITE_INGOT");
        set(cfg, "souls.table.catalyst.recipe.ingredients.E", "ECHO_SHARD");
        set(cfg, "souls.table.catalyst.recipe.ingredients.C", "CONDENSED_SOUL_ESSENCE");
        set(cfg, "souls.table.catalyst.recipe.amount", 1);
        set(cfg, "souls.table.infusion.enabled", true);
        set(cfg, "souls.table.infusion.min-rarity", "EPIC");
        set(cfg, "souls.table.infusion.min-enhance-level", 5);
        set(cfg, "souls.table.infusion.strict-roll-conditions", false);
        set(cfg, "souls.table.infusion.trait-cap.COMMON", 1);
        set(cfg, "souls.table.infusion.trait-cap.UNCOMMON", 1);
        set(cfg, "souls.table.infusion.trait-cap.RARE", 3);
        set(cfg, "souls.table.infusion.trait-cap.EPIC", 3);
        set(cfg, "souls.table.infusion.trait-cap.LEGENDARY", 4);
        set(cfg, "souls.table.infusion.trait-cap.MYTHIC", 5);
        set(cfg, "souls.table.costs.infusion.catalysts.ADD", 2);
        set(cfg, "souls.table.costs.infusion.catalysts.REPLACE", 1);
        set(cfg, "souls.table.costs.infusion.essence.ADD.COMMON", 0);
        set(cfg, "souls.table.costs.infusion.essence.ADD.UNCOMMON", 0);
        set(cfg, "souls.table.costs.infusion.essence.ADD.RARE", 500);
        set(cfg, "souls.table.costs.infusion.essence.ADD.EPIC", 1200);
        set(cfg, "souls.table.costs.infusion.essence.ADD.LEGENDARY", 3000);
        set(cfg, "souls.table.costs.infusion.essence.ADD.MYTHIC", 7000);
        set(cfg, "souls.table.costs.infusion.essence.REPLACE.COMMON", 0);
        set(cfg, "souls.table.costs.infusion.essence.REPLACE.UNCOMMON", 0);
        set(cfg, "souls.table.costs.infusion.essence.REPLACE.RARE", 300);
        set(cfg, "souls.table.costs.infusion.essence.REPLACE.EPIC", 750);
        set(cfg, "souls.table.costs.infusion.essence.REPLACE.LEGENDARY", 1800);
        set(cfg, "souls.table.costs.infusion.essence.REPLACE.MYTHIC", 4200);

        set(cfg, "souls.stats.enabled", true);
        set(cfg, "souls.stats.backfill-legacy", true);
        set(cfg, "souls.stats.bounds.health.min", 1.0);
        set(cfg, "souls.stats.bounds.health.max", 100.0);
        set(cfg, "souls.stats.bounds.attack-damage.min", 4.0);
        set(cfg, "souls.stats.bounds.attack-damage.max", 30.0);
        set(cfg, "souls.stats.bounds.movement-speed.min", 1.0 / 43.17);
        set(cfg, "souls.stats.bounds.movement-speed.max", 14.0 / 43.17);
        set(cfg, "souls.stats.bounds.attack-speed.min", SoulBaseStats.MIN_ATTACK_SPEED_APS);
        set(cfg, "souls.stats.bounds.attack-speed.max", SoulBaseStats.MAX_ATTACK_SPEED_APS);
        // DEF (armor points): min 1; max 13.75 gives up to ~55% base armor damage negation (toughness 0). Enhancement can exceed caps.
        set(cfg, "souls.stats.bounds.defense.min", SoulBaseStats.MIN_DEFENSE);
        set(cfg, "souls.stats.bounds.defense.max", SoulBaseStats.MAX_DEFENSE);
        set(cfg, "souls.stats.ranges.overlap", 0.06);
        for (SoulRarity rarity : SoulRarity.values()) {
            set(cfg, "souls.stats.ranges.split." + rarity.name(), 1.0);
        }
        // HP: smaller low-rarity bands, wider high-rarity bands, and harder-to-roll top-end HP.
        set(cfg, "souls.stats.ranges.split.health.COMMON", 0.45);
        set(cfg, "souls.stats.ranges.split.health.UNCOMMON", 0.55);
        set(cfg, "souls.stats.ranges.split.health.RARE", 0.80);
        set(cfg, "souls.stats.ranges.split.health.EPIC", 1.10);
        set(cfg, "souls.stats.ranges.split.health.LEGENDARY", 1.60);
        set(cfg, "souls.stats.ranges.split.health.MYTHIC", 2.50);
        set(cfg, "souls.stats.ranges.roll-skew.health", 2.5);
    }

    private static void set(FileConfiguration cfg, String path, Object value) {
        if (!cfg.isSet(path)) cfg.set(path, value);
    }

    private static Material material(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        Material mat = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        return mat != null ? mat : fallback;
    }

    private static String normalizeLegacyName(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        // Accept & color codes from config/admin edits and normalize to section-sign formatting.
        return raw.replace('&', '§');
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

    // ------------------------------------------------------------------ condense

    public boolean isCondenseEnabled() { return condenseEnabled; }
    public int condenseRate() { return Math.max(1, condenseRate); }
    public boolean condenseReversible() { return condenseReversible; }
    public Material condensedMaterial() { return condensedMaterial != null ? condensedMaterial : Material.AMETHYST_SHARD; }
    public String condensedName() { return normalizeLegacyName(condensedName != null ? condensedName : "&bCondensed Soul Essence"); }

    // ------------------------------------------------------------------ catalyst

    public boolean isCatalystEnabled() { return catalystEnabled; }
    public Material catalystMaterial() { return catalystMaterial != null ? catalystMaterial : Material.NETHER_STAR; }
    public String catalystName() { return normalizeLegacyName(catalystName != null ? catalystName : "&dSoul Catalyst"); }
    public boolean isCatalystRecipeEnabled() { return catalystRecipeEnabled; }
    public List<String> getCatalystRecipePattern() { return List.copyOf(catalystRecipePattern); }
    public Map<Character, Material> getCatalystRecipeIngredients() { return Map.copyOf(catalystRecipeIngredients); }
    public Map<Character, String> getCatalystRecipeSpecialIngredients() { return Map.copyOf(catalystRecipeSpecialIngredients); }
    public int getCatalystRecipeAmount() { return Math.max(1, catalystRecipeAmount); }

    // ------------------------------------------------------------------ infusion

    public boolean isInfusionEnabled() { return infusionEnabled; }
    public SoulRarity infusionMinRarity() { return infusionMinRarity != null ? infusionMinRarity : SoulRarity.EPIC; }
    public int infusionMinEnhanceLevel() { return Math.max(0, infusionMinEnhanceLevel); }
    public boolean isInfusionStrictRollConditions() { return infusionStrictRollConditions; }

    public int infusionTraitCap(SoulRarity rarity) {
        if (rarity == null) return 0;
        Integer cap = infusionTraitCaps.get(rarity);
        if (cap == null) return rarity.maxAffixes; // fallback to natural max
        return Math.max(0, cap);
    }

    public int infusionEssenceCost(String action, SoulRarity rarity) {
        if (rarity == null || action == null) return Integer.MAX_VALUE;
        boolean replace = "REPLACE".equalsIgnoreCase(action.trim());
        EnumMap<SoulRarity, Integer> map = replace ? infusionEssenceReplace : infusionEssenceAdd;
        return Math.max(0, map.getOrDefault(rarity, 0));
    }

    public int infusionCatalystCost(String action) {
        if (action == null) return 0;
        return "REPLACE".equalsIgnoreCase(action.trim()) ? Math.max(0, infusionCatalystReplace) : Math.max(0, infusionCatalystAdd);
    }

    public boolean infusionRarityAllowed(SoulRarity rarity) {
        if (rarity == null) return false;
        SoulRarity min = infusionMinRarity();
        return rarity.ordinal() >= min.ordinal();
    }
}
