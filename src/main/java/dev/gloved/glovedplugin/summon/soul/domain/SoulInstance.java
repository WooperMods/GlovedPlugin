package dev.gloved.glovedplugin.summon.soul.domain;

import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final int groupCapacity; // rolled base group capacity (summon group size), authoritative before affix/enhance modifiers
    private boolean favorite;

    public SoulInstance(UUID soulId, UUID ownerId, EntityType entityType, String familyKey, EntityCategory category,
                        SoulRarity rarity, double qualityScore, List<SoulAffixRoll> affixes,
                        SoulTrait trait, long createdAt, double maxHealth, boolean favorite) {
        this(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                trait != null ? List.of(trait) : List.of(), createdAt, maxHealth, favorite, null, 0, 1);
    }

    public SoulInstance(UUID soulId, UUID ownerId, EntityType entityType, String familyKey, EntityCategory category,
                        SoulRarity rarity, double qualityScore, List<SoulAffixRoll> affixes,
                        List<SoulTrait> traits, long createdAt, double maxHealth, boolean favorite) {
        this(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, null, 0, 1);
    }

    public SoulInstance(UUID soulId, UUID ownerId, EntityType entityType, String familyKey, EntityCategory category,
                        SoulRarity rarity, double qualityScore, List<SoulAffixRoll> affixes,
                        List<SoulTrait> traits, long createdAt, double maxHealth, boolean favorite,
                        SoulBaseStats baseStats, int enhanceLevel) {
        this(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel, 1);
    }

    public SoulInstance(UUID soulId, UUID ownerId, EntityType entityType, String familyKey, EntityCategory category,
                        SoulRarity rarity, double qualityScore, List<SoulAffixRoll> affixes,
                        List<SoulTrait> traits, long createdAt, double maxHealth, boolean favorite,
                        SoulBaseStats baseStats, int enhanceLevel, int groupCapacity) {
        this.soulId = soulId != null ? soulId : UUID.randomUUID();
        this.ownerId = ownerId;
        this.entityType = entityType;
        this.familyKey = familyKey != null ? familyKey : (entityType != null ? entityType.name() : "UNKNOWN");
        this.category = category != null ? category : EntityCategory.SPECIAL;
        this.rarity = rarity != null ? rarity : SoulRarity.COMMON;
        this.qualityScore = Double.isNaN(qualityScore) ? 0.0 : Math.max(0.0, Math.min(100.0, qualityScore));
        this.affixes = affixes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(affixes));
        this.traits = normalizeTraits(traits);
        this.trait = this.traits.isEmpty() ? null : this.traits.get(0);
        this.createdAt = createdAt;
        this.maxHealth = Double.isNaN(maxHealth) || maxHealth < 0 ? 0.0 : maxHealth;
        this.baseStats = baseStats;
        this.enhanceLevel = Math.max(0, enhanceLevel);
        this.groupCapacity = Math.max(1, groupCapacity);
        this.favorite = favorite;
    }

    private static List<SoulTrait> normalizeTraits(List<SoulTrait> traits) {
        if (traits == null || traits.isEmpty()) return List.of();
        List<SoulTrait> out = new ArrayList<>(traits.size());
        for (SoulTrait t : traits) {
            if (t == null || t.id() == null || t.id().isBlank()) continue;
            out.add(t);
        }
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
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
    public boolean hasTrait(SoulTrait t) {
        if (t == null || t.id() == null) return false;
        return hasTraitId(t.id());
    }
    public boolean hasTraitId(String id) {
        String canonical = SoulTrait.canonicalize(id);
        if (canonical == null) return false;
        for (SoulTrait tr : traits) {
            if (tr != null && canonical.equals(tr.id())) return true;
        }
        return false;
    }
    public List<String> getTraitIds() {
        return traits.stream().map(SoulTrait::id).collect(Collectors.toUnmodifiableList());
    }

    public long getCreatedAt() { return createdAt; }
    public double getMaxHealth() { return maxHealth; }
    public SoulBaseStats getBaseStats() { return baseStats; }
    public int getEnhanceLevel() { return enhanceLevel; }
    public int getGroupCapacity() { return groupCapacity; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    /** Same soul with a different rolled base-stat block (null keeps legacy behaviour). */
    public SoulInstance withBaseStats(SoulBaseStats baseStats) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel, groupCapacity);
    }

    /** Same soul at a different Soul Table enhancement level. */
    public SoulInstance withEnhanceLevel(int enhanceLevel) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel, groupCapacity);
    }

    /** Same soul with rerolled affixes and a recomputed quality score. */
    public SoulInstance withAffixes(List<SoulAffixRoll> affixes, double qualityScore) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel, groupCapacity);
    }

    /** Same soul with a different base group capacity. */
    public SoulInstance withGroupCapacity(int groupCapacity) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel, groupCapacity);
    }

    /** Combined update used by reforge/enhancement flows that may change multiple rolled fields. */
    public SoulInstance withReforgedState(SoulBaseStats baseStats, List<SoulAffixRoll> affixes, double qualityScore, int groupCapacity) {
        return new SoulInstance(soulId, ownerId, entityType, familyKey, category, rarity, qualityScore, affixes,
                traits, createdAt, maxHealth, favorite, baseStats, enhanceLevel, groupCapacity);
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
