package dev.gloved.glovedplugin.summon.soul;

import java.util.Locale;

public enum SoulRarity {
    COMMON(5000, 1, 1),
    UNCOMMON(2800, 2, 2),
    RARE(1400, 2, 3),
    EPIC(600, 3, 3),
    LEGENDARY(180, 3, 4),
    MYTHIC(20, 4, 4);

    public final int defaultWeight;
    public final int minAffixes;
    public final int maxAffixes;

    SoulRarity(int defaultWeight, int minAffixes, int maxAffixes) {
        this.defaultWeight = defaultWeight;
        this.minAffixes = minAffixes;
        this.maxAffixes = maxAffixes;
    }

    public static SoulRarity fromString(String raw, SoulRarity fallback) {
        if (raw == null) return fallback;
        try {
            return SoulRarity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** XP multiplier for soul capture progression (Common = 1x base). */
    public double getCaptureXpMultiplier() {
        return switch (this) {
            case COMMON -> 1.0;
            case UNCOMMON -> 2.0;
            case RARE -> 3.0;
            case EPIC -> 5.0;
            case LEGENDARY -> 8.0;
            case MYTHIC -> 13.0;
        };
    }
}
