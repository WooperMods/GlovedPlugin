package dev.gloved.glovedplugin.combat.targeting;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Isolated WorldGuard PvP checks. Only reference this class when WorldGuard is enabled
 * to avoid NoClassDefFoundError on servers without WorldGuard.
 */
final class WorldGuardPvP {
    private WorldGuardPvP() {}

    /**
     * Returns true if PvP is denied by WorldGuard for the given context at the location.
     * Checks from the perspective of the involved player(s) when available so membership/bypass
     * and region rules are respected.
     */
    static boolean isPvpDenied(Player actor, Player victim, Location location) {
        try {
            if (location == null || location.getWorld() == null) return false;
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionQuery query = container.createQuery();
            com.sk89q.worldedit.util.Location wgLoc = BukkitAdapter.adapt(location);

            // If either relevant player is explicitly denied PvP at this location, treat as non-PvP.
            if (actor != null) {
                LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(actor);
                if (!query.testState(wgLoc, lp, Flags.PVP)) return true;
            }
            if (victim != null) {
                LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(victim);
                if (!query.testState(wgLoc, lp, Flags.PVP)) return true;
            }
            if (actor == null && victim == null) {
                return !query.testState(wgLoc, (LocalPlayer) null, Flags.PVP);
            }
            return false;
        } catch (Throwable t) {
            // Fail closed for PvP if WG check errors unexpectedly.
            return true;
        }
    }
}
