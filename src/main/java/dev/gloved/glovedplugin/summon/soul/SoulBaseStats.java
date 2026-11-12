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
    public static final SoulBaseStats NEUTRAL = new SoulBaseStats(20.0, 0.0, 0.1, 0.80);

    private static final Gson GSON = new Gson();

    /** Attack speed is measured in attacks per second (APS). */
    public static final double MIN_ATTACK_SPEED_APS = 0.20;
    public static final double AVG_ATTACK_SPEED_APS = 0.80;
    public static final double MAX_ATTACK_SPEED_APS = 2.40;

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

    /** Converts stored/legacy/vanilla attack speed values to APS. */
    public static double normalizeAttackSpeedAps(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return MIN_ATTACK_SPEED_APS;
        double aps = value;
        // Legacy/vanilla entity ATTACK_SPEED attribute scale (typically ~4.0, caps ~8.0) -> APS scale.
        // New APS values can be > 2.0 (up to MAX_ATTACK_SPEED_APS), so only treat clearly vanilla-scale values as legacy.
        if (aps > 3.0) {
            aps = (aps / 10.0) * (AVG_ATTACK_SPEED_APS / 0.40); // preserve prior ~4.0 -> old avg mapping, scaled to new avg
        }
        return clamp(aps, MIN_ATTACK_SPEED_APS, MAX_ATTACK_SPEED_APS);
    }

    // ------------------------------------------------------------------ rolling

    /**
     * Rolls base stats within the rarity's auto-partitioned absolute band (ignores mob base values).
     * Endpoint exclusivity is enforced: only Common may roll the global floor and only Mythic the
     * global max. {@link #capped(SoulStatConfig)} is a no-op safety clamp under the new model.
     */
    public static SoulBaseStats roll(SoulRarity rarity, SoulStatConfig cfg, Random random) {
        Random rng = random != null ? random : new Random();
        SoulRarity r = rarity != null ? rarity : SoulRarity.COMMON;
        if (cfg == null) cfg = new SoulStatConfig();
        return new SoulBaseStats(
                sample(cfg, KEY_HEALTH, r, rng),
                sample(cfg, KEY_ATTACK_DAMAGE, r, rng),
                sample(cfg, KEY_MOVEMENT_SPEED, r, rng),
                sample(cfg, KEY_ATTACK_SPEED, r, rng)
        ).normalized();
    }

    /**
     * Legacy compatibility: old call sites (and legacy backfill) roll within the rarity band.
     * The extra boolean is ignored; base values on this instance are not used.
     */
    public SoulBaseStats withVariance(Random random, SoulRarity rarity, SoulStatConfig cfg,
                                      boolean ignoredHealthUsesRarityMultiplier) {
        return roll(rarity, cfg, random);
    }

    public SoulBaseStats withVariance(Random random, SoulRarity rarity, SoulStatConfig cfg) {
        return roll(rarity, cfg, random);
    }

    private static double sample(SoulStatConfig cfg, String key, SoulRarity rarity, Random rng) {
        SoulStatConfig.Band band = cfg.band(key, rarity);
        double lo = band.min();
        double hi = band.max();
        if (hi <= lo) return band.clampRolled(lo);
        double value = lo + rng.nextDouble() * (hi - lo);
        return band.clampRolled(value);
    }

    /** Applies hard stat floors/APS normalization (safety). */
    public SoulBaseStats normalized() {
        return new SoulBaseStats(
                Math.max(0.0, health),
                Math.max(4.0, attackDamage),
                Math.max(0.0, movementSpeed),
                normalizeAttackSpeedAps(attackSpeed)
        );
    }

    /** Legacy safety clamp; bands already bound values, but keep hard floors/global caps respected. */
    public SoulBaseStats capped(SoulStatConfig cfg) {
        SoulBaseStats n = normalized();
        if (cfg == null) return n;
        return new SoulBaseStats(
                clamp(n.health, cfg.globalMin(KEY_HEALTH), cfg.globalMax(KEY_HEALTH)),
                Math.max(4.0, clamp(n.attackDamage, cfg.globalMin(KEY_ATTACK_DAMAGE), cfg.globalMax(KEY_ATTACK_DAMAGE))),
                clamp(n.movementSpeed, cfg.globalMin(KEY_MOVEMENT_SPEED), cfg.globalMax(KEY_MOVEMENT_SPEED)),
                clamp(n.attackSpeed, cfg.globalMin(KEY_ATTACK_SPEED), cfg.globalMax(KEY_ATTACK_SPEED))
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

    /** Multiplies every stat by the Soul Table enhancement factor (no rarity ceiling). */
    public SoulBaseStats enhanced(int enhanceLevel, double perLevelBonus) {
        return enhanced(enhanceLevel, perLevelBonus, null, null);
    }

    /**
     * Applies enhancement and enforces rarity final-stat ceilings: non-Mythic cannot reach the
     * global max, Mythic may reach it. Attack speed uses diminishing returns toward the rarity's
     * ceiling (Mythic → global max).
     */
    public SoulBaseStats enhanced(int enhanceLevel, double perLevelBonus, SoulRarity rarity, SoulStatConfig cfg) {
        int level = Math.max(0, enhanceLevel);
        double bonus = Math.max(0.0, perLevelBonus);
        if (cfg == null) cfg = new SoulStatConfig();
        SoulRarity r = rarity != null ? rarity : SoulRarity.COMMON;
        if (level == 0 || bonus == 0.0) {
            return clampFinal(this, r, cfg);
        }
        double factor = 1.0 + bonus * level;
        double maxAtkSpd = cfg.band(KEY_ATTACK_SPEED, r).max();
        SoulBaseStats boosted = new SoulBaseStats(
                health * factor,
                attackDamage * factor,
                movementSpeed * factor,
                enhancedAttackSpeed(attackSpeed, factor, maxAtkSpd)
        );
        return clampFinal(boosted, r, cfg);
    }

    /** Clamps final/effective stats to the rarity's ceiling (enforces endpoint exclusivity). */
    public SoulBaseStats clampFinal(SoulRarity rarity, SoulStatConfig cfg) {
        if (cfg == null) cfg = new SoulStatConfig();
        return clampFinal(this, rarity != null ? rarity : SoulRarity.COMMON, cfg);
    }

    private static SoulBaseStats clampFinal(SoulBaseStats stats, SoulRarity rarity, SoulStatConfig cfg) {
        return new SoulBaseStats(
                cfg.band(KEY_HEALTH, rarity).clampFinal(stats.health),
                cfg.band(KEY_ATTACK_DAMAGE, rarity).clampFinal(Math.max(4.0, stats.attackDamage)),
                cfg.band(KEY_MOVEMENT_SPEED, rarity).clampFinal(stats.movementSpeed),
                cfg.band(KEY_ATTACK_SPEED, rarity).clampFinal(normalizeAttackSpeedAps(stats.attackSpeed))
        );
    }

    public static double enhancedAttackSpeed(double baseAps, double factor) {
        return enhancedAttackSpeed(baseAps, factor, MAX_ATTACK_SPEED_APS);
    }

    /**
     * Applies enhancement as a bonus above base attack speed (APS) with diminishing returns
     * toward the provided ceiling (rarity ceiling; Mythic → global max).
     */
    public static double enhancedAttackSpeed(double baseAps, double factor, double ceilingAps) {
        double base = normalizeAttackSpeedAps(baseAps);
        double f = Double.isFinite(factor) ? factor : 1.0;
        double max = clamp(ceilingAps, MIN_ATTACK_SPEED_APS, MAX_ATTACK_SPEED_APS);
        if (max < base) max = base;
        if (f <= 1.0) return clamp(base, MIN_ATTACK_SPEED_APS, max);
        double min = MIN_ATTACK_SPEED_APS;
        double bonus = f - 1.0;
        double span = Math.max(0.0001, max - min);
        double effectiveness = Math.pow(Math.max(0.0, (max - base) / span), 0.6);
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
