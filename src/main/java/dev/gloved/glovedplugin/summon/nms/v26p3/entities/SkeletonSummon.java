package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.*;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonBowAttackGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonMeleeAttackGoal;
import net.minecraft.world.InteractionHand;

public class SkeletonSummon extends Skeleton implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private SummonBowAttackGoal bowGoal;
    private SummonMeleeAttackGoal meleeGoal;

    private int weaponReassessTicks;

    public SkeletonSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.SKELETON, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        try {
            this.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
            // AbstractSkeleton ranged attacks require a projectile item to be available.
            this.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW));
        } catch (Throwable ignored) {}
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
        GlovedPlugin.logDebug("[Summon] SkeletonSummon goals initialized uuid=" + getBukkitUniqueId());
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
        GlovedPlugin.logDebug("[Summon] SkeletonSummon weapon goal uuid=" + getBukkitUniqueId() + " bow=" + hasBow);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        try { super.customServerAiStep(level); } catch (Throwable ignored) {}
        // Periodic reassessment so runtime equipment swaps reselect bow/melee without pathing fights.
        if (++weaponReassessTicks >= 20) {
            weaponReassessTicks = 0;
            try { reassessWeaponGoal(); } catch (Throwable ignored) {}
        }
        // Best-effort freeze suppression for mappings where conversion state can tick.
        try { this.setTicksFrozen(0); } catch (Throwable ignored) {}
    }

    @Override
    public boolean isSunBurnTick() {
        return false;
    }

    @Override
    public void performRangedAttack(net.minecraft.world.entity.LivingEntity target, float velocity) {
        try {
            org.bukkit.entity.LivingEntity bukkitShooter = (org.bukkit.entity.LivingEntity) this.getBukkitEntity();
            org.bukkit.entity.LivingEntity bukkitTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (bukkitShooter == null || bukkitTarget == null || bukkitShooter.getWorld() == null) return;

            org.bukkit.Location from = bukkitShooter.getEyeLocation();
            org.bukkit.Location to = bukkitTarget.getEyeLocation();
            org.bukkit.util.Vector dir = to.toVector().subtract(from.toVector());
            if (dir.lengthSquared() < 1.0E-6) return;
            dir.normalize();

            double power = Math.max(0.1D, Math.min(1.0D, velocity));
            org.bukkit.util.Vector vel = dir.multiply(power * 3.0D);
            // slight upward compensation similar to vanilla ranged aiming
            vel.setY(vel.getY() + (to.getY() - from.getY()) * 0.02D);

            org.bukkit.entity.Arrow arrow = bukkitShooter.launchProjectile(org.bukkit.entity.Arrow.class, vel);
            try { arrow.setShooter(bukkitShooter); } catch (Throwable ignored) {}
            try { arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED); } catch (Throwable ignored) {}
            try { arrow.setCritical(power >= 1.0F); } catch (Throwable ignored) {}
            // Damage/owner credit is converted by DamageProxy on hit; keep vanilla arrow as projectile carrier.
        } catch (Throwable t) {
            try { super.performRangedAttack(target, velocity); } catch (Throwable ignored) {}
        }
    }

    // Best-effort 26.3 freeze-conversion suppression hook (mapping-dependent signature).
    public boolean isFreezeConverting() {
        return false;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor accessor, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData groupData) {
        // CombatPets parity: suppress vanilla spawn equipment/behavior randomization for summons.
        return groupData;
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
