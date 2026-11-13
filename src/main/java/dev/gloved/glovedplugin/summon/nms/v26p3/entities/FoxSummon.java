package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFoxMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFoxStalkGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LeapAtTargetGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.lang.reflect.Method;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;

public class FoxSummon extends Fox implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public FoxSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.FOX, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        // CombatPets-inspired fox behavior: float/state handling, stalk, pounce/leap feel,
        // and state-gated attack routed through SummonAI pipeline.
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FoxFloatGoal(this));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(5, new SummonFoxStalkGoal(this));
        addPounceGoalIfAvailable();
        this.goalSelector.addGoal(7, new SummonFoxMeleeAttackGoal(this, plugin, 1.2D));
        this.goalSelector.addGoal(8, new LeapAtTargetGoal(this, 0.4f));
        this.goalSelector.addGoal(9, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(10, new RandomLookAroundGoal(this));
    }

    private void addPounceGoalIfAvailable() {
        try {
            // Vanilla fox inner pounce goal (name/mapping may vary). Best-effort only.
            for (Class<?> inner : Fox.class.getDeclaredClasses()) {
                String n = inner.getSimpleName();
                if (n == null || !n.toLowerCase().contains("pounce")) continue;
                if (!Goal.class.isAssignableFrom(inner)) continue;
                try {
                    var ctor = inner.getDeclaredConstructor(Fox.class);
                    ctor.setAccessible(true);
                    Object goal = ctor.newInstance(this);
                    if (goal instanceof Goal g) {
                        this.goalSelector.addGoal(6, g);
                        return;
                    }
                } catch (Throwable ignored) {}
                try {
                    var ctor = inner.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    Object goal = ctor.newInstance();
                    if (goal instanceof Goal g) {
                        this.goalSelector.addGoal(6, g);
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Suppress trust/feed/pickup interactions.
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    /** CombatPets-style float that clears fox action states when entering fluid. */
    private static class FoxFloatGoal extends FloatGoal {
        private final FoxSummon fox;

        FoxFloatGoal(FoxSummon fox) {
            super(fox);
            this.fox = fox;
        }

        @Override
        public void start() {
            super.start();
            fox.clearFoxStatesSafe();
        }

        @Override
        public boolean canUse() {
            try {
                if (fox.isInWater() && fox.getFluidHeight(FluidTags.WATER) > 0.25D) return true;
                return fox.isInLava();
            } catch (Throwable t) {
                return super.canUse();
            }
        }
    }

    private void clearFoxStatesSafe() {
        try {
            this.setIsCrouching(false);
            this.setIsInterested(false);
            this.setSitting(false);
            this.setSleeping(false);
            // Try to clear faceplanted/internal flags reflectively for mapping differences.
            try {
                Method m = Fox.class.getDeclaredMethod("setFaceplanted", boolean.class);
                m.setAccessible(true);
                m.invoke(this, false);
            } catch (Throwable ignored) {}
            try {
                Method m = Fox.class.getDeclaredMethod("B", boolean.class);
                m.setAccessible(true);
                m.invoke(this, false);
            } catch (Throwable ignored) {}
            for (String name : new String[]{"clearStates", "gY"}) {
                try {
                    Method m = Fox.class.getDeclaredMethod(name);
                    m.setAccessible(true);
                    m.invoke(this);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
