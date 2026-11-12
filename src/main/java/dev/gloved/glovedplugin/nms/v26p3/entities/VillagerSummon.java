package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class VillagerSummon extends Villager implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public VillagerSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.VILLAGER, (Level) level);
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
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Suppress trading UI/interactions.
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    // --- Trade / restock / gossip / breed / golem suppressions (mapping-safe signatures where stable) ---
    public boolean canRestock() { return false; }
    public void restock() {}
    public boolean shouldRestock() { return false; }
    protected void updateTrades() {}
    public void setOffers(MerchantOffers offers) { try { this.offers = null; } catch (Throwable ignored) {} }
    public void increaseMerchantCareer() {}
    public void gossip(ServerLevel level, Villager other, long time) {}
    public boolean wantsToSpawnGolem(long time) { return false; }
    public void releaseAllPois() {}

    // Mapping-safe: no @Override (signature varies across mappings).
    protected void ageBoundaryReached() {}

    public boolean canBreed() { return false; }

    @Override
    public void thunderHit(ServerLevel level, LightningBolt bolt) {
        // Suppress witch conversion; keep summon controlled.
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
