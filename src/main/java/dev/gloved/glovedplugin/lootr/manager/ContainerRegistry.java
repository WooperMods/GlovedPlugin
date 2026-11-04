package dev.gloved.glovedplugin.lootr.manager;

import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.ContainerState;
import org.bukkit.loot.Lootable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
            return false;
        }
    }

    public void register(Block block, String lootTable) {
        World w = block.getWorld();
        ContainerId id = new ContainerId(w.getUID(), block.getX(), block.getY(), block.getZ());
        if (isRegistered(id)) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO containers(id, world, x, y, z, loot_table, created_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, w.getName());
            ps.setInt(3, block.getX());
            ps.setInt(4, block.getY());
            ps.setInt(5, block.getZ());
            ps.setString(6, lootTable);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
            cache.add(id);
        } catch (SQLException ignored) {}
    }

    public String getLootTable(ContainerId id) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT loot_table FROM containers WHERE id = ?")) {
            ps.setString(1, id.toStringKey());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("loot_table");
            }
        } catch (SQLException ignored) {}
        return null;
    }
}
