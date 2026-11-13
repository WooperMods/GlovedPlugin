package dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Optional;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.core.GlovedPlugin;

/**
 * Minimal MoveToTarget behavior adapted for Gloved summons (based on CombatPets/Pet brain MoveToTarget).
 * Consumes WALK_TARGET memory and drives path navigation.
 */
public class SummonMoveToTarget extends Behavior<Mob> {
    private static final int MAX_COOLDOWN_BEFORE_RETRYING = 40;

    private int remainingCooldown;
    @Nullable private Path path;
    @Nullable private BlockPos lastTargetPos;
    private float speedModifier;

    public SummonMoveToTarget() {
        super(ImmutableMap.of(
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, MemoryStatus.REGISTERED,
                MemoryModuleType.PATH, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_PRESENT
        ), 150, 250);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Mob mob) {
        if (this.remainingCooldown > 0) {
            --this.remainingCooldown;
            return false;
        }
        Brain<?> brain = mob.getBrain();
        Optional<WalkTarget> opt = brain.getMemory(MemoryModuleType.WALK_TARGET);
        if (opt.isEmpty()) {
            dbg(mob, "startSkip noWalkTarget");
            return false;
        }
        WalkTarget walkTarget = opt.get();
        boolean reached = this.reachedTarget(mob, walkTarget);
        if (!reached && this.tryComputePath(mob, walkTarget, level.getGameTime())) {
            this.lastTargetPos = walkTarget.getTarget().currentBlockPosition();
            dbg(mob, "start ok distToTarget=" + String.format(java.util.Locale.US, "%.1f", Math.sqrt(mob.distanceToSqr(walkTarget.getTarget().currentPosition()))));
            return true;
        }
        dbg(mob, "startClear reached=" + reached + " pathOk=" + (this.path != null));
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        if (reached) brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        return false;
    }

    private static void dbg(Mob mob, String msg) {
        try {
            if (!DebugLog.isEnabled()) return;
            if ((mob.tickCount % 40L) != 0L) return;
            String id = "?"; try { id = mob.getBukkitEntity().getUniqueId().toString().substring(0,8); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[SummonMoveToTarget] " + mob.getType() + "/" + id + " " + msg);
        } catch (Throwable ignored) {}
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Mob mob, long gameTime) {
        if (this.path != null && this.lastTargetPos != null) {
            Optional<WalkTarget> walkTarget = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
            PathNavigation navigation = mob.getNavigation();
            return !navigation.isDone() && walkTarget.isPresent() && !this.reachedTarget(mob, walkTarget.get());
        } else {
            // CombatPets parity: clear stale combat state when movement cannot continue.
            try { mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT); } catch (Throwable ignored) {}
            try { mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET); } catch (Throwable ignored) {}
            return false;
        }
    }

    @Override
    protected void stop(ServerLevel level, Mob mob, long gameTime) {
        Optional<WalkTarget> wt = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (wt.isPresent() && !this.reachedTarget(mob, wt.get()) && mob.getNavigation().isStuck()) {
            this.remainingCooldown = level.getRandom().nextInt(MAX_COOLDOWN_BEFORE_RETRYING);
        }
        try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.PATH);
        this.path = null;
    }

    @Override
    protected void start(ServerLevel level, Mob mob, long gameTime) {
        mob.getBrain().setMemory(MemoryModuleType.PATH, this.path);
        try { mob.getNavigation().moveTo(this.path, this.speedModifier); } catch (Throwable ignored) {}
    }

    @Override
    protected void tick(ServerLevel level, Mob mob, long gameTime) {
        Path navPath = mob.getNavigation().getPath();
        Brain<?> brain = mob.getBrain();
        if (this.path != navPath) {
            this.path = navPath;
            try { brain.setMemory(MemoryModuleType.PATH, navPath); } catch (Throwable ignored) {}
        }
        if (navPath != null && this.lastTargetPos != null) {
            Optional<WalkTarget> wt = brain.getMemory(MemoryModuleType.WALK_TARGET);
            if (wt.isEmpty()) return;
            if (wt.get().getTarget().currentBlockPosition().distSqr(this.lastTargetPos) > 4.0D
                    && this.tryComputePath(mob, wt.get(), level.getGameTime())) {
                this.lastTargetPos = wt.get().getTarget().currentBlockPosition();
                this.start(level, mob, gameTime);
            }
        }
    }

    private boolean tryComputePath(Mob mob, WalkTarget walkTarget, long gameTime) {
        BlockPos targetPos = walkTarget.getTarget().currentBlockPosition();
        this.path = mob.getNavigation().createPath(targetPos, 0);
        this.speedModifier = walkTarget.getSpeedModifier();
        Brain<?> brain = mob.getBrain();
        if (this.reachedTarget(mob, walkTarget)) {
            brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        } else {
            boolean canReach = this.path != null && this.path.canReach();
            if (canReach) {
                brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            } else if (!brain.hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
                brain.setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, gameTime);
            }
            if (this.path != null) return true;
            if (mob instanceof PathfinderMob pf) {
                Vec3 alt = DefaultRandomPos.getPosTowards(pf, 10, 7, Vec3.atBottomCenterOf(targetPos), 1.5707963705062866D);
                if (alt != null) {
                    this.path = mob.getNavigation().createPath(alt.x, alt.y, alt.z, 0);
                    return this.path != null;
                }
            }
        }
        return false;
    }

    private boolean reachedTarget(Mob mob, WalkTarget walkTarget) {
        return walkTarget.getTarget().currentBlockPosition().distManhattan(mob.blockPosition()) <= walkTarget.getCloseEnoughDist();
    }
}
