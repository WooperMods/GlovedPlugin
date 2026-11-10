package dev.gloved.glovedplugin.damagehologram;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Computes hologram spawn location mirroring AuraSkills HologramsHook.getLocation.
 */
public final class DamageHologramLocation {

    private DamageHologramLocation() {}

    public static Location compute(Entity entity, DamageHologramConfig cfg) {
        Location location = entity.getLocation().clone();
        double baseY = entity.getHeight() - entity.getHeight() * 0.1; // 0.9 * height
        if (cfg.randomOffsetEnabled) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            double x = cfg.randomXMin + (cfg.randomXMax - cfg.randomXMin) * r.nextDouble();
            double y = cfg.randomYMin + (cfg.randomYMax - cfg.randomYMin) * r.nextDouble();
            double z = cfg.randomZMin + (cfg.randomZMax - cfg.randomZMin) * r.nextDouble();
            location.add(x, baseY + y, z);
        } else {
            location.add(cfg.offsetX, baseY + cfg.offsetY, cfg.offsetZ);
        }
        return location;
    }
}
