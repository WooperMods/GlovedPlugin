package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.UUID;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

public class BlazeSummon extends Blaze implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public BlazeSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.BLAZE, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(4, new BlazeFireballGoal(this, plugin));
        this.goalSelector.addGoal(7, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override public boolean isSensitiveToWater() { return false; }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }

    private static void setChargedSafe(Blaze blaze, boolean charged) {
        try {
            Method m = Blaze.class.getDeclaredMethod("setCharged", boolean.class);
            m.setAccessible(true);
            m.invoke(blaze, charged);
        } catch (Throwable ignored) {}
    }

    static class BlazeFireballGoal extends Goal {
        private final Blaze blaze;
        private final GlovedPlugin plugin;
        private int attackStep;
        private int attackTime;
        private int lastSeen;

        BlazeFireballGoal(Blaze blaze, GlovedPlugin plugin) {
            this.blaze = blaze;
            this.plugin = plugin;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        private Player resolveOwner() {
            try {
                if (!(blaze instanceof SummonEntity se)) return null;
                UUID id = se.getOwnerId();
                return id == null ? null : Bukkit.getPlayer(id);
            } catch (Throwable t) { return null; }
        }

        @Override
        public boolean canUse() {
            LivingEntity target = blaze.getTarget();
            return target != null && target.isAlive();
        }

        @Override public void start() { this.attackStep = 0; }

        @Override
        public void stop() {
            this.lastSeen = 0;
            setChargedSafe(blaze, false);
        }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public void tick() {
            --this.attackTime;
            LivingEntity target = blaze.getTarget();
            if (target == null) return;
            Player owner = resolveOwner();
            if (owner == null || !owner.isOnline()) return;
            try {
                org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
                if (CombatTargetRules.isProtected(plugin, owner, bTarget)) { blaze.setTarget(null); return; }
            } catch (Throwable ignored) {}

            boolean hasLos = blaze.getSensing().hasLineOfSight(target);
            if (hasLos) lastSeen = 0; else ++lastSeen;
            double distSq = blaze.distanceToSqr(target);

            if (distSq < 4.0D) {
                if (!hasLos) return;
                if (this.attackTime <= 0) {
                    this.attackTime = 20;
                    SummonCombatService ai = plugin.getSummonCombatService();
                    org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) blaze.getBukkitEntity();
                    long now = Bukkit.getCurrentTick();
                    if (ai == null || ai.tryConsumeAttackCooldown(bSummon, owner, now)) {
                        try { ((org.bukkit.entity.LivingEntity) blaze.getBukkitEntity()).swingMainHand(); } catch (Throwable ignored) {}
                        if (ai != null) ai.applySummonHit(bSummon, owner, (org.bukkit.entity.LivingEntity) target.getBukkitEntity(), 0.0, now, null);
                    }
                }
                blaze.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D);
            } else if (distSq < 1024.0D && hasLos) {
                double dx = target.getX() - blaze.getX();
                double dy = target.getY(0.5D) - blaze.getY(0.5D);
                double dz = target.getZ() - blaze.getZ();
                if (this.attackTime <= 0) {
                    ++this.attackStep;
                    if (this.attackStep == 1) {
                        this.attackTime = 5;
                        setChargedSafe(blaze, true);
                    } else if (this.attackStep <= 4) {
                        this.attackTime = 2;
                    } else {
                        this.attackTime = 20;
                        this.attackStep = 0;
                        setChargedSafe(blaze, false);
                    }

                    if (this.attackStep > 1) {
                        SummonCombatService ai = plugin.getSummonCombatService();
                        org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) blaze.getBukkitEntity();
                        long now = Bukkit.getCurrentTick();
                        if (ai == null || ai.tryConsumeAttackCooldown(bSummon, owner, now)) {
                            double spread = Math.sqrt(Math.sqrt(distSq)) * 0.5D;
                            try {
                                if (!blaze.isSilent()) blaze.level().levelEvent(null, 1018, blaze.blockPosition(), 0);
                                Vec3 dir = new Vec3(blaze.getRandom().triangle(dx, 2.297D * spread), dy, blaze.getRandom().triangle(dz, 2.297D * spread));
                                SmallFireball fireball = new SmallFireball(blaze.level(), blaze, dir.normalize());
                                fireball.setPos(fireball.getX(), blaze.getY(0.5D) + 0.5D, fireball.getZ());
                                // Attribution hardening: projectile impact is converted by DamageProxy; cooldown consumed at fire.
                                try {
                                    org.bukkit.entity.Entity bProj = fireball.getBukkitEntity();
                                    if (bProj instanceof org.bukkit.entity.Projectile proj) {
                                        proj.setShooter(bSummon);
                                        if (blaze instanceof SummonEntity se) {
                                            DamageProxy.tagSummonProjectile(proj, plugin, se.getOwnerId(), se.getBukkitUniqueId());
                                        }
                                        DamageProxy.markProjectileCooldownConsumed(proj, plugin);
                                    }
                                } catch (Throwable ignored) {}
                                blaze.level().addFreshEntity(fireball);
                                if (DebugLog.isEnabled()) {
                                    GlovedPlugin.logDebug("[Summon] Blaze fireball spawned minion=" + bSummon.getUniqueId()
                                            + " owner=" + owner.getUniqueId() + " target=" + target.getBukkitEntity().getUniqueId() + " cdConsumedAtFire=true");
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                blaze.getLookControl().setLookAt(target, 10.0F, 10.0F);
            } else if (this.lastSeen < 5) {
                blaze.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D);
            }
            super.tick();
        }
    }
}
