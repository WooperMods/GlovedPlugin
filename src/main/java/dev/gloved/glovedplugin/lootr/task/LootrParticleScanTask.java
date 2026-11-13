package dev.gloved.glovedplugin.lootr.task;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.config.LootrConfig;
import dev.gloved.glovedplugin.lootr.db.LootrDatabase;
import dev.gloved.glovedplugin.lootr.repository.ContainerRegistry;
import org.bukkit.scheduler.BukkitRunnable;
import dev.gloved.glovedplugin.lootr.model.ContainerId;

/**
 * Coupled particle/discovery/orphan-scan task for Lootr.
 * Preserves existing scan semantics, queries, and ordering (mechanical extraction).
 */
public final class LootrParticleScanTask extends BukkitRunnable {
    private final GlovedPlugin plugin;
    private final LootrConfig config;
    private final LootrDatabase database;
    private final ContainerRegistry registry;

    public LootrParticleScanTask(GlovedPlugin plugin, LootrConfig config, LootrDatabase database, ContainerRegistry registry) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.registry = registry;
    }

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
                    java.util.Set<ContainerId> seenInChunk = new java.util.HashSet<>();
                    for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                        if (!(state instanceof org.bukkit.block.Container container)) continue;
                        org.bukkit.block.Block block = container.getBlock();
                        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                        // Presence check for orphan cleanup: ANY container block counts as
                        // still existing, even if it has no loot table. Lootr clears the
                        // loot table when it takes a container over, and /lootr generate
                        // creates chests without one, so requiring a loot table here
                        // would wrongly unregister live containers.
                        seenInChunk.add(id);
                        boolean isRegistered = registry.isRegistered(id);
                        if (!isRegistered) {
                            if (container instanceof org.bukkit.loot.Lootable lootable) {
                                org.bukkit.loot.LootTable table = lootable.getLootTable();
                                if (table != null) {
                                    registry.register(id, table.getKey().toString());
                                    isRegistered = true;
                                }
                            }
                        }
                        if (!isRegistered) continue;
                        if (opened.contains(id.toStringKey())) continue;
                        org.bukkit.Location loc = new org.bukkit.Location(world, block.getX() + 0.5, block.getY() + 1.0, block.getZ() + 0.5);
                        if (ploc.distanceSquared(loc) > rangeSq) continue;
                        player.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
                    }
                    // Orphan cleanup: remove registered containers in this chunk whose block is gone
                    java.util.List<ContainerId> registered = registry.findAllInChunk(world.getUID(), cx, cz);
                    for (ContainerId regId : registered) {
                        if (!seenInChunk.contains(regId)) {
                            GlovedPlugin.logDebug("Lootr orphan cleanup unregistering " + regId.toStringKey());
                            registry.unregister(regId);
                        }
                    }
                }
            }
        }
    }

    private static org.bukkit.Particle parseParticle(String name) {
        if (name == null) return null;
        try {
            return org.bukkit.Particle.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return org.bukkit.Particle.HAPPY_VILLAGER;
        }
    }
}
