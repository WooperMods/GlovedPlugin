package dev.gloved.glovedplugin.nms.v26p3.brain.behavior.impl;

import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * CombatPets PrepareRamTarget parity adapted for Gloved summon brains (26.3-safe).
 */
public class PrepareRamTargetBehavior<E extends PathfinderMob> extends Behavior<E> {
    public static final int TIME_OUT_DURATION = 160;

    private final ToIntFunction<E> cooldownOnFail;
    private final int minRamDistance;
    private final int maxRamDistance;
    private final float walkSpeed;
    private final int ramPrepareTime;
    private final Function<E, SoundEvent> getPrepareRamSound;

    private Optional<Long> reachedRamPositionTimestamp = Optional.empty();
    private Optional<RamCandidate> ramCandidate = Optional.empty();

    public PrepareRamTargetBehavior(ToIntFunction<E> cooldownOnFail,
                                    int minRamDistance, int maxRamDistance, float speed,
                                    int prepareTime, Function<E, SoundEvent> prepareRamSound) {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.RAM_COOLDOWN_TICKS, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.RAM_TARGET, MemoryStatus.VALUE_ABSENT), TIME_OUT_DURATION);
        this.cooldownOnFail = cooldownOnFail;
        this.minRamDistance = minRamDistance;
        this.maxRamDistance = maxRamDistance;
        this.walkSpeed = speed;
        this.ramPrepareTime = prepareTime;
        this.getPrepareRamSound = prepareRamSound;
    }

    @Override
    protected void start(ServerLevel level, E mob, long gameTime) {
        LivingEntity target = mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target != null && target.isAlive()) {
            this.chooseRamPosition(mob, target);
        }
    }

    @Override
    protected void stop(ServerLevel level, E mob, long gameTime) {
        Brain<?> brain = mob.getBrain();
        if (!brain.hasMemoryValue(MemoryModuleType.RAM_TARGET)) {
            try { level.broadcastEntityEvent(mob, (byte) 59); } catch (Throwable ignored) {}
            try { brain.setMemory(MemoryModuleType.RAM_COOLDOWN_TICKS, cooldownOnFail.applyAsInt(mob)); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, E mob, long gameTime) {
        return this.ramCandidate.isPresent() && this.ramCandidate.get().target.isAlive();
    }

    @Override
    protected void tick(ServerLevel level, E mob, long gameTime) {
        if (this.ramCandidate.isEmpty()) return;
        RamCandidate cand = this.ramCandidate.get();
        LivingEntity target = cand.target;
        if (target == null || !target.isAlive()) return;

        mob.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(cand.startPosition, this.walkSpeed, 0));
        mob.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));

        BlockPos position = mob.blockPosition();
        if (position.equals(cand.startPosition)) {
            try { level.broadcastEntityEvent(mob, (byte) 58); } catch (Throwable ignored) {}
            if (this.reachedRamPositionTimestamp.isEmpty()) {
                this.reachedRamPositionTimestamp = Optional.of(gameTime);
            }
            if (gameTime - this.reachedRamPositionTimestamp.get() >= (long) this.ramPrepareTime) {
                mob.getBrain().setMemory(MemoryModuleType.RAM_TARGET, this.getEdgeOfBlock(position, target.blockPosition()));
                try {
                    level.playSound(null, mob, this.getPrepareRamSound.apply(mob), SoundSource.NEUTRAL, 1.0F, mob.getVoicePitch());
                } catch (Throwable ignored) {}
                this.ramCandidate = Optional.empty();
            }
        }
    }

    private Vec3 getEdgeOfBlock(BlockPos from, BlockPos to) {
        double dx = 0.5D * (double) Mth.sign(to.getX() - from.getX());
        double dz = 0.5D * (double) Mth.sign(to.getZ() - from.getZ());
        return Vec3.atBottomCenterOf(to).add(dx, 0.0D, dz);
    }

    private Optional<BlockPos> calculateRammingStartPosition(PathfinderMob mob, LivingEntity target) {
        BlockPos targetPos = target.blockPosition();
        if (!this.isWalkableBlock(mob, targetPos)) return Optional.empty();
        List<BlockPos> ramPositions = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = targetPos.mutable();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            mutable.set(targetPos);
            for (int range = 0; range < this.maxRamDistance; ++range) {
                if (!this.isWalkableBlock(mob, mutable.move(direction))) {
                    mutable.move(direction.getOpposite());
                    break;
                }
            }
            if (mutable.distManhattan(targetPos) >= this.minRamDistance) {
                ramPositions.add(mutable.immutable());
            }
        }
        PathNavigation navigation = mob.getNavigation();
        BlockPos mobPos = mob.blockPosition();
        return ramPositions.stream()
                .sorted(Comparator.comparingDouble(mobPos::distSqr))
                .filter(p -> {
                    try {
                        Path path = navigation.createPath(p, 0);
                        return path != null && path.canReach();
                    } catch (Throwable t) { return false; }
                })
                .findFirst();
    }

    private boolean isWalkableBlock(PathfinderMob mob, BlockPos pos) {
        try {
            return mob.getNavigation().isStableDestination(pos)
                    && mob.getPathfindingMalus(WalkNodeEvaluator.getPathTypeStatic(mob, pos)) == 0.0F;
        } catch (Throwable t) {
            return false;
        }
    }

    private void chooseRamPosition(PathfinderMob mob, LivingEntity target) {
        this.reachedRamPositionTimestamp = Optional.empty();
        this.ramCandidate = this.calculateRammingStartPosition(mob, target)
                .map(pos -> new RamCandidate(pos, target.blockPosition(), target));
    }

    private static final class RamCandidate {
        final BlockPos startPosition;
        final BlockPos targetPosition;
        final LivingEntity target;
        RamCandidate(BlockPos startPosition, BlockPos targetPosition, LivingEntity target) {
            this.startPosition = startPosition;
            this.targetPosition = targetPosition;
            this.target = target;
        }
    }
}
