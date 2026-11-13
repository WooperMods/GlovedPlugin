package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import com.mojang.serialization.Dynamic;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainUtil;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;

/**
 * Zoglin summon brained like CombatPets ZoglinPet, adapted to Gloved 32-block range
 * and SummonAI/soul-stats damage pipeline.
 */
public class ZoglinSummon extends Zoglin implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public ZoglinSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.ZOGLIN, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.refreshBrain(this, (Brain<Zoglin>) this.getBrain()); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Zoglin> brainProvider() {
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
    public Brain<Zoglin> refreshBrain(Zoglin pet, Brain<Zoglin> brain) {
        if (brain == null || plugin == null) return brain;
        try { SummonBrain.refreshBrain(plugin, this, (Brain) brain); } catch (Throwable ignored) {}
        try { this.brain = brain; } catch (Throwable ignored) {}
        return brain;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty,
                                        EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // CombatPets-style: brain tick + activity update only.
        try {
            ProfilerFiller filler = Profiler.get();
            filler.push("zoglinSummonBrain");
            try { this.getBrain().tick(level, this); } catch (Throwable ignored) {}
            filler.pop();
            suppressVanillaBehaviors();
            try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {
                try { SummonBrainUtil.updateActivity(this); } catch (Throwable ignored2) {}
            }
            suppressVanillaBehaviors();
        } catch (Throwable t) {
            try { SummonBrainUtil.debugFailSafe(plugin, "ZoglinSummon.customServerAiStep", t); } catch (Throwable ignored) {}
        }
    }

    private void suppressVanillaBehaviors() {
        eraseMemoryByName("NEAREST_VISIBLE_ADULT_PIGLIN");
        eraseMemoryByName("NEAREST_VISIBLE_ADULT_PIGLINS");
        eraseMemoryByName("NEAREST_REPELLENT");
        eraseMemoryByName("AVOID_TARGET");
        eraseMemoryByName("CANT_REACH_WALK_TARGET_SINCE");
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
    protected void playAngrySound() {}

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override public void checkDespawn() { }
    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
