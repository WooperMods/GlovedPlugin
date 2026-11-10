package dev.gloved.glovedplugin.summon.soul;

import java.util.List;

/**
 * Computes soul quality so that it is tightly integrated with rarity.
 *
 * <p>Quality is no longer just "average affix roll vs that rarity's max" (which made a perfect
 * common look as good as a perfect legendary). Instead each rarity owns a quality band:
 * rarity sets the floor and the range, and affix rolls determine where within the band the
 * soul lands. A common can never reach legendary quality and a legendary never drops to common
 * quality.</p>
 */
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

    /** Base quality floor for each rarity (quality when affixes roll at their minimum). */
    public static double baseQuality(SoulRarity rarity) {
        if (rarity == null) return 0.0;
        return switch (rarity) {
            case COMMON -> 20.0;
            case UNCOMMON -> 35.0;
            case RARE -> 50.0;
            case EPIC -> 65.0;
            case LEGENDARY -> 80.0;
            case MYTHIC -> 90.0;
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

    /** Quality variance range for each rarity (max attainable quality minus {@link #baseQuality}). */
    public static double qualityRange(SoulRarity rarity) {
        if (rarity == null) return 0.0;
        return switch (rarity) {
            case COMMON -> 20.0;   // 20-40%
            case UNCOMMON -> 20.0; // 35-55%
            case RARE -> 20.0;     // 50-70%
            case EPIC -> 20.0;     // 65-85%
            case LEGENDARY -> 15.0; // 80-95%
            case MYTHIC -> 10.0;   // 90-100%
        };
    }

    /** Average percentage (0..100) of all affix stats compared to their max for the soul's rarity.
     *  Used as the position within the rarity's quality band. */
    public static double affixQualityPercent(SoulRarity rarity, List<SoulAffixRoll> affixes) {
        if (affixes == null || affixes.isEmpty()) return 0.0;
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
        if (count == 0) return 0.0;
        return Math.round((sum / count) * 10.0) / 10.0;
    }

    /**
     * Final quality (0..100): {@link #baseQuality} plus the affix-roll position within the
     * rarity's {@link #qualityRange}. Higher rarities always score higher than lower rarities
     * for equivalent roll quality.
     */
    public static double percent(SoulRarity rarity, List<SoulAffixRoll> affixes) {
        if (rarity == null) return 0.0;
        double affixPct = affixQualityPercent(rarity, affixes);
        double quality = baseQuality(rarity) + (affixPct / 100.0) * qualityRange(rarity);
        return Math.max(0.0, Math.min(100.0, quality));
    }

    public static double percent(SoulInstance soul) {
        if (soul == null) return 0.0;
        return percent(soul.getRarity(), soul.getAffixes());
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
