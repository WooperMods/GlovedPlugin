package dev.gloved.glovedplugin.summon.runtime;

import dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge;
import org.bukkit.entity.Player;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.config.SummonProfile;

/**
 * Effective summon stats after progression scaling. In dynamic soul/cast mode, "chance" is not
 * used for triggering; minionsPerHit = cast batch size, maxActive = per-type cap (global cap
 * enforced separately).
 */
public final class EffectiveSummonStats {
    public final double chance;
    public final int minionsPerHit;
    public final int maxActive;
    public final int summoningLevel;

    public EffectiveSummonStats(double chance, int minionsPerHit, int maxActive, int summoningLevel) {
        this.chance = chance;
        this.minionsPerHit = minionsPerHit;
        this.maxActive = maxActive;
        this.summoningLevel = summoningLevel;
    }

    public static EffectiveSummonStats resolve(Player owner, SummonProfile profile, SummonConfig config,
                                               SummonProgressionBridge progression) {
        if (profile == null) return new EffectiveSummonStats(0, 1, 1, 0);
        int level = 0;
        if (owner != null && progression != null && config != null && config.isProgressionEnabled() && config.isScalingEnabled()) {
            try {
                UUID id = owner.getUniqueId();
                level = Math.max(0, progression.getSummoningLevel(id));
            } catch (Throwable ignored) {}
        }
        if (config == null || !config.isProgressionEnabled() || !config.isScalingEnabled() || level <= 0) {
            return new EffectiveSummonStats(profile.chance, Math.max(1, profile.minionsPerHit),
                    Math.max(1, profile.maxActive), level);
        }
        // Never shrink below template base. Scaling may grow batch/per-type cap up to configured maxima.
        double chance = clamp(profile.chance + config.getScalingChancePerLevel() * level, 0.0,
                Math.max(profile.chance, config.getScalingChanceMax()));
        int mph = (int) Math.round(profile.minionsPerHit + config.getScalingMinionsPerLevel() * level);
        mph = Math.max(mph, profile.minionsPerHit);
        mph = Math.min(mph, Math.max(profile.minionsPerHit, config.getScalingMinionsPerHitMax()));
        int maxActive = (int) Math.round(profile.maxActive + config.getScalingMaxActivePerLevel() * level);
        maxActive = Math.max(maxActive, profile.maxActive);
        maxActive = Math.min(maxActive, Math.max(profile.maxActive, config.getScalingMaxActiveMax()));
        // Respect explicit per-type hard max-active overrides (e.g. set mob types capped to 1).
        try {
            var sc = config.getSoulConfig();
            Integer hardCap = sc != null ? sc.getTypeMaxActiveOverride(profile.name) : null;
            if (hardCap != null) {
                maxActive = Math.min(maxActive, Math.max(1, hardCap));
            }
        } catch (Throwable ignored) {}
        return new EffectiveSummonStats(chance, Math.max(1, mph), Math.max(1, maxActive), level);
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }
}
