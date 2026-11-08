package dev.gloved.glovedplugin.summon.phasing;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.summon.MinionAI;
import dev.gloved.glovedplugin.summon.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.SummonProfile;
import dev.gloved.glovedplugin.summon.SummonTargetCoordinator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla-AI control mode for phasing minions (e.g. Vex): keep vanilla goals/movement/attacks,
 * and only assign targets / enforce owner range. Damage credit is handled by {@link PhasingMinionDamage}
 * via {@code DamageProxy}.
 * Targeting uses the shared capacity-based coordinator (last damaged highest, distance-based, stable assignments).
 */
public final class PhasingMinionVanillaAI {
    private static final double EXTENDED_ATTACK_RANGE_BONUS_BLOCKS = 4.0;
    private static final long EXTENDED_ATTACK_COOLDOWN_TICKS = 10L;
    private static final Map<UUID, Long> LAST_EXTENDED_ATTACK_TICK = new ConcurrentHashMap<>();

    private PhasingMinionVanillaAI() {}

    public static void runTick(Mob mob, Player owner, SummonInstanceTracker tracker,
                               GlovedPlugin plugin, SummonTargetCoordinator coordinator) {
        if (owner == null || !owner.isOnline() || mob == null || mob.isDead()) {
            if (mob != null) LAST_EXTENDED_ATTACK_TICK.remove(mob.getUniqueId());
            return;
        }
        double distSqToOwner = mob.getLocation().distanceSquared(owner.getLocation());
        if (distSqToOwner > SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE) {
            MinionAI.teleportNearPlayer(owner, mob);
            return;
        }

        List<LivingEntity> candidates = new ArrayList<>();
        Set<UUID> recentDamagerIds = new HashSet<>();
        double scanDist = SummonProfile.FOLLOW_RANGE;
        for (Entity e : owner.getNearbyEntities(scanDist, scanDist, scanDist)) {
            if (!(e instanceof LivingEntity le) || !isValidTarget(owner, le, tracker, plugin)) continue;
            boolean targetsOwner = e instanceof Mob mobEnt
                    && mobEnt.getTarget() != null && mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId());
            boolean isHostile = le instanceof org.bukkit.entity.Monster
                    || le instanceof org.bukkit.entity.Slime
                    || le instanceof org.bukkit.entity.Ghast;
            if ((isHostile || targetsOwner) && !candidates.contains(le)) {
                candidates.add(le);
            }
        }
        for (UUID damagerId : MinionAI.getRecentDamagers(owner)) {
            recentDamagerIds.add(damagerId);
            Entity e = owner.getWorld().getEntity(damagerId);
            if (e instanceof LivingEntity le && isValidTarget(owner, le, tracker, plugin) && !candidates.contains(le)) {
                candidates.add(le);
            }
        }

        long now = Bukkit.getCurrentTick();
        LivingEntity chosen = null;
        if (!candidates.isEmpty() && coordinator != null) {
            chosen = coordinator.assign(owner, mob, candidates, recentDamagerIds, now);
        }
        if (chosen != null && (!isValidTarget(owner, chosen, tracker, plugin) || !candidates.contains(chosen))) {
            chosen = null;
        }
        if (chosen == null && mob.getTarget() instanceof LivingEntity cur
                && isValidTarget(owner, cur, tracker, plugin) && candidates.contains(cur)) {
            chosen = cur; // brief local fallback
        }

        try {
            if (chosen != null && (mob.getTarget() == null || !mob.getTarget().getUniqueId().equals(chosen.getUniqueId()))) {
                mob.setTarget(chosen);
            } else if (chosen == null && mob.getTarget() != null && !isValidTarget(owner, mob.getTarget(), tracker, plugin)) {
                mob.setTarget(null);
            }
        } catch (Exception ignored) {}

        LivingEntity attackTarget = mob.getTarget();
        if (attackTarget == null || !isValidTarget(owner, attackTarget, tracker, plugin)) return;
        tryExtendedRangeAttack(mob, owner, attackTarget, tracker, plugin);
    }

    private static void tryExtendedRangeAttack(Mob mob, Player owner, LivingEntity target,
                                               SummonInstanceTracker tracker, GlovedPlugin plugin) {
        if (!PhasingMob.usesVanillaAI(mob)) return;
        if (CombatTargetRules.isProtected(plugin, owner, target)) return;
        double distSq = mob.getLocation().distanceSquared(target.getLocation());
        double attackRangeSq = extendedAttackReachSq(mob, target);
        if (distSq > attackRangeSq) return;
        long now = Bukkit.getCurrentTick();
        long last = LAST_EXTENDED_ATTACK_TICK.getOrDefault(mob.getUniqueId(), Long.MIN_VALUE / 2);
        if (now - last < EXTENDED_ATTACK_COOLDOWN_TICKS) return;
        LAST_EXTENDED_ATTACK_TICK.put(mob.getUniqueId(), now);
        PhasingMinionDamage.applyOwnerCreditedScaledDamage(owner, mob, target, tracker, plugin);
        try { mob.lookAt(target); } catch (Throwable ignored) {}
    }

    private static double extendedAttackReachSq(LivingEntity mob, LivingEntity target) {
        double width = mob.getWidth();
        double targetWidth = target.getWidth();
        double vanillaReach = Math.sqrt((width * 2.0) * (width * 2.0) + targetWidth);
        double reach = vanillaReach + EXTENDED_ATTACK_RANGE_BONUS_BLOCKS;
        return reach * reach;
    }

    private static boolean isValidTarget(Player owner, LivingEntity le, SummonInstanceTracker tracker, GlovedPlugin plugin) {
        if (owner == null || le == null) return false;
        if (le == owner || le.isDead()) return false;
        if (tracker != null && tracker.isSummoned(le.getUniqueId())) return false;
        if (!owner.getWorld().equals(le.getWorld())) return false;
        if (CombatTargetRules.isProtected(plugin, owner, le)) return false;
        return le.getLocation().distanceSquared(owner.getLocation()) <= SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE;
    }
}
