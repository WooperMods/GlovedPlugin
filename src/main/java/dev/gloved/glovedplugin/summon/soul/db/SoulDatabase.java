package dev.gloved.glovedplugin.summon.soul.db;

import dev.gloved.glovedplugin.GlovedPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite storage for dynamic soul collection (vault, loadouts, player state, codex). */
public class SoulDatabase {
    private final GlovedPlugin plugin;
    private Connection connection;

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
            // Migration: add max_health column to existing souls tables (safe no-op if already present).
            try (java.sql.ResultSet cols = stmt.executeQuery("PRAGMA table_info(souls)")) {
                boolean hasMaxHealth = false;
                while (cols.next()) {
                    if ("max_health".equalsIgnoreCase(cols.getString("name"))) { hasMaxHealth = true; break; }
                }
                if (!hasMaxHealth) {
                    stmt.execute("ALTER TABLE souls ADD COLUMN max_health REAL NOT NULL DEFAULT 0");
                }
            }

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
        }
        plugin.getLogger().info("[Souls] SQLite database initialized (souls.db)");
    }

    public synchronized Connection getConnection() {
        return connection;
    }

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
