package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.Pathfinder;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.lootr.util.EliteMobsIntegration;
import dev.gloved.glovedplugin.summon.phasing.PhasingMob;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
    private final SummonTargetCoordinator coordinator;
    private final Map<UUID, UUID> minionTasks = new HashMap<>();
    private SummonManager summonManager;
    private SummonConfig summonConfig;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> recentDamagers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DAMAGER_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, Long>> unreachableCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<UUID, org.bukkit.Location>> unreachableLocationCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNREACHABLE_TTL_MS = 10_000L;
    private static final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastWaypointLog = new java.util.concurrent.ConcurrentHashMap<>();

    public MinionAI(GlovedPlugin plugin, SummonInstanceTracker tracker, SummonTargetCoordinator coordinator) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.coordinator = coordinator;
        this.reachabilityService = new ReachabilityService(plugin);
        startGlobalTask();
    }

    /** Wired after construction to avoid a MinionAI <-> SummonManager cycle. */
    public void setSummon(SummonConfig summonConfig, SummonManager summonManager) {
        this.summonConfig = summonConfig;
        this.summonManager = summonManager;
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
                    if (minion.getLocation().distanceSquared(owner.getLocation()) > SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE) {
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
                // Targeting is plugin-owned; SummonMoveGoal handles obstacle-aware pathfinding.

                // Capacity-based stable targeting (no owner-look, no protection emergency, no "close enemy" priority).
                // Scoring: last damaged/recent damagers highest, then distance-based.
                LivingEntity target = null;
                List<LivingEntity> candidates = new ArrayList<>();
                Set<UUID> recentDamagerIds = new HashSet<>();
                double scanDist = SummonProfile.FOLLOW_RANGE;
                for (Entity e : owner.getNearbyEntities(scanDist, scanDist, scanDist)) {
                    if (!(e instanceof LivingEntity le)) continue;
                    if (le == owner || le.isDead() || tracker.isSummoned(le.getUniqueId())) continue;
                    if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
                    boolean isHostile = le instanceof org.bukkit.entity.Monster
                            || le instanceof org.bukkit.entity.Slime
                            || le instanceof org.bukkit.entity.Ghast;
                    boolean targetsOwner = false;
                    if (e instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
                        targetsOwner = mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId());
                    }
                    if (isHostile || targetsOwner) {
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
                double maxTargetSq = SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE;
                candidates.removeIf(le -> le.getLocation().distanceSquared(owner.getLocation()) > maxTargetSq);

                // Global candidates: owner-valid threats for shared coordinator state (prune/rebalance/coverage).
                // Per-minion constraints are passed as a predicate so one minion's pathing limits don't
                // make others "forget" enemies (including those close to the owner).
                List<LivingEntity> globalCandidates = new ArrayList<>(candidates);
                double maxChaseSq = SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE;
                java.util.function.Predicate<UUID> unavailableForMinion = (UUID targetId) -> {
                    if (mob == null) return false;
                    Entity te = owner.getWorld().getEntity(targetId);
                    if (!(te instanceof LivingEntity tle)) return true;
                    if (MinionAI.isCachedUnreachable(minion.getUniqueId(), targetId, tle.getLocation())) return true;
                    if (!isFlyingMob) {
                        double dy = Math.abs(minion.getLocation().getY() - tle.getLocation().getY());
                        if (dy > 16) return true;
                    }
                    return minion.getLocation().distanceSquared(tle.getLocation()) > maxChaseSq;
                };

                long now = Bukkit.getCurrentTick();
                if (!globalCandidates.isEmpty()) {
                    target = coordinator.assign(owner, (LivingEntity) minion, globalCandidates, recentDamagerIds, now, unavailableForMinion);
                }
                if (target != null) {
                    boolean globallyValid = false;
                    for (LivingEntity gc : globalCandidates) {
                        if (gc.getUniqueId().equals(target.getUniqueId())) { globallyValid = true; break; }
                    }
                    if (target.isDead() || CombatTargetRules.isProtected(plugin, owner, target) || !globallyValid) {
                        target = null;
                    }
                }
                if (target != null) {
                    lastTarget = target;
                    lastTargetTick = now;
                } else {
                    // Fallback to memory briefly if coordinator yielded nothing but recent target may still be valid
                    if (lastTarget != null && now - lastTargetTick < 40 && !lastTarget.isDead()
                            && !CombatTargetRules.isProtected(plugin, owner, lastTarget)) {
                        target = lastTarget;
                    } else {
                        lastTarget = null;
                    }
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
                                coordinator.invalidateMinion(minion.getUniqueId());
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
                } else {
                    // Reset stuck tracking when no target
                    lastTargetDist = Double.MAX_VALUE;
                    stuckSince = 0L;
                }

                try {
                    mob.setTarget(target);
                } catch (Exception ignored) {}

                if (target != null) {
                    {
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
                                // Level-scaled damage with player credit, no weapon scaling, no base mob damage
                                int ownerLevel = EliteMobsIntegration.getCombatLevel(owner);
                                boolean isElite = isEliteMob(target);
                                double damage = isElite ? 7.0 + ownerLevel * 0.4 : 7.0;
                                try {
                                    DamageSource src = DamageSource.builder(DamageType.GENERIC)
                                            .withDirectEntity(minion)
                                            .withCausingEntity(owner)
                                            .build();
                                    target.damage(damage, src);
                                    applyKnockback(minion, owner, target);
                                    GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " dealt level-scaled damage " + damage + " to " + target.getName() + " for player " + owner.getName() + " level=" + ownerLevel + " elite=" + isElite);
                                } catch (Exception e) {
                                    GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " damage exception: " + e);
                                }
                                // Reset despawn timers per minion type
                                NamespacedKey mobTypeKey = new NamespacedKey(plugin, "summon_mobtype");
                                String mobType = minion.getPersistentDataContainer().get(mobTypeKey, PersistentDataType.STRING);
                                if (mobType != null) {
                                    tracker.resetTimersForPlayerAndType(owner.getUniqueId(), mobType, SummonProfile.LIFETIME_TICKS);
                                }
                                trySummonFromMinion(minion, owner, target);
                                lastAttackTick = now;
                            }
                        }
                    }
                } else {
                    // No target -> SummonMoveGoal pathfinds to owner; handle stuck recovery only.
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
                // Teleport if too far away
                if (target == null && minion.getLocation().distanceSquared(owner.getLocation()) > SummonProfile.FOLLOW_RANGE * SummonProfile.FOLLOW_RANGE) {
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

    static void moveMobTowards(org.bukkit.entity.Mob mob, Location target, double speedFactor) {
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
     * Minions have a 1/4th chance (of the profile's base chance) to spawn more minions of their
     * own type on hit, following the same cooldown and max-active rules as the player.
     */
    private void trySummonFromMinion(LivingEntity minion, Player owner, LivingEntity target) {
        if (summonManager == null || summonConfig == null) return;
        Player liveOwner = Bukkit.getPlayer(owner.getUniqueId());
        if (liveOwner == null || !liveOwner.isOnline()) return;
        NamespacedKey profileKey = new NamespacedKey(plugin, "summon_profile");
        String profileName = minion.getPersistentDataContainer().get(profileKey, PersistentDataType.STRING);
        if (profileName == null) return;
        SummonProfile profile = summonConfig.getProfile(profileName);
        if (profile == null) return;
        if (!summonManager.canSummon(liveOwner, profile)) return;
        double minionChance = profile.chance * 0.25;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > minionChance) return;
        summonManager.setCooldown(liveOwner, profile);
        summonManager.spawn(liveOwner, profile, target);
        GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " triggered spawn for " + liveOwner.getName()
                + " profile " + profileName + " (minion-chance=" + minionChance + ")");
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

    private static boolean isEliteMob(LivingEntity entity) {
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
