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
 * fixed AI limit owned by {@code SummonProfile}. See AGENTS.md.</p>
 */
public record SoulBaseStats(
        double health,
        double attackDamage,
        double movementSpeed,
        double armor,
        double armorToughness,
        double knockbackResistance,
        double attackKnockback,
        double attackSpeed
) {
    /** Neutral fallback used when a mob has no read-able attribute map. */
    public static final SoulBaseStats NEUTRAL = new SoulBaseStats(20.0, 0.0, 0.1, 0.0, 0.0, 0.0, 0.0, 4.0);

    private static final Gson GSON = new Gson();

    /** Stat keys used by config variance/caps. */
    public static final String KEY_HEALTH = "health";
    public static final String KEY_ATTACK_DAMAGE = "attack-damage";
    public static final String KEY_MOVEMENT_SPEED = "movement-speed";
    public static final String KEY_ARMOR = "armor";
    public static final String KEY_ARMOR_TOUGHNESS = "armor-toughness";
    public static final String KEY_KNOCKBACK_RESISTANCE = "knockback-resistance";
    public static final String KEY_ATTACK_KNOCKBACK = "attack-knockback";
    public static final String KEY_ATTACK_SPEED = "attack-speed";

    // ------------------------------------------------------------------ capture

    /** Reads the live base attribute values of a spawned entity (null-safe). */
    public static SoulBaseStats fromEntity(LivingEntity entity) {
        if (entity == null) return NEUTRAL;
        SoulBaseStats fallback = defaults(entity.getType());
        return new SoulBaseStats(
                read(entity, Attribute.MAX_HEALTH, fallback.health),
                read(entity, Attribute.ATTACK_DAMAGE, fallback.attackDamage),
                read(entity, Attribute.MOVEMENT_SPEED, fallback.movementSpeed),
                read(entity, Attribute.ARMOR, fallback.armor),
                read(entity, Attribute.ARMOR_TOUGHNESS, fallback.armorToughness),
                read(entity, Attribute.KNOCKBACK_RESISTANCE, fallback.knockbackResistance),
                read(entity, Attribute.ATTACK_KNOCKBACK, fallback.attackKnockback),
                read(entity, Attribute.ATTACK_SPEED, fallback.attackSpeed)
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
                read(def, Attribute.ATTACK_DAMAGE, NEUTRAL.attackDamage),
                read(def, Attribute.MOVEMENT_SPEED, NEUTRAL.movementSpeed),
                read(def, Attribute.ARMOR, NEUTRAL.armor),
                read(def, Attribute.ARMOR_TOUGHNESS, NEUTRAL.armorToughness),
                read(def, Attribute.KNOCKBACK_RESISTANCE, NEUTRAL.knockbackResistance),
                read(def, Attribute.ATTACK_KNOCKBACK, NEUTRAL.attackKnockback),
                read(def, Attribute.ATTACK_SPEED, NEUTRAL.attackSpeed)
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
        return new SoulBaseStats(
                jitter(health, healthUsesRarityMultiplier ? mult : 1.0, cfg, KEY_HEALTH, rng),
                jitter(attackDamage, mult, cfg, KEY_ATTACK_DAMAGE, rng),
                jitter(movementSpeed, mult, cfg, KEY_MOVEMENT_SPEED, rng),
                jitter(armor, mult, cfg, KEY_ARMOR, rng),
                jitter(armorToughness, mult, cfg, KEY_ARMOR_TOUGHNESS, rng),
                jitter(knockbackResistance, mult, cfg, KEY_KNOCKBACK_RESISTANCE, rng),
                jitter(attackKnockback, mult, cfg, KEY_ATTACK_KNOCKBACK, rng),
                jitter(attackSpeed, mult, cfg, KEY_ATTACK_SPEED, rng)
        );
    }

    private static double jitter(double source, double multiplier, SoulStatConfig cfg, String key, Random rng) {
        double variance = cfg != null ? cfg.variance(key) : 0.0;
        double factor = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * Math.max(0.0, variance);
        return Math.max(0.0, source * multiplier * factor);
    }

    /** Clamps every stat to its configured cap. */
    public SoulBaseStats capped(SoulStatConfig cfg) {
        if (cfg == null) return this;
        return new SoulBaseStats(
                cap(health, cfg.cap(KEY_HEALTH)),
                cap(attackDamage, cfg.cap(KEY_ATTACK_DAMAGE)),
                cap(movementSpeed, cfg.cap(KEY_MOVEMENT_SPEED)),
                cap(armor, cfg.cap(KEY_ARMOR)),
                cap(armorToughness, cfg.cap(KEY_ARMOR_TOUGHNESS)),
                cap(knockbackResistance, cfg.cap(KEY_KNOCKBACK_RESISTANCE)),
                cap(attackKnockback, cfg.cap(KEY_ATTACK_KNOCKBACK)),
                cap(attackSpeed, cfg.cap(KEY_ATTACK_SPEED))
        );
    }

    private static double cap(double value, double cap) {
        if (!Double.isFinite(value)) return 0.0;
        if (cap <= 0.0) return Math.max(0.0, value);
        return Math.min(Math.max(0.0, value), cap);
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
                armor * factor,
                armorToughness * factor,
                knockbackResistance * factor,
                attackKnockback * factor,
                attackSpeed * factor
        );
    }

    // ------------------------------------------------------------------ json

    public String toJson() {
        StatsDto dto = new StatsDto();
        dto.health = health;
        dto.attackDamage = attackDamage;
        dto.movementSpeed = movementSpeed;
        dto.armor = armor;
        dto.armorToughness = armorToughness;
        dto.knockbackResistance = knockbackResistance;
        dto.attackKnockback = attackKnockback;
        dto.attackSpeed = attackSpeed;
        return GSON.toJson(dto);
    }

    public static SoulBaseStats fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            StatsDto dto = GSON.fromJson(json, StatsDto.class);
            if (dto == null) return null;
            return new SoulBaseStats(
                    dto.health, dto.attackDamage, dto.movementSpeed, dto.armor,
                    dto.armorToughness, dto.knockbackResistance, dto.attackKnockback, dto.attackSpeed);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static class StatsDto {
        double health, attackDamage, movementSpeed, armor, armorToughness,
                knockbackResistance, attackKnockback, attackSpeed;
    }
}
