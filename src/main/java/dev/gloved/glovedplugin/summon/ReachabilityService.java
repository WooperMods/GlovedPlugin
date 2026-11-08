package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.phasing.PhasingMob;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Reachability checks for summon minions using Paper's native {@code Pathfinder}.
 *
 * <p>Movement is always driven by Paper's {@code Pathfinder}; reachability here is a
 * lightweight signal used for owner-reach caching and target pruning. Results are
 * intentionally optimistic when no reliable answer can be produced so that unknown
 * errors never cause minions to be teleported or targets to be discarded.</p>
 */
public class ReachabilityService {

    /** Do not run expensive searches for targets far outside the follow range. */
    private static final double MAX_SEARCH_DISTANCE_SQ = 32.0 * 32.0;
    /** Paper's single path search is capped by the mob's navigation range (~16 blocks). */
    private static final double PAPER_SEARCH_DISTANCE_SQ = 16.0 * 16.0;

    public ReachabilityService(GlovedPlugin plugin) {
        // Kept for parity with the rest of the summon package.
    }

    /** @deprecated use {@link PhasingMob#isPhasing(LivingEntity)} */
    @Deprecated(forRemoval = false)
    public static boolean phasesThroughBlocks(LivingEntity mob) {
        return PhasingMob.isPhasing(mob);
    }

    public boolean canReach(Mob mob, Location target) {
        if (mob == null || target == null) return false;
        try {
            if (mob.getLocation().distanceSquared(target) > MAX_SEARCH_DISTANCE_SQ) {
                return true;
            }
            if (PhasingMob.isPhasing(mob)) {
                return true;
            }
            return paperCanReach(mob, target);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Reachability check failed, allowing optimistically: " + t);
            return true;
        }
    }

    private boolean paperCanReach(Mob mob, Location target) {
        if (mob.getLocation().distanceSquared(target) > PAPER_SEARCH_DISTANCE_SQ) {
            // One navigation search cannot cover this distance; let movement and stuck detection decide.
            return true;
        }
        try {
            var pf = mob.getPathfinder();
            if (pf == null) return true;
            var result = pf.findPath(target);
            if (result == null) return false;
            return result.canReachFinalPoint();
        } catch (Exception e) {
            GlovedPlugin.logDebug("[Summon] Paper reachability check failed: " + e.getMessage());
            return true;
        }
    }

    public boolean canReachEntity(Mob mob, LivingEntity target) {
        if (target == null) return false;
        return canReach(mob, target.getLocation());
    }
}
