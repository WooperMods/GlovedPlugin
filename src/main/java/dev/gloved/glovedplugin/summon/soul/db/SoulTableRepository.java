package dev.gloved.glovedplugin.summon.soul.db;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC repository for the {@code soul_tables} table (placed Soul Table stations + overlay entities).
 *
 * <p>Synchronous like {@link SoulRepository}; callers are expected to be on the main thread or
 * inside {@link SoulDatabase#transaction(SoulDatabase.SqlWork)}.</p>
 */
public class SoulTableRepository {

    /** One placed Soul Table. {@code displayId} is the ItemDisplay overlay entity UUID (nullable). */
    public record SoulTableRow(UUID tableId, String world, int x, int y, int z, UUID ownerId,
                               UUID displayId, long createdAt) {
        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            if (w == null) return null;
            return new Location(w, x, y, z);
        }
    }

    private final GlovedPlugin plugin;
    private final SoulDatabase database;

    public SoulTableRepository(GlovedPlugin plugin, SoulDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    private Connection conn() { return database.connection(); }

    public synchronized void insert(SoulTableRow row) throws SQLException {
        String sql = "INSERT INTO soul_tables (table_id, world, x, y, z, owner_uuid, display_uuid, created_at) " +
                "VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, row.tableId().toString());
            ps.setString(2, row.world());
            ps.setInt(3, row.x());
            ps.setInt(4, row.y());
            ps.setInt(5, row.z());
            ps.setString(6, row.ownerId().toString());
            ps.setString(7, row.displayId() != null ? row.displayId().toString() : null);
            ps.setLong(8, row.createdAt());
            ps.executeUpdate();
        }
    }

    public synchronized SoulTableRow get(UUID tableId) throws SQLException {
        if (tableId == null) return null;
        String sql = "SELECT * FROM soul_tables WHERE table_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, tableId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public synchronized SoulTableRow getAt(String world, int x, int y, int z) throws SQLException {
        String sql = "SELECT * FROM soul_tables WHERE world = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public synchronized List<SoulTableRow> listChunk(String world, int chunkX, int chunkZ) throws SQLException {
        String sql = "SELECT * FROM soul_tables WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?";
        List<SoulTableRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, chunkX * 16);
            ps.setInt(3, chunkX * 16 + 15);
            ps.setInt(4, chunkZ * 16);
            ps.setInt(5, chunkZ * 16 + 15);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        }
        return out;
    }

    public synchronized List<SoulTableRow> listAll() throws SQLException {
        String sql = "SELECT * FROM soul_tables ORDER BY created_at ASC";
        List<SoulTableRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(map(rs));
        }
        return out;
    }

    public synchronized boolean exists(UUID tableId) throws SQLException {
        if (tableId == null) return false;
        String sql = "SELECT 1 FROM soul_tables WHERE table_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, tableId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized void setDisplayId(UUID tableId, UUID displayId) throws SQLException {
        String sql = "UPDATE soul_tables SET display_uuid = ? WHERE table_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, displayId != null ? displayId.toString() : null);
            ps.setString(2, tableId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void delete(UUID tableId) throws SQLException {
        if (tableId == null) return;
        String sql = "DELETE FROM soul_tables WHERE table_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, tableId.toString());
            ps.executeUpdate();
        }
    }

    private SoulTableRow map(ResultSet rs) throws SQLException {
        UUID tableId = UUID.fromString(rs.getString("table_id"));
        UUID owner = UUID.fromString(rs.getString("owner_uuid"));
        UUID display = null;
        String displayRaw = rs.getString("display_uuid");
        if (displayRaw != null && !displayRaw.isBlank()) {
            try { display = UUID.fromString(displayRaw); } catch (Exception ignored) {}
        }
        return new SoulTableRow(tableId, rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                owner, display, rs.getLong("created_at"));
    }

    /** Small helper so callers do not need to import GlovedPlugin just for logging. */
    public GlovedPlugin plugin() { return plugin; }
}
