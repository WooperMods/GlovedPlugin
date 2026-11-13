package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainReflect;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonCoreBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonFightBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonIdleBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl.BreezeLongJumpBehavior;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl.BreezeShootBehavior;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.List;
import java.util.UUID;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;

/**
 * Breeze summon brained like CombatPets BreezePet (core/idle/fight + shoot/long-jump),
 * adapted to Gloved 32-block range/targeting and SummonAI-driven cooldown/damage integration.
 */
public class BreezeSummon extends Breeze implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public BreezeSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.BREEZE, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.refreshBrain(this, (Brain<Breeze>) this.getBrain()); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Breeze> brainProvider() {
        return SummonBrain.brainProvider(this, List.<MemoryModuleType<?>>of(
                MemoryModuleType.BREEZE_JUMP_COOLDOWN,
                MemoryModuleType.BREEZE_JUMP_INHALING,
                MemoryModuleType.BREEZE_JUMP_TARGET,
                MemoryModuleType.BREEZE_LEAVING_WATER,
                MemoryModuleType.BREEZE_SHOOT,
                MemoryModuleType.BREEZE_SHOOT_CHARGING,
                MemoryModuleType.BREEZE_SHOOT_COOLDOWN,
                MemoryModuleType.BREEZE_SHOOT_RECOVERING
        ));
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
    public Brain<Breeze> refreshBrain(Breeze pet, Brain<Breeze> brain) {
        if (brain == null) return brain;

        // CombatPets BreezePet-like brain structure (Gloved behaviors/range/damage integration)
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
                    Pair.of(10, new BreezeShootBehavior(plugin)),
                    Pair.of(10, new BreezeLongJumpBehavior())
            ));
        } catch (Throwable ignored) {}

        SummonBrainReflect.setCoreActivities(brain, ImmutableSet.of(Activity.CORE));
        SummonBrainReflect.setDefaultActivity(brain, Activity.IDLE);
        SummonBrainReflect.useDefaultActivity(brain);
        try { brain.setMemoryWithExpiry(MemoryModuleType.BREEZE_SHOOT, Unit.INSTANCE, 1L); } catch (Throwable ignored) {}
        try { this.brain = brain; } catch (Throwable ignored) {}
        try { if (DebugLog.isEnabled()) GlovedPlugin.logDebug("[SummonBrain] refreshed " + "BreezeSummon" + " uuid=" + getBukkitUniqueId()); } catch (Throwable ignored) {}
        return brain;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // CombatPets-style tick (no vanilla AI fallback). Breeze shoot/jump cycle managed below.
        try {
            ProfilerFiller filler = Profiler.get();
            filler.push("breezeSummonBrain");
            try { this.getBrain().tick(level, this); } catch (Throwable ignored) {}
            filler.popPush("breezeSummonActivityUpdate");
            updateActivity();
            filler.pop();
        } catch (Throwable ignored) {}
    }

    protected void updateActivity() {
        try {
            Brain<Breeze> brain = this.getBrain();
            boolean hasTarget = brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET);
            if (hasTarget) {
                try {
                    boolean jumping = brain.hasMemoryValue(MemoryModuleType.BREEZE_JUMP_INHALING)
                            || brain.hasMemoryValue(MemoryModuleType.BREEZE_JUMP_TARGET);
                    boolean busyShoot = brain.hasMemoryValue(MemoryModuleType.BREEZE_SHOOT)
                            || brain.hasMemoryValue(MemoryModuleType.BREEZE_SHOOT_COOLDOWN)
                            || brain.hasMemoryValue(MemoryModuleType.BREEZE_SHOOT_CHARGING)
                            || brain.hasMemoryValue(MemoryModuleType.BREEZE_SHOOT_RECOVERING);
                    if (!jumping && !busyShoot) {
                        brain.setMemoryWithExpiry(MemoryModuleType.BREEZE_SHOOT, Unit.INSTANCE, 60L);
                    }
                } catch (Throwable ignored) {}
                try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {
                    SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.FIGHT);
                }
            } else {
                try { brain.eraseMemory(MemoryModuleType.BREEZE_SHOOT); } catch (Throwable ignored) {}
                try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {
                    SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.IDLE);
                }
            }
            try { this.setAggressive(hasTarget); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @Override public void checkDespawn() { }
    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
