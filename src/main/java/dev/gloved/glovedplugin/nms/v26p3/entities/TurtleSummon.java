package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonMeleeAttackGoal;
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
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.lang.reflect.Method;
import java.util.UUID;

public class TurtleSummon extends Turtle implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public TurtleSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.TURTLE, (Level) level);
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
        this.goalSelector.addGoal(4, new SummonMeleeAttackGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        return groupData;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Suppress breeding/feeding interactions.
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // Best-effort suppression of egg/lay/home state drift that can break summon control.
        tryInvokeNoArg(this, "setHasEgg", boolean.class, false);
        tryInvokeNoArg(this, "setLayingEgg", boolean.class, false);
        tryInvokeNoArg(this, "setGoingHome", boolean.class, false);
    }

    /**
     * CombatPets parity: suppress turtle age boundary behavior.
     * Mapping-safe no-@Override in case signature differs in 26.3.
     */
    protected void ageBoundaryReached() {
        // no-op
    }

    // Suppress breeding where such hooks exist in this mapping.
    public boolean canFallInLove() { return false; }

    private static void tryInvokeNoArg(Object target, String name, Class<?> param, Object value) {
        try {
            Method m = Turtle.class.getMethod(name, param);
            m.invoke(target, value);
        } catch (Throwable ignored) {}
        try {
            Method m = Turtle.class.getDeclaredMethod(name, param);
            m.setAccessible(true);
            m.invoke(target, value);
        } catch (Throwable ignored) {}
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
