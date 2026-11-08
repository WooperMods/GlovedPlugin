package dev.gloved.glovedplugin.summon;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Strategic, capacity-based summon targeting (count-aware, no per-minion speed weighting).
 * - Priorities: last damaged/recent damagers highest, then distance-based (distance to minion).
 * - Minion-count aware: min coverage + focus-fire cap scale with squad size.
 * - No owner look targeting, no "very close enemies" bucket, no protection emergency.
 */
public class SummonTargetCoordinator {
    private static final long REVIEW_INTERVAL_TICKS = 60L; // ~3s
    private static final double FOCUS_FIRE_SHARE = 0.40;   // top target up to 40% (hard-capped)

    private final SummonInstanceTracker tracker;
    private final Map<UUID, OwnerState> states = new ConcurrentHashMap<>();

    private static final class OwnerState {
        final Map<UUID, UUID> assignment = new HashMap<>(); // minionId -> targetId
        long lastReviewTick = Long.MIN_VALUE / 4;
    }

    private static final class ScoredTarget {
        final LivingEntity entity;
        final UUID id;
        final double score;

        ScoredTarget(LivingEntity entity, double score) {
            this.entity = entity;
            this.id = entity.getUniqueId();
            this.score = score;
        }
    }

    public SummonTargetCoordinator(SummonInstanceTracker tracker) {
        this.tracker = tracker;
    }

    public LivingEntity assign(Player owner, LivingEntity minion, List<LivingEntity> candidates,
                               Set<UUID> recentDamagerIds, long nowTick) {
        return assign(owner, minion, candidates, recentDamagerIds, nowTick, null);
    }

    public LivingEntity assign(Player owner, LivingEntity minion, List<LivingEntity> candidates,
                               Set<UUID> recentDamagerIds, long nowTick,
                               Predicate<UUID> isUnreachableForMinion) {
        if (owner == null || minion == null) return null;
        OwnerState state = states.computeIfAbsent(owner.getUniqueId(), k -> new OwnerState());

        Map<UUID, LivingEntity> candidateById = new LinkedHashMap<>();
        List<ScoredTarget> scored = new ArrayList<>();
        for (LivingEntity le : candidates) {
            if (le == null || le.isDead()) continue;
            if (isUnreachableForMinion != null && isUnreachableForMinion.test(le.getUniqueId())) continue;
            candidateById.put(le.getUniqueId(), le);
            scored.add(new ScoredTarget(le, scoreTarget(minion, le, recentDamagerIds)));
        }
        scored.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            if (c != 0) return c;
            return a.id.compareTo(b.id);
        });
        List<UUID> targetOrder = new ArrayList<>();
        for (ScoredTarget st : scored) targetOrder.add(st.id);

        synchronized (state) {
            prune(state, owner.getUniqueId(), candidateById);

            UUID minionId = minion.getUniqueId();
            UUID current = state.assignment.get(minionId);
            if (current != null) {
                if (candidateById.containsKey(current)
                        && (isUnreachableForMinion == null || !isUnreachableForMinion.test(current))) {
                    return candidateById.get(current); // stable commitment
                }
                state.assignment.remove(minionId);
            }

            boolean dueReview = nowTick - state.lastReviewTick >= REVIEW_INTERVAL_TICKS;
            if (dueReview || state.assignment.isEmpty()) {
                rebalance(state, owner.getUniqueId(), targetOrder, candidateById);
                state.lastReviewTick = nowTick;
            }

            current = state.assignment.get(minionId);
            if (current != null && candidateById.containsKey(current)) {
                return candidateById.get(current);
            }
            LivingEntity picked = pickBestAvailable(state, owner.getUniqueId(), minionId, minion, targetOrder, candidateById, recentDamagerIds);
            if (picked != null) {
                state.assignment.put(minionId, picked.getUniqueId());
                return picked;
            }
            return null;
        }
    }

    private double scoreTarget(LivingEntity minion, LivingEntity target, Set<UUID> recentDamagerIds) {
        double score = 0.0;
        if (recentDamagerIds != null && recentDamagerIds.contains(target.getUniqueId())) {
            score += 1_000_000.0; // last damaged dominates
        }
        // Secondary distance-based term (distance stays meaningful but below last-damaged)
        try {
            Location ml = minion.getLocation();
            Location tl = target.getLocation();
            if (ml.getWorld() != null && ml.getWorld().equals(tl.getWorld())) {
                double d2 = ml.distanceSquared(tl);
                if (d2 >= 0) score += Math.max(0.0, 10000.0 - Math.min(d2, 10000.0)) * 0.001;
            }
        } catch (Exception ignored) {}
        return score;
    }

    private void prune(OwnerState state, UUID ownerId, Map<UUID, LivingEntity> candidateById) {
        List<LivingEntity> active = tracker.getActiveMinions(ownerId);
        Set<UUID> activeIds = new HashSet<>();
        for (LivingEntity le : active) activeIds.add(le.getUniqueId());
        state.assignment.keySet().removeIf(mid -> !activeIds.contains(mid));
        state.assignment.entrySet().removeIf(e -> !candidateById.containsKey(e.getValue()));
    }

    private void rebalance(OwnerState state, UUID ownerId, List<UUID> targetOrder,
                           Map<UUID, LivingEntity> candidateById) {
        if (targetOrder.isEmpty()) {
            state.assignment.clear();
            return;
        }
        List<LivingEntity> minions = tracker.getActiveMinions(ownerId);
        minions.sort(Comparator.comparing(le -> le.getUniqueId().toString()));
        int n = minions.size();
        int m = targetOrder.size();
        if (n == 0) return;

        Map<UUID, Integer> capacity = computeCapacities(n, m, targetOrder);
        Map<UUID, Integer> load = new HashMap<>();
        for (UUID tid : targetOrder) load.put(tid, 0);
        for (UUID tid : state.assignment.values()) if (load.containsKey(tid)) load.put(tid, load.get(tid) + 1);

        // Free over-capacity (remove last sorted minion ID first for stability)
        for (UUID tid : targetOrder) {
            int cap = capacity.getOrDefault(tid, 0);
            while (load.getOrDefault(tid, 0) > cap) {
                UUID toFree = null;
                for (Map.Entry<UUID, UUID> e : state.assignment.entrySet()) {
                    if (tid.equals(e.getValue())) {
                        if (toFree == null || e.getKey().compareTo(toFree) > 0) toFree = e.getKey();
                    }
                }
                if (toFree == null) break;
                state.assignment.remove(toFree);
                load.put(tid, load.getOrDefault(tid, 0) - 1);
            }
        }

        // Assignment of unassigned minions: highest-priority under-capacity target with lowest distance.
        List<LivingEntity> unassigned = new ArrayList<>();
        Set<UUID> assigned = new HashSet<>(state.assignment.keySet());
        for (LivingEntity minion : minions) {
            if (!assigned.contains(minion.getUniqueId())) unassigned.add(minion);
        }
        unassigned.sort(Comparator.comparing(le -> le.getUniqueId().toString()));

        for (LivingEntity minion : unassigned) {
            UUID best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (UUID tid : targetOrder) {
                if (load.getOrDefault(tid, 0) >= capacity.getOrDefault(tid, Integer.MAX_VALUE)) continue;
                LivingEntity target = candidateById.get(tid);
                if (target == null) continue;
                double distSq = distanceSq(minion, target);
                if (distSq < bestDistSq) { bestDistSq = distSq; best = tid; }
            }
            if (best == null) {
                for (UUID tid : targetOrder) {
                    if (candidateById.containsKey(tid)) { best = tid; break; }
                }
            }
            if (best == null) best = targetOrder.get(0);
            state.assignment.put(minion.getUniqueId(), best);
            load.put(best, load.getOrDefault(best, 0) + 1);
        }
    }

    private Map<UUID, Integer> computeCapacities(int n, int m, List<UUID> targetOrder) {
        Map<UUID, Integer> cap = new LinkedHashMap<>();
        if (m <= 0) return cap;
        if (m == 1) { cap.put(targetOrder.get(0), n); return cap; }

        int[] caps = new int[m];
        int remaining = n;
        if (n >= m) {
            Arrays.fill(caps, 1); // minimum coverage
            remaining = n - m;
        }
        int focusCap = Math.max(2, (int) Math.ceil(n * FOCUS_FIRE_SHARE));
        focusCap = Math.min(focusCap, Math.max(1, n - (m - 1))); // leave room for others
        int softMax = (int) Math.ceil((double) n / m) + 1;

        for (int i = 0; i < m && remaining > 0; i++) {
            int maxForI = (i == 0) ? Math.max(softMax, focusCap) : softMax;
            int give = Math.min(remaining, maxForI - caps[i]);
            if (give > 0) { caps[i] += give; remaining -= give; }
        }
        for (int i = 0; i < m && remaining > 0; i++) { caps[i]++; remaining--; }

        for (int i = 0; i < m; i++) cap.put(targetOrder.get(i), caps[i]);
        int total = 0; for (int v : caps) total += v;
        if (total < n && m > 0) cap.put(targetOrder.get(0), cap.get(targetOrder.get(0)) + (n - total));
        return cap;
    }

    private LivingEntity pickBestAvailable(OwnerState state, UUID ownerId, UUID minionId, LivingEntity minion,
                                           List<UUID> targetOrder, Map<UUID, LivingEntity> candidateById,
                                           Set<UUID> recentDamagerIds) {
        Map<UUID, Integer> capacity = computeCapacities(
                Math.max(1, tracker.getActiveMinions(ownerId).size()), targetOrder.size(), targetOrder);
        Map<UUID, Integer> load = new HashMap<>();
        for (UUID tid : targetOrder) load.put(tid, 0);
        for (Map.Entry<UUID, UUID> e : state.assignment.entrySet()) {
            if (!e.getKey().equals(minionId) && load.containsKey(e.getValue())) {
                load.put(e.getValue(), load.get(e.getValue()) + 1);
            }
        }
        double bestDistSq = Double.MAX_VALUE;
        LivingEntity best = null;
        for (UUID tid : targetOrder) {
            if (load.getOrDefault(tid, 0) >= capacity.getOrDefault(tid, Integer.MAX_VALUE)) continue;
            LivingEntity target = candidateById.get(tid);
            if (target == null) continue;
            double distSq = distanceSq(minion, target);
            if (distSq < bestDistSq) { bestDistSq = distSq; best = target; }
        }
        if (best == null) {
            for (UUID tid : targetOrder) {
                LivingEntity target = candidateById.get(tid);
                if (target != null) { best = target; break; }
            }
        }
        return best;
    }

    private static double distanceSq(LivingEntity minion, LivingEntity target) {
        try {
            Location ml = minion.getLocation();
            Location tl = target.getLocation();
            if (ml.getWorld() == null || !ml.getWorld().equals(tl.getWorld())) return Double.MAX_VALUE;
            return ml.distanceSquared(tl);
        } catch (Exception e) {
            return Double.MAX_VALUE;
        }
    }

    public void invalidateMinion(UUID minionId) {
        if (minionId == null) return;
        for (OwnerState state : states.values()) {
            synchronized (state) { state.assignment.remove(minionId); }
        }
    }

    public void invalidateOwner(UUID ownerId) {
        if (ownerId != null) states.remove(ownerId);
    }
}
