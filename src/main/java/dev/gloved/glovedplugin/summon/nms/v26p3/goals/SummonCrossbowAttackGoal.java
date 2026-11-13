package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

/** Crossbow attack goal for summon pillagers/illagers, adapted for GlovedPlugin cooldown/crediting. */
public class SummonCrossbowAttackGoal extends Goal {
    public static final UniformInt PATHFINDING_DELAY_RANGE = TimeUtil.rangeOfSeconds(1, 2);

    private enum State { UNCHARGED, CHARGING, CHARGED, READY_TO_ATTACK }

    private final Mob mob;
    private final RangedAttackMob rangedMob;
    private final CrossbowAttackMob crossbowMob;
    private final GlovedPlugin plugin;
    private final double speedModifier;
    private final float attackRadiusSqr;
    private int seeTime;
    private int updatePathDelay;
    private State state = State.UNCHARGED;

    public SummonCrossbowAttackGoal(Mob mob, GlovedPlugin plugin, double speedModifier, float attackRadius) {
        if (!(mob instanceof RangedAttackMob ram) || !(mob instanceof CrossbowAttackMob cam)) {
            throw new IllegalArgumentException("SummonCrossbowAttackGoal requires RangedAttackMob & CrossbowAttackMob");
        }
        this.mob = mob;
        this.rangedMob = ram;
        this.crossbowMob = cam;
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

    private boolean isHoldingCrossbow() {
        try { return this.mob.isHolding(Items.CROSSBOW); } catch (Throwable t) { return true; }
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive() && this.isHoldingCrossbow();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() || (!this.mob.getNavigation().isDone() && this.isHoldingCrossbow());
    }

    @Override
    public void stop() {
        super.stop();
        try {
            if (this.mob.isUsingItem()) {
                this.mob.stopUsingItem();
                this.crossbowMob.setChargingCrossbow(false);
                this.mob.getUseItem().set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY);
            }
        } catch (Throwable ignored) {}
        this.state = State.UNCHARGED;
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    private boolean canRun() { return this.state == State.UNCHARGED; }

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

        boolean hasLos = this.mob.getSensing().hasLineOfSight(target);
        boolean seen = this.seeTime > 0;
        if (hasLos != seen) this.seeTime = 0;
        if (hasLos) ++this.seeTime; else --this.seeTime;

        double distSq = this.mob.distanceToSqr(target);
        boolean far = (distSq > (double) this.attackRadiusSqr || this.seeTime < 5);
        if (far) {
            if (--this.updatePathDelay <= 0) {
                this.mob.getNavigation().moveTo(target, this.canRun() ? this.speedModifier : this.speedModifier * 0.5D);
                this.updatePathDelay = PATHFINDING_DELAY_RANGE.sample(this.mob.getRandom());
            }
        } else {
            this.updatePathDelay = 0;
            this.mob.getNavigation().stop();
        }
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (this.state == State.UNCHARGED) {
            if (!far) {
                try {
                    this.mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, Items.CROSSBOW));
                    this.state = State.CHARGING;
                    this.crossbowMob.setChargingCrossbow(true);
                } catch (Throwable ignored) {}
            }
        } else if (this.state == State.CHARGING) {
            if (!this.mob.isUsingItem()) {
                this.state = State.UNCHARGED;
            } else {
                int useTicks = this.mob.getTicksUsingItem();
                ItemStack item = this.mob.getUseItem();
                if (useTicks >= CrossbowItem.getChargeDuration(item, this.mob)) {
                    this.mob.releaseUsingItem();
                    this.state = State.CHARGED;
                    this.crossbowMob.setChargingCrossbow(false);
                }
            }
        } else if (this.state == State.CHARGED) {
            // wait for cooldown window / LOS before firing
            SummonCombatService ai = plugin.getSummonCombatService();
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.mob.getBukkitEntity();
            long now = Bukkit.getCurrentTick();
            if (hasLos && (ai == null || ai.canAttackNow(bSummon, owner, now))) {
                this.state = State.READY_TO_ATTACK;
            }
        } else if (this.state == State.READY_TO_ATTACK && hasLos) {
            SummonCombatService ai = plugin.getSummonCombatService();
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.mob.getBukkitEntity();
            long now = Bukkit.getCurrentTick();
            // Gate by cooldown; consume on projectile impact in DamageProxy for consistent conversion.
            if (ai == null || ai.canAttackNow(bSummon, owner, now)) {
                try { this.rangedMob.performRangedAttack(target, 1.0F); } catch (Throwable ignored) {}
                try { this.mob.getUseItem().set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY); } catch (Throwable ignored) {}
                if (DebugLog.isEnabled()) {
                    GlovedPlugin.logDebug("[Summon] Crossbow shot fired by " + bSummon.getUniqueId()
                            + " owner=" + owner.getUniqueId() + " target=" + target.getBukkitEntity().getUniqueId());
                }
            }
            this.state = State.UNCHARGED;
        }
    }
}
