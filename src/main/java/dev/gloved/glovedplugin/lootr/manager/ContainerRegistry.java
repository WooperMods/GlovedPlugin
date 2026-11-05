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
        if (cache.contains(id)) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr ContainerRegistry.isRegistered cache hit " + id.toStringKey());
            return true;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM containers WHERE id = ?")) {
            ps.setString(1, id.toStringKey());
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                if (found) cache.add(id);
                dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr ContainerRegistry.isRegistered db found=" + found + " id=" + id.toStringKey());
                return found;
            }
        } catch (SQLException e) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr SQLException on isRegistered " + id.toStringKey() + ": " + e.getMessage());
            return false;
        }
    }

    public void register(ContainerId id, String lootTable) {
        if (isRegistered(id)) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr ContainerRegistry.register skip already registered id=" + id.toStringKey());
            return;
        }
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
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr ContainerRegistry.register inserted rows=" + rows + " id=" + id.toStringKey() + " loot=" + lootTable);
            if (rows == 0) {
                dev.gloved.glovedplugin.GlovedPlugin.logDebug("[Lootr] INSERT OR IGNORE affected 0 rows for " + id.toStringKey());
            }
        } catch (SQLException e) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr SQLException on register " + id.toStringKey() + ": " + e.getMessage());
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

    public void unregister(ContainerId id) {
        try {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM containers WHERE id = ?")) {
                ps.setString(1, id.toStringKey());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_loot WHERE container_id = ?")) {
                ps.setString(1, id.toStringKey());
                ps.executeUpdate();
            }
            cache.remove(id);
        } catch (SQLException e) {
            System.out.println("[Lootr] SQLException on unregister " + id.toStringKey() + ": " + e.getMessage());
        }
    }

    public java.util.List<ContainerId> findAllInChunk(java.util.UUID world, int chunkX, int chunkZ) {
        java.util.List<ContainerId> out = new java.util.ArrayList<>();
        int minX = chunkX << 4;
        int maxX = minX + 15;
        int minZ = chunkZ << 4;
        int maxZ = minZ + 15;
        String worldStr = world.toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM containers WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")) {
            ps.setString(1, worldStr);
            ps.setInt(2, minX);
            ps.setInt(3, maxX);
            ps.setInt(4, minZ);
            ps.setInt(5, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String idStr = rs.getString("id");
                    String[] parts = idStr.split(":");
                    if (parts.length == 4) {
                        java.util.UUID w = java.util.UUID.fromString(parts[0]);
                        int x = Integer.parseInt(parts[1]);
                        int y = Integer.parseInt(parts[2]);
                        int z = Integer.parseInt(parts[3]);
                        ContainerId cid = new ContainerId(w, x, y, z);
                        out.add(cid);
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("[Lootr] SQLException on findAllInChunk " + world + " " + chunkX + "," + chunkZ + ": " + e.getMessage());
        }
        return out;
    }
}
