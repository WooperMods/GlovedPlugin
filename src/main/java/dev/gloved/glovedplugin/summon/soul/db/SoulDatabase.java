package dev.gloved.glovedplugin.summon.soul.db;

import dev.gloved.glovedplugin.GlovedPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite storage for dynamic soul collection (vault, loadouts, player state, codex, soul tables). */
public class SoulDatabase {
    private final GlovedPlugin plugin;
    private volatile Connection connection;

    public SoulDatabase(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void init() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), "souls.db");
        if (!dbFile.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            dbFile.getParentFile().mkdirs();
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS souls (" +
                "soul_id TEXT PRIMARY KEY," +
                "owner_uuid TEXT NOT NULL," +
                "entity_type TEXT NOT NULL," +
                "family_key TEXT NOT NULL," +
                "category TEXT NOT NULL," +
                "rarity TEXT NOT NULL," +
                "quality REAL NOT NULL DEFAULT 0," +
                "affixes_json TEXT NOT NULL DEFAULT '[]'," +
                "trait TEXT," +
                "favorite INTEGER NOT NULL DEFAULT 0," +
                "state TEXT NOT NULL DEFAULT 'VAULT'," +
                "origin_context TEXT," +
                "created_at INTEGER NOT NULL" +
                ");"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_souls_owner_state ON souls(owner_uuid, state);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_souls_owner_family ON souls(owner_uuid, family_key);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_souls_owner_rarity ON souls(owner_uuid, rarity);");

            // Additive migrations (safe no-ops when the columns already exist).
            addColumnIfMissing(stmt, "max_health", "ALTER TABLE souls ADD COLUMN max_health REAL NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, "base_stats_json", "ALTER TABLE souls ADD COLUMN base_stats_json TEXT");
            addColumnIfMissing(stmt, "enhance_level", "ALTER TABLE souls ADD COLUMN enhance_level INTEGER NOT NULL DEFAULT 0");

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS soul_loadouts (" +
                "owner_uuid TEXT NOT NULL," +
                "slot INTEGER NOT NULL," +
                "soul_id TEXT," +
                "selected INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(owner_uuid, slot)" +
                ");"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_soul_loadouts_soul ON soul_loadouts(soul_id);");

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS soul_player_state (" +
                "owner_uuid TEXT PRIMARY KEY," +
                "essence INTEGER NOT NULL DEFAULT 0," +
                "vault_capacity INTEGER NOT NULL DEFAULT 30," +
                "pity_json TEXT," +
                "selected_slot INTEGER NOT NULL DEFAULT 0," +
                "doctrine TEXT," +
                "updated_at INTEGER NOT NULL" +
                ");"
            );

            stmt.execute(
                "CREATE TABLE IF NOT EXISTS soul_codex (" +
                "owner_uuid TEXT NOT NULL," +
                "family_key TEXT NOT NULL," +
                "highest_rarity TEXT," +
                "captured_count INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(owner_uuid, family_key)" +
                ");"
            );

            // Placed Soul Table stations (real smithing tables + ItemDisplay overlay).
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS soul_tables (" +
                "table_id TEXT PRIMARY KEY," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "owner_uuid TEXT NOT NULL," +
                "display_uuid TEXT," +
                "created_at INTEGER NOT NULL" +
                ");"
            );
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_soul_tables_chunk ON soul_tables(world, x, z);");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_soul_tables_pos ON soul_tables(world, x, y, z);");
        }
        plugin.getLogger().info("[Souls] SQLite database initialized (souls.db)");
    }

    private static void addColumnIfMissing(Statement stmt, String column, String alterSql) throws SQLException {
        try (ResultSet cols = stmt.executeQuery("PRAGMA table_info(souls)")) {
            while (cols.next()) {
                if (column.equalsIgnoreCase(cols.getString("name"))) return;
            }
        }
        stmt.execute(alterSql);
    }

    /**
     * Connection accessor without locking. Repository code must use this (not {@link #getConnection()})
     * so a {@link #transaction(SqlWork)} holding the database monitor can never deadlock against a
     * repository method that holds its own monitor and waits for the database monitor.
     */
    public Connection connection() {
        return connection;
    }

    /** Kept for backwards compatibility; prefer {@link #connection()} for hot paths. */
    public synchronized Connection getConnection() {
        return connection;
    }

    /** Unit of work executed inside a transaction. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} atomically on the shared connection: commit on success, rollback on any
     * failure. Synchronized so concurrent callers cannot interleave statements mid-transaction.
     */
    public synchronized <T> T transaction(SqlWork<T> work) throws SQLException {
        Connection conn = connection;
        if (conn == null) throw new SQLException("soul database not initialized");
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            T result = work.run(conn);
            conn.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // best-effort rollback; the original failure is more useful
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
                // ignore; connection stays usable for the next operation
            }
        }
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
