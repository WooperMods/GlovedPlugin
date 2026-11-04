package dev.gloved.glovedplugin.lootr.manager;

import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

public class PlayerLootService {
    private final Connection conn;

    public PlayerLootService(Connection conn) {
        this.conn = conn;
    }

    public boolean hasOpened(ContainerId id, UUID player) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT opened FROM player_loot WHERE container_id = ? AND player_uuid = ?")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("opened") == 1;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void markOpened(ContainerId id, UUID player) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO player_loot(container_id, player_uuid, opened) VALUES(?,?,1)")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, player.toString());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    // Placeholder serialization
    public byte[] serializeInventory(Inventory inv) {
        // Simple placeholder: return empty
        return new byte[0];
    }
}
