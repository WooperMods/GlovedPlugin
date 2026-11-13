package dev.gloved.glovedplugin.summon.ai.legacy;

import com.destroystokyo.paper.entity.Pathfinder;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.lootr.integration.EliteMobsIntegration;

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
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.ai.movement.ReachabilityService;
import dev.gloved.glovedplugin.summon.ai.movement.SummonMoveGoal;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;
import dev.gloved.glovedplugin.summon.runtime.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.runtime.SummonManager;
import dev.gloved.glovedplugin.summon.runtime.SummonNametag;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import dev.gloved.glovedplugin.summon.progression.NoopSummonProgressionBridge;
import dev.gloved.glovedplugin.summon.progression.SummonAbilityService;
import dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge;

public class SummonAI {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final ReachabilityService reachabilityService;
    private final Map<UUID, UUID> minionTasks = new HashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastAttackTickByMinion = new java.util.concurrent.ConcurrentHashMap<>();
    // Fire-time cooldown consumed for vanilla/projectile attacks where the spawned projectile
    // cannot be tagged directly by the firing code. DamageProxy launch tagging consumes this once
    // to mark the next attributable projectile as cd_consumed so impact conversion does not drop.
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> pendingProjectileCooldownUntilTick = new java.util.concurrent.ConcurrentHashMap<>();
    private SummonManager summonManager;
    private SummonConfig summonConfig;
    private SummonProgressionBridge progression =
            new NoopSummonProgressionBridge();
    private SummonAbilityService abilityService;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> recentDamagers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DAMAGER_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> unreachableCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, org.bukkit.Location>> unreachableLocationCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNREACHABLE_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastWaypointLog = new java.util.concurrent.ConcurrentHashMap<>();
    // Trait ICDs (bounded proc behavior)
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> unstableIcd = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> packboundIcd = new java.util.concurrent.ConcurrentHashMap<>();

    public SummonAI(GlovedPlugin plugin, SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.reachabilityService = new ReachabilityService(plugin);
        startGlobalTask();
    }

    /** Wired after construction to avoid a SummonAI <-> SummonManager cycle. */
    public void setSummon(SummonConfig summonConfig, SummonManager summonManager) {
        this.summonConfig = summonConfig;
        this.summonManager = summonManager;
    }

    public void setProgression(SummonProgressionBridge progression) {
        this.progression = progression != null ? progression : new NoopSummonProgressionBridge();
    }

    public void setAbilityService(SummonAbilityService abilityService) {
        this.abilityService = abilityService;
    }

    public boolean canAttackNow(LivingEntity minion, Player owner, long nowTick) {
        if (minion == null) return false;
        long last = lastAttackTickByMinion.getOrDefault(minion.getUniqueId(), Long.MIN_VALUE / 4);
        return (nowTick - last) >= requiredArcTicks(owner, minion);
    }

    public boolean tryConsumeAttackCooldown(LivingEntity minion, Player owner, long nowTick) {
        if (!canAttackNow(minion, owner, nowTick)) return false;
        recordAttack(minion, nowTick);
        return true;
    }

    public void recordAttack(LivingEntity minion, long nowTick) {
        if (minion == null) return;
        lastAttackTickByMinion.put(minion.getUniqueId(), nowTick);
    }

    public void resetAttackCooldown(LivingEntity minion) {
        if (minion == null) return;
        lastAttackTickByMinion.remove(minion.getUniqueId());
    }

    public void clearAttackCooldown(LivingEntity minion) {
        resetAttackCooldown(minion);
        try {
            if (minion != null) pendingProjectileCooldownUntilTick.remove(minion.getUniqueId());
        } catch (Throwable ignored) {}
    }

    /**
     * Records that this minion's current attack cooldown was consumed at projectile fire time.
     * The next projectile launched/tagged for this minion within a short window will be marked
     * {@code summon_cd_consumed} so hit conversion can apply damage without re-consuming.
     */
    public void markPendingProjectileCooldownConsumed(LivingEntity minion, long nowTick) {
        if (minion == null) return;
        // Short TTL: enough for delayed projectile spawn/launch events, but bounded.
        pendingProjectileCooldownUntilTick.put(minion.getUniqueId(), nowTick + 10L);
    }

    /**
     * One-shot claim of a pending fire-time cooldown mark for projectile launch tagging.
     */
    public boolean pollPendingProjectileCooldownConsumed(UUID minionId, long nowTick) {
        if (minionId == null) return false;
        Long until = pendingProjectileCooldownUntilTick.get(minionId);
        if (until == null) return false;
        if (nowTick > until) {
            pendingProjectileCooldownUntilTick.remove(minionId, until);
            return false;
        }
        return pendingProjectileCooldownUntilTick.remove(minionId, until);
    }

    private SoulInstance resolveMinionSoul(LivingEntity minion) {
        try {
            if (minion == null || plugin.getSoulService() == null) return null;
            NamespacedKey k = new NamespacedKey(plugin, "summon_soul_id");
            String id = minion.getPersistentDataContainer().get(k, PersistentDataType.STRING);
            if (id == null || id.isBlank()) return null;
            return plugin.getSoulService().getSoulCached(java.util.UUID.fromString(id));
        } catch (Throwable ignored) { return null; }
    }

    private SoulStatSnapshot resolveMinionSnapshot(LivingEntity minion) {
        try {
            if (minion == null || plugin.getSoulService() == null) return SoulStatSnapshot.identity();
            NamespacedKey k = new NamespacedKey(plugin, "summon_soul_id");
            String id = minion.getPersistentDataContainer().get(k, PersistentDataType.STRING);
            if (id == null || id.isBlank()) return SoulStatSnapshot.identity();
            return plugin.getSoulService().getSnapshot(java.util.UUID.fromString(id));
        } catch (Throwable ignored) { return SoulStatSnapshot.identity(); }
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
            if (mobEnt.getTarget().getUniqueId().equals(minion.getUniqueId())) {
                return TargetIntent.CHASING; // hitting back at our attacker - survival first
            }
            if (mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId())) {
                return TargetIntent.PEELING;
            }
        }
        double targetHpPct = target.getHealth() / target.getMaxHealth();
        if (targetHpPct < TARGET_NEAR_DEAD_PCT) return TargetIntent.FINISHING;
        return TargetIntent.CHASING;
    }

    /**
     * Score a candidate target for a minion. Self-preservation always comes first:
     * a minion that is being attacked will handle that threat before anything else,
     * regardless of HP. When not under immediate threat, the balance between
     * self-preservation and owner-protection scales with current HP:
     *
     *   - At full HP: protect the owner at all costs (except death to self).
     *   - As HP drops: self-preservation becomes increasingly important.
     *   - At near-death: stay defensive, don't over-extend, prioritize survival.
     *
     * Priority stack:
     * 1. Actively attacking this minion (survival - always #1)
     * 2. Close to the minion (immediate self-defense threat)
     * 3. Recently damaged this minion
     * 4. Actively attacking the owner (peel threat off player)
     * 5. Recently damaged the owner
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
        boolean ownerLowHp = ownerHpPct < OWNER_LOW_HP_PCT;

        // Self-preservation weight scales inversely with minion HP:
        // full HP -> low self weight (protect owner), near-death -> high self weight (survive)
        double selfWeight = 1.0 - minionHpPct; // 0.0 at full HP, 1.0 at 0 HP
        double ownerWeight = minionHpPct;      // 1.0 at full HP, 0.0 at 0 HP

        double d2Owner = tLoc.distanceSquared(oLoc);
        double d2Minion = mLoc.distanceSquared(tLoc);

        // 1. Actively attacking this minion - survival is ALWAYS #1
        if (target instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
            UUID mobTarget = mobEnt.getTarget().getUniqueId();
            if (mobTarget.equals(minion.getUniqueId())) {
                score += 15_000_000.0; // attacking me - must handle first, always
            } else if (mobTarget.equals(owner.getUniqueId())) {
                // attacking owner - peel, weighted by how much HP we have to spare
                score += 10_000_000.0 * ownerWeight; // full HP -> full peel urgency
                if (ownerLowHp) score += 3_000_000.0 * ownerWeight; // owner dying
            }
        }

        // 2. Close to the minion - immediate self-defense threat (always high priority)
        double closeToMinionBonus = Math.max(0.0, 8.0 - Math.sqrt(d2Minion)) / 8.0; // 1.0 at melee, 0 at 8 blocks
        score += closeToMinionBonus * 12_000_000.0; // right next to me = huge priority

        // 3. Recently damaged this minion (hit back for survival)
        if (SummonAI.getRecentDamagers(minion).contains(tid)) {
            score += 5_000_000.0 * (0.5 + selfWeight);
        }

        // 4. Recently damaged the owner
        if (recentDamagerIds != null && recentDamagerIds.contains(tid)) {
            score += 2_000_000.0 * ownerWeight;
        }

        // 5. Close to the owner - owner is in danger (bodyguard radius ~6 blocks)
        if (d2Owner <= BODYGUARD_RADIUS_SQ) {
            double ownerDanger = (1.0 - d2Owner / BODYGUARD_RADIUS_SQ); // 1.0 right on owner
            score += ownerDanger * 2_000_000.0 * ownerWeight;
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

    public void monitorSummon(LivingEntity minion, Player owner, SummonProfile profile) {
        if (minion == null || owner == null) return;
        // Stagger initial attack timing so multiple minions don't sync their swings.
        try {
            long now = Bukkit.getCurrentTick();
            long req = requiredArcTicks(owner, minion);
            long offset = req <= 1 ? 0 : java.util.concurrent.ThreadLocalRandom.current().nextLong(req);
            lastAttackTickByMinion.put(minion.getUniqueId(), now - offset);
        } catch (Throwable ignored) {}
        new BukkitRunnable() {
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
            // Movement speed tracking (debug): estimate actual bps while moving, throttled.
            Location speedTrackLoc = null;
            long speedTrackTick = -1L;
            double speedTrackDist = 0.0;
            long speedTrackTicks = 0L;
            long speedTrackLastLogTick = -1000L;

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
                    clearAttackCooldown(minion);
                    cancel();
                    return;
                }
                if (owner == null || !owner.isOnline()) {
                    clearAttackCooldown(minion);
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
                    speedTrackLoc = null; speedTrackTick = -1L; speedTrackDist = 0.0; speedTrackTicks = 0L;
                    teleportNearPlayer(owner, minion);
                    return;
                }
                // Owner-priority recall: even with an active attack target, don't let summons get stranded far from owner.
                try {
                    if (minion.getLocation().distanceSquared(owner.getLocation()) > 1024.0D) { // > 32 blocks
                        GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " too far from owner " + owner.getName() + " while targeting/following, recalling");
                        try {
                            if (previousAssignmentTarget != null) {
                                clearTargetAssignment(owner.getUniqueId(), previousAssignmentTarget);
                                previousAssignmentTarget = null;
                            }
                        } catch (Throwable ignored) {}
                        lastTarget = null;
                        lastTargetDist = Double.MAX_VALUE;
                        stuckSince = 0L;
                        try { mob.setTarget(null); } catch (Throwable ignored) {}
                        try { clearAttackCooldown(minion); } catch (Throwable ignored) {}
                        teleportNearPlayer(owner, minion);
                        return;
                    }
                } catch (Throwable ignored) {}

                // Throttled movement-speed debug tracking (actual horizontal bps).
                if (GlovedPlugin.getPlugin(GlovedPlugin.class) != null && DebugLog.isEnabled()) {
                    long sampleNow = Bukkit.getCurrentTick();
                    Location cur = mob.getLocation();
                    if (speedTrackLoc != null && speedTrackTick >= 0
                            && speedTrackLoc.getWorld() != null && cur.getWorld() != null
                            && speedTrackLoc.getWorld().equals(cur.getWorld())) {
                        long dt = Math.max(0L, sampleNow - speedTrackTick);
                        if (dt > 0L) {
                            double dx = cur.getX() - speedTrackLoc.getX();
                            double dz = cur.getZ() - speedTrackLoc.getZ();
                            double dist = Math.sqrt(dx * dx + dz * dz);
                            // Ignore likely teleports/warps.
                            if (dist <= 8.0) {
                                speedTrackDist += dist;
                                speedTrackTicks += dt;
                            } else {
                                speedTrackDist = 0.0;
                                speedTrackTicks = 0L;
                            }
                        }
                    } else {
                        speedTrackDist = 0.0;
                        speedTrackTicks = 0L;
                    }
                    speedTrackLoc = cur.clone();
                    speedTrackTick = sampleNow;
                    if (speedTrackTicks >= 20L && (sampleNow - speedTrackLastLogTick) >= 40L) {
                        double secs = speedTrackTicks / 20.0;
                        double actualBps = secs > 0.0 ? (speedTrackDist / secs) : 0.0;
                        if (speedTrackDist >= 0.15 && actualBps >= 0.05) {
                            double desiredBps = -1.0;
                            double attr = -1.0;
                            try {
                                var k = new NamespacedKey(plugin, "summon_move_bps");
                                Double v = mob.getPersistentDataContainer().get(k, PersistentDataType.DOUBLE);
                                if (v != null) desiredBps = v;
                            } catch (Throwable ignored) {}
                            try {
                                var a = mob.getAttribute(Attribute.MOVEMENT_SPEED);
                                if (a != null) attr = a.getValue();
                            } catch (Throwable ignored) {}
                            if (desiredBps < 0.0 && attr > 0.0) desiredBps = attr * 43.17;
                            GlovedPlugin.logDebug(String.format(java.util.Locale.ROOT,
                                    "[SummonSpeed] %s actual=%.2f bps desired=%.2f bps attr=%.4f intent=%s target=%s dist=%.2f over %.2fs",
                                    mob.getUniqueId(), actualBps, desiredBps, attr, currentIntent,
                                    (mob.getTarget() != null ? mob.getTarget().getType().name() : "none"),
                                    speedTrackDist, secs));
                            speedTrackLastLogTick = sampleNow;
                        }
                        speedTrackDist = 0.0;
                        speedTrackTicks = 0L;
                    }
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
                String combatMode = "AGGRESSIVE";
                try {
                    if (plugin.getSoulService() != null) combatMode = plugin.getSoulService().getSummonCombatMode(owner.getUniqueId());
                } catch (Throwable ignored) {}
                boolean defensiveMode = "DEFENSIVE".equalsIgnoreCase(combatMode);
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
                    boolean relevant = defensiveMode ? (targetsOwner || targetsMinion) : (isHostile || targetsOwner || targetsMinion);
                    if (relevant) {
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
                    boolean relevant = defensiveMode ? targetsMinion : (isHostile || targetsMinion);
                    if (relevant) {
                        if (!candidates.contains(le)) candidates.add(le);
                    }
                }
                for (UUID damagerId : SummonAI.getRecentDamagers(owner)) {
                    recentDamagerIds.add(damagerId);
                    Entity e = owner.getWorld().getEntity(damagerId);
                    if (e instanceof LivingEntity le && le != owner && !le.isDead() && !tracker.isSummoned(le.getUniqueId())
                            && !CombatTargetRules.isProtected(plugin, owner, le)) {
                        if (!candidates.contains(le)) candidates.add(le);
                    }
                }
                // Entities that recently damaged this minion get priority targeting so minions hit back.
                for (UUID damagerId : SummonAI.getRecentDamagers(minion)) {
                    recentDamagerIds.add(damagerId);
                    Entity e = minion.getWorld().getEntity(damagerId);
                    if (e instanceof LivingEntity le && le != owner && !le.isDead() && !tracker.isSummoned(le.getUniqueId())
                            && !CombatTargetRules.isProtected(plugin, owner, le)) {
                        if (!candidates.contains(le)) candidates.add(le);
                    }
                }
                // Do not target enemies past 32 blocks from the owner (owner leash).
                double maxTargetFromOwnerSq = 1024.0D; // 32 blocks
                candidates.removeIf(le -> {
                    if (le.getWorld() != owner.getWorld()) return true;
                    double d2Owner = le.getLocation().distanceSquared(owner.getLocation());
                    return d2Owner > maxTargetFromOwnerSq;
                });

                // Per-minion target selection: filter by reachability, score by smart owner-defensive
                // priority (HP-aware, target-HP-aware, swarm-aware), pick best target with stickiness.
                // Track target assignments for swarm avoidance across minions of the same owner.
                List<ScoredTarget> scored = new ArrayList<>();
                double maxChaseSq = SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE;
                double maxOwnerLeashSq = 1024.0D; // 32 blocks from owner
                int unreachableFiltered = 0;
                int dyFiltered = 0;
                int distFiltered = 0;
                for (LivingEntity le : candidates) {
                    UUID id = le.getUniqueId();
                    Entity te = owner.getWorld().getEntity(id);
                    if (!(te instanceof LivingEntity tle)) continue;
                    // Reachability pre-filter: never pathfind at unreachable enemies.
                    if (!reachabilityService.canReachEntity(mob, tle)) {
                        SummonAI.cacheUnreachable(minion.getUniqueId(), id, tle.getLocation());
                        unreachableFiltered++;
                        continue;
                    }
                    try {
                        if (tle.getWorld() == owner.getWorld() && tle.getLocation().distanceSquared(owner.getLocation()) > maxOwnerLeashSq) {
                            distFiltered++;
                            continue;
                        }
                    } catch (Throwable ignored) {}
                    if (!isFlyingMob) {
                        double dy = Math.abs(minion.getLocation().getY() - tle.getLocation().getY());
                        if (dy > 16) { dyFiltered++; continue; }
                    }
                    double d2 = minion.getLocation().distanceSquared(tle.getLocation());
                    if (d2 > maxChaseSq) { distFiltered++; continue; }
                    double score = scoreTarget(owner, minion, tle, recentDamagerIds);
                    scored.add(new ScoredTarget(tle, score));
                }
                scored.sort((a, b) -> {
                    int c = Double.compare(b.score, a.score);
                    if (c != 0) return c;
                    return a.id.compareTo(b.id);
                });

                if (GlovedPlugin.getPlugin(GlovedPlugin.class) != null && DebugLog.isEnabled()
                        && (Bukkit.getCurrentTick() % 40 == 0 || (!scored.isEmpty() && lastTarget == null))) {
                    GlovedPlugin.logDebug("[SummonAI] " + minion.getUniqueId() + " (" + minion.getType() + ") owner=" + owner.getName()
                            + " candidates=" + candidates.size() + " scored=" + scored.size()
                            + " unreachable=" + unreachableFiltered + " dy=" + dyFiltered + " dist=" + distFiltered
                            + " lastTarget=" + (lastTarget != null ? lastTarget.getName() : "none"));
                }

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
                    if (lastTarget == null || !target.getUniqueId().equals(lastTarget.getUniqueId())) {
                        GlovedPlugin.logDebug("[SummonAI] " + minion.getUniqueId() + " acquired target " + target.getType() + "/" + target.getName()
                                + " at " + String.format(java.util.Locale.ROOT, "%.2f", minion.getLocation().distance(target.getLocation())) + " blocks");
                    }
                    lastTarget = target;
                    lastTargetTick = now;
                } else {
                    // Fallback to memory briefly if no scored target but recent target may still be valid
                    boolean lastTargetWithinOwnerLeash = false;
                    try {
                        lastTargetWithinOwnerLeash = lastTarget != null && lastTarget.getWorld() == owner.getWorld()
                                && lastTarget.getLocation().distanceSquared(owner.getLocation()) <= 1024.0D;
                    } catch (Throwable ignored) {}
                    if (lastTarget != null && now - lastTargetTick < 40 && !lastTarget.isDead()
                            && !CombatTargetRules.isProtected(plugin, owner, lastTarget)
                            && lastTargetWithinOwnerLeash) {
                        target = lastTarget;
                    } else {
                        if (lastTarget != null) {
                            GlovedPlugin.logDebug("[SummonAI] " + minion.getUniqueId() + " lost target (no valid candidates/memory expired)");
                        }
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
                    resetAttackCooldown(minion);
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
                                    SummonAI.cacheUnreachable(minion.getUniqueId(), target.getUniqueId(), target.getLocation());

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
                    boolean slimeContactCombat = minion instanceof org.bukkit.entity.Slime
                            || minion instanceof org.bukkit.entity.MagmaCube;
                    if (!slimeContactCombat) {
                        // Movement is handled by SummonMoveGoal using Paper's pathfinder.
                        double distSq = minion.getLocation().distanceSquared(target.getLocation());
                        double attackRangeSq = defaultAttackReachSq(minion, target);
                        long reqTicks = requiredArcTicks(owner, minion);
                        long lastAttackTick = lastAttackTickByMinion.getOrDefault(minion.getUniqueId(), Long.MIN_VALUE / 4);
                        long cdDiff = now - lastAttackTick;
                        GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " target=" + target.getType() + "/" + target.getName()
                                + " distSq=" + String.format(java.util.Locale.ROOT, "%.2f", distSq)
                                + " attackRangeSq=" + String.format(java.util.Locale.ROOT, "%.2f", attackRangeSq)
                                + " cd=" + cdDiff + "/" + reqTicks);
                        if (distSq <= attackRangeSq && cdDiff >= reqTicks) {
                            if (CombatTargetRules.isProtected(plugin, owner, target)) {
                                recordAttack(minion, now);
                                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " skipped protected target " + target.getName());
                            } else {
                                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " ATTACK -> " + target.getType() + "/" + target.getName());
                                // Unified per-summon attack helper: cooldown/protection handled above for this path,
                                // damage/calc/procs/soul-stats/abilities/knockback applied via central pipeline.
                                double finalDamage = performSummonAttack(minion, owner, target, now,
                                        AttackOptions.defaults()
                                                .profileName(profile != null ? profile.name : null)
                                                .swingAnimation(true)
                                                .forceOwnerAttackStrength(true)
                                                .recordCooldownAfterHit(true));
                                int ndt = -1;
                                boolean inv = false;
                                try { ndt = target.getNoDamageTicks(); } catch (Throwable ignored) {}
                                try { inv = target.isInvulnerable(); } catch (Throwable ignored) {}
                                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " attack result damage=" + finalDamage
                                        + " targetHealthAfter=" + (target.isDead() ? "dead" : target.getHealth())
                                        + " targetNoDamageTicks=" + ndt + " targetInvulnerable=" + inv);
                            }
                        } else if (distSq > attackRangeSq) {
                            if (now % 20 == 0) {
                                GlovedPlugin.logDebug("[SummonAI] " + minion.getUniqueId() + " out of range of " + target.getName() + " (" + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)) + " blocks)");
                            }
                        } else {
                            if (now % 20 == 0) {
                                GlovedPlugin.logDebug("[SummonAI] " + minion.getUniqueId() + " attack on cooldown for " + target.getName() + " (" + cdDiff + "/" + reqTicks + " ticks)");
                            }
                        }
                    }
                } else if (target == null) {
                    // CombatPets-style: follow goals handle pathing/teleporting (>32 blocks or different world).
                    if (minion.getWorld() != owner.getWorld()) {
                        try { minion.teleport(owner.getLocation()); } catch (Throwable ignored) {}
                    }
                }

                // Live LM-style nametag (rarity badge + current HP), updated every AI tick.
                try {
                    boolean nametagEnabled = false;
                    try {
                        var scfg = plugin != null ? plugin.getSummonConfig() : null;
                        nametagEnabled = scfg != null && scfg.getSoulConfig() != null && scfg.getSoulConfig().isNametagEnabled();
                    } catch (Throwable ignored) {}
                    if (nametagEnabled) {
                        var soul = resolveMinionSoul(minion);
                        var rarity = soul != null ? soul.getRarity() : null;
                        String nameTag = SummonNametag.format(profile, owner, owner != null ? owner.getName() : "Owner", minion, rarity);
                        if (nameTag != null && !nameTag.isEmpty()) {
                            minion.setCustomName(nameTag);
                            minion.setCustomNameVisible(true);
                        }
                    } else {
                        // Custom health nametags toggled off; hide and clear to avoid stale HP names.
                        try { minion.setCustomName(null); } catch (Throwable ignored) {}
                        try { minion.setCustomNameVisible(false); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "[Summon] SummonAI tick error for " + (minion != null ? minion.getUniqueId() : "unknown") + " (suppressed)", t);
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

    private double packboundMultiplier(LivingEntity minion, Player owner, SoulInstance soul) {
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

    private void applyUnstableTrait(Player owner, LivingEntity minion, LivingEntity primaryTarget, SoulInstance soul, double finalDamage, double baseDamage, long nowTick) {
        if (owner == null || minion == null || primaryTarget == null || soul == null) return;
        if (!soul.hasTrait(SoulTrait.UNSTABLE)) return;
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
                try { if (le.getNoDamageTicks() > 0) le.setNoDamageTicks(0); } catch (Throwable ignored) {}
                DamageSource src = DamageSource.builder(DamageType.GENERIC)
                        .withDirectEntity(minion)
                        .withCausingEntity(minion != null ? minion : owner)
                        .build();
                try {
                    DamageProxy.beginPipelineDamage();
                    le.damage(aoe, src);
                } finally {
                    DamageProxy.endPipelineDamage();
                }
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
        return requiredArcTicks(player, null);
    }

    private static long requiredArcTicks(Player player, LivingEntity minion) {
        double aps = -1.0;
        // Prefer the minion's own attack speed (APS) for custom attacks.
        if (minion != null) {
            try {
                var attr = minion.getAttribute(Attribute.ATTACK_SPEED);
                if (attr != null && attr.getValue() > 0.0) {
                    aps = attr.getValue();
                }
            } catch (Exception ignored) {}
            // Convert legacy/vanilla-scale values to APS if encountered.
            if (aps > 2.0) {
                aps = SoulBaseStats.normalizeAttackSpeedAps(aps);
            }
            // If attribute is missing/unreadable, do NOT fall back to the owner's weapon cooldown;
            // use the default minion APS so attack cadence respects soul attack speed semantics.
            if (aps <= 0.0) {
                aps = SoulBaseStats.AVG_ATTACK_SPEED_APS;
            }
        } else if (player != null) {
            // Legacy/player-based path (non-minion callers): approximate APS from cooldown period.
            try {
                double period = player.getCooldownPeriod();
                if (period > 0.0) aps = 20.0 / period;
            } catch (Exception ignored) {}
        }

        double periodTicks = -1.0;
        if (aps > 0.0) {
            if (aps > 2.0) aps = SoulBaseStats.normalizeAttackSpeedAps(aps);
            aps = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS,
                    Math.min(SoulBaseStats.MAX_ATTACK_SPEED_APS, aps));
            periodTicks = 20.0 / aps;
        }
        if (periodTicks <= 0.0) periodTicks = 20.0;

        // Minions: APS is the allowed attacks-per-second rate, so cooldown is the APS period directly.
        // (Player combat uses min_attack_strength gating separately; applying it here makes summons slower than their APS stat.)
        if (minion != null) {
            return Math.max(1L, Math.round(periodTicks));
        }

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
        return Math.max(1L, Math.round(requiredTicks));
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

    /** Alias for NMS goal integration. */
    public double applySummonHit(LivingEntity summon, Player owner, LivingEntity target,
                                 double rawBaseDamage, long nowTick, String profileName) {
        return applyMinionHit(summon, owner, target, rawBaseDamage, nowTick, profileName);
    }

    /**
     * Unified summon attack execution (central helper): resolves summon context, enforces cooldown/protection,
     * runs animation hook, and applies soul-stat/crit/ability/progression damage through the pipeline.
     * Intended to be called from each minion's brain/AI/goal logic so attack behavior is consistent.
     */
    public double performSummonAttack(LivingEntity minion, Player owner, LivingEntity target, long nowTick, AttackOptions options) {
        AttackOptions opt = options != null ? options : AttackOptions.defaults();
        if (minion == null || owner == null || target == null || minion.isDead() || target.isDead()) return 0.0;
        try {
            if (CombatTargetRules.isProtected(plugin, owner, target)) {
                if (opt.consumeCooldownOnProtected) recordAttack(minion, nowTick);
                GlovedPlugin.logDebug("[SummonAttack] Skipped protected target minion=" + minion.getUniqueId()
                        + " target=" + target.getType() + "/" + target.getName() + " owner=" + owner.getName());
                return 0.0;
            }
            if (opt.requireAndConsumeCooldown && !tryConsumeAttackCooldown(minion, owner, nowTick)) {
                return 0.0;
            }
            if (!opt.requireAndConsumeCooldown && opt.recordCooldownAfterHit) {
                // caller manages gating but wants consistent recording
            }
            String profileName = opt.profileName;
            if ((profileName == null || profileName.isBlank())) {
                profileName = resolveMinionProfileName(minion);
            }
            if (opt.swingAnimation) {
                try { minion.swingMainHand(); } catch (Throwable ignored) {}
            }
            if (opt.forceOwnerAttackStrength) {
                try { forceFullAttackStrength(owner); } catch (Throwable ignored) {}
            }
            double dealt = applyMinionHit(minion, owner, target, opt.rawBaseDamage, nowTick, profileName);
            if (!opt.requireAndConsumeCooldown && opt.recordCooldownAfterHit) {
                recordAttack(minion, nowTick);
            }
            if (GlovedPlugin.getPlugin(GlovedPlugin.class) != null && DebugLog.isEnabled()) {
                int ndt = -1; boolean inv = false;
                try { ndt = target.getNoDamageTicks(); } catch (Throwable ignored) {}
                try { inv = target.isInvulnerable(); } catch (Throwable ignored) {}
                GlovedPlugin.logDebug("[SummonAttack] result minion=" + minion.getUniqueId() + " target=" + target.getType() + "/" + target.getName()
                        + " dealt=" + dealt + " hpAfter=" + (target.isDead() ? "dead" : target.getHealth())
                        + " ndt=" + ndt + " inv=" + inv + " profile=" + profileName);
            }
            return dealt;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonAttack] Error performing attack for " + minion.getUniqueId() + ": " + t);
            return 0.0;
        }
    }

    public double performSummonAttack(LivingEntity minion, Player owner, LivingEntity target, long nowTick) {
        return performSummonAttack(minion, owner, target, nowTick, AttackOptions.defaults());
    }

    private String resolveMinionProfileName(LivingEntity minion) {
        try {
            if (minion == null || plugin == null) return null;
            var pdc = minion.getPersistentDataContainer();
            String v = pdc.get(new NamespacedKey(plugin, "summon_profile"), PersistentDataType.STRING);
            if (v == null || v.isBlank()) v = pdc.get(new NamespacedKey(plugin, "summon_family"), PersistentDataType.STRING);
            return v;
        } catch (Throwable ignored) { return null; }
    }

    public static final class AttackOptions {
        public double rawBaseDamage = 0.0;
        public String profileName = null;
        public boolean requireAndConsumeCooldown = false;
        public boolean recordCooldownAfterHit = false;
        public boolean consumeCooldownOnProtected = false;
        public boolean swingAnimation = false;
        public boolean forceOwnerAttackStrength = false;

        public static AttackOptions defaults() { return new AttackOptions(); }
        public static AttackOptions meleePipeline() {
            AttackOptions o = new AttackOptions();
            o.requireAndConsumeCooldown = true;
            o.swingAnimation = true;
            o.forceOwnerAttackStrength = true;
            o.consumeCooldownOnProtected = true;
            return o;
        }
        public AttackOptions rawBaseDamage(double v) { this.rawBaseDamage = v; return this; }
        public AttackOptions profileName(String v) { this.profileName = v; return this; }
        public AttackOptions requireAndConsumeCooldown(boolean v) { this.requireAndConsumeCooldown = v; return this; }
        public AttackOptions recordCooldownAfterHit(boolean v) { this.recordCooldownAfterHit = v; return this; }
        public AttackOptions consumeCooldownOnProtected(boolean v) { this.consumeCooldownOnProtected = v; return this; }
        public AttackOptions swingAnimation(boolean v) { this.swingAnimation = v; return this; }
        public AttackOptions forceOwnerAttackStrength(boolean v) { this.forceOwnerAttackStrength = v; return this; }
    }

    /**
     * Shared damage application for minion attacks (melee and ranged).
     * Uses the soul's effective base attack damage (raw/enhanced soul stat) as the foundation,
     * then applies owner/target/affix/trait/crit modifiers. Returns the final damage.
     */
    public double applyMinionHit(LivingEntity minion, Player owner, LivingEntity target,
                                 double rawBaseDamage, long nowTick, String profileName) {
        SoulInstance soul = resolveMinionSoul(minion);
        var soulService = plugin.getSoulService();
        SoulStatSnapshot soulSnap = (soul != null && soulService != null)
                ? soulService.getSnapshot(soul) : SoulStatSnapshot.identity();

        int ownerLevel = EliteMobsIntegration.getCombatLevel(owner);
        boolean isElite = isEliteMob(target);

        // Core base: soul's effective (enhanced) attack stat, falling back to the mob's attack attribute or legacy base.
        double soulBaseAtk;
        if (soul != null && soulService != null) {
            soulBaseAtk = soulService.effectiveBaseStats(soul).attackDamage();
        } else if (minion != null && minion.getAttribute(Attribute.ATTACK_DAMAGE) != null) {
            soulBaseAtk = minion.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue();
        } else {
            soulBaseAtk = Math.max(0.5, rawBaseDamage);
        }
        // Elite-target bonus (preserves level scaling vs elite mobs).
        double eliteBonus = isElite ? Math.max(0.0, ownerLevel * 0.4) : 0.0;

        GlovedPlugin.logDebug("[SummonHit] begin minion=" + (minion != null ? minion.getUniqueId() : "null")
                + " owner=" + (owner != null ? owner.getName() : "null")
                + " target=" + (target != null ? target.getType() + "/" + target.getName() : "null")
                + " soulBaseAtk=" + soulBaseAtk + " eliteBonus=" + eliteBonus + " rawBaseArg=" + rawBaseDamage
                + " profile=" + profileName
                + " abilityService=" + (abilityService != null) + " summonConfig=" + (summonConfig != null));

        double baseDamage = Math.max(0.5, soulBaseAtk + eliteBonus);

        // Owner strength bonus (AuraSkills attack damage trait)
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

        // Soul damage multipliers: affix (BRUTAL via snapshot) + combat traits
        double soulDamageMult = Math.max(0.25, Math.min(4.0, soulSnap.damageMultiplier()));
        if (soul != null && soul.hasTrait(SoulTrait.FRENZIED)) soulDamageMult *= 1.08;
        if (soul != null && soul.hasTrait(SoulTrait.PACKBOUND)) {
            soulDamageMult *= packboundMultiplier(minion, owner, soul);
        }
        baseDamage = Math.max(0.5, baseDamage * soulDamageMult);

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
                try { if (target.getNoDamageTicks() > 0) target.setNoDamageTicks(0); } catch (Throwable ignored) {}
                DamageSource src = DamageSource.builder(DamageType.GENERIC)
                        .withDirectEntity(minion)
                        .withCausingEntity(minion != null ? minion : owner)
                        .build();
                GlovedPlugin.logDebug("[SummonHit] applying fallback damage path base=" + baseDamage + " to " + target.getName());
                try {
                    DamageProxy.beginPipelineDamage();
                    target.damage(baseDamage, src);
                } finally {
                    DamageProxy.endPipelineDamage();
                }
            }
            applyKnockback(minion, owner, target);
            GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " dealt level-scaled damage " + finalDamage + " (base " + baseDamage + ", soulBaseAtk=" + soulBaseAtk + ", crit=" + crit + ", soul=" + (soul != null ? soul.getSoulId() : "none") + ") to " + target.getName() + " for player " + owner.getName() + " level=" + ownerLevel + " elite=" + isElite);
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
        // Apply damage with player as damager (player action, not summon damage).
        target.damage(damage, owner);
    }
}
