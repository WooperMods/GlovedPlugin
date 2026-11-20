package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.ui.UiIcons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
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

/** Lists all craftable soul items; clicking one opens its recipe detail. */
public final class SoulTableRecipeInv implements SoulTableScreen {

    private static final int SIZE = 54;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static final int SLOT_BACK = 49;

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final Inventory inventory;

    public SoulTableRecipeInv(GlovedPlugin plugin, Player player, UUID tableId) {
        this.plugin = plugin;
        this.viewerId = player != null ? player.getUniqueId() : null;
        this.tableId = tableId;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Soul Recipes", NamedTextColor.DARK_AQUA));
        build();
    }

    @Override
    public UUID getViewerId() { return viewerId; }

    @Override
    public UUID getTableId() { return tableId; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }

    @Override
    public void open(Player player) {
        if (player == null) return;
        player.openInventory(inventory);
    }

    @Override
    public void refresh() {
        build();
    }

    private void build() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, SoulTableUiItems.filler());

        int slot = 0;
        for (SoulTableRecipe recipe : SoulTableRecipe.all()) {
            if (slot >= 45) break;
            inventory.setItem(slot, recipeItem(recipe));
            slot++;
        }

        inventory.setItem(SLOT_BACK, SoulTableUiItems.backToHubItem(plugin));
    }

    private ItemStack recipeItem(SoulTableRecipe recipe) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Click to view recipe");

        SoulTableConfig cfg = plugin.getSummonConfig() != null
                ? plugin.getSummonConfig().getSoulTableConfig()
                : null;
        ItemStack item = recipe.createItem(plugin, cfg);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> components = new ArrayList<>();
            for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            SoulTableUiKeys.tagAction(meta, plugin, SoulTableUiKeys.ACTION_RECIPE_DETAIL + ":" + recipe.id);
            item.setItemMeta(meta);
        }
        return item;
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

        if (SoulTableUiKeys.ACTION_BACK_HUB.equals(action)) {
            SoulTableNav.openHub(plugin, player, tableId);
        } else if (action.startsWith(SoulTableUiKeys.ACTION_RECIPE_DETAIL + ":")) {
            String recipeId = action.substring(SoulTableUiKeys.ACTION_RECIPE_DETAIL.length() + 1);
            new SoulTableRecipeDetailInv(plugin, player, tableId, recipeId).open(player);
        }
    }
}
