package dev.gloved.glovedplugin.handlers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import dev.gloved.glovedplugin.MenuConfig;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.editor.EditSession;
import dev.gloved.glovedplugin.editor.EditSessionCache;
import dev.gloved.glovedplugin.editor.MenuEditorInv;
import dev.gloved.glovedplugin.editor.PendingInput;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import static org.bukkit.Bukkit.getPluginManager;
import static dev.gloved.glovedplugin.GlovedPlugin.logInfo;

/**
 * Handles the menu GUI and its opener item.
 *
 * <p>All clicks and drags touching the menu are cancelled so items can never
 * be inserted into (and lost in) the ephemeral menu inventory. Opener handling
 * is strictly PDC-based and never deletes or overwrites player items.
 */
public class MenuHandler implements Listener {

    private final GlovedPlugin plugin;
    private final java.util.Map<java.util.UUID, Long> lastVaultAction = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Constructor for the MenuHandler class.
     * It registers the MenuHandler as a listener for events in the Bukkit server.
     *
     * @param plugin The instance of the GlovedPlugin.
     */
    public MenuHandler(GlovedPlugin plugin) {
        this.plugin = plugin;
        getPluginManager().registerEvents(this, plugin);
        logInfo("Registered MenuHandler!");
    }

    /**
     * True when the viewed top inventory is our menu. Holder check is primary;
     * title comparison is a fallback for inventories opened before a hot-reload
     * (whose holder was loaded by a previous classloader).
     */
    private boolean isMenuView(InventoryView view, MenuConfig config) {
        if (config == null) return false;
        if (view.getTopInventory().getHolder() instanceof MenuInv) return true;
        Component title = view.title();
        Component expected = config.titleComponent();
        return title != null && title.equals(expected);
    }

    /**
     * Single entry point for inventory clicks. Runs at LOWEST so no other
     * plugin can mutate the cursor before we snapshot it; anything we cancel
     * here stays cancelled for later listeners.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof MenuEditorInv editor) {
            handleEditorClick(event, editor, player);
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv vault) {
            handleSoulVaultClick(event, vault, player);
            return;
        }
        MenuConfig config = plugin.getMenuConfig();
        if (config == null) return;

        if (isMenuView(event.getView(), config)) {
            handleMenuClick(event, player, config);
            return;
        }
        handleOpenerClick(event, player, config);
    }

    private void handleSoulVaultClick(InventoryClickEvent event, dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv vault, Player player) {
        event.setCancelled(true);
        if (vault.getViewerId() != null && !vault.getViewerId().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= event.getView().getTopInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        var svc = plugin.getSoulService();
        if (svc == null) return;

        // nav/actions
        String nav = null;
        try {
            var meta = clicked.getItemMeta();
            if (meta != null) nav = meta.getPersistentDataContainer().get(dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv.navKey(plugin), org.bukkit.persistence.PersistentDataType.STRING);
        } catch (Throwable ignored) {}
        if (nav != null) {
            switch (nav) {
                case "prev" -> new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), Math.max(0, vault.getPage() - 1), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                case "next" -> new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage() + 1, vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                case "close" -> player.closeInventory();
                case "sort" -> {
                    String next = switch (vault.getSortMode().toLowerCase(java.util.Locale.ROOT)) {
                        case "default" -> "quality";
                        case "quality" -> "rarity";
                        case "rarity" -> "type";
                        case "type", "family" -> "recent";
                        default -> "default";
                    };
                    new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), 0, vault.getFilterType(), vault.getFilterRarity(), next).open(player);
                }
                case "rarity" -> {
                    dev.gloved.glovedplugin.summon.soul.SoulRarity cur = vault.getFilterRarity();
                    dev.gloved.glovedplugin.summon.soul.SoulRarity[] vals = dev.gloved.glovedplugin.summon.soul.SoulRarity.values();
                    dev.gloved.glovedplugin.summon.soul.SoulRarity next = null;
                    if (cur == null) next = vals[0];
                    else {
                        int i = (cur.ordinal() + 1) % (vals.length + 1);
                        if (i < vals.length) next = vals[i];
                    }
                    new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), 0, vault.getFilterType(), next, vault.getSortMode()).open(player);
                }
                case "filter" -> {
                    // simple cycle: ALL -> player's recent families in page/list not tracked; fall back to ALL/ZOMBIE/SKELETON/CREEPER/SPIDER common examples by cycling a small set
                    String[] presets = {null, "ZOMBIE", "SKELETON", "CREEPER", "SPIDER", "BEE", "IRON_GOLEM"};
                    String cur = vault.getFilterType();
                    int idx = 0;
                    for (int i = 0; i < presets.length; i++) if (java.util.Objects.equals(cur, presets[i]) || (cur != null && cur.equalsIgnoreCase(presets[i] == null ? "" : presets[i]))) { idx = i; break; }
                    String next = presets[(idx + 1) % presets.length];
                    new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), 0, next, vault.getFilterRarity(), vault.getSortMode()).open(player);
                }
                default -> {}
            }
            return;
        }

        // soul item
        String idStr = null;
        try {
            var meta = clicked.getItemMeta();
            if (meta != null) idStr = meta.getPersistentDataContainer().get(dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv.soulIdKey(plugin), org.bukkit.persistence.PersistentDataType.STRING);
        } catch (Throwable ignored) {}
        if (idStr == null) return;
        java.util.UUID soulId;
        try { soulId = java.util.UUID.fromString(idStr); } catch (Exception e) { return; }

        // debounce rapid clicks to prevent double salvage/extract races
        long now = System.currentTimeMillis();
        Long prev = lastVaultAction.get(player.getUniqueId());
        if (prev != null && now - prev < 200L) return;
        lastVaultAction.put(player.getUniqueId(), now);

        dev.gloved.glovedplugin.summon.soul.SoulInstance soul = svc.getSoul(soulId);
        if (soul == null || !player.getUniqueId().equals(soul.getOwnerId())) return;

        if (event.isShiftClick() && event.isLeftClick()) {
            boolean ok = svc.toggleFavorite(player.getUniqueId(), soul);
            if (ok) player.sendMessage(Component.text("Soul " + dev.gloved.glovedplugin.summon.soul.SoulService.shortId(soul) + (soul.isFavorite() ? " favorited." : " unfavorited.")));
            new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
            return;
        }
        if (event.isShiftClick() && event.isRightClick()) {
            boolean ok = svc.toggleLock(player.getUniqueId(), soul);
            if (ok) player.sendMessage(Component.text("Soul " + dev.gloved.glovedplugin.summon.soul.SoulService.shortId(soul) + (soul.isLocked() ? " locked." : " unlocked.")));
            new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
            return;
        }
        switch (event.getClick()) {
            case CONTROL_DROP -> {
                var res = svc.salvage(player.getUniqueId(), soul);
                if (res.ok()) player.sendMessage(Component.text("Salvaged " + dev.gloved.glovedplugin.summon.soul.SoulService.shortId(soul) + " for +" + res.essenceGained() + " essence."));
                else player.sendMessage(Component.text("Cannot salvage: " + res.reason()));
                new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                return;
            }
            case DROP -> {
                // Validate we can create the physical item BEFORE deleting the soul from the vault.
                // If item creation throws, we must not lose the soul.
                org.bukkit.inventory.ItemStack item;
                try {
                    item = dev.gloved.glovedplugin.summon.soul.SoulDropItem.create(plugin, soul);
                    if (item == null || item.getType() == org.bukkit.Material.AIR || !item.hasItemMeta()) {
                        player.sendMessage(Component.text("Cannot extract soul: invalid item."));
                        new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                        return;
                    }
                } catch (Throwable t) {
                    player.sendMessage(Component.text("Cannot extract soul: item creation failed."));
                    GlovedPlugin.logDebug("[Souls] Extract item creation failed for soul " + soul.getSoulId() + ": " + t);
                    new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                    return;
                }

                var res = svc.extractForItem(player.getUniqueId(), soul);
                if (!res.ok()) {
                    player.sendMessage(Component.text("Cannot extract soul: " + res.reason()));
                    new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                    return;
                }
                java.util.Map<Integer, org.bukkit.inventory.ItemStack> leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    for (org.bukkit.inventory.ItemStack it : leftover.values()) {
                        if (it == null || it.getType().isAir()) continue;
                        org.bukkit.entity.Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), it);
                        // tradeable: allow any player to pick up
                        try { dropped.setPickupDelay(10); } catch (Throwable ignored) {}
                        try { dropped.setGlowing(true); } catch (Throwable ignored) {}
                        try {
                            dropped.setCustomName(dev.gloved.glovedplugin.summon.soul.SoulDropItem.getDisplayName(soul));
                            dropped.setCustomNameVisible(true);
                        } catch (Throwable ignored) {}
                    }
                    player.sendMessage(Component.text("Extracted " + dev.gloved.glovedplugin.summon.soul.SoulService.describe(soul) + " as a soul item (dropped at your feet; inventory full)."));
                } else {
                    player.sendMessage(Component.text("Extracted " + dev.gloved.glovedplugin.summon.soul.SoulService.describe(soul) + " as a soul item."));
                }
                try { player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f); } catch (Throwable ignored) {}
                new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                return;
            }
            case RIGHT -> {
                for (String line : svc.inspect(soul).split("\n")) player.sendMessage(Component.text(line));
                return;
            }
            case LEFT -> {
                var st = svc.getState(player.getUniqueId());
                int slot = st != null ? st.selectedSlot() : 0;
                boolean ok = svc.equipSoul(player.getUniqueId(), slot, soul) && svc.selectSlot(player.getUniqueId(), slot);
                if (ok) {
                    player.sendMessage(Component.text("Equipped & selected " + dev.gloved.glovedplugin.summon.soul.SoulGenerator.describe(soul) + " in slot " + slot + "."));
                    dev.gloved.glovedplugin.summon.soul.SoulItem.refresh(player, plugin);
                } else {
                    player.sendMessage(Component.text("Failed to equip/select soul."));
                }
                new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), vault.getPage(), vault.getFilterType(), vault.getFilterRarity(), vault.getSortMode()).open(player);
                return;
            }
            default -> {}
        }
    }

    /**
     * Editor views are fully locked down like the menu; the editor object
     * itself handles the click. Permission is re-checked every interaction.
     */
    private void handleEditorClick(InventoryClickEvent event, MenuEditorInv editor, Player player) {
        event.setCancelled(true);
        if (!player.hasPermission("gloved.edit")) {
            EditSession.close(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(Component.text("You don't have permission to do that."));
            player.updateInventory();
            return;
        }
        editor.onClick(player, event);
    }

    /**
     * Moves the cursor stack into the player's inventory (first empty slot,
     * merging with partial stacks). Returns true when the cursor is empty and
     * everything is safe. Returns false when the cursor still holds items that
     * could not be safeguarded (inventory full) — the caller must then refuse
     * the window switch entirely: the items stay on the cursor, nothing opens.
     * A window switch is never allowed with an unsafeguarded cursor, so items
     * can neither be wiped by the switch nor dropped on the ground to despawn.
     * TEMPORARY DIAGNOSTIC (DIAG2) logging included.
     */
    public static boolean stashCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir()) {
            GlovedPlugin.logDebug("DIAG2 stash player=" + player.getName() + " to=none (cursor already empty)");
            return true;
        }
        ItemStack moving = cursor.clone();
        player.setItemOnCursor(null);
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(moving);
        if (leftover.isEmpty()) {
            GlovedPlugin.logDebug("DIAG2 stash player=" + player.getName() + " item=" + diagItem(moving) + " to=slot");
            return true;
        }
        // No room: put the exact stack back and refuse. Nothing was lost,
        // nothing was duplicated, the cursor is exactly as it was.
        player.setItemOnCursor(moving);
        player.updateInventory();
        GlovedPlugin.logDebug("DIAG2 stash player=" + player.getName() + " item=" + diagItem(moving) + " to=REFUSED_FULL");
        return false;
    }

    /**
     * Menu open: the top (menu) side is fully locked and runs commands.
     * The bottom (player) side stays usable so players can rearrange their own
     * items, except for the actions that could leak items into the ephemeral
     * menu or steal its display items: shift-clicks, double-click collect, and
     * anything touching the opener item.
     *
     * <p>A held stack is stashed into the inventory before the menu closes:
     * closing/opening windows with a full cursor is what loses it.
     */
    private void handleMenuClick(InventoryClickEvent event, Player player, MenuConfig config) {
        Inventory top = event.getView().getTopInventory();
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) return; // click outside the window: let vanilla handle it

        if (rawSlot < top.getSize()) {
            event.setCancelled(true);
            // Editors shortcut: shift-click a button to open it in the editor.
            if (event.isShiftClick() && player.hasPermission("gloved.edit")) {
                dev.gloved.glovedplugin.editor.MenuEditorInv.openEditorFor(plugin, player);
                return;
            }
            String command = config.commandForSlot(rawSlot, player.isOp());
            if (command == null || command.isBlank()) {
                player.updateInventory();
                return;
            }

            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir() && !MenuInv.isMenuOpener(cursor)) {
                if (!stashCursor(player)) {
                    player.sendActionBar(Component.text("Free an inventory slot first!"));
                    player.updateInventory();
                    return;
                }
            }

            Sound sound = config.sound();
            if (sound != null) player.playSound(player.getLocation(), sound, 1, 1);
            player.closeInventory();
            player.updateInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) player.performCommand(command);
            });
            return;
        }

        switch (event.getAction()) {
            case MOVE_TO_OTHER_INVENTORY, COLLECT_TO_CURSOR -> {
                event.setCancelled(true);
                player.updateInventory();
                return;
            }
            default -> {}
        }
        if (MenuInv.isMenuOpener(event.getCurrentItem()) || MenuInv.isMenuOpener(event.getCursor())) {
            event.setCancelled(true);
            player.updateInventory();
        }
        // Any other bottom click is the player's own items: allow it.
    }

    // TEMPORARY DIAGNOSTIC (DIAG2), remove once the cursor-loss repro is resolved.
    private static String diagItem(ItemStack item) {
        if (item == null) return "null";
        return item.getType() + "x" + item.getAmount() + (MenuInv.isMenuOpener(item) ? "[OPENER]" : "");
    }

    /**
     * True when the packet-claimed cursor matches live server state (type,
     * amount and opener tag). Anything else is client prediction fiction.
     */
    private static boolean cursorStateMatches(ItemStack eventCursor, ItemStack liveCursor) {
        boolean eventAir = eventCursor == null || eventCursor.getType().isAir();
        boolean liveAir = liveCursor == null || liveCursor.getType().isAir();
        if (eventAir != liveAir) return false;
        if (eventAir) return true;
        if (eventCursor.getType() != liveCursor.getType()) return false;
        if (eventCursor.getAmount() != liveCursor.getAmount()) return false;
        return MenuInv.isMenuOpener(eventCursor) == MenuInv.isMenuOpener(liveCursor);
    }

    /**
     * Opener item in the player inventory. Only PDC-tagged opener items react;
     * the cursor is only cleared when it actually holds a duplicate opener.
     * The menu opens next tick (never open inside the click event, which
     * desyncs the client), and without closing first: openInventory() replaces
     * the current view atomically.
     */
    private void handleOpenerClick(InventoryClickEvent event, Player player, MenuConfig config) {
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;
        if (clicked != event.getView().getBottomInventory()) return;

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        // Double-click collect runs after dispatch and would vacuum an opener
        // into a matching cursor stack (merging can also strip its identity),
        // so block it upfront whenever an opener is carried.
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR
                && cursor != null && !cursor.getType().isAir() && config != null
                && cursor.getType() == config.openerMaterial() && hasOpener(player)) {
            event.setCancelled(true);
            player.updateInventory();
            return;
        }
        boolean currentOpener = MenuInv.isMenuOpener(current);
        boolean cursorOpener = MenuInv.isMenuOpener(cursor);
        if (!currentOpener && !cursorOpener) return;

        // The client predicts merges on cancelled clicks and then echoes that
        // fiction back in later click packets (tagged cursor the server never
        // held). Opening windows on fiction amplifies the desync into working
        // duplicate buttons, so swallow it: cancel, resync, no menu, no sound.
        if (!cursorStateMatches(cursor, player.getItemOnCursor())) {
            event.setCancelled(true);
            dev.gloved.glovedplugin.DebugLog.log("INFO",
                    "Opener desync-click swallowed player=" + player.getName() + " action=" + event.getAction()
                            + " cursor=" + diagItem(cursor) + " serverCursor=" + diagItem(player.getItemOnCursor()));
            player.updateInventory();
            return;
        }

        switch (event.getAction()) {
            case DROP_ONE_SLOT, DROP_ALL_SLOT, DROP_ONE_CURSOR, DROP_ALL_CURSOR,
                 HOTBAR_SWAP, HOTBAR_MOVE_AND_READD, CLONE_STACK, COLLECT_TO_CURSOR,
                 MOVE_TO_OTHER_INVENTORY, UNKNOWN -> {
                // Non-pickup actions must never open the menu: switching windows
                // right after a cancelled drop desyncs the client into ghost
                // duplicate openers that can't be moved or dropped.
                event.setCancelled(true);
                int removed = dedupeOpeners(player);
                dev.gloved.glovedplugin.DebugLog.log("INFO",
                        "Opener drop-click player=" + player.getName() + " action=" + event.getAction()
                                + " current=" + diagItem(current) + " cursor=" + diagItem(cursor)
                                + " dupesRemoved=" + removed);
                player.updateInventory();
                return;
            }
            default -> {}
        }

        // TEMPORARY DIAGNOSTIC (DIAG2): storedInInv distinguishes a ghost
        // (stack exists nowhere server-side) from a desync (stack is in storage).
        int stored = 0;
        if (cursor != null && !cursor.getType().isAir()) {
            for (ItemStack it : player.getInventory().getContents()) {
                if (it != null && it.getType() == cursor.getType()) stored += it.getAmount();
            }
        }
        GlovedPlugin.logDebug("DIAG2 click handler=" + System.identityHashCode(this)
                + " player=" + player.getName() + " action=" + event.getAction()
                + " current=" + diagItem(current) + " cursor=" + diagItem(cursor)
                + " serverCursor=" + diagItem(player.getItemOnCursor())
                + " storedInInv=" + stored);
        // TEMPORARY DIAGNOSTIC (DIAG2): event.getCursor() and
        // player.getItemOnCursor() both read live container state. If they
        // disagree, the window was switched mid-dispatch by an earlier
        // listener — compare the views to catch who did it.
        Inventory eventTop = event.getView().getTopInventory();
        InventoryView curView = player.getOpenInventory();
        Inventory curTop = curView.getTopInventory();
        GlovedPlugin.logDebug("DIAG2 views player=" + player.getName()
                + " sameTop=" + (eventTop == curTop)
                + " eventTop=" + eventTop.getType()
                + " curTop=" + curTop.getType()
                + " curTitle=" + curView.title());

        event.setCancelled(true);
        if (cursorOpener) {
            // Player was holding a duplicate opener: remove it, keep anything else.
            player.setItemOnCursor(null);
        } else {
            // Stash a real held stack before the window switch below.
            // Full inventory: refuse instead, menu stays closed, items untouched.
            if (!stashCursor(player)) {
                player.sendActionBar(Component.text("Free an inventory slot first!"));
                player.updateInventory();
                return;
            }
        }
        // NOTE: no inventory rewrite here (no giveMenuOpener). Replacing the
        // clicked slot mid-click confuses the client's click prediction and
        // blanks the cursor display until the next window change. The opener
        // in the slot is already valid; orphans are purged by /gloved reload,
        // join, respawn and enable instead (no click in flight there).
        player.updateInventory();
        Sound sound = config.sound();
        if (sound != null) player.playSound(player.getLocation(), sound, 1, 1);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            // The click may have been un-cancelled or the inventory changed
            // after we handled it: only open if the opener is still in place,
            // otherwise leave the player's inventory exactly as it is.
            MenuConfig liveConfig = plugin.getMenuConfig();
            ItemStack openerSlot = liveConfig != null
                    ? player.getInventory().getItem(liveConfig.openerSlot()) : null;
            if (!MenuInv.isMenuOpener(openerSlot)) {
                // TEMPORARY DIAGNOSTIC (DIAG2)
                GlovedPlugin.logDebug("DIAG2 skipopen player=" + player.getName()
                        + " openerGoneFromSlot cursor=" + diagItem(player.getItemOnCursor()));
                player.updateInventory();
                return;
            }
            new MenuInv(plugin).openMenu(player);
            // TEMPORARY DIAGNOSTIC (DIAG2)
            GlovedPlugin.logDebug("DIAG2 postopen player=" + player.getName()
                    + " cursor=" + diagItem(player.getItemOnCursor()));
            player.updateInventory();
        });
    }

    /**
     * TEMPORARY DIAGNOSTIC (DIAG2): runs after every other listener and reports
     * the cursor state they left behind. If the cursor is full at our main
     * handler but empty here, a later listener wiped it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClickTail(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!MenuInv.isMenuOpener(event.getCurrentItem()) && !MenuInv.isMenuOpener(event.getCursor())) return;
        GlovedPlugin.logDebug("DIAG2 tail handler=" + System.identityHashCode(this)
                + " player=" + player.getName() + " cancelled=" + event.isCancelled()
                + " serverCursor=" + diagItem(player.getItemOnCursor()));
    }

    /**
     * Drags touching the menu top are cancelled so players can't drag stacks
     * into the ephemeral menu and lose them. Drags over the opener slot are
     * also cancelled (in and out of the menu) so the opener can't be
     * overwritten; other drags are the player's own items and are allowed.
     */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryView view = event.getView();
        int topSize = view.getTopInventory().getSize();
        if (view.getTopInventory().getHolder() instanceof MenuEditorInv
                || view.getTopInventory().getHolder() instanceof dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 0 && rawSlot < topSize) {
                    event.setCancelled(true);
                    player.updateInventory();
                    return;
                }
            }
            return;
        }
        MenuConfig config = plugin.getMenuConfig();
        boolean menuView = isMenuView(view, config);
        for (int rawSlot : event.getRawSlots()) {
            if (menuView && rawSlot >= 0 && rawSlot < topSize) {
                event.setCancelled(true);
                player.updateInventory();
                return;
            }
        }
        if (config != null && touchesOpenerSlot(event, view, config)) {
            event.setCancelled(true);
            player.updateInventory();
        }
    }

    private boolean touchesOpenerSlot(InventoryDragEvent event, InventoryView view, MenuConfig config) {
        int openerRaw = view.getTopInventory().getSize() + config.openerSlot();
        if (!event.getRawSlots().contains(openerRaw)) return false;
        return MenuInv.isMenuOpener(view.getBottomInventory().getItem(config.openerSlot()));
    }

    /**
     * The opener is a UI element, not a real chest: prevent dropping it.
     */
    @EventHandler
    public void onOpenerDrop(PlayerDropItemEvent event) {
        if (MenuInv.isMenuOpener(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            int removed = dedupeOpeners(event.getPlayer());
            dev.gloved.glovedplugin.DebugLog.log("INFO",
                    "Opener drop-event player=" + event.getPlayer().getName() + " dupesRemoved=" + removed);
            // Re-set the held slot explicitly: updateInventory alone often
            // fails to restore the client's held-item render after a
            // cancelled drop, leaving ghost duplicates behind.
            try {
                Player p = event.getPlayer();
                int held = p.getInventory().getHeldItemSlot();
                ItemStack inHand = p.getInventory().getItem(held);
                if (MenuInv.isMenuOpener(inHand)) {
                    p.getInventory().setItem(held, inHand);
                }
            } catch (Exception ignored) {}
            event.getPlayer().updateInventory();
        }
    }

    /**
     * The F-key hand swap bypasses inventory clicks: keep the opener out of
     * the offhand, where the give/purge pass can't see it and the next give
     * would mint a duplicate.
     */
    @EventHandler
    public void onOpenerSwapHand(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (MenuInv.isMenuOpener(event.getMainHandItem()) || MenuInv.isMenuOpener(event.getOffHandItem())) {
            event.setCancelled(true);
            event.getPlayer().updateInventory();
        }
    }

    /**
     * Keep the opener out of death drops (prevents farming free chests and
     * scattering UI items); it is re-given on respawn.
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(MenuInv::isMenuOpener);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        giveMenuOpener(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        // Keep the memory entry (instant resume on relog); the file covers restarts.
        PendingInput.cancel(event.getPlayer().getUniqueId());
        EditSessionCache.storeAll(plugin);
    }

    /**
     * Reminds players that closing the editor does not save or discard:
     * the session resumes on next open. Navigation between editor screens
     * re-opens windows (OPEN_NEW) and stays silent.
     */
    @EventHandler
    public void onEditorClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getReason() == org.bukkit.event.inventory.InventoryCloseEvent.Reason.OPEN_NEW) return;
        if (!(event.getInventory().getHolder() instanceof MenuEditorInv)) return;
        EditSessionCache.storeAll(plugin);
        if (event.getReason() == org.bukkit.event.inventory.InventoryCloseEvent.Reason.OPEN_NEW) return;
        EditSession session = EditSession.get(player.getUniqueId());
        if (session == null || !session.isDirty()) return;
        player.sendMessage(Component.text("Unsaved menu edits kept — reopen /gloved edit to continue, Save to apply."));
    }

    /**
     * Registers a chat-text request from the menu editor and tells the player
     * what to type. The next chat message is consumed (never broadcast) and
     * applied on the main thread; silence for 30 seconds expires it.
     */
    public static void askChat(GlovedPlugin plugin, Player player, PendingInput pending, String prompt) {
        PendingInput.put(player.getUniqueId(), pending);
        // TEMPORARY DIAGNOSTIC (DIAG3)
        GlovedPlugin.logDebug("DIAG3 ask player=" + player.getName() + " kind=" + pending.kind);
        player.sendMessage(GlovedPlugin.makeComponent(prompt));
        player.sendMessage(Component.text("(Type 'cancel' to go back. A leading / is trimmed for commands.)"));
        scheduleTimeout(plugin, player, pending);
    }

    private static void scheduleTimeout(GlovedPlugin plugin, Player player, PendingInput pending) {
        pending.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (PendingInput.removeIfSame(player.getUniqueId(), pending) && player.isOnline()) {
                player.sendMessage(Component.text("Edit timed out — nothing changed."));
            }
        }, 20L * 30);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditorChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = PendingInput.take(player.getUniqueId());
        if (pending == null) return;
        // TEMPORARY DIAGNOSTIC (DIAG3)
        GlovedPlugin.logDebug("DIAG3 chat player=" + player.getName() + " kind=" + pending.kind);
        // LOWEST + cancelled: edit input never reaches chat plugins or Discord.
        event.setCancelled(true);
        String text = LegacyComponentSerializer.legacySection().serialize(event.message()).replace('§', '&').strip();
        Bukkit.getScheduler().runTask(plugin, () -> applyChatInput(player, pending, text, text));
    }

    /**
     * Commands typed while editing never execute: a message starting with "/"
     * never reaches the chat listener, so without this, "/stop" typed mid-edit
     * would stop the server. Both the verbatim text and the once-trimmed form
     * are passed down: display fields keep every slash exactly as typed, while
     * commands trim one leading slash (so WorldEdit's //wand works) and
     * control words match either form ("/cancel" aborts, "//cancel" is text).
     * Only active while this player has pending input.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditorCommandPreprocess(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        PendingInput pending = PendingInput.take(player.getUniqueId());
        if (pending == null) return;
        event.setCancelled(true);
        String message = event.getMessage();
        String verbatim = message.strip();
        String stripped = (verbatim.startsWith("/") ? verbatim.substring(1) : verbatim).strip();
        Bukkit.getScheduler().runTask(plugin, () -> applyChatInput(player, pending, verbatim, stripped));
    }

    /**
     * Control words (cancel/done/clear) match the text with or without its
     * leading slash, so "/cancel" aborts while "//cancel" stays literal text.
     */
    public static boolean isControlWord(String verbatim, String stripped, String word) {
        return word.equalsIgnoreCase(verbatim) || word.equalsIgnoreCase(stripped);
    }

    private void applyChatInput(Player player, PendingInput pending, String verbatim, String stripped) {
        if (!player.isOnline()) return;
        if (!player.hasPermission("gloved.edit")) {
            EditSession.close(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(Component.text("You don't have permission to do that."));
            return;
        }
        EditSession session = EditSession.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(Component.text("Edit session expired — open /gloved edit again."));
            return;
        }
        boolean cancelled = isControlWord(verbatim, stripped, "cancel");
        switch (pending.kind) {
            case ITEM_NAME -> {
                if (cancelled || verbatim.isBlank()) {
                    reopenEditor(player, session, MenuEditorInv.Screen.ITEM, pending.itemIndex);
                    return;
                }
                replaceItem(session, pending.itemIndex,
                        withName(session, pending.itemIndex, verbatim));
                reopenEditor(player, session, MenuEditorInv.Screen.ITEM, pending.itemIndex);
            }
            case ITEM_COMMAND -> {
                if (cancelled || stripped.isBlank()) {
                    if (!cancelled) player.sendMessage(Component.text("Commands can't be blank."));
                    reopenEditor(player, session, MenuEditorInv.Screen.ITEM, pending.itemIndex);
                    return;
                }
                MenuConfig.MenuItem mi = itemAt(session, pending.itemIndex);
                if (mi == null) {
                    reopenEditor(player, session, MenuEditorInv.Screen.PREVIEW, -1);
                    return;
                }
                replaceItem(session, pending.itemIndex,
                        new MenuConfig.MenuItem(mi.material(), mi.name(), new java.util.ArrayList<>(mi.lore()),
                                stripped, mi.anchor(), mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
                reopenEditor(player, session, MenuEditorInv.Screen.ITEM, pending.itemIndex);
            }
            case ITEM_MATERIAL -> {
                if (cancelled) {
                    reopenEditor(player, session, MenuEditorInv.Screen.ITEM, pending.itemIndex);
                    return;
                }
                Material mat = Material.matchMaterial(stripped);
                if (mat == null || mat.isAir()) {
                    player.sendMessage(Component.text("Unknown material — try DIAMOND_SWORD."));
                    reopenEditor(player, session, MenuEditorInv.Screen.ITEM, pending.itemIndex);
                    return;
                }
                MenuConfig.MenuItem mi = itemAt(session, pending.itemIndex);
                if (mi == null) {
                    reopenEditor(player, session, MenuEditorInv.Screen.PREVIEW, -1);
                    return;
                }
                replaceItem(session, pending.itemIndex, MenuEditorInv.withMaterial(mi, mat));
                reopenEditor(player, session, MenuEditorInv.Screen.ITEM, pending.itemIndex);
            }
            case ADD_BUTTON_MATERIAL -> {
                if (cancelled) {
                    reopenEditor(player, session, MenuEditorInv.Screen.CONTROLS, -1);
                    return;
                }
                Material mat = Material.matchMaterial(stripped);
                if (mat == null || mat.isAir()) {
                    player.sendMessage(Component.text("Unknown material — try DIAMOND_SWORD."));
                    reopenEditor(player, session, MenuEditorInv.Screen.CONTROLS, -1);
                    return;
                }
                session.items.add(new MenuConfig.MenuItem(mat, "&f" + mat.name(), new java.util.ArrayList<>(),
                        "", null, 0, 0, null, false, true));
                // TEMPORARY DIAGNOSTIC (DIAG3)
                GlovedPlugin.logDebug("DIAG3 created player=" + player.getName() + " material=" + mat.name()
                        + " index=" + (session.items.size() - 1));
                reopenEditor(player, session, MenuEditorInv.Screen.ITEM, session.items.size() - 1);
            }
            case ITEM_LORE -> applyLoreInput(player, session, pending, verbatim, stripped, true);
            case ITEM_LORE_LINE, OPENER_LORE_LINE -> {
                boolean forItem = pending.kind == PendingInput.Kind.ITEM_LORE_LINE;
                if (cancelled || verbatim.isBlank()) {
                    if (!cancelled) player.sendMessage(Component.text("Lines can't be blank."));
                    reopenEditor(player, session, MenuEditorInv.Screen.LORE,
                            forItem ? pending.itemIndex : -1);
                    return;
                }
                java.util.List<String> current = forItem
                        ? (itemAt(session, pending.itemIndex) == null ? null
                                : new java.util.ArrayList<>(itemAt(session, pending.itemIndex).lore()))
                        : new java.util.ArrayList<>(session.openerLore);
                if (current == null || pending.lineIndex < 0 || pending.lineIndex >= current.size()) {
                    reopenEditor(player, session, MenuEditorInv.Screen.LORE,
                            forItem ? pending.itemIndex : -1);
                    return;
                }
                current.set(pending.lineIndex, verbatim);
                if (forItem) {
                    MenuConfig.MenuItem mi = itemAt(session, pending.itemIndex);
                    if (mi != null) replaceItem(session, pending.itemIndex,
                            new MenuConfig.MenuItem(mi.material(), mi.name(), current, mi.command(),
                                    mi.anchor(), mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
                } else {
                    session.openerLore = current;
                }
                reopenEditor(player, session, MenuEditorInv.Screen.LORE,
                        forItem ? pending.itemIndex : -1);
            }
            case MENU_TITLE -> {
                if (cancelled || verbatim.isBlank()) {
                    reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
                    return;
                }
                session.title = verbatim;
                reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
            }
            case MENU_SOUND -> {
                if (cancelled) {
                    reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
                    return;
                }
                Sound sound = MenuConfig.parseSound(stripped);
                if (sound == null) {
                    player.sendMessage(Component.text("Unknown sound — try ENTITY_EXPERIENCE_ORB_PICKUP."));
                    reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
                    return;
                }
                session.soundName = stripped;
                player.playSound(player.getLocation(), sound, 1, 1);
                reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
            }
            case OPENER_NAME -> {
                if (cancelled || verbatim.isBlank()) {
                    reopenEditor(player, session, MenuEditorInv.Screen.OPENER, -1);
                    return;
                }
                session.openerName = verbatim;
                reopenEditor(player, session, MenuEditorInv.Screen.OPENER, -1);
            }
            case FILLER_MATERIAL -> {
                if (cancelled) {
                    reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
                    return;
                }
                Material mat = Material.matchMaterial(stripped);
                if (mat == null || mat.isAir()) {
                    player.sendMessage(Component.text("Unknown material — try BLACK_STAINED_GLASS_PANE."));
                    reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
                    return;
                }
                session.fillerMaterial = mat;
                reopenEditor(player, session, MenuEditorInv.Screen.SETTINGS, -1);
            }
            case OPENER_MATERIAL -> {
                if (cancelled) {
                    reopenEditor(player, session, MenuEditorInv.Screen.OPENER, -1);
                    return;
                }
                Material mat = Material.matchMaterial(stripped);
                if (mat == null || mat.isAir()) {
                    player.sendMessage(Component.text("Unknown material — try CHEST."));
                    reopenEditor(player, session, MenuEditorInv.Screen.OPENER, -1);
                    return;
                }
                session.openerMaterial = mat;
                reopenEditor(player, session, MenuEditorInv.Screen.OPENER, -1);
            }
            case OPENER_LORE -> applyLoreInput(player, session, pending, verbatim, stripped, false);
        }
    }

    private MenuConfig.MenuItem itemAt(EditSession session, int index) {
        if (index < 0 || index >= session.items.size()) return null;
        return session.items.get(index);
    }

    private void replaceItem(EditSession session, int index, MenuConfig.MenuItem replacement) {
        if (replacement == null || index < 0 || index >= session.items.size()) return;
        session.items.set(index, replacement);
    }

    private MenuConfig.MenuItem withName(EditSession session, int index, String name) {
        MenuConfig.MenuItem mi = itemAt(session, index);
        if (mi == null) return null;
        return new MenuConfig.MenuItem(mi.material(), name, new java.util.ArrayList<>(mi.lore()),
                mi.command(), mi.anchor(), mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled());
    }

    private void applyLoreInput(Player player, EditSession session, PendingInput pending,
                                String verbatim, String stripped, boolean item) {
        if (isControlWord(verbatim, stripped, "cancel")) {
            if (pending.originalLore != null) {
                if (item) {
                    MenuConfig.MenuItem mi = itemAt(session, pending.itemIndex);
                    if (mi != null) replaceItem(session, pending.itemIndex,
                            new MenuConfig.MenuItem(mi.material(), mi.name(),
                                    new java.util.ArrayList<>(pending.originalLore), mi.command(),
                                    mi.anchor(), mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
                } else {
                    session.openerLore = new java.util.ArrayList<>(pending.originalLore);
                }
            }
            reopenEditor(player, session, item ? MenuEditorInv.Screen.ITEM : MenuEditorInv.Screen.OPENER,
                    item ? pending.itemIndex : -1);
            return;
        }
        java.util.List<String> lore = item
                ? new java.util.ArrayList<>(itemAt(session, pending.itemIndex) == null
                        ? java.util.List.of() : itemAt(session, pending.itemIndex).lore())
                : new java.util.ArrayList<>(session.openerLore);
        if (isControlWord(verbatim, stripped, "done")) {
            reopenEditor(player, session, item ? MenuEditorInv.Screen.ITEM : MenuEditorInv.Screen.OPENER,
                    item ? pending.itemIndex : -1);
            return;
        }
        if (isControlWord(verbatim, stripped, "clear")) {
            lore.clear();
        } else {
            if (lore.size() >= 16) {
                player.sendMessage(Component.text("Lore is full (16 lines) — 'done' to finish."));
            } else {
                lore.add(verbatim);
                player.sendActionBar(Component.text("Line " + lore.size() + " added ('done' finishes)."));
            }
        }
        if (item) {
            MenuConfig.MenuItem mi = itemAt(session, pending.itemIndex);
            if (mi != null) replaceItem(session, pending.itemIndex,
                    new MenuConfig.MenuItem(mi.material(), mi.name(), lore, mi.command(),
                            mi.anchor(), mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
        } else {
            session.openerLore = lore;
        }
        PendingInput.put(player.getUniqueId(), new PendingInput(pending.kind, pending.itemIndex,
                pending.originalLore, pending.returnTo));
        armTimeout(player);
    }

    private void armTimeout(Player player) {
        // Re-arms the just-registered pending input (lore multi-line flow).
        PendingInput pending = PendingInput.get(player.getUniqueId());
        if (pending == null) return;
        scheduleTimeout(plugin, player, pending);
    }

    private void reopenEditor(Player player, EditSession session, MenuEditorInv.Screen screen, int index) {
        new MenuEditorInv(plugin, session, screen, index).open(player);
    }

    /**
     * True when the player carries any opener stack (storage, armor, offhand
     * or cursor).
     */
    private static boolean hasOpener(Player player) {
        for (ItemStack it : player.getInventory().getContents()) {
            if (MenuInv.isMenuOpener(it)) return true;
        }
        for (ItemStack it : player.getInventory().getArmorContents()) {
            if (MenuInv.isMenuOpener(it)) return true;
        }
        return MenuInv.isMenuOpener(player.getInventory().getItemInOffHand())
                || MenuInv.isMenuOpener(player.getItemOnCursor());
    }

    /**
     * Collapses opener duplicates down to a single stack: keeps the first
     * PDC-tagged opener found across storage, armor, offhand and cursor,
     * clears the rest. Returns how many stacks were removed.
     */
    public static int dedupeOpeners(Player player) {
        int removed = 0;
        boolean kept = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (MenuInv.isMenuOpener(contents[i])) {
                if (!kept) {
                    kept = true;
                    if (contents[i].getAmount() != 1) {
                        contents[i].setAmount(1);
                        player.getInventory().setItem(i, contents[i]);
                    }
                } else {
                    player.getInventory().setItem(i, null);
                    removed++;
                }
            }
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean armorTouched = false;
        for (int i = 0; i < armor.length; i++) {
            if (MenuInv.isMenuOpener(armor[i])) {
                if (!kept) {
                    kept = true;
                    if (armor[i].getAmount() != 1) {
                        armor[i].setAmount(1);
                        armorTouched = true;
                    }
                } else {
                    armor[i] = null;
                    armorTouched = true;
                    removed++;
                }
            }
        }
        if (armorTouched) player.getInventory().setArmorContents(armor);
        if (MenuInv.isMenuOpener(player.getInventory().getItemInOffHand())) {
            if (!kept) {
                kept = true;
                if (player.getInventory().getItemInOffHand().getAmount() != 1) {
                    player.getInventory().getItemInOffHand().setAmount(1);
                }
            } else {
                player.getInventory().setItemInOffHand(null);
                removed++;
            }
        }
        if (MenuInv.isMenuOpener(player.getItemOnCursor())) {
            if (!kept) {
                kept = true;
                if (player.getItemOnCursor().getAmount() != 1) {
                    player.getItemOnCursor().setAmount(1);
                }
            } else {
                player.setItemOnCursor(null);
                removed++;
            }
        }
        if (removed > 0) player.updateInventory();
        return removed;
    }

    /**
     * Places the opener without ever overwriting player loot. If the configured
     * slot holds a real item, the opener goes to the first free slot instead.
     */
    public static void giveMenuOpener(Player player) {
        GlovedPlugin plugin = JavaPlugin.getPlugin(GlovedPlugin.class);
        MenuConfig config = plugin.getMenuConfig();
        int slot = config != null ? config.openerSlot() : 17;
        // Purge stale openers anywhere on the player (storage, armor, offhand;
        // e.g. the slot was changed and the old one was orphaned). Only
        // PDC-tagged openers are touched.
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (MenuInv.isMenuOpener(contents[i])) player.getInventory().setItem(i, null);
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean armorTouched = false;
        for (int i = 0; i < armor.length; i++) {
            if (MenuInv.isMenuOpener(armor[i])) {
                armor[i] = null;
                armorTouched = true;
            }
        }
        if (armorTouched) player.getInventory().setArmorContents(armor);
        if (MenuInv.isMenuOpener(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
        if (MenuInv.isMenuOpener(player.getItemOnCursor())) player.setItemOnCursor(null);
        ItemStack opener = MenuInv.createMenuOpener();
        ItemStack existing = player.getInventory().getItem(slot);
        if (existing == null || existing.getType().isAir() || MenuInv.isMenuOpener(existing)) {
            player.getInventory().setItem(slot, opener);
        } else if (player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(opener);
        } else {
            return;
        }
        player.updateInventory();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        giveMenuOpener(event.getPlayer());
        logInfo("Menu Item added to " + event.getPlayer().getName() + " inventory.");
    }

}
