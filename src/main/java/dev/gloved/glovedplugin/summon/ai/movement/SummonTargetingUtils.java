package dev.gloved.glovedplugin.summon.ai.movement;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;

/**
 * Target scoring utilities for NMS/goal-based summon AI (HP-aware priority, owner-defense and swarm heuristics).
 */
public final class SummonTargetingUtils {
    private SummonTargetingUtils() {}

    public static final double TARGET_SWITCH_THRESHOLD = 1.25;
    public static final long ASSIGNMENT_TTL_MS = 10_000L;
    private static final double MINION_LOW_HP_PCT = 0.30;
    private static final double OWNER_LOW_HP_PCT = 0.30;
    private static final double BODYGUARD_RADIUS_SQ = 6.0 * 6.0;

    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Integer>> targetAssignments = new ConcurrentHashMap<>();

    public static final class ScoredTarget {
        public final LivingEntity entity;
        public final UUID id;
        public final double score;

        public ScoredTarget(LivingEntity entity, double score) {
            this.entity = entity;
            this.id = entity.getUniqueId();
            this.score = score;
        }
    }

    public static double scoreTarget(Player owner, LivingEntity summon, LivingEntity target, Set<UUID> recentDamagerIds) {
        if (owner == null || summon == null || target == null) return Double.NEGATIVE_INFINITY;
        double score = 0.0;
        UUID tid = target.getUniqueId();
        Location tLoc = target.getLocation();
        Location mLoc = summon.getLocation();
        Location oLoc = owner.getLocation();

        double minionHpPct = safeHpPct(summon);
        double ownerHpPct = safeHpPct(owner);
        double targetHpPct = safeHpPct(target);
        boolean ownerLowHp = ownerHpPct < OWNER_LOW_HP_PCT;

        double selfWeight = 1.0 - minionHpPct;
        double ownerWeight = minionHpPct;

        double d2Owner = safeDistSq(tLoc, oLoc);
        double d2Minion = safeDistSq(mLoc, tLoc);

        if (target instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
            UUID mobTarget = mobEnt.getTarget().getUniqueId();
            if (mobTarget.equals(summon.getUniqueId())) {
                score += 15_000_000.0;
            } else if (mobTarget.equals(owner.getUniqueId())) {
                score += 10_000_000.0 * ownerWeight;
                if (ownerLowHp) score += 3_000_000.0 * ownerWeight;
            }
        }

        double closeToMinionBonus = Math.max(0.0, 8.0 - Math.sqrt(Math.max(0.0, d2Minion))) / 8.0;
        score += closeToMinionBonus * 12_000_000.0;

        if (SummonCombatService.getRecentDamagers(summon).contains(tid)) {
            score += 5_000_000.0 * (0.5 + selfWeight);
        }

        if (recentDamagerIds != null && recentDamagerIds.contains(tid)) {
            score += 2_000_000.0 * ownerWeight;
        }

        if (d2Owner <= BODYGUARD_RADIUS_SQ) {
            double ownerDanger = (1.0 - d2Owner / BODYGUARD_RADIUS_SQ);
            score += ownerDanger * 2_000_000.0 * ownerWeight;
        }

        if (targetHpPct < 0.5) {
            score += (1.0 - targetHpPct) * 300_000.0;
        }

        int assignmentCount = getTargetAssignmentCount(owner.getUniqueId(), tid);
        if (assignmentCount >= 1) {
            score -= assignmentCount * 300_000.0;
        }

        return score;
    }

    public static LivingEntity selectBestTarget(Player owner, LivingEntity summon, List<LivingEntity> candidates,
                                                Set<UUID> recentDamagerIds, LivingEntity lastTarget) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<ScoredTarget> scored = new ArrayList<>(candidates.size());
        for (LivingEntity le : candidates) {
            if (le == null || le.isDead() || !le.isValid()) continue;
            scored.add(new ScoredTarget(le, scoreTarget(owner, summon, le, recentDamagerIds)));
        }
        if (scored.isEmpty()) return null;
        scored.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            return c != 0 ? c : a.id.compareTo(b.id);
        });
        LivingEntity best = scored.get(0).entity;
        GlovedPlugin plugin = GlovedPlugin.getPlugin(GlovedPlugin.class);
        if (lastTarget != null && !lastTarget.isDead() && lastTarget.isValid()
                && !CombatTargetRules.isProtected(plugin, owner, lastTarget)) {
            Double currentScore = null;
            for (ScoredTarget st : scored) {
                if (st.id.equals(lastTarget.getUniqueId())) {
                    currentScore = st.score;
                    break;
                }
            }
            if (currentScore != null && scored.get(0).score <= currentScore * TARGET_SWITCH_THRESHOLD) {
                return lastTarget;
            }
        }
        return best;
    }

    private static double safeHpPct(LivingEntity le) {
        try {
            double max = le.getMaxHealth();
            if (max <= 0.0) return 1.0;
            return Math.max(0.0, Math.min(1.0, le.getHealth() / max));
        } catch (Throwable t) {
            return 1.0;
        }
    }

    private static double safeDistSq(Location a, Location b) {
        try {
            if (a == null || b == null || a.getWorld() != b.getWorld()) return Double.MAX_VALUE / 4;
            return a.distanceSquared(b);
        } catch (Throwable t) {
            return Double.MAX_VALUE / 4;
        }
    }

    public static int getTargetAssignmentCount(UUID ownerId, UUID targetId) {
        var map = targetAssignments.get(ownerId);
        if (map == null) return 0;
        Integer v = map.get(targetId);
        return v == null ? 0 : v;
    }

    public static void recordTargetAssignment(UUID ownerId, LivingEntity target) {
        if (ownerId == null || target == null) return;
        targetAssignments.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>())
                .merge(target.getUniqueId(), 1, Integer::sum);
    }

    public static void clearTargetAssignment(UUID ownerId, UUID targetId) {
        if (ownerId == null || targetId == null) return;
        var map = targetAssignments.get(ownerId);
        if (map != null) {
            map.remove(targetId);
            if (map.isEmpty()) targetAssignments.remove(ownerId);
        }
    }
}
