package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableService;
import dev.gloved.glovedplugin.summon.soul.table.item.CondensedSoulEssenceItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Condense process: convert Essence ⇄ Condensed Soul Essence in batches. */
public final class SoulTableCondenseInv implements SoulTableScreen {

    private static final int SIZE = 54;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final SoulTableConfig FALLBACK = new SoulTableConfig();

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final Inventory inventory;

    public SoulTableCondenseInv(GlovedPlugin plugin, Player player, SoulTableRow table) {
        this.plugin = plugin;
        this.viewerId = player.getUniqueId();
        this.tableId = table != null ? table.tableId() : null;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Soul Table - Condense", NamedTextColor.LIGHT_PURPLE));
        refresh();
    }

    @Override public UUID getViewerId() { return viewerId; }
    @Override public UUID getTableId() { return tableId; }
    @Override public @NotNull Inventory getInventory() { return inventory; }

    @Override
    public void open(Player player) {
        if (player == null) return;
        refresh();
        player.openInventory(inventory);
    }

    private SoulTableConfig cfg() {
        SoulService svc = plugin.getSoulService();
        SoulTableConfig c = svc != null ? svc.tableConfig() : null;
        return c != null ? c : FALLBACK;
    }

    private SoulTableService tableService() { return plugin.getSoulTableService(); }

    @Override
    public void refresh() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, SoulTableUiItems.filler());

        SoulService svc = plugin.getSoulService();
        SoulTableConfig cfg = cfg();
        Player player = Bukkit.getPlayer(viewerId);
        int essence = svc != null ? svc.getEssence(viewerId) : 0;
        int condensed = player != null ? CondensedSoulEssenceItem.countInInventory(plugin, player) : 0;
        int rate = cfg.condenseRate();
        boolean reversible = cfg.condenseReversible();
        boolean enabled = cfg.isCondenseEnabled();

        inventory.setItem(0, SoulTableUiItems.backToHubItem(plugin));
        inventory.setItem(8, SoulTableUiItems.actionItem(plugin, Material.BOOK, "§b§lSoul Guide",
                SoulTableUiKeys.ACTION_GUIDE, List.of("§7Learn about Condense and Infusion.",
                        "§7See how to convert Essence and use Catalysts.",
                        "", "§eClick to open")));

        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7Rate: §b" + rate + " Essence §7= §b1§7 Condensed");
        infoLore.add("§7Reversible: " + (reversible ? "§aYes" : "§cNo"));
        infoLore.add("§7Condensed owned: §f" + condensed);
        infoLore.add("");
        infoLore.add(enabled ? "§aCondensing enabled." : "§cCondensing disabled by config.");
        inventory.setItem(13, SoulTableUiItems.infoItem(Material.AMETHYST_BLOCK,
                "§b§lCondense Essence", infoLore));

        inventory.setItem(49, SoulTableUiItems.infoItem(Material.AMETHYST_SHARD,
                "§b§lSoul Essence: §f" + essence,
                List.of("§7Use Condense to package Essence into",
                        "§7Condensed Soul Essence for catalysts.")));
        inventory.setItem(53, SoulTableUiItems.actionItem(plugin, Material.BARRIER, "§cClose",
                SoulTableUiKeys.ACTION_CLOSE, List.of("§7Close the Soul Table.")));

        // Condense buttons (essence -> condensed)
        int maxCondense = rate > 0 ? Math.max(0, essence / rate) : 0;
        inventory.setItem(20, batchAction(Material.LIME_DYE, "§aCondense x1", "condense:1",
                1, maxCondense, rate, true, enabled));
        inventory.setItem(21, batchAction(Material.LIME_DYE, "§aCondense x16", "condense:16",
                16, maxCondense, rate, true, enabled));
        inventory.setItem(22, batchAction(Material.LIME_DYE, "§aCondense x64", "condense:64",
                64, maxCondense, rate, true, enabled));
        inventory.setItem(23, batchAction(Material.EMERALD, "§aCondense Max", "condense:max",
                maxCondense, maxCondense, rate, true, enabled));

        // Decondense buttons (condensed -> essence)
        boolean canDe = enabled && reversible;
        inventory.setItem(29, batchAction(Material.ORANGE_DYE, "§eDecondense x1", "decondense:1",
                1, condensed, rate, false, canDe));
        inventory.setItem(30, batchAction(Material.ORANGE_DYE, "§eDecondense x16", "decondense:16",
                16, condensed, rate, false, canDe));
        inventory.setItem(31, batchAction(Material.ORANGE_DYE, "§eDecondense x64", "decondense:64",
                64, condensed, rate, false, canDe));
        inventory.setItem(32, batchAction(Material.GOLD_INGOT, "§eDecondense Max", "decondense:max",
                condensed, condensed, rate, false, canDe));

        if (!reversible) {
            inventory.setItem(40, SoulTableUiItems.infoItem(Material.GRAY_STAINED_GLASS_PANE,
                    "§7Decondense Unavailable", List.of("§7Conversion is not reversible",
                            "§7in the current config.")));
        }
    }

    private ItemStack batchAction(Material material, String name, String action, int requested, int available, int rate,
                                  boolean condenseMode, boolean enabled) {
        int units = requested;
        if ("condense:max".equals(action) || "decondense:max".equals(action)) units = available;
        units = Math.max(0, units);
        boolean can = enabled && units > 0 && units <= Math.max(0, available);
        if (!enabled) can = false;

        List<String> lore = new ArrayList<>();
        if (condenseMode) {
            lore.add("§7Convert §b" + (units * rate) + " Essence §7→ §b" + units + "§7 Condensed");
        } else {
            lore.add("§7Convert §b" + units + " Condensed §7→ §b" + (units * rate) + " Essence");
        }
        lore.add("§7Available: §f" + Math.max(0, available));
        if (!enabled) lore.add("§cUnavailable");
        else if (units <= 0) lore.add("§cNothing to convert");
        else lore.add("§eClick to " + (condenseMode ? "condense" : "decondense"));

        ItemStack item = new ItemStack(can ? material : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> comps = new ArrayList<>();
            for (String line : lore) comps.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(comps);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (can) SoulTableUiItems.glint(meta);
            SoulTableUiKeys.tagAction(meta, plugin, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void handleClick(Player player, int rawSlot, ClickType clickType) {
        if (player == null) return;
        if (!viewerId.equals(player.getUniqueId())) { player.closeInventory(); return; }
        if (rawSlot < 0 || rawSlot >= SIZE) return;
        ItemStack clicked = inventory.getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) return;
        String action = SoulTableUiKeys.readAction(plugin, clicked);
        if (action == null) return;
        switch (action) {
            case SoulTableUiKeys.ACTION_BACK_HUB -> SoulTableNav.openHub(plugin, player, tableId);
            case SoulTableUiKeys.ACTION_CLOSE -> player.closeInventory();
            case SoulTableUiKeys.ACTION_GUIDE ->
                    SoulTableNav.openGuide(plugin, player, tableId, SoulTableGuideInv.Page.MAIN, SoulTableReturnTarget.CONDENSE);
            default -> {
                if (action.startsWith("condense:")) runCondense(player, action.substring(9), false);
                else if (action.startsWith("decondense:")) runCondense(player, action.substring(11), true);
            }
        }
    }

    private void runCondense(Player player, String amountRaw, boolean decondense) {
        SoulTableService svc = tableService();
        SoulTableConfig cfg = cfg();
        if (svc == null) {
            SoulTableUiItems.actionBar(player, "Soul system is not initialized.", NamedTextColor.RED);
            return;
        }
        if (!cfg.isCondenseEnabled()) {
            SoulTableUiItems.actionBar(player, "Condensing is disabled.", NamedTextColor.RED);
            return;
        }
        int rate = Math.max(1, cfg.condenseRate());
        int units;
        if ("max".equalsIgnoreCase(amountRaw)) {
            if (decondense) {
                units = CondensedSoulEssenceItem.countInInventory(plugin, player);
            } else {
                int essence = plugin.getSoulService() != null ? plugin.getSoulService().getEssence(viewerId) : 0;
                units = essence / rate;
            }
        } else {
            try { units = Integer.parseInt(amountRaw); } catch (Exception e) { units = 0; }
        }
        if (units <= 0) {
            SoulTableUiItems.actionBar(player, "Nothing to convert.", NamedTextColor.RED);
            return;
        }
        SoulTableService.TableResult res = decondense ? svc.decondenseEssence(player, units) : svc.condenseEssence(player, units);
        if (res == null || !res.ok()) {
            SoulTableUiItems.actionBar(player, res != null ? res.message() : "Operation failed.", NamedTextColor.RED);
        } else {
            SoulTableUiItems.actionBar(player, res.message(), NamedTextColor.GREEN);
            player.sendMessage(SoulTableUiItems.chatPrefix().append(Component.text(res.message(), NamedTextColor.GRAY)));
        }
        refresh();
    }
}
