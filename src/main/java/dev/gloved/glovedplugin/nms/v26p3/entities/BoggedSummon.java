package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.*;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.UUID;

public class BoggedSummon extends Bogged implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private SummonBowAttackGoal bowGoal;
    private SummonMeleeAttackGoal meleeGoal;
    private int weaponReassessTicks;

    public BoggedSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.BOGGED, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        this.bowGoal = new SummonBowAttackGoal(this, plugin, 1.0D, 15.0F);
        this.meleeGoal = new SummonMeleeAttackGoal(this, plugin);
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        reassessWeaponGoal();
    }

    @Override
    public void reassessWeaponGoal() {
        if (this.level() == null || this.level().isClientSide()) return;
        if (this.bowGoal == null || this.meleeGoal == null) return;
        try { this.goalSelector.removeGoal(this.bowGoal); } catch (Throwable ignored) {}
        try { this.goalSelector.removeGoal(this.meleeGoal); } catch (Throwable ignored) {}
        boolean hasBow = false;
        try {
            ItemStack stack = this.getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, Items.BOW));
            hasBow = stack != null && stack.is(Items.BOW);
        } catch (Throwable ignored) {}
        if (hasBow) this.goalSelector.addGoal(4, this.bowGoal);
        else this.goalSelector.addGoal(4, this.meleeGoal);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        try { super.customServerAiStep(level); } catch (Throwable ignored) {}
        if (++weaponReassessTicks >= 20) {
            weaponReassessTicks = 0;
            try { reassessWeaponGoal(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        // CombatPets parity: suppress vanilla spawn equipment/behavior randomization for summons.
        return groupData;
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // CombatPets parity: block vanilla shearing/interaction side effects on summoned bogged.
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void shear(ServerLevel worldserver, SoundSource soundcategory, ItemStack itemstack) {
        // CombatPets parity: summoned bogged cannot be sheared for mushrooms/arrows.
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
