package dev.gloved.glovedplugin.summon.soul.table.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Common contract for Soul Table UI screens (hub + process views). */
public interface SoulTableScreen extends InventoryHolder {

    UUID getViewerId();

    UUID getTableId();

    @Override
    @NotNull Inventory getInventory();

    void open(Player player);

    void refresh();

    void handleClick(Player player, int rawSlot, ClickType clickType);
}
