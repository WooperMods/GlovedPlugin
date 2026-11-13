package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonVexChargeAttackGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonVexFollowOwnerGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;

public class VexSummon extends Vex implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public VexSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.VEX, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        // Suppress vanilla life-limited/owner-bound vex behavior on construction.
        suppressVanillaLifecycle();
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        // Custom charge/attack integrated with SummonAI cooldown/damage/owner credit.
        this.goalSelector.addGoal(4, new SummonVexChargeAttackGoal(this, plugin));
        // CombatPets-like follow/hover positioning when idle.
        this.goalSelector.addGoal(8, new SummonVexFollowOwnerGoal(this));
    }

    @Override
    public void tick() {
        suppressVanillaLifecycle();
        super.tick();
        // Enforce suppression after vanilla tick in case fields are updated internally.
        suppressVanillaLifecycle();
    }

    /**
     * Prevent vanilla vex behaviors that conflict with summon lifecycle (limited life, owner-bound eviction).
     * Uses reflective fallbacks across mappings.
     */
    private void suppressVanillaLifecycle() {
        try { this.setNoGravity(true); } catch (Throwable ignored) {}
        // Mojang/Paper naming variants for limited life ticks.
        tryInvokeNoArg("setLimitedLifeTicks", new Class<?>[]{int.class}, new Object[]{Integer.MAX_VALUE / 4});
        tryInvokeNoArg("setIsLimitedLife", new Class<?>[]{boolean.class}, new Object[]{Boolean.FALSE});
        tryInvokeNoArg("setLimitedLife", new Class<?>[]{boolean.class}, new Object[]{Boolean.FALSE});
        // Common obf/legacy helper names seen in pet implementations.
        tryInvokeNoArg("t", new Class<?>[]{int.class}, new Object[]{Integer.MAX_VALUE / 4});
        trySetIntFieldContains("limitedLife", Integer.MAX_VALUE / 4);
        trySetIntFieldContains("lifeTicks", Integer.MAX_VALUE / 4);
        trySetBooleanFieldContains("limitedLife", false);
        trySetBooleanFieldContains("hasLimited", false);
        // Clear bound/owner references if present (vex sometimes stores owner/bounds).
        trySetObjectFieldContains("owner", null);
        trySetObjectFieldContains("bound", null);
    }

    private void tryInvokeNoArg(String name, Class<?>[] sig, Object[] args) {
        try {
            Method m = Vex.class.getDeclaredMethod(name, sig);
            m.setAccessible(true);
            m.invoke(this, args);
        } catch (Throwable ignored) {}
        try {
            Method m = Vex.class.getMethod(name, sig);
            m.setAccessible(true);
            m.invoke(this, args);
        } catch (Throwable ignored) {}
    }

    private void trySetIntFieldContains(String needle, int value) {
        try {
            for (Class<?> c = Vex.class; c != null && c != Object.class; c = c.getSuperclass()) {
                for (var f : c.getDeclaredFields()) {
                    if (f.getType() != int.class) continue;
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!n.contains(needle.toLowerCase(java.util.Locale.ROOT))) continue;
                    f.setAccessible(true);
                    f.setInt(this, value);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void trySetBooleanFieldContains(String needle, boolean value) {
        try {
            for (Class<?> c = Vex.class; c != null && c != Object.class; c = c.getSuperclass()) {
                for (var f : c.getDeclaredFields()) {
                    if (f.getType() != boolean.class) continue;
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!n.contains(needle.toLowerCase(java.util.Locale.ROOT))) continue;
                    f.setAccessible(true);
                    f.setBoolean(this, value);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void trySetObjectFieldContains(String needle, Object value) {
        try {
            for (Class<?> c = Vex.class; c != null && c != Object.class; c = c.getSuperclass()) {
                for (var f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!n.contains(needle.toLowerCase(java.util.Locale.ROOT))) continue;
                    // Avoid nuking unrelated critical refs; only clear owner/bound-like UUID/LivingEntity fields.
                    Class<?> t = f.getType();
                    if (t == java.util.UUID.class || net.minecraft.world.entity.LivingEntity.class.isAssignableFrom(t)
                            || net.minecraft.world.entity.Mob.class.isAssignableFrom(t)) {
                        f.setAccessible(true);
                        f.set(this, value);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
