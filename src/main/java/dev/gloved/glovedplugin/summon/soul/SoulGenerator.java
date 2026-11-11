package dev.gloved.glovedplugin.summon.soul;

import org.bukkit.entity.EntityType;

import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Generates random soul instances (rarity, affixes, trait, quality) with bounded rolls. */
public final class SoulGenerator {

    private static final SoulTableConfig.SoulStatConfig DEFAULT_STAT_CONFIG =
            new SoulTableConfig.SoulStatConfig();

    private SoulGenerator() {}

    public static SoulInstance generate(UUID owner, EntityType type, SoulConfig cfg) {
        return generate(owner, type, cfg, null, 0, 0, 0);
    }

    public static SoulInstance generate(UUID owner, EntityType type, SoulConfig cfg, SoulRarity forcedRarity) {
        return generate(owner, type, cfg, forcedRarity, 0, 0, 0);
    }
    public static SoulInstance generate(UUID owner, EntityType type, SoulConfig cfg, SoulRarity forcedRarity,
                                        int summoningLevel, int pityRare, int pityEpic) {
        return generate(owner, type, cfg, forcedRarity, summoningLevel, pityRare, pityEpic, 0.0);
    }

    public static SoulInstance generate(UUID owner, EntityType type, SoulConfig cfg, SoulRarity forcedRarity,
                                        int summoningLevel, int pityRare, int pityEpic, double maxHealth) {
        return generate(owner, type, cfg, forcedRarity, summoningLevel, pityRare, pityEpic, maxHealth, null, null);
    }

    /**
     * Full generator entry point: rolls rarity/affixes/traits/quality and the rolled base-stat block.
     *
     * @param source   live captured stats (null = use the entity type's default attribute map)
     * @param tableCfg stat config source (null = built-in defaults)
     */
    public static SoulInstance generate(UUID owner, EntityType type, SoulConfig cfg, SoulRarity forcedRarity,
                                        int summoningLevel, int pityRare, int pityEpic, double maxHealth,
                                        SoulBaseStats source, SoulTableConfig tableCfg) {
        EntityCategory cat = EntityCategory.of(type);
        SoulRarity rarity = forcedRarity != null ? forcedRarity : rollRarity(cfg, summoningLevel, pityRare, pityEpic);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int affixCount = rng.nextInt(rarity.minAffixes, rarity.maxAffixes + 1);
        List<SoulAffixRoll> affixes = rollAffixes(rarity, affixCount, rng);
        List<SoulTrait> traits = rollTraits(rarity, rng);
        double quality = SoulQuality.percent(rarity, affixes);
        SoulBaseStats baseStats = rollBaseStats(type, rarity, source, tableCfg);
        return new SoulInstance(UUID.randomUUID(), owner, type, type.name(), cat, rarity, quality, affixes, traits,
                System.currentTimeMillis(), maxHealth, false, baseStats, 0);
    }

    /** Rolls the captured base-stat block (rarity multiplier + variance + caps). */
    public static SoulBaseStats rollBaseStats(EntityType type, SoulRarity rarity, SoulBaseStats source,
                                              SoulTableConfig tableCfg) {
        SoulBaseStats statSource = source != null ? source : SoulBaseStats.defaults(type);
        if (tableCfg != null && !tableCfg.isStatsEnabled()) return statSource;
        SoulTableConfig.SoulStatConfig statCfg =
                tableCfg != null ? tableCfg.getStatConfig() : DEFAULT_STAT_CONFIG;
        return statSource.withVariance(ThreadLocalRandom.current(), rarity, statCfg).capped(statCfg);
    }

    /** Rerolls the affix list for a rarity (used by the Soul Table reforge). */
    public static List<SoulAffixRoll> rollAffixesForRarity(SoulRarity rarity) {
        if (rarity == null) return List.of();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int affixCount = rng.nextInt(rarity.minAffixes, rarity.maxAffixes + 1);
        return rollAffixes(rarity, affixCount, rng);
    }

    public static SoulRarity rollRarity(SoulConfig cfg) {
        return rollRarity(cfg, 0, 0, 0);
    }

    public static SoulRarity rollRarity(SoulConfig cfg, int summoningLevel, int pityRare, int pityEpic) {
        Map<SoulRarity, Integer> base = cfg != null ? cfg.getRarityWeightsForLevel(summoningLevel) : null;
        EnumMap<SoulRarity, Integer> weights = new EnumMap<>(SoulRarity.class);
        if (base != null) weights.putAll(base); else for (SoulRarity r : SoulRarity.values()) weights.put(r, r.defaultWeight);
        // Pity bias (soft): shift weight toward higher rarities as counters grow
        if (cfg != null && cfg.isPityEnabled()) {
            if (pityRare >= Math.max(1, cfg.getPityRareThreshold() / 2)) {
                boost(weights, SoulRarity.RARE, 200 + pityRare * 5);
                boost(weights, SoulRarity.EPIC, 40 + pityRare * 2);
            }
            if (pityEpic >= Math.max(1, cfg.getPityEpicThreshold() / 2)) {
                boost(weights, SoulRarity.EPIC, 120 + pityEpic * 3);
                boost(weights, SoulRarity.LEGENDARY, 20 + pityEpic);
            }
            if (pityRare >= cfg.getPityRareThreshold()) {
                weights.put(SoulRarity.COMMON, Math.max(0, weights.getOrDefault(SoulRarity.COMMON, 0) / 3));
                weights.put(SoulRarity.UNCOMMON, Math.max(0, weights.getOrDefault(SoulRarity.UNCOMMON, 0) / 2));
            }
        }
        int total = 0;
        for (SoulRarity r : SoulRarity.values()) total += Math.max(0, weights.getOrDefault(r, 0));
        if (total <= 0) return SoulRarity.COMMON;
        int roll = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        for (SoulRarity r : SoulRarity.values()) {
            acc += Math.max(0, weights.getOrDefault(r, 0));
            if (roll < acc) return r;
        }
        return SoulRarity.COMMON;
    }

    private static void boost(Map<SoulRarity, Integer> w, SoulRarity r, int add) {
        if (w == null || r == null) return;
        w.put(r, Math.max(0, w.getOrDefault(r, 0)) + Math.max(0, add));
    }

    private static List<SoulAffixRoll> rollAffixes(SoulRarity rarity, int count, ThreadLocalRandom rng) {
        List<SoulAffixType> pool = new ArrayList<>(Arrays.asList(SoulAffixType.values()));
        Collections.shuffle(pool, new Random(rng.nextLong()));
        int n = Math.min(count, pool.size());
        List<SoulAffixRoll> out = new ArrayList<>(n);
        double baseMax = switch (rarity) {
            case COMMON -> 0.06;
            case UNCOMMON -> 0.10;
            case RARE -> 0.16;
            case EPIC -> 0.24;
            case LEGENDARY -> 0.34;
            case MYTHIC -> 0.45;
        };
        double baseMin = baseMax * 0.35;
        // Per-type caps scale with rarity so higher tiers can roll meaningfully stronger affixes.
        double swiftCap = switch (rarity) {
            case COMMON -> 0.08;
            case UNCOMMON -> 0.12;
            case RARE -> 0.16;
            case EPIC -> 0.20;
            case LEGENDARY -> 0.25;
            case MYTHIC -> 0.30;
        };
        double commanderCap = switch (rarity) {
            case COMMON -> 0.10;
            case UNCOMMON -> 0.16;
            case RARE -> 0.22;
            case EPIC -> 0.30;
            case LEGENDARY -> 0.38;
            case MYTHIC -> 0.45;
        };
        for (int i = 0; i < n; i++) {
            SoulAffixType t = pool.get(i);
            double val = baseMin + rng.nextDouble() * (baseMax - baseMin);
            // Type-specific tuning (kept bounded, caps scaled by rarity)
            val = switch (t) {
                case PRECISE -> val * 0.5; // crit chance points fraction is smaller
                case DEADLY, RESONANT -> val * 0.8;
                case SWIFT -> Math.min(val, swiftCap); // speed safety
                case COMMANDER -> Math.min(val, commanderCap); // cost/cd efficiency
                default -> val;
            };
            out.add(new SoulAffixRoll(t, Math.round(val * 10000.0) / 10000.0));
        }
        return out;
    }

    private static List<SoulTrait> rollTraits(SoulRarity rarity, ThreadLocalRandom rng) {
        List<SoulTrait> out = new ArrayList<>();
        SoulTrait[] vals = SoulTrait.values();
        int target = 0;

        switch (rarity) {
            case COMMON:
                target = rng.nextDouble() <= 0.10 ? 1 : 0;
                break;
            case UNCOMMON:
                target = rng.nextDouble() <= 0.20 ? 1 : 0;
                break;
            case RARE:
                target = 1;
                if (rng.nextDouble() <= 0.50) target++; // 50% chance for 2nd
                if (rng.nextDouble() <= 0.05) target++; // 5% chance for 3rd (independent)
                break;
            case EPIC:
                target = 2;
                if (rng.nextDouble() <= 0.25) target++; // 25% chance for 3rd
                break;
            case LEGENDARY:
                target = 2;
                if (rng.nextDouble() <= 0.50) target++; // 50% chance for 3rd
                if (rng.nextDouble() <= 0.05) target++; // 5% chance for 4th (independent)
                break;
            case MYTHIC:
                target = 3;
                if (rng.nextDouble() <= 0.25) target++; // 25% chance for 4th
                if (rng.nextDouble() <= 0.05) target++; // 5% chance for 5th (independent)
                break;
            default:
                target = 0;
                break;
        }

        target = Math.min(target, vals.length);
        if (target <= 0) return out;

        List<SoulTrait> pool = new ArrayList<>(Arrays.asList(vals));
        Collections.shuffle(pool, new Random(rng.nextLong()));
        for (int i = 0; i < target && i < pool.size(); i++) {
            out.add(pool.get(i));
        }
        return out;
    }



    public static String describe(SoulInstance soul) {
        if (soul == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        sb.append(soul.getRarity().name()).append(" ")
          .append(prettyName(soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey()))
          .append(" Soul");
        if (!soul.getTraits().isEmpty()) {
            sb.append(" [");
            for (int i = 0; i < soul.getTraits().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(soul.getTraits().get(i).name());
            }
            sb.append("]");
        }
        if (!soul.getAffixes().isEmpty()) {
            sb.append(" {");
            for (int i = 0; i < soul.getAffixes().size(); i++) {
                if (i > 0) sb.append(", ");
                SoulAffixRoll a = soul.getAffixes().get(i);
                sb.append(SoulAffixType.format(a));
            }
            sb.append("}");
        }
        return sb.toString();
    }

    private static String prettyName(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(java.util.Locale.ROOT).split("[_\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }
}
