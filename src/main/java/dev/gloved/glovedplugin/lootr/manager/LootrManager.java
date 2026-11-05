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
    private BukkitRunnable orphanCleanupTask;

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
                final int range = 64;
                final int rangeSq = range * range;
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
                    org.bukkit.World world = player.getWorld();
                    org.bukkit.Location ploc = player.getLocation();
                    int chunkRadius = range / 16 + 1;
                    int playerChunkX = ploc.getBlockX() >> 4;
                    int playerChunkZ = ploc.getBlockZ() >> 4;
                    for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
                        for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
                            org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
                            if (!chunk.isLoaded()) continue;
                            java.util.Set<dev.gloved.glovedplugin.lootr.model.ContainerId> seenInChunk = new java.util.HashSet<>();
                            for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                                if (!(state instanceof org.bukkit.block.Container container)) continue;
                                if (!(container instanceof org.bukkit.loot.Lootable lootable)) continue;
                                org.bukkit.loot.LootTable table = lootable.getLootTable();
                                if (table == null) continue;
                                org.bukkit.block.Block block = container.getBlock();
                                dev.gloved.glovedplugin.lootr.model.ContainerId id = new dev.gloved.glovedplugin.lootr.model.ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                                seenInChunk.add(id);
                                if (!registry.isRegistered(id)) {
                                    registry.register(id, table.getKey().toString());
                                }
                                if (opened.contains(id.toStringKey())) continue;
                                org.bukkit.Location loc = new org.bukkit.Location(world, block.getX() + 0.5, block.getY() + 1.0, block.getZ() + 0.5);
                                if (ploc.distanceSquared(loc) > rangeSq) continue;
                                player.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                            }
                            // Orphan cleanup: remove registered containers in this chunk that no longer have a block
                            java.util.List<dev.gloved.glovedplugin.lootr.model.ContainerId> registered = registry.findAllInChunk(world.getUID(), cx, cz);
                            for (dev.gloved.glovedplugin.lootr.model.ContainerId regId : registered) {
                                if (!seenInChunk.contains(regId)) {
                                    registry.unregister(regId);
                                }
                            }
                        }
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

    private void forEachContainerInPlayerRange(org.bukkit.entity.Player player, int range, java.util.function.BiConsumer<org.bukkit.block.BlockState, dev.gloved.glovedplugin.lootr.model.ContainerId> consumer) {
        org.bukkit.World world = player.getWorld();
        org.bukkit.Location ploc = player.getLocation();
        int chunkRadius = range / 16 + 1;
        int playerChunkX = ploc.getBlockX() >> 4;
        int playerChunkZ = ploc.getBlockZ() >> 4;
        for (int cx = playerChunkX - chunkRadius; cx <= playerChunkX + chunkRadius; cx++) {
            for (int cz = playerChunkZ - chunkRadius; cz <= playerChunkZ + chunkRadius; cz++) {
                org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
                if (!chunk.isLoaded()) continue;
                for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                    if (!(state instanceof org.bukkit.block.Container container)) continue;
                    if (!(container instanceof org.bukkit.loot.Lootable lootable)) continue;
                    org.bukkit.loot.LootTable table = lootable.getLootTable();
                    if (table == null) continue;
                    org.bukkit.block.Block block = container.getBlock();
                    dev.gloved.glovedplugin.lootr.model.ContainerId id = new dev.gloved.glovedplugin.lootr.model.ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                    consumer.accept(state, id);
                }
            }
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
