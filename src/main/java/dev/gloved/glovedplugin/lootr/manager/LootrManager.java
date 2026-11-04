package dev.gloved.glovedplugin.lootr.manager;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.config.LootrConfig;
import dev.gloved.glovedplugin.lootr.db.LootrDatabase;

public class LootrManager {
    private final GlovedPlugin plugin;
    private final LootrConfig config;
    private final LootrDatabase database;
    private ContainerRegistry registry;

    public LootrManager(GlovedPlugin plugin, LootrConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.database = new LootrDatabase(plugin);
    }

    public void load() {
        try {
            database.init();
            registry = new ContainerRegistry(database.getConnection());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to init Lootr DB: " + e);
        }
    }

    public void save() {
        // placeholder for future persistence
    }

    public void shutdown() {
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
