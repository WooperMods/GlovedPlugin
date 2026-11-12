package dev.gloved.glovedplugin.nms.v26p3.goals;

import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CombatPets-like vex follow/hover behavior: randomize hover position around owner when idle.
 */
public class SummonVexFollowOwnerGoal extends Goal {
    private final Mob mob;

    public SummonVexFollowOwnerGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
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
        LivingEntity target = mob.getTarget();
        if (target != null && target.isAlive()) return false;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return false;
        try {
            if (!mob.getMoveControl().hasWanted() && mob.getRandom().nextInt(7) == 0) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void tick() {
        Player p = resolveOwner();
        if (p == null) return;
        double range = 4.0D;
        double dx = p.getX() + ThreadLocalRandom.current().nextDouble(-range / 2.0D, range / 2.0D);
        double dy = p.getY() + 1.0D + ThreadLocalRandom.current().nextDouble(0.0D, range / 2.0D);
        double dz = p.getZ() + ThreadLocalRandom.current().nextDouble(-range / 2.0D, range / 2.0D);
        try {
            mob.getMoveControl().setWantedPosition(dx, dy, dz, 1.0D);
        } catch (Throwable ignored) {}
    }
}
