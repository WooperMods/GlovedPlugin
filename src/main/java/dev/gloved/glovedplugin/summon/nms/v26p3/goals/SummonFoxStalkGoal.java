package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.fox.Fox;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;

/**
 * Best-effort port of CombatPets FoxStalkPreyGoal for Gloved summon foxes.
 * Uses reflection for faceplanted/state internals where 26.3 mappings/access differ.
 */
public class SummonFoxStalkGoal extends Goal {
    private static final float MAX_DISTANCE_SQR = 36.0F;
    private final Fox fox;

    private static Method setFaceplanted;
    private static Field jumpingField;

    static {
        try { setFaceplanted = Fox.class.getDeclaredMethod("setFaceplanted", boolean.class); setFaceplanted.setAccessible(true); } catch (Throwable ignored) {}
        if (setFaceplanted == null) {
            try { setFaceplanted = Fox.class.getDeclaredMethod("B", boolean.class); setFaceplanted.setAccessible(true); } catch (Throwable ignored) {}
        }
        try { jumpingField = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("jumping"); jumpingField.setAccessible(true); } catch (Throwable ignored) {}
        if (jumpingField == null) {
            try { jumpingField = net.minecraft.world.entity.Entity.class.getDeclaredField("jumping"); jumpingField.setAccessible(true); } catch (Throwable ignored) {}
        }
    }

    public SummonFoxStalkGoal(Fox fox) {
        this.fox = fox;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private void setFaceplantedSafe(boolean v) {
        try { if (setFaceplanted != null) setFaceplanted.invoke(fox, v); } catch (Throwable ignored) {}
    }

    private boolean isJumpingSafe() {
        try {
            if (jumpingField != null) {
                Object v = jumpingField.get(fox);
                return v instanceof Boolean b && b;
            }
        } catch (Throwable ignored) {}
        try { return fox.getDeltaMovement().y > 0.05D; } catch (Throwable ignored) { return false; }
    }

    private boolean isPathClear(LivingEntity target) {
        try {
            // Vanilla helper exists on Fox in most mappings; reflect as fallback.
            Method m = Fox.class.getDeclaredMethod("isPathClear", Fox.class, LivingEntity.class);
            m.setAccessible(true);
            Object r = m.invoke(null, fox, target);
            return r instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return fox.hasLineOfSight(target);
        }
    }

    @Override
    public boolean canUse() {
        try {
            if (fox.isSleeping()) return false;
            LivingEntity target = fox.getTarget();
            if (target == null || !target.isAlive()) return false;
            if (fox.distanceToSqr(target) <= MAX_DISTANCE_SQR) return false;
            if (fox.isCrouching() || fox.isInterested() || isJumpingSafe()) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void start() {
        try { fox.setSitting(false); } catch (Throwable ignored) {}
        setFaceplantedSafe(false);
    }

    @Override
    public void stop() {
        LivingEntity target = fox.getTarget();
        try {
            if (target != null && isPathClear(target)) {
                fox.setIsInterested(true);
                fox.setIsCrouching(true);
                fox.getNavigation().stop();
                fox.getLookControl().setLookAt(target, fox.getMaxHeadYRot(), fox.getMaxHeadXRot());
            } else {
                fox.setIsInterested(false);
                fox.setIsCrouching(false);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        LivingEntity target = fox.getTarget();
        if (target == null) return;
        try {
            fox.getLookControl().setLookAt(target, fox.getMaxHeadYRot(), fox.getMaxHeadXRot());
            if (fox.distanceToSqr(target) <= MAX_DISTANCE_SQR) {
                fox.setIsInterested(true);
                fox.setIsCrouching(true);
                fox.getNavigation().stop();
            } else {
                fox.getNavigation().moveTo(target, 1.5D);
            }
        } catch (Throwable ignored) {}
    }
}
