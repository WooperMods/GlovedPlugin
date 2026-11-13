package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Aerial follow/positioning for ghast summons (CombatPets parity).
 * Moves toward the current combat target when it is far away, otherwise hovers near the owner.
 * Uses MoveControl rather than ground navigation for stable flight.
 */
public class SummonGhastAerialMoveGoal extends Goal {
    private final Mob mob;

    public SummonGhastAerialMoveGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private Player resolveOwnerBukkit() {
        try {
            if (!(mob instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private LivingEntity resolveFollow() {
        try {
            LivingEntity target = mob.getTarget();
            if (target != null && target.isAlive()) return target;
        } catch (Throwable ignored) {}
        try {
            Player p = resolveOwnerBukkit();
            if (p == null) return null;
            return ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle();
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public boolean canUse() {
        if (!ensureOwnerProximity()) return false;
        // Always eligible when owner/follow context is valid.
        return resolveFollow() != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!ensureOwnerProximity()) return false;
        return resolveFollow() != null;
    }

    /**
     * Safety parity with {@link SummonFollowGoal}: teleport to owner if cross-world or too far.
     * Returns false when a teleport recovery was performed or owner is unavailable.
     */
    private boolean ensureOwnerProximity() {
        try {
            Player owner = resolveOwnerBukkit();
            if (owner == null || !owner.isOnline()) return false;
            LivingEntity ownerNms = ((org.bukkit.craftbukkit.entity.CraftPlayer) owner).getHandle();
            if (ownerNms == null) return false;
            if (mob.level() != ownerNms.level() || mob.distanceTo(ownerNms) > 32.0D) {
                teleportToOwner(owner);
                return false;
            }
            return true;
        } catch (Throwable t) {
            try {
                Player owner = resolveOwnerBukkit();
                if (owner == null) return false;
                if (mob.getBukkitEntity().getWorld() != owner.getWorld()
                        || mob.getBukkitEntity().getLocation().distanceSquared(owner.getLocation()) > 1024.0D) {
                    teleportToOwner(owner);
                    return false;
                }
                return owner.isOnline();
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private void teleportToOwner(Player owner) {
        try { mob.setTarget(null); } catch (Throwable ignored) {}
        try { if (owner != null) mob.getBukkitEntity().teleport(owner.getLocation()); } catch (Throwable ignored) {}
        try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
    }

    // canContinueToUse handled above with proximity safety.

    @Override
    public void tick() {
        LivingEntity follow = resolveFollow();
        if (follow == null) return;

        LivingEntity target = null;
        try { target = mob.getTarget(); } catch (Throwable ignored) {}

        double x;
        double y;
        double z;
        if (target != null && target.isAlive()) {
            // CombatPets parity: hover offset behind/around the target for firing positioning.
            try {
                Location loc = target.getBukkitEntity().getLocation();
                Vector dir = loc.getDirection();
                loc = loc.clone().subtract(dir.multiply(4.0D));
                x = loc.getX();
                z = loc.getZ();
                y = target.getY() + 4.0D;
            } catch (Throwable t) {
                x = target.getX();
                y = target.getY() + 4.0D;
                z = target.getZ();
            }
        } else {
            Player owner = resolveOwnerBukkit();
            if (owner == null) return;
            x = owner.getX();
            y = owner.getY() + 2.5D;
            z = owner.getZ();
        }

        try {
            mob.getMoveControl().setWantedPosition(x, y, z, 1.0D);
        } catch (Throwable ignored) {}
        try {
            mob.lookAt(follow, 30.0F, 30.0F);
        } catch (Throwable ignored) {}
    }
}
