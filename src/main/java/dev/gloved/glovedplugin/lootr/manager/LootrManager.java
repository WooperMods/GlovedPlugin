package dev.gloved.glovedplugin.lootr.manager;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.config.LootrConfig;
import dev.gloved.glovedplugin.lootr.db.LootrDatabase;
import org.bukkit.scheduler.BukkitRunnable;

public class LootrManager {
    private final GlovedPlugin plugin;
    private final LootrConfig config;
    private final LootrDatabase database;
    private ContainerRegistry registry;
    private BukkitRunnable autoSaveTask;

    public LootrManager(GlovedPlugin plugin, LootrConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.database = new LootrDatabase(plugin);
    }

    public void load() {
        try {
            database.init();
            registry = new ContainerRegistry(database.getConnection());
            startAutoSave();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to init Lootr DB: " + e);
        }
    }

    private void startAutoSave() {
        int interval = Math.max(1, config.autoSaveInterval);
        autoSaveTask = new BukkitRunnable() {
            @Override
            public void run() {
                try (java.sql.Connection conn = database.getConnection();
                     java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(FULL)");
                    plugin.getLogger().info("Lootr auto-save tick");
                } catch (Exception e) {
                    plugin.getLogger().warning("Lootr auto-save failed: " + e.getMessage());
                }
            }
        };
        autoSaveTask.runTaskTimerAsynchronously(plugin, 20L * 60 * interval, 20L * 60 * interval);
    }

    public void save() {
        // placeholder for future persistence
    }

    public void shutdown() {
        if (autoSaveTask != null) autoSaveTask.cancel();
        database.close();
    }

    public LootrConfig getConfig() {
        return config;
    }

    public LootrDatabase getDatabase() {
        return database;
    }

    public ContainerRegistry getRegistry() {
        return registry;
    }
}
