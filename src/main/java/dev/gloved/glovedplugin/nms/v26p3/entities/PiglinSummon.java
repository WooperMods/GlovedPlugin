package dev.gloved.glovedplugin.nms.v26p3.entities;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainReflect;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainUtil;
import dev.gloved.glovedplugin.nms.v26p3.brain.behavior.SummonCoreBehaviors;
import dev.gloved.glovedplugin.nms.v26p3.brain.behavior.SummonFightBehaviors;
import dev.gloved.glovedplugin.nms.v26p3.brain.behavior.SummonIdleBehaviors;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.CrossbowAttack;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.UUID;

/**
 * Piglin summon brained like CombatPets PiglinPet (core/idle/fight + crossbow/melee),
 * adapted to Gloved 32-block range and SummonAI/soul-stats damage pipeline.
 * Vanilla bartering/admiring/hunt/dance/zombification side effects are suppressed.
 */
public class PiglinSummon extends Piglin implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public PiglinSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.PIGLIN, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        try { this.setImmuneToZombification(true); } catch (Throwable ignored) {}
        trySetCannotHunt();
        try {
            this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
            this.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        } catch (Throwable ignored) {}
    }

    private void trySetCannotHunt() {
        for (String name : new String[]{"cannotHunt", "setCannotHunt"}) {
            try {
                java.lang.reflect.Method m = Piglin.class.getDeclaredMethod(name, boolean.class);
                m.setAccessible(true);
                m.invoke(this, true);
                return;
            } catch (Throwable ignored) {}
            try {
                java.lang.reflect.Field f = Piglin.class.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                    f.setBoolean(this, true);
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void setGoals() {
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.refreshBrain(this, (Brain<Piglin>) this.getBrain()); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Piglin> brainProvider() {
        return SummonBrain.brainProvider(this);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain<?> makeBrain(Object dynamic) {
        try {
            var provider = this.brainProvider();
            if (provider != null) {
                for (java.lang.reflect.Method m : provider.getClass().getMethods()) {
                    if (!"makeBrain".equals(m.getName())) continue;
                    try {
                        Object brain;
                        if (m.getParameterCount() == 1) brain = m.invoke(provider, dynamic);
                        else if (m.getParameterCount() == 2) brain = m.invoke(provider, this, dynamic);
                        else continue;
                        return this.refreshBrain(this, (Brain) brain);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try { return this.getBrain(); } catch (Throwable t) { return null; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Brain<Piglin> refreshBrain(Piglin pet, Brain<Piglin> brain) {
        if (brain == null) return brain;

        try {
            SummonBrainReflect.addActivity(brain, Activity.CORE, 0, ImmutableList.of(
                    SummonCoreBehaviors.lookAtTarget(),
                    SummonCoreBehaviors.moveToTarget(),
                    SummonCoreBehaviors.swim(),
                    SummonFightBehaviors.stopAngryIfTargetDead(),
                    SummonFightBehaviors.stopAttackIfTargetInvalid(plugin)
            ));
        } catch (Throwable ignored) {}

        try {
            SummonBrainReflect.addActivityPairs(brain, Activity.IDLE, ImmutableList.of(
                    Pair.of(10, new RunOne<>(ImmutableList.of(Pair.of(SummonIdleBehaviors.lookAtOwner(plugin), 1)))),
                    Pair.of(10, SummonIdleBehaviors.followOwner(plugin)),
                    Pair.of(10, SummonFightBehaviors.autoTargetAndAttack(plugin))
            ));
        } catch (Throwable t) {
            try {
                SummonBrainReflect.addActivityPairs(brain, Activity.IDLE, ImmutableList.of(
                        Pair.of(10, SummonIdleBehaviors.lookAtOwner(plugin)),
                        Pair.of(10, SummonIdleBehaviors.followOwner(plugin)),
                        Pair.of(10, SummonFightBehaviors.autoTargetAndAttack(plugin))
                ));
            } catch (Throwable ignored) {}
        }

        try {
            SummonBrainReflect.addActivityPairs(brain, Activity.FIGHT, ImmutableList.of(
                    Pair.of(10, SummonFightBehaviors.stopAttackIfTargetInvalid(plugin)),
                    Pair.of(10, SummonFightBehaviors.reachTargetWhenOutOfRange()),
                    Pair.of(10, SummonFightBehaviors.meleeAttack(plugin)),
                    Pair.of(10, new CrossbowAttack<>())
            ));
        } catch (Throwable t) {
            try {
                SummonBrainReflect.addActivityPairs(brain, Activity.FIGHT, ImmutableList.of(
                        Pair.of(10, SummonFightBehaviors.stopAttackIfTargetInvalid(plugin)),
                        Pair.of(10, SummonFightBehaviors.reachTargetWhenOutOfRange()),
                        Pair.of(10, SummonFightBehaviors.meleeAttack(plugin))
                ));
            } catch (Throwable ignored) {}
        }

        SummonBrainReflect.setCoreActivities(brain, ImmutableSet.of(Activity.CORE));
        SummonBrainReflect.setDefaultActivity(brain, Activity.IDLE);
        SummonBrainReflect.useDefaultActivity(brain);
        try { this.brain = brain; } catch (Throwable ignored) {}
        try { if (dev.gloved.glovedplugin.DebugLog.isEnabled()) GlovedPlugin.logDebug("[SummonBrain] refreshed " + "PiglinSummon" + " uuid=" + getBukkitUniqueId()); } catch (Throwable ignored) {}
        return brain;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty,
                                        EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        try {
            ProfilerFiller filler = Profiler.get();
            filler.push("piglinSummonBrain");
            try { this.getBrain().tick(level, this); } catch (Throwable ignored) {}
            filler.pop();
            suppressVanillaBehaviors();
            try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {
                try { SummonBrainUtil.updateActivity(this); } catch (Throwable ignored2) {}
            }
            suppressVanillaBehaviors();
        } catch (Throwable t) {
            try { SummonBrainUtil.debugFailSafe(plugin, "PiglinSummon.customServerAiStep", t); } catch (Throwable ignored) {}
        }
    }

    private void suppressVanillaBehaviors() {
        try { this.setImmuneToZombification(true); } catch (Throwable ignored) {}
        trySetCannotHunt();
        eraseMemoryByName("ADMIRING_ITEM");
        eraseMemoryByName("ADMIRING_DISABLED");
        eraseMemoryByName("DANCING");
        eraseMemoryByName("CELEBRATE_LOCATION");
        eraseMemoryByName("AVOID_TARGET");
        eraseMemoryByName("NEAREST_REPELLENT");
        eraseMemoryByName("ANGRY_AT");
        eraseMemoryByName("UNIVERSAL_ANGER");
        eraseMemoryByName("TIME_TRYING_TO_REACH_ADMIRE_ITEM");
        eraseMemoryByName("HUNTED_RECENTLY");
        eraseMemoryByName("RIDE_TARGET");
        try { this.setBaby(false); } catch (Throwable ignored) {}
        try {
            if (!this.isHolding(Items.CROSSBOW) && this.getMainHandItem().isEmpty()) {
                this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CROSSBOW));
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void eraseMemoryByName(String fieldName) {
        try {
            java.lang.reflect.Field f = MemoryModuleType.class.getField(fieldName);
            Object v = f.get(null);
            if (v instanceof MemoryModuleType type) {
                this.getBrain().eraseMemory(type);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount) {
        try {
            if (SummonBrainUtil.shouldIgnoreDamageFromOwner(this, damageSource)) return false;
            return super.hurtServer(level, damageSource, amount);
        } catch (Throwable t) {
            try { return super.hurtServer(level, damageSource, amount); } catch (Throwable t2) { return false; }
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    public boolean isConverting() { return false; }
    public boolean isImmuneToZombification() { return true; }
    protected boolean canHunt() { return false; }
    public void setDancing(boolean flag) {}
    public boolean isBaby() { return false; }
    public void setBaby(boolean baby) { try { super.setBaby(false); } catch (Throwable ignored) {} }
    public boolean startRiding(Entity entity, boolean flag, boolean flag2) { return false; }

    @Override public void checkDespawn() { }
    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
