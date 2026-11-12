package dev.gloved.glovedplugin.nms.v26p3.brain.behavior.impl;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.LongJumpUtil;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/**
 * CombatPets Breeze LongJump parity adapted for summon brain.
 */
public class BreezeLongJumpBehavior extends Behavior<Breeze> {
    private static final int REQUIRED_AIR_BLOCKS_ABOVE = 4;
    private static final double MAX_LINE_OF_SIGHT_TEST_RANGE = 50.0;
    private static final int JUMP_COOLDOWN_TICKS = 10;
    private static final int JUMP_COOLDOWN_WHEN_HURT_TICKS = 2;
    private static final int INHALING_DURATION_TICKS = 10;
    private static final float MAX_JUMP_VELOCITY = 1.4F;
    private static final List<Integer> ALLOWED_ANGLES = List.of(40, 55, 60, 75, 80);

    public BreezeLongJumpBehavior() {
        super(Map.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.BREEZE_JUMP_COOLDOWN, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.BREEZE_JUMP_INHALING, MemoryStatus.REGISTERED,
                MemoryModuleType.BREEZE_JUMP_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.BREEZE_SHOOT, MemoryStatus.VALUE_ABSENT
        ), 200);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Breeze breeze) {
        if (!breeze.onGround() && !breeze.isInWater()) return false;
        if (breeze.getBrain().checkMemory(MemoryModuleType.BREEZE_JUMP_TARGET, MemoryStatus.VALUE_PRESENT)) return true;

        LivingEntity target = breeze.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target == null) return false;
        if (tooCloseForJump(breeze, target)) return false;
        if (!canJumpFromCurrentPosition(level, breeze)) return false;

        BlockPos pos = snapToSurface(breeze, randomPointBehindTarget(target, breeze.getRandom()));
        if (pos == null) return false;
        if (!hasLineOfSight(breeze, Vec3.atCenterOf(pos)) && !hasLineOfSight(breeze, Vec3.atCenterOf(pos.above(REQUIRED_AIR_BLOCKS_ABOVE)))) return false;

        breeze.getBrain().setMemory(MemoryModuleType.BREEZE_JUMP_TARGET, pos);
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Breeze breeze, long gameTime) {
        try {
            return breeze.getPose() != Pose.STANDING && !breeze.getBrain().hasMemoryValue(MemoryModuleType.BREEZE_JUMP_COOLDOWN);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void start(ServerLevel level, Breeze breeze, long gameTime) {
        if (breeze.getBrain().checkMemory(MemoryModuleType.BREEZE_JUMP_INHALING, MemoryStatus.VALUE_ABSENT)) {
            breeze.getBrain().setMemoryWithExpiry(MemoryModuleType.BREEZE_JUMP_INHALING, Unit.INSTANCE, INHALING_DURATION_TICKS);
        }
        try { breeze.setPose(Pose.INHALING); } catch (Throwable ignored) {}
        breeze.getBrain().getMemory(MemoryModuleType.BREEZE_JUMP_TARGET).ifPresent(pos -> {
            try { breeze.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos)); } catch (Throwable ignored) {}
        });
    }

    @Override
    protected void tick(ServerLevel level, Breeze breeze, long gameTime) {
        if (finishedInhaling(breeze)) {
            Optional<Vec3> jumpTarget = breeze.getBrain().getMemory(MemoryModuleType.BREEZE_JUMP_TARGET)
                    .flatMap(pos -> calculateOptimalJumpVector(breeze, Vec3.atBottomCenterOf(pos)));
            if (jumpTarget.isEmpty()) {
                try { breeze.setPose(Pose.STANDING); } catch (Throwable ignored) {}
                return;
            }
            try { breeze.playSound(SoundEvents.BREEZE_JUMP, 1.0F, 1.0F); } catch (Throwable ignored) {}
            try { breeze.setPose(Pose.LONG_JUMPING); } catch (Throwable ignored) {}
            try { breeze.setYRot(breeze.yBodyRot); } catch (Throwable ignored) {}
            try { breeze.setDiscardFriction(true); } catch (Throwable ignored) {}
            try { breeze.setDeltaMovement(jumpTarget.get()); } catch (Throwable ignored) {}
        } else if (finishedJumping(breeze)) {
            try { breeze.playSound(SoundEvents.BREEZE_LAND, 1.0F, 1.0F); } catch (Throwable ignored) {}
            try { breeze.setPose(Pose.STANDING); } catch (Throwable ignored) {}
            try { breeze.setDiscardFriction(false); } catch (Throwable ignored) {}
            boolean hurted = breeze.getBrain().hasMemoryValue(MemoryModuleType.HURT_BY);
            breeze.getBrain().setMemoryWithExpiry(MemoryModuleType.BREEZE_JUMP_COOLDOWN, Unit.INSTANCE, hurted ? JUMP_COOLDOWN_WHEN_HURT_TICKS : JUMP_COOLDOWN_TICKS);
            breeze.getBrain().setMemoryWithExpiry(MemoryModuleType.BREEZE_SHOOT, Unit.INSTANCE, 100L);
        }
    }

    @Override
    protected void stop(ServerLevel level, Breeze breeze, long gameTime) {
        try {
            Pose pose = breeze.getPose();
            if (pose == Pose.LONG_JUMPING || pose == Pose.INHALING) {
                breeze.setPose(Pose.STANDING);
            }
        } catch (Throwable ignored) {}
        breeze.getBrain().eraseMemory(MemoryModuleType.BREEZE_JUMP_TARGET);
        breeze.getBrain().eraseMemory(MemoryModuleType.BREEZE_JUMP_INHALING);
    }

    private static boolean finishedInhaling(Breeze breeze) {
        try {
            return breeze.getBrain().getMemory(MemoryModuleType.BREEZE_JUMP_INHALING).isEmpty() && breeze.getPose() == Pose.INHALING;
        } catch (Throwable t) { return false; }
    }

    private static boolean finishedJumping(Breeze breeze) {
        try {
            return breeze.getPose() == Pose.LONG_JUMPING && breeze.onGround();
        } catch (Throwable t) { return false; }
    }

    private static Vec3 randomPointBehindTarget(LivingEntity target, RandomSource random) {
        float yaw = target.yHeadRot + 180.0F + (float) random.nextGaussian() * 45.0F;
        float dist = Mth.lerp(random.nextFloat(), 4.0F, 8.0F);
        Vec3 offset = Vec3.directionFromRotation(0.0F, yaw).scale(dist);
        return target.position().add(offset);
    }

    @Nullable
    private static BlockPos snapToSurface(LivingEntity entity, Vec3 vec) {
        try {
            ClipContext down = new ClipContext(vec, vec.relative(Direction.DOWN, 10.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
            HitResult hit = entity.level().clip(down);
            if (hit.getType() == HitResult.Type.BLOCK) {
                return BlockPos.containing(hit.getLocation()).above();
            }
            ClipContext up = new ClipContext(vec, vec.relative(Direction.UP, 10.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
            HitResult hitUp = entity.level().clip(up);
            if (hitUp.getType() == HitResult.Type.BLOCK) {
                return BlockPos.containing(hitUp.getLocation()).above();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean hasLineOfSight(Breeze breeze, Vec3 vec) {
        try {
            Vec3 pos = new Vec3(breeze.getX(), breeze.getY(), breeze.getZ());
            if (vec.distanceTo(pos) > MAX_LINE_OF_SIGHT_TEST_RANGE) return false;
            return breeze.level().clip(new ClipContext(pos, vec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, breeze)).getType() == HitResult.Type.MISS;
        } catch (Throwable t) { return false; }
    }

    private static boolean tooCloseForJump(Breeze breeze, LivingEntity target) {
        try { return target.distanceTo(breeze) - 4.0F <= 0.0F; } catch (Throwable t) { return true; }
    }

    private static boolean canJumpFromCurrentPosition(ServerLevel level, Breeze breeze) {
        try {
            BlockPos pos = breeze.blockPosition();
            for (int step = 1; step <= REQUIRED_AIR_BLOCKS_ABOVE; ++step) {
                BlockPos rel = pos.relative(Direction.UP, step);
                if (!level.getBlockState(rel).isAir() && !level.getFluidState(rel).is(FluidTags.WATER)) return false;
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    private static Optional<Vec3> calculateOptimalJumpVector(Breeze breeze, Vec3 vec) {
        Set<Integer> angles = new HashSet<>(ALLOWED_ANGLES);
        RandomSource random = breeze.getRandom();
        while (!angles.isEmpty()) {
            int angle = angles.stream().skip(random.nextInt(angles.size())).findFirst().orElse(60);
            try {
                Optional<Vec3> opt = LongJumpUtil.calculateJumpVectorForAngle(breeze, vec, MAX_JUMP_VELOCITY, angle, false);
                if (opt.isPresent()) return opt;
            } catch (Throwable ignored) {}
            angles.remove(angle);
        }
        return Optional.empty();
    }
}
