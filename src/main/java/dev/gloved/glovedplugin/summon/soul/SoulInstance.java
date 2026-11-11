package dev.gloved.glovedplugin.summon.soul;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** A captured, reusable soul collectible with random stats. */
public final class SoulInstance {
    private final UUID soulId;
    private final UUID ownerId;
    private final EntityType entityType;
    private final String familyKey;
    private final EntityCategory category;
    private final SoulRarity rarity;
    private final double qualityScore;
    private final List<SoulAffixRoll> affixes;
    private final SoulTrait trait; // deprecated, kept for back-compat; use getTraits()
    private final List<SoulTrait> traits;
    private final long createdAt;
    private final double maxHealth; // captured mob max HP at capture time; 0.0 = not saved (use mob-type default)
    private final SoulBaseStats baseStats; // rolled capture-time stats; null for legacy souls before backfill
    private final int enhanceLevel; // Soul Table enhancement level (0 = none)
    private boolean favorite;

    public SoulInstance(UUID soulId, UUID ownerId, EntityType entityType, String familyKey, EntityCategory category,
                        SoulRarity rarity, double qualityScore, List<SoulAffixRoll> affixes,
                        SoulTrait trait, long createdAt, double maxHealth, boolean favorite) {
        this(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                trait != null ? List.of(trait) : List.of(), createdAt, maxHealth, favorite, null, 0);
    }

    public SoulInstance(UUID soulId, UUID ownerId, EntityType entityType, String familyKey, EntityCategory category,
                        SoulRarity rarity, double qualityScore, List<SoulAffixRoll> affixes,
                        List<SoulTrait> traits, long createdAt, double maxHealth, boolean favorite) {
        this(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, null, 0);
    }

    public SoulInstance(UUID soulId, UUID ownerId, EntityType entityType, String familyKey, EntityCategory category,
                        SoulRarity rarity, double qualityScore, List<SoulAffixRoll> affixes,
                        List<SoulTrait> traits, long createdAt, double maxHealth, boolean favorite,
                        SoulBaseStats baseStats, int enhanceLevel) {
        this.soulId = soulId != null ? soulId : UUID.randomUUID();
        this.ownerId = ownerId;
        this.entityType = entityType;
        this.familyKey = familyKey != null ? familyKey : (entityType != null ? entityType.name() : "UNKNOWN");
        this.category = category != null ? category : EntityCategory.SPECIAL;
        this.rarity = rarity != null ? rarity : SoulRarity.COMMON;
        this.qualityScore = Double.isNaN(qualityScore) ? 0.0 : Math.max(0.0, Math.min(100.0, qualityScore));
        this.affixes = affixes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(affixes));
        this.traits = traits == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(traits));
        this.trait = this.traits.isEmpty() ? null : this.traits.get(0);
        this.createdAt = createdAt;
        this.maxHealth = Double.isNaN(maxHealth) || maxHealth < 0 ? 0.0 : maxHealth;
        this.baseStats = baseStats;
        this.enhanceLevel = Math.max(0, enhanceLevel);
        this.favorite = favorite;
    }

    public UUID getSoulId() { return soulId; }
    public UUID getOwnerId() { return ownerId; }
    public EntityType getEntityType() { return entityType; }
    public String getFamilyKey() { return familyKey; }
    public EntityCategory getCategory() { return category; }
    public SoulRarity getRarity() { return rarity; }
    public double getQualityScore() { return qualityScore; }
    public List<SoulAffixRoll> getAffixes() { return affixes; }
    public SoulTrait getTrait() { return trait; }
    /** All traits on this soul (may be empty). */
    public List<SoulTrait> getTraits() { return traits; }
    public boolean hasTrait(SoulTrait t) { return t != null && traits.contains(t); }

    public long getCreatedAt() { return createdAt; }
    public double getMaxHealth() { return maxHealth; }
    public SoulBaseStats getBaseStats() { return baseStats; }
    public int getEnhanceLevel() { return enhanceLevel; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    /** Same soul with a different rolled base-stat block (null keeps legacy behaviour). */
    public SoulInstance withBaseStats(SoulBaseStats baseStats) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel);
    }

    /** Same soul at a different Soul Table enhancement level. */
    public SoulInstance withEnhanceLevel(int enhanceLevel) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel);
    }

    /** Same soul with rerolled affixes and a recomputed quality score. */
    public SoulInstance withAffixes(List<SoulAffixRoll> affixes, double qualityScore) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel);
    }

    /** Total rolled value for an affix type (sum). */
    public double affixValue(SoulAffixType type) {
        if (type == null) return 0.0;
        double sum = 0.0;
        for (SoulAffixRoll r : affixes) {
            if (r != null && r.type() == type) sum += r.value();
        }
        return sum;
    }
}
