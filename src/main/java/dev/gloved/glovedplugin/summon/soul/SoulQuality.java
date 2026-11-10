package dev.gloved.glovedplugin.summon.soul;

import java.util.List;

/** Computes soul quality as average percentage of affix rolls vs their max possible values. */
public final class SoulQuality {
    private SoulQuality() {}

    public static double baseMaxForRarity(SoulRarity rarity) {
        if (rarity == null) return 0.05;
        return switch (rarity) {
            case COMMON -> 0.05;
            case UNCOMMON -> 0.08;
            case RARE -> 0.12;
            case EPIC -> 0.16;
            case LEGENDARY -> 0.22;
            case MYTHIC -> 0.30;
        };
    }

    /** Flat quality bonus added by rarity, so higher-rarity souls inherently score higher quality. */
    public static double rarityQualityBonus(SoulRarity rarity) {
        if (rarity == null) return 0.0;
        return switch (rarity) {
            case COMMON -> 0.0;
            case UNCOMMON -> 5.0;
            case RARE -> 10.0;
            case EPIC -> 15.0;
            case LEGENDARY -> 20.0;
            case MYTHIC -> 25.0;
        };
    }

    /** Theoretical max roll value for an affix type at a given rarity (matches SoulGenerator roll tuning). */
    public static double maxFor(SoulRarity rarity, SoulAffixType type) {
        double baseMax = baseMaxForRarity(rarity);
        if (type == null) return baseMax;
        return switch (type) {
            case PRECISE -> baseMax * 0.5;
            case DEADLY, RESONANT -> baseMax * 0.8;
            case SWIFT -> Math.min(baseMax, 0.12);
            case COMMANDER -> Math.min(baseMax, 0.18);
            default -> baseMax;
        };
    }

    /** Average percentage (0..100) of all affix stats compared to their max for the soul's rarity,
     *  plus a rarity-based quality bonus so higher rarities inherently score higher. */
    public static double percent(SoulInstance soul) {
        if (soul == null) return 0.0;
        return percent(soul.getRarity(), soul.getAffixes());
    }

    public static double percent(SoulRarity rarity, List<SoulAffixRoll> affixes) {
        if (affixes == null || affixes.isEmpty()) return rarityQualityBonus(rarity);
        double sum = 0.0;
        int count = 0;
        for (SoulAffixRoll r : affixes) {
            if (r == null || r.type() == null) continue;
            double max = maxFor(rarity, r.type());
            if (max <= 0.0) continue;
            double pct = (Math.max(0.0, r.value()) / max) * 100.0;
            // clamp to 100 in case of rounding/tuning edge cases
            sum += Math.min(100.0, pct);
            count++;
        }
        if (count == 0) return rarityQualityBonus(rarity);
        double avg = sum / count;
        double withBonus = Math.min(100.0, avg + rarityQualityBonus(rarity));
        return Math.round(withBonus * 10.0) / 10.0; // 1 decimal
    }

    public static String format(SoulInstance soul) {
        return format(percent(soul));
    }

    public static String format(double percent) {
        // show as integer if whole, else 1 decimal
        double r = Math.round(percent * 10.0) / 10.0;
        if (Math.abs(r - Math.rint(r)) < 0.0001) return String.valueOf((int) Math.rint(r)) + "%";
        return String.format(java.util.Locale.ROOT, "%.1f%%", r);
    }
}
