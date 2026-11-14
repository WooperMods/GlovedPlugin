package dev.gloved.glovedplugin.summon.vfx;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, reusable composite VFX effect (ordered particle + sound steps), constructed via {@link Builder}.
 */
public final class VfxEffect {
    private static final VfxEffect EMPTY = new VfxEffect(Collections.emptyList(), Collections.emptyList());

    private final List<ParticleStep> particles;
    private final List<SoundStep> sounds;

    private VfxEffect(List<ParticleStep> particles, List<SoundStep> sounds) {
        this.particles = particles;
        this.sounds = sounds;
    }

    public static Builder builder() { return new Builder(); }
    public static VfxEffect empty() { return EMPTY; }

    public void play(@Nullable World world, @Nullable Location base) {
        play(world, base, null);
    }

    public void play(@Nullable Location base) {
        if (base == null) return;
        play(base.getWorld(), base, null);
    }

    public void play(@Nullable World world, @Nullable Location base, @Nullable Player player) {
        if (world == null || base == null) return;
        for (ParticleStep p : particles) p.play(world, base);
        for (SoundStep s : sounds) s.play(world, base, player);
    }

    public void playAtEntity(@Nullable Entity entity, double yOffset) {
        if (entity == null || entity.getWorld() == null) return;
        play(entity.getWorld(), entity.getLocation().add(0, yOffset, 0), null);
    }

    public static final class Builder {
        private final List<ParticleStep> particles = new ArrayList<>();
        private final List<SoundStep> sounds = new ArrayList<>();

        public Builder step(ParticleStep step) { if (step != null) particles.add(step); return this; }
        public Builder step(SoundStep step) { if (step != null) sounds.add(step); return this; }

        public Builder particle(Particle particle, int count, double ox, double oy, double oz, double extra) {
            return step(ParticleStep.builder(particle).count(count).offset(ox, oy, oz).extra(extra).build());
        }

        public Builder particle(Particle particle, int count, double ox, double oy, double oz, double extra, double atDx, double atDy, double atDz) {
            return step(ParticleStep.builder(particle).count(count).offset(ox, oy, oz).extra(extra).atOffset(atDx, atDy, atDz).build());
        }

        public Builder particle(Particle particle, int count, double ox, double oy, double oz, double extra, @Nullable Object data) {
            return step(ParticleStep.builder(particle).count(count).offset(ox, oy, oz).extra(extra).data(data).build());
        }

        public Builder sound(Sound sound, float volume, float pitch) {
            return step(SoundStep.builder(sound).volume(volume).pitch(pitch).world().build());
        }

        public Builder sound(Sound sound, float volume, float pitch, double atDx, double atDy, double atDz) {
            return step(SoundStep.builder(sound).volume(volume).pitch(pitch).world().atOffset(atDx, atDy, atDz).build());
        }

        public Builder playerSound(Sound sound, float volume, float pitch) {
            return step(SoundStep.builder(sound).volume(volume).pitch(pitch).toPlayer().build());
        }

        public VfxEffect build() {
            return new VfxEffect(List.copyOf(particles), List.copyOf(sounds));
        }
    }
}
