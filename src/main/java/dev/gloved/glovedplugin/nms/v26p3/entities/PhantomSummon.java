package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonPhantomOrbitGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonPhantomSweepAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.UUID;

public class PhantomSummon extends Phantom implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    // Reflective access to vanilla phantom movement state (mapping-dependent names/types).
    private static volatile boolean stateResolved;
    private static Field moveTargetField;
    private static Field anchorField;
    private static Field phaseField;
    private static Object circlePhase;

    public PhantomSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.PHANTOM, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        ensureStateResolved();
        suppressVanillaBehaviors();
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        // Orbit/follow movement state and sweep attack adapted for summons.
        this.goalSelector.addGoal(2, new SummonPhantomOrbitGoal(this));
        this.goalSelector.addGoal(3, new SummonPhantomSweepAttackGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        suppressVanillaBehaviors();
        try {
            net.minecraft.world.entity.LivingEntity t = this.getTarget();
            if (t != null && !t.isAlive()) this.setTarget(null);
        } catch (Throwable ignored) {}
        super.tick();
        suppressVanillaBehaviors();
    }

    /**
     * Suppress unwanted vanilla phantom behaviors for summons:
     * daylight burning, resting/anchor drift, and despawn-like lifecycle quirks.
     */
    private void suppressVanillaBehaviors() {
        try { this.setPersistenceRequired(); } catch (Throwable ignored) {}
        try { this.setNoGravity(false); } catch (Throwable ignored) {}
        // Keep phantom in a non-resting/circling-over-anchor state when no target logic demands it.
        try {
            if (anchorField != null) {
                BlockPos anchor = this.blockPosition().above(4);
                anchorField.set(this, anchor);
            }
        } catch (Throwable ignored) {}
        // Prevent burn from sunlight; phantoms are undead and burn in daylight.
        try {
            if (this.isAlive() && this.isSunBurnTick()) {
                this.clearFire();
                this.setRemainingFireTicks(0);
            }
        } catch (Throwable ignored) {}
        // Ensure a move target exists to reduce erratic vanilla fallback movement.
        try {
            if (moveTargetField != null && moveTargetField.get(this) == null) {
                moveTargetField.set(this, new Vec3(this.getX(), this.getY() + 1.0D, this.getZ()));
            }
        } catch (Throwable ignored) {}
        // Prefer circle/idle phase when no combat target.
        try {
            if (phaseField != null && circlePhase != null && (this.getTarget() == null || !this.getTarget().isAlive())) {
                phaseField.set(this, circlePhase);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean isSunBurnTick() {
        // Summons must not burn in daylight.
        return false;
    }

    private static synchronized void ensureStateResolved() {
        if (stateResolved) return;
        try {
            Class<?> c = Phantom.class;
            for (Field f : c.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (Vec3.class.isAssignableFrom(t) && moveTargetField == null) moveTargetField = f;
                    else if (BlockPos.class.isAssignableFrom(t) && anchorField == null) anchorField = f;
                    else if (t.isEnum() && phaseField == null) {
                        if (t.getDeclaringClass() == Phantom.class || t.getEnclosingClass() == Phantom.class) phaseField = f;
                        else phaseField = f;
                    }
                } catch (Throwable ignored) {}
            }
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
                if (vals != null && vals.length > 0) circlePhase = vals[0];
            }
        } catch (Throwable ignored) {}
        stateResolved = true;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
