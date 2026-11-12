package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class ArmadilloSummon extends Armadillo implements SummonEntity {
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private boolean brainInitialized;

    public ArmadilloSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.ARMADILLO, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Armadillo> brainProvider() {
        try { return SummonBrain.brainProvider(this); } catch (Throwable t) { return null; }
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
                        return this.refreshBrain((Brain) brain);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try { return this.getBrain(); } catch (Throwable t) { return null; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Brain<Armadillo> refreshBrain(Brain<Armadillo> brain) {
        try { if (brain != null && plugin != null) { SummonBrain.refreshBrain(plugin, this, (Brain) brain); brainInitialized = true; } } catch (Throwable ignored) {}
        return brain;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void ensureBrain() {
        try {
            if (plugin != null) {
                Brain brain = this.getBrain();
                if (brain != null) SummonBrain.refreshBrain(plugin, this, brain);
                brainInitialized = true;
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void setGoals() {
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        ensureBrain();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    protected void customServerAiStep(ServerLevel level) {
        try {
            if (!brainInitialized) ensureBrain();
            try { ((Brain) this.getBrain()).tick(level, this); } catch (Throwable ignored) {}
            SummonBrainAI.updateActivity(this);
        } catch (Throwable ignored) {}
    }

    @Override
    public void aiStep() {
        try { super.aiStep(); } catch (Throwable ignored) {}
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount) {
        try {
            if (dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainUtil.shouldIgnoreDamageFromOwner(this, damageSource)) return false;
            return super.hurtServer(level, damageSource, amount);
        } catch (Throwable t) {
            try { return super.hurtServer(level, damageSource, amount); } catch (Throwable t2) { return false; }
        }
    }

    @Override public void checkDespawn() { }
    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void hurtArmor(DamageSource source, float amount) {
        this.doHurtEquipment(source, amount, ARMOR_SLOTS);
    }

    @Override
    public boolean isScaredBy(LivingEntity entity) {
        // Suppress vanilla scare/roll-up reactions that conflict with summon control.
        return false;
    }

    @Override
    public void rollUp() {
        // Prevent involuntary rolling state transitions for summons.
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
