package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;

public class OcelotSummon extends Ocelot implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public OcelotSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.OCELOT, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        try { this.setTrusting(true); } catch (Throwable ignored) {}
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        // CombatPets-style ocelot combat: leap + attack (damage routed through SummonCombatService pipeline).
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(7, new LeapAtTargetGoal(this, 0.3f));
        this.goalSelector.addGoal(8, new SummonMeleeAttackGoal(this, plugin));
        this.goalSelector.addGoal(9, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Suppress trust/taming/feeding interactions.
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    // Suppress vanilla trust-goal reassessment that can reintroduce unwanted behavior.
    protected void reassessTrustingGoals() {
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
