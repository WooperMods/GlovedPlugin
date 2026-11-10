package dev.gloved.glovedplugin.summon.soul;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.*;

/**
 * Config for the fully-dynamic soul collection system (parsed from summon.yml "souls.*").
 * Minimal by design: global/category defaults, blacklist/safety, capture/rarity/vault/cast/caps.
 */
public final class SoulConfig {
    private boolean enabled = true;
    private boolean allowAllLiving = true;
    private final Set<String> blacklist = new HashSet<>();
    private boolean allowBosses = false;
    private int defaultBatch = 1;
    private String nametagFormat = "{player}'s {mob}";

    // category defaults
    private final EnumMap<EntityCategory, CategoryDefaults> categoryDefaults = new EnumMap<>(EntityCategory.class);

    // capture
    private double captureBaseChance = 0.08;
    private double captureLuckPerLevel = 0.0015;
    private double captureLuckMaxBonus = 0.06;
    private boolean captureMinionKills = true;
    private double captureMinionKillMultiplier = 0.5;
    private boolean captureDrEnabled = true;
    private int captureDrWindowSeconds = 600;
    private double captureDrRadius = 48.0;
    private boolean pityEnabled = true;
    private int pityRareThreshold = 40;
    private int pityEpicThreshold = 120;
    private int pityLegendaryThreshold = 350;
    private final EnumMap<EntityCategory, Double> captureCategoryMult = new EnumMap<>(EntityCategory.class);

    // rarity weights (defaults) and gates
    private final EnumMap<SoulRarity, Integer> rarityWeights = new EnumMap<>(SoulRarity.class);
    private final EnumMap<SoulRarity, Integer> rarityMinLevels = new EnumMap<>(SoulRarity.class);

    // vault
    private int vaultBaseCapacity = 30;
    private int vaultMaxCapacity = 0; // <= 0 means unlimited
    private double vaultCapacityPerLevel = 2.0;

    // loadout/selection
    private int loadoutBaseSlots = 1;
    private int loadoutLevelsPerSlot = 10;
    private int loadoutMaxSlots = 5;

    // cast
    private int castSharedGcdTicks = 40;
    private int castBaseCooldownTicks = 240;
    private double castBaseManaCost = 12.0;
    private boolean replaceIfCapped = false;

    // caps
    private int globalMaxActiveBase = 2;
    private double globalMaxActivePerLevel = 0.15;
    private int globalMaxActiveMax = 8;

    // telemetry/debug
    private boolean debugTelemetry = true;

    public record CategoryDefaults(int maxActivePerType, double costMult, double hpMult, boolean enabled) {}

    public SoulConfig() {
        // sensible defaults
        blacklist.addAll(List.of("ENDER_DRAGON", "WITHER", "WARDEN", "GIANT", "PLAYER"));
        for (SoulRarity r : SoulRarity.values()) rarityWeights.put(r, r.defaultWeight);
        rarityMinLevels.put(SoulRarity.COMMON, 0);
        rarityMinLevels.put(SoulRarity.UNCOMMON, 0);
        rarityMinLevels.put(SoulRarity.RARE, 5);
        rarityMinLevels.put(SoulRarity.EPIC, 15);
        rarityMinLevels.put(SoulRarity.LEGENDARY, 30);
        rarityMinLevels.put(SoulRarity.MYTHIC, 50);
        for (EntityCategory cat : EntityCategory.values()) captureCategoryMult.put(cat, 1.0);
        captureCategoryMult.put(EntityCategory.PASSIVE, 0.8);
        captureCategoryMult.put(EntityCategory.AMBIENT, 0.7);
        captureCategoryMult.put(EntityCategory.BOSS, 0.5);
        categoryDefaults.put(EntityCategory.PASSIVE, new CategoryDefaults(3, 0.8, 0.9, true));
        categoryDefaults.put(EntityCategory.NEUTRAL, new CategoryDefaults(2, 1.0, 1.0, true));
        categoryDefaults.put(EntityCategory.HOSTILE, new CategoryDefaults(2, 1.0, 1.0, true));
        categoryDefaults.put(EntityCategory.FLYING, new CategoryDefaults(3, 1.1, 0.9, true));
        categoryDefaults.put(EntityCategory.AQUATIC, new CategoryDefaults(3, 1.0, 1.0, true));
        categoryDefaults.put(EntityCategory.AMBIENT, new CategoryDefaults(2, 0.6, 0.7, true));
        categoryDefaults.put(EntityCategory.BOSS, new CategoryDefaults(1, 3.0, 1.5, false));
        categoryDefaults.put(EntityCategory.SPECIAL, new CategoryDefaults(1, 1.2, 1.0, true));
    }

    public static SoulConfig load(FileConfiguration yaml) {
        SoulConfig cfg = new SoulConfig();
        if (yaml == null) return cfg;
        backfill(yaml);
        ConfigurationSection s = yaml.getConfigurationSection("souls");
        if (s == null) return cfg;

        cfg.enabled = s.getBoolean("enabled", true);
        ConfigurationSection dyn = s.getConfigurationSection("dynamic");
        if (dyn != null) {
            cfg.allowAllLiving = dyn.getBoolean("allow-all-living", true);
            cfg.allowBosses = dyn.getBoolean("allow-bosses", false);
            cfg.defaultBatch = Math.max(1, dyn.getInt("default-batch", 1));
            cfg.nametagFormat = dyn.getString("default-nametag-format", "{player}'s {mob}");
            List<String> bl = dyn.getStringList("blacklist");
            if (!bl.isEmpty()) {
                cfg.blacklist.clear();
                for (String b : bl) {
                    if (b != null && !b.isBlank()) cfg.blacklist.add(b.trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        ConfigurationSection cats = s.getConfigurationSection("categories");
        if (cats != null) {
            for (EntityCategory cat : EntityCategory.values()) {
                ConfigurationSection cs = cats.getConfigurationSection(cat.name());
                if (cs == null) continue;
                CategoryDefaults base = cfg.categoryDefaults.getOrDefault(cat, new CategoryDefaults(2, 1.0, 1.0, true));
                int cap = Math.max(1, cs.getInt("max-active-per-type", base.maxActivePerType));
                double cost = Math.max(0.1, cs.getDouble("cost-mult", base.costMult));
                double hp = Math.max(0.1, cs.getDouble("hp-mult", base.hpMult));
                boolean en = cs.getBoolean("enabled", base.enabled);
                cfg.categoryDefaults.put(cat, new CategoryDefaults(cap, cost, hp, en));
            }
        }

        ConfigurationSection capSec = s.getConfigurationSection("capture");
        if (capSec != null) {
            cfg.captureBaseChance = clamp(capSec.getDouble("base-chance", 0.08), 0.0, 1.0);
            cfg.captureLuckPerLevel = clamp(capSec.getDouble("luck-per-level", 0.0015), 0.0, 0.1);
            cfg.captureLuckMaxBonus = clamp(capSec.getDouble("luck-max-bonus", 0.06), 0.0, 1.0);
            cfg.captureMinionKills = capSec.getBoolean("minion-kills.enabled", true);
            cfg.captureMinionKillMultiplier = clamp(capSec.getDouble("minion-kills.chance-multiplier", 0.5), 0.0, 1.0);
            ConfigurationSection dr = capSec.getConfigurationSection("dr");
            if (dr != null) {
                cfg.captureDrEnabled = dr.getBoolean("enabled", true);
                cfg.captureDrWindowSeconds = Math.max(10, dr.getInt("window-seconds", 600));
                cfg.captureDrRadius = Math.max(1.0, dr.getDouble("radius", 48.0));
            }
            ConfigurationSection pity = capSec.getConfigurationSection("pity");
            if (pity != null) {
                cfg.pityEnabled = pity.getBoolean("enabled", true);
                cfg.pityRareThreshold = Math.max(1, pity.getInt("rare-threshold", 40));
                cfg.pityEpicThreshold = Math.max(cfg.pityRareThreshold, pity.getInt("epic-threshold", 120));
                cfg.pityLegendaryThreshold = Math.max(cfg.pityEpicThreshold, pity.getInt("legendary-threshold", 350));
            }
            ConfigurationSection byCat = capSec.getConfigurationSection("by-category-mult");
            if (byCat != null) {
                for (EntityCategory cat : EntityCategory.values()) {
                    cfg.captureCategoryMult.put(cat, clamp(byCat.getDouble(cat.name(), cfg.captureCategoryMult.getOrDefault(cat, 1.0)), 0.0, 10.0));
                }
            }
        }

        ConfigurationSection rar = s.getConfigurationSection("rarity");
        if (rar != null) {
            ConfigurationSection w = rar.getConfigurationSection("weights");
            if (w != null) {
                for (SoulRarity r : SoulRarity.values()) {
                    int val = Math.max(0, w.getInt(r.name(), cfg.rarityWeights.getOrDefault(r, r.defaultWeight)));
                    cfg.rarityWeights.put(r, val);
                }
            }
            ConfigurationSection ml = rar.getConfigurationSection("min-levels");
            if (ml != null) {
                for (SoulRarity r : SoulRarity.values()) {
                    cfg.rarityMinLevels.put(r, Math.max(0, ml.getInt(r.name(), cfg.rarityMinLevels.getOrDefault(r, 0))));
                }
            }
        }

        ConfigurationSection vault = s.getConfigurationSection("vault");
        if (vault != null) {
            cfg.vaultBaseCapacity = Math.max(1, vault.getInt("base-capacity", 30));
            cfg.vaultMaxCapacity = vault.getInt("max-capacity", 0); // <= 0 means unlimited
            if (cfg.vaultMaxCapacity > 0) cfg.vaultMaxCapacity = Math.max(cfg.vaultBaseCapacity, cfg.vaultMaxCapacity);
            cfg.vaultCapacityPerLevel = clamp(vault.getDouble("capacity-per-level", 2.0), 0.0, 50.0);
            cfg.loadoutBaseSlots = Math.max(1, vault.getInt("loadout.base-slots", vault.getInt("loadout-base-slots", 1)));
            cfg.loadoutLevelsPerSlot = Math.max(1, vault.getInt("loadout.levels-per-slot", vault.getInt("loadout-levels-per-slot", 10)));
            cfg.loadoutMaxSlots = Math.max(cfg.loadoutBaseSlots, vault.getInt("loadout.max-slots", vault.getInt("loadout-max-slots", 5)));
        }

        ConfigurationSection cast = s.getConfigurationSection("cast");
        if (cast != null) {
            cfg.castSharedGcdTicks = Math.max(0, cast.getInt("shared-gcd-ticks", 40));
            cfg.castBaseCooldownTicks = Math.max(0, cast.getInt("base-cooldown-ticks", 120));
            cfg.castBaseManaCost = Math.max(0.0, cast.getDouble("base-mana-cost", 12.0));
            String rp = cast.getString("replace-policy", "BLOCK_IF_CAPPED");
            cfg.replaceIfCapped = rp != null && rp.toUpperCase(Locale.ROOT).contains("REPLACE");
        }

        ConfigurationSection caps = s.getConfigurationSection("caps");
        if (caps != null) {
            cfg.globalMaxActiveBase = Math.max(1, caps.getInt("global-max-active-base", 2));
            cfg.globalMaxActivePerLevel = Math.max(0.0, caps.getDouble("global-max-active-per-level", 0.15));
            cfg.globalMaxActiveMax = Math.max(cfg.globalMaxActiveBase, caps.getInt("global-max-active-max", 8));
        }

        cfg.debugTelemetry = s.getBoolean("debug-telemetry", true);

        return cfg;
    }

    public static void backfill(FileConfiguration cfg) {
        if (cfg == null) return;
        setIfMissing(cfg, "souls.enabled", true);
        setIfMissing(cfg, "souls.dynamic.allow-all-living", true);
        setIfMissing(cfg, "souls.dynamic.allow-bosses", false);
        setIfMissing(cfg, "souls.dynamic.default-batch", 1);
        setIfMissing(cfg, "souls.dynamic.default-nametag-format", "{player}'s {mob}");
        if (!cfg.isList("souls.dynamic.blacklist")) {
            cfg.set("souls.dynamic.blacklist", List.of("ENDER_DRAGON", "WITHER", "WARDEN", "GIANT", "PLAYER"));
        }
        setIfMissing(cfg, "souls.capture.base-chance", 0.08);
        setIfMissing(cfg, "souls.capture.luck-per-level", 0.0015);
        setIfMissing(cfg, "souls.capture.luck-max-bonus", 0.06);
        setIfMissing(cfg, "souls.capture.minion-kills.enabled", true);
        setIfMissing(cfg, "souls.capture.minion-kills.chance-multiplier", 0.5);
        setIfMissing(cfg, "souls.capture.dr.enabled", true);
        setIfMissing(cfg, "souls.capture.dr.window-seconds", 600);
        setIfMissing(cfg, "souls.capture.dr.radius", 48.0);
        setIfMissing(cfg, "souls.capture.pity.enabled", true);
        setIfMissing(cfg, "souls.capture.pity.rare-threshold", 40);
        setIfMissing(cfg, "souls.capture.pity.epic-threshold", 120);
        setIfMissing(cfg, "souls.capture.pity.legendary-threshold", 350);
        setIfMissing(cfg, "souls.capture.by-category-mult.PASSIVE", 0.8);
        setIfMissing(cfg, "souls.capture.by-category-mult.NEUTRAL", 1.0);
        setIfMissing(cfg, "souls.capture.by-category-mult.HOSTILE", 1.0);
        setIfMissing(cfg, "souls.capture.by-category-mult.FLYING", 1.0);
        setIfMissing(cfg, "souls.capture.by-category-mult.AQUATIC", 1.0);
        setIfMissing(cfg, "souls.capture.by-category-mult.AMBIENT", 0.7);
        setIfMissing(cfg, "souls.capture.by-category-mult.BOSS", 0.5);
        setIfMissing(cfg, "souls.capture.by-category-mult.SPECIAL", 1.0);
        setIfMissing(cfg, "souls.vault.base-capacity", 30);
        setIfMissing(cfg, "souls.vault.max-capacity", 0); // <= 0 means unlimited
        setIfMissing(cfg, "souls.vault.capacity-per-level", 2.0);
        setIfMissing(cfg, "souls.vault.overflow", "CONVERT_TO_ESSENCE");
        setIfMissing(cfg, "souls.vault.loadout.base-slots", 1);
        setIfMissing(cfg, "souls.vault.loadout.levels-per-slot", 10);
        setIfMissing(cfg, "souls.vault.loadout.max-slots", 5);
        setIfMissing(cfg, "souls.cast.shared-gcd-ticks", 10);
        setIfMissing(cfg, "souls.cast.base-cooldown-ticks", 120);
        setIfMissing(cfg, "souls.cast.base-mana-cost", 12.0);
        setIfMissing(cfg, "souls.cast.replace-policy", "BLOCK_IF_CAPPED");
        setIfMissing(cfg, "souls.caps.global-max-active-base", 2);
        setIfMissing(cfg, "souls.caps.global-max-active-per-level", 0.15);
        setIfMissing(cfg, "souls.caps.global-max-active-max", 8);
        setIfMissing(cfg, "souls.debug-telemetry", true);
        // rarity weights and min levels
        for (SoulRarity r : SoulRarity.values()) {
            setIfMissing(cfg, "souls.rarity.weights." + r.name(), r.defaultWeight);
        }
        setIfMissing(cfg, "souls.rarity.min-levels.COMMON", 0);
        setIfMissing(cfg, "souls.rarity.min-levels.UNCOMMON", 0);
        setIfMissing(cfg, "souls.rarity.min-levels.RARE", 5);
        setIfMissing(cfg, "souls.rarity.min-levels.EPIC", 15);
        setIfMissing(cfg, "souls.rarity.min-levels.LEGENDARY", 30);
        setIfMissing(cfg, "souls.rarity.min-levels.MYTHIC", 50);
        // category defaults (minimal)
        setCat(cfg, "PASSIVE", 3, 0.8, 0.9, true);
        setCat(cfg, "NEUTRAL", 2, 1.0, 1.0, true);
        setCat(cfg, "HOSTILE", 2, 1.0, 1.0, true);
        setCat(cfg, "FLYING", 3, 1.1, 0.9, true);
        setCat(cfg, "AQUATIC", 3, 1.0, 1.0, true);
        setCat(cfg, "AMBIENT", 2, 0.6, 0.7, true);
        setCat(cfg, "BOSS", 1, 3.0, 1.5, false);
        setCat(cfg, "SPECIAL", 1, 1.2, 1.0, true);
    }

    private static void setCat(FileConfiguration cfg, String cat, int cap, double cost, double hp, boolean en) {
        setIfMissing(cfg, "souls.categories." + cat + ".max-active-per-type", cap);
        setIfMissing(cfg, "souls.categories." + cat + ".cost-mult", cost);
        setIfMissing(cfg, "souls.categories." + cat + ".hp-mult", hp);
        setIfMissing(cfg, "souls.categories." + cat + ".enabled", en);
    }

    private static void setIfMissing(FileConfiguration cfg, String path, Object value) {
        if (cfg != null && !cfg.isSet(path)) cfg.set(path, value);
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    /** Resolve whether an entity type can generate/be used as a dynamic soul. */
    public boolean isAllowed(EntityType type) {
        if (!enabled || type == null) return false;
        String name = type.name().toUpperCase(Locale.ROOT);
        if (blacklist.contains(name)) return false;
        if (!type.isAlive() || !type.isSpawnable()) return false;
        if (type == EntityType.PLAYER || type == EntityType.ARMOR_STAND) return false;
        EntityCategory cat = EntityCategory.of(type);
        if (cat == EntityCategory.BOSS && !allowBosses) return false;
        CategoryDefaults cd = categoryDefaults.getOrDefault(cat, new CategoryDefaults(1, 1.0, 1.0, true));
        return cd.enabled;
    }

    /** Resolve a runtime template for a dynamic entity type (or null if disallowed). */
    public DynamicSoulTemplate resolveTemplate(EntityType type) {
        if (!isAllowed(type)) return null;
        EntityCategory cat = EntityCategory.of(type);
        CategoryDefaults cd = categoryDefaults.getOrDefault(cat, new CategoryDefaults(2, 1.0, 1.0, true));
        int perTypeCap = Math.max(1, cd.maxActivePerType);
        return new DynamicSoulTemplate(type.name(), type, cat, defaultBatch, perTypeCap, DynamicSoulTemplate.defaultColor(cat));
    }

    /** Resolve by family/type key string (entity type name). */
    public DynamicSoulTemplate resolveTemplate(String familyKey) {
        if (familyKey == null) return null;
        EntityType type = DynamicSoulTemplate.parseType(familyKey);
        return resolveTemplate(type);
    }

    public int computeGlobalMaxActive(int summoningLevel) {
        int v = (int) Math.floor(globalMaxActiveBase + globalMaxActivePerLevel * Math.max(0, summoningLevel));
        return Math.max(1, Math.min(globalMaxActiveMax, Math.max(globalMaxActiveBase, v)));
    }

    public boolean isEnabled() { return enabled; }
    public double getCaptureBaseChance() { return captureBaseChance; }
    public double getCaptureLuckPerLevel() { return captureLuckPerLevel; }
    public double getCaptureLuckMaxBonus() { return captureLuckMaxBonus; }
    public boolean isCaptureMinionKills() { return captureMinionKills; }
    public double getCaptureMinionKillMultiplier() { return captureMinionKillMultiplier; }
    public double getCaptureCategoryMultiplier(EntityCategory cat) { return captureCategoryMult.getOrDefault(cat != null ? cat : EntityCategory.SPECIAL, 1.0); }
    public boolean isCaptureDrEnabled() { return captureDrEnabled; }
    public int getCaptureDrWindowSeconds() { return captureDrWindowSeconds; }
    public double getCaptureDrRadius() { return captureDrRadius; }
    public boolean isPityEnabled() { return pityEnabled; }
    public int getPityRareThreshold() { return pityRareThreshold; }
    public int getPityEpicThreshold() { return pityEpicThreshold; }
    public int getPityLegendaryThreshold() { return pityLegendaryThreshold; }
    public int getVaultBaseCapacity() { return vaultBaseCapacity; }
    public int getVaultMaxCapacity() { return vaultMaxCapacity; }
    public double getVaultCapacityPerLevel() { return vaultCapacityPerLevel; }
    public int computeVaultCapacity(int summoningLevel) {
        int v = (int) Math.floor(vaultBaseCapacity + vaultCapacityPerLevel * Math.max(0, summoningLevel));
        v = Math.max(vaultBaseCapacity, v);
        if (vaultMaxCapacity <= 0) return Math.max(1, v); // unlimited
        return Math.max(1, Math.min(vaultMaxCapacity, v));
    }
    public int getLoadoutBaseSlots() { return loadoutBaseSlots; }
    public int getLoadoutLevelsPerSlot() { return loadoutLevelsPerSlot; }
    public int getLoadoutMaxSlots() { return loadoutMaxSlots; }
    public int getCastSharedGcdTicks() { return castSharedGcdTicks; }
    public int getCastBaseCooldownTicks() { return castBaseCooldownTicks; }
    public double getCastBaseManaCost() { return castBaseManaCost; }
    public boolean isReplaceIfCapped() { return replaceIfCapped; }
    public String getNametagFormat() { return nametagFormat; }
    public Map<SoulRarity, Integer> getRarityWeights() { return Collections.unmodifiableMap(rarityWeights); }
    public Map<SoulRarity, Integer> getRarityWeightsForLevel(int summoningLevel) {
        EnumMap<SoulRarity, Integer> out = new EnumMap<>(SoulRarity.class);
        for (SoulRarity r : SoulRarity.values()) {
            int minLvl = rarityMinLevels.getOrDefault(r, 0);
            int w = rarityWeights.getOrDefault(r, r.defaultWeight);
            if (summoningLevel < minLvl) w = 0;
            out.put(r, Math.max(0, w));
        }
        // ensure at least common/uncommon fallback
        int sum = 0; for (int w : out.values()) sum += w;
        if (sum <= 0) out.put(SoulRarity.COMMON, 1);
        return out;
    }
    public int getRarityMinLevel(SoulRarity r) { return rarityMinLevels.getOrDefault(r, 0); }
    public CategoryDefaults getCategoryDefaults(EntityCategory cat) { return categoryDefaults.getOrDefault(cat, new CategoryDefaults(2,1,1,true)); }
    public boolean isDebugTelemetry() { return debugTelemetry; }
}
