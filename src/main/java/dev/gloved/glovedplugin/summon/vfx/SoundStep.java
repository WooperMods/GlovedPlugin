package dev.gloved.glovedplugin.summon.vfx;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable sound step built via {@link Builder}.
 */
public final class SoundStep {
    public enum Target { WORLD, PLAYER }

    private final Sound sound;
    private final float volume;
    private final float pitch;
    private final Target target;
    private final double atDx, atDy, atDz;

    private SoundStep(Builder b) {
        this.sound = Objects.requireNonNull(b.sound, "sound");
        this.volume = b.volume;
        this.pitch = b.pitch;
        this.target = b.target;
        this.atDx = b.atDx; this.atDy = b.atDy; this.atDz = b.atDz;
    }

    public static Builder builder(Sound sound) { return new Builder(sound); }

    public void play(@Nullable World world, @Nullable Location base, @Nullable Player player) {
        try {
            if (target == Target.PLAYER) {
                if (player == null || !player.isOnline()) return;
                Location at = player.getLocation();
                if (atDx != 0 || atDy != 0 || atDz != 0) at = at.clone().add(atDx, atDy, atDz);
                player.playSound(at, sound, volume, pitch);
                return;
            }
            if (world == null || base == null) return;
            Location at = (atDx == 0 && atDy == 0 && atDz == 0) ? base : base.clone().add(atDx, atDy, atDz);
            world.playSound(at, sound, volume, pitch);
        } catch (Throwable ignored) {}
    }

    public static final class Builder {
        private final Sound sound;
        private float volume = 1f;
        private float pitch = 1f;
        private Target target = Target.WORLD;
        private double atDx, atDy, atDz;

        private Builder(Sound sound) { this.sound = sound; }

        public Builder volume(float volume) { this.volume = volume; return this; }
        public Builder pitch(float pitch) { this.pitch = pitch; return this; }
        public Builder at(Target target) { this.target = target != null ? target : Target.WORLD; return this; }
        public Builder world() { return at(Target.WORLD); }
        public Builder toPlayer() { return at(Target.PLAYER); }
        public Builder atOffset(double dx, double dy, double dz) { this.atDx = dx; this.atDy = dy; this.atDz = dz; return this; }

        public SoundStep build() { return new SoundStep(this); }
    }
}
