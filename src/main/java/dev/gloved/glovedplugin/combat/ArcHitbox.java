package dev.gloved.glovedplugin.combat;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Arc hit detection. Vanilla only reports a single target; we expand to
 * every {@link LivingEntity} inside {@code reach * rangeMultiplier} whose
 * angle to the look vector is within {@code angle/2}.
 */
public final class ArcHitbox {

    private ArcHitbox() {}

    public record ArcHit(LivingEntity entity, double distance, double angleDeg) {}

    public static List<ArcHit> collect(Player attacker, LivingEntity primary,
                                       double baseReach, AttackStage stage,
                                       WeaponAttributes.HitboxShape shape) {
        double reach = (baseReach + 3.0) * stage.rangeMultiplier();
        // rangeBonus in config is additive over the ~3 block vanilla reach.
        // baseReach passed as bonus; keep total sane.
        reach = Math.min(9.0, Math.max(2.0, reach));
        double halfAngle = stage.angleDegrees() <= 0 ? 180.0 : stage.angleDegrees() / 2.0;

        Location eye = attacker.getEyeLocation();
        Vector look = eye.getDirection().normalize();

        List<ArcHit> hits = new ArrayList<>();
        for (var entity : eye.getWorld().getNearbyEntities(eye, reach, reach / 2 + 2, reach,
                e -> e instanceof LivingEntity le
                        && !le.isDead()
                        && !le.getUniqueId().equals(attacker.getUniqueId()))) {
            LivingEntity le = (LivingEntity) entity;
            Location target = le.getEyeLocation();
            Vector to = target.clone().subtract(eye).toVector();
            double dist = to.length();
            if (dist > reach + le.getBoundingBox().getVolume() * 0.1) continue;
            if (dist < 0.001) {
                hits.add(new ArcHit(le, dist, 0));
                continue;
            }
            to.normalize();
            double angle = Math.toDegrees(Math.acos(Math.clamp(look.dot(to), -1.0, 1.0)));

            if (shape == WeaponAttributes.HitboxShape.VERTICAL_PLANE) {
                // Vertical chop: forgive horizontal error, punish vertical miss.
                double dy = Math.abs(target.getY() - eye.getY());
                if (dy > 2.5) continue;
                if (angle > halfAngle + 25.0) continue;
            } else if (angle > halfAngle) {
                continue;
            }
            hits.add(new ArcHit(le, dist, angle));
        }

        // Primary vanilla target always counts, even at the arc edge.
        if (primary != null && hits.stream().noneMatch(h -> h.entity().equals(primary))) {
            hits.add(new ArcHit(primary, attacker.getLocation().distance(primary.getLocation()), 0));
        }
        hits.sort(Comparator.comparingDouble(ArcHit::distance));
        return hits;
    }
}
