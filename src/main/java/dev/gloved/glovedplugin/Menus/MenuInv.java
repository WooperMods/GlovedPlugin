package dev.gloved.glovedplugin.Menus;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.MenuConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The MenuInv class implements the InventoryHolder interface.
 * It represents a custom inventory menu for the GlovedPlugin.
 * This class is responsible for creating the menu, setting up the items in the menu, and providing access to the menu.
 * It uses the Bukkit API to create an inventory and the Kyori Adventure API to create text components for item names and lore.
 */
public class MenuInv implements InventoryHolder {

    private final GlovedPlugin plugin;

    public static NamespacedKey menuKey() {
        return new NamespacedKey(JavaPlugin.getPlugin(GlovedPlugin.class), "menu-opener");
    }

    /**
     * Constructor for the MenuInv class.
     * Stores the plugin reference. Inventories are built fresh per-open
     * so hot-reloads never serve a stale shared inventory.
     *
     * @param plugin The instance of the GlovedPlugin.
     */
    public MenuInv(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public Inventory createMenuInventory() {
        return buildMenuInventory(true, true);
    }

    public Inventory createMenuInventory(Player viewer) {
        return buildMenuInventory(viewer != null && viewer.isOp(),
                viewer != null && viewer.hasPermission("gloved.edit"));
    }

    private Inventory buildMenuInventory(boolean isOp, boolean canEdit) {
        GlovedPlugin plugin = JavaPlugin.getPlugin(GlovedPlugin.class);
        MenuConfig config = plugin.getMenuConfig();
        if (config == null) {
            config = MenuConfig.load(plugin);
        }
        Inventory inventory = this.plugin.getServer().createInventory(this, config.rows() * 9, config.titleComponent());
        for (Map.Entry<Integer, MenuConfig.MenuItem> entry : config.layout().entrySet()) {
            MenuConfig.MenuItem mi = entry.getValue();
            // Operator-only buttons are skipped for non-operators; the filler
            // below covers the gap so positions stay stable for everyone.
            if (!mi.visibleTo(isOp)) continue;
            List<String> lore = new ArrayList<>(buttonLore(mi));
            if (canEdit) {
                lore.add("");
                lore.add("&eShift Click to Edit");
            }
            inventory.setItem(entry.getKey(), getItem(new ItemStack(mi.material()), mi.name(),
                    lore.toArray(new String[0])));
        }
        if (config.fillerEnabled()) {
            ItemStack filler = getItem(new ItemStack(config.fillerMaterial()), config.fillerName(),
                    config.fillerLore().toArray(new String[0]));
            int size = config.rows() * 9;
            for (int i = 0; i < size; i++) {
                if (inventory.getItem(i) == null) inventory.setItem(i, filler.clone());
            }
        }
        return inventory;
    }

    /**
     * This method is used to retrieve a fresh menu inventory.
     *
     * @return A newly built menu inventory.
     */
    @Override
    public @NotNull Inventory getInventory() {
        return createMenuInventory();
    }

    public void openMenu(Player player) {
        player.openInventory(createMenuInventory(player));
    }

    public static ItemStack createMenuOpener() {
        GlovedPlugin plugin = JavaPlugin.getPlugin(GlovedPlugin.class);
        MenuConfig config = plugin.getMenuConfig();
        Material mat = config != null ? config.openerMaterial() : Material.CHEST;
        String name = config != null ? config.openerName() : "&b&lMenu";
        List<String> lore = config != null ? config.openerLore() : List.of("&aClick to Open the Menu!");
        ItemStack opener = getItem(new ItemStack(mat), name, lore.toArray(new String[0]));
        ItemMeta meta = opener.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(menuKey(), PersistentDataType.BYTE, (byte) 1);
            opener.setItemMeta(meta);
        }
        return opener;
    }

    public static boolean isMenuOpener(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(menuKey(), PersistentDataType.BYTE);
    }

    /**
     * The full lore for a menu button: the command auto-shown as the first
     * line (like the old defaults), then the configured descriptive lines.
     * Blank commands show no auto line.
     */
    public static List<String> buttonLore(MenuConfig.MenuItem mi) {
        List<String> lore = new ArrayList<>();
        if (mi.command() != null && !mi.command().isBlank()) {
            lore.add("&a/" + mi.command().strip());
        }
        lore.addAll(mi.lore());
        return lore;
    }

    /**
     * This method is used to create a custom ItemStack with a given name and lore.
     * It first retrieves the ItemMeta from the provided ItemStack, then sets the display name and lore of the item.
     * The display name and lore are converted into Components using the makeComponent method from the plugin instance.
     * Finally, it sets the modified ItemMeta back to the ItemStack and returns it.
     *
     * @param item The original ItemStack that needs to be customized.
     * @param name The display name that needs to be set for the item.
     * @param lore The lore that needs to be set for the item. It can be multiple lines.
     * @return The customized ItemStack with the provided name and lore.
     */
    public static ItemStack getItem(ItemStack item, String name, String... lore) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noAutoItalic(GlovedPlugin.makeComponent(name)));
        List<Component> lores = new ArrayList<>();
        for (String s : lore) {
            lores.add(noAutoItalic(GlovedPlugin.makeComponent(s)));
        }
        meta.lore(lores);
        // logger.info("Item created: " + item.getType() + " with name: " + name + " and lore: " + lores + ".");

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Vanilla renders names/lore italic unless italic is explicitly set.
     * Opt out only when the author's codes didn't ask for italic ({@code &o}).
     */
    static Component noAutoItalic(Component component) {
        if (component == null) return null;
        if (!component.hasDecoration(TextDecoration.ITALIC)) {
            return component.decoration(TextDecoration.ITALIC, false);
        }
        return component;
    }
}
