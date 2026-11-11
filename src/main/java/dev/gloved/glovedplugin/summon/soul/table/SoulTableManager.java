package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulDatabase;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry + overlay lifecycle for placed Soul Tables.
 *
 * <p>The physical station is a real {@code SMITHING_TABLE} block; the custom look comes from a
 * persistent {@link ItemDisplay} rendered from the generated resource pack. Rows live in the
 * {@code soul_tables} SQLite table so overlays rehydrate after restarts and chunk reloads.</p>
 */
public final class SoulTableManager {

    /**
     * Item-display transform. {@code NONE} renders the item model at 1:1 around the entity
     * position, while block item models use a 0.5 scale under {@code FIXED}. The tiny oversize
     * avoids z-fighting with the real block underneath.
     */
    private static final ItemDisplay.ItemDisplayTransform TRANSFORM = ItemDisplay.ItemDisplayTransform.NONE;
    private static final float COVER_SCALE = 1.002f;

    private final GlovedPlugin plugin;
    private final SoulTableRepository repository;
    private final Map<UUID, SoulTableRow> byId = new ConcurrentHashMap<>();
    private final Map<String, UUID> byPosition = new ConcurrentHashMap<>();

    public SoulTableManager(GlovedPlugin plugin, SoulDatabase database) {
        this.plugin = plugin;
        this.repository = new SoulTableRepository(plugin, database);
    }

    public SoulTableRepository repository() {
        return repository;
    }

    public SoulTableConfig config() {
        try {
            var summon = plugin.getSummonConfig();
            return summon != null ? summon.getSoulTableConfig() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean isEnabled() {
        SoulTableConfig cfg = config();
        return cfg == null || cfg.isEnabled();
    }

    // ------------------------------------------------------------------ lookup

    public SoulTableRow get(UUID tableId) {
        if (tableId == null) return null;
        SoulTableRow cached = byId.get(tableId);
        if (cached != null) return cached;
        try {
            SoulTableRow row = repository.get(tableId);
            if (row != null) cache(row);
            return row;
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] get failed: " + e);
            return null;
        }
    }

    /** Registered table at this block, or null. */
    public SoulTableRow getAt(Block block) {
        if (block == null) return null;
        return getAt(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public SoulTableRow getAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        return getAt(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private SoulTableRow getAt(World world, int x, int y, int z) {
        if (world == null) return null;
        String key = positionKey(world.getName(), x, y, z);
        UUID id = byPosition.get(key);
        if (id != null) {
            SoulTableRow row = byId.get(id);
            if (row != null) return row;
        }
        try {
            SoulTableRow row = repository.getAt(world.getName(), x, y, z);
            if (row != null) cache(row);
            return row;
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] getAt failed: " + e);
            return null;
        }
    }

    public Collection<SoulTableRow> cached() {
        return List.copyOf(byId.values());
    }

    /** Resolves the table whose overlay is this entity, or null. */
    public SoulTableRow getByOverlay(Entity entity) {
        if (!(entity instanceof ItemDisplay display)) return null;
        UUID tableId = readOverlayId(display);
        return tableId != null ? get(tableId) : null;
    }

    public List<SoulTableRow> listAll() {
        try {
            return repository.listAll();
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] listAll failed: " + e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------ mutation

    /**
     * Persists a new table at {@code block} and spawns its overlay.
     *
     * @throws SQLException when the insert fails (the caller must cancel placement)
     */
    public SoulTableRow register(Block block, UUID owner) throws SQLException {
        if (block == null || block.getWorld() == null || owner == null) {
            throw new SQLException("invalid soul table position/owner");
        }
        // A stale row can survive a crash between block removal and DB cleanup; replace it.
        try {
            SoulTableRow stale = repository.getAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
            if (stale != null) {
                removeOverlay(stale);
                repository.delete(stale.tableId());
                byId.remove(stale.tableId());
                byPosition.remove(positionKey(stale.world(), stale.x(), stale.y(), stale.z()));
            }
        } catch (SQLException e) {
            throw e;
        }
        SoulTableRow row = new SoulTableRow(UUID.randomUUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), owner, null, System.currentTimeMillis());
        repository.insert(row);
        SoulTableRow withDisplay = ensureOverlay(row);
        cache(withDisplay);
        return withDisplay;
    }

    /**
     * Deletes the row first (so a failed delete leaves a working table), then removes the overlay.
     * The caller is responsible for dropping the Soul Table item.
     */
    public boolean unregister(UUID tableId) {
        SoulTableRow row = get(tableId);
        if (row == null) return false;
        try {
            repository.delete(tableId);
        } catch (SQLException e) {
            plugin.getLogger().warning("[SoulTable] Could not delete table row " + tableId + ": " + e.getMessage());
            return false;
        }
        removeOverlay(row);
        byId.remove(tableId);
        byPosition.remove(positionKey(row.world(), row.x(), row.y(), row.z()));
        return true;
    }

    /** Force a display (re)spawn for a row, e.g. after a failed break. */
    public void refreshOverlay(SoulTableRow row) {
        if (row == null) return;
        ensureOverlay(row);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Loads every row and rehydrates overlays in loaded chunks, then sweeps orphans. */
    public void loadAll() {
        byId.clear();
        byPosition.clear();
        List<SoulTableRow> rows;
        try {
            rows = repository.listAll();
        } catch (SQLException e) {
            plugin.getLogger().warning("[SoulTable] Could not load soul tables: " + e.getMessage());
            return;
        }
        for (SoulTableRow row : rows) cache(row);
        sweep(rows);
        if (!rows.isEmpty()) {
            plugin.getLogger().info("[SoulTable] Loaded " + rows.size() + " placed soul table(s)");
        }
    }

    /** Removes overlay entities without a row and respawns missing ones in loaded chunks. */
    public void cleanupOrphans() {
        List<SoulTableRow> rows;
        try {
            rows = repository.listAll();
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] cleanup query failed: " + e);
            return;
        }
        for (SoulTableRow row : rows) cache(row);
        sweep(rows);
    }

    public void handleChunkLoad(Chunk chunk) {
        if (chunk == null || chunk.getWorld() == null) return;
        List<SoulTableRow> rows;
        try {
            rows = repository.listChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] chunk query failed: " + e);
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (SoulTableRow row : rows) {
            cache(row);
            SoulTableRow withDisplay = ensureOverlay(row);
            cache(withDisplay);
            seen.add(row.tableId());
        }
        // Drop overlay entities whose row was deleted while the chunk was unloaded.
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay display)) continue;
            UUID tableId = readOverlayId(display);
            if (tableId == null) continue;
            if (!seen.contains(tableId)) display.remove();
        }
    }

    public void handleChunkUnload(Chunk chunk) {
        if (chunk == null || chunk.getWorld() == null) return;
        String world = chunk.getWorld().getName();
        int minX = chunk.getX() * 16;
        int maxX = minX + 15;
        int minZ = chunk.getZ() * 16;
        int maxZ = minZ + 15;
        byId.entrySet().removeIf(entry -> {
            SoulTableRow row = entry.getValue();
            return row.world().equals(world) && row.x() >= minX && row.x() <= maxX
                    && row.z() >= minZ && row.z() <= maxZ;
        });
        byPosition.values().removeIf(id -> !byId.containsKey(id));
    }

    // ------------------------------------------------------------------ overlays

    private SoulTableRow ensureOverlay(SoulTableRow row) {
        if (row == null) return null;
        World world = Bukkit.getWorld(row.world());
        if (world == null) return row;
        if (!world.isChunkLoaded(row.x() >> 4, row.z() >> 4)) return row;

        Location center = center(row);
        ItemDisplay existing = findOverlay(center, row.tableId());
        if (existing != null) {
            if (existing.getLocation().distanceSquared(center) > 0.0625) {
                existing.teleport(center);
            }
            return withDisplayId(row, existing.getUniqueId());
        }

        ItemDisplay display;
        try {
            display = world.spawn(center, ItemDisplay.class, entity -> {
                entity.setItemStack(SoulTableItem.createDisplayStack());
                try {
                    entity.setItemDisplayTransform(TRANSFORM);
                } catch (Throwable ignored) {
                    // Older API: keep the default transform.
                }
                entity.setTransformation(new Transformation(
                        new Vector3f(0f, 0f, 0f),
                        new Quaternionf(),
                        new Vector3f(COVER_SCALE, COVER_SCALE, COVER_SCALE),
                        new Quaternionf()));
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setPersistent(true);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setSilent(true);
                entity.setShadowRadius(0f);
                entity.setShadowStrength(0f);
                entity.setDisplayWidth(1.0f);
                entity.setDisplayHeight(1.0f);
                entity.getPersistentDataContainer().set(
                        SoulTableItem.overlayKey(plugin), PersistentDataType.STRING, row.tableId().toString());
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[SoulTable] Could not spawn overlay for " + row.tableId() + ": " + t.getMessage());
            return row;
        }
        return withDisplayId(row, display.getUniqueId());
    }

    private SoulTableRow withDisplayId(SoulTableRow row, UUID displayId) {
        if (displayId == null || displayId.equals(row.displayId())) return row;
        SoulTableRow updated = new SoulTableRow(row.tableId(), row.world(), row.x(), row.y(), row.z(),
                row.ownerId(), displayId, row.createdAt());
        try {
            repository.setDisplayId(row.tableId(), displayId);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] Could not persist display uuid: " + e);
        }
        cache(updated);
        return updated;
    }

    private void removeOverlay(SoulTableRow row) {
        ItemDisplay display = null;
        if (row.displayId() != null) {
            Entity entity = Bukkit.getEntity(row.displayId());
            if (entity instanceof ItemDisplay itemDisplay && row.tableId().equals(readOverlayId(itemDisplay))) {
                display = itemDisplay;
            }
        }
        if (display == null) {
            World world = Bukkit.getWorld(row.world());
            if (world != null) display = findOverlay(center(row), row.tableId());
        }
        if (display != null) display.remove();
    }

    private ItemDisplay findOverlay(Location center, UUID tableId) {
        if (center == null || center.getWorld() == null || tableId == null) return null;
        for (Entity entity : center.getWorld().getNearbyEntities(center, 1.5, 2.0, 1.5)) {
            if (!(entity instanceof ItemDisplay display)) continue;
            if (tableId.equals(readOverlayId(display))) return display;
        }
        return null;
    }

    private UUID readOverlayId(ItemDisplay display) {
        if (display == null) return null;
        try {
            String raw = display.getPersistentDataContainer()
                    .get(SoulTableItem.overlayKey(plugin), PersistentDataType.STRING);
            return raw != null && !raw.isBlank() ? UUID.fromString(raw) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void sweep(List<SoulTableRow> rows) {
        Map<UUID, SoulTableRow> expected = new HashMap<>();
        for (SoulTableRow row : rows) expected.put(row.tableId(), row);

        Set<UUID> seen = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            java.util.Collection<ItemDisplay> displays;
            try {
                displays = world.getEntitiesByClass(ItemDisplay.class);
            } catch (Throwable ignored) {
                continue;
            }
            for (ItemDisplay display : displays) {
                UUID tableId = readOverlayId(display);
                if (tableId == null) continue;
                SoulTableRow row = expected.get(tableId);
                if (row == null || !row.world().equals(world.getName())) {
                    display.remove();
                    continue;
                }
                Location center = center(row);
                if (center == null) {
                    display.remove();
                    continue;
                }
                if (display.getLocation().distanceSquared(center) > 0.25) {
                    display.teleport(center);
                }
                if (!seen.add(tableId)) {
                    // Duplicate overlay for the same table: keep the first, remove extras.
                    display.remove();
                    continue;
                }
                if (row.displayId() == null || !row.displayId().equals(display.getUniqueId())) {
                    withDisplayId(row, display.getUniqueId());
                }
            }
        }

        List<SoulTableRow> missing = new ArrayList<>();
        for (SoulTableRow row : rows) {
            if (seen.contains(row.tableId())) continue;
            World world = Bukkit.getWorld(row.world());
            if (world == null || !world.isChunkLoaded(row.x() >> 4, row.z() >> 4)) continue;
            missing.add(row);
        }
        for (SoulTableRow row : missing) ensureOverlay(row);
    }

    private void cache(SoulTableRow row) {
        if (row == null) return;
        byId.put(row.tableId(), row);
        byPosition.put(positionKey(row.world(), row.x(), row.y(), row.z()), row.tableId());
    }

    public static Location center(SoulTableRow row) {
        World world = Bukkit.getWorld(row.world());
        if (world == null) return null;
        return new Location(world, row.x() + 0.5, row.y() + 0.5, row.z() + 0.5);
    }

    private static String positionKey(String world, int x, int y, int z) {
        return world + "@" + x + "," + y + "," + z;
    }
}
