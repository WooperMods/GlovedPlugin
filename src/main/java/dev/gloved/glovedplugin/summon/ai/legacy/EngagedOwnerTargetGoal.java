package dev.gloved.glovedplugin.summon.ai.legacy;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.runtime.SummonInstanceTracker;

/**
 * Target goal injected into hostile mobs engaged with a player so they can
 * "vanilla'ly" choose between the player and that player's summoned minions.
 */
public class EngagedOwnerTargetGoal implements Goal<Mob> {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final Mob mob;
    private final UUID ownerId;
    private final GoalKey<Mob> key;

    public EngagedOwnerTargetGoal(GlovedPlugin plugin, SummonInstanceTracker tracker, Mob mob, UUID ownerId) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.mob = mob;
        this.ownerId = ownerId;
        this.key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "engaged_target"));
    }

    @Override
    public boolean shouldActivate() {
        if (mob == null || mob.isDead() || ownerId == null) return false;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline() || !owner.getWorld().equals(mob.getWorld())) return false;
        // Only active while owner has summons and mob is engaged context (tracked externally),
        // but keep cheap validity checks.
        return !tracker.getActiveMinions(ownerId).isEmpty() || mob.getTarget() instanceof Player p && p.getUniqueId().equals(ownerId);
    }

    @Override
    public boolean shouldStayActive() {
        return shouldActivate();
    }

    @Override
    public void start() {
        tick();
    }

    @Override
    public void stop() {
        // let vanilla/other goals decide; do not force-clear target here
    }

    @Override
    public void tick() {
        if (mob == null || mob.isDead()) return;
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline() || !owner.getWorld().equals(mob.getWorld())) return;

        LivingEntity best = pickTarget(owner);
        if (best != null) {
            LivingEntity current = mob.getTarget();
            if (current == null || current.isDead() || !current.getWorld().equals(mob.getWorld())
                    || !isValidChoice(owner, current)) {
                try { mob.setTarget(best); } catch (Throwable ignored) {}
            } else {
                // Occasionally retarget to allow vanilla-like switching between owner and minions
                if (mob.getTicksLived() % 40 == 0) {
                    try { mob.setTarget(best); } catch (Throwable ignored) {}
                }
            }
        } else {
            // Fall back to owner if valid
            if (isValidChoice(owner, owner)) {
                try { mob.setTarget(owner); } catch (Throwable ignored) {}
            }
        }
    }

    private LivingEntity pickTarget(Player owner) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        Location ml = mob.getLocation();
        // Consider owner
        if (isValidChoice(owner, owner) && owner.getWorld().equals(ml.getWorld())) {
            double d = ml.distanceSquared(owner.getLocation());
            best = owner;
            bestDist = d;
        }
        // Consider owner's summons
        List<LivingEntity> minions = tracker.getActiveMinions(ownerId);
        if (minions != null) {
            for (LivingEntity minion : minions) {
                if (!isValidChoice(owner, minion)) continue;
                if (!minion.getWorld().equals(ml.getWorld())) continue;
                double d = ml.distanceSquared(minion.getLocation());
                if (best == null || d < bestDist) {
                    best = minion;
                    bestDist = d;
                }
            }
        }
        return best;
    }

    private boolean isValidChoice(Player owner, LivingEntity target) {
        if (target == null || target.isDead()) return false;
        if (!target.getWorld().equals(mob.getWorld())) return false;
        if (target.getUniqueId().equals(mob.getUniqueId())) return false;
        // Use combat protection rules for owner-target checks; minions are valid targets for enemies
        if (target instanceof Player p && p.getUniqueId().equals(ownerId)) {
            // allow targeting owner unless somehow protected in a way that blocks engagement; generally valid
            return true;
        }
        if (tracker != null && tracker.isSummoned(target.getUniqueId())) {
            UUID to = tracker.getOwner(target.getUniqueId());
            return ownerId.equals(to);
        }
        return false;
    }

    @Override
    public GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.TARGET);
    }
}
