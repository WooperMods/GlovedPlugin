package dev.gloved.glovedplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.commands.GlovedCommand;
import dev.gloved.glovedplugin.commands.Menu;
import dev.gloved.glovedplugin.handlers.MenuHandler;
import dev.gloved.glovedplugin.handlers.TorchHandler;

import java.util.logging.Logger;

/**
 * The MenuInv class implements the InventoryHolder interface.
 * It represents a custom inventory menu for the GlovedPlugin.
 * This class is responsible for creating the menu, setting up the items in the menu, and providing access to the menu.
 * It uses the Bukkit API to create an inventory and the Kyori Adventure API to create text components for item names and lore.
 */
public final class GlovedPlugin extends JavaPlugin {

    private static Logger logger;

    private MenuConfig menuConfig;

    {
        logger = getLogger();
    }

    /**
     * This method is used to log information messages in a specific format.
     * The format is: <###>-------------< {message} >-------------<###>
     *
     * @param message The message that needs to be logged.
     */
    public static void logInfo(String message) {
        logger.info("<##>-------------< " + message + " >-------------<##>");
    }

    /**
     * This method is used to log warning messages in a specific format.
     * The format is: <###>-------------< {message} >-------------<###>
     *
     * @param message The warning message that needs to be logged.
     */
    public static void logWarning(String message) {
        logger.warning("<###>-------------< " + message + " >-------------<###>");
    }

    /**
     * This method is used to create a Component from a given text string.
     * It uses Adventure's LegacyComponentSerializer to convert legacy & codes.
     *
     * @param input The text string that needs to be converted into a Component.
     * @return The created Component.
     */
    public static Component makeComponent(String input) {
        
        return LegacyComponentSerializer.legacyAmpersand().deserialize(input);
    }

    /**
     * This method is called when the plugin is enabled.
     * It logs a message indicating that the GlovedPlugin has been loaded.
     * It also initializes various handlers and menus, and registers the plugin with FastInvManager.
     */
    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        this.menuConfig = MenuConfig.load(this);
        Menu.refreshTitle(this.menuConfig);

        // Initialize TorchHandler
        new TorchHandler(this);
        // Initialize MenuHandler
        new MenuHandler(this);
        // Initialize MenuInv
        new MenuInv(this);

        // Register menu command using Paper's registerCommand API
        Menu menuCommand = new Menu();
        this.registerCommand("menu", "Custom menu for Commands", menuCommand);
        this.registerCommand("gloved", "GlovedPlugin admin command", new GlovedCommand(this));
        logInfo("Registered Menu Command!");

        // Hot-reload safe: PlayerJoin doesn't fire for already-online players
        // when using a runtime plugin reloader, so refresh the opener for everyone.
        // This also replaces stale items created before the color-code fix.
        for (Player player : Bukkit.getOnlinePlayers()) {
            MenuHandler.giveMenuOpener(player);
        }

        logInfo("GlovedPlugin has been loaded!");

    }

    public MenuConfig getMenuConfig() {
        return menuConfig;
    }

    public void reloadMenuConfig() {
        reloadConfig();
        this.menuConfig = MenuConfig.load(this);
        Menu.refreshTitle(this.menuConfig);
    }

    /**
     * This method is called when the plugin is disabled.
     * It logs a message indicating that the GlovedPlugin has been disabled.
     */
    @Override
    public void onDisable() {
        // Close any open menu views so a hot-reload doesn't leave ghost
        // inventories / cursor items attached.
        Component title = menuConfig != null ? menuConfig.titleComponent() : Menu.menuComponent;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().title().equals(title)) {
                player.closeInventory();
            }
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && cursor.getType() != Material.AIR && MenuInv.isMenuOpener(cursor)) {
                player.setItemOnCursor(null);
            }
        }
        logInfo("GlovedPlugin has been disabled!");
    }


}
