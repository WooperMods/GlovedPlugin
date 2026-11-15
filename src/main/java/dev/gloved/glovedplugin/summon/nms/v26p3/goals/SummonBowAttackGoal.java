package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

/** Bow attack goal for summon skeleton-likes, adapted for GlovedPlugin cooldown/crediting. */
public class SummonBowAttackGoal extends Goal {
    private final Mob mob;
    private final RangedAttackMob rangedMob;
    private final GlovedPlugin plugin;
    private final double speedModifier;
    private final float attackRadiusSqr;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;
    private int attackTime = -1;

    public SummonBowAttackGoal(Mob mob, GlovedPlugin plugin, double speedModifier, float attackRadius) {
        if (!(mob instanceof RangedAttackMob ram)) {
            throw new IllegalArgumentException("SummonBowAttackGoal requires RangedAttackMob");
        }
        this.mob = mob;
        this.rangedMob = ram;
        this.plugin = plugin;
        this.speedModifier = speedModifier;
        this.attackRadiusSqr = attackRadius * attackRadius;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    protected Player resolveOwner() {
        try {
            if (!(mob instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) { return null; }
    }

    protected boolean isHoldingBow() {
        try { return this.mob.isHolding(Items.BOW); } catch (Throwable t) { return true; }
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive() && this.isHoldingBow();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse() || (!this.mob.getNavigation().isDone() && this.isHoldingBow());
    }

    @Override
    public void start() { this.mob.setAggressive(true); }

    @Override
    public void stop() {
        this.mob.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
        try { this.mob.stopUsingItem(); } catch (Throwable ignored) {}
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return;
        try {
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) { this.mob.setTarget(null); return; }
        } catch (Throwable ignored) {}

        double distSq = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean hasLos = this.mob.getSensing().hasLineOfSight(target);
        boolean seen = this.seeTime > 0;
        if (hasLos != seen) this.seeTime = 0;
        if (hasLos) ++this.seeTime; else --this.seeTime;

        if (distSq <= (double) this.attackRadiusSqr && this.seeTime >= 20) {
            this.mob.getNavigation().stop();
            ++this.strafingTime;
        } else {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
            this.strafingTime = -1;
        }

        if (this.strafingTime >= 20) {
            if ((double) this.mob.getRandom().nextFloat() < 0.3D) this.strafingClockwise = !this.strafingClockwise;
            if ((double) this.mob.getRandom().nextFloat() < 0.3D) this.strafingBackwards = !this.strafingBackwards;
            this.strafingTime = 0;
        }
        if (this.strafingTime > -1) {
            if (distSq > (double) (this.attackRadiusSqr * 0.75F)) this.strafingBackwards = false;
            else if (distSq < (double) (this.attackRadiusSqr * 0.25F)) this.strafingBackwards = true;
            this.mob.getMoveControl().strafe(this.strafingBackwards ? -0.5F : 0.5F, this.strafingClockwise ? 0.5F : -0.5F);
            try { this.mob.lookAt(target, 30.0F, 30.0F); } catch (Throwable ignored) {}
        } else {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }

        if (--this.attackTime > 0) {
            // Respect attack pacing; avoid drawing/cancelling repeatedly while on cooldown.
            if (this.mob.isUsingItem()) {
                try { this.mob.stopUsingItem(); } catch (Throwable ignored) {}
            }
            return;
        }

        SummonCombatService ai = plugin.getSummonCombatService();
        org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.mob.getBukkitEntity();
        long now = Bukkit.getCurrentTick();
        boolean ready = ai == null || ai.canAttackNow(bSummon, owner, now);

        if (this.mob.isUsingItem()) {
            int useTicks = this.mob.getTicksUsingItem();
            if (useTicks >= 20) {
                if (hasLos) {
                    boolean consumed = ai == null || ai.tryConsumeAttackCooldown(bSummon, owner, now);
                    if (consumed) {
                        if (ai != null) {
                            try { ai.markPendingProjectileCooldownConsumed(bSummon, now); } catch (Throwable ignored) {}
                        }
                        this.mob.stopUsingItem();
                        try {
                            this.rangedMob.performRangedAttack(target, BowItem.getPowerForTime(useTicks));
                        } catch (Throwable t) {
                            if (DebugLog.isEnabled()) {
                                GlovedPlugin.logDebug("[Summon] Bow performRangedAttack failed for " + bSummon.getUniqueId() + ": " + t);
                            }
                        }
                        if (DebugLog.isEnabled()) {
                            GlovedPlugin.logDebug("[Summon] Bow shot fired by " + bSummon.getUniqueId()
                                    + " owner=" + owner.getUniqueId() + " target=" + target.getBukkitEntity().getUniqueId()
                                    + " cdConsumedAtFire=true power=" + BowItem.getPowerForTime(useTicks));
                        }
                        this.attackTime = 20; // fallback pacing; real cadence comes from attack cooldown
                    } else {
                        // Cooldown not ready at release; stop drawing and retry later.
                        this.mob.stopUsingItem();
                        this.attackTime = 4;
                        if (DebugLog.isEnabled()) {
                            GlovedPlugin.logDebug("[Summon] Bow release deferred (cooldown) by " + bSummon.getUniqueId());
                        }
                    }
                } else if (this.seeTime < -20 || useTicks > 60) {
                    // Prevent getting stuck holding a fully drawn bow when LOS is lost.
                    this.mob.stopUsingItem();
                    this.attackTime = 4;
                }
            } else if (!hasLos && this.seeTime < -60) {
                this.mob.stopUsingItem();
            }
        } else if (ready && hasLos && this.seeTime >= -60) {
            try {
                this.mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, Items.BOW));
                if (DebugLog.isEnabled()) {
                    GlovedPlugin.logDebug("[Summon] Bow draw started by " + bSummon.getUniqueId()
                            + " target=" + target.getBukkitEntity().getUniqueId());
                }
            } catch (Throwable ignored) {}
        } else if (this.attackTime < 0) {
            this.attackTime = 0;
        }
    }
}
