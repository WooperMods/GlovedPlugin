package dev.gloved.glovedplugin.nms.v26p3.goals;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Movement-only chase goal for summons that need to approach a target without dealing
 * melee damage through the standard attack pipeline (e.g. creepers that explode).
 */
public class SummonChaseTargetGoal extends Goal {
    private final Mob mob;
    private final GlovedPlugin plugin;
    private final double speedModifier;
    private final double stopDistanceSq;
    private int repathCooldown;

    public SummonChaseTargetGoal(Mob mob, GlovedPlugin plugin) {
        this(mob, plugin, 1.0D, 2.25D); // stop ~1.5 blocks from target center/swell range
    }

    public SummonChaseTargetGoal(Mob mob, GlovedPlugin plugin, double speedModifier, double stopDistance) {
        this.mob = mob;
        this.plugin = plugin;
        this.speedModifier = speedModifier;
        this.stopDistanceSq = stopDistance * stopDistance;
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
            return !CombatTargetRules.isProtected(plugin, owner, bTarget);
        } catch (Throwable ignored) {
            return true;
        }
    }

    @Override
    public void start() {
        this.repathCooldown = 0;
    }

    @Override
    public void stop() {
        try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;
        try { mob.getLookControl().setLookAt(target, 30.0F, 30.0F); } catch (Throwable ignored) {}

        double distSq = this.mob.distanceToSqr(target);
        if (--repathCooldown <= 0) {
            repathCooldown = 4 + this.mob.getRandom().nextInt(7);
            if (distSq > this.stopDistanceSq) {
                try { mob.getNavigation().moveTo(target, speedModifier); } catch (Throwable ignored) {}
            } else {
                try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
            }
        } else if (distSq <= this.stopDistanceSq) {
            try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
        }
    }
}
