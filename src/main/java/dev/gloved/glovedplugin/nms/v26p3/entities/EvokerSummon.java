package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonSpellGoalHelper;
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
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.illager.Evoker;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

public class EvokerSummon extends Evoker implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public EvokerSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.EVOKER, (Level) level);
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
        addEvokerSpellGoals();
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    private void addEvokerSpellGoals() {
        // CombatPets parity: explicit casting + attack spell goals (attack goal drives fangs/summon-vex/wololo selection in vanilla).
        boolean added = false;
        try {
            Goal casting = SummonSpellGoalHelper.instantiateBySimpleName(this, Evoker.class, "EvokerCastingSpellGoal");
            if (casting == null) casting = SummonSpellGoalHelper.instantiateFromHierarchy(this, Evoker.class, Evoker.class, "EvokerCastingSpellGoal");
            if (casting != null) { this.goalSelector.addGoal(1, casting); added = true; }

            Goal attack = SummonSpellGoalHelper.instantiateBySimpleName(this, Evoker.class, "EvokerAttackSpellGoal");
            if (attack == null) attack = SummonSpellGoalHelper.instantiateFromHierarchy(this, Evoker.class, Evoker.class, "EvokerAttackSpellGoal");
            if (attack != null) { this.goalSelector.addGoal(5, attack); added = true; }
        } catch (Throwable ignored) {}

        // Fallback: reflection-safe scan for mapped/variant inner spell goals.
        if (!added) {
            try {
                for (Class<?> inner : Evoker.class.getDeclaredClasses()) {
                    String n = inner.getSimpleName().toLowerCase(Locale.ROOT);
                    if (!Goal.class.isAssignableFrom(inner)) continue;
                    if (n.contains("cast") || n.contains("spell")) {
                        try {
                            var c = inner.getDeclaredConstructor(Evoker.class);
                            c.setAccessible(true);
                            Goal g = (Goal) c.newInstance(this);
                            int prio = n.contains("cast") ? 1 : 5;
                            this.goalSelector.addGoal(prio, g);
                            added = true;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
        // If no inner spell goals are resolvable in this mapping, fail-open: summon still follows/targets without casting.
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    public boolean canAttack(LivingEntity entity) {
        try { return entity != null && entity.canBeSeenAsEnemy(); } catch (Throwable t) { return super.canAttack(entity); }
    }

    @Override
    public boolean isAlliedTo(@Nullable Team team) { return false; }

    // --- Raid/patrol/celebration/pickup/leader suppressions (best-effort for illagers in 26.3) ---
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
