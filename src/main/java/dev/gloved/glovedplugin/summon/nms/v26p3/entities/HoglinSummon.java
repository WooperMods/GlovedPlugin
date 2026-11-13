package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import com.mojang.serialization.Dynamic;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainUtil;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.hoglin.HoglinBase;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;

/**
 * Hoglin summon brained like CombatPets HoglinPet, adapted to Gloved 32-block range and
 * SummonAI/soul-stats damage pipeline.
 */
public class HoglinSummon extends Hoglin implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public HoglinSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.HOGLIN, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        try { this.setImmuneToZombification(true); } catch (Throwable ignored) {}
        trySetCannotBeHunted();
    }

    private void trySetCannotBeHunted() {
        for (String name : new String[]{"cannotBeHunted", "bQ", "cannot_be_hunted"}) {
            try {
                java.lang.reflect.Field f = Hoglin.class.getDeclaredField(name);
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
        try { this.refreshBrain(this, (Brain<Hoglin>) this.getBrain()); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Hoglin> brainProvider() {
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
    public Brain<Hoglin> refreshBrain(Hoglin pet, Brain<Hoglin> brain) {
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
        // CombatPets-style: brain tick + activity update only (no vanilla Hoglin AI side effects).
        try {
            ProfilerFiller filler = Profiler.get();
            filler.push("hoglinSummonBrain");
            try { this.getBrain().tick(level, this); } catch (Throwable ignored) {}
            filler.pop();
            suppressVanillaBehaviors();
            try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {
                try { SummonBrainUtil.updateActivity(this); } catch (Throwable ignored2) {}
            }
            suppressVanillaBehaviors();
        } catch (Throwable t) {
            try { SummonBrainUtil.debugFailSafe(plugin, "HoglinSummon.customServerAiStep", t); } catch (Throwable ignored) {}
        }
    }

    private void suppressVanillaBehaviors() {
        try { this.setImmuneToZombification(true); } catch (Throwable ignored) {}
        trySetCannotBeHunted();
        eraseMemoryByName("AVOID_TARGET");
        eraseMemoryByName("NEAREST_REPELLENT");
        eraseMemoryByName("NEAREST_VISIBLE_ADULT_PIGLIN");
        eraseMemoryByName("NEAREST_VISIBLE_ADULT_PIGLINS");
        eraseMemoryByName("NEAREST_VISIBLE_HUNTABLE_HOGLIN");
        eraseMemoryByName("HUNTED_RECENTLY");
        eraseMemoryByName("RIDE_TARGET");
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
    public boolean doHurtTarget(ServerLevel level, Entity entity) {
        if (entity instanceof LivingEntity target) {
            try { this.handleEntityEvent((byte) 4); } catch (Throwable ignored) {}
            try { this.level().broadcastEntityEvent(this, (byte) 4); } catch (Throwable ignored) {}
            try { this.playSound(SoundEvents.HOGLIN_ATTACK, 1.0F, this.getVoicePitch()); } catch (Throwable ignored) {}
            try {
                // Route damage through Gloved summon pipeline (soul stats/cooldowns/owner credit/progression).
                org.bukkit.entity.Player owner = SummonBrainAI.resolveOwnerBukkit(this);
                var ai = plugin != null ? plugin.getSummonAI() : null;
                if (owner == null || ai == null) return false;
                org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.getBukkitEntity();
                org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
                long now = org.bukkit.Bukkit.getCurrentTick();
                return ai.performSummonAttack(bSummon, owner, bTarget, now, SummonAI.AttackOptions.meleePipeline()) > 0.0;
            } catch (Throwable t) {
                // Best-effort fallback keeps knockback/impact feel if pipeline unavailable.
                try { return HoglinBase.hurtAndThrowTarget(level, this, target); } catch (Throwable ignored) { return false; }
            }
        }
        return false;
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

    @Override
    public boolean isImmuneToZombification() { return true; }

    @Override
    public boolean isConverting() { return false; }

    @Override
    protected void ageBoundaryReached() {}

    @Override
    public boolean canBeHunted() { return false; }

    @Override public void checkDespawn() { }
    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
