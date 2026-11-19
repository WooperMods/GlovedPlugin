package dev.gloved.glovedplugin.summon.soul.domain;

/**
 * Computed/effective modifiers for a soul at cast time (bounded). Values are additive/multiplicative
 * modifiers interpreted by spawn/combat systems (implementation applies clamps).
 */
public record SoulStatSnapshot(
        double damageMultiplier,   // e.g. 1.0 + BRUTAL
        double healthMultiplier,   // e.g. 1.0 + VIGOROUS
        double speedMultiplier,    // legacy/compat (movement speed is tier-based; kept for API compat)
        double critChanceBonus,    // additive points fraction (0..cap)
        double critDamageBonus,    // additive fraction
        double abilityPotencyMultiplier, // 1.0 + RESONANT
        double costEfficiency,     // legacy neutral (1.0); COMMANDER removed in favor of respawn reduction
        double respawnReduction,   // 0..cap, fraction of respawn time reduced by REAWAKENING
        double tenacityBonus,      // TENACIOUS: DEF/armor stat bonus multiplier contribution (e.g. 0.10 = +10% DEF)
        double enduranceBonus,     // duration/uptime bonus
        double capacityBonus       // additive fraction for group capacity (e.g. 0.10 = +10%)
) {
    public static SoulStatSnapshot identity() {
        return new SoulStatSnapshot(1.0, 1.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0);
    }

    public static SoulStatSnapshot fromSoul(SoulInstance soul) {
        if (soul == null) return identity();
        double brutal = clamp01(soul.affixValue(SoulAffixType.BRUTAL));
        double vigorous = clamp01(soul.affixValue(SoulAffixType.VIGOROUS));
        double precise = Math.max(0.0, soul.affixValue(SoulAffixType.PRECISE));
        double deadly = Math.max(0.0, soul.affixValue(SoulAffixType.DEADLY));
        double resonant = clamp01(soul.affixValue(SoulAffixType.RESONANT));
        double reawakening = Math.max(0.0, Math.min(0.60, soul.affixValue(SoulAffixType.REAWAKENING)));
        double tenacious = clamp01(soul.affixValue(SoulAffixType.TENACIOUS));
        double enduring = clamp01(soul.affixValue(SoulAffixType.ENDURING));
        double capacity = Math.max(0.0, Math.min(1.0, soul.affixValue(SoulAffixType.CAPACITY)));
        return new SoulStatSnapshot(
                1.0 + brutal,
                1.0 + vigorous,
                1.0, // movement speed is tier-based (no SWIFT affix)
                precise,
                deadly,
                1.0 + resonant,
                1.0, // COMMANDER removed: cast costs/cooldowns are no longer reduced by affixes
                reawakening,
                tenacious, // TENACIOUS: DEF/armor stat bonus multiplier contribution
                enduring,
                capacity
        );
    }

    public double respawnMultiplier() {
        return Math.max(0.0, 1.0 - Math.max(0.0, Math.min(0.95, respawnReduction)));
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.max(0.0, Math.min(5.0, v));
    }
}
