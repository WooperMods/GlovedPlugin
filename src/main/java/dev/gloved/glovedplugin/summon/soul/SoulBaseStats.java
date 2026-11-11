package dev.gloved.glovedplugin.summon.soul;

import com.google.gson.Gson;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig.SoulStatConfig;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.Random;

/**
 * Captured (rolled) base attributes of a soul.
 *
 * <p>These are the mob's own attribute values, captured once at soul creation and then scaled by
 * rarity, jittered by a per-stat variance and clamped to configured caps. Affix multipliers and
 * the Soul Table enhancement level are applied on top of these values at summon time.</p>
 *
 * <p>Follow range is intentionally <b>not</b> part of this record: minion follow/target range is a
 * fixed AI limit owned by {@code SummonProfile}. Armor, armor toughness, knockback resistance and
 * attack knockback are also excluded by design, so minions keep their vanilla values for those
 * attributes. See AGENTS.md.</p>
 */
public record SoulBaseStats(
        double health,
        double attackDamage,
        double movementSpeed,
        double attackSpeed
) {
    /** Neutral fallback used when a mob has no read-able attribute map. Attack speed is in APS. */
    public static final SoulBaseStats NEUTRAL = new SoulBaseStats(20.0, 0.0, 0.1, 0.40);

    private static final Gson GSON = new Gson();

    /** Attack speed is measured in attacks per second (APS). */
    public static final double MIN_ATTACK_SPEED_APS = 0.10;
    public static final double AVG_ATTACK_SPEED_APS = 0.40;
    public static final double MAX_ATTACK_SPEED_APS = 1.20;

    /** Stat keys used by config variance/caps. */
    public static final String KEY_HEALTH = "health";
    public static final String KEY_ATTACK_DAMAGE = "attack-damage";
    public static final String KEY_MOVEMENT_SPEED = "movement-speed";
    public static final String KEY_ATTACK_SPEED = "attack-speed";

    // ------------------------------------------------------------------ capture

    /** Reads the live base attribute values of a spawned entity (null-safe). */
    public static SoulBaseStats fromEntity(LivingEntity entity) {
        if (entity == null) return NEUTRAL;
        SoulBaseStats fallback = defaults(entity.getType());
        return new SoulBaseStats(
                read(entity, Attribute.MAX_HEALTH, fallback.health),
                Math.max(4.0, read(entity, Attribute.ATTACK_DAMAGE, fallback.attackDamage)),
                read(entity, Attribute.MOVEMENT_SPEED, fallback.movementSpeed),
                readAttackSpeedAps(entity, fallback.attackSpeed)
        );
    }

    /** Reads the vanilla default attribute map of an entity type (null-safe). */
    public static SoulBaseStats defaults(EntityType type) {
        if (type == null) return NEUTRAL;
        Attributable def;
        try {
            if (!type.hasDefaultAttributes()) return NEUTRAL;
            def = type.getDefaultAttributes();
        } catch (Throwable ignored) {
            return NEUTRAL;
        }
        if (def == null) return NEUTRAL;
        return new SoulBaseStats(
                read(def, Attribute.MAX_HEALTH, NEUTRAL.health),
                Math.max(4.0, read(def, Attribute.ATTACK_DAMAGE, NEUTRAL.attackDamage)),
                read(def, Attribute.MOVEMENT_SPEED, NEUTRAL.movementSpeed),
                readAttackSpeedAps(def, NEUTRAL.attackSpeed)
        );
    }

    private static double read(Attributable attributable, Attribute attribute, double fallback) {
        try {
            var instance = attributable.getAttribute(attribute);
            if (instance == null) return fallback;
            double value = instance.getBaseValue();
            return Double.isFinite(value) ? Math.max(0.0, value) : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static double readAttackSpeedAps(Attributable attributable, double fallback) {
        try {
            var instance = attributable.getAttribute(Attribute.ATTACK_SPEED);
            if (instance == null) return fallback;
            double value = instance.getBaseValue();
            if (!Double.isFinite(value)) return fallback;
            // Convert vanilla attribute scale (e.g. ~4.0) to APS (~0.40 avg).
            return normalizeAttackSpeedAps(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** Converts stored/legacy/vanilla attack speed values to APS (0.10–1.20). */
    public static double normalizeAttackSpeedAps(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return MIN_ATTACK_SPEED_APS;
        double aps = value;
        // Legacy/vanilla attribute scale (typically ~4.0, caps ~8.0) -> APS scale.
        if (aps > 2.0) {
            aps = aps / 10.0;
        }
        return clamp(aps, MIN_ATTACK_SPEED_APS, MAX_ATTACK_SPEED_APS);
    }

    // ------------------------------------------------------------------ rolling

    /**
     * Applies the rarity multiplier and a uniform per-stat variance roll.
     * Caps are applied separately via {@link #capped(SoulStatConfig)}.
     */
    public SoulBaseStats withVariance(Random random, SoulRarity rarity, SoulStatConfig cfg) {
        return withVariance(random, rarity, cfg, true);
    }

    /**
     * Applies the rarity multiplier and a uniform per-stat variance roll.
     * Caps are applied separately via {@link #capped(SoulStatConfig)}.
     *
     * @param healthUsesRarityMultiplier false for legacy backfill of elite-captured health
     *                                   (max_health already includes elite inflation)
     */
    public SoulBaseStats withVariance(Random random, SoulRarity rarity, SoulStatConfig cfg,
                                      boolean healthUsesRarityMultiplier) {
        Random rng = random != null ? random : new Random();
        double mult = cfg != null ? cfg.rarityMultiplier(rarity) : 1.0;
        double atkMult = mult * attackRarityBoost(rarity);
        double moveMult = movementRarityMultiplier(rarity);
        double atkSpdMult = attackSpeedRarityMultiplier(rarity);
        double moveBase = 2.5 / 43.17; // normalized target average base (~2.5 bps)
        double atkSpdBase = AVG_ATTACK_SPEED_APS; // normalized target average base (~0.40 APS)
        return new SoulBaseStats(
                jitter(health, healthUsesRarityMultiplier ? mult : 1.0, cfg, KEY_HEALTH, rng),
                Math.max(4.0, jitter(attackDamage, atkMult, cfg, KEY_ATTACK_DAMAGE, rng)),
                jitter(moveBase, moveMult, cfg, KEY_MOVEMENT_SPEED, rng),
                jitter(atkSpdBase, atkSpdMult, cfg, KEY_ATTACK_SPEED, rng)
        );
    }

    /**
     * Extra rarity-based scaling for attack damage so higher-rarity souls hit significantly harder.
     */
    private static double attackRarityBoost(SoulRarity rarity) {
        if (rarity == null) return 1.0;
        return switch (rarity) {
            case COMMON -> 1.0;
            case UNCOMMON -> 1.25;
            case RARE -> 1.6;
            case EPIC -> 2.1;
            case LEGENDARY -> 2.8;
            case MYTHIC -> 3.8;
        };
    }

    /**
     * Rarity scaling for movement speed base rolls, tuned around a ~2.5 bps average base
     * with headroom for SWIFT and diminishing returns up to the global cap.
     */
    private static double movementRarityMultiplier(SoulRarity rarity) {
        if (rarity == null) return 1.0;
        return switch (rarity) {
            case COMMON -> 1.00;
            case UNCOMMON -> 1.08;
            case RARE -> 1.18;
            case EPIC -> 1.30;
            case LEGENDARY -> 1.45;
            case MYTHIC -> 1.60;
        };
    }

    /**
     * Rarity scaling for attack speed base rolls (APS), tuned around ~0.40 average with
     * headroom for bonuses up to the 1.20 APS cap.
     */
    private static double attackSpeedRarityMultiplier(SoulRarity rarity) {
        if (rarity == null) return 1.0;
        return switch (rarity) {
            case COMMON -> 1.00;
            case UNCOMMON -> 1.08;
            case RARE -> 1.18;
            case EPIC -> 1.30;
            case LEGENDARY -> 1.45;
            case MYTHIC -> 1.60;
        };
    }

    private static double jitter(double source, double multiplier, SoulStatConfig cfg, String key, Random rng) {
        double variance = cfg != null ? cfg.variance(key) : 0.0;
        double factor = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * Math.max(0.0, variance);
        return Math.max(0.0, source * multiplier * factor);
    }

    /** Clamps every stat to its configured cap and applies minimum floors. */
    public SoulBaseStats capped(SoulStatConfig cfg) {
        if (cfg == null) {
            return new SoulBaseStats(health, Math.max(4.0, attackDamage), movementSpeed, attackSpeed);
        }
        return new SoulBaseStats(
                cap(health, cfg.cap(KEY_HEALTH)),
                Math.max(4.0, cap(attackDamage, cfg.cap(KEY_ATTACK_DAMAGE))),
                cap(movementSpeed, cfg.cap(KEY_MOVEMENT_SPEED)),
                cap(attackSpeed, cfg.cap(KEY_ATTACK_SPEED))
        );
    }

    private static double cap(double value, double cap) {
        if (!Double.isFinite(value)) return 0.0;
        if (cap <= 0.0) return Math.max(0.0, value);
        return Math.min(Math.max(0.0, value), cap);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /** Multiplies every stat by the Soul Table enhancement factor. */
    public SoulBaseStats enhanced(int enhanceLevel, double perLevelBonus) {
        int level = Math.max(0, enhanceLevel);
        double bonus = Math.max(0.0, perLevelBonus);
        if (level == 0 || bonus == 0.0) return this;
        double factor = 1.0 + bonus * level;
        return new SoulBaseStats(
                health * factor,
                attackDamage * factor,
                movementSpeed * factor,
                enhancedAttackSpeed(attackSpeed, factor)
        );
    }

    /**
     * Applies enhancement as a bonus above base attack speed (APS) with diminishing returns
     * toward the cap (higher APS => harder to increase).
     */
    public static double enhancedAttackSpeed(double baseAps, double factor) {
        double base = normalizeAttackSpeedAps(baseAps);
        double f = Double.isFinite(factor) ? factor : 1.0;
        if (f <= 1.0) return base;
        double max = MAX_ATTACK_SPEED_APS;
        double min = MIN_ATTACK_SPEED_APS;
        double bonus = f - 1.0;
        // Gentler curve than movement speed: easier early gains, still diminished near cap.
        double effectiveness = Math.pow(Math.max(0.0, (max - base) / (max - min)), 0.6);
        double applied = base + base * bonus * effectiveness;
        return clamp(applied, min, max);
    }

    // ------------------------------------------------------------------ json

    public String toJson() {
        StatsDto dto = new StatsDto();
        dto.health = health;
        dto.attackDamage = attackDamage;
        dto.movementSpeed = movementSpeed;
        dto.attackSpeed = attackSpeed;
        return GSON.toJson(dto);
    }

    /**
     * Parses stored stats. Unknown fields are ignored, so rows written before retired stats were
     * removed still load cleanly. Legacy attack speed values (vanilla attribute scale) are
     * converted to APS.
     */
    public static SoulBaseStats fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            StatsDto dto = GSON.fromJson(json, StatsDto.class);
            if (dto == null) return null;
            double atkSpd = normalizeAttackSpeedAps(dto.attackSpeed);
            double atkDmg = Double.isFinite(dto.attackDamage) ? Math.max(4.0, dto.attackDamage) : 4.0;
            return new SoulBaseStats(dto.health, atkDmg, dto.movementSpeed, atkSpd);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static class StatsDto {
        double health, attackDamage, movementSpeed, attackSpeed;
    }
}
