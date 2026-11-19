package dev.gloved.glovedplugin.summon.soul.domain;

import com.google.gson.Gson;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig.SoulStatConfig;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.Random;

/**
 * Captured (rolled) base attributes of a soul.
 *
 * <p>These are the soul's rolled base values, scaled by rarity and clamped to configured caps.
 * Affix multipliers and the Soul Table enhancement level are applied on top at summon time.</p>
 *
 * <p>Follow range is intentionally <b>not</b> part of this record: minion follow/target range is a
 * fixed AI limit owned by {@code SummonProfile}. Armor toughness, knockback resistance and attack
 * knockback remain non-soul/vanilla attributes.</p>
 *
 * <p>DEF is a soul stat (armor points) applied to summoned minions via {@code Attribute.ARMOR}.
 * With vanilla toughness 0, rolled DEF is capped at 13.75 (~55% base damage negation) but enhancement can exceed caps.</p>
 *
 * <p>RESPAWN is a base soul stat (seconds) rolled per rarity band; enhancement and respawn-reduction affixes
 * reduce effective downtime with configured/global safety floors.</p>
 */
public record SoulBaseStats(
        double health,
        double attackDamage,
        double movementSpeed,
        double attackSpeed,
        double defense,
        double respawnSeconds
) {
    /** Neutral fallback used when a mob has no read-able attribute map. Attack speed in APS, respawn in seconds. */
    public static final SoulBaseStats NEUTRAL = new SoulBaseStats(20.0, 0.0, 0.1, 0.80, 6.0, 60.0);

    private static final Gson GSON = new Gson();

    /** Attack speed is measured in attacks per second (APS). */
    public static final double MIN_ATTACK_SPEED_APS = 0.20;
    public static final double AVG_ATTACK_SPEED_APS = 0.80;
    public static final double MAX_ATTACK_SPEED_APS = 2.40;

    /** DEF (armor points). Min 1; base/roll cap 13.75 (~55% base armor damage negation, toughness 0). Enhancement can exceed caps. */
    public static final double MIN_DEFENSE = 1.0;
    public static final double MAX_DEFENSE = 13.75;

    /** Respawn time base bounds (seconds). Effective respawn is clamped by config/global floors at runtime. */
    public static final double MIN_RESPAWN_SECONDS = 10.0;
    public static final double MAX_RESPAWN_SECONDS = 120.0;

    /** Movement speed tiers (stored in movementSpeed): 1=Normal, 2=Fast, 3=Very Fast, 4=Insane. */
    public static final int MIN_MOVEMENT_SPEED_TIER = 1;
    public static final int MAX_MOVEMENT_SPEED_TIER = 4;

    /** Stat keys used by config bounds/bands/caps. */
    public static final String KEY_HEALTH = "health";
    public static final String KEY_ATTACK_DAMAGE = "attack-damage";
    public static final String KEY_MOVEMENT_SPEED = "movement-speed";
    public static final String KEY_ATTACK_SPEED = "attack-speed";
    public static final String KEY_DEFENSE = "defense";
    public static final String KEY_RESPAWN = "respawn";

    // ------------------------------------------------------------------ capture

    /** Reads the live base attribute values of a spawned entity (null-safe). */
    public static SoulBaseStats fromEntity(LivingEntity entity) {
        if (entity == null) return NEUTRAL;
        SoulBaseStats fallback = defaults(entity.getType());
        return new SoulBaseStats(
                read(entity, Attribute.MAX_HEALTH, fallback.health),
                Math.max(4.0, read(entity, Attribute.ATTACK_DAMAGE, fallback.attackDamage)),
                read(entity, Attribute.MOVEMENT_SPEED, fallback.movementSpeed),
                readAttackSpeedAps(entity, fallback.attackSpeed),
                readDefense(entity, fallback.defense),
                fallback.respawnSeconds
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
                readAttackSpeedAps(def, NEUTRAL.attackSpeed),
                readDefense(def, NEUTRAL.defense),
                NEUTRAL.respawnSeconds
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

    private static double readDefense(Attributable attributable, double fallback) {
        try {
            var instance = attributable.getAttribute(Attribute.ARMOR);
            if (instance == null) return clampDefense(Math.max(MIN_DEFENSE, fallback));
            double value = instance.getBaseValue();
            if (!Double.isFinite(value)) return clampDefense(Math.max(MIN_DEFENSE, fallback));
            return clampDefense(Math.max(MIN_DEFENSE, value));
        } catch (Throwable ignored) {
            return clampDefense(Math.max(MIN_DEFENSE, fallback));
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

    public static double clampDefense(double value) {
        if (!Double.isFinite(value)) return MIN_DEFENSE;
        if (value < MIN_DEFENSE) return MIN_DEFENSE;
        if (value > MAX_DEFENSE) return MAX_DEFENSE;
        return value;
    }

    // ------------------------------------------------------------------ rolling

    /**
     * Rolls base stats within the rarity's auto-partitioned absolute band (ignores mob base values).
     * Movement speed is tier-based (1-4: Normal/Fast/Very Fast/Insane), rolled with equal chance for any rarity.
     */
    public static SoulBaseStats roll(SoulRarity rarity, SoulStatConfig cfg, Random random) {
        Random rng = random != null ? random : new Random();
        SoulRarity r = rarity != null ? rarity : SoulRarity.COMMON;
        if (cfg == null) cfg = new SoulStatConfig();
        int moveTier = MIN_MOVEMENT_SPEED_TIER + rng.nextInt(MAX_MOVEMENT_SPEED_TIER - MIN_MOVEMENT_SPEED_TIER + 1);
        return new SoulBaseStats(
                sample(cfg, KEY_HEALTH, r, rng),
                sample(cfg, KEY_ATTACK_DAMAGE, r, rng),
                (double) moveTier,
                sample(cfg, KEY_ATTACK_SPEED, r, rng),
                sample(cfg, KEY_DEFENSE, r, rng),
                sample(cfg, KEY_RESPAWN, r, rng)
        ).normalized();
    }

    /** Default respawn seconds for a rarity band midpoint (used for legacy migration fallbacks). */
    public static double defaultRespawnSeconds(SoulRarity rarity, SoulStatConfig cfg) {
        SoulRarity r = rarity != null ? rarity : SoulRarity.COMMON;
        if (cfg == null) cfg = new SoulStatConfig();
        SoulStatConfig.Band band = cfg.band(KEY_RESPAWN, r);
        double mid = (band.min() + band.max()) / 2.0;
        if (!Double.isFinite(mid)) mid = NEUTRAL.respawnSeconds;
        return band.clampRolled(mid);
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
        double t = rng.nextDouble();
        double skew = cfg.rollSkew(key);
        if (Double.isFinite(skew) && skew > 0.0 && Math.abs(skew - 1.0) > 1.0e-6) {
            t = Math.pow(t, skew);
        }
        if (!Double.isFinite(t) || t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;
        double value = lo + t * (hi - lo);
        return band.clampRolled(value);
    }

    /** Normalize/migrate movement speed to tier (1-4). Legacy continuous attribute/bps-like values are mapped. */
    public static int normalizeMovementSpeedTier(double value) {
        if (!Double.isFinite(value)) return MIN_MOVEMENT_SPEED_TIER;
        // Already tier-like (1..4)
        if (value >= 0.5 && value <= 4.5) {
            int tier = (int) Math.round(value);
            if (tier < MIN_MOVEMENT_SPEED_TIER) tier = MIN_MOVEMENT_SPEED_TIER;
            if (tier > MAX_MOVEMENT_SPEED_TIER) tier = MAX_MOVEMENT_SPEED_TIER;
            // If clearly in integer tier range, treat as tier
            if (Math.abs(value - tier) < 0.45 || (value >= 1.0 && value <= 4.0)) {
                return tier;
            }
        }
        // Legacy continuous attribute speed (e.g. 0.23) or bps-like values: map to tiers
        double attr = value;
        if (attr > 1.0) {
            // Could be bps-like; convert rough bps->attr (43.17 bps per 1.0 attr) if very large
            if (attr > 4.5) attr = attr / 43.17;
        }
        if (attr < 0.26) return 1;       // Normal
        if (attr < 0.312) return 2;      // ~Speed I (+20%)
        if (attr < 0.374) return 3;      // ~Speed II (+40%)
        return 4;                        // Speed III+
    }

    public static String movementSpeedTierLabel(int tier) {
        return switch (Math.max(MIN_MOVEMENT_SPEED_TIER, Math.min(MAX_MOVEMENT_SPEED_TIER, tier))) {
            case 1 -> "Normal";
            case 2 -> "Fast";
            case 3 -> "Very Fast";
            default -> "Insane";
        };
    }

    /** Potion Speed amplifier for tier (0-based), or -1 for no speed effect. */
    public static int speedPotionAmplifierForTier(int tier) {
        int t = Math.max(MIN_MOVEMENT_SPEED_TIER, Math.min(MAX_MOVEMENT_SPEED_TIER, tier));
        return t <= 1 ? -1 : (t - 2); // Tier2->0 (Speed I), Tier3->1, Tier4->2
    }

    public int movementSpeedTier() {
        return normalizeMovementSpeedTier(movementSpeed);
    }

    public String movementSpeedTierLabel() {
        return movementSpeedTierLabel(movementSpeedTier());
    }

    /** Applies hard stat floors/APS/DEF/respawn normalization (safety). Movement speed is tier-based (1-4). */
    public SoulBaseStats normalized() {
        double rs = Double.isFinite(respawnSeconds) ? respawnSeconds : NEUTRAL.respawnSeconds;
        if (rs <= 0.0) rs = NEUTRAL.respawnSeconds;
        rs = clamp(rs, MIN_RESPAWN_SECONDS, MAX_RESPAWN_SECONDS);
        return new SoulBaseStats(
                Math.max(0.0, health),
                Math.max(4.0, attackDamage),
                (double) normalizeMovementSpeedTier(movementSpeed),
                normalizeAttackSpeedAps(attackSpeed),
                clampDefense(defense),
                rs
        );
    }

    /** Legacy safety clamp; bands already bound values, but keep hard floors/global caps respected. Movement speed is tier-based. */
    public SoulBaseStats capped(SoulStatConfig cfg) {
        SoulBaseStats n = normalized();
        if (cfg == null) return n;
        return new SoulBaseStats(
                clamp(n.health, cfg.globalMin(KEY_HEALTH), cfg.globalMax(KEY_HEALTH)),
                Math.max(4.0, clamp(n.attackDamage, cfg.globalMin(KEY_ATTACK_DAMAGE), cfg.globalMax(KEY_ATTACK_DAMAGE))),
                (double) n.movementSpeedTier(),
                clamp(n.attackSpeed, cfg.globalMin(KEY_ATTACK_SPEED), cfg.globalMax(KEY_ATTACK_SPEED)),
                clamp(n.defense, cfg.globalMin(KEY_DEFENSE), cfg.globalMax(KEY_DEFENSE)),
                clamp(n.respawnSeconds, cfg.globalMin(KEY_RESPAWN), cfg.globalMax(KEY_RESPAWN))
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
     * Applies enhancement to base stats (movement speed is tier-based and not enhanced).
     * Enhancement is allowed to exceed rarity/global caps (including DEF's base cap); base/rolled stats remain capped when not enhanced.
     * Attack speed uses diminishing returns toward the rarity's ceiling and is not hard-clamped above it.
     * Respawn time is improved (reduced) by enhancement with diminishing/bounded effect and never below global floor.
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
        double boostedAtkSpd = enhancedAttackSpeed(attackSpeed, factor, maxAtkSpd);
        // Allow enhancement to push attack speed beyond the rarity/global ceiling.
        if (attackSpeed >= maxAtkSpd - 1.0e-9) {
            boostedAtkSpd = normalizeAttackSpeedAps(attackSpeed * factor);
        }
        // Respawn: enhancement reduces downtime (bounded), never below global floor.
        double respawnReduction = Math.min(0.20, Math.max(0.0, perLevelBonus) * level); // configurable cap mirrored in runtime logic
        double respawnFloor = Math.max(MIN_RESPAWN_SECONDS, cfg.globalMin(KEY_RESPAWN));
        double enhancedRespawn = Math.max(respawnFloor, respawnSeconds * (1.0 - respawnReduction));
        return new SoulBaseStats(
                Math.max(0.0, health * factor),
                Math.max(4.0, attackDamage * factor),
                (double) movementSpeedTier(),
                Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS, boostedAtkSpd),
                Math.max(MIN_DEFENSE, defense * factor),
                enhancedRespawn
        );
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
                (double) stats.movementSpeedTier(), // tier-based; not clamped by continuous movement bands
                cfg.band(KEY_ATTACK_SPEED, rarity).clampFinal(normalizeAttackSpeedAps(stats.attackSpeed)),
                cfg.band(KEY_DEFENSE, rarity).clampFinal(clampDefense(stats.defense)),
                cfg.band(KEY_RESPAWN, rarity).clampFinal(clamp(stats.respawnSeconds, cfg.globalMin(KEY_RESPAWN), cfg.globalMax(KEY_RESPAWN)))
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
        dto.defense = defense;
        dto.respawnSeconds = respawnSeconds;
        return GSON.toJson(dto);
    }

    /**
     * Parses stored stats. Unknown fields are ignored, so rows written before retired/new stats were
     * added still load cleanly. Missing defense defaults to the soul minimum (6.0). Accepts legacy
     * "armor" as an alias for defense. Legacy attack speed values (vanilla attribute scale) are
     * converted to APS. Legacy payloads missing respawnSeconds parse with a sentinel (<= 0) so callers
     * can backfill a rarity-band/default value.
     */
    public static SoulBaseStats fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            StatsDto dto = GSON.fromJson(json, StatsDto.class);
            if (dto == null) return null;
            double atkSpd = normalizeAttackSpeedAps(dto.attackSpeed);
            double atkDmg = Double.isFinite(dto.attackDamage) ? Math.max(4.0, dto.attackDamage) : 4.0;
            double def = dto.defense;
            double respawn = dto.respawnSeconds;
            // Accept legacy "armor" key / detect missing respawn from raw JSON for migration backfill.
            try {
                com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                if ((!Double.isFinite(def) || def <= 0.0) && obj.has("armor")) def = obj.get("armor").getAsDouble();
                if (!obj.has("respawnSeconds") && !obj.has("respawn")) {
                    respawn = -1.0; // sentinel: missing respawn stat (legacy row/item), needs backfill
                } else if (obj.has("respawn") && !obj.has("respawnSeconds")) {
                    respawn = obj.get("respawn").getAsDouble();
                }
            } catch (Throwable ignored) {
                if (!Double.isFinite(respawn) || respawn == 0.0) respawn = -1.0;
            }
            if (!Double.isFinite(def) || def <= 0.0) def = MIN_DEFENSE;
            def = clampDefense(def);
            // Keep respawn sentinel/invalid as-is here for migration detection; valid values are normalized.
            if (Double.isFinite(respawn) && respawn > 0.0) {
                respawn = clamp(respawn, MIN_RESPAWN_SECONDS, MAX_RESPAWN_SECONDS);
            } else if (!Double.isFinite(respawn)) {
                respawn = -1.0;
            }
            return new SoulBaseStats(dto.health, atkDmg, normalizeMovementSpeedTier(dto.movementSpeed), atkSpd, def, respawn);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static class StatsDto {
        double health, attackDamage, movementSpeed, attackSpeed, defense, respawnSeconds;
    }
}
