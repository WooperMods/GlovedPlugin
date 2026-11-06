package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.Location;

public class ReachabilityService {

    private final GlovedPlugin plugin;

    // Cached reflection handles for NpcApi
    private static Class<?> utilsClass;
    private static Class<?> navCapsClass;
    private static Class<?> builderClass;
    private static java.lang.reflect.Method findPathListMethod;
    private static java.lang.reflect.Method findPathBuilderMethod;
    private static java.lang.reflect.Method builderMethod;
    private static java.lang.reflect.Method capabilitiesMethod;
    private static java.lang.reflect.Method maxIterationsMethod;
    private static java.lang.reflect.Method allowDiagMethod;
    private static java.lang.reflect.Method navCapsOfMethod;
    private static java.lang.reflect.Method asLocationsMethod;
    private static boolean initialized = false;

    static {
        try {
            utilsClass = Class.forName("de.eisi05.npc.api.pathfinding.util.PathfindingUtils");
            navCapsClass = Class.forName("de.eisi05.npc.api.pathfinding.movement.NavCapabilities");
            builderClass = Class.forName("de.eisi05.npc.api.pathfinding.util.PathfindingUtils$PathfindingBuilder");
            findPathListMethod = utilsClass.getMethod("findPath", java.util.List.class, int.class, boolean.class, java.util.function.Consumer.class);
            findPathBuilderMethod = utilsClass.getMethod("findPath", builderClass);
            builderMethod = utilsClass.getMethod("builder", java.util.List.class);
            capabilitiesMethod = builderClass.getMethod("capabilities", navCapsClass);
            maxIterationsMethod = builderClass.getMethod("maxIterations", int.class);
            allowDiagMethod = builderClass.getMethod("allowDiagonalMovement", boolean.class);
            navCapsOfMethod = navCapsClass.getMethod("of", org.bukkit.entity.EntityType.class);
            // asLocations is defined on Path interface, we can retrieve via sample invocation later
            initialized = true;
        } catch (Throwable ignored) {
            // NpcApi not available
        }
    }

    public ReachabilityService(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks if a mob can reach a target location considering its capabilities.
     * Tries NpcApi first, falls back to Paper Pathfinder findPath.
     */
    public boolean canReach(Mob mob, Location target) {
        if (mob == null || target == null) return false;
        if (initialized) {
            try {
                java.util.List<Location> waypoints = java.util.List.of(mob.getLocation(), target);
                Object path = findPathListMethod.invoke(null, waypoints, 20000, true, null);
                if (path != null) {
                    // Get asLocations via reflection if not cached
                    if (asLocationsMethod == null) {
                        asLocationsMethod = path.getClass().getMethod("asLocations");
                    }
                    java.util.List<?> locations = (java.util.List<?>) asLocationsMethod.invoke(path);
                    return locations != null && !locations.isEmpty();
                }
                return false;
            } catch (Exception e) {
                GlovedPlugin.logDebug("[Summon] NpcApi reachability check failed: " + e.getMessage());
            }
        }
        // Fallback to Paper Pathfinder
        try {
            var pf = mob.getPathfinder();
            if (pf == null) return true;
            var result = pf.findPath(target);
            return result != null && result.canReachFinalPoint();
        } catch (Exception e) {
            return true;
        }
    }

    public Location getNextWaypoint(Mob mob, Location target) {
        if (mob == null || target == null) return target;
        if (initialized) {
            try {
                Object caps = navCapsOfMethod.invoke(null, mob.getType());
                Object builder = builderMethod.invoke(null, java.util.List.of(mob.getLocation(), target));
                builder = capabilitiesMethod.invoke(builder, caps);
                builder = maxIterationsMethod.invoke(builder, 20000);
                builder = allowDiagMethod.invoke(builder, true);
                Object path = findPathBuilderMethod.invoke(null, builder);
                if (path != null) {
                    if (asLocationsMethod == null) {
                        asLocationsMethod = path.getClass().getMethod("asLocations");
                    }
                    java.util.List<Location> locations = (java.util.List<Location>) asLocationsMethod.invoke(path);
                    if (locations != null && locations.size() > 1) {
                        return locations.get(1);
                    } else if (locations != null && !locations.isEmpty()) {
                        return locations.get(0);
                    }
                }
            } catch (Exception e) {
                GlovedPlugin.logDebug("[Summon] NpcApi next waypoint failed: " + e.getMessage());
            }
        }
        return target;
    }

    public boolean canReachEntity(Mob mob, LivingEntity target) {
        if (target == null) return false;
        return canReach(mob, target.getLocation());
    }
}
