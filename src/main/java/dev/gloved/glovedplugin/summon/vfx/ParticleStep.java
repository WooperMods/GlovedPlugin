package dev.gloved.glovedplugin.summon.vfx;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable single particle burst step built via {@link Builder}.
 */
public final class ParticleStep {
    private final Particle particle;
    private final int count;
    private final double offsetX, offsetY, offsetZ;
    private final double extra;
    private final @Nullable Object data;
    private final double atDx, atDy, atDz;

    private ParticleStep(Builder b) {
        this.particle = Objects.requireNonNull(b.particle, "particle");
        this.count = Math.max(0, b.count);
        this.offsetX = b.offsetX; this.offsetY = b.offsetY; this.offsetZ = b.offsetZ;
        this.extra = b.extra;
        this.data = b.data;
        this.atDx = b.atDx; this.atDy = b.atDy; this.atDz = b.atDz;
    }

    public static Builder builder(Particle particle) { return new Builder(particle); }

    public void play(@Nullable World world, @Nullable Location base) {
        if (world == null || base == null) return;
        try {
            Location at = (atDx == 0 && atDy == 0 && atDz == 0) ? base : base.clone().add(atDx, atDy, atDz);
            if (data != null) {
                world.spawnParticle(particle, at, count, offsetX, offsetY, offsetZ, extra, data);
            } else {
                world.spawnParticle(particle, at, count, offsetX, offsetY, offsetZ, extra);
            }
        } catch (Throwable ignored) {}
    }

    public void play(@Nullable Location base) {
        if (base == null) return;
        play(base.getWorld(), base);
    }

    public void playAtEntity(@Nullable Entity entity, double yOffset) {
        if (entity == null || entity.getWorld() == null) return;
        play(entity.getWorld(), entity.getLocation().add(0, yOffset, 0));
    }

    public static final class Builder {
        private final Particle particle;
        private int count = 1;
        private double offsetX, offsetY, offsetZ;
        private double extra;
        private @Nullable Object data;
        private double atDx, atDy, atDz;

        private Builder(Particle particle) { this.particle = particle; }

        public Builder count(int count) { this.count = count; return this; }
        public Builder offset(double x, double y, double z) { this.offsetX = x; this.offsetY = y; this.offsetZ = z; return this; }
        public Builder extra(double extra) { this.extra = extra; return this; }
        public Builder speed(double speed) { return extra(speed); }
        public Builder data(@Nullable Object data) { this.data = data; return this; }
        /** Offset applied to the base play location for this step. */
        public Builder atOffset(double dx, double dy, double dz) { this.atDx = dx; this.atDy = dy; this.atDz = dz; return this; }

        public ParticleStep build() { return new ParticleStep(this); }
    }
}
