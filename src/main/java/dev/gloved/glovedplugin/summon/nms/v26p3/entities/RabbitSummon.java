package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.UUID;

public class RabbitSummon extends Rabbit implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public RabbitSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.RABBIT, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(5, new SummonMeleeAttackGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setVariant(Variant variant) {
        // Killer-bunny safe variant handling (CombatPets parity): set data directly to avoid
        // vanilla EVIL-rabbit goal/attribute side effects when possible.
        if (variant == Variant.EVIL) {
            try {
                EntityDataAccessor<Integer> accessor = findVariantAccessor();
                if (accessor != null) {
                    this.entityData.set(accessor, variant.id());
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        super.setVariant(variant);
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Integer> findVariantAccessor() throws Exception {
        for (String name : new String[]{"DATA_TYPE_ID", "DATA_VARIANT_ID"}) {
            try {
                Field f = Rabbit.class.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof EntityDataAccessor<?> acc) {
                    return (EntityDataAccessor<Integer>) acc;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
