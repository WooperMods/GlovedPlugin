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
    private BukkitRunnable particleTask;

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
            startParticleTask();
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
                } catch (Exception e) {
                    plugin.getLogger().warning("Lootr auto-save failed: " + e.getMessage());
                }
            }
        };
        autoSaveTask.runTaskTimer(plugin, 20L * 60 * interval, 20L * 60 * interval);
    }

    private void startParticleTask() {
        if (!config.particlesEnabled) return;
        particleTask = new BukkitRunnable() {
            @Override
            public void run() {
                try (java.sql.Connection conn = database.getConnection();
                     java.sql.PreparedStatement ps = conn.prepareStatement(
                         "SELECT c.id, c.world, c.x, c.y, c.z FROM containers c LEFT JOIN player_loot p ON c.id = p.container_id WHERE p.container_id IS NULL")) {
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        org.bukkit.Particle particle = parseParticle(config.particleType);
                        if (particle == null) return;
                        while (rs.next()) {
                            String worldUuid = rs.getString("world");
                            int x = rs.getInt("x");
                            int y = rs.getInt("y");
                            int z = rs.getInt("z");
                            try {
                                java.util.UUID wuid = java.util.UUID.fromString(worldUuid);
                                org.bukkit.World world = plugin.getServer().getWorld(wuid);
                                if (world == null) continue;
                                org.bukkit.Location loc = new org.bukkit.Location(world, x + 0.5, y + 1.1, z + 0.5);
                                world.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        };
        particleTask.runTaskTimer(plugin, 20L, 20L);
    }

    private org.bukkit.Particle parseParticle(String name) {
        if (name == null) return null;
        try {
            return org.bukkit.Particle.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return org.bukkit.Particle.HAPPY_VILLAGER;
        }
    }

    public void save() {
        // placeholder for future persistence
    }

    public void shutdown() {
        if (autoSaveTask != null) autoSaveTask.cancel();
        if (particleTask != null) particleTask.cancel();
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
