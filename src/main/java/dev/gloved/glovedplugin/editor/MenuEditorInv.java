package dev.gloved.glovedplugin.editor;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.MenuConfig;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.handlers.MenuHandler;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The in-game menu editor. Each open view is built from the opener's
 * {@link EditSession} working copy; nothing is written to config.yml until
 * Save. Routed by holder type in {@code MenuHandler}, so editor clicks can
 * never leak into menu/opener handling (and vice versa).
 */
public class MenuEditorInv implements InventoryHolder {

    public enum Screen {
        PREVIEW, CONTROLS, ITEM, SETTINGS, OPENER, PICKSLOT, HIDDEN, LORE
    }

    private static final Material CHROME = Material.GRAY_STAINED_GLASS_PANE;

    private final GlovedPlugin plugin;
    private final EditSession session;
    private final Screen screen;
    /** Item index for ITEM/PICKSLOT screens, -1 otherwise. */
    private final int index;

    public MenuEditorInv(GlovedPlugin plugin, EditSession session, Screen screen, int index) {
        this.plugin = plugin;
        this.session = session;
        this.screen = screen;
        this.index = index;
    }

    public void open(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.openInventory(build());
            if (screen == Screen.PREVIEW) {
                player.sendActionBar(Component.text("Click: edit button • Shift-click: delete • Empty space: controls"));
            } else if (screen == Screen.PICKSLOT) {
                player.sendActionBar(Component.text("Click a slot to pin it • Shift-click for auto position"));
            }
        });
    }

    /**
     * Opens the editor for a player: stashes a held stack first (refusing when
     * the inventory is full), then resumes an existing session or snapshots a
     * fresh working copy. Shared by /gloved edit and shift-clicking the menu.
     */
    public static void openEditorFor(GlovedPlugin plugin, Player player) {
        if (plugin.getMenuConfig() == null) {
            player.sendMessage(Component.text("Menu config isn't loaded."));
            return;
        }
        if (!MenuHandler.stashCursor(player)) {
            player.sendActionBar(Component.text("Free an inventory slot first!"));
            player.updateInventory();
            return;
        }
        EditSession session = EditSession.get(player.getUniqueId());
        boolean resumed = session != null;
        if (session == null) {
            session = EditSession.snapshot(plugin.getMenuConfig(), EditSession.configModifiedAt(plugin));
            EditSession.put(player.getUniqueId(), session);
        }
        new MenuEditorInv(plugin, session, Screen.PREVIEW, -1).open(player);
        player.sendMessage(Component.text(resumed
                ? "Resumed your unsaved menu edits."
                : "Editing a copy of the menu — Save to apply, Discard to throw away."));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return build();
    }

    // ------------------------------------------------------------------ build

    private ItemStack btn(Material material, String name, String... lore) {
        return MenuInv.getItem(new ItemStack(material), name, lore);
    }

    private void chrome(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, btn(CHROME, " "));
        }
    }

    private void fillWorkingFiller(Inventory inv) {
        if (!session.fillerEnabled) return;
        ItemStack filler = MenuInv.getItem(new ItemStack(session.fillerMaterial), session.fillerName,
                session.fillerLore.toArray(new String[0]));
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler.clone());
        }
    }

    private Map<Integer, MenuConfig.MenuItem> workingLayout() {
        return MenuConfig.buildLayout(session.rows, session.itemsPerRow, session.items);
    }

    private int indexOf(MenuConfig.MenuItem mi) {
        for (int i = 0; i < session.items.size(); i++) {
            if (session.items.get(i) == mi) return i;
        }
        return -1;
    }

    private static String anchorLabel(MenuConfig.Anchor anchor) {
        if (anchor == null) return "Auto (center)";
        return switch (anchor) {
            case TOP_LEFT -> "Top Left";
            case TOP_CENTER -> "Top";
            case TOP_RIGHT -> "Top Right";
            case LEFT -> "Left";
            case CENTER -> "Center";
            case RIGHT -> "Right";
            case BOTTOM_LEFT -> "Bottom Left";
            case BOTTOM_CENTER -> "Bottom";
            case BOTTOM_RIGHT -> "Bottom Right";
        };
    }

    private static MenuConfig.Anchor nextAnchor(MenuConfig.Anchor anchor) {
        MenuConfig.Anchor[] order = {null,
                MenuConfig.Anchor.TOP_LEFT, MenuConfig.Anchor.TOP_CENTER, MenuConfig.Anchor.TOP_RIGHT,
                MenuConfig.Anchor.LEFT, MenuConfig.Anchor.CENTER, MenuConfig.Anchor.RIGHT,
                MenuConfig.Anchor.BOTTOM_LEFT, MenuConfig.Anchor.BOTTOM_CENTER, MenuConfig.Anchor.BOTTOM_RIGHT};
        for (int i = 0; i < order.length; i++) {
            if (order[i] == anchor) return order[(i + 1) % order.length];
        }
        return null;
    }

    private Inventory build() {
        return switch (screen) {
            case PREVIEW -> buildPreview();
            case CONTROLS -> buildControls();
            case ITEM -> buildItem();
            case SETTINGS -> buildSettings();
            case OPENER -> buildOpener();
            case PICKSLOT -> buildPickSlot();
            case HIDDEN -> buildHidden();
            case LORE -> buildLore();
        };
    }

    private Inventory buildPreview() {
        Inventory inv = plugin.getServer().createInventory(this, session.rows * 9, Component.text("Menu Editor"));
        for (Map.Entry<Integer, MenuConfig.MenuItem> entry : workingLayout().entrySet()) {
            MenuConfig.MenuItem mi = entry.getValue();
            List<String> lore = new ArrayList<>(MenuInv.buttonLore(mi));
            lore.add("");
            int idx = indexOf(mi);
            if (session.armedDelete == idx && idx != -1) {
                lore.add("&c&lCLICK AGAIN TO CONFIRM DELETE");
            } else {
                lore.add("&eClick to edit");
                lore.add("&7Shift-click to delete");
            }
            inv.setItem(entry.getKey(), MenuInv.getItem(new ItemStack(mi.material()), mi.name(),
                    lore.toArray(new String[0])));
        }
        fillWorkingFiller(inv);
        return inv;
    }

    private Inventory buildControls() {
        Inventory inv = plugin.getServer().createInventory(this, 9, Component.text("Menu Editor"));
        inv.setItem(1, btn(Material.EMERALD, "&aAdd Button",
                "&7Type a material name in chat"));
        inv.setItem(2, btn(Material.WRITABLE_BOOK, "&eMenu Settings",
                "&7Title, size, sound, filler"));
        inv.setItem(3, btn(Material.CHEST, "&eOpener Settings",
                "&7The menu item in player inventories"));
        long hidden = session.items.stream().filter(mi -> !mi.enabled()).count();
        inv.setItem(4, btn(Material.GRAY_DYE, "&7Hidden Buttons (" + hidden + ")",
                "&7Disabled buttons live here"));
        inv.setItem(5, btn(Material.GREEN_WOOL, "&a&lSave",
                "&7Write to config.yml and reload"));
        inv.setItem(6, btn(Material.RED_WOOL, "&c&lDiscard",
                "&7Throw away unsaved edits"));
        inv.setItem(7, btn(Material.ARROW, "&fBack to Preview"));
        chrome(inv);
        return inv;
    }

    private MenuConfig.MenuItem editedItem() {
        if (index < 0 || index >= session.items.size()) return null;
        return session.items.get(index);
    }

    private Inventory buildItem() {
        Inventory inv = plugin.getServer().createInventory(this, 18, Component.text("Edit Button"));
        MenuConfig.MenuItem mi = editedItem();
        if (mi == null) return buildPreview();
        boolean armed = session.armedDelete == index;
        inv.setItem(0, mi.enabled()
                ? btn(Material.GREEN_WOOL, "&aEnabled", "&7Click to disable")
                : btn(Material.RED_WOOL, "&cDisabled", "&7Click to enable"));
        inv.setItem(1, mi.opOnly()
                ? btn(Material.GOLD_INGOT, "&6Operator Only", "&7Click to show to everyone")
                : btn(Material.IRON_INGOT, "&fEveryone", "&7Click to restrict to operators"));
        inv.setItem(2, btn(Material.COMPASS, "&eAnchor: " + anchorLabel(mi.anchor()), "&7Click to cycle"));
        inv.setItem(3, btn(Material.RED_DYE, "&cOffset X: " + mi.offsetX(), "&7Click: -1"));
        inv.setItem(4, btn(Material.GREEN_DYE, "&aOffset X: " + mi.offsetX(), "&7Click: +1"));
        inv.setItem(5, btn(Material.RED_DYE, "&cOffset Y: " + mi.offsetY(), "&7Click: -1"));
        inv.setItem(6, btn(Material.GREEN_DYE, "&aOffset Y: " + mi.offsetY(), "&7Click: +1"));
        inv.setItem(7, btn(Material.ENDER_EYE, "&eSet Slot",
                "&7Current: " + (mi.slot() == null ? "auto" : mi.slot()), "&7Click to pick a slot"));
        inv.setItem(8, btn(Material.CLOCK, "&eAuto Position", "&7Click to clear the slot pin"));
        inv.setItem(9, btn(mi.material(), "&eMaterial: " + mi.material().name(),
                "&7Click to type a material name"));
        inv.setItem(10, btn(Material.NAME_TAG, "&eName", "&7Current: " + mi.name(), "&7Click to type a new one"));
        List<String> loreLines = new ArrayList<>();
        loreLines.add("&7Lines: " + mi.lore().size() + " (+ command on top)");
        loreLines.add("&7Click to edit lines");
        inv.setItem(11, btn(Material.WRITABLE_BOOK, "&eLore", loreLines.toArray(new String[0])));
        String cmd = mi.command() == null || mi.command().isBlank() ? "&c(none — required!)" : "&7" + mi.command();
        inv.setItem(12, btn(Material.COMMAND_BLOCK, "&eCommand", cmd, "&7Click to type a new one"));
        inv.setItem(13, armed
                ? btn(Material.TNT, "&c&lClick Again to Confirm Delete")
                : btn(Material.TNT, "&cDelete Button", "&7Click, then click again"));
        inv.setItem(17, btn(Material.ARROW, "&fBack to Preview"));
        chrome(inv);
        return inv;
    }

    private Inventory buildSettings() {
        Inventory inv = plugin.getServer().createInventory(this, 18, Component.text("Menu Settings"));
        inv.setItem(0, btn(Material.NAME_TAG, "&eTitle", "&7Current: " + session.title, "&7Click to type a new one"));
        inv.setItem(1, btn(Material.RED_DYE, "&cRows: " + session.rows, "&7Click: -1 (min 1)"));
        inv.setItem(2, btn(Material.GREEN_DYE, "&aRows: " + session.rows, "&7Click: +1 (max 6)"));
        inv.setItem(3, btn(Material.RED_DYE, "&cPer Row: " + session.itemsPerRow, "&7Click: -1 (min 1)"));
        inv.setItem(4, btn(Material.GREEN_DYE, "&aPer Row: " + session.itemsPerRow, "&7Click: +1 (max 9)"));
        inv.setItem(5, btn(Material.NOTE_BLOCK, "&eClick Sound",
                "&7Current: " + session.soundName, "&7Click to type a new one"));
        inv.setItem(6, btn(Material.MUSIC_DISC_13, "&eHear Current Sound"));
        inv.setItem(7, session.fillerEnabled
                ? btn(Material.BLACK_WOOL, "&aFiller: On", "&7Click to disable filler panes")
                : btn(Material.GRAY_WOOL, "&cFiller: Off", "&7Click to enable filler panes"));
        inv.setItem(8, btn(session.fillerMaterial, "&eFiller Material",
                "&7Click to type a material name"));
        inv.setItem(17, btn(Material.ARROW, "&fBack"));
        chrome(inv);
        return inv;
    }

    private Inventory buildOpener() {
        Inventory inv = plugin.getServer().createInventory(this, 18, Component.text("Opener Settings"));
        inv.setItem(0, btn(session.openerMaterial, "&eMaterial",
                "&7Click to type a material name"));
        inv.setItem(1, btn(Material.RED_DYE, "&cSlot: " + session.openerSlot, "&7Click: -1"));
        inv.setItem(2, btn(Material.GREEN_DYE, "&aSlot: " + session.openerSlot, "&7Click: +1 (max 35)"));
        inv.setItem(3, btn(Material.NAME_TAG, "&eName", "&7Current: " + session.openerName, "&7Click to type a new one"));
        List<String> loreLines = new ArrayList<>();
        loreLines.add("&7Lines: " + session.openerLore.size());
        loreLines.add("&7Click to edit lines");
        inv.setItem(4, btn(Material.WRITABLE_BOOK, "&eLore", loreLines.toArray(new String[0])));
        inv.setItem(17, btn(Material.ARROW, "&fBack"));
        chrome(inv);
        return inv;
    }

    private Inventory buildPickSlot() {
        Inventory inv = plugin.getServer().createInventory(this, session.rows * 9, Component.text("Pick a Slot"));
        MenuConfig.MenuItem edited = editedItem();
        for (Map.Entry<Integer, MenuConfig.MenuItem> entry : workingLayout().entrySet()) {
            MenuConfig.MenuItem mi = entry.getValue();
            inv.setItem(entry.getKey(), MenuInv.getItem(new ItemStack(mi.material()), mi.name(),
                    MenuInv.buttonLore(mi).toArray(new String[0])));
        }
        fillWorkingFiller(inv);
        return inv;
    }

    private Inventory buildHidden() {
        Inventory inv = plugin.getServer().createInventory(this, 27, Component.text("Hidden Buttons"));
        int slot = 0;
        for (int i = 0; i < session.items.size() && slot < 26; i++) {
            MenuConfig.MenuItem mi = session.items.get(i);
            if (mi.enabled()) continue;
            List<String> hiddenLore = new ArrayList<>();
            hiddenLore.add("&cDisabled (hidden)");
            hiddenLore.addAll(MenuInv.buttonLore(mi));
            hiddenLore.add("");
            hiddenLore.add("&eClick to edit");
            inv.setItem(slot++, MenuInv.getItem(new ItemStack(mi.material()), mi.name(),
                    hiddenLore.toArray(new String[0])));
        }
        inv.setItem(26, btn(Material.ARROW, "&fBack"));
        chrome(inv);
        return inv;
    }

    // ------------------------------------------------------------------ clicks

    private MenuConfig.MenuItem with(MenuConfig.MenuItem mi, String name, List<String> lore, String command,
                                     MenuConfig.Anchor anchor, int ox, int oy, Integer slot,
                                     boolean opOnly, boolean enabled) {
        return new MenuConfig.MenuItem(mi.material(), name, lore, command, anchor, ox, oy, slot, opOnly, enabled);
    }

    public static MenuConfig.MenuItem withMaterial(MenuConfig.MenuItem mi, Material material) {
        return new MenuConfig.MenuItem(material, mi.name(), new ArrayList<>(mi.lore()), mi.command(),
                mi.anchor(), mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled());
    }

    private void reopen(Player player, Screen screen, int index) {
        new MenuEditorInv(plugin, session, screen, index).open(player);
    }

    private void disarmIfExpired() {
        if (session.armedDelete != -1 && System.currentTimeMillis() - session.armedDeleteAt > 10_000) {
            session.armedDelete = -1;
        }
    }

    /** Handles a click already cancelled by MenuHandler. Bottom clicks follow menu rules. */
    public void onClick(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        // TEMPORARY DIAGNOSTIC (DIAG3)
        GlovedPlugin.logInfo("DIAG3 click player=" + player.getName() + " screen=" + screen
                + " slot=" + event.getSlot() + " raw=" + event.getRawSlot() + " action=" + event.getAction());
        disarmIfExpired();
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) return;
        if (rawSlot >= topSize) {
            switch (event.getAction()) {
                case MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR -> {
                    event.setCancelled(true);
                    player.updateInventory();
                }
                default -> {}
            }
            return;
        }
        switch (screen) {
            case PREVIEW -> clickPreview(player, event);
            case CONTROLS -> clickControls(player, event.getSlot());
            case ITEM -> clickItem(player, event);
            case SETTINGS -> clickSettings(player, event);
            case OPENER -> clickOpener(player, event);
            case PICKSLOT -> clickPickSlot(player, event);
            case HIDDEN -> clickHidden(player, event.getSlot());
            case LORE -> clickLore(player, event);
        }
        player.updateInventory();
    }

    private void clickPreview(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        int slot = event.getSlot();
        Map<Integer, MenuConfig.MenuItem> layout = workingLayout();
        MenuConfig.MenuItem mi = layout.get(slot);
        if (mi == null) {
            session.armedDelete = -1;
            reopen(player, Screen.CONTROLS, -1);
            return;
        }
        int idx = indexOf(mi);
        if (event.isShiftClick()) {
            if (session.armedDelete == idx) {
                session.items.remove(idx);
                session.armedDelete = -1;
                player.sendMessage(Component.text("Button deleted."));
                reopen(player, Screen.PREVIEW, -1);
            } else {
                session.armedDelete = idx;
                session.armedDeleteAt = System.currentTimeMillis();
                reopen(player, Screen.PREVIEW, -1);
            }
            return;
        }
        session.armedDelete = -1;
        reopen(player, Screen.ITEM, idx);
    }

    private void clickItem(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        MenuConfig.MenuItem mi = editedItem();
        if (mi == null) {
            reopen(player, Screen.PREVIEW, -1);
            return;
        }
        int idx = index;
        int slot = event.getSlot();
        switch (slot) {
            case 0 -> {
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                        mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), !mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 1 -> {
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                        mi.offsetX(), mi.offsetY(), mi.slot(), !mi.opOnly(), mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 2 -> {
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), nextAnchor(mi.anchor()),
                        mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 3 -> {
                int ox = Math.max(-8, mi.offsetX() - 1);
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                        ox, mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 4 -> {
                int ox = Math.min(8, mi.offsetX() + 1);
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                        ox, mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 5 -> {
                int oy = Math.max(-(session.rows - 1), mi.offsetY() - 1);
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                        mi.offsetX(), oy, mi.slot(), mi.opOnly(), mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 6 -> {
                int oy = Math.min(session.rows - 1, mi.offsetY() + 1);
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                        mi.offsetX(), oy, mi.slot(), mi.opOnly(), mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 7 -> reopen(player, Screen.PICKSLOT, idx);
            case 8 -> {
                session.items.set(idx, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                        mi.offsetX(), mi.offsetY(), null, mi.opOnly(), mi.enabled()));
                reopen(player, Screen.ITEM, idx);
            }
            case 9 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.ITEM_MATERIAL, idx, null, Screen.ITEM),
                    "&eType a material name (e.g. DIAMOND_SWORD) in chat ('cancel' aborts):");
            case 10 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.ITEM_NAME, idx, null, Screen.ITEM),
                    "&eType the new name in chat (& codes work, 'cancel' aborts):");
            case 11 -> reopen(player, Screen.LORE, idx);
            case 12 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.ITEM_COMMAND, idx, null, Screen.ITEM),
                    "&eType the command as you'd type it (with or without /) in chat ('cancel' aborts):");
            case 13 -> {
                if (session.armedDelete == idx) {
                    session.items.remove(idx);
                    session.armedDelete = -1;
                    player.sendMessage(Component.text("Button deleted."));
                    reopen(player, Screen.PREVIEW, -1);
                } else {
                    session.armedDelete = idx;
                    session.armedDeleteAt = System.currentTimeMillis();
                    reopen(player, Screen.ITEM, idx);
                }
            }
            case 17 -> {
                session.armedDelete = -1;
                reopen(player, Screen.PREVIEW, -1);
            }
            default -> {}
        }
    }

    private void clickSettings(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        switch (event.getSlot()) {
            case 0 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.MENU_TITLE, -1, null, Screen.SETTINGS),
                    "&eType the new menu title in chat ('cancel' aborts):");
            case 1 -> {
                session.rows = Math.max(1, session.rows - 1);
                reopen(player, Screen.SETTINGS, -1);
            }
            case 2 -> {
                session.rows = Math.min(6, session.rows + 1);
                reopen(player, Screen.SETTINGS, -1);
            }
            case 3 -> {
                session.itemsPerRow = Math.max(1, session.itemsPerRow - 1);
                reopen(player, Screen.SETTINGS, -1);
            }
            case 4 -> {
                session.itemsPerRow = Math.min(9, session.itemsPerRow + 1);
                reopen(player, Screen.SETTINGS, -1);
            }
            case 5 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.MENU_SOUND, -1, null, Screen.SETTINGS),
                    "&eType the click sound (e.g. ENTITY_EXPERIENCE_ORB_PICKUP) in chat ('cancel' aborts):");
            case 6 -> {
                Sound sound = MenuConfig.parseSound(session.soundName);
                if (sound == null) {
                    player.sendMessage(Component.text("Current sound is unknown — type a new one first."));
                    return;
                }
                player.playSound(player.getLocation(), sound, 1, 1);
            }
            case 7 -> {
                session.fillerEnabled = !session.fillerEnabled;
                reopen(player, Screen.SETTINGS, -1);
            }
            case 8 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.FILLER_MATERIAL, -1, null, Screen.SETTINGS),
                    "&eType a material name (e.g. BLACK_STAINED_GLASS_PANE) in chat ('cancel' aborts):");
            case 17 -> reopen(player, Screen.CONTROLS, -1);
            default -> {}
        }
    }

    private void clickOpener(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        switch (event.getSlot()) {
            case 0 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.OPENER_MATERIAL, -1, null, Screen.OPENER),
                    "&eType a material name (e.g. CHEST) in chat ('cancel' aborts):");
            case 1 -> {
                session.openerSlot = Math.max(0, session.openerSlot - 1);
                reopen(player, Screen.OPENER, -1);
            }
            case 2 -> {
                session.openerSlot = Math.min(35, session.openerSlot + 1);
                reopen(player, Screen.OPENER, -1);
            }
            case 3 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.OPENER_NAME, -1, null, Screen.OPENER),
                    "&eType the new opener name in chat ('cancel' aborts):");
            case 4 -> reopen(player, Screen.LORE, -1);
            case 17 -> reopen(player, Screen.CONTROLS, -1);
            default -> {}
        }
    }

    private void clickPickSlot(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        MenuConfig.MenuItem mi = editedItem();
        if (mi == null) {
            reopen(player, Screen.PREVIEW, -1);
            return;
        }
        if (event.isShiftClick()) {
            session.items.set(index, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                    mi.offsetX(), mi.offsetY(), null, mi.opOnly(), mi.enabled()));
            reopen(player, Screen.ITEM, index);
            return;
        }
        int slot = event.getSlot();
        Map<Integer, MenuConfig.MenuItem> layout = workingLayout();
        MenuConfig.MenuItem occupant = layout.get(slot);
        if (occupant != null && occupant != mi) {
            player.sendMessage(Component.text("That slot is taken — pick a free one."));
            return;
        }
        session.items.set(index, with(mi, mi.name(), mi.lore(), mi.command(), mi.anchor(),
                mi.offsetX(), mi.offsetY(), slot, mi.opOnly(), mi.enabled()));
        reopen(player, Screen.ITEM, index);
    }

    private Inventory buildLore() {
        boolean forItem = index >= 0 && index < session.items.size();
        List<String> lore = forItem ? session.items.get(index).lore() : session.openerLore;
        Inventory inv = plugin.getServer().createInventory(this, 18, Component.text("Edit Lore"));
        int slot = 0;
        for (int i = 0; i < lore.size() && slot < 16; i++) {
            String line = lore.get(i);
            String shown = line.isBlank() ? "&7(blank line)" : line;
            inv.setItem(slot++, btn(Material.PAPER, "&f" + (i + 1) + ". " + shown,
                    "&7Click: edit this line", "&7Shift-click: delete"));
        }
        inv.setItem(16, btn(Material.EMERALD, "&aAdd Line", "&7Type in chat"));
        inv.setItem(17, btn(Material.ARROW, "&fBack"));
        chrome(inv);
        return inv;
    }

    private void clickLore(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        boolean forItem = index >= 0 && index < session.items.size();
        List<String> lore = forItem ? new ArrayList<>(session.items.get(index).lore())
                : new ArrayList<>(session.openerLore);
        int slot = event.getSlot();
        MenuEditorInv.Screen back = forItem ? Screen.ITEM : Screen.OPENER;
        int backIndex = forItem ? index : -1;
        if (slot == 17) {
            reopen(player, back, backIndex);
            return;
        }
        if (slot == 16) {
            MenuHandler.askChat(plugin, player,
                    new PendingInput(forItem ? PendingInput.Kind.ITEM_LORE : PendingInput.Kind.OPENER_LORE,
                            forItem ? index : -1, lore, Screen.LORE),
                    "&eType lore lines in chat ('done' finishes, 'clear' empties, 'cancel' aborts):");
            return;
        }
        if (slot < 0 || slot >= lore.size()) return;
        if (event.isShiftClick()) {
            List<String> next = new ArrayList<>(lore);
            next.remove(slot);
            writeLore(forItem, next);
            reopen(player, Screen.LORE, index);
            return;
        }
        MenuHandler.askChat(plugin, player,
                new PendingInput(forItem ? PendingInput.Kind.ITEM_LORE_LINE : PendingInput.Kind.OPENER_LORE_LINE,
                        forItem ? index : -1, null, Screen.LORE, slot),
                "&eType the replacement line in chat ('cancel' aborts):");
    }

    private void writeLore(boolean forItem, List<String> lore) {
        if (forItem) {
            MenuConfig.MenuItem mi = editedItem();
            if (mi == null) return;
            session.items.set(index, with(mi, mi.name(), lore, mi.command(), mi.anchor(),
                    mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
        } else {
            session.openerLore = lore;
        }
    }

    private void clickHidden(Player player, int slot) {
        if (slot == 26) {
            reopen(player, Screen.CONTROLS, -1);
            return;
        }
        int seen = -1;
        for (int i = 0; i < session.items.size(); i++) {
            if (session.items.get(i).enabled()) continue;
            if (++seen == slot) {
                reopen(player, Screen.ITEM, i);
                return;
            }
        }
    }

    private static String plainName(String name) {
        return name == null ? "(unnamed)" : name.replaceAll("&[0-9a-fk-or]", "");
    }

    /** Validates and writes the working copy to config.yml, then reloads. */
    public void saveAndClose(Player player) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < session.items.size(); i++) {
            MenuConfig.MenuItem mi = session.items.get(i);
            if (mi.enabled() && (mi.command() == null || mi.command().isBlank())) {
                missing.add("#" + (i + 1) + " '" + plainName(mi.name()) + "'");
            }
        }
        if (!missing.isEmpty()) {
            player.sendMessage(Component.text("These buttons need commands first: " + String.join(", ", missing)));
            reopen(player, Screen.CONTROLS, -1);
            return;
        }
        long nowModified = EditSession.configModifiedAt(plugin);
        if (nowModified != session.fileModifiedAt && !session.armedSave) {
            session.armedSave = true;
            player.sendMessage(Component.text("config.yml changed outside the editor — Save again to overwrite it."));
            reopen(player, Screen.CONTROLS, -1);
            return;
        }
        try {
            session.applyTo(plugin.getConfig());
            plugin.saveConfig();
            plugin.reloadMenuConfig();
            for (Player online : Bukkit.getOnlinePlayers()) {
                MenuHandler.giveMenuOpener(online);
            }
        } catch (Exception e) {
            player.sendMessage(Component.text("Save failed: " + e.getMessage()));
            return;
        }
        int count = session.items.size();
        EditSession.close(player.getUniqueId());
        EditSessionCache.storeAll(plugin);
        player.closeInventory();
        player.sendMessage(Component.text("Menu saved (" + count + " buttons) and reloaded."));
        GlovedPlugin.logInfo("Menu edited in-game by " + player.getName() + " (" + count + " buttons).");
    }

    private void clickControls(Player player, int slot) {
        switch (slot) {
            case 1 -> MenuHandler.askChat(plugin, player,
                    new PendingInput(PendingInput.Kind.ADD_BUTTON_MATERIAL, -1, null, Screen.CONTROLS),
                    "&eType a material name (e.g. DIAMOND_SWORD) in chat ('cancel' aborts):");
            case 2 -> reopen(player, Screen.SETTINGS, -1);
            case 3 -> reopen(player, Screen.OPENER, -1);
            case 4 -> {
                long hidden = session.items.stream().filter(mi -> !mi.enabled()).count();
                if (hidden == 0) {
                    player.sendMessage(Component.text("No hidden buttons."));
                    return;
                }
                reopen(player, Screen.HIDDEN, -1);
            }
            case 5 -> saveAndClose(player);
            case 6 -> {
                EditSession.close(player.getUniqueId());
                EditSessionCache.storeAll(plugin);
                player.closeInventory();
                player.sendMessage(Component.text("Discarded unsaved menu edits."));
            }
            case 7 -> reopen(player, Screen.PREVIEW, -1);
            default -> {}
        }
    }
}
