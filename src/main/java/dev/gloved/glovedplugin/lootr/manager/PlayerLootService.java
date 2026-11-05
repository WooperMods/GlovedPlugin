package dev.gloved.glovedplugin.lootr.manager;

import dev.gloved.glovedplugin.lootr.model.ContainerId;
import dev.gloved.glovedplugin.lootr.util.InventorySerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    public byte[] getInventoryBlob(ContainerId id, UUID player) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT inventory_blob FROM player_loot WHERE container_id = ? AND player_uuid = ?")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBytes("inventory_blob");
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public void saveInventory(ContainerId id, UUID player, Inventory inv) {
        byte[] data = InventorySerializer.serialize(inv);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO player_loot(container_id, player_uuid, inventory_blob, opened) VALUES(?,?,?,1) ON CONFLICT(container_id, player_uuid) DO UPDATE SET inventory_blob = excluded.inventory_blob, opened = 1")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, player.toString());
            ps.setBytes(3, data);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public int getEliteFlag(ContainerId id, UUID player) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT elite_flag FROM player_loot WHERE container_id = ? AND player_uuid = ?")) {
            ps.setString(1, id.toStringKey());
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("elite_flag");
            }
        } catch (SQLException ignored) {}
        return 0;
    }

    public void setEliteFlag(ContainerId id, UUID player, int flag) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE player_loot SET elite_flag = ? WHERE container_id = ? AND player_uuid = ?")) {
                ps.setInt(1, flag);
                ps.setString(2, id.toStringKey());
                ps.setString(3, player.toString());
                int updated = ps.executeUpdate();
                dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr PlayerLootService.setEliteFlag UPDATE updated=" + updated + " id=" + id.toStringKey() + " player=" + player);
                if (updated == 0) {
                    try (PreparedStatement ins = conn.prepareStatement(
                            "INSERT INTO player_loot(container_id, player_uuid, elite_flag, opened) VALUES(?,?,?,0)")) {
                        ins.setString(1, id.toStringKey());
                        ins.setString(2, player.toString());
                        ins.setInt(3, flag);
                        ins.executeUpdate();
                        dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr PlayerLootService.setEliteFlag INSERTED id=" + id.toStringKey() + " player=" + player);
                    }
                }
            }
        } catch (SQLException e) {
            dev.gloved.glovedplugin.GlovedPlugin.logDebug("Lootr PlayerLootService.setEliteFlag SQLException " + e.getMessage() + " id=" + id.toStringKey() + " player=" + player);
        }
    }
}
