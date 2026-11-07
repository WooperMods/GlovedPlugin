package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.Pathfinder;
import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class MinionAI {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final ReachabilityService reachabilityService;
    private final Map<UUID, UUID> minionTasks = new HashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> recentDamagers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DAMAGER_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> unreachableCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, org.bukkit.Location>> unreachableLocationCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNREACHABLE_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastWaypointLog = new java.util.concurrent.ConcurrentHashMap<>();

    public MinionAI(GlovedPlugin plugin, SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.reachabilityService = new ReachabilityService(plugin);
        startGlobalTask();
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
                if (cachedLoc.distanceSquared(currentLoc) > 0.01) {
                    // Target moved, invalidate cache
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
                if (mob.getLocation().distanceSquared(goal) <= 4.0) {
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
                if (minion.isDead() || !tracker.isSummoned(minion.getUniqueId())) {
                    cancel();
                    return;
                }
                org.bukkit.entity.Mob mob = minion instanceof org.bukkit.entity.Mob m ? m : null;
                if (mob == null) {
                    // Non-mob entities just follow owner
                    if (minion.getLocation().distanceSquared(owner.getLocation()) > profile.followRange * profile.followRange) {
                        GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " non-mob too far from owner " + owner.getName() + ", teleporting");
                        minion.teleport(owner.getLocation().add((Math.random()-0.5)*2,0,(Math.random()-0.5)*2));
                    }
                    return;
                }
                boolean flying;
                try {
                    flying = mob.getAttribute(Attribute.FLYING_SPEED) != null;
                } catch (Exception ignored) {
                    flying = false;
                }
                final boolean isFlyingMob = flying;
                // Clear any vanilla target to avoid AI conflicts
                try {
                    mob.setTarget(null);
                } catch (Exception ignored) {}

                // Resolve target with distribution
                LivingEntity target = null;
                List<LivingEntity> candidates = new ArrayList<>();
                // Player look target
                try {
                    Entity targetEntity = owner.getTargetEntity(30, true);
                    if (targetEntity instanceof LivingEntity le && le != owner && !tracker.isSummoned(le.getUniqueId()) && !le.isDead()) {
                        candidates.add(le);
                    }
                } catch (Exception ignored) {}
                // Nearby hostile entities targeting player or generic monsters
                double scanDist = profile.followRange;
                for (Entity e : owner.getNearbyEntities(scanDist, scanDist, scanDist)) {
                    if (!(e instanceof LivingEntity le)) continue;
                    if (le == owner || le.isDead() || tracker.isSummoned(le.getUniqueId())) continue;
                    boolean isHostile = le instanceof org.bukkit.entity.Monster;
                    boolean targetsOwner = false;
                    if (e instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
                        targetsOwner = mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId());
                    }
                    if (isHostile || targetsOwner) {
                        if (!candidates.contains(le)) {
                            candidates.add(le);
                        }
                    }
                }
                // Add recent damagers for neutral mobs that attacked player
                for (UUID damagerId : MinionAI.getRecentDamagers(owner)) {
                    Entity e = owner.getWorld().getEntity(damagerId);
                    if (e instanceof LivingEntity le && le != owner && !le.isDead() && !tracker.isSummoned(le.getUniqueId())) {
                        if (!candidates.contains(le)) {
                            candidates.add(le);
                        }
                    }
                }
                // Filter candidates to those within the teleport-back / follow range of the owner
                double maxTargetSq = profile.followRange * profile.followRange;
                candidates.removeIf(le -> le.getLocation().distanceSquared(owner.getLocation()) > maxTargetSq);
                // Cheap pre-filter only. Reachability is validated once per acquired target further down,
                // because running a full A* search for every candidate every 2 ticks is very expensive.
                if (mob != null && !candidates.isEmpty()) {
                    double maxChaseSq = profile.followRange * profile.followRange * 4;
                    candidates.removeIf(le -> {
                        if (MinionAI.isCachedUnreachable(minion.getUniqueId(), le.getUniqueId(), le.getLocation())) {
                            return true;
                        }
                        // Ground mobs cannot follow a target far above/below them; flying mobs can.
                        if (!isFlyingMob) {
                            double dy = Math.abs(minion.getLocation().getY() - le.getLocation().getY());
                            if (dy > 8) return true;
                        }
                        return minion.getLocation().distanceSquared(le.getLocation()) > maxChaseSq;
                    });
                }
                long now = Bukkit.getCurrentTick();
                // Protection override: pull far minions to very close threats
                LivingEntity protectionTarget = null;
                double protectionRadiusSq = 10 * 10;
                double minionDistSqToOwner = minion.getLocation().distanceSquared(owner.getLocation());
                boolean minionIsFar = minionDistSqToOwner > 6 * 6;
                // Find closest enemy to owner within protection radius
                LivingEntity closestClose = null;
                double closestCloseDistSq = Double.MAX_VALUE;
                for (LivingEntity le : candidates) {
                    double dSq = le.getLocation().distanceSquared(owner.getLocation());
                    if (dSq <= protectionRadiusSq && dSq < closestCloseDistSq) {
                        closestCloseDistSq = dSq;
                        closestClose = le;
                    }
                }
                if (minionIsFar && closestClose != null) {
                    // If minion currently has no target or target is far from owner, switch to protect
                    boolean currentTargetFar = false;
                    if (lastTarget != null && !lastTarget.isDead() && candidates.contains(lastTarget)) {
                        double curDistSq = lastTarget.getLocation().distanceSquared(owner.getLocation());
                        currentTargetFar = curDistSq > 12 * 12;
                    } else {
                        currentTargetFar = true;
                    }
                    if (currentTargetFar || lastTarget == null || !lastTarget.getUniqueId().equals(closestClose.getUniqueId())) {
                        protectionTarget = closestClose;
                    }
                }

                if (!candidates.isEmpty()) {
                    candidates.sort((a,b) -> Double.compare(a.getLocation().distanceSquared(owner.getLocation()), b.getLocation().distanceSquared(owner.getLocation())));
                    // Keep current target if still valid and recent, unless protection override
                    boolean lastValid = lastTarget != null && !lastTarget.isDead() && candidates.contains(lastTarget) && (now - lastTargetTick < 200);
                    if (protectionTarget != null) {
                        target = protectionTarget;
                        lastTarget = target;
                        lastTargetTick = now;
                    } else if (lastValid) {
                        target = lastTarget;
                    } else {
                        // Stable distribution per minion: assign based on minion hash, not tick
                        int idx = Math.abs(minion.getUniqueId().hashCode()) % candidates.size();
                        target = candidates.get(idx);
                        lastTarget = target;
                        lastTargetTick = now;
                    }
                } else {
                    target = null;
                }
                // Fallback to memory if no candidates
                if (target == null && lastTarget != null && now - lastTargetTick < 100 && !lastTarget.isDead()) {
                    target = lastTarget;
                } else if (target == null) {
                    lastTarget = null;
                }

                // Unreachable target detection: drop target if distance hasn't improved for ~5s
                if (target != null) {
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
                            }
                        }
                    } else {
                        // New target
                        lastTargetDist = curDist;
                        lastTargetDistTick = now;
                        stuckSince = 0L;
                    }
                } else {
                    // Reset stuck tracking when no target
                    lastTargetDist = Double.MAX_VALUE;
                    stuckSince = 0L;
                }

                if (target != null) {
                    {
                        double speed = getMobSpeed(mob);
                        // Paper's navigator handles ground mobs (walking around walls, stairs) and
                        // flying mobs (FlyingPathNavigation) the same way, so drive both through it.
                        boolean navigating = driveTowards(mob, target.getLocation(), speed, now);
                        if (!navigating) {
                            GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " no path to target " + target.getName() + ", direct fallback");
                            moveMobTowards(mob, target.getLocation(), speed * 0.8);
                        }
                        faceTowards(mob, target.getLocation());
                        double distSq = minion.getLocation().distanceSquared(target.getLocation());
                        double attackRangeSq = profile.attackRange * profile.attackRange;
                        if (distSq <= attackRangeSq && now - lastAttackTick >= requiredArcTicks(owner)) {
                            forceFullAttackStrength(owner);
                            applyPlayerDamage(owner, target);
                            GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " triggered player attack on " + target.getName() + " for player " + owner.getName());
                            lastAttackTick = now;
                        }
                    }
                } else {
                    // No target -> follow owner with a stable anchor to avoid jitter
                    double distSqToOwner = minion.getLocation().distanceSquared(owner.getLocation());
                    double idleRadius = 4.0;
                    double curFollowDist = Math.sqrt(distSqToOwner);
                    // Stuck follow detection: only when outside idle radius
                    if (curFollowDist > idleRadius) {
                        if (curFollowDist < lastFollowDist - 0.1) {
                            lastFollowDist = curFollowDist;
                            followStuckSince = 0L;
                        } else {
                            if (followStuckSince == 0L) followStuckSince = now;
                            boolean hasActivePath = false;
                            try {
                                hasActivePath = mob.getPathfinder().hasPath();
                            } catch (Exception ignored) {}
                            if (now - followStuckSince >= 100 && !hasActivePath) {
                                // Stuck with no path at all, teleport near owner
                                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " stuck following " + owner.getName() + " with no path, teleporting");
                                teleportNearPlayer(owner, minion);
                                lastFollowDist = Double.MAX_VALUE;
                                followStuckSince = 0L;
                            }
                        }
                    } else {
                        // Within idle radius, reset stuck tracking
                        lastFollowDist = curFollowDist;
                        followStuckSince = 0L;
                    }
                    if (distSqToOwner <= idleRadius * idleRadius) {
                        // Close enough, stop movement and stand still
                        try {
                            mob.getPathfinder().stopPathfinding();
                        } catch (Exception ignored) {}
                        navGoal = null;
                        if (isFlyingMob) {
                            mob.setVelocity(new Vector(0,0,0));
                        } else {
                            Vector v = mob.getVelocity();
                            mob.setVelocity(new Vector(0, v.getY(), 0));
                        }
                        lastFollowDist = curFollowDist;
                        followUnreachableSince = 0L;
                        ownerReachCached = true;
                        lastOwnerReachCheck = -1000L;
                    } else {
                        boolean ownerReachable = true;
                        boolean shouldCheckReach = curFollowDist > idleRadius + 2.0;
                        if (shouldCheckReach) {
                            // NpcApi pathfinding is synchronous, so only run it when the minion has
                            // actually stopped making progress toward the owner. Normal following
                            // never touches it.
                            boolean noProgress = followStuckSince != 0L && now - followStuckSince >= 10;
                            if (noProgress && now - lastOwnerReachCheck >= 20) {
                                Location mobLoc = minion.getLocation();
                                Location ownerLoc = owner.getLocation();
                                boolean sameSpot = lastReachMobLoc != null && lastReachOwnerLoc != null
                                        && lastReachMobLoc.getWorld() == mobLoc.getWorld()
                                        && lastReachMobLoc.distanceSquared(mobLoc) < 1.0
                                        && lastReachOwnerLoc.distanceSquared(ownerLoc) < 1.0;
                                if (!sameSpot) {
                                    lastOwnerReachCheck = now;
                                    lastReachMobLoc = mobLoc.clone();
                                    lastReachOwnerLoc = ownerLoc.clone();
                                    try {
                                        ownerReachCached = reachabilityService.canReach(mob, ownerLoc);
                                    } catch (Exception ignored) {
                                        ownerReachCached = true;
                                    }
                                }
                            }
                            ownerReachable = ownerReachCached;
                        } else {
                            ownerReachCached = true;
                        }
                        if (!ownerReachable) {
                            if (followUnreachableSince == 0L) {
                                followUnreachableSince = now;
                                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " owner " + owner.getName() + " unreachable while following (dist=" + String.format("%.2f", curFollowDist) + "), waiting 1s");
                            }
                            if (now - followUnreachableSince >= 20 && curFollowDist >= 3.0) {
                                GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " owner " + owner.getName() + " still unreachable after 1s, teleporting");
                                minion.teleport(owner.getLocation().add((Math.random()-0.5)*2,0,(Math.random()-0.5)*2));
                                lastFollowDist = Double.MAX_VALUE;
                                followStuckSince = 0L;
                                followUnreachableSince = 0L;
                            } else {
                                // Keep moving toward owner while waiting
                                double speed = getMobSpeed(mob) * 0.85;
                                moveMobTowards(mob, owner.getLocation(), speed);
                                faceTowards(mob, owner.getLocation());
                                lastFollowDist = curFollowDist;
                            }
                        } else {
                            followUnreachableSince = 0L;
                            double speed = getMobSpeed(mob) * 0.9;
                            // Same navigator for ground and flying mobs
                            if (!driveTowards(mob, owner.getLocation(), speed, now)) {
                                moveMobTowards(mob, owner.getLocation(), speed * 0.8);
                            }
                            faceTowards(mob, owner.getLocation());
                            lastFollowDist = curFollowDist;
                        }
                    }
                }
                // Teleport if too far away
                if (target == null && minion.getLocation().distanceSquared(owner.getLocation()) > profile.followRange * profile.followRange) {
                    GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " too far from owner " + owner.getName() + " with no target, teleporting");
                    minion.teleport(owner.getLocation().add((Math.random()-0.5)*2,0,(Math.random()-0.5)*2));
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private static void teleportNearPlayer(Player owner, LivingEntity minion) {
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

    private static void moveMobTowards(org.bukkit.entity.Mob mob, Location target, double speedFactor) {
        if (mob == null || target == null) return;
        boolean isFlying = false;
        try {
            isFlying = mob.getAttribute(Attribute.FLYING_SPEED) != null;
        } catch (Exception ignored) {}
        Location current = mob.getLocation();
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

    private static void faceTowards(org.bukkit.entity.Mob mob, Location target) {
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
