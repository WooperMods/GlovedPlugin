package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.*;
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
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

public class IllusionerSummon extends Illusioner implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public IllusionerSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.ILLUSIONER, (Level) level);
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
        addIllusionerSpellGoals();
        // Bow + spell coordination parity: bow at priority 6 (mirror/blindness/casting at 1/4/5).
        try { this.goalSelector.addGoal(6, new SummonBowAttackGoal(this, plugin, 0.5D, 15.0F)); } catch (Throwable ignored) {}
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    private void addIllusionerSpellGoals() {
        boolean added = false;
        try {
            // Casting spell goal is typically SpellcasterIllager.SpellcasterCastingSpellGoal; resolve reflectively across hierarchy.
            Goal casting = SummonSpellGoalHelper.instantiateFromHierarchy(this, SpellcasterIllager.class, SpellcasterIllager.class, "SpellcasterCastingSpellGoal");
            if (casting == null) casting = SummonSpellGoalHelper.instantiateBySimpleName(this, Illusioner.class, "IllusionerCastingSpellGoal");
            if (casting != null) { this.goalSelector.addGoal(1, casting); added = true; }

            // Mirror/blindness spell goals (inner classes of Illusioner). Names vary by mapping; match by keyword.
            Goal mirror = SummonSpellGoalHelper.instantiateBySimpleName(this, Illusioner.class, "IllusionerMirrorSpellGoal");
            if (mirror == null) mirror = firstByKeyword(new String[]{"mirror"}, new String[]{"cast"}, 4);
            if (mirror != null) { this.goalSelector.addGoal(4, mirror); added = true; }

            Goal blind = SummonSpellGoalHelper.instantiateBySimpleName(this, Illusioner.class, "IllusionerBlindnessSpellGoal");
            if (blind == null) blind = firstByKeyword(new String[]{"blind"}, new String[]{"cast"}, 5);
            if (blind != null) { this.goalSelector.addGoal(5, blind); added = true; }
        } catch (Throwable ignored) {}

        // Fallback: best-effort scan of any Illusioner inner spell goals (preserve exclusions/priorities by name).
        if (!added) {
            try {
                for (Class<?> inner : Illusioner.class.getDeclaredClasses()) {
                    if (!Goal.class.isAssignableFrom(inner)) continue;
                    try {
                        var c = inner.getDeclaredConstructor(Illusioner.class);
                        c.setAccessible(true);
                        Goal g = (Goal) c.newInstance(this);
                        String n = inner.getSimpleName().toLowerCase(Locale.ROOT);
                        int prio = n.contains("cast") ? 1 : (n.contains("mirror") ? 4 : (n.contains("blind") ? 5 : 4));
                        this.goalSelector.addGoal(prio, g);
                        added = true;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        // Fail-open if mapping prevents resolution.
    }

    private Goal firstByKeyword(String[] include, String[] exclude, int ignoredPriority) {
        try {
            for (var e : SummonSpellGoalHelper.instantiateInnerGoals(this, Illusioner.class, include, exclude, 0).keySet()) {
                return e;
            }
        } catch (Throwable ignored) {}
        return null;
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
