package dev.gloved.glovedplugin.damagehologram.provider;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.events.HologramsLoadedEvent;
import de.oliver.fancyholograms.api.hologram.Hologram;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.UUID;

/**
 * FancyHolograms provider mirroring AuraSkills FancyHologramsHook behavior,
 * using Gloved-owned naming prefix and cleanup.
 */
public class FancyHologramsProvider implements HologramProvider, Listener {

    public static final String PREFIX = "gloved_auraskills_";

    private final GlovedPlugin plugin;
    private boolean registeredCleanupListener;

    public FancyHologramsProvider(GlovedPlugin plugin) {
        this.plugin = plugin;
        registerCleanupListener();
    }

    private void registerCleanupListener() {
        if (registeredCleanupListener) return;
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registeredCleanupListener = true;
        } catch (Exception e) {
            GlovedPlugin.logDebug("[DamageHologram] Failed to register FancyHolograms cleanup listener: " + e);
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            if (Bukkit.getPluginManager().getPlugin("FancyHolograms") == null) return false;
            if (!Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) return false;
            Class.forName("de.oliver.fancyholograms.api.FancyHologramsPlugin", false, getClass().getClassLoader());
            FancyHologramsPlugin.get();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void createHologram(Location location, String text, Player player) {
        try {
            TextHologramData data = new TextHologramData(PREFIX + UUID.randomUUID(), location);
            data.setText(List.of(text));
            try {
                data.setBillboard(Display.Billboard.CENTER);
            } catch (Throwable ignored) {
                // billboard API presence varies; non-fatal
            }
            try {
                data.setPersistent(false);
            } catch (Throwable ignored) {
                // best-effort; transient hologram
            }
            HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
            Hologram hologram = manager.create(data);
            manager.addHologram(hologram);
            try {
                if (player != null && player.isOnline()) {
                    hologram.forceShowHologram(player);
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[DamageHologram] forceShowHologram failed: " + t);
            }
            scheduleDelete(manager, hologram, location);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[DamageHologram] FancyHolograms create failed: " + t);
        }
    }

    private void scheduleDelete(HologramManager manager, Hologram hologram, Location location) {
        // 30 ticks lifetime to mirror AuraSkills (30L * 50L ms)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                manager.removeHologram(hologram);
            } catch (Throwable t) {
                try {
                    if (hologram != null && hologram.getData() != null) {
                        String name = hologram.getData().getName();
                        if (name != null) {
                            try {
                                java.lang.reflect.Method m = manager.getClass().getMethod("removeHologram", String.class);
                                m.invoke(manager, name);
                            } catch (Throwable ignored) {
                                // API variant without String overload; best-effort only
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }, 30L);
    }

    @EventHandler
    public void onHologramsLoaded(HologramsLoadedEvent event) {
        cleanupStale();
    }

    public void cleanupStale() {
        try {
            HologramManager manager = FancyHologramsPlugin.get().getHologramManager();
            // Remove stale holograms with our prefix
            // Iterate defensively as API shapes vary; try common access patterns.
            try {
                // Preferred: iterate via manager if Iterable/collection-like in implementation
                for (Hologram h : manager.getHolograms()) {
                    if (h != null && h.getData() != null && h.getData().getName() != null
                            && h.getData().getName().startsWith(PREFIX)) {
                        manager.removeHologram(h);
                    }
                }
            } catch (Throwable ignored) {
                // ignore if getHolograms signature differs; not critical
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[DamageHologram] FancyHolograms stale cleanup failed: " + t);
        }
    }

    @Override
    public String name() {
        return "FancyHolograms";
    }

    @Override
    public void shutdown() {
        if (registeredCleanupListener) {
            try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
            registeredCleanupListener = false;
        }
    }
}
