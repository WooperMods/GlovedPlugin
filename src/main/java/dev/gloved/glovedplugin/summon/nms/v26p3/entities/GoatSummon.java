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
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl.PrepareRamTargetBehavior;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
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
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.CountDownCooldownTicks;
import net.minecraft.world.entity.ai.behavior.RamTarget;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;

/**
 * Goat summon brained like CombatPets GoatPet (core/idle/fight/ram),
 * adapted to Gloved 32-block range/targeting and SummonAI soul-stats/damage pipeline.
 */
public class GoatSummon extends Goat implements SummonEntity {
    public static final UniformInt TIME_BETWEEN_RAMS = UniformInt.of(100, 300);
    private static final TargetingConditions RAM_TARGET_CONDITIONS = TargetingConditions.forCombat();

    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public GoatSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.GOAT, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.refreshBrain(this, (Brain<Goat>) this.getBrain()); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Goat> brainProvider() {
        return SummonBrain.brainProvider(this, List.<MemoryModuleType<?>>of(
                MemoryModuleType.RAM_COOLDOWN_TICKS,
                MemoryModuleType.RAM_TARGET
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
    public Brain<Goat> refreshBrain(Goat pet, Brain<Goat> brain) {
        if (brain == null) return brain;

        ToIntFunction<Goat> cooldownOnFail = g -> minRamCooldown();

        BehaviorControl<?> ramTargetBehavior = null;
        try {
            ramTargetBehavior = new RamTarget(
                    goat -> TIME_BETWEEN_RAMS,
                    RAM_TARGET_CONDITIONS,
                    3.0F,
                    goat -> goat.isBaby() ? 1.0D : 2.5D,
                    goat -> goat.isScreamingGoat() ? SoundEvents.GOAT_SCREAMING_RAM_IMPACT : SoundEvents.GOAT_RAM_IMPACT,
                    goat -> SoundEvents.GOAT_HORN_BREAK
            );
        } catch (Throwable ignored) {}
        BehaviorControl<?> prepareRamBehavior = new PrepareRamTargetBehavior<>(
                cooldownOnFail,
                4, 10, 1.5F, 20,
                goat -> goat.isScreamingGoat() ? SoundEvents.GOAT_SCREAMING_PREPARE_RAM : SoundEvents.GOAT_PREPARE_RAM
        );

        try {
            SummonBrainReflect.addActivityPairs(brain, Activity.CORE, ImmutableList.of(
                    Pair.of(0, SummonCoreBehaviors.lookAtTarget()),
                    Pair.of(0, SummonCoreBehaviors.moveToTarget()),
                    Pair.of(0, SummonCoreBehaviors.swim()),
                    Pair.of(0, new CountDownCooldownTicks(MemoryModuleType.RAM_COOLDOWN_TICKS)),
                    Pair.of(0, SummonFightBehaviors.stopAngryIfTargetDead()),
                    Pair.of(0, SummonFightBehaviors.stopAttackIfTargetInvalid(plugin))
            ));
        } catch (Throwable ignored) {}

        try {
            SummonBrainReflect.addActivityPairs(brain, Activity.IDLE, ImmutableList.of(
                    Pair.of(10, new RunOne(ImmutableList.of(Pair.of(SummonIdleBehaviors.lookAtOwner(plugin), 1)))),
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

        // Fight fallback melee (SummonAI-integrated) in addition to RAM activity
        try {
            SummonBrainReflect.addActivityPairs(brain, Activity.FIGHT, ImmutableList.of(
                    Pair.of(10, SummonFightBehaviors.stopAttackIfTargetInvalid(plugin)),
                    Pair.of(10, SummonFightBehaviors.reachTargetWhenOutOfRange()),
                    Pair.of(10, SummonFightBehaviors.meleeAttack(plugin))
            ));
        } catch (Throwable ignored) {}

        if (ramTargetBehavior != null) {
            try {
                SummonBrainReflect.addActivityWithConditions(brain, Activity.RAM, ImmutableList.of(
                        Pair.of(0, ramTargetBehavior),
                        Pair.of(1, prepareRamBehavior)
                ), ImmutableSet.of(
                        Pair.of(MemoryModuleType.RAM_COOLDOWN_TICKS, MemoryStatus.VALUE_ABSENT)
                ));
            } catch (Throwable ignored) {}
        }

        SummonBrainReflect.setCoreActivities(brain, ImmutableSet.of(Activity.CORE));
        SummonBrainReflect.setDefaultActivity(brain, Activity.IDLE);
        SummonBrainReflect.useDefaultActivity(brain);
        try { brain.setMemory(MemoryModuleType.RAM_COOLDOWN_TICKS, sampleRamCooldown()); } catch (Throwable ignored) {}
        try { this.brain = brain; } catch (Throwable ignored) {}
        try { if (DebugLog.isEnabled()) GlovedPlugin.logDebug("[SummonBrain] refreshed " + "GoatSummon" + " uuid=" + getBukkitUniqueId()); } catch (Throwable ignored) {}
        return brain;
    }

    private static int minRamCooldown() {
        try { return (int) UniformInt.class.getMethod("getMinValue").invoke(TIME_BETWEEN_RAMS); } catch (Throwable ignored) {}
        try { return (int) UniformInt.class.getMethod("minInclusive").invoke(TIME_BETWEEN_RAMS); } catch (Throwable ignored) {}
        try { return (int) UniformInt.class.getMethod("getMin").invoke(TIME_BETWEEN_RAMS); } catch (Throwable ignored) {}
        return 100;
    }

    private int sampleRamCooldown() {
        try { return TIME_BETWEEN_RAMS.sample(this.getRandom()); } catch (Throwable ignored) {}
        try { return TIME_BETWEEN_RAMS.sample(this.level().getRandom()); } catch (Throwable ignored) {}
        return minRamCooldown();
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // CombatPets-style tick (no vanilla AI fallback).
        try {
            ProfilerFiller filler = Profiler.get();
            filler.push("goatSummonBrain");
            try { this.getBrain().tick(level, this); } catch (Throwable t) { dbg("brain.tick err=" + t.getClass().getSimpleName()); }
            filler.popPush("goatSummonActivityUpdate");
            updateActivity();
            filler.pop();
            dbg("tick end active=" + safeActive() + " target=" + (getTarget()!=null) + " walk=" + getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET) + " look=" + getBrain().hasMemoryValue(net.minecraft.world.entity.ai.memory.MemoryModuleType.LOOK_TARGET));
        } catch (Throwable t) { dbg("customServerAiStep err=" + t.getClass().getSimpleName()); }
    }

    private String safeActive() {
        try { return String.valueOf(getBrain().getActiveNonCoreActivity()); } catch (Throwable ignored) {}
        try { return String.valueOf(getBrain().getActiveActivities()); } catch (Throwable ignored2) {}
        return "?";
    }
    private void dbg(String msg) {
        try {
            if (!DebugLog.isEnabled()) return;
            if ((tickCount % 40L) != 0L) return;
            String id = "?"; try { id = getBukkitEntity().getUniqueId().toString().substring(0,8); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[GoatSummonAI] " + id + " " + msg);
        } catch (Throwable ignored) {}
    }

    protected void updateActivity() {
        try {
            Brain<Goat> brain = this.getBrain();
            if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                boolean ramPreferred = brain.hasMemoryValue(MemoryModuleType.RAM_TARGET)
                        || !brain.hasMemoryValue(MemoryModuleType.RAM_COOLDOWN_TICKS);
                try {
                    SummonBrainReflect.setActiveActivityToFirstValid(brain, ImmutableList.of(Activity.RAM, Activity.FIGHT));
                } catch (Throwable ignored) {
                    if (ramPreferred) SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.RAM);
                    SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.FIGHT);
                }
            } else {
                try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {
                    SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.IDLE);
                }
            }
            try { this.setAggressive(brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /**
     * Route vanilla ram/melee hurt through Gloved summon pipeline for owner credit/progression and APS/cooldowns.
     * RamTarget/vanilla attacks call doHurtTarget; we gate with SummonAI cooldown and protections.
     */
    @Override
    public boolean doHurtTarget(ServerLevel level, Entity entity) {
        try {
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity target)) return false;
            org.bukkit.entity.Player owner = SummonBrainAI.resolveOwnerBukkit(this);
            var ai = plugin != null ? plugin.getSummonCombatService() : null;
            if (owner == null || ai == null) return false;
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.getBukkitEntity();
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                try { this.getBrain().eraseMemory(MemoryModuleType.RAM_TARGET); } catch (Throwable ignored) {}
                try { this.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET); } catch (Throwable ignored) {}
                try { this.setTarget(null); } catch (Throwable ignored) {}
                return false;
            }
            long now = Bukkit.getCurrentTick();
            if (!ai.tryConsumeAttackCooldown(bSummon, owner, now)) return false;
            ai.applySummonHit(bSummon, owner, bTarget, 0.0, now, sharedData != null ? sharedData.getProfileName() : null);
            try { this.getBrain().setMemory(MemoryModuleType.RAM_COOLDOWN_TICKS, sampleRamCooldown()); } catch (Throwable ignored) {}
            try { this.getBrain().eraseMemory(MemoryModuleType.RAM_TARGET); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void ageBoundaryReached() {}

    @Override
    public boolean dropHorn() {
        return false;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
