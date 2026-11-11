package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.MinionAI.TargetIntent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
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
    private static final double MIN_CHASE_DIST_SQ = 2.25; // 1.5 blocks; stop pathfinding once in melee

    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final Mob mob;
    private final GoalKey<Mob> key;

    private Location lastNavTarget = null;
    private double lastNavSpeed = -1.0;
    private long lastNavTick = -1000L;
    private boolean lastNavWasOwner = false;

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
        if (target != null && !target.isDead() && target.getWorld().equals(mob.getWorld())) {
            // Chase our attack target so minions can close distance and hit back.
            return true;
        }
        // No valid target: follow the owner.
        UUID ownerId = tracker.getOwner(mob.getUniqueId());
        if (ownerId == null) return false;
        Player owner = Bukkit.getPlayer(ownerId);
        return owner != null && owner.isOnline() && owner.getWorld().equals(mob.getWorld());
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void start() {
        lastNavTarget = null;
        lastNavSpeed = -1.0;
        lastNavTick = -1000L;
        lastNavWasOwner = false;
    }

    @Override
    public void stop() {
        try {
            var pf = mob.getPathfinder();
            if (pf != null) pf.stopPathfinding();
        } catch (Exception ignored) {}
        lastNavTarget = null;
        lastNavSpeed = -1.0;
        lastNavTick = -1000L;
        lastNavWasOwner = false;
    }

    private void pathTo(com.destroystokyo.paper.entity.Pathfinder pf, Location dest, double speed, boolean ownerFollow) {
        if (pf == null || dest == null) return;
        long now = Bukkit.getCurrentTick();
        boolean hasPath = false;
        try { hasPath = pf.hasPath(); } catch (Exception ignored) {}
        boolean need = !hasPath || lastNavTarget == null || lastNavWasOwner != ownerFollow;
        if (!need && lastNavTarget.getWorld() != null && dest.getWorld() != null
                && lastNavTarget.getWorld().equals(dest.getWorld())) {
            double movedSq = lastNavTarget.distanceSquared(dest);
            // Repath if destination moved meaningfully, speed changed notably, or path is stale.
            if (movedSq > (ownerFollow ? 2.25 : 1.0) || Math.abs(speed - lastNavSpeed) > 0.05 || (now - lastNavTick) >= 20L) {
                need = true;
            }
        } else if (!need) {
            need = true;
        }
        if (!need) return;
        try {
            if (pf.moveTo(dest, speed)) {
                lastNavTarget = dest.clone();
                lastNavSpeed = speed;
                lastNavTick = now;
                lastNavWasOwner = ownerFollow;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void tick() {
        if (mob == null || mob.isDead()) return;
        UUID ownerId = tracker.getOwner(mob.getUniqueId());
        if (ownerId == null) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) return;

        try {
            var pf = mob.getPathfinder();
            if (pf == null) return;
            LivingEntity target = mob.getTarget();
            TargetIntent intent = readIntent(mob);
            double speed = moveSpeedForIntent(mob, intent);
            if (target != null && !target.isDead() && target.getWorld().equals(mob.getWorld())
                    && owner.getWorld().equals(mob.getWorld())) {
                // Bodyguard positioning: when peeling a threat off the owner, path to a point
                // between the owner and the threat so the minion interposes itself. Once in
                // melee range, stop and let MinionAI handle the attack (knockback pushes
                // the target away from the owner on every hit).
                double distSq = mob.getLocation().distanceSquared(target.getLocation());
                if (intent == TargetIntent.PEELING && distSq > MIN_CHASE_DIST_SQ) {
                    Location interpose = interposeLocation(mob, owner, target);
                    if (interpose != null) {
                        pathTo(pf, interpose, speed, false);
                        try { mob.lookAt(target); } catch (Throwable ignored) {}
                        return;
                    }
                }
                if (distSq <= MIN_CHASE_DIST_SQ) {
                    // In melee range: stop pathfinding, let MinionAI handle the attack.
                    try { pf.stopPathfinding(); } catch (Exception ignored) {}
                    lastNavTarget = null;
                } else {
                    pathTo(pf, target.getLocation(), speed, false);
                    try { mob.lookAt(target); } catch (Throwable ignored) {}
                }
                return;
            }
            // No target: follow owner
            if (!owner.getWorld().equals(mob.getWorld())) return;
            double distSqToOwner = mob.getLocation().distanceSquared(owner.getLocation());
            if (distSqToOwner <= IDLE_RADIUS_SQ) {
                try { pf.stopPathfinding(); } catch (Exception ignored) {}
                lastNavTarget = null;
                return;
            }
            pathTo(pf, owner.getLocation(), speed, true);
            try { mob.lookAt(owner); } catch (Throwable ignored) {}
        } catch (Exception ignored) {}
    }

    private static double desiredBaseBps(Mob mob) {
        try {
            NamespacedKey key = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_move_bps");
            Double stored = mob.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
            if (stored != null && Double.isFinite(stored)) {
                return Math.max(1.0, Math.min(14.0, stored));
            }
        } catch (Throwable ignored) {}
        try {
            var move = mob.getAttribute(Attribute.MOVEMENT_SPEED);
            if (move != null) return Math.max(1.0, Math.min(14.0, move.getValue() * 43.17));
        } catch (Exception ignored) {}
        return 5.0;
    }

    /** Read the minion's current targeting intent from its PDC (set by MinionAI). */
    private static TargetIntent readIntent(Mob mob) {
        try {
            NamespacedKey key = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_target_intent");
            String val = mob.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (val != null) return TargetIntent.valueOf(val);
        } catch (Throwable ignored) {}
        return TargetIntent.IDLE;
    }

    /** Speed multiplier for each targeting intent. */
    private static double speedMultiplier(TargetIntent intent) {
        return switch (intent) {
            case IDLE -> 1.0;       // normal follow speed
            case DEFENSIVE -> 1.25; // get back to owner faster when hurt
            case PEELING -> 1.4;    // close gap fast to protect owner
            case FINISHING -> 1.15; // secure the kill quickly
            case CHASING -> 1.0;    // normal pursuit speed
        };
    }

    /** Base speed with intent burst, dampened at higher speeds and capped to ~14 bps total. */
    private static double moveSpeedForIntent(Mob mob, TargetIntent intent) {
        double baseBps = desiredBaseBps(mob);
        double intentMult = speedMultiplier(intent);
        double maxBps = 14.0;
        // Diminishing returns for intent bursts: higher base speed => less effect from "other stuff".
        double bonus = Math.max(0.0, intentMult - 1.0);
        double effectiveness = Math.max(0.0, Math.min(1.0, (maxBps - baseBps) / maxBps));
        double desiredBps = baseBps * (1.0 + bonus * effectiveness);
        desiredBps = Math.max(1.0, Math.min(maxBps, desiredBps));

        double attr = 0.23;
        try {
            var move = mob.getAttribute(Attribute.MOVEMENT_SPEED);
            if (move != null && move.getValue() > 0.0) attr = move.getValue();
        } catch (Exception ignored) {}
        // Pathfinder speed 1.0 = move at entity's MOVEMENT_SPEED attribute (theoretical).
        // Empirical calibration: observed actual movement is significantly below attr*43.17 in practice.
        final double CALIBRATION = 6.0;
        double pf = (desiredBps / (attr * 43.17)) * CALIBRATION;
        if (!Double.isFinite(pf) || pf <= 0.0) pf = CALIBRATION;
        return Math.max(0.2, Math.min(12.0, pf));
    }

    /**
     * Compute a bodyguard interpose point: a location on the line between the owner and the
     * threat, closer to the owner, so the minion positions itself between them.
     * Returns null if the positions are degenerate.
     */
    private static Location interposeLocation(Mob mob, Player owner, LivingEntity threat) {
        try {
            Location oLoc = owner.getLocation();
            Location tLoc = threat.getLocation();
            // Vector from threat to owner
            double dx = oLoc.getX() - tLoc.getX();
            double dz = oLoc.getZ() - tLoc.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 1.0) return null;
            // Interpose point: 60% of the way from threat toward owner (closer to owner)
            double t = 0.6;
            double ix = tLoc.getX() + dx * t;
            double iz = tLoc.getZ() + dz * t;
            double iy = oLoc.getY();
            Location interpose = oLoc.getWorld().getBlockAt((int) ix, (int) iy, (int) iz).getLocation();
            interpose.setX(ix);
            interpose.setZ(iz);
            interpose.setY(iy);
            // Don't interpose too close to the threat (melee range) or too far from owner
            double d2ToThreat = interpose.distanceSquared(tLoc);
            double d2ToOwner = interpose.distanceSquared(oLoc);
            if (d2ToThreat < MIN_CHASE_DIST_SQ || d2ToOwner > 49.0) { // >7 blocks from owner
                return null;
            }
            return interpose;
        } catch (Throwable ignored) {}
        return null;
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
