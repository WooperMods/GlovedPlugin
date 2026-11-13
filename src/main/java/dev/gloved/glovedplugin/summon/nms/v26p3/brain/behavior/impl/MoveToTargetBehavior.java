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

/**
 * Adapted from CombatPets MoveToTarget for Gloved summon brains (26.3).
 */
public class MoveToTargetBehavior extends Behavior<Mob> {
    private static final int MAX_COOLDOWN_BEFORE_RETRYING = 40;
    private int remainingCooldown;
    @Nullable private Path path;
    @Nullable private BlockPos lastTargetPos;
    private float speedModifier;

    public MoveToTargetBehavior() {
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
        WalkTarget walkTarget = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
        if (walkTarget == null) return false;
        boolean reachedTarget = this.reachedTarget(mob, walkTarget);
        if (!reachedTarget && this.tryComputePath(mob, walkTarget, level.getGameTime())) {
            this.lastTargetPos = walkTarget.getTarget().currentBlockPosition();
            return true;
        }
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        if (reachedTarget) {
            brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        }
        return false;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Mob mob, long gameTime) {
        if (this.path != null && this.lastTargetPos != null) {
            Optional<WalkTarget> walkTarget = mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
            PathNavigation navigation = mob.getNavigation();
            return !navigation.isDone() && walkTarget.isPresent() && !this.reachedTarget(mob, walkTarget.get());
        }
        return false;
    }

    @Override
    protected void stop(ServerLevel level, Mob mob, long gameTime) {
        try {
            if (mob.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    && !this.reachedTarget(mob, mob.getBrain().getMemory(MemoryModuleType.WALK_TARGET).get())
                    && mob.getNavigation().isStuck()) {
                this.remainingCooldown = level.getRandom().nextInt(MAX_COOLDOWN_BEFORE_RETRYING);
            }
        } catch (Throwable ignored) {}
        try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
        try { mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET); } catch (Throwable ignored) {}
        try { mob.getBrain().eraseMemory(MemoryModuleType.PATH); } catch (Throwable ignored) {}
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
            brain.setMemory(MemoryModuleType.PATH, navPath);
        }
        if (navPath != null && this.lastTargetPos != null) {
            WalkTarget wt = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
            if (wt == null) return;
            if (wt.getTarget().currentBlockPosition().distSqr(this.lastTargetPos) > 4.0D
                    && this.tryComputePath(mob, wt, level.getGameTime())) {
                this.lastTargetPos = wt.getTarget().currentBlockPosition();
                this.start(level, mob, gameTime);
            }
        }
    }

    private boolean tryComputePath(Mob mob, WalkTarget target, long gameTime) {
        BlockPos pos = target.getTarget().currentBlockPosition();
        try {
            this.path = mob.getNavigation().createPath(pos, 0);
        } catch (Throwable t) {
            this.path = null;
        }
        this.speedModifier = target.getSpeedModifier();
        Brain<?> brain = mob.getBrain();
        if (this.reachedTarget(mob, target)) {
            brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        } else {
            boolean canReach = this.path != null && this.path.canReach();
            if (canReach) {
                brain.eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
            } else if (!brain.hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
                brain.setMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, gameTime);
            }
            if (this.path != null) return true;
            if (mob instanceof PathfinderMob pm) {
                try {
                    Vec3 vec = DefaultRandomPos.getPosTowards(pm, 10, 7, Vec3.atBottomCenterOf(pos), 1.5707963705062866D);
                    if (vec != null) {
                        this.path = mob.getNavigation().createPath(vec.x, vec.y, vec.z, 0);
                        return this.path != null;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private boolean reachedTarget(Mob mob, WalkTarget target) {
        return target.getTarget().currentBlockPosition().distManhattan(mob.blockPosition()) <= target.getCloseEnoughDist();
    }
}
