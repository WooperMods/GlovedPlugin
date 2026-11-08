package dev.gloved.glovedplugin.summon;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Strategic, capacity-based summon targeting (count-aware, no per-minion speed weighting).
 * - Priorities: last damaged/recent damagers highest, then threat/distance-based (owner proximity +
 *   minion travel distance). Enemies close to the owner that lack coverage are prioritized dynamically.
 * - Minion-count aware: min coverage + focus-fire cap scale with squad size.
 * - No owner look targeting; dynamic coverage/lending responds to uncovered threats near the owner.
 */
public class SummonTargetCoordinator {
    private static final long REVIEW_INTERVAL_TICKS = 60L; // ~3s steady-state
    private static final long URGENT_REVIEW_INTERVAL_TICKS = 10L; // ~0.5s when uncovered threats near owner
    private static final double FOCUS_FIRE_SHARE = 0.40;   // top target up to 40% (hard-capped)
    private static final double OWNER_THREAT_RADIUS_SQ = 10.0 * 10.0; // "close to owner" for dynamic coverage
    private static final double OWNER_THREAT_WEIGHT = 8000.0; // significant but below last-damaged (1,000,000)

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

    /**
     * @param globalCandidates owner-valid candidates (shared view for pruning/rebalancing/coverage).
     *                         Must NOT be pre-filtered by per-minion unreachable/dy, or other minions'
     *                         assignments will be pruned incorrectly.
     * @param isUnavailableForMinion optional per-minion filter (unreachable/dy for this minion).
     */
    public LivingEntity assign(Player owner, LivingEntity minion, List<LivingEntity> globalCandidates,
                               Set<UUID> recentDamagerIds, long nowTick,
                               Predicate<UUID> isUnavailableForMinion) {
        if (owner == null || minion == null) return null;
        OwnerState state = states.computeIfAbsent(owner.getUniqueId(), k -> new OwnerState());

        // Global view: used for shared state (prune/rebalance/coverage) so one minion's pathing
        // constraints don't make others "forget" valid enemies (e.g. close to owner).
        Map<UUID, LivingEntity> globalById = new LinkedHashMap<>();
        for (LivingEntity le : globalCandidates) {
            if (le == null || le.isDead()) continue;
            globalById.put(le.getUniqueId(), le);
        }

        // Per-minion reachable view: only targets this minion can take now.
        Map<UUID, LivingEntity> reachableById = new LinkedHashMap<>();
        List<ScoredTarget> scored = new ArrayList<>();
        for (LivingEntity le : globalById.values()) {
            UUID id = le.getUniqueId();
            if (isUnavailableForMinion != null && isUnavailableForMinion.test(id)) continue;
            reachableById.put(id, le);
            scored.add(new ScoredTarget(le, scoreTarget(owner, minion, le, recentDamagerIds)));
        }
        scored.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            if (c != 0) return c;
            return a.id.compareTo(b.id);
        });
        List<UUID> reachableOrder = new ArrayList<>();
        for (ScoredTarget st : scored) reachableOrder.add(st.id);

        synchronized (state) {
            prune(state, owner.getUniqueId(), globalById);

            UUID minionId = minion.getUniqueId();
            UUID current = state.assignment.get(minionId);
            if (current != null) {
                // Keep stable assignment if target still exists globally and is reachable by this minion.
                if (globalById.containsKey(current)
                        && (isUnavailableForMinion == null || !isUnavailableForMinion.test(current))) {
                    return globalById.get(current);
                }
                state.assignment.remove(minionId);
            }

            boolean dueReview = nowTick - state.lastReviewTick >= REVIEW_INTERVAL_TICKS;
            if (!dueReview && hasUncoveredThreatNearOwner(state, owner, globalById)) {
                dueReview = nowTick - state.lastReviewTick >= URGENT_REVIEW_INTERVAL_TICKS;
            }
            if (dueReview || state.assignment.isEmpty()) {
                // Rebalance using global priorities so uncovered threats near owner get coverage.
                List<ScoredTarget> globalScored = new ArrayList<>();
                for (LivingEntity le : globalById.values()) {
                    globalScored.add(new ScoredTarget(le, scoreTarget(owner, minion, le, recentDamagerIds)));
                }
                globalScored.sort((a, b) -> {
                    int c = Double.compare(b.score, a.score);
                    if (c != 0) return c;
                    return a.id.compareTo(b.id);
                });
                List<UUID> globalOrder = new ArrayList<>();
                for (ScoredTarget st : globalScored) globalOrder.add(st.id);
                rebalance(state, owner, globalOrder, globalById);
                state.lastReviewTick = nowTick;
            }

            current = state.assignment.get(minionId);
            if (current != null && reachableById.containsKey(current)) {
                return reachableById.get(current);
            }
            // If rebalance assigned a globally-valid target this minion can't reach, pick best reachable.
            if (current != null && globalById.containsKey(current)) {
                state.assignment.remove(minionId);
            }
            LivingEntity picked = pickBestAvailable(state, owner, minionId, minion, reachableOrder, reachableById, recentDamagerIds);
            if (picked != null) {
                state.assignment.put(minionId, picked.getUniqueId());
                return picked;
            }
            // Last resort: if something is globally valid (e.g. close to owner) but not in reachable
            // view due to transient per-minion filtering, allow targeting it so minions don't ignore it.
            if (current != null) {
                LivingEntity global = globalById.get(current);
                if (global != null) return global;
            }
            for (UUID tid : reachableOrder) {
                LivingEntity le = reachableById.get(tid);
                if (le != null) return le;
            }
            for (LivingEntity le : globalById.values()) return le;
            return null;
        }
    }

    private double scoreTarget(Player owner, LivingEntity minion, LivingEntity target, Set<UUID> recentDamagerIds) {
        double score = 0.0;
        if (recentDamagerIds != null && recentDamagerIds.contains(target.getUniqueId())) {
            score += 1_000_000.0; // last damaged dominates
        }
        try {
            Location tl = target.getLocation();
            // Threat to owner: enemies close to the owner are urgent (dynamic coverage/lending).
            if (owner != null && tl.getWorld() != null && tl.getWorld().equals(owner.getWorld())) {
                double od2 = owner.getLocation().distanceSquared(tl);
                double ownerCloseness = Math.max(0.0, (OWNER_THREAT_RADIUS_SQ * 4.0) - Math.min(od2, OWNER_THREAT_RADIUS_SQ * 4.0));
                score += (ownerCloseness / (OWNER_THREAT_RADIUS_SQ * 4.0)) * OWNER_THREAT_WEIGHT;
            }
            // Travel/range factor: prefer targets this minion can actually reach efficiently.
            Location ml = minion.getLocation();
            if (ml.getWorld() != null && ml.getWorld().equals(tl.getWorld())) {
                double d2 = ml.distanceSquared(tl);
                if (d2 >= 0) score += Math.max(0.0, 10000.0 - Math.min(d2, 10000.0)) * 0.001;
            }
        } catch (Exception ignored) {}
        return score;
    }

    /**
     * True if any candidate is close to the owner and currently has no minion assigned.
     * Used to trigger faster, dynamic rebalancing so uncovered threats near the owner get coverage.
     */
    private boolean hasUncoveredThreatNearOwner(OwnerState state, Player owner, Map<UUID, LivingEntity> candidateById) {
        if (owner == null || candidateById.isEmpty()) return false;
        Set<UUID> covered = new HashSet<>(state.assignment.values());
        Location ol = owner.getLocation();
        for (Map.Entry<UUID, LivingEntity> e : candidateById.entrySet()) {
            if (covered.contains(e.getKey())) continue;
            try {
                LivingEntity le = e.getValue();
                if (le.getWorld() != null && le.getWorld().equals(ol.getWorld())
                        && le.getLocation().distanceSquared(ol) <= OWNER_THREAT_RADIUS_SQ) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void prune(OwnerState state, UUID ownerId, Map<UUID, LivingEntity> candidateById) {
        List<LivingEntity> active = tracker.getActiveMinions(ownerId);
        Set<UUID> activeIds = new HashSet<>();
        for (LivingEntity le : active) activeIds.add(le.getUniqueId());
        state.assignment.keySet().removeIf(mid -> !activeIds.contains(mid));
        state.assignment.entrySet().removeIf(e -> !candidateById.containsKey(e.getValue()));
    }

    private void rebalance(OwnerState state, Player owner, List<UUID> targetOrder,
                           Map<UUID, LivingEntity> candidateById) {
        if (targetOrder.isEmpty()) {
            state.assignment.clear();
            return;
        }
        UUID ownerId = owner.getUniqueId();
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

        // Dynamic lending: ensure enemies close to the owner get coverage when possible.
        // If an uncovered threat is near the owner, lend the nearest suitable minion (taking range
        // into account) even if that means moving it from a lower-priority, non-urgent target.
        lendCoverageToUncoveredThreats(state, owner, minions, targetOrder, candidateById, capacity, load);
    }

    private void lendCoverageToUncoveredThreats(OwnerState state, Player owner, List<LivingEntity> minions,
                                                List<UUID> targetOrder, Map<UUID, LivingEntity> candidateById,
                                                Map<UUID, Integer> capacity, Map<UUID, Integer> load) {
        if (owner == null || targetOrder.isEmpty() || minions.isEmpty()) return;
        Location ol = owner.getLocation();
        Set<UUID> covered = new HashSet<>();
        for (UUID tid : state.assignment.values()) if (load.getOrDefault(tid, 0) > 0) covered.add(tid);

        // Prioritize uncovered targets that are close to the owner (threats), in targetOrder priority.
        List<UUID> uncoveredThreats = new ArrayList<>();
        for (UUID tid : targetOrder) {
            if (covered.contains(tid)) continue;
            LivingEntity le = candidateById.get(tid);
            if (le == null) continue;
            try {
                if (le.getWorld() != null && le.getWorld().equals(ol.getWorld())
                        && le.getLocation().distanceSquared(ol) <= OWNER_THREAT_RADIUS_SQ) {
                    uncoveredThreats.add(tid);
                }
            } catch (Exception ignored) {}
        }
        if (uncoveredThreats.isEmpty()) return;

        Map<UUID, LivingEntity> minionById = new HashMap<>();
        for (LivingEntity m : minions) minionById.put(m.getUniqueId(), m);

        for (UUID threatId : uncoveredThreats) {
            if (load.getOrDefault(threatId, 0) > 0) continue;
            LivingEntity threat = candidateById.get(threatId);
            if (threat == null) continue;

            // Find best lender: minion closest (range-aware) whose current target is lower priority
            // and not itself an uncovered owner-close threat, and not the sole minion on a recent-damager
            // in a way that leaves it empty? (we allow lending; capacity/focus still guides).
            UUID bestMinionId = null;
            double bestDistSq = Double.MAX_VALUE;
            UUID bestFromTarget = null;
            for (Map.Entry<UUID, UUID> a : state.assignment.entrySet()) {
                UUID mid = a.getKey();
                UUID fromTid = a.getValue();
                if (fromTid == null || threatId.equals(fromTid)) continue;
                LivingEntity lender = minionById.get(mid);
                if (lender == null) continue;

                // Don't steal if it would leave a higher-priority target completely uncovered
                // unless that target is not an owner-close threat and is lower priority than this threat.
                int fromLoad = load.getOrDefault(fromTid, 0);
                int fromPriority = targetOrder.indexOf(fromTid);
                int threatPriority = targetOrder.indexOf(threatId);
                boolean fromIsOwnerThreat = isNearOwner(owner, candidateById.get(fromTid));
                boolean fromIsSoleOnHighPriority = fromLoad <= 1 && (fromPriority >= 0 && fromPriority < threatPriority);
                if (fromIsOwnerThreat && fromLoad <= 1) continue;
                if (fromIsSoleOnHighPriority && threatPriority > 0) continue;

                double distSq = distanceSq(lender, threat);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestMinionId = mid;
                    bestFromTarget = fromTid;
                }
            }

            if (bestMinionId != null && bestFromTarget != null) {
                state.assignment.put(bestMinionId, threatId);
                load.put(bestFromTarget, Math.max(0, load.getOrDefault(bestFromTarget, 0) - 1));
                load.put(threatId, load.getOrDefault(threatId, 0) + 1);
                covered.add(threatId);
            }
        }
    }

    private static boolean isNearOwner(Player owner, LivingEntity target) {
        if (owner == null || target == null) return false;
        try {
            Location ol = owner.getLocation();
            return target.getWorld() != null && target.getWorld().equals(ol.getWorld())
                    && target.getLocation().distanceSquared(ol) <= OWNER_THREAT_RADIUS_SQ;
        } catch (Exception e) {
            return false;
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

    private LivingEntity pickBestAvailable(OwnerState state, Player owner, UUID minionId, LivingEntity minion,
                                           List<UUID> targetOrder, Map<UUID, LivingEntity> candidateById,
                                           Set<UUID> recentDamagerIds) {
        UUID ownerId = owner.getUniqueId();
        Map<UUID, Integer> capacity = computeCapacities(
                Math.max(1, tracker.getActiveMinions(ownerId).size()), targetOrder.size(), targetOrder);
        Map<UUID, Integer> load = new HashMap<>();
        for (UUID tid : targetOrder) load.put(tid, 0);
        for (Map.Entry<UUID, UUID> e : state.assignment.entrySet()) {
            if (!e.getKey().equals(minionId) && load.containsKey(e.getValue())) {
                load.put(e.getValue(), load.get(e.getValue()) + 1);
            }
        }

        // Dynamic: prefer uncovered enemies close to the owner (range-aware lending).
        LivingEntity uncoveredThreat = null;
        double uncoveredDistSq = Double.MAX_VALUE;
        for (UUID tid : targetOrder) {
            if (load.getOrDefault(tid, 0) > 0) continue;
            LivingEntity target = candidateById.get(tid);
            if (target == null || !isNearOwner(owner, target)) continue;
            double distSq = distanceSq(minion, target);
            if (distSq < uncoveredDistSq) { uncoveredDistSq = distSq; uncoveredThreat = target; }
        }
        if (uncoveredThreat != null) return uncoveredThreat;

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
