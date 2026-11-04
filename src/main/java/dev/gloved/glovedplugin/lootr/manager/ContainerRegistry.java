package dev.gloved.glovedplugin.lootr.manager;

import dev.gloved.glovedplugin.lootr.model.ContainerId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

public class ContainerRegistry {
    private final Connection conn;
    private final Set<ContainerId> cache = new HashSet<>();

    public ContainerRegistry(Connection conn) {
        this.conn = conn;
    }

    public boolean isRegistered(ContainerId id) {
        if (cache.contains(id)) return true;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM containers WHERE id = ?")) {
            ps.setString(1, id.toStringKey());
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                if (found) cache.add(id);
                return found;
            }
        } catch (SQLException e) {
            System.out.println("[Lootr] SQLException on isRegistered " + id.toStringKey() + ": " + e.getMessage());
            return false;
        }
    }

    public void register(ContainerId id, String lootTable) {
        if (isRegistered(id)) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO containers(id, world, x, y, z, loot_table, created_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, id.world().toString());
            ps.setInt(3, id.x());
            ps.setInt(4, id.y());
            ps.setInt(5, id.z());
            ps.setString(6, lootTable);
            ps.setLong(7, System.currentTimeMillis());
            int rows = ps.executeUpdate();
            cache.add(id);
            if (rows == 0) {
                // Log for debugging
                System.out.println("[Lootr] INSERT OR IGNORE affected 0 rows for " + id.toStringKey());
            }
        } catch (SQLException e) {
            System.out.println("[Lootr] SQLException on register " + id.toStringKey() + ": " + e.getMessage());
        }
    }

    public String getLootTable(ContainerId id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT loot_table FROM containers WHERE id = ?")) {
            ps.setString(1, id.toStringKey());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("loot_table");
            }
        } catch (SQLException e) {
            System.out.println("[Lootr] SQLException on getLootTable " + id.toStringKey() + ": " + e.getMessage());
        }
        return null;
    }
}
