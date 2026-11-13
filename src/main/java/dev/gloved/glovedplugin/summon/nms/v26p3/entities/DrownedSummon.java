package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.*;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonRangedAttackGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonZombieAttackGoal;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

public class DrownedSummon extends Drowned implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public DrownedSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.DROWNED, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    private boolean isHoldingTrident() {
        try {
            return this.getMainHandItem().is(Items.TRIDENT) || this.getOffhandItem().is(Items.TRIDENT);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        // Trident ranged parity: when holding a trident, use cooldown-aware ranged attacks (vanilla
        // Drowned#performRangedAttack throws the trident). Projectile ownership stays with this summon
        // so DamageProxy can convert hits to owner-credited scaled damage.
        this.goalSelector.addGoal(3, new SummonRangedAttackGoal(this, plugin, 1.0D, 10.0F) {
            @Override
            public boolean canUse() {
                return super.canUse() && DrownedSummon.this.isHoldingTrident();
            }
            @Override
            public boolean canContinueToUse() {
                return super.canContinueToUse() && DrownedSummon.this.isHoldingTrident();
            }
            @Override
            public void start() {
                super.start();
                try { mob.setAggressive(true); } catch (Throwable ignored) {}
                try { mob.startUsingItem(InteractionHand.MAIN_HAND); } catch (Throwable ignored) {}
            }
            @Override
            public void stop() {
                super.stop();
                try { mob.stopUsingItem(); } catch (Throwable ignored) {}
                try { mob.setAggressive(false); } catch (Throwable ignored) {}
            }
        });
        // Melee fallback when not in trident-ranged mode.
        this.goalSelector.addGoal(3, new SummonZombieAttackGoal(this, plugin) {
            @Override
            public boolean canUse() {
                return super.canUse() && !DrownedSummon.this.isHoldingTrident();
            }
            @Override
            public boolean canContinueToUse() {
                return super.canContinueToUse() && !DrownedSummon.this.isHoldingTrident();
            }
        });
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // CombatPets parity: nudge drowned water-seeking/navigation when submerged below sea level.
        try { super.customServerAiStep(level); } catch (Throwable ignored) {}
        try {
            boolean underwater = this.isUnderWater();
            int seaLevel;
            try { seaLevel = this.level().getSeaLevel(); } catch (Throwable t) { seaLevel = 62; }
            this.setSearchingForLand(underwater && this.getY() < (seaLevel - 2));
        } catch (Throwable ignored) {
            // Mapping-safe fallback: skip water-seeking nudge if 26.3 signatures differ.
        }
    }

    @Override
    public boolean isSunSensitive() {
        // Suppress sun burn/conversion behavior.
        return false;
    }

    @Override
    public boolean okTarget(LivingEntity target) {
        // Match CombatPets drowned permissiveness while Gloved targeting rules are enforced by goals/AI.
        return true;
    }

    @Override
    public boolean canBreakDoors() {
        return false;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
