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
            Class<?> navCaps = Class.forName("de.eisi05.npc.api.pathfinding.movement.NavCapabilities");
            // Build capabilities for mob type
            java.lang.reflect.Method ofMethod = navCaps.getMethod("of", EntityType.class);
            Object caps = ofMethod.invoke(null, mob.getType());
            // Simple check using Paper Pathfinder as fallback inside NpcApi? We'll just use findPath via utils
            // NpcApi PathfindingUtils.findPath expects waypoints list
            java.lang.reflect.Method findPath = utils.getMethod("findPath", java.util.List.class, int.class, boolean.class, java.util.function.Consumer.class);
            java.util.List<Location> waypoints = java.util.List.of(mob.getLocation(), target);
            Object path = findPath.invoke(null, waypoints, 20000, true, null);
            if (path == null) return false;
            // Path has isEmpty? Assume non-null means path found
            java.lang.reflect.Method isEmpty = path.getClass().getMethod("isEmpty");
            boolean empty = (boolean) isEmpty.invoke(path);
            return !empty;
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

    public boolean canReachEntity(Mob mob, LivingEntity target) {
        if (target == null) return false;
        return canReach(mob, target.getLocation());
    }
}
