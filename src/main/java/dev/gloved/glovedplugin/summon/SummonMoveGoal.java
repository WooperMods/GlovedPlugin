package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CombatPets-style follow/movement goal for legacy (non-NMS) Bukkit summons.
 * - Teleports to owner if different world or farther than 32 blocks.
 * - Follows when beyond reach (or occasional random tick).
 * - Stops pathing when targetting an enemy / close to owner.
 */
public class SummonMoveGoal implements Goal<Mob> {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final Mob mob;
    private final GoalKey<Mob> key;

    public SummonMoveGoal(GlovedPlugin plugin, SummonInstanceTracker tracker, Mob mob) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.mob = mob;
        this.key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "summon_move"));
    }

    private Player resolveOwner() {
        UUID ownerId = tracker.getOwner(mob.getUniqueId());
        if (ownerId == null) return null;
        Player owner = Bukkit.getPlayer(ownerId);
        return (owner != null && owner.isOnline()) ? owner : null;
    }

    private double getReachSq(Player owner) {
        double bw = 0.6;
        double ow = 0.6;
        try { bw = mob.getWidth(); } catch (Throwable ignored) {}
        try { ow = owner.getWidth(); } catch (Throwable ignored) {}
        return (Math.pow(bw * 2.0, 2.0) + ow) * 1.5;
    }

    private boolean isTargettedToEnemy(Player owner) {
        LivingEntity target = mob.getTarget();
        if (target == null) return false;
        if (target.isDead() || !target.isValid()) {
            try { mob.setTarget(null); } catch (Throwable ignored) {}
            return false;
        }
        return !target.getUniqueId().equals(owner.getUniqueId());
    }

    private void teleportToOwner(Player owner) {
        try { mob.setTarget(null); } catch (Throwable ignored) {}
        try { mob.teleport(owner.getLocation()); } catch (Throwable ignored) {}
        try { mob.getPathfinder().stopPathfinding(); } catch (Throwable ignored) {}
    }

    @Override
    public boolean shouldActivate() {
        if (mob == null || mob.isDead() || !tracker.isSummoned(mob.getUniqueId())) return false;
        Player owner = resolveOwner();
        if (owner == null) return false;
        if (isTargettedToEnemy(owner)) return false;
        if (mob.getPassengers().contains(owner)) return false;

        if (!mob.getWorld().equals(owner.getWorld()) || mob.getLocation().distanceSquared(owner.getLocation()) > 1024.0D) {
            teleportToOwner(owner);
            return false;
        }

        double distSq = mob.getLocation().distanceSquared(owner.getLocation());
        double reach = getReachSq(owner);
        return distSq > reach || ThreadLocalRandom.current().nextFloat() < 0.05F;
    }

    @Override
    public boolean shouldStayActive() {
        if (mob == null || mob.isDead() || !tracker.isSummoned(mob.getUniqueId())) return false;
        Player owner = resolveOwner();
        if (owner == null) return false;
        if (isTargettedToEnemy(owner)) return false;
        if (!mob.getWorld().equals(owner.getWorld()) || mob.getLocation().distanceSquared(owner.getLocation()) > 1024.0D) {
            teleportToOwner(owner);
            return false;
        }
        try { return mob.getPathfinder().hasPath(); } catch (Throwable ignored) { return true; }
    }

    @Override
    public void start() { }

    @Override
    public void stop() {
        try {
            // Fix for movement jitter when trying to reach owner (CombatPets behavior)
            mob.getPathfinder().moveTo(mob.getLocation(), 1.0D);
            mob.getPathfinder().stopPathfinding();
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        Player owner = resolveOwner();
        if (owner == null || mob == null || mob.isDead()) return;
        if (isTargettedToEnemy(owner)) return;
        if (!mob.getWorld().equals(owner.getWorld())) {
            teleportToOwner(owner);
            return;
        }
        if (mob.getLocation().distanceSquared(owner.getLocation()) > 1024.0D) {
            teleportToOwner(owner);
            return;
        }

        double speedModifier = 1.0D;
        try { if (mob.isInWater()) speedModifier = 2.0D; } catch (Throwable ignored) {}

        Location targetLoc = owner.getLocation().clone();
        double distSq = mob.getLocation().distanceSquared(owner.getLocation());
        double reach = getReachSq(owner);
        if (distSq <= reach) {
            double xOff = ThreadLocalRandom.current().nextDouble(0.25D);
            targetLoc = new Location(owner.getWorld(), owner.getLocation().getBlockX() + xOff,
                    owner.getLocation().getY(), owner.getLocation().getBlockZ() + xOff);
        }

        try {
            mob.getPathfinder().moveTo(targetLoc, speedModifier);
        } catch (Throwable ignored) {}
        try { mob.lookAt(owner); } catch (Throwable ignored) {}
    }

    @Override
    public GoalKey<Mob> getKey() { return key; }

    @Override
    public EnumSet<GoalType> getTypes() { return EnumSet.of(GoalType.MOVE, GoalType.LOOK); }
}
