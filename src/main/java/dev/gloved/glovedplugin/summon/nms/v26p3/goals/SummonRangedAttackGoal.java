package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;
import dev.gloved.glovedplugin.core.logging.DebugLog;

/**
 * Ranged attack goal for summons implementing {@link RangedAttackMob}.
 * Projectile damage/crediting is handled by GlovedPlugin's summon damage proxy when the projectile hits;
 * this goal gates firing by the summon attack cooldown.
 */
public class SummonRangedAttackGoal extends Goal {
    protected final Mob mob;
    protected final RangedAttackMob rangedMob;
    protected final GlovedPlugin plugin;
    protected final double speedModifier;
    protected final float attackRadius;
    protected final float attackRadiusSqr;
    protected int seeTime;
    protected int attackTime = -1;

    public SummonRangedAttackGoal(Mob mob, GlovedPlugin plugin, double speedModifier, float attackRadius) {
        this.mob = mob;
        this.rangedMob = (RangedAttackMob) mob;
        this.plugin = plugin;
        this.speedModifier = speedModifier;
        this.attackRadius = attackRadius;
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

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        Player owner = resolveOwner();
        if (owner == null) return false;
        try {
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) mob.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) return false;

            String mode = "AGGRESSIVE";
            try {
                if (plugin.getSoulService() != null) mode = plugin.getSoulService().getSummonCombatMode(owner.getUniqueId());
            } catch (Throwable ignored) {}
            boolean defensive = "DEFENSIVE".equalsIgnoreCase(mode);
            java.util.Set<java.util.UUID> recentDamagerIds = new java.util.HashSet<>(SummonCombatService.getRecentDamagers(owner));
            recentDamagerIds.addAll(SummonCombatService.getRecentDamagers(bSummon));

            boolean isHostile = bTarget instanceof org.bukkit.entity.Monster
                    || bTarget instanceof org.bukkit.entity.Slime
                    || bTarget instanceof org.bukkit.entity.Ghast;
            boolean targetsOwner = false;
            boolean targetsSummon = false;
            if (bTarget instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
                targetsOwner = mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId());
                targetsSummon = mobEnt.getTarget().getUniqueId().equals(bSummon.getUniqueId());
            }
            boolean recent = recentDamagerIds.contains(bTarget.getUniqueId());
            boolean relevant;
            if (defensive) {
                boolean hasLosToOwner = false;
                try { hasLosToOwner = bTarget.hasLineOfSight(owner); } catch (Throwable ignored) {}
                relevant = (targetsOwner || targetsSummon || recent) && hasLosToOwner;
            } else {
                relevant = (isHostile || targetsOwner || targetsSummon || recent);
            }
            if (!relevant) return false;
        } catch (Throwable ignored) {
            return false;
        }
        return true;
    }

    @Override
    public void stop() {
        this.seeTime = 0;
        this.attackTime = -1;
        try { this.mob.setAggressive(false); } catch (Throwable ignored) {}
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
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                this.mob.setTarget(null);
                return;
            }
        } catch (Throwable ignored) {}

        double distSq = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean hasLos = this.mob.getSensing().hasLineOfSight(target);
        if (hasLos) ++this.seeTime; else this.seeTime = 0;

        if (distSq <= (double) this.attackRadiusSqr && this.seeTime >= 5) {
            this.mob.getNavigation().stop();
        } else {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
        }
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (--this.attackTime <= 0 && hasLos) {
            SummonCombatService ai = plugin.getSummonCombatService();
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.mob.getBukkitEntity();
            long now = Bukkit.getCurrentTick();
            // Consume at fire time and let projectile launch tagging mark the spawned projectile as cd_consumed.
            // This prevents impact-time re-consume failures (e.g. cooldown changed) from dropping converted damage.
            boolean consumed = ai == null || ai.tryConsumeAttackCooldown(bSummon, owner, now);
            if (consumed) {
                if (ai != null) {
                    try { ai.markPendingProjectileCooldownConsumed(bSummon, now); } catch (Throwable ignored) {}
                }
                float f = (float) Math.sqrt(distSq) / this.attackRadius;
                float velocity = Mth.clamp(f, 0.1F, 1.0F);
                this.rangedMob.performRangedAttack(target, velocity);
                if (DebugLog.isEnabled()) {
                    GlovedPlugin.logDebug("[Summon] Ranged performRangedAttack fired by " + bSummon.getUniqueId()
                            + " owner=" + owner.getUniqueId() + " target=" + target.getBukkitEntity().getUniqueId() + " cdConsumedAtFire=true");
                }
                this.attackTime = 20; // fallback pacing
            } else {
                this.attackTime = 4;
            }
        } else if (this.attackTime < -60) {
            this.attackTime = 0;
        }
    }
}
