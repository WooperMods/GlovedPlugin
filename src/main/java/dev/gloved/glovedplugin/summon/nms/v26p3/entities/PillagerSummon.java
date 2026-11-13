package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.*;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.UUID;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonCrossbowAttackGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonMeleeAttackGoal;

public class PillagerSummon extends Pillager implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public PillagerSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.PILLAGER, (Level) level);
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
        // Crossbow combat parity: cooldown-aware firing/LOS/state pacing via SummonCrossbowAttackGoal.
        this.goalSelector.addGoal(3, new SummonCrossbowAttackGoal(this, plugin, 1.0D, 8.0F));
        this.goalSelector.addGoal(4, new SummonMeleeAttackGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        // Suppress vanilla patrol/raid/captain equipment randomization for summons.
        return groupData;
    }

    @Override
    public boolean canAttack(LivingEntity entity) {
        // Keep vanilla hostility baseline; summon targeting/protection is enforced by SummonAutoTargetGoal/CombatTargetRules.
        try { return entity != null && entity.canBeSeenAsEnemy(); } catch (Throwable t) { return super.canAttack(entity); }
    }

    @Override
    public boolean isAlliedTo(@Nullable Team team) { return false; }

    // --- Raid/patrol/celebration/pickup/leader suppressions (CombatPets parity) ---
    @Override public boolean canJoinPatrol() { return false; }
    @Override public boolean canJoinRaid() { return false; }
    @Override public void setCanJoinRaid(boolean flag) { }
    @Override public void setCurrentRaid(@Nullable Raid raid) { }
    @Override public @Nullable Raid getCurrentRaid() { return null; }
    @Override public boolean isPatrolling() { return false; }
    @Override public boolean isPatrolLeader() { return false; }
    @Override public void setPatrolling(boolean value) { }
    @Override public void setPatrolLeader(boolean value) { }
    @Override public void setPatrolTarget(BlockPos pos) { }
    @Override public void setWave(int wave) { }
    @Override public boolean isCelebrating() { return false; }
    @Override protected void pickUpItem(ServerLevel level, ItemEntity itemEntity) { }
    @Override public boolean canBeLeader() { return false; }
    @Override public void findPatrolTarget() { }
    @Override public void applyRaidBuffs(ServerLevel level, int wave, boolean unused) { }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
