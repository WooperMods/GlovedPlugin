package dev.gloved.glovedplugin.summon.phasing;

import dev.gloved.glovedplugin.summon.ReachabilityService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Centralizes Vex/phasing-minion behavior. Phasing mobs can move through solid blocks, so block-collision
 * pathfinding/reachability and some manual AI controls are handled differently from other minions.
 *
 * <p>All Vex-specific logic should be routed through this package to avoid affecting other mobs.</p>
 */
public final class PhasingMob {
    private PhasingMob() {}

    /** Whether the mob phases through solid blocks (currently Vex). */
    public static boolean isPhasing(LivingEntity mob) {
        return mob instanceof org.bukkit.entity.Vex;
    }

    /** Whether this minion should use vanilla AI control (goals/movement/attacks) with target assignment only. */
    public static boolean usesVanillaAI(LivingEntity mob) {
        return isPhasing(mob);
    }

    /** Back-compat alias while call sites migrate. */
    public static boolean phasesThroughBlocks(LivingEntity mob) {
        return isPhasing(mob);
    }

    /** Apply spawn-time settings specific to phasing minions (e.g. no gravity to prevent sinking). */
    public static void applySpawnSettings(LivingEntity mob) {
        if (!isPhasing(mob) || mob == null) return;
        try { mob.setGravity(false); } catch (Throwable ignored) {}
    }
}
