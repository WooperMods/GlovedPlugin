package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.phasing.PhasingMob;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Reachability checks for summon minions.
 *
 * <p>NpcApi's pathfinder is the primary source of truth here because it knows about per-entity
 * navigation capabilities (flying, jumping, swimming, ...). Paper's navigator is only used as a
 * fallback when NpcApi is unavailable or fails unexpectedly. Movement itself is always driven by
 * Paper's {@code Pathfinder}; NpcApi is never used to move a minion.</p>
 */
public class ReachabilityService {

    /** Do not run expensive searches for targets far outside the follow range. */
    private static final double MAX_SEARCH_DISTANCE_SQ = 32.0 * 32.0;
    /** Bounded A* budget: enough for a short follow-range route, cheap enough to stay off the tick budget. */
    private static final int NPC_MAX_ITERATIONS = 6000;
    /** Paper's single path search is capped by the mob's navigation range (~16 blocks). */
    private static final double PAPER_SEARCH_DISTANCE_SQ = 16.0 * 16.0;

    // Cached reflection handles for NpcApi
    private static Class<?> utilsClass;
    private static Class<?> navCapsClass;
    private static Class<?> builderClass;
    private static java.lang.reflect.Method findPathBuilderMethod;
    private static java.lang.reflect.Method builderMethod;
    private static java.lang.reflect.Method capabilitiesMethod;
    private static java.lang.reflect.Method maxIterationsMethod;
    private static java.lang.reflect.Method allowDiagMethod;
    private static java.lang.reflect.Method navCapsOfMethod;
    private static java.lang.reflect.Method asLocationsMethod;
    private static volatile boolean initialized = false;
    private static volatile boolean npcApiBroken = false;

    static {
        try {
            utilsClass = Class.forName("de.eisi05.npc.api.pathfinding.util.PathfindingUtils");
            navCapsClass = Class.forName("de.eisi05.npc.api.pathfinding.movement.NavCapabilities");
            builderClass = Class.forName("de.eisi05.npc.api.pathfinding.util.PathfindingUtils$PathfindingBuilder");
            findPathBuilderMethod = utilsClass.getMethod("findPath", builderClass);
            builderMethod = utilsClass.getMethod("builder", java.util.List.class);
            capabilitiesMethod = builderClass.getMethod("capabilities", navCapsClass);
            maxIterationsMethod = builderClass.getMethod("maxIterations", int.class);
            allowDiagMethod = builderClass.getMethod("allowDiagonalMovement", boolean.class);
            navCapsOfMethod = navCapsClass.getMethod("of", org.bukkit.entity.EntityType.class);
            initialized = true;
        } catch (Throwable ignored) {
            // NpcApi not available; fall back to Paper only
            initialized = false;
        }
    }

    public ReachabilityService(GlovedPlugin plugin) {
        // Kept for parity with the rest of the summon package.
    }

    /**
     * Checks if a mob can reach a target location.
     *
     * <p>NpcApi decides first; Paper's navigator is the fallback. Results are intentionally
     * optimistic when no reliable answer can be produced so that unknown errors never cause
     * minions to be teleported or targets to be discarded.</p>
     */
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
            Boolean npcResult = npcCanReach(mob, target);
            if (npcResult != null) {
                return npcResult;
            }
            return paperCanReach(mob, target);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Reachability check failed, allowing optimistically: " + t);
            return true;
        }
    }

    /**
     * @return {@code true}/{@code false} when NpcApi produced a definitive answer,
     *         {@code null} when it is unavailable or errored and Paper should decide.
     */
    private static void markNpcApiBroken(Throwable t) {
        if (!npcApiBroken) {
            npcApiBroken = true;
            initialized = false;
            GlovedPlugin.logInfo("[Summon] NpcApi pathfinding disabled after fatal/linkage error: " + t
                    + " (falling back to Paper pathfinding; plugin reload may be required to restore NpcApi)");
        }
    }

    private static boolean isFatalNpcError(Throwable t) {
        return t instanceof LinkageError
                || t instanceof ExceptionInInitializerError
                || (t != null && t.getCause() instanceof LinkageError);
    }

    private Boolean npcCanReach(Mob mob, Location target) {
        if (!initialized || npcApiBroken) return null;
        try {
            Object caps = navCapsOfMethod.invoke(null, mob.getType());
            Object builder = builderMethod.invoke(null, java.util.List.of(mob.getLocation(), target));
            builder = capabilitiesMethod.invoke(builder, caps);
            builder = maxIterationsMethod.invoke(builder, NPC_MAX_ITERATIONS);
            builder = allowDiagMethod.invoke(builder, true);
            Object path = findPathBuilderMethod.invoke(null, builder);
            if (path == null) {
                return false;
            }
            if (asLocationsMethod == null) {
                asLocationsMethod = path.getClass().getMethod("asLocations");
            }
            Object value = asLocationsMethod.invoke(path);
            if (!(value instanceof java.util.List<?> locations)) {
                return null;
            }
            return !locations.isEmpty();
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (isFatalNpcError(cause) || isFatalNpcError(e)) {
                markNpcApiBroken(cause != null ? cause : e);
                return null;
            }
            if (cause != null && cause.getClass().getName().endsWith("PathfindingException")) {
                String reason = pathingResultName(cause);
                if (reason == null || reason.equals("ITERATIONS_EXCEEDED") || reason.equals("CANCELLED")) {
                    // Budget exhausted is NOT proof that no path exists; let Paper decide.
                    GlovedPlugin.logDebug("[Summon] NpcApi inconclusive (" + reason + "), deferring to Paper");
                    return null;
                }
                GlovedPlugin.logDebug("[Summon] NpcApi found no path (" + reason + ")");
                return false;
            }
            GlovedPlugin.logDebug("[Summon] NpcApi reachability errored: " + (cause == null ? e : cause));
            return null;
        } catch (Throwable t) {
            if (isFatalNpcError(t)) {
                markNpcApiBroken(t);
                return null;
            }
            GlovedPlugin.logDebug("[Summon] NpcApi reachability unavailable: " + t);
            return null;
        }
    }

    /** Reads {@code PathfindingException#getReason()} without a hard dependency on NpcApi at compile time. */
    private static String pathingResultName(Throwable exception) {
        try {
            Object reason = exception.getClass().getMethod("getReason").invoke(exception);
            return reason == null ? null : reason.toString();
        } catch (Throwable ignored) {
            return null;
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
