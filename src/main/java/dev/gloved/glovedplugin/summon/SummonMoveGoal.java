package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.phasing.PhasingMob;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Minimal movement-only goal for summoned minions. All other vanilla goals are removed to
 * prevent mob-specific quirks (e.g. bee pollination). Movement uses Paper's native pathfinder
 * for obstacle-aware navigation where possible; phasing mobs (e.g. Vex) use direct steering
 * because they can pass through blocks. Targeting and combat remain plugin-owned.
 */
public class SummonMoveGoal implements Goal<Mob> {
    private static final double IDLE_RADIUS = 4.0;
    private static final double IDLE_RADIUS_SQ = IDLE_RADIUS * IDLE_RADIUS;

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

    @Override
    public boolean shouldActivate() {
        return mob != null && !mob.isDead() && tracker.isSummoned(mob.getUniqueId());
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
        try {
            var pf = mob.getPathfinder();
            if (pf != null) pf.stopPathfinding();
        } catch (Exception ignored) {}
    }

    @Override
    public void tick() {
        if (mob == null || mob.isDead()) return;
        UUID ownerId = tracker.getOwner(mob.getUniqueId());
        if (ownerId == null) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline() || !owner.getWorld().equals(mob.getWorld())) return;

        LivingEntity target = mob.getTarget();
        boolean phasing = PhasingMob.isPhasing(mob);
        boolean flying = hasFlyingSpeed(mob);
        try {
            if (target != null && !target.isDead() && target.getWorld().equals(mob.getWorld())) {
                moveToward(target.getLocation(), mobSpeed(mob), phasing, flying);
                try {
                    mob.lookAt(target);
                } catch (Throwable ignored) {}
                return;
            }

            double distSqToOwner = mob.getLocation().distanceSquared(owner.getLocation());
            if (distSqToOwner <= IDLE_RADIUS_SQ) {
                try {
                    var pf = mob.getPathfinder();
                    if (pf != null) pf.stopPathfinding();
                } catch (Exception ignored) {}
                if (flying && !phasing) {
                    mob.setVelocity(mob.getVelocity().multiply(0));
                }
                return;
            }
            moveToward(owner.getLocation(), mobSpeed(mob) * 0.9, phasing, flying);
            try {
                mob.lookAt(owner);
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
    }

    private void moveToward(Location goal, double speed, boolean phasing, boolean flying) {
        if (goal == null) return;
        if (mob.getLocation().distanceSquared(goal) <= 1.0) {
            try {
                var pf = mob.getPathfinder();
                if (pf != null) pf.stopPathfinding();
            } catch (Exception ignored) {}
            return;
        }
        MinionAI.faceTowards(mob, goal);
        // Phasing mobs (Vex) can move through blocks; direct steering is reliable without vanilla goals.
        if (phasing) {
            MinionAI.moveMobTowards(mob, goal, speed);
            return;
        }
        boolean moved = false;
        try {
            var pf = mob.getPathfinder();
            if (pf != null) {
                moved = pf.moveTo(goal, speed);
            }
        } catch (Exception ignored) {}
        // Flying mobs with stripped goals may fail to path reliably; fall back to direct steering.
        if (!moved && flying) {
            MinionAI.moveMobTowards(mob, goal, speed);
        }
    }

    private static boolean hasFlyingSpeed(Mob mob) {
        try {
            return mob.getAttribute(Attribute.FLYING_SPEED) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static double mobSpeed(Mob mob) {
        try {
            var fly = mob.getAttribute(Attribute.FLYING_SPEED);
            if (fly != null) return Math.max(0.6, fly.getValue() * 10.0);
        } catch (Exception ignored) {}
        try {
            var move = mob.getAttribute(Attribute.MOVEMENT_SPEED);
            if (move != null) return Math.max(0.2, move.getValue());
        } catch (Exception ignored) {}
        return 1.0;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE, GoalType.LOOK);
    }
}
