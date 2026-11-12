package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonGhastAerialMoveGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonGhastFireballGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class GhastSummon extends Ghast implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public GhastSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.GHAST, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        // Custom aerial follow/positioning (target-aware hover) instead of ground navigation follow.
        this.goalSelector.addGoal(2, new SummonGhastAerialMoveGoal(this));
        this.goalSelector.addGoal(3, new SummonGhastFireballGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
