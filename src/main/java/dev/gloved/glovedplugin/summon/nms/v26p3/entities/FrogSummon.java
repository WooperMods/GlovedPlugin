package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainReflect;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonCoreBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonFightBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonIdleBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl.ShootTongueBehavior;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.UUID;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;

/**
 * Frog summon brained like CombatPets FrogPet (core/idle/fight/swim, croak, tongue),
 * but using Gloved 32-block range/targeting and SummonAI soul-stats/damage pipeline in tongue behavior.
 */
public class FrogSummon extends Frog implements SummonEntity {
    private static final UniformInt TIME_BETWEEN_LONG_JUMPS = UniformInt.of(100, 140);

    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public FrogSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.FROG, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.refreshBrain(this, (Brain<Frog>) this.getBrain()); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Frog> brainProvider() {
        try {
            return SummonBrain.brainProvider(this, java.util.List.of(MemoryModuleType.LONG_JUMP_COOLDOWN_TICKS));
        } catch (Throwable t) {
            return SummonBrain.brainProvider(this);
        }
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
    public Brain<Frog> refreshBrain(Frog pet, Brain<Frog> brain) {
        if (brain == null) return brain;

        // CombatPets FrogPet-like structure (adapted to Gloved behaviors/range/damage)
        try {
            SummonBrainReflect.addActivityPairs(brain, Activity.CORE, ImmutableList.of(
                    Pair.of(0, SummonCoreBehaviors.lookAtTarget()),
                    Pair.of(0, SummonCoreBehaviors.moveToTarget()),
                    Pair.of(0, new CountDownCooldownTicks(MemoryModuleType.LONG_JUMP_COOLDOWN_TICKS)),
                    Pair.of(0, SummonFightBehaviors.stopAngryIfTargetDead()),
                    Pair.of(0, SummonFightBehaviors.stopAttackIfTargetInvalid(plugin))
            ));
        } catch (Throwable ignored) {}

        try {
            SummonBrainReflect.addActivityPairs(brain, Activity.IDLE, ImmutableList.of(
                    Pair.of(10, new RunOne(ImmutableList.of(Pair.of(SummonIdleBehaviors.lookAtOwner(plugin), 1)))),
                    Pair.of(10, SummonIdleBehaviors.followOwner(plugin)),
                    Pair.of(10, new RunOne(
                            ImmutableMap.of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT),
                            ImmutableList.of(Pair.of(new Croak(), 3))
                    )),
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
                    Pair.of(10, new ShootTongueBehavior(plugin)) // Gloved SummonAI/cooldown+soul damage pipeline
            ));
        } catch (Throwable ignored) {}

        // SWIM activity (CombatPets parity)
        try {
            SummonBrainReflect.addActivityWithConditions(brain, Activity.SWIM, ImmutableList.of(
                    Pair.of(0, SummonIdleBehaviors.lookAtOwner(plugin)),
                    Pair.of(2, SummonFightBehaviors.autoTargetAndAttack(plugin)),
                    Pair.of(5, new GateBehavior(
                            ImmutableMap.of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT),
                            ImmutableSet.of(),
                            GateBehavior.OrderPolicy.ORDERED, GateBehavior.RunningPolicy.TRY_ALL,
                            ImmutableList.of(
                                    Pair.of(RandomStroll.swim(0.75F), 1),
                                    Pair.of(SummonIdleBehaviors.followOwner(plugin), 1),
                                    Pair.of(SetWalkTargetFromLookTarget.create(1.0F, 3), 1),
                                    Pair.of(BehaviorBuilder.triggerIf(Entity::isInWater), 5)
                            )
                    ))
            ), ImmutableSet.of());
        } catch (Throwable ignored) {}

        SummonBrainReflect.setCoreActivities(brain, ImmutableSet.of(Activity.CORE));
        SummonBrainReflect.setDefaultActivity(brain, Activity.IDLE);
        SummonBrainReflect.useDefaultActivity(brain);
        try { brain.setMemory(MemoryModuleType.LONG_JUMP_COOLDOWN_TICKS, sampleLongJumpCooldown()); } catch (Throwable ignored) {}
        try { this.brain = brain; } catch (Throwable ignored) {}
        try { if (DebugLog.isEnabled()) GlovedPlugin.logDebug("[SummonBrain] refreshed " + "FrogSummon" + " uuid=" + getBukkitUniqueId()); } catch (Throwable ignored) {}
        return brain;
    }

    private int sampleLongJumpCooldown() {
        try { return TIME_BETWEEN_LONG_JUMPS.sample(this.getRandom()); } catch (Throwable ignored) {}
        try { return TIME_BETWEEN_LONG_JUMPS.sample(this.level().getRandom()); } catch (Throwable ignored) {}
        return 100;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // CombatPets-style tick; do not call super to avoid vanilla frog AI interference.
        try {
            ProfilerFiller filler = Profiler.get();
            filler.push("frogSummonBrain");
            try { this.getBrain().tick(level, this); } catch (Throwable ignored) {}
            filler.popPush("frogSummonActivityUpdate");
            try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {}
            filler.pop();
        } catch (Throwable ignored) {}
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
