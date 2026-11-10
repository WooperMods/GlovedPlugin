package dev.gloved.glovedplugin.combat;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

/**
 * Shared target-protection rules mirroring the custom arc combat system
 * ({@code CombatListener}/{@code ArcHitbox}/{@code WeaponRegistry}).
 */
public final class CombatTargetRules {
    private CombatTargetRules() {}

    /**
     * Returns {@code true} when the target should be spared from owner/minion damage,
     * using the same protections as the arc: {@code spare_tamed}, {@code spare_named}
     * (named + persistent), and PvP toggle checks for player targets.
     */
    public static boolean isProtected(GlovedPlugin plugin, Player owner, LivingEntity target) {
        if (plugin == null || owner == null || target == null) return true;
        if (target.isDead() || target.getUniqueId().equals(owner.getUniqueId())) return true;

        WeaponRegistry registry = null;
        try { registry = plugin.getWeaponRegistry(); } catch (Throwable ignored) {}

        if (registry != null && registry.isSpareTamed() && target instanceof Tameable tameable && tameable.isTamed()) {
            return true;
        }
        if (registry != null && registry.isSpareNamed() && target.customName() != null && target.isPersistent()) {
            return true;
        }
        if (target instanceof Player targetPlayer) {
            try {
                if (plugin.isPvpDisabled(owner) || plugin.isPvpDisabled(targetPlayer)) {
                    return true;
                }
            } catch (Throwable ignored) {
                return true;
            }
            if (isWorldGuardPvpDenied(owner, targetPlayer, target.getLocation())) {
                return true;
            }
        }

        // Protect other players' summoned minions using the same PvP toggle and region rules.
        try {
            var tracker = plugin.getSummonTracker();
            if (tracker != null && tracker.isSummoned(target.getUniqueId())) {
                java.util.UUID targetOwnerId = tracker.getOwner(target.getUniqueId());
                if (targetOwnerId != null && !targetOwnerId.equals(owner.getUniqueId())) {
                    org.bukkit.entity.Player targetOwner = org.bukkit.Bukkit.getPlayer(targetOwnerId);
                    if (targetOwner == null || plugin.isPvpDisabled(owner) || plugin.isPvpDisabled(targetOwner)) {
                        return true;
                    }
                    if (isWorldGuardPvpDenied(owner, targetOwner, target.getLocation())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    private static boolean isWorldGuardPvpDenied(Player owner, Player other, org.bukkit.Location location) {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return false;
            return WorldGuardPvP.isPvpDenied(owner, other, location);
        } catch (Throwable t) {
            // Fail closed for PvP-related checks when integration errors.
            return true;
        }
    }
}
