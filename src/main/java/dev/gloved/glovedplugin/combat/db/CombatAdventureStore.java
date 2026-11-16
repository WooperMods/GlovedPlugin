package dev.gloved.glovedplugin.combat.db;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.GameMode;

import java.io.File;
import java.sql.*;
import java.util.UUID;

/**
 * Persists players switched to adventure mode by the combat weapon-hold feature so they can be
 * restored correctly after restarts/crashes/reloads (instead of getting stuck in adventure).
 */
public final class CombatAdventureStore {
    private final GlovedPlugin plugin;
    private Connection connection;

    public CombatAdventureStore(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void init() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), "combat.db");
        if (!dbFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            dbFile.getParentFile().mkdirs();
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS adventure_switched (" +
                            "uuid TEXT PRIMARY KEY," +
                            "prior_mode TEXT NOT NULL," +
                            "updated_at INTEGER NOT NULL" +
                            ");"
            );
        }
        plugin.getLogger().info("[Combat] SQLite database initialized (combat.db)");
    }

    public synchronized void markSwitched(UUID uuid, GameMode priorMode) {
        if (uuid == null || priorMode == null || connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO adventure_switched(uuid, prior_mode, updated_at) VALUES(?,?,?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET prior_mode = excluded.prior_mode, updated_at = excluded.updated_at")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, priorMode.name());
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Combat] Failed to persist adventure switch for " + uuid + ": " + e.getMessage());
        }
    }

    public synchronized GameMode getPriorMode(UUID uuid) {
        if (uuid == null || connection == null) return null;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT prior_mode FROM adventure_switched WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String name = rs.getString("prior_mode");
                if (name == null) return null;
                try {
                    return GameMode.valueOf(name);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Combat] Failed to read adventure switch for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    public synchronized boolean isTracked(UUID uuid) {
        return getPriorMode(uuid) != null;
    }

    public synchronized void clear(UUID uuid) {
        if (uuid == null || connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM adventure_switched WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Combat] Failed to clear adventure switch for " + uuid + ": " + e.getMessage());
        }
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {
        }
    }
}
