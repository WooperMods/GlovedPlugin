package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
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

        if (this.mob.isUsingItem()) {
            if (!hasLos && this.seeTime < -60) {
                this.mob.stopUsingItem();
            } else if (hasLos) {
                int useTicks = this.mob.getTicksUsingItem();
                if (useTicks >= 20) {
                    SummonAI ai = plugin.getSummonAI();
                    Player p = resolveOwner();
                    org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.mob.getBukkitEntity();
                    long now = Bukkit.getCurrentTick();
                    // Gate by cooldown; consume on projectile impact in DamageProxy for consistent conversion.
                    if (ai == null || ai.canAttackNow(bSummon, p, now)) {
                        this.mob.stopUsingItem();
                        this.rangedMob.performRangedAttack(target, BowItem.getPowerForTime(useTicks));
                        if (DebugLog.isEnabled()) {
                            GlovedPlugin.logDebug("[Summon] Bow shot fired by " + bSummon.getUniqueId()
                                    + " owner=" + p.getUniqueId() + " target=" + target.getBukkitEntity().getUniqueId());
                        }
                    } else {
                        // keep drawing briefly, but avoid spamming attempts
                        this.mob.stopUsingItem();
                    }
                }
            }
        } else if (this.seeTime >= -60) {
            try {
                this.mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, Items.BOW));
            } catch (Throwable ignored) {}
        }
    }
}
