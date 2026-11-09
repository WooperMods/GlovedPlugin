package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import dev.gloved.glovedplugin.GlovedPlugin;
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
 * Minimal navigation-intent goal for summoned minions. Uses Paper's native pathfinder
 * ({@code Pathfinder.moveTo}) only — no direct velocity steering — so vanilla navigation
 * controls actual movement. Target selection/damage remain plugin-owned.
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
        if (mob == null || mob.isDead() || !tracker.isSummoned(mob.getUniqueId())) return false;
        LivingEntity target = mob.getTarget();
        return target == null || target.isDead() || !target.getWorld().equals(mob.getWorld());
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

        try {
            var pf = mob.getPathfinder();
            if (pf == null) return;
            double distSqToOwner = mob.getLocation().distanceSquared(owner.getLocation());
            if (distSqToOwner <= IDLE_RADIUS_SQ) {
                pf.stopPathfinding();
                return;
            }
            pf.moveTo(owner.getLocation(), mobSpeed(mob) * 0.9);
            try {
                mob.lookAt(owner);
            } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
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
