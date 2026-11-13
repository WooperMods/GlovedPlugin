package dev.gloved.glovedplugin.menu.command;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.menu.inventory.MenuInv;
import dev.gloved.glovedplugin.menu.listener.MenuHandler;
import org.bukkit.entity.Player;

/**
 * Shared main-menu open flow (D1). Preserves stash/refusal/open behavior for all entry points.
 */
public final class MenuOpenService {

    private MenuOpenService() {
    }

    /**
     * Attempts to open the main menu for a player.
     * <p>Stashes the cursor first; if the inventory is full the menu stays closed, items are untouched,
     * and the player is notified with the existing refusal message.
     *
     * @return {@code true} if the menu was opened, {@code false} if refused
     */
    public static boolean tryOpenMainMenu(GlovedPlugin plugin, Player player) {
        // Opening a window discards the carried stack: stash it first.
        // Full inventory: refuse instead, menu stays closed, items untouched.
        if (!MenuHandler.stashCursor(player)) {
            player.sendMessage("Free an inventory slot first!");
            return false;
        }
        new MenuInv(plugin).openMenu(player);
        return true;
    }
}
