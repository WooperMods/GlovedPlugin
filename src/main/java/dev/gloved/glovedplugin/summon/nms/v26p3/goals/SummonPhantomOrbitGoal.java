package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CombatPets-inspired phantom orbit/follow movement state adapted for summons.
 * Manipulates phantom move/anchor/phase state reflectively across mappings when possible.
 */
public class SummonPhantomOrbitGoal extends Goal {
    private final Phantom phantom;

    private float distance;
    private float height;
    private float clockWise;
    private float angle;

    private static volatile boolean resolved;
    private static Field moveTargetField; // Vec3
    private static Field anchorField;     // BlockPos
    private static Field phaseField;      // enum AttackPhase
    private static Object circlePhase;    // phaseValues[0]

    public SummonPhantomOrbitGoal(Phantom phantom) {
        this.phantom = phantom;
        this.setFlags(EnumSet.of(Flag.MOVE));
        ensureResolved();
    }

    private static synchronized void ensureResolved() {
        if (resolved) return;
        try {
            Class<?> c = Phantom.class;
            for (Field f : c.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (Vec3.class.isAssignableFrom(t) && moveTargetField == null) moveTargetField = f;
                    else if (BlockPos.class.isAssignableFrom(t) && anchorField == null) anchorField = f;
                    else if (t.isEnum() && phaseField == null) {
                        // Phantom has AttackPhase enum field
                        if (t.getDeclaringClass() == Phantom.class || t.getEnclosingClass() == Phantom.class
                                || t.getSimpleName().toLowerCase().contains("phase") || t.getSimpleName().toLowerCase().contains("attack")) {
                            phaseField = f;
                        } else if (phaseField == null) {
                            // fallback candidate
                            phaseField = f;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // Prefer enum field actually named like attack phase if multiple enums found
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().isEnum() || Modifier.isStatic(f.getModifiers())) continue;
                    String n = f.getName().toLowerCase();
                    if (n.contains("attack") || n.contains("phase")) { phaseField = f; break; }
                }
            } catch (Throwable ignored) {}

            if (moveTargetField != null) moveTargetField.setAccessible(true);
            if (anchorField != null) anchorField.setAccessible(true);
            if (phaseField != null) {
                phaseField.setAccessible(true);
                Object[] vals = phaseField.getType().getEnumConstants();
                if (vals != null && vals.length > 0) circlePhase = vals[0]; // CIRCLE in vanilla
            }
        } catch (Throwable ignored) {}
        resolved = true;
    }

    private Player resolveOwner() {
        try {
            if (!(phantom instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private LivingEntity resolveOwnerNms() {
        try {
            Player p = resolveOwner();
            if (p == null) return null;
            return ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle();
        } catch (Throwable t) {
            return null;
        }
    }

    private Object getPhase() {
        try { return phaseField != null ? phaseField.get(phantom) : null; } catch (Throwable t) { return null; }
    }

    private boolean isCirclePhaseOrUnknown() {
        Object p = getPhase();
        if (p == null || circlePhase == null) return true;
        return p == circlePhase;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = phantom.getTarget();
        if (target == null || !target.isAlive()) return true;
        return isCirclePhaseOrUnknown();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        this.distance = 5.0f + ThreadLocalRandom.current().nextFloat() * 10.0f;
        this.height = -4.0f + ThreadLocalRandom.current().nextFloat() * 9.0f;
        this.clockWise = ThreadLocalRandom.current().nextBoolean() ? 1.0f : -1.0f;
        selectNext();
    }

    @Override
    public void tick() {
        if (ThreadLocalRandom.current().nextInt(350) == 0) {
            this.height = -4.0f + ThreadLocalRandom.current().nextFloat() * 9.0f;
        }
        if (ThreadLocalRandom.current().nextInt(250) == 0) {
            ++this.distance;
            if (this.distance > 15.0f) {
                this.distance = 5.0f;
                this.clockWise = -this.clockWise;
            }
        }
        if (ThreadLocalRandom.current().nextInt(450) == 0) {
            this.angle = ThreadLocalRandom.current().nextFloat() * 2.0f * (float) Math.PI;
            selectNext();
        }
        if (touchingTarget()) {
            selectNext();
        }

        Vec3 moveTarget = getMoveTarget();
        if (moveTarget != null) {
            try {
                if (moveTarget.y < phantom.getY() && !phantom.level().isEmptyBlock(phantom.blockPosition().below(1))) {
                    this.height = Math.max(1.0f, this.height);
                    selectNext();
                }
                if (moveTarget.y > phantom.getY() && !phantom.level().isEmptyBlock(phantom.blockPosition().above(1))) {
                    this.height = Math.min(-1.0f, this.height);
                    selectNext();
                }
            } catch (Throwable ignored) {}
        }
    }

    private Vec3 getMoveTarget() {
        try { return moveTargetField != null ? (Vec3) moveTargetField.get(phantom) : null; } catch (Throwable t) { return null; }
    }

    private boolean touchingTarget() {
        Vec3 moveTarget = getMoveTarget();
        if (moveTarget == null) return false;
        try {
            return moveTarget.distanceToSqr(phantom.getX(), phantom.getY(), phantom.getZ()) < 4.0D;
        } catch (Throwable t) {
            return false;
        }
    }

    private void selectNext() {
        LivingEntity follow = phantom.getTarget();
        if (follow == null || !follow.isAlive()) {
            follow = resolveOwnerNms();
        }
        if (follow == null) return;

        BlockPos anchor = getOrInitAnchor();
        if (anchor == null) anchor = phantom.blockPosition();

        Vec3 point;
        LivingEntity target = phantom.getTarget();
        if (target == null || !target.isAlive()) {
            // Hover near owner with slight randomness
            point = new Vec3(
                    follow.getX() + ThreadLocalRandom.current().nextDouble(-2.0D, 2.0D),
                    follow.getY() + 2.5D,
                    follow.getZ() + ThreadLocalRandom.current().nextDouble(-2.0D, 2.0D)
            );
        } else {
            this.angle += this.clockWise * 15.0F * ((float) Math.PI / 180.0F);
            point = Vec3.atLowerCornerOf(anchor).add(
                    this.distance * Mth.cos(this.angle),
                    -4.0F + this.height,
                    this.distance * Mth.sin(this.angle)
            );
        }
        try { if (moveTargetField != null) moveTargetField.set(phantom, point); } catch (Throwable ignored) {}
    }

    private BlockPos getOrInitAnchor() {
        try {
            if (anchorField == null) return phantom.blockPosition();
            BlockPos anchor = (BlockPos) anchorField.get(phantom);
            if (anchor == null || BlockPos.ZERO.equals(anchor)) {
                anchor = phantom.blockPosition();
                anchorField.set(phantom, anchor);
            }
            return anchor;
        } catch (Throwable t) {
            return phantom.blockPosition();
        }
    }
}
