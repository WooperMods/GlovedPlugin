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
                try {
                    java.sql.Connection conn = database.getConnection();
                    try (java.sql.Statement stmt = conn.createStatement()) {
                        stmt.execute("PRAGMA wal_checkpoint(FULL)");
                    }
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
                org.bukkit.Particle particle = parseParticle(config.particleType);
                if (particle == null) return;
                java.util.List<ContainerPos> containers = new java.util.ArrayList<>();
                // Load all containers once per tick
                try {
                    java.sql.Connection conn = database.getConnection();
                    try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT id, world, x, y, z FROM containers")) {
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                containers.add(new ContainerPos(rs.getString("id"), rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                            }
                        }
                    }
                } catch (Exception ignored) {}
                if (containers.isEmpty()) return;
                for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
                    java.util.Set<String> opened = new java.util.HashSet<>();
                    try {
                        java.sql.Connection conn = database.getConnection();
                        try (java.sql.PreparedStatement ps = conn.prepareStatement("SELECT container_id FROM player_loot WHERE player_uuid = ?")) {
                            ps.setString(1, player.getUniqueId().toString());
                            try (java.sql.ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) opened.add(rs.getString("container_id"));
                            }
                        }
                    } catch (Exception ignored) {}
                    for (ContainerPos cp : containers) {
                        if (opened.contains(cp.id)) continue;
                        try {
                            java.util.UUID wuid = java.util.UUID.fromString(cp.world);
                            org.bukkit.World world = plugin.getServer().getWorld(wuid);
                            if (world == null) continue;
                            org.bukkit.Location loc = new org.bukkit.Location(world, cp.x + 0.5, cp.y + 1.0, cp.z + 0.5);
                            if (player.getLocation().distanceSquared(loc) > 256 * 256) continue; // 256 blocks squared
                            // Per-player spawn
                            player.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                        } catch (Exception ignored) {}
                    }
                }
            }
        };
        particleTask.runTaskTimer(plugin, 20L, 20L);
    }

    private static class ContainerPos {
        final String id;
        final String world;
        final int x, y, z;
        ContainerPos(String id, String world, int x, int y, int z) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
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
