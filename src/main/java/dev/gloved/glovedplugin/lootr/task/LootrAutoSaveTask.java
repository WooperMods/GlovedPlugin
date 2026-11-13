package dev.gloved.glovedplugin.lootr.task;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.db.LootrDatabase;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Periodic WAL checkpoint task for Lootr storage. Preserves existing scheduling and behavior.
 */
public final class LootrAutoSaveTask extends BukkitRunnable {
    private final GlovedPlugin plugin;
    private final LootrDatabase database;

    public LootrAutoSaveTask(GlovedPlugin plugin, LootrDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

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
}
