package dev.gloved.glovedplugin.handlers;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import dev.gloved.glovedplugin.MenuConfig;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.GlovedPlugin;

import static org.bukkit.Bukkit.getPluginManager;
import static dev.gloved.glovedplugin.GlovedPlugin.logInfo;

/**
 * The MenuHandler class implements the Listener interface.
 * This class is responsible for handling various events related to the game menu.
 * It includes methods to handle events such as a player joining the game, a player clicking on an inventory, etc.
 * Each of these methods is annotated with the @EventHandler annotation to indicate that they are event handlers.
 */
public class MenuHandler implements Listener {

    private final GlovedPlugin plugin;

    /**
     * Constructor for the MenuHandler class.
     * It registers the MenuHandler as a listener for events in the Bukkit server.
     * This is done by calling the registerEvents method from the PluginManager instance retrieved from the Bukkit server.
     * The method takes two parameters: the listener (this) and the plugin instance.
     * After the event registration, it logs a message indicating that the MenuHandler has been registered.
     *
     * @param plugin The instance of the GlovedPlugin.
     */
    public MenuHandler(GlovedPlugin plugin) {
        this.plugin = plugin;
        getPluginManager().registerEvents(this, plugin);
        logInfo("Registered MenuHandler!");
    }

    /**
     * Event handler for inventory click events.
     * This method is triggered when a player clicks on an inventory.
     * It first checks if the clicked inventory is a MenuInv instance.
     * If it is not, the method returns immediately.
     * If it is, the method cancels the event to prevent the default behavior.
     * Then, it checks if the clicked inventory is a chest.
     * If it is, it retrieves the clicked slot and performs different actions based on the slot number.
     * For each slot, it plays a sound, closes the inventory, and performs a command.
     * If the clicked inventory is not a chest, it logs the clicked slot.
     *
     * @param event The inventory click event.
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inventory = event.getClickedInventory();
        if (inventory == null) return;
        MenuConfig config = plugin.getMenuConfig();
        if (config == null) return;
        Component title = event.getView().title();
        if (!title.equals(config.titleComponent())) {
            return;
        }
        if (!inventory.getType().equals(InventoryType.CHEST)) {
            return;
        }

        int slot = event.getSlot();
        event.setCancelled(true);

        String command = config.commandForSlot(slot);
        if (command == null) return;

        Sound sound = config.sound();
        player.playSound(player.getLocation(), sound, 1, 1);
        player.closeInventory();
        player.performCommand(command);
    }

    /**
     * Event handler for player join events.
     * This method is triggered when a player joins the game.
     * It first retrieves the player and their inventory.
     * Then, it adds a new item to the 18th slot of the player's inventory.
     * The new item is a chest with the name "Menu" and the lore "Click to Open the Menu!".
     * After adding the item, it logs a message indicating that the menu item has been added to the player's inventory.
     *
     * @param event The player join event.
     */
    public static void giveMenuOpener(Player player) {
        GlovedPlugin plugin = JavaPlugin.getPlugin(GlovedPlugin.class);
        MenuConfig config = plugin.getMenuConfig();
        int slot = config != null ? config.openerSlot() : 17;
        player.getInventory().setItem(slot, MenuInv.createMenuOpener());
        player.updateInventory();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        giveMenuOpener(event.getPlayer());
        logInfo("Menu Item added to " + event.getPlayer().getName() + " inventory.");
    }

    /**
     * Event handler for inventory click events.
     * This method is triggered when a player clicks on an inventory.
     * It first retrieves the player who clicked and the bottom inventory of the clicked view.
     * Then, it checks if the clicked slot is not 17 and if the clicked inventory is not the bottom inventory.
     * If either of these conditions is true, the method returns immediately.
     * If both conditions are false, it plays a sound at the player's location, closes the player's inventory, cancels the event to prevent the default behavior, and performs the "menu" command.
     *
     * @param event The inventory click event.
     */
    @EventHandler
    public void onPlayerClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;
        Inventory bottom = event.getView().getBottomInventory();
        if (clicked != bottom) return;

        MenuConfig config = plugin.getMenuConfig();
        int openerSlot = config != null ? config.openerSlot() : 17;
        Material openerMaterial = config != null ? config.openerMaterial() : Material.CHEST;

        ItemStack current = event.getCurrentItem();
        boolean isOpenerSlot = event.getSlot() == openerSlot;
        boolean isOpenerItem = MenuInv.isMenuOpener(current) || MenuInv.isMenuOpener(event.getCursor());

        // Fallback for legacy items created before the PDC tag existed:
        // treat the configured opener material in the opener slot as the opener
        // so old broken items still work until they are replaced on re-enable.
        if (!isOpenerItem && !(isOpenerSlot && current != null && current.getType() == openerMaterial)) {
            return;
        }

        event.setCancelled(true);
        player.setItemOnCursor(null);
        // Re-assert the correct opener in case the player was holding a stale copy
        // (fixes "chest stuck on cursor when reopening" after hot-reload).
        giveMenuOpener(player);
        Sound sound = config != null ? config.sound() : Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        player.playSound(player.getLocation(), sound, 1, 1);
        player.closeInventory();
        player.performCommand("menu");
    }

}
