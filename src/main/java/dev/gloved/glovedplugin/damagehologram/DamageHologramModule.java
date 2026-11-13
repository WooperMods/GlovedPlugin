package dev.gloved.glovedplugin.damagehologram;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.damagehologram.provider.FancyHologramsProvider;
import dev.gloved.glovedplugin.damagehologram.provider.HologramProvider;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.text.NumberFormat;

/**
 * AuraSkills-compatible damage hologram module.
 * Reads settings live from AuraSkills config.yml (Mode A), is always enabled,
 * supports FancyHolograms provider, and excludes EliteMobs victims.
 */
public class DamageHologramModule {

    private final GlovedPlugin plugin;
    private DamageHologramConfig config;
    private HologramProvider provider;
    private DamageHologramListener listener;
    private NumberFormat numberFormat;
    private boolean registered;

    public DamageHologramModule(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ensureResource();
        reloadInternal();
        registerListener();
        GlovedPlugin.logInfo("DamageHologram module loaded (provider=" + (provider != null ? provider.name() : "none")
                + ", excludeEliteMobs=" + (config != null && config.excludeEliteMobs) + ")");
    }

    public void reload() {
        reloadInternal();
        GlovedPlugin.logInfo("DamageHologram module reloaded (provider=" + (provider != null ? provider.name() : "none")
                + ", excludeEliteMobs=" + (config != null && config.excludeEliteMobs) + ")");
    }

    public void shutdown() {
        unregisterListener();
        if (provider != null) {
            try { provider.shutdown(); } catch (Throwable ignored) {}
        }
        provider = null;
    }

    private void ensureResource() {
        File file = new File(plugin.getDataFolder(), "damage-holograms.yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("damage-holograms.yml", false);
            } catch (Exception ignored) {
                // create minimal default if resource missing in jar context
            }
        }
    }

    private void reloadInternal() {
        File moduleFile = new File(plugin.getDataFolder(), "damage-holograms.yml");
        File auraFile = resolveAuraSkillsConfigFile();
        this.config = DamageHologramConfig.load(moduleFile, auraFile);
        this.numberFormat = DamageHologramFormatter.createNumberFormat(config.decimalMaxAmount);
        bindProvider();
        if (provider != null && provider.isAvailable() && provider instanceof FancyHologramsProvider fh) {
            // best-effort cleanup of stale gloved holograms on reload
            try { fh.cleanupStale(); } catch (Throwable ignored) {}
        }
    }

    private File resolveAuraSkillsConfigFile() {
        try {
            var pm = Bukkit.getPluginManager();
            var aura = pm.getPlugin("AuraSkills");
            if (aura != null) {
                File f = new File(aura.getDataFolder(), "config.yml");
                if (f.isFile()) return f;
            }
        } catch (Throwable ignored) {}
        // common fallback path
        File fallback = new File("plugins/AuraSkills/config.yml");
        return fallback.isFile() ? fallback : null;
    }

    private void bindProvider() {
        String desired = config != null ? config.provider : "auto";
        // Only FancyHolograms supported as requested
        if ("auto".equals(desired) || "fancyholograms".equals(desired) || "fancy".equals(desired)) {
            FancyHologramsProvider fh = (provider instanceof FancyHologramsProvider existing) ? existing : new FancyHologramsProvider(plugin);
            if (fh.isAvailable()) {
                // shutdown old different provider if any
                if (provider != null && provider != fh) {
                    try { provider.shutdown(); } catch (Throwable ignored) {}
                }
                provider = fh;
                return;
            } else {
                if (!(provider instanceof FancyHologramsProvider)) {
                    // keep existing fh instance only if we created it now? ensure not leaked
                }
                if (provider != null && !(provider instanceof FancyHologramsProvider)) {
                    try { provider.shutdown(); } catch (Throwable ignored) {}
                }
                provider = null;
                GlovedPlugin.logWarning("[DamageHologram] FancyHolograms provider requested but not available.");
                return;
            }
        }
        GlovedPlugin.logWarning("[DamageHologram] Unsupported provider '" + desired + "' (only fancyholograms supported).");
        if (provider != null) {
            try { provider.shutdown(); } catch (Throwable ignored) {}
        }
        provider = null;
    }

    private void registerListener() {
        if (registered) return;
        listener = new DamageHologramListener(this);
        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(listener, plugin);
        registered = true;
    }

    private void unregisterListener() {
        if (!registered || listener == null) return;
        try { HandlerList.unregisterAll(listener); } catch (Throwable ignored) {}
        listener = null;
        registered = false;
    }

    public DamageHologramConfig getConfig() {
        return config;
    }

    public HologramProvider getProvider() {
        return provider;
    }

    public NumberFormat getNumberFormat() {
        return numberFormat;
    }
}
