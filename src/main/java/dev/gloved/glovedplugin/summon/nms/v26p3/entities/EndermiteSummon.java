package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.ClimbOnTopOfPowderSnowGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class EndermiteSummon extends Endermite implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public EndermiteSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.ENDERMITE, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new ClimbOnTopOfPowderSnowGoal(this, this.level()));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(2, new SummonMeleeAttackGoal(this, plugin, 1.0D));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        // Suppress vanilla limited-life/player-spawned endermite despawn behavior for summons.
        try { this.setPersistenceRequired(); } catch (Throwable ignored) {}
        try {
            for (var f : Endermite.class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (n.contains("life") || n.contains("despawn")) {
                        f.setAccessible(true);
                        int v = f.getInt(this);
                        if (v > 0 && v < 1000000) f.setInt(this, Integer.MAX_VALUE / 4);
                    }
                } else if (f.getType() == boolean.class) {
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (n.contains("playerspawn") || n.contains("player_spawn")) {
                        f.setAccessible(true);
                        f.setBoolean(this, false);
                    }
                }
            }
        } catch (Throwable ignored) {}
        super.tick();
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
