package dev.gloved.glovedplugin.summon.soul.table.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Routes Soul Table GUI inventory clicks to the owning screen holders.
 */
public final class SoulTableUiRouter {

    private SoulTableUiRouter() {
    }

    public static boolean handleClick(InventoryClickEvent event, Player player) {
        if (event.getView().getTopInventory().getHolder() instanceof SoulTableScreen screen) {
            event.setCancelled(true);
            screen.handleClick(player, event.getRawSlot(), event.getClick());
            return true;
        }
        return false;
    }
}
