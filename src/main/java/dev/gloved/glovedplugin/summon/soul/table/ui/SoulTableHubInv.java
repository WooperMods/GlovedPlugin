package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.ui.UiIcons;
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

/** Hub/entry screen for the Soul Table: pick a process, open guide, or close. */
public final class SoulTableHubInv implements SoulTableScreen {

    private static final int SIZE = 54;
    private static final int SLOT_COMBINE = 19;
    private static final int SLOT_ENHANCE = 21;
    private static final int SLOT_REFORGE = 23;
    private static final int SLOT_SALVAGE = 25;
    private static final int SLOT_CONDENSE = 29;
    private static final int SLOT_INFUSION = 33;
    private static final int SLOT_GUIDE = 49;
    private static final int SLOT_RECIPES = 31;
    private static final int SLOT_ESSENCE = 4;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final Inventory inventory;

    public SoulTableHubInv(GlovedPlugin plugin, Player player, SoulTableRow table) {
        this.plugin = plugin;
        this.viewerId = player.getUniqueId();
        this.tableId = table != null ? table.tableId() : null;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Soul Table", NamedTextColor.LIGHT_PURPLE));
        refresh();
    }

    @Override
    public UUID getViewerId() {
        return viewerId;
    }

    @Override
    public UUID getTableId() {
        return tableId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    @Override
    public void open(Player player) {
        if (player == null) return;
        refresh();
        player.openInventory(inventory);
    }

    @Override
    public void refresh() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, SoulTableUiItems.filler());

        SoulService svc = plugin.getSoulService();
        int essence = svc != null ? svc.getEssence(viewerId) : 0;

        inventory.setItem(SLOT_COMBINE, processItem(Material.ANVIL, "§d§lCombine",
                SoulTableUiKeys.ACTION_OPEN_COMBINE, List.of(
                        "§7Combine 3 souls of the same type",
                        "§7into one higher-rarity soul.",
                        "",
                        "§eClick to open")));
        inventory.setItem(SLOT_ENHANCE, processItem(Material.EXPERIENCE_BOTTLE, "§d§lEnhance",
                SoulTableUiKeys.ACTION_OPEN_ENHANCE, List.of(
                        "§7Raise one soul's level and improve",
                        "§7it's stats.",
                        "",
                        "§eClick to open")));
        inventory.setItem(SLOT_REFORGE, processItem(Material.GRINDSTONE, "§d§lReforge",
                SoulTableUiKeys.ACTION_OPEN_REFORGE, List.of(
                        "§7Reroll a soul's affixes and stats.",
                        "",
                        "§eClick to open")));
        inventory.setItem(SLOT_SALVAGE, processItem(Material.HOPPER, "§d§lSalvage",
                SoulTableUiKeys.ACTION_OPEN_SALVAGE, List.of(
                        "§7Convert selected souls into Essence.",
                        "",
                        "§eClick to open")));

        inventory.setItem(SLOT_CONDENSE, processItem(Material.AMETHYST_BLOCK, "§b§lCondense",
                SoulTableUiKeys.ACTION_OPEN_CONDENSE, List.of(
                        "§7Convert Essence ⇄ Condensed Soul Essence.",
                        "",
                        "§eClick to open")));
        inventory.setItem(SLOT_INFUSION, processItem(Material.NETHER_STAR, "§5§lInfusion",
                SoulTableUiKeys.ACTION_OPEN_INFUSION, List.of(
                        "§7Add or replace traits",
                        "",
                        "§eClick to open")));

        inventory.setItem(SLOT_ESSENCE, SoulTableUiItems.infoItem(Material.AMETHYST_SHARD,
                "§b§lSoul Essence: §f" + essence,
                List.of("§7Used for table processes including",
                        "§7Combine, Enhance, Reforge, Salvage, Condense, and Infusion.")));

        inventory.setItem(SLOT_GUIDE, SoulTableUiItems.actionItem(plugin, Material.BOOK,
                "§b§lSoul Guide", SoulTableUiKeys.ACTION_GUIDE,
                List.of("§7Learn about traits, bonuses, stats,",
                        "§7and what each table action does.",
                        "",
                        "§eClick to open")));
        inventory.setItem(SLOT_RECIPES, SoulTableUiItems.actionItem(plugin, Material.CRAFTING_TABLE,
                "§6§lSoul Recipes", SoulTableUiKeys.ACTION_RECIPES,
                List.of("§7View crafting recipes for soul items.",
                        "§7Soul Guide, Soul Table, Catalyst,",
                        "§7Soul Traps, and Condensed Essence.",
                        "",
                        "§eClick to open")));
    }

    private ItemStack processItem(Material material, String name, String action, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            SoulTableUiKeys.tagAction(meta, plugin, action);
            item.setItemMeta(meta);
        }
        String icon = switch (action) {
            case SoulTableUiKeys.ACTION_OPEN_COMBINE -> UiIcons.COMBINE;
            case SoulTableUiKeys.ACTION_OPEN_ENHANCE -> UiIcons.ENHANCE;
            case SoulTableUiKeys.ACTION_OPEN_REFORGE -> UiIcons.REFORGE;
            case SoulTableUiKeys.ACTION_OPEN_SALVAGE -> UiIcons.SALVAGE;
            case SoulTableUiKeys.ACTION_OPEN_CONDENSE -> UiIcons.CONDENSE;
            case SoulTableUiKeys.ACTION_OPEN_INFUSION -> UiIcons.INFUSION;
            default -> null;
        };
        return UiIcons.apply(item, icon);
    }

    @Override
    public void handleClick(Player player, int rawSlot, ClickType clickType) {
        if (player == null) return;
        if (!viewerId.equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (rawSlot < 0 || rawSlot >= SIZE) return;
        ItemStack clicked = inventory.getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) return;
        String action = SoulTableUiKeys.readAction(plugin, clicked);
        if (action == null) return;
        switch (action) {
            case SoulTableUiKeys.ACTION_OPEN_COMBINE ->
                    SoulTableNav.openProcess(plugin, player, tableId, SoulTableReturnTarget.COMBINE);
            case SoulTableUiKeys.ACTION_OPEN_ENHANCE ->
                    SoulTableNav.openProcess(plugin, player, tableId, SoulTableReturnTarget.ENHANCE);
            case SoulTableUiKeys.ACTION_OPEN_REFORGE ->
                    SoulTableNav.openProcess(plugin, player, tableId, SoulTableReturnTarget.REFORGE);
            case SoulTableUiKeys.ACTION_OPEN_SALVAGE ->
                    SoulTableNav.openProcess(plugin, player, tableId, SoulTableReturnTarget.SALVAGE);
            case SoulTableUiKeys.ACTION_OPEN_CONDENSE ->
                    SoulTableNav.openProcess(plugin, player, tableId, SoulTableReturnTarget.CONDENSE);
            case SoulTableUiKeys.ACTION_OPEN_INFUSION ->
                    SoulTableNav.openProcess(plugin, player, tableId, SoulTableReturnTarget.INFUSION);
            case SoulTableUiKeys.ACTION_GUIDE ->
                    SoulTableNav.openGuide(plugin, player);
            case SoulTableUiKeys.ACTION_RECIPES ->
                    new SoulTableRecipeInv(plugin, player, tableId).open(player);
            default -> {
            }
        }
    }
}
