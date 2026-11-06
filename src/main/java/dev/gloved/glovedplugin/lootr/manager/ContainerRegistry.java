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
            return true;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM containers WHERE id = ?")) {
            ps.setString(1, id.toStringKey());
            try (ResultSet rs = ps.executeQuery()) {
                boolean found = rs.next();
                if (found) cache.add(id);
                return found;
            }
        } catch (SQLException e) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr SQLException on isRegistered " + id.toStringKey() + ": " + e.getMessage());
            return false;
        }
    }

    public void register(ContainerId id, String lootTable) {
        // Preserve any existing elite marker; only the loot table is refreshed.
        upsert(id, lootTable, isElite(id));
    }

    /**
     * Registers or updates a container, overwriting the stored loot table and
     * elite marker. Used by {@code /lootr generate} and {@code /lootr convert}
     * so an explicit admin action always wins over the previously stored state.
     */
    public void upsert(ContainerId id, String lootTable, boolean elite) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO containers(id, world, x, y, z, loot_table, is_elite, created_at) VALUES(?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET loot_table = excluded.loot_table, is_elite = excluded.is_elite")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, id.world().toString());
            ps.setInt(3, id.x());
            ps.setInt(4, id.y());
            ps.setInt(5, id.z());
            ps.setString(6, lootTable);
            ps.setInt(7, elite ? 1 : 0);
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
            cache.add(id);
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr ContainerRegistry.upsert id=" + id.toStringKey() + " loot=" + lootTable + " elite=" + elite);
        } catch (SQLException e) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr SQLException on upsert " + id.toStringKey() + ": " + e.getMessage());
        }
    }

    public boolean isElite(ContainerId id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT is_elite FROM containers WHERE id = ?")) {
            ps.setString(1, id.toStringKey());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("is_elite") == 1;
            }
        } catch (SQLException e) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr SQLException on isElite " + id.toStringKey() + ": " + e.getMessage());
            return false;
        }
    }

    public void setElite(ContainerId id, boolean elite) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE containers SET is_elite = ? WHERE id = ?")) {
            ps.setInt(1, elite ? 1 : 0);
            ps.setString(2, id.toStringKey());
            ps.executeUpdate();
        } catch (SQLException e) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr SQLException on setElite " + id.toStringKey() + ": " + e.getMessage());
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
