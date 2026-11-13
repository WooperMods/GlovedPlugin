package dev.gloved.glovedplugin.lootr;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.config.LootrConfig;
import dev.gloved.glovedplugin.lootr.db.LootrDatabase;
import dev.gloved.glovedplugin.lootr.task.LootrAutoSaveTask;
import dev.gloved.glovedplugin.lootr.task.LootrParticleScanTask;
import org.bukkit.scheduler.BukkitRunnable;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import dev.gloved.glovedplugin.lootr.repository.ContainerRegistry;

public class LootrModule {
    private final GlovedPlugin plugin;
    private final LootrConfig config;
    private final LootrDatabase database;
    private ContainerRegistry registry;
    private BukkitRunnable autoSaveTask;
    private BukkitRunnable particleTask;
    private BukkitRunnable orphanCleanupTask;

    public LootrModule(GlovedPlugin plugin, LootrConfig config) {
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
        autoSaveTask = new LootrAutoSaveTask(plugin, database);
        autoSaveTask.runTaskTimer(plugin, 20L * 60 * interval, 20L * 60 * interval);
    }

    private void startParticleTask() {
        if (!config.particlesEnabled) return;
        particleTask = new LootrParticleScanTask(plugin, config, database, registry);
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



    private void forEachContainerInPlayerRange(org.bukkit.entity.Player player, int range, java.util.function.BiConsumer<org.bukkit.block.BlockState, ContainerId> consumer) {
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
                    ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
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
