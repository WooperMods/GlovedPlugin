package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.Pathfinder;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.lootr.util.EliteMobsIntegration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class MinionAI {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final ReachabilityService reachabilityService;
    private final Map<UUID, UUID> minionTasks = new HashMap<>();
    private SummonManager summonManager;
    private SummonConfig summonConfig;
    private dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge progression =
            new dev.gloved.glovedplugin.summon.progression.NoopSummonProgressionBridge();
    private dev.gloved.glovedplugin.summon.progression.SummonAbilityService abilityService;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> recentDamagers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DAMAGER_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> unreachableCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, org.bukkit.Location>> unreachableLocationCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNREACHABLE_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastWaypointLog = new java.util.concurrent.ConcurrentHashMap<>();
    // Trait ICDs (bounded proc behavior)
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> unstableIcd = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> packboundIcd = new java.util.concurrent.ConcurrentHashMap<>();

    public MinionAI(GlovedPlugin plugin, SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.reachabilityService = new ReachabilityService(plugin);
        startGlobalTask();
    }

    /** Wired after construction to avoid a MinionAI <-> SummonManager cycle. */
    public void setSummon(SummonConfig summonConfig, SummonManager summonManager) {
        this.summonConfig = summonConfig;
        this.summonManager = summonManager;
    }

    public void setProgression(dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge progression) {
        this.progression = progression != null ? progression : new dev.gloved.glovedplugin.summon.progression.NoopSummonProgressionBridge();
    }

    public void setAbilityService(dev.gloved.glovedplugin.summon.progression.SummonAbilityService abilityService) {
        this.abilityService = abilityService;
    }

    private dev.gloved.glovedplugin.summon.soul.SoulInstance resolveMinionSoul(LivingEntity minion) {
        try {
            if (minion == null || plugin.getSoulService() == null) return null;
            NamespacedKey k = new NamespacedKey(plugin, "summon_soul_id");
            String id = minion.getPersistentDataContainer().get(k, PersistentDataType.STRING);
            if (id == null || id.isBlank()) return null;
            return plugin.getSoulService().getSoulCached(java.util.UUID.fromString(id));
        } catch (Throwable ignored) { return null; }
    }

    private dev.gloved.glovedplugin.summon.soul.SoulStatSnapshot resolveMinionSnapshot(LivingEntity minion) {
        try {
            if (minion == null || plugin.getSoulService() == null) return dev.gloved.glovedplugin.summon.soul.SoulStatSnapshot.identity();
            NamespacedKey k = new NamespacedKey(plugin, "summon_soul_id");
            String id = minion.getPersistentDataContainer().get(k, PersistentDataType.STRING);
            if (id == null || id.isBlank()) return dev.gloved.glovedplugin.summon.soul.SoulStatSnapshot.identity();
            return plugin.getSoulService().getSnapshot(java.util.UUID.fromString(id));
        } catch (Throwable ignored) { return dev.gloved.glovedplugin.summon.soul.SoulStatSnapshot.identity(); }
    }

    public static void recordRecentDamager(Player player, LivingEntity damager) {
        if (player == null || damager == null) return;
        recentDamagers.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(damager.getUniqueId(), System.currentTimeMillis());
    }

    public static java.util.List<UUID> getRecentDamagers(Player player) {
        if (player == null) return java.util.Collections.emptyList();
        var map = recentDamagers.get(player.getUniqueId());
        if (map == null) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        java.util.List<UUID> result = new java.util.ArrayList<>();
        for (var e : map.entrySet()) {
            if (now - e.getValue() <= DAMAGER_TTL_MS) {
                result.add(e.getKey());
            } else {
                map.remove(e.getKey());
            }
        }
        if (map.isEmpty()) recentDamagers.remove(player.getUniqueId());
        return result;
    }

    /** Tracks who last damaged a given minion so its squad can prioritize hitting back. */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> minionRecentDamagers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MINION_DAMAGER_TTL_MS = 10_000L;

    public static void recordRecentDamager(LivingEntity minion, LivingEntity damager) {
        if (minion == null || damager == null) return;
        minionRecentDamagers.computeIfAbsent(minion.getUniqueId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(damager.getUniqueId(), System.currentTimeMillis());
    }

    public static java.util.List<UUID> getRecentDamagers(LivingEntity minion) {
        if (minion == null) return java.util.Collections.emptyList();
        var map = minionRecentDamagers.get(minion.getUniqueId());
        if (map == null) return java.util.Collections.emptyList();
        long now = System.currentTimeMillis();
        java.util.List<UUID> result = new java.util.ArrayList<>();
        for (var e : map.entrySet()) {
            if (now - e.getValue() <= MINION_DAMAGER_TTL_MS) {
                result.add(e.getKey());
            } else {
                map.remove(e.getKey());
            }
        }
        if (map.isEmpty()) minionRecentDamagers.remove(minion.getUniqueId());
        return result;
    }

    public static boolean isCachedUnreachable(UUID minionId, UUID targetId, org.bukkit.Location currentLoc) {
        var map = unreachableCache.get(minionId);
        if (map == null) return false;
        long now = System.currentTimeMillis();
        Long exp = map.get(targetId);
        if (exp == null) return false;
        if (now > exp) {
            map.remove(targetId);
            var locMap = unreachableLocationCache.get(minionId);
            if (locMap != null) {
                locMap.remove(targetId);
                if (locMap.isEmpty()) unreachableLocationCache.remove(minionId);
            }
            if (map.isEmpty()) unreachableCache.remove(minionId);
            return false;
        }
        // If target moved since caching, invalidate
        var locMap = unreachableLocationCache.get(minionId);
        if (locMap != null) {
            org.bukkit.Location cachedLoc = locMap.get(targetId);
            if (cachedLoc != null && currentLoc != null) {
                if (cachedLoc.getWorld() != currentLoc.getWorld() || cachedLoc.distanceSquared(currentLoc) > 0.01) {
                    // Target moved/world changed, invalidate cache
                    map.remove(targetId);
                    locMap.remove(targetId);
                    if (map.isEmpty()) unreachableCache.remove(minionId);
                    if (locMap.isEmpty()) unreachableLocationCache.remove(minionId);
                    return false;
                }
            }
        }
        return true;
    }

    public static void cacheUnreachable(UUID minionId, UUID targetId, org.bukkit.Location locAtCache) {
        unreachableCache.computeIfAbsent(minionId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(targetId, System.currentTimeMillis() + UNREACHABLE_TTL_MS);
        if (locAtCache != null) {
            unreachableLocationCache.computeIfAbsent(minionId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(targetId, locAtCache.clone());
        }
    }

    private void startGlobalTask() {
        // Custom minion-targeting goals removed: use vanilla targeting only.
    }

    /** Per-minion target score: higher = higher priority. */
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

    /** Targeting intent for a minion - drives movement speed bursts and bodyguard positioning. */
    public enum TargetIntent {
        IDLE,           // following owner, normal speed
        DEFENSIVE,      // minion low HP, staying near owner, not over-extending
        PEELING,        // chasing threat attacking owner, high speed burst
        FINISHING,      // chasing near-dead target, small speed burst
        CHASING         // general pursuit, normal speed
    }

    /** Minion HP thresholds. */
    private static final double MINION_LOW_HP_PCT = 0.30;
    private static final double OWNER_LOW_HP_PCT = 0.30;
    private static final double TARGET_NEAR_DEAD_PCT = 0.30;

    /** Bodyguard radius: threats within this many blocks of the owner are urgent. */
    private static final double BODYGUARD_RADIUS_SQ = 6.0 * 6.0;

    /** Hysteresis: a new target must score this much higher to displace current target. */
    private static final double TARGET_SWITCH_THRESHOLD = 1.25;

    /** Per-owner target assignment counts for swarm-avoidance (minions spread out). */
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Integer>> targetAssignments =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ASSIGNMENT_TTL_MS = 10_000L;

    /** Determine a minion's targeting intent from its current state and target. */
    public static TargetIntent determineIntent(LivingEntity minion, Player owner, LivingEntity target) {
        if (target == null) return TargetIntent.IDLE;
        double minionHpPct = minion.getHealth() / minion.getMaxHealth();
        if (minionHpPct < MINION_LOW_HP_PCT) return TargetIntent.DEFENSIVE;
        if (target instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
            if (mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId())) {
                return TargetIntent.PEELING;
            }
        }
        double targetHpPct = target.getHealth() / target.getMaxHealth();
        if (targetHpPct < TARGET_NEAR_DEAD_PCT) return TargetIntent.FINISHING;
        return TargetIntent.CHASING;
    }

    /**
     * Score a candidate target for a minion. The balance between self-preservation and
     * owner-protection scales with the minion's current HP:
     *
     *   - At full HP: protect the owner at all costs (except death to self).
     *   - As HP drops: self-preservation becomes increasingly important.
     *   - At near-death: stay defensive, don't over-extend, prioritize survival.
     *
     * Priority stack:
     * 1. Actively attacking this minion (survival - hit back first)
     * 2. Actively attacking the owner (peel threat off player)
     * 3. Recently damaged this minion
     * 4. Recently damaged the owner
     * 5. Close to the minion (immediate self-defense threat)
     * 6. Close to the owner (bodyguard - owner in danger)
     * 7. Low target HP (easier to finish - clear threats fast)
     */
    private static double scoreTarget(Player owner, LivingEntity minion, LivingEntity target,
                                       Set<UUID> recentDamagerIds) {
        double score = 0.0;
        UUID tid = target.getUniqueId();
        Location tLoc = target.getLocation();
        Location mLoc = minion.getLocation();
        Location oLoc = owner.getLocation();

        double minionHpPct = minion.getHealth() / minion.getMaxHealth();
        double ownerHpPct = owner.getHealth() / owner.getMaxHealth();
        double targetHpPct = target.getHealth() / target.getMaxHealth();
        boolean minionLowHp = minionHpPct < MINION_LOW_HP_PCT;
        boolean ownerLowHp = ownerHpPct < OWNER_LOW_HP_PCT;

        // Self-preservation weight scales inversely with minion HP:
        // full HP -> low self weight (protect owner), near-death -> high self weight (survive)
        double selfWeight = 1.0 - minionHpPct; // 0.0 at full HP, 1.0 at 0 HP
        double ownerWeight = minionHpPct;      // 1.0 at full HP, 0.0 at 0 HP

        double d2Owner = tLoc.distanceSquared(oLoc);
        double d2Minion = mLoc.distanceSquared(tLoc);

        // 1. Actively attacking this minion - survival is #1, scaled by how hurt we are
        if (target instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
            UUID mobTarget = mobEnt.getTarget().getUniqueId();
            if (mobTarget.equals(minion.getUniqueId())) {
                double base = 10_000_000.0;
                score += base * (0.5 + selfWeight); // more urgent the lower our HP
            } else if (mobTarget.equals(owner.getUniqueId())) {
                // attacking owner - peel, but weighted by how much HP we have to spare
                double base = 10_000_000.0 * ownerWeight;
                score += base; // full HP -> full peel urgency, low HP -> less
                if (ownerLowHp) score += 2_000_000.0 * ownerWeight; // owner dying
            }
        }

        // 2. Recently damaged this minion (hit back for survival)
        if (MinionAI.getRecentDamagers(minion).contains(tid)) {
            score += 3_000_000.0 * (0.5 + selfWeight);
        }

        // 3. Recently damaged the owner
        if (recentDamagerIds != null && recentDamagerIds.contains(tid)) {
            score += 2_000_000.0 * ownerWeight;
        }

        // 4. Close to the minion - immediate self-defense threat (scaled by self-preservation)
        double closeToMinionBonus = Math.max(0.0, 8.0 - Math.sqrt(d2Minion)) / 8.0; // 1.0 at melee, 0 at 8 blocks
        score += closeToMinionBonus * 2_000_000.0 * (0.5 + selfWeight);

        // 5. Close to the owner - owner is in danger (bodyguard radius ~6 blocks)
        if (d2Owner <= BODYGUARD_RADIUS_SQ) {
            double ownerDanger = (1.0 - d2Owner / BODYGUARD_RADIUS_SQ); // 1.0 right on owner
            score += ownerDanger * 1_500_000.0 * ownerWeight;
        }

        // 6. Target HP - lower is easier to finish; near-dead targets get a bonus
        if (targetHpPct < 0.5) {
            score += (1.0 - targetHpPct) * 300_000.0; // up to ~150k for almost-dead
        }

        // 7. Swarm avoidance: if many minions already target this enemy, deprioritize
        int assignmentCount = getTargetAssignmentCount(owner.getUniqueId(), tid);
        if (assignmentCount >= 1) {
            score -= assignmentCount * 300_000.0; // spread minions across multiple enemies
        }

        return score;
    }

    private static int getTargetAssignmentCount(UUID ownerId, UUID targetId) {
        var map = targetAssignments.get(ownerId);
        if (map == null) return 0;
        Integer v = map.get(targetId);
        return v == null ? 0 : v;
    }

    private static void setTargetAssignmentCount(UUID ownerId, UUID targetId, int count) {
        targetAssignments.computeIfAbsent(ownerId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(targetId, count);
    }

    public static void clearTargetAssignment(UUID ownerId, UUID targetId) {
        var map = targetAssignments.get(ownerId);
        if (map != null) {
            map.remove(targetId);
            if (map.isEmpty()) targetAssignments.remove(ownerId);
        }
    }

    /** Record that a minion is now targeting a target (for swarm avoidance). */
    public static void recordTargetAssignment(UUID ownerId, LivingEntity minion, LivingEntity target) {
        if (ownerId == null || target == null) return;
        var map = targetAssignments.computeIfAbsent(ownerId, k -> new java.util.concurrent.ConcurrentHashMap<>());
        map.merge(target.getUniqueId(), 1, Integer::sum);
    }

    /** Store targeting intent on the minion's PDC so SummonMoveGoal can read it for speed bursts. */
    public static void storeIntent(LivingEntity minion, TargetIntent intent) {
        if (minion == null) return;
        try {
            NamespacedKey key = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_target_intent");
            minion.getPersistentDataContainer().set(key, PersistentDataType.STRING, intent.name());
        } catch (Throwable ignored) {}
    }

    public void monitorMinion(LivingEntity minion, Player owner, SummonProfile profile) {
        if (minion == null || owner == null) return;
        new BukkitRunnable() {
            long lastAttackTick = 0L;
            LivingEntity lastTarget = null;
            long lastTargetTick = 0L;
            double lastTargetDist = Double.MAX_VALUE;
            long lastTargetDistTick = 0L;
            long stuckSince = 0L;
            double lastFollowDist = Double.MAX_VALUE;
            long followStuckSince = 0L;
            long followUnreachableSince = 0L;
            UUID previousAssignmentTarget = null;
            TargetIntent currentIntent = TargetIntent.IDLE;
            Location navGoal = null;
            long navGoalTick = -1L;
            long lastOwnerReachCheck = -1000L;
            boolean ownerReachCached = true;
            Location lastReachMobLoc = null;
            Location lastReachOwnerLoc = null;

            /**
             * Drives the mob along a Paper path to {@code goal}. The path is only rebuilt when it is
             * gone, when the goal moved more than 2 blocks, or every 2 seconds. Rebuilding the path
             * every tick makes mobs cut corners and walk straight into obstacles.
             */
            private boolean driveTowards(org.bukkit.entity.Mob mob, Location goal, double speed, long now) {
                if (mob == null || goal == null) return false;
                Pathfinder pf;
                try {
                    pf = mob.getPathfinder();
                } catch (Exception e) {
                    return false;
                }
                if (pf == null) return false;
                // Already at the destination: do not rebuild a path every tick at melee range.
                // Keep this tight because vanilla melee reach is roughly 1.5-2 blocks.
                if (mob.getLocation().distanceSquared(goal) <= 1.0) {
                    try {
                        pf.stopPathfinding();
                    } catch (Exception ignored) {}
                    navGoal = null;
                    return true;
                }
                boolean needPath = true;
                if (navGoal != null && navGoal.getWorld() == goal.getWorld()) {
                    boolean hasPath = false;
                    try {
                        hasPath = pf.hasPath();
                    } catch (Exception ignored) {}
                    if (hasPath && navGoal.distanceSquared(goal) <= 4.0 && now - navGoalTick < 40) {
                        needPath = false;
                    }
                }
                if (!needPath) return true;
                try {
                    if (pf.moveTo(goal, speed)) {
                        navGoal = goal.clone();
                        navGoalTick = now;
                        return true;
                    }
                } catch (Exception ignored) {}
                return false;
            }

            @Override
            public void run() {
                try {
                if (minion.isDead() || !tracker.isSummoned(minion.getUniqueId())) {
                    // Clear swarm assignment so other minions aren't penalized for a dead target.
                    if (previousAssignmentTarget != null) {
                        clearTargetAssignment(owner.getUniqueId(), previousAssignmentTarget);
                    }
                    cancel();
                    return;
                }
                if (owner == null || !owner.isOnline()) {
                    tracker.despawn(minion.getUniqueId());
                    cancel();
                    return;
                }
                org.bukkit.entity.Mob mob = minion instanceof org.bukkit.entity.Mob m ? m : null;
                if (mob == null) {
                    // Non-mob entities just follow owner
                    if (minion.getWorld() != owner.getWorld() || minion.getLocation().distanceSquared(owner.getLocation()) > SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE) {
                        GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " non-mob out of range/world from owner " + owner.getName() + ", teleporting");
                        minion.teleport(owner.getLocation().add((Math.random()-0.5)*2,0,(Math.random()-0.5)*2));
                    }
                    return;
                }
                if (minion.getWorld() != owner.getWorld()) {
                    teleportNearPlayer(owner, minion);
                    return;
                }
                boolean flying;
                try {
                    flying = mob.getAttribute(Attribute.FLYING_SPEED) != null;
                } catch (Exception ignored) {
                    flying = false;
                }
                final boolean isFlyingMob = flying;
                // Targeting is plugin-owned; SummonMoveGoal handles obstacle-aware pathfinding.

                // Capacity-based stable targeting (no owner-look, no protection emergency, no "close enemy" priority).
                // Scoring: last damaged/recent damagers highest, then distance-based.
                LivingEntity target = null;
                List<LivingEntity> candidates = new ArrayList<>();
                Set<UUID> recentDamagerIds = new HashSet<>();
                double scanDist = SummonProfile.FOLLOW_RANGE;
                // Scan around the owner (defend owner) and around the minion (defend self),
                // so minions engage threats near themselves even when away from the owner.
                List<LivingEntity> scannedNearOwner = new ArrayList<>();
                for (Entity e : owner.getNearbyEntities(scanDist, scanDist, scanDist)) {
                    if (!(e instanceof LivingEntity le)) continue;
                    if (le == owner || le.isDead() || tracker.isSummoned(le.getUniqueId())) continue;
                    if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
                    scannedNearOwner.add(le);
                }
                List<LivingEntity> scannedNearMinion = new ArrayList<>();
                for (Entity e : minion.getNearbyEntities(scanDist, scanDist, scanDist)) {
                    if (!(e instanceof LivingEntity le)) continue;
                    if (le == owner || le.isDead() || tracker.isSummoned(le.getUniqueId())) continue;
                    if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
                    if (!scannedNearOwner.contains(le)) scannedNearMinion.add(le);
                }
                for (LivingEntity le : scannedNearOwner) {
                    boolean isHostile = le instanceof org.bukkit.entity.Monster
                            || le instanceof org.bukkit.entity.Slime
                            || le instanceof org.bukkit.entity.Ghast;
                    boolean targetsOwner = false;
                    boolean targetsMinion = false;
                    if (le instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
                        targetsOwner = mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId());
                        targetsMinion = mobEnt.getTarget().getUniqueId().equals(minion.getUniqueId());
                    }
                    if (isHostile || targetsOwner || targetsMinion) {
                        if (!candidates.contains(le)) candidates.add(le);
                    }
                }
                for (LivingEntity le : scannedNearMinion) {
                    boolean isHostile = le instanceof org.bukkit.entity.Monster
                            || le instanceof org.bukkit.entity.Slime
                            || le instanceof org.bukkit.entity.Ghast;
                    boolean targetsMinion = false;
                    if (le instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
                        targetsMinion = mobEnt.getTarget().getUniqueId().equals(minion.getUniqueId());
                    }
                    if (isHostile || targetsMinion) {
                        if (!candidates.contains(le)) candidates.add(le);
                    }
                }
                for (UUID damagerId : MinionAI.getRecentDamagers(owner)) {
                    recentDamagerIds.add(damagerId);
                    Entity e = owner.getWorld().getEntity(damagerId);
                    if (e instanceof LivingEntity le && le != owner && !le.isDead() && !tracker.isSummoned(le.getUniqueId())
                            && !CombatTargetRules.isProtected(plugin, owner, le)) {
                        if (!candidates.contains(le)) candidates.add(le);
                    }
                }
                // Entities that recently damaged this minion get priority targeting so minions hit back.
                for (UUID damagerId : MinionAI.getRecentDamagers(minion)) {
                    recentDamagerIds.add(damagerId);
                    Entity e = minion.getWorld().getEntity(damagerId);
                    if (e instanceof LivingEntity le && le != owner && !le.isDead() && !tracker.isSummoned(le.getUniqueId())
                            && !CombatTargetRules.isProtected(plugin, owner, le)) {
                        if (!candidates.contains(le)) candidates.add(le);
                    }
                }
                // Keep threats near either the owner or the minion; do not drop
                // minion-centered targets just because they are far from the owner.
                double maxTargetSq = SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE;
                candidates.removeIf(le -> {
                    if (le.getWorld() != owner.getWorld()) return true;
                    double d2Owner = le.getLocation().distanceSquared(owner.getLocation());
                    double d2Minion = le.getLocation().distanceSquared(minion.getLocation());
                    return d2Owner > maxTargetSq && d2Minion > maxTargetSq;
                });

                // Per-minion target selection: filter by reachability, score by smart owner-defensive
                // priority (HP-aware, target-HP-aware, swarm-aware), pick best target with stickiness.
                // Track target assignments for swarm avoidance across minions of the same owner.
                List<ScoredTarget> scored = new ArrayList<>();
                double maxChaseSq = SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE;
                for (LivingEntity le : candidates) {
                    UUID id = le.getUniqueId();
                    Entity te = owner.getWorld().getEntity(id);
                    if (!(te instanceof LivingEntity tle)) continue;
                    // Reachability pre-filter: never pathfind at unreachable enemies.
                    if (!reachabilityService.canReachEntity(mob, tle)) {
                        MinionAI.cacheUnreachable(minion.getUniqueId(), id, tle.getLocation());
                        continue;
                    }
                    if (!isFlyingMob) {
                        double dy = Math.abs(minion.getLocation().getY() - tle.getLocation().getY());
                        if (dy > 16) continue;
                    }
                    double d2 = minion.getLocation().distanceSquared(tle.getLocation());
                    if (d2 > maxChaseSq) continue;
                    double score = scoreTarget(owner, minion, tle, recentDamagerIds);
                    scored.add(new ScoredTarget(tle, score));
                }
                scored.sort((a, b) -> {
                    int c = Double.compare(b.score, a.score);
                    if (c != 0) return c;
                    return a.id.compareTo(b.id);
                });

                long now = Bukkit.getCurrentTick();
                LivingEntity newTarget = null;
                if (!scored.isEmpty()) {
                    LivingEntity best = scored.get(0).entity;
                    // Stickiness with hysteresis: keep current target unless a much higher-priority
                    // target appears (>= 25% higher score). Low-HP targets are preferred for finishing.
                    if (lastTarget != null && !lastTarget.isDead()
                            && !CombatTargetRules.isProtected(plugin, owner, lastTarget)) {
                        Double currentScore = null;
                        Double bestScore = scored.get(0).score;
                        for (ScoredTarget st : scored) {
                            if (st.id.equals(lastTarget.getUniqueId())) {
                                currentScore = st.score;
                                break;
                            }
                        }
                        if (currentScore != null && bestScore <= currentScore * TARGET_SWITCH_THRESHOLD) {
                            newTarget = lastTarget;
                        } else {
                            newTarget = best;
                        }
                    } else {
                        newTarget = best;
                    }
                }
                target = newTarget;

                if (target != null) {
                    if (target.isDead() || CombatTargetRules.isProtected(plugin, owner, target)) {
                        target = null;
                    }
                }
                LivingEntity previousLastTarget = lastTarget;
                if (target != null) {
                    lastTarget = target;
                    lastTargetTick = now;
                } else {
                    // Fallback to memory briefly if no scored target but recent target may still be valid
                    if (lastTarget != null && now - lastTargetTick < 40 && !lastTarget.isDead()
                            && !CombatTargetRules.isProtected(plugin, owner, lastTarget)) {
                        target = lastTarget;
                    } else {
                        lastTarget = null;
                    }
                }

                // Track target assignments for swarm avoidance: clear old, record new.
                // Done after the dead/protected check so we don't track invalid targets.
                if (previousAssignmentTarget != null) {
                    clearTargetAssignment(owner.getUniqueId(), previousAssignmentTarget);
                }
                if (target != null) {
                    recordTargetAssignment(owner.getUniqueId(), minion, target);
                    previousAssignmentTarget = target.getUniqueId();
                }

                // A newly acquired target should not be gated by a stale attack cooldown
                // left over from a previous target.
                if (target != null
                        && previousLastTarget != null
                        && !target.getUniqueId().equals(previousLastTarget.getUniqueId())) {
                    lastAttackTick = 0L;
                }

                // Determine and store targeting intent so SummonMoveGoal can apply speed bursts
                // and bodyguard positioning.
                TargetIntent intent = determineIntent(minion, owner, target);
                storeIntent(minion, intent);
                currentIntent = intent;

                // Unreachable target detection: drop target if distance hasn't improved for ~5s
                if (target != null) {
                    if (minion.getWorld() != target.getWorld()) {
                        target = null;
                        lastTarget = null;
                        lastTargetDist = Double.MAX_VALUE;
                        stuckSince = 0L;
                        try { mob.setTarget(null); } catch (Exception ignored) {}
                    } else {
                        double curDist = minion.getLocation().distance(target.getLocation());
                        if (lastTarget != null && lastTarget.getUniqueId().equals(target.getUniqueId())) {
                            if (curDist < lastTargetDist - 0.1) {
                                // Progress made
                                lastTargetDist = curDist;
                                lastTargetDistTick = now;
                                stuckSince = 0L;
                            } else {
                                if (stuckSince == 0L) stuckSince = now;
                                if (now - stuckSince >= 100) {
                                    // Consider unreachable, drop target
                                    GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " stuck on target " + target.getName() + ", dropping");
                                    MinionAI.cacheUnreachable(minion.getUniqueId(), target.getUniqueId(), target.getLocation());

                                    target = null;
                                    lastTarget = null;
                                    lastTargetDist = Double.MAX_VALUE;
                                    stuckSince = 0L;
                                    try { mob.setTarget(null); } catch (Exception ignored) {}
                                }
                            }
                        } else {
                            // New target
                            lastTargetDist = curDist;
                            lastTargetDistTick = now;
                            stuckSince = 0L;
                        }
                    }
                } else {
                    // Reset stuck tracking when no target
                    lastTargetDist = Double.MAX_VALUE;
                    stuckSince = 0L;
                }

                try {
                    mob.setTarget(target);
                } catch (Exception ignored) {}

                if (target != null && minion.getWorld() == target.getWorld()) {
                    // Movement is handled by SummonMoveGoal using Paper's pathfinder.
                    double distSq = minion.getLocation().distanceSquared(target.getLocation());
                    double attackRangeSq = defaultAttackReachSq(minion, target);
                    GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " distSq=" + distSq + " attackRangeSq=" + attackRangeSq + " lastAttackTickDiff=" + (now - lastAttackTick) + " requiredArcTicks=" + requiredArcTicks(owner));
                    if (distSq <= attackRangeSq && now - lastAttackTick >= requiredArcTicks(owner)) {
                        if (CombatTargetRules.isProtected(plugin, owner, target)) {
                            lastAttackTick = now;
                            GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " skipped protected target " + target.getName());
                        } else {
                            forceFullAttackStrength(owner);
                            // Trigger attack animation
                            try {
                                ((org.bukkit.entity.LivingEntity) mob).attack(target);
                            } catch (Exception ignored) {}
                            double finalDamage = applyMinionHit(minion, owner, target,
                                    isEliteMob(target) ? 7.0 + EliteMobsIntegration.getCombatLevel(owner) * 0.4 : 7.0,
                                    now, profile != null ? profile.name : null);
                            lastAttackTick = now;
                        }
                    }
                } else if (target == null) {
                    // No target -> SummonMoveGoal pathfinds to owner; handle stuck recovery only.
                    if (minion.getWorld() != owner.getWorld()) {
                        teleportNearPlayer(owner, minion);
                    } else {
                    double distSqToOwner = minion.getLocation().distanceSquared(owner.getLocation());
                    double idleRadius = 4.0;
                    double curFollowDist = Math.sqrt(distSqToOwner);
                    if (curFollowDist <= idleRadius) {
                        lastFollowDist = curFollowDist;
                        followStuckSince = 0L;
                        followUnreachableSince = 0L;
                    } else {
                        if (curFollowDist < lastFollowDist - 0.1) {
                            lastFollowDist = curFollowDist;
                            followStuckSince = 0L;
                        } else {
                            if (followStuckSince == 0L) followStuckSince = now;
                            if (now - followStuckSince >= 100) {
                                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " stuck following " + owner.getName() + ", teleporting");
                                teleportNearPlayer(owner, minion);
                                lastFollowDist = Double.MAX_VALUE;
                                followStuckSince = 0L;
                                followUnreachableSince = 0L;
                            }
                        }
                    }
                    }
                }
                // Teleport if too far away
                if (target == null && (minion.getWorld() != owner.getWorld() || minion.getLocation().distanceSquared(owner.getLocation()) > SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE)) {
                    GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " too far from owner " + owner.getName() + " with no target, teleporting");
                    minion.teleport(owner.getLocation().add((Math.random()-0.5)*2,0,(Math.random()-0.5)*2));
                }
                } catch (Throwable t) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "[Summon] MinionAI tick error for " + (minion != null ? minion.getUniqueId() : "unknown") + " (suppressed)", t);
                    try { cancel(); } catch (Throwable ignored) {}
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    public static void teleportNearPlayer(Player owner, LivingEntity minion) {
        org.bukkit.Location base = owner.getLocation().clone();
        // Teleport to player with small random XZ offset, keeping Y to work underground
        org.bukkit.Location target = base.clone().add((Math.random()-0.5)*2, 0, (Math.random()-0.5)*2);
        // Ensure we don't teleport inside a solid block; if so, try a few times
        for (int i = 0; i < 5; i++) {
            if (!target.getBlock().getType().isSolid()) {
                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " teleporting near player " + owner.getName() + " via teleportNearPlayer");
                minion.teleport(target);
                return;
            }
            target.add((Math.random()-0.5)*1, 0, (Math.random()-0.5)*1);
        }
        // Fallback to exact player location
        GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " teleporting to owner " + owner.getName() + " fallback via teleportNearPlayer");
        minion.teleport(base);
    }

    private double packboundMultiplier(LivingEntity minion, Player owner, dev.gloved.glovedplugin.summon.soul.SoulInstance soul) {
        try {
            if (minion == null || owner == null || soul == null || tracker == null) return 1.0;
            String fam = soul.getFamilyKey();
            int same = 0;
            for (LivingEntity le : tracker.getActiveMinions(owner.getUniqueId())) {
                if (le == null || le.isDead()) continue;
                if (le.getWorld() != minion.getWorld()) continue;
                if (le.getLocation().distanceSquared(minion.getLocation()) > 256.0) continue; // 16 blocks
                try {
                    var pdc = le.getPersistentDataContainer();
                    String f = pdc.get(new NamespacedKey(plugin, "summon_family"), PersistentDataType.STRING);
                    if (f == null) f = pdc.get(new NamespacedKey(plugin, "summon_profile"), PersistentDataType.STRING);
                    if (fam.equalsIgnoreCase(f)) same++;
                } catch (Throwable ignored) {}
            }
            if (same >= 2) {
                // bounded: +3% per nearby same-family ally up to +12%
                return 1.0 + Math.min(0.12, (same - 1) * 0.03);
            }
        } catch (Throwable ignored) {}
        return 1.0;
    }

    private void applyUnstableTrait(Player owner, LivingEntity minion, LivingEntity primaryTarget, dev.gloved.glovedplugin.summon.soul.SoulInstance soul, double finalDamage, double baseDamage, long nowTick) {
        if (owner == null || minion == null || primaryTarget == null || soul == null) return;
        if (!soul.hasTrait(dev.gloved.glovedplugin.summon.soul.SoulTrait.UNSTABLE)) return;
        Long last = unstableIcd.get(minion.getUniqueId());
        if (last != null && nowTick - last < 80) return; // ~4s ICD
        // ~18% proc chance, bounded
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > 0.18) return;
        unstableIcd.put(minion.getUniqueId(), nowTick);
        double radius = 2.75;
        double aoe = Math.max(0.75, Math.min(6.0, Math.max(finalDamage, baseDamage) * 0.35));
        int hit = 0;
        try {
            if (primaryTarget.getWorld() != null) {
                primaryTarget.getWorld().spawnParticle(Particle.EXPLOSION, primaryTarget.getLocation().add(0, 0.8, 0), 1, 0, 0, 0, 0);
                primaryTarget.getWorld().spawnParticle(Particle.FLAME, primaryTarget.getLocation().add(0, 0.8, 0), 18, 0.6, 0.4, 0.6, 0.02);
                primaryTarget.getWorld().playSound(primaryTarget.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.35f);
            }
        } catch (Throwable ignored) {}
        for (Entity e : primaryTarget.getNearbyEntities(radius, radius, radius)) {
            if (hit >= 3) break;
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            if (le.getUniqueId().equals(primaryTarget.getUniqueId())) continue;
            if (le.getUniqueId().equals(owner.getUniqueId()) || le.getUniqueId().equals(minion.getUniqueId())) continue;
            try {
                var pdc = le.getPersistentDataContainer();
                if (pdc.has(new NamespacedKey(plugin, "summon_mobtype"), PersistentDataType.STRING)) continue;
            } catch (Throwable ignored) {}
            if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
            try {
                DamageSource src = DamageSource.builder(DamageType.GENERIC).withDirectEntity(minion).withCausingEntity(owner).build();
                le.damage(aoe, src);
                try { progression.recordKillCredit(owner, minion, le, null, nowTick); } catch (Throwable ignored) {}
                hit++;
            } catch (Throwable ignored) {}
        }
        GlovedPlugin.logDebug("[SummonTrait] UNSTABLE proc by minion " + minion.getUniqueId() + " aoe=" + aoe + " extraTargets=" + hit);
    }

    static void moveMobTowards(org.bukkit.entity.Mob mob, Location target, double speedFactor) {
        if (mob == null || target == null) return;
        boolean isFlying = false;
        try {
            isFlying = mob.getAttribute(Attribute.FLYING_SPEED) != null;
        } catch (Exception ignored) {}
        Location current = mob.getLocation();
        if (current.getWorld() != target.getWorld()) return;
        double distance = current.distance(target);
        if (distance < 0.5) {
            if (isFlying) {
                // Flying mobs hover when close
                mob.setVelocity(new Vector(0, 0, 0));
            } else {
                // Stop horizontal movement, keep vertical for gravity
                Vector v = mob.getVelocity();
                mob.setVelocity(new Vector(0, v.getY(), 0));
            }
            return;
        }
        Vector dir = target.toVector().subtract(current.toVector());
        // Flying mobs use full 3D steering; ground mobs use horizontal only
        if (!isFlying) {
            dir.setY(0);
        }
        if (dir.lengthSquared() < 0.0001) {
            if (isFlying) {
                mob.setVelocity(new Vector(0, 0, 0));
            } else {
                // No horizontal direction, try to jump if target is above
                double verticalDelta = target.getY() - current.getY();
                boolean onGround = mob.isOnGround();
                Vector v = mob.getVelocity();
                if (onGround && verticalDelta > 0.4 && v.getY() <= 0.01) {
                    mob.setVelocity(new Vector(0, 0.32, 0));
                } else {
                    mob.setVelocity(new Vector(0, v.getY(), 0));
                }
            }
            return;
        }
        dir.normalize();
        double speed = speedFactor * 0.2;
        Vector vel = dir.multiply(speed);
        if (isFlying) {
            // Flying mobs get full 3D steering
            mob.setVelocity(vel);
        } else {
            // Preserve existing vertical velocity for vanilla gravity/jump
            vel.setY(mob.getVelocity().getY());
            // Allow vanilla jump to work: only nudge up if clearly stuck on uphill
            double verticalDelta = target.getY() - current.getY();
            boolean onGround = mob.isOnGround();
            if (onGround && verticalDelta > 0.1 && mob.getVelocity().getY() <= 0.01) {
                vel.setY(Math.max(vel.getY(), 0.32));
            }
            // Detect stairs/slabs/low walls directly ahead and step up
            if (onGround && mob.getVelocity().getY() <= 0.01) {
                Location ahead = current.clone().add(dir.clone().multiply(0.7));
                org.bukkit.block.Block feetBlock = ahead.getBlock();
                org.bukkit.block.Block aboveBlock = ahead.clone().add(0, 1, 0).getBlock();
                if (feetBlock.getType().isSolid() && !aboveBlock.getType().isSolid()) {
                    vel.setY(Math.max(vel.getY(), 0.42));
                }
            }
            mob.setVelocity(vel);
        }
    }

    static void faceTowards(org.bukkit.entity.Mob mob, Location target) {
        if (mob == null || target == null) return;
        Location from = mob.getLocation();
        double dx = target.getX() - from.getX();
        double dz = target.getZ() - from.getZ();
        double dy = target.getY() - from.getY();
        double distXZ = Math.sqrt(dx*dx + dz*dz);
        float targetYaw = (float)Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float targetPitch = distXZ == 0 ? 0 : (float)Math.toDegrees(Math.atan2(dy, distXZ));
        targetPitch = Math.max(-90f, Math.min(90f, targetPitch));
        try {
            float currentYaw = from.getYaw();
            float currentPitch = from.getPitch();
            // Interpolate yaw with wrap-around
            float yawDiff = targetYaw - currentYaw;
            while (yawDiff > 180f) yawDiff -= 360f;
            while (yawDiff < -180f) yawDiff += 360f;
            float newYaw = currentYaw + yawDiff * 0.35f;
            // Interpolate pitch
            float pitchDiff = targetPitch - currentPitch;
            float newPitch = currentPitch + pitchDiff * 0.45f;
            mob.setRotation(newYaw, newPitch);
        } catch (Exception ignored) {}
    }

    private static double getMobSpeed(org.bukkit.entity.Mob mob) {
        if (mob == null) return 1.0;
        try {
            var attr = mob.getAttribute(Attribute.MOVEMENT_SPEED);
            if (attr != null) {
                double base = attr.getValue();
                // Normalize to Paper's pathfinder speed scale, where 1 is "normal" for that mob.
                // Vanilla mob movement speed is ~0.2-0.35, so this keeps minions at a sane pace
                // instead of launching them at 2.5x and overshooting everything.
                double speed = base / 0.3;
                return Math.max(0.7, Math.min(1.8, speed));
            }
        } catch (Exception ignored) {}
        return 1.2;
    }

    private static void forceFullAttackStrength(Player player) {
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("setCooledAttackStrength", float.class);
            m.invoke(player, 1.0f);
            return;
        } catch (Exception ignored) {}
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            java.lang.reflect.Method getHandle = craftPlayerClass.getMethod("getHandle");
            Object handle = getHandle.invoke(player);
            int cooldownPeriod = (int) player.getCooldownPeriod();
            Class<?> cls = handle.getClass();
            java.lang.reflect.Field f = null;
            while (cls != null) {
                try {
                    f = cls.getDeclaredField("attackStrengthTicker");
                    break;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            if (f != null) {
                f.setAccessible(true);
                f.setInt(handle, cooldownPeriod);
            }
        } catch (Exception ignored) {}
    }

    private static long requiredArcTicks(Player player) {
        double periodTicks;
        try {
            periodTicks = player.getCooldownPeriod();
        } catch (Exception e) {
            periodTicks = 20.0;
        }
        if (periodTicks <= 0) periodTicks = 20.0;
        double minAttackStrength = 0.9;
        try {
            var plugin = GlovedPlugin.getPlugin(GlovedPlugin.class);
            if (plugin != null) {
                var registry = plugin.getWeaponRegistry();
                if (registry != null) {
                    minAttackStrength = registry.getMinAttackStrength();
                }
            }
        } catch (Exception ignored) {}
        double requiredTicks = minAttackStrength * periodTicks - 0.5;
        if (requiredTicks < 0) requiredTicks = 0;
        long ticks = Math.max(1L, Math.round(requiredTicks));
        return ticks;
    }

    /**
     * Minions have a 1/4th chance (of the effective profile chance) to spawn more minions of their
     * own type on hit, following the same cooldown and max-active rules as the player.
     */
    private void trySummonFromMinion(LivingEntity minion, Player owner, LivingEntity target) {
        if (summonManager == null || summonConfig == null) return;
        if (!summonConfig.isMinionProcSummon()) return;
        Player liveOwner = Bukkit.getPlayer(owner.getUniqueId());
        if (liveOwner == null || !liveOwner.isOnline()) return;
        NamespacedKey profileKey = new NamespacedKey(plugin, "summon_profile");
        String profileName = minion.getPersistentDataContainer().get(profileKey, PersistentDataType.STRING);
        if (profileName == null) return;
        SummonProfile profile = summonConfig.getProfile(profileName);
        if (profile == null) return;
        if (!summonManager.canSummon(liveOwner, profile, SummonTrigger.MINION)) return;
        EffectiveSummonStats stats = EffectiveSummonStats.resolve(liveOwner, profile, summonConfig, progression);
        double minionChance = stats.chance * 0.25;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > minionChance) return;
        int spawned = summonManager.spawn(liveOwner, profile, target);
        if (spawned <= 0) return;
        summonManager.setCooldown(liveOwner, profile, SummonTrigger.MINION);
        spawnMinionProcVfx(minion, target, spawned);
        GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " triggered spawn for " + liveOwner.getName()
                + " profile " + profileName + " spawned=" + spawned + " (minion-chance=" + minionChance + ", lvl " + stats.summoningLevel + ")");
    }

    private void spawnMinionProcVfx(LivingEntity minion, LivingEntity target, int spawned) {
        try {
            if (minion == null || minion.getWorld() == null) return;
            org.bukkit.World world = minion.getWorld();
            Location at = minion.getLocation().add(0, 0.9, 0);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 18 + Math.min(spawned * 6, 24), 0.45, 0.55, 0.45, 0.03);
            world.spawnParticle(Particle.REVERSE_PORTAL, at, 16 + Math.min(spawned * 4, 16), 0.4, 0.5, 0.4, 0.08);
            world.spawnParticle(Particle.WITCH, at, 10 + Math.min(spawned * 2, 10), 0.35, 0.4, 0.35, 0.02);
            world.spawnParticle(Particle.END_ROD, at.clone().add(0, 0.5, 0), 8 + Math.min(spawned * 2, 8), 0.25, 0.5, 0.25, 0.01);
            if (target != null && target.getWorld() == world) {
                Location tc = target.getLocation().add(0, 1.0, 0);
                world.spawnParticle(Particle.ELECTRIC_SPARK, tc, 10, 0.25, 0.3, 0.25, 0.03);
            }
            world.playSound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 0.5f, 1.6f);
            world.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.35f, 1.85f);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Failed to play minion proc VFX: " + t);
        }
    }

    /**
     * Vanilla melee reach: {@code (mobWidth * 2)^2 + targetWidth}, matching
     * {@code MeleeAttackGoal#getAttackReachSqr}, plus a 2-block bonus.
     */
    private static double defaultAttackReachSq(LivingEntity mob, LivingEntity target) {
        double width = mob.getWidth();
        double targetWidth = target.getWidth();
        double vanillaReach = Math.sqrt((width * 2.0) * (width * 2.0) + targetWidth);
        double reach = vanillaReach + 2.0;
        return reach * reach;
    }

    public static void applyKnockback(LivingEntity minion, LivingEntity owner, LivingEntity target) {
        // LivingEntity#knockback subtracts the supplied vector from the victim's velocity,
        // so it must point from the victim toward the source (target -> owner) to push the
        // target away from the owner.
        Vector direction = owner.getLocation().toVector().subtract(target.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() < 1.0E-4) {
            // Target is basically on top of the owner: fall back to pushing off the minion.
            direction = minion.getLocation().toVector().subtract(target.getLocation().toVector());
            direction.setY(0);
            if (direction.lengthSquared() < 1.0E-4) return;
        }
        direction.normalize();
        double strength = 0.4;
        try {
            var knockback = minion.getAttribute(Attribute.ATTACK_KNOCKBACK);
            if (knockback != null) strength += Math.max(0, knockback.getValue());
        } catch (Exception ignored) {}
        target.knockback(strength, direction.getX(), direction.getZ());
    }

    /**
     * Shared damage application for minion attacks (melee and ranged).
     * Computes owner-credited, level/soul/trait/strength/ability scaled damage
     * using the same pipeline as custom melee attacks, applies it to the target,
     * and dispatches trait/progression/ability hooks. Returns the final damage.
     */
    public double applyMinionHit(LivingEntity minion, Player owner, LivingEntity target,
                                 double rawBaseDamage, long nowTick, String profileName) {
        double baseDamage = rawBaseDamage;
        try {
            if (summonConfig != null && summonConfig.isStrengthDamageEnabled() && progression != null) {
                var st = progression.resolveOwnerCombatStats(owner.getUniqueId());
                if (st != null && st.available()) {
                    double bonus = Math.max(0.0, st.attackDamageTrait()) * summonConfig.getStrengthDamagePerAttackDamageTrait();
                    bonus = Math.min(bonus, Math.max(0.0, summonConfig.getStrengthDamageMaxBonus()));
                    baseDamage += bonus;
                }
            }
        } catch (Throwable ignored) {}
        // Soul stat modifiers (Phase 4)
        dev.gloved.glovedplugin.summon.soul.SoulInstance soul = resolveMinionSoul(minion);
        var soulService = plugin.getSoulService();
        dev.gloved.glovedplugin.summon.soul.SoulStatSnapshot soulSnap = (soul != null && soulService != null)
                ? soulService.getSnapshot(soul) : dev.gloved.glovedplugin.summon.soul.SoulStatSnapshot.identity();
        double soulDamageMult = Math.max(0.25, Math.min(4.0, soulSnap.damageMultiplier()));
        if (soul != null && soul.hasTrait(dev.gloved.glovedplugin.summon.soul.SoulTrait.FRENZIED)) soulDamageMult *= 1.08;
        if (soul != null && soul.hasTrait(dev.gloved.glovedplugin.summon.soul.SoulTrait.BULWARK)) soulDamageMult *= 0.97;
        if (soul != null && soul.hasTrait(dev.gloved.glovedplugin.summon.soul.SoulTrait.PACKBOUND)) {
            soulDamageMult *= packboundMultiplier(minion, owner, soul);
        }
        baseDamage = Math.max(0.5, baseDamage * soulDamageMult);

        int ownerLevel = EliteMobsIntegration.getCombatLevel(owner);
        boolean isElite = isEliteMob(target);
        double finalDamage = baseDamage;
        boolean crit = false;
        boolean targetDebuffedBefore = false;
        try {
            if (abilityService != null && summonConfig != null) {
                var hr = abilityService.applyCritAndDamage(owner, minion, target, baseDamage, summonConfig, soul, soulSnap);
                finalDamage = hr.damage;
                crit = hr.crit;
                targetDebuffedBefore = hr.targetDebuffedBefore;
            } else {
                DamageSource src = DamageSource.builder(DamageType.GENERIC)
                        .withDirectEntity(minion)
                        .withCausingEntity(owner)
                        .build();
                target.damage(baseDamage, src);
            }
            applyKnockback(minion, owner, target);
            GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " dealt level-scaled damage " + finalDamage + " (base " + baseDamage + ", crit=" + crit + ", soul=" + (soul != null ? soul.getSoulId() : "none") + ") to " + target.getName() + " for player " + owner.getName() + " level=" + ownerLevel + " elite=" + isElite);
            try {
                progression.onMinionHit(owner, minion, target, profileName, finalDamage);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SummonXP] Hit hook dispatch error: " + t);
            }
            try {
                if (abilityService != null && summonConfig != null) {
                    abilityService.onAfterPrimaryHit(owner, minion, target, profileName, finalDamage, baseDamage, crit, targetDebuffedBefore, summonConfig, soul, soulSnap);
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SummonAbility] Ability hook dispatch error: " + t);
            }
            try { applyUnstableTrait(owner, minion, target, soul, finalDamage, baseDamage, nowTick); } catch (Throwable ignored) {}
        } catch (Exception e) {
            GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " damage exception: " + e);
        }
        trySummonFromMinion(minion, owner, target);
        return finalDamage;
    }

    public static boolean isEliteMob(LivingEntity entity) {
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null) return false;
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> eliteClass = Class.forName("com.magmaguy.elitemobs.mobconstructor.EliteEntity", true, cl);
            return eliteClass.isInstance(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyPlayerDamage(Player owner, LivingEntity target) {
        ItemStack weapon = owner.getInventory().getItemInMainHand();
        double baseDamage = 1.0;
        if (owner.getAttribute(Attribute.ATTACK_DAMAGE) != null) {
            baseDamage = owner.getAttribute(Attribute.ATTACK_DAMAGE).getValue();
        }
        double damage = baseDamage;
        if (weapon != null && weapon.getType().getMaxDurability() > 0) {
            var meta = weapon.getItemMeta();
            if (meta != null) {
                for (var entry : meta.getEnchants().entrySet()) {
                    var ench = entry.getKey();
                    int lvl = entry.getValue();
                    String name = ench.getKey().getKey();
                    if (name.equals("sharpness")) {
                        damage += lvl * 0.5 + Math.max(0, lvl - 5) * 0.5;
                    } else if (name.equals("smite") && target instanceof org.bukkit.entity.Monster) {
                        damage += lvl * 2.5;
                    } else if (name.equals("bane_of_arthropods")) {
                        damage += lvl * 2.5;
                    } else if (name.equals("fire_aspect")) {
                        int ticks = lvl * 100;
                        target.setFireTicks(target.getFireTicks() + ticks);
                    }
                }
            }
        }
        // Apply damage with player as damager for credit/loot
        target.damage(damage, owner);
    }
}
