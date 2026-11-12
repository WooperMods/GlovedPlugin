package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class IronGolemSummon extends IronGolem implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public IronGolemSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.IRON_GOLEM, (Level) level);
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

    // Best-effort 26.3 parity hooks (kept without @Override for mapping safety).
    protected void doPush(Entity entity) {
        try {
            // Avoid mutual push jank; targeting/protection enforced by Gloved summon systems.
            entity.push(this);
        } catch (Throwable ignored) {}
    }

    protected void doPushSafe(Entity entity) {
        doPush(entity);
    }

    @Override
    public void push(Entity entity) {
        // Fallback for mappings where push hooks differ.
        try {
            super.push(entity);
        } catch (Throwable ignored) {}
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    public boolean canAttackType(EntityType<?> entitytypes) {
        return true;
    }

    public boolean canAttackTypeSafe(EntityType<?> entitytypes) {
        return canAttackType(entitytypes);
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
