package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;

public class SummonMeleeAttackGoal extends Goal {
    protected final Mob mob;
    private final GlovedPlugin plugin;
    private final double speedModifier;
    private int repathCooldown;

    public SummonMeleeAttackGoal(Mob mob, GlovedPlugin plugin) {
        this(mob, plugin, 1.1D);
    }

    public SummonMeleeAttackGoal(Mob mob, GlovedPlugin plugin, double speedModifier) {
        this.mob = mob;
        this.plugin = plugin;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Player resolveOwner() {
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
            return relevant;
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void stop() {
        try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return;

        try { mob.getLookControl().setLookAt(target, 30.0F, 30.0F); } catch (Throwable ignored) {}
        double distSq = this.mob.distanceToSqr(target);
        double reach = getAttackReachSqr(target);
        if (--repathCooldown <= 0 || (distSq > reach && repathCooldown <= -20)) {
            repathCooldown = 4 + this.mob.getRandom().nextInt(7);
            if (distSq > reach) {
                try { mob.getNavigation().moveTo(target, speedModifier); } catch (Throwable ignored) {}
            } else {
                try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
            }
        }

        if (distSq <= reach) {
            long now = Bukkit.getCurrentTick();
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) mob.getBukkitEntity();
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            SummonCombatService ai = plugin.getSummonCombatService();
            if (ai != null) {
                // Unified per-summon attack helper (soul stats/calc, cooldown, procs/abilities, pipeline damage).
                ai.performSummonAttack(bSummon, owner, bTarget, now, SummonCombatService.AttackOptions.meleePipeline());
            }
        }
    }

    protected double getAttackReachSqr(LivingEntity target) {
        // Vanilla melee reach + 2 block bonus (matches legacy behavior intent)
        double w = this.mob.getBbWidth();
        double tw = target.getBbWidth();
        double reach = Math.sqrt((w * 2.0) * (w * 2.0) + tw) + 2.0;
        return reach * reach;
    }
}
