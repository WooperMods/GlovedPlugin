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
}
