package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;

import java.util.UUID;
import java.util.function.BiConsumer;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainUtil;

public class AllaySummon extends Allay implements SummonEntity {
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private boolean brainInitialized;

    public AllaySummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.ALLAY, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Allay> brainProvider() {
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
    public Brain<Allay> refreshBrain(Brain<Allay> brain) {
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
        // Brained summon: clear legacy goal/target selectors and wire summon brain graph.
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        ensureBrain();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    protected void customServerAiStep(ServerLevel level) {
        try {
            if (!brainInitialized) ensureBrain();
            try { ((Brain) this.getBrain()).tick(level, this); } catch (Throwable ignored) {}
            SummonBrainAI.updateActivity(this);
        } catch (Throwable ignored) {
            // 26.3 mapping differences: brain tick/activity handled best-effort.
        }
    }

    @Override
    public void aiStep() {
        try { super.aiStep(); } catch (Throwable ignored) {}
    }

    @Override
    public void updateDynamicGameEventListener(BiConsumer<DynamicGameEventListener<?>, ServerLevel> consumer) {
        // Suppress vanilla note-block/item listener behavior for summon control.
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

    @Override public void checkDespawn() { }
    @Override public boolean removeWhenFarAway(double distanceToClosestPlayer) { return false; }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected void hurtArmor(DamageSource source, float amount) {
        this.doHurtEquipment(source, amount, ARMOR_SLOTS);
    }

    @Override
    protected void dropEquipment(ServerLevel level) {
        // Summons should not drop equipment/inventory on death.
    }

    @Override
    public boolean wantsToPickUp(ServerLevel level, ItemStack itemstack) {
        return false;
    }

    @Override
    public boolean isPanicking() {
        return false;
    }

    @Override
    public boolean canDuplicate() {
        return false;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
