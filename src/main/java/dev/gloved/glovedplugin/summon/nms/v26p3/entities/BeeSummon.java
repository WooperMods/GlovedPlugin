package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.bukkit.event.entity.EntityTargetEvent;

import java.lang.reflect.Field;
import java.util.UUID;

public class BeeSummon extends Bee implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public BeeSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.BEE, (Level) level);
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

    @Override
    public boolean doHurtTarget(ServerLevel level, Entity entity) {
        // Re-assert target after a successful sting hit so the summon stays controlled
        // (CombatPets parity; avoids passive/retreat behavior).
        boolean hurtTarget = super.doHurtTarget(level, entity);
        if (hurtTarget && entity instanceof LivingEntity living && entity.isAlive()) {
            try {
                org.bukkit.entity.Entity bSummon = this.getBukkitEntity();
                org.bukkit.entity.Entity bTarget = living.getBukkitEntity();
                if (bSummon instanceof org.bukkit.entity.Mob bMob && bTarget instanceof org.bukkit.entity.LivingEntity bLiving) {
                    bMob.setTarget(bLiving);
                } else {
                    this.setTarget(living);
                }
            } catch (Throwable ignored) { }
        }
        return hurtTarget;
    }

    @Override
    public void setHasStung(boolean flag) {
        // Prevent sting death/loss of control.
    }

    @Override
    public boolean hasStung() {
        // Prevent sting death/loss of control.
        return false;
    }

    @Override
    public void setSavedFlowerPos(BlockPos blockposition) {
        // Suppress flower memory/pollination side effects.
    }

    @Override
    public void setHivePos(BlockPos blockposition) {
        // Suppress hive memory side effects; try to clear backing field if accessible.
        try {
            Field f = Bee.class.getDeclaredField("hivePos");
            f.setAccessible(true);
            f.set(this, null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
