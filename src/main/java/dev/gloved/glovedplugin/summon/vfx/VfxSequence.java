package dev.gloved.glovedplugin.summon.vfx;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Builder-driven animated VFX sequence (scheduled). Use for trails/pulses and other timed effects.
 */
public final class VfxSequence {
    public interface TickEffect {
        void tick(int tick, BukkitRunnable handle);
    }

    private final Plugin plugin;
    private final long delayTicks;
    private final long periodTicks;
    private final int maxTicks; // inclusive bound check count
    private final TickEffect onTick;

    private VfxSequence(Builder b) {
        this.plugin = Objects.requireNonNull(b.plugin, "plugin");
        this.delayTicks = Math.max(0, b.delayTicks);
        this.periodTicks = Math.max(1, b.periodTicks);
        this.maxTicks = b.maxTicks;
        this.onTick = Objects.requireNonNull(b.onTick, "onTick");
    }

    public static Builder builder(Plugin plugin) { return new Builder(plugin); }

    @Nullable
    public BukkitTask start() {
        try {
            BukkitRunnable runnable = new BukkitRunnable() {
                int tick = 0;
                @Override
                public void run() {
                    try {
                        onTick.tick(tick, this);
                    } catch (Throwable ignored) {
                        cancel();
                        return;
                    }
                    tick++;
                    if (maxTicks >= 0 && tick > maxTicks) cancel();
                }
            };
            return runnable.runTaskTimer(plugin, delayTicks, periodTicks);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static final class Builder {
        private final Plugin plugin;
        private long delayTicks = 0L;
        private long periodTicks = 1L;
        private int maxTicks = -1;
        private TickEffect onTick;

        private Builder(Plugin plugin) { this.plugin = plugin; }

        public Builder delayTicks(long delayTicks) { this.delayTicks = delayTicks; return this; }
        public Builder periodTicks(long periodTicks) { this.periodTicks = periodTicks; return this; }
        /** Run ticks 0..maxTicks inclusive (then auto-cancel). */
        public Builder maxTicks(int maxTicks) { this.maxTicks = maxTicks; return this; }
        public Builder onTick(TickEffect onTick) { this.onTick = onTick; return this; }

        public VfxSequence build() { return new VfxSequence(this); }
        @Nullable public BukkitTask start() { return build().start(); }
    }
}
