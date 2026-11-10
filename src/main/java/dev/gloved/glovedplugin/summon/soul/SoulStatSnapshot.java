package dev.gloved.glovedplugin.summon.soul;

/**
 * Computed/effective modifiers for a soul at cast time (bounded). Values are additive/multiplicative
 * modifiers interpreted by spawn/combat systems (implementation applies clamps).
 */
public record SoulStatSnapshot(
        double damageMultiplier,   // e.g. 1.0 + BRUTAL
        double healthMultiplier,   // e.g. 1.0 + VIGOROUS
        double speedMultiplier,    // e.g. 1.0 + SWIFT (clamped)
        double critChanceBonus,    // additive points fraction (0..cap)
        double critDamageBonus,    // additive fraction
        double abilityPotencyMultiplier, // 1.0 + RESONANT
        double costEfficiency,     // 1.0 - COMMANDER (reduces mana/cd)
        double tenacityBonus,      // damage reduction bonus (capped)
        double enduranceBonus      // duration/uptime bonus
) {
    public static SoulStatSnapshot identity() {
        return new SoulStatSnapshot(1.0, 1.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0);
    }

    public static SoulStatSnapshot fromSoul(SoulInstance soul) {
        if (soul == null) return identity();
        double brutal = clamp01(soul.affixValue(SoulAffixType.BRUTAL));
        double vigorous = clamp01(soul.affixValue(SoulAffixType.VIGOROUS));
        double swift = clamp01(soul.affixValue(SoulAffixType.SWIFT));
        double precise = Math.max(0.0, soul.affixValue(SoulAffixType.PRECISE));
        double deadly = Math.max(0.0, soul.affixValue(SoulAffixType.DEADLY));
        double resonant = clamp01(soul.affixValue(SoulAffixType.RESONANT));
        double commander = clamp01(soul.affixValue(SoulAffixType.COMMANDER));
        double tenacious = clamp01(soul.affixValue(SoulAffixType.TENACIOUS));
        double enduring = clamp01(soul.affixValue(SoulAffixType.ENDURING));
        return new SoulStatSnapshot(
                1.0 + brutal,
                1.0 + vigorous,
                1.0 + Math.min(0.5, swift), // speed safety clamp baseline
                precise,
                deadly,
                1.0 + resonant,
                Math.max(0.2, 1.0 - Math.min(0.6, commander)),
                tenacious,
                enduring
        );
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.max(0.0, Math.min(5.0, v));
    }
}
