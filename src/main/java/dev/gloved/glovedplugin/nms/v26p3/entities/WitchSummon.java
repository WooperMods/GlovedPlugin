package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.*;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.UUID;

public class WitchSummon extends Witch implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public WitchSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.WITCH, (Level) level);
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
        // Potion-throwing ranged behavior parity: cooldown-aware pacing via SummonRangedAttackGoal,
        // delegating to Witch#performRangedAttack so vanilla offensive/defensive potion selection runs.
        this.goalSelector.addGoal(3, new SummonRangedAttackGoal(this, plugin, 1.0D, 10.0F));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        // Suppress vanilla spawn randomization/equipment.
        return groupData;
    }

    @Override
    public boolean canAttack(LivingEntity entity) {
        try { return entity != null && entity.canBeSeenAsEnemy(); } catch (Throwable t) { return super.canAttack(entity); }
    }

    @Override
    public boolean isAlliedTo(@Nullable Team team) { return false; }

    // Suppress unwanted vanilla interactions (pickup). Raider/raid lifecycle methods are illager-specific
    // and may not exist on Witch in 26.3 mappings, so they are intentionally not overridden here.
    @Override
    protected void pickUpItem(ServerLevel level, ItemEntity itemEntity) { }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
