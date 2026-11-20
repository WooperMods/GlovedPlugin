package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
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

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Shows a single recipe's layout in a fake 3x3 crafting grid. */
public final class SoulTableRecipeDetailInv implements SoulTableScreen {

    private static final int SIZE = 54;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    // 3x3 grid slots (centered)
    private static final int[] GRID = {11, 12, 13, 20, 21, 22, 29, 30, 31};

    private static final int SLOT_BACK = 49;

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final String recipeId;
    private final Inventory inventory;

    public SoulTableRecipeDetailInv(GlovedPlugin plugin, Player player, UUID tableId, String recipeId) {
        this.plugin = plugin;
        this.viewerId = player != null ? player.getUniqueId() : null;
        this.tableId = tableId;
        this.recipeId = recipeId;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Recipe: " + SoulTableRecipe.byId(recipeId).name, NamedTextColor.DARK_AQUA));
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

        SoulTableRecipe recipe = SoulTableRecipe.byId(recipeId);
        if (recipe == null) {
            inventory.setItem(SLOT_BACK, SoulTableUiItems.backButton(plugin, "§eBack to Recipes", "§7Return to the recipe picker."));
            return;
        }

        // Result item (top right)
        SoulTableConfig cfg = plugin.getSummonConfig() != null
                ? plugin.getSummonConfig().getSoulTableConfig()
                : null;
        ItemStack result = recipe.createItem(plugin, cfg);
        ItemMeta resultMeta = result.getItemMeta();
        if (resultMeta != null) {
            resultMeta.displayName(LEGACY.deserialize("§a§lResult: " + recipe.name).decoration(TextDecoration.ITALIC, false));
            resultMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            result.setItemMeta(resultMeta);
        }
        inventory.setItem(24, result);

        // 3x3 grid
        if (recipe.shapeless) {
            List<String> ings = recipe.shapelessIngredients;
            for (int i = 0; i < Math.min(ings.size(), GRID.length); i++) {
                Material m = Material.matchMaterial(ings.get(i).toUpperCase(Locale.ROOT).replace(' ', '_'));
                inventory.setItem(GRID[i], SoulTableUiItems.recipeGridItem(m));
            }
        } else {
            for (int i = 0; i < GRID.length; i++) {
                int row = i / 3;
                int col = i % 3;
                inventory.setItem(GRID[i], SoulTableUiItems.recipeGridItem(recipe.grid[row][col]));
            }
        }

        inventory.setItem(SLOT_BACK, SoulTableUiItems.backButton(plugin, "§eBack to Recipes", "§7Return to the recipe picker."));
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
            new SoulTableRecipeInv(plugin, player, tableId).open(player);
        }
    }
}
