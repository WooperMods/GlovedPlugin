package dev.gloved.glovedplugin.summon.soul;

import org.bukkit.entity.EntityType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Generates random soul instances (rarity, affixes, trait, quality) with bounded rolls. */
public final class SoulGenerator {

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
        EntityCategory cat = EntityCategory.of(type);
        SoulRarity rarity = forcedRarity != null ? forcedRarity : rollRarity(cfg, summoningLevel, pityRare, pityEpic);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int affixCount = rng.nextInt(rarity.minAffixes, rarity.maxAffixes + 1);
        List<SoulAffixRoll> affixes = rollAffixes(rarity, affixCount, rng);
        List<SoulTrait> traits = rollTraits(rarity, rng);
        double quality = SoulQuality.percent(rarity, affixes);
        return new SoulInstance(UUID.randomUUID(), owner, type, type.name(), cat, rarity, quality, affixes, traits,
                System.currentTimeMillis(), maxHealth, false);
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
            case COMMON -> 0.05;
            case UNCOMMON -> 0.08;
            case RARE -> 0.12;
            case EPIC -> 0.16;
            case LEGENDARY -> 0.22;
            case MYTHIC -> 0.30;
        };
        double baseMin = baseMax * 0.35;
        for (int i = 0; i < n; i++) {
            SoulAffixType t = pool.get(i);
            double val = baseMin + rng.nextDouble() * (baseMax - baseMin);
            // Type-specific tuning (kept bounded)
            val = switch (t) {
                case PRECISE -> val * 0.5; // crit chance points fraction is smaller
                case DEADLY, RESONANT -> val * 0.8;
                case SWIFT -> Math.min(val, 0.12); // speed safety
                case COMMANDER -> Math.min(val, 0.18); // cost/cd efficiency
                default -> val;
            };
            out.add(new SoulAffixRoll(t, Math.round(val * 10000.0) / 10000.0));
        }
        return out;
    }

    private static List<SoulTrait> rollTraits(SoulRarity rarity, ThreadLocalRandom rng) {
        List<SoulTrait> out = new ArrayList<>();
        int guaranteed;
        double extraChance;
        switch (rarity) {
            case COMMON:
                if (rng.nextDouble() <= 0.10) guaranteed = 1; else guaranteed = 0;
                extraChance = 0.0;
                break;
            case UNCOMMON:
                guaranteed = rng.nextDouble() <= 0.20 ? 1 : 0;
                extraChance = 0.0;
                break;
            case RARE:
                guaranteed = 1;
                extraChance = 0.50; // 50% chance for a 2nd trait
                break;
            case EPIC:
                guaranteed = 2;
                extraChance = 0.0;
                break;
            case LEGENDARY:
                guaranteed = 2;
                extraChance = 0.50; // 50% chance for a 3rd trait
                break;
            case MYTHIC:
                guaranteed = 3;
                extraChance = 0.0;
                break;
            default:
                guaranteed = 0;
                extraChance = 0.0;
                break;
        }
        SoulTrait[] vals = SoulTrait.values();
        Set<SoulTrait> picked = new LinkedHashSet<>();
        for (int i = 0; i < guaranteed; i++) {
            SoulTrait t = vals[rng.nextInt(vals.length)];
            if (!picked.contains(t)) picked.add(t);
        }
        if (rng.nextDouble() <= extraChance) {
            SoulTrait t = vals[rng.nextInt(vals.length)];
            if (!picked.contains(t)) picked.add(t);
        }
        out.addAll(picked);
        return out;
    }



    public static String describe(SoulInstance soul) {
        if (soul == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        sb.append(soul.getRarity().name()).append(" ")
          .append(prettyName(soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey()))
          .append(" Soul (").append(SoulQuality.format(soul)).append(")");
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
