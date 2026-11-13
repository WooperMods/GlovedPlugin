package dev.gloved.glovedplugin.core.bootstrap;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.config.LootrConfig;
import dev.gloved.glovedplugin.lootr.listener.ChestAutoRegisterListener;
import dev.gloved.glovedplugin.lootr.listener.ChestOpenListener;
import dev.gloved.glovedplugin.lootr.listener.LootrChestPlaceListener;
import dev.gloved.glovedplugin.lootr.listener.ProtectionListener;
import dev.gloved.glovedplugin.lootr.LootrModule;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Bootstrap helper for the Lootr module. Preserves enable-time behavior and ordering.
 */
public final class LootrBootstrap {

    private LootrBootstrap() {
    }

    public static LootrModule enable(GlovedPlugin plugin) {
        // Initialize Lootr module
        File lootrFile = new File(plugin.getDataFolder(), "lootr.yml");
        if (!lootrFile.exists()) {
            plugin.saveResource("lootr.yml", false);
        } else {
            // Backfill new lootr keys into existing server files without
            // overwriting anything the admin has set.
            try {
                LootrConfig.backfillMissingEliteKeys(lootrFile);
            } catch (Exception e) {
                GlovedPlugin.logInfo("Could not backfill lootr.yml defaults: " + e);
            }
        }
        FileConfiguration lootrCfg = YamlConfiguration.loadConfiguration(lootrFile);
        LootrConfig lootrConfig = LootrConfig.load(lootrCfg);
        if (!lootrConfig.enabled) {
            return null;
        }
        LootrModule lootrManager = new LootrModule(plugin, lootrConfig);
        lootrManager.load();
        plugin.getServer().getPluginManager().registerEvents(new ChestOpenListener(plugin, lootrManager), plugin);
        plugin.getServer().getPluginManager().registerEvents(new ProtectionListener(lootrManager), plugin);
        plugin.getServer().getPluginManager().registerEvents(new ChestAutoRegisterListener(lootrManager), plugin);
        plugin.getServer().getPluginManager().registerEvents(new LootrChestPlaceListener(plugin, lootrManager), plugin);
        GlovedPlugin.logInfo("Lootr module enabled");
        return lootrManager;
    }
}
