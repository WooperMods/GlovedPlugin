package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.cubemob.Slime;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.UUID;

/**
 * CombatPets-style slime target goal: tracks current target, faces it, drives slime move control
 * toward it, and performs contact damage through the Gloved summon pipeline (cooldown-gated,
 * owner-credited) to avoid duplicate hits.
 */
public class SummonSlimeAttackGoal extends Goal {
    private final Mob mob;
    private final GlovedPlugin plugin;

    private static Method slimeRotate;

    static {
        try {
            for (Class<?> inner : Slime.class.getDeclaredClasses()) {
                try {
                    Method m = inner.getDeclaredMethod("a", float.class, boolean.class);
                    m.setAccessible(true);
                    slimeRotate = m;
                    break;
                } catch (Throwable ignored) {}
                try {
                    Method m = inner.getDeclaredMethod("setDirection", float.class, boolean.class);
                    m.setAccessible(true);
                    slimeRotate = m;
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public SummonSlimeAttackGoal(Mob mob, GlovedPlugin plugin) {
        this.mob = mob;
        this.plugin = plugin;
        // LOOK like CombatPets; MOVE is fine to share with follow goal (mutually exclusive by target state)
        this.setFlags(EnumSet.of(Flag.LOOK, Flag.MOVE));
    }

    private Player resolveOwner() {
        try {
            if (!(mob instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return false;
        try {
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            return !CombatTargetRules.isProtected(plugin, owner, bTarget);
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return;

        try {
            this.mob.lookAt(target, 10.0F, 10.0F);
        } catch (Throwable ignored) {}
        try {
            if (slimeRotate != null) slimeRotate.invoke(this.mob.getMoveControl(), this.mob.getYRot(), true);
        } catch (Throwable ignored) {}
        try {
            this.mob.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D);
        } catch (Throwable ignored) {}

        // Contact damage (equivalent to push/playerTouch collision): cooldown-gated through SummonAI pipeline.
        tryContactDamage(owner, target);
    }

    public void tryContactDamage(Player owner, LivingEntity nmsTarget) {
        if (owner == null || nmsTarget == null || !nmsTarget.isAlive()) return;
        try {
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) mob.getBukkitEntity();
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) nmsTarget.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) return;

            // Contact range similar to slime touch/collision.
            double reachSq = contactReachSqr(nmsTarget);
            if (this.mob.distanceToSqr(nmsTarget) > reachSq) return;

            long now = Bukkit.getCurrentTick();
            SummonCombatService ai = plugin.getSummonCombatService();
            if (ai == null) return;
            // Unified per-summon attack helper (cooldown, soul stats/calc, procs/abilities, pipeline damage).
            ai.performSummonAttack(bSummon, owner, bTarget, now,
                    SummonCombatService.AttackOptions.meleePipeline().swingAnimation(false));
        } catch (Throwable ignored) {}
    }

    private double contactReachSqr(LivingEntity target) {
        double w = this.mob.getBbWidth();
        double tw = target.getBbWidth();
        // Generous contact reach: ~collision distance + small bonus (slime contact is body-collision based)
        double reach = (w * 0.5D) + (tw * 0.5D) + 0.6D;
        reach = Math.max(reach, 1.2D);
        return reach * reach;
    }
}
