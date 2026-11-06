package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.Location;

public class ReachabilityService {

    private final GlovedPlugin plugin;

    public ReachabilityService(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Checks if a mob can reach a target location considering its capabilities.
     * Tries NpcApi first, falls back to Paper Pathfinder findPath.
     */
    public boolean canReach(Mob mob, Location target) {
        if (mob == null || target == null) return false;
        // Try NpcApi pathfinding if available
        try {
            Class<?> utils = Class.forName("de.eisi05.npc.api.pathfinding.util.PathfindingUtils");
            java.lang.reflect.Method findPath = utils.getMethod("findPath", java.util.List.class, int.class, boolean.class, java.util.function.Consumer.class);
            java.util.List<Location> waypoints = java.util.List.of(mob.getLocation(), target);
            Object path = findPath.invoke(null, waypoints, 20000, true, null);
            if (path == null) return false;
            // Check if path has locations
            java.lang.reflect.Method asLocations = path.getClass().getMethod("asLocations");
            java.util.List<?> locations = (java.util.List<?>) asLocations.invoke(path);
            return locations != null && !locations.isEmpty();
        } catch (ClassNotFoundException e) {
            // NpcApi not present, fallback
        } catch (Exception e) {
            GlovedPlugin.logDebug("[Summon] NpcApi reachability check failed: " + e.getMessage());
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
        try {
            Class<?> utils = Class.forName("de.eisi05.npc.api.pathfinding.util.PathfindingUtils");
            Class<?> navCaps = Class.forName("de.eisi05.npc.api.pathfinding.movement.NavCapabilities");
            java.lang.reflect.Method ofMethod = navCaps.getMethod("of", EntityType.class);
            Object caps = ofMethod.invoke(null, mob.getType());
            // Use builder with capabilities for better accuracy
            Class<?> builderClass = Class.forName("de.eisi05.npc.api.pathfinding.util.PathfindingUtils$PathfindingBuilder");
            java.lang.reflect.Method builderMethod = utils.getMethod("builder", java.util.List.class);
            Object builder = builderMethod.invoke(null, java.util.List.of(mob.getLocation(), target));
            java.lang.reflect.Method capabilitiesMethod = builderClass.getMethod("capabilities", navCaps);
            builder = capabilitiesMethod.invoke(builder, caps);
            java.lang.reflect.Method maxIterMethod = builderClass.getMethod("maxIterations", int.class);
            builder = maxIterMethod.invoke(builder, 20000);
            java.lang.reflect.Method allowDiagMethod = builderClass.getMethod("allowDiagonalMovement", boolean.class);
            builder = allowDiagMethod.invoke(builder, true);
            java.lang.reflect.Method findPath = utils.getMethod("findPath", builderClass);
            Object path = findPath.invoke(null, builder);
            if (path != null) {
                java.lang.reflect.Method asLocations = path.getClass().getMethod("asLocations");
                java.util.List<Location> locations = (java.util.List<Location>) asLocations.invoke(path);
                if (locations != null && locations.size() > 1) {
                    // Return second node to avoid zero movement
                    return locations.get(1);
                } else if (locations != null && !locations.isEmpty()) {
                    return locations.get(0);
                }
            }
        } catch (Exception e) {
            GlovedPlugin.logDebug("[Summon] NpcApi next waypoint failed: " + e.getMessage());
        }
        // Fallback to target
        return target;
    }

    public boolean canReachEntity(Mob mob, LivingEntity target) {
        if (target == null) return false;
        return canReach(mob, target.getLocation());
    }
}
