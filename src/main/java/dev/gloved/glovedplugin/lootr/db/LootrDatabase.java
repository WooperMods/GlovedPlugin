package dev.gloved.glovedplugin.lootr.db;

import dev.gloved.glovedplugin.GlovedPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class LootrDatabase {
    private final GlovedPlugin plugin;
    private Connection connection;

    public LootrDatabase(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), "lootr.db");
        if (!dbFile.getParentFile().exists()) dbFile.getParentFile().mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS containers (" +
                "id TEXT PRIMARY KEY," +
                "world TEXT NOT NULL," +
                "x INTEGER NOT NULL," +
                "y INTEGER NOT NULL," +
                "z INTEGER NOT NULL," +
                "loot_table TEXT," +
                "created_at INTEGER NOT NULL" +
                ");"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS player_loot (" +
                "container_id TEXT NOT NULL," +
                "player_uuid TEXT NOT NULL," +
                "inventory_blob BLOB," +
                "opened INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(container_id, player_uuid)," +
                "FOREIGN KEY(container_id) REFERENCES containers(id) ON DELETE CASCADE" +
                ");"
            );
        }
        plugin.getLogger().info("Lootr SQLite database initialized");
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException ignored) {}
    }
}
