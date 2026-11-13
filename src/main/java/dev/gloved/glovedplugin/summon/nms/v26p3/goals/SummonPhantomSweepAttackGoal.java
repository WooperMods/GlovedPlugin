package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Phantom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Phantom sweep/attack goal integrated with Gloved summon cooldown and damage pipeline.
 * Triggers when in swoop-like proximity to target; actual movement/orbit is handled by Phantom AI/orbit goal.
 */
public class SummonPhantomSweepAttackGoal extends Goal {
    private final Phantom phantom;
    private final GlovedPlugin plugin;

    public SummonPhantomSweepAttackGoal(Phantom phantom, GlovedPlugin plugin) {
        this.phantom = phantom;
        this.plugin = plugin;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    private Player resolveOwner() {
        try {
            if (!(phantom instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean canUse() {
        LivingEntity target = phantom.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity target = phantom.getTarget();
        if (target == null) return;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return;

        try {
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                phantom.setTarget(null);
                return;
            }
        } catch (Throwable ignored) {}

        // Proximity window approximating phantom swoop contact.
        double distSq = phantom.distanceToSqr(target);
        boolean touching = phantom.getBoundingBox().inflate(0.2D).intersects(target.getBoundingBox());
        if (!touching && distSq > 9.0D) return;

        SummonAI ai = plugin != null ? plugin.getSummonAI() : null;
        long now = Bukkit.getCurrentTick();
        try {
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) phantom.getBukkitEntity();
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (ai == null) return;
            boolean cdOk = ai.canAttackNow(bSummon, owner, now);
            if (!cdOk) return;
            try { phantom.playSound(SoundEvents.PHANTOM_BITE, 1.0F, 1.0F); } catch (Throwable ignored) {}
            ai.performSummonAttack(bSummon, owner, bTarget, now, SummonAI.AttackOptions.meleePipeline());
        } catch (Throwable ignored) {}
    }
}
