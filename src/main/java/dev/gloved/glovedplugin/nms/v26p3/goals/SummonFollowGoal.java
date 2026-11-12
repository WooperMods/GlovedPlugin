package dev.gloved.glovedplugin.nms.v26p3.goals;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CombatPets-style owner follow goal for NMS summons.
 * - Teleports to owner if different level/world or farther than 32 blocks.
 * - Follows when beyond reach (or occasional random tick), stops pathing when close.
 */
public class SummonFollowGoal extends Goal {
    private final Mob mob;
    private final GlovedPlugin plugin;
    private Player ownerBukkit;
    private LivingEntity ownerNms;

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

    public SummonFollowGoal(Mob mob, GlovedPlugin plugin) {
        this.mob = mob;
        this.plugin = plugin;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean resolveOwner() {
        try {
            if (!(mob instanceof SummonEntity se)) return false;
            UUID ownerId = se.getOwnerId();
            if (ownerId == null) return false;
            Player p = Bukkit.getPlayer(ownerId);
            if (p == null || !p.isOnline()) return false;
            this.ownerBukkit = p;
            this.ownerNms = ((CraftPlayer) p).getHandle();
            return this.ownerNms != null;
        } catch (Throwable t) {
            this.ownerBukkit = null;
            this.ownerNms = null;
            return false;
        }
    }

    protected double getReach(LivingEntity target) {
        return (Math.pow(this.mob.getBbWidth() * 2.0F, 2.0D) + target.getBbWidth()) * 1.5D;
    }

    private boolean isTargettedToEnemy() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return false;
        if (!target.isAlive()) {
            this.mob.setTarget(null);
            return false;
        }
        return this.ownerNms == null || !target.equals(this.ownerNms);
    }

    @Override
    public boolean canUse() {
        if (!resolveOwner()) return false;
        if (this.ownerNms == null || !this.ownerNms.isAlive()) return false;

        // Owner-priority recall: always return to owner if too far/different world, even while targeting.
        if (shouldTeleportToOwner()) {
            clearCombatTargetAndRecall();
            return false;
        }

        if (this.isTargettedToEnemy()) return false;
        if (this.mob.isPassenger() || this.mob.getPassengers().contains(this.ownerNms)) return false;

        double distance = mob.distanceToSqr(ownerNms);
        double reach = getReach(ownerNms);
        return distance > reach || ThreadLocalRandom.current().nextFloat() < 0.05F;
    }

    @Override
    public boolean canContinueToUse() {
        if (!resolveOwner()) return false;
        if (this.ownerNms == null || !this.ownerNms.isAlive()) return false;

        // Owner-priority recall: always return to owner if too far/different world, even while targeting.
        if (shouldTeleportToOwner()) {
            clearCombatTargetAndRecall();
            return false;
        }

        if (this.isTargettedToEnemy()) return false;
        if (this.mob.isPassenger()) return false;
        return !mob.getNavigation().isDone();
    }

    private boolean shouldTeleportToOwner() {
        try {
            return mob.level() != ownerNms.level() || mob.distanceTo(ownerNms) > 32.0D;
        } catch (Throwable ignored) {
            try {
                return mob.getBukkitEntity().getWorld() != ownerBukkit.getWorld()
                        || mob.getBukkitEntity().getLocation().distanceSquared(ownerBukkit.getLocation()) > 1024.0D;
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }

    private void clearCombatTargetAndRecall() {
        try {
            dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainAI.clearAttackTarget(this.mob);
        } catch (Throwable ignored) {
            try { this.mob.setTarget(null); } catch (Throwable ignored2) {}
        }
        teleportToOwner();
    }

    private void teleportToOwner() {
        try {
            this.mob.setTarget(null);
            if (ownerBukkit != null) this.mob.getBukkitEntity().teleport(ownerBukkit.getLocation());
            try { this.mob.getNavigation().stop(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        this.ownerBukkit = null;
        this.ownerNms = null;
        try {
            // Fix for movement jitter when trying to reach owner (CombatPets behavior)
            this.mob.getNavigation().moveTo(this.mob, 1.0D);
            this.mob.getNavigation().stop();
        } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        if (!resolveOwner() || ownerNms == null) return;
        try { this.mob.getLookControl().setLookAt(ownerNms, 10.0F, (float) this.mob.getMaxHeadXRot()); } catch (Throwable ignored) {}

        double speedModifier = 1.0D;
        try { if (this.mob.isInWater()) speedModifier = 2.0D; } catch (Throwable ignored) {}

        try {
            if (this.mob instanceof Slime slime) {
                this.mob.lookAt(this.ownerNms, 10.0F, 10.0F);
                try {
                    if (slimeRotate != null) slimeRotate.invoke(slime.getMoveControl(), this.mob.getYRot(), true);
                } catch (Throwable ignored) {}
                this.mob.getMoveControl().setWantedPosition(ownerNms.getX(), ownerNms.getY(), ownerNms.getZ(), speedModifier);
                return;
            }
            if (this.mob.getBukkitEntity() instanceof org.bukkit.entity.Frog) {
                this.mob.lookAt(this.ownerNms, 10.0F, 10.0F);
                this.mob.getMoveControl().setWantedPosition(ownerNms.getX(), ownerNms.getY(), ownerNms.getZ(), speedModifier);
                return;
            }
            if (this.mob instanceof Blaze) {
                this.mob.getNavigation().moveTo(ownerNms.getX(), ownerNms.getY() + 0.5D, ownerNms.getZ(), speedModifier);
                return;
            }
        } catch (Throwable ignored) {}

        Vec3 target = this.ownerNms.position();
        try {
            double distance = this.mob.distanceToSqr(this.ownerNms);
            double reach = this.getReach(this.ownerNms);
            if (distance <= reach) {
                double xOff = ThreadLocalRandom.current().nextDouble(0.25D);
                target = new Vec3(this.ownerNms.getBlockX() + xOff, this.ownerNms.getBlockY(), this.ownerNms.getBlockZ() + xOff);
            }
        } catch (Throwable ignored) {}

        try {
            this.mob.getNavigation().moveTo(target.x, target.y, target.z, speedModifier);
        } catch (Throwable ignored) {}
    }
}
