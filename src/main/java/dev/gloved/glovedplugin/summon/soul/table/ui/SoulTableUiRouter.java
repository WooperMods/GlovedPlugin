package dev.gloved.glovedplugin.summon.soul.table.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Routes Soul Table GUI inventory clicks to the owning inventory holders.
 * Keeps routing logic in the soul table UI package (D6/T2.2) while preserving behavior.
 */
public final class SoulTableUiRouter {

    private SoulTableUiRouter() {
    }

    public static boolean handleClick(InventoryClickEvent event, Player player) {
        if (event.getView().getTopInventory().getHolder() instanceof SoulTableInv table) {
            event.setCancelled(true);
            table.handleClick(player, event.getRawSlot(), event.getClick());
            return true;
        }
        if (event.getView().getTopInventory().getHolder() instanceof SoulTableGuideInv guide) {
            event.setCancelled(true);
            guide.handleClick(player, event.getRawSlot(), event.getClick());
            return true;
        }
        return false;
    }
}
