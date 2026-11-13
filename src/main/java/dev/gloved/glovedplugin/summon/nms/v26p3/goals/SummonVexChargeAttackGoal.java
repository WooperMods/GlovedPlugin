package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Vex;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;

/**
 * CombatPets-like vex charge/attack goal integrated with Gloved summon damage pipeline.
 * Replaces vanilla/inner vex charge behavior so hits are owner-credited via SummonAI and cooldown-gated.
 */
public class SummonVexChargeAttackGoal extends Goal {
    private final Vex vex;
    private final GlovedPlugin plugin;

    public SummonVexChargeAttackGoal(Vex vex, GlovedPlugin plugin) {
        this.vex = vex;
        this.plugin = plugin;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private Player resolveOwner() {
        try {
            if (!(vex instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean canUse() {
        LivingEntity target = vex.getTarget();
        if (target == null || !target.isAlive()) return false;
        try { return vex.getMoveControl().hasWanted() || vex.getRandom().nextInt(7) == 0; } catch (Throwable ignored) {}
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = vex.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public void start() {
        try { vex.setIsCharging(true); } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        try { vex.setIsCharging(false); } catch (Throwable ignored) {}
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = vex.getTarget();
        if (target == null) return;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) {
            try { vex.setIsCharging(false); } catch (Throwable ignored) {}
            return;
        }

        try {
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                vex.setTarget(null);
                return;
            }
        } catch (Throwable ignored) {}

        try { vex.getLookControl().setLookAt(target, 30.0F, 30.0F); } catch (Throwable ignored) {}
        try { vex.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D); } catch (Throwable ignored) {}

        double distSq = vex.distanceToSqr(target);
        double reachSq = getAttackReachSqr(target);
        boolean near = distSq <= reachSq || vex.getBoundingBox().intersects(target.getBoundingBox());
        try { vex.setIsCharging(near || distSq < 16.0D); } catch (Throwable ignored) {}

        if (near) {
            SummonCombatService ai = plugin != null ? plugin.getSummonCombatService() : null;
            long now = Bukkit.getCurrentTick();
            try {
                org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) vex.getBukkitEntity();
                org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
                if (ai != null) {
                    ai.performSummonAttack(bSummon, owner, bTarget, now, SummonCombatService.AttackOptions.meleePipeline());
                }
            } catch (Throwable ignored) {}
        }
    }

    private double getAttackReachSqr(LivingEntity target) {
        double w = vex.getBbWidth();
        double tw = target.getBbWidth();
        double reach = Math.sqrt((w * 2.0D) * (w * 2.0D) + tw) + 1.0D;
        return reach * reach;
    }
}
