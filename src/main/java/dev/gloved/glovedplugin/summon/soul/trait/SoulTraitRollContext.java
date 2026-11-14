package dev.gloved.glovedplugin.summon.soul.trait;

import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.domain.EntityCategory;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixRoll;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixType;
import dev.gloved.glovedplugin.summon.soul.domain.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import org.bukkit.entity.EntityType;

import java.util.List;
import java.util.UUID;

/**
 * Generation-time context for trait roll eligibility checks.
 * Affixes/base group capacity are the rolled values for the soul being generated (enhance level 0).
 */
public record SoulTraitRollContext(
        UUID owner,
        EntityType entityType,
        String familyKey,
        EntityCategory category,
        SoulRarity rarity,
        int baseGroupCapacity,
        List<SoulAffixRoll> affixes,
        SoulConfig soulConfig,
        SummonConfig summonConfig
) {
    public SoulTraitRollContext {
        affixes = affixes == null ? List.of() : List.copyOf(affixes);
        if (familyKey == null || familyKey.isBlank()) {
            familyKey = entityType != null ? entityType.name() : "UNKNOWN";
        }
        baseGroupCapacity = Math.max(1, baseGroupCapacity);
    }

    /** Capacity bonus fraction from rolled affixes (parity with SoulStatSnapshot CAPACITY handling). */
    public double capacityBonusPct() {
        double sum = 0.0;
        for (SoulAffixRoll r : affixes) {
            if (r != null && r.type() == SoulAffixType.CAPACITY) sum += r.value();
        }
        if (Double.isNaN(sum) || Double.isInfinite(sum)) return 0.0;
        return Math.max(0.0, Math.min(1.0, sum));
    }

    /**
     * Final effective group capacity at generation time (enhance 0), using the same rules as
     * {@code SoulService.effectiveGroupCapacity} for base/affix/ceilings.
     */
    public int effectiveGroupCapacity() {
        int base = Math.max(1, baseGroupCapacity);
        int enhanceBonus = 0; // generation-time, enhance level 0
        int baseWithEnhance = Math.max(1, base + enhanceBonus);

        double pct = capacityBonusPct();
        int computed = (int) Math.floor(baseWithEnhance * (1.0 + pct) + 1e-9);
        if (pct > 0.0 && computed == baseWithEnhance) {
            // Ensure a meaningful mild % affix can contribute at low capacities.
            computed = baseWithEnhance + 1;
        }
        if (computed < 1) computed = 1;

        int ceiling = 12; // absolute safety ceiling (parity)
        try {
            if (soulConfig != null) {
                String fam = familyKey;
                if (fam == null || fam.isBlank()) fam = entityType != null ? entityType.name() : null;
                Integer hard = fam != null ? soulConfig.getTypeMaxActiveOverride(fam) : null;
                if (hard == null && entityType != null) {
                    hard = soulConfig.getTypeMaxActiveOverride(entityType.name());
                }
                if (hard != null) ceiling = Math.min(ceiling, Math.max(1, hard));
            }
        } catch (Throwable ignored) {}

        return Math.max(1, Math.min(computed, ceiling));
    }
}
