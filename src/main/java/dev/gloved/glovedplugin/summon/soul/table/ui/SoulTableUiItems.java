package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulQuality;
import dev.gloved.glovedplugin.summon.soul.item.SoulDropItem;
import dev.gloved.glovedplugin.summon.soul.item.SoulLoreBuilder;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableItem;
import dev.gloved.glovedplugin.ui.UiIcons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Shared item/chrome builders for Soul Table screens. */
public final class SoulTableUiItems {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SoulTableUiItems() {
    }

    public static ItemStack filler() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Creates a recipe result item showing the actual material texture with a display name. */
    public static ItemStack recipeResultItem(Material material, int amount, String displayName) {
        ItemStack item = new ItemStack(material != null ? material : Material.AIR, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(displayName).decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Creates a recipe grid slot item showing the actual material texture. Null/air returns filler. */
    public static ItemStack recipeGridItem(Material material) {
        if (material == null || material == Material.AIR) return filler();
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§f" + SoulTableRecipe.materialName(material)).decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack actionItem(GlovedPlugin plugin, Material material, String name, String action, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            if (lore != null) {
                for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (action != null) SoulTableUiKeys.tagAction(meta, plugin, action);
            item.setItemMeta(meta);
        }
        return UiIcons.apply(item, iconForAction(action));
    }

    public static ItemStack pageNavItem(GlovedPlugin plugin, boolean next, boolean enabled, int page, int maxPage, String context) {
        if (maxPage <= 0) return filler();
        String action = next ? SoulTableUiKeys.ACTION_NEXT : SoulTableUiKeys.ACTION_PREV;
        String baseName = next ? "Next Page" : "Previous Page";
        String name = enabled ? "§e" + baseName : "§8" + baseName;
        Material mat = enabled ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE;
        String icon = next
                ? (enabled ? UiIcons.ARROW_RIGHT : UiIcons.ARROW_RIGHT_DISABLED)
                : (enabled ? UiIcons.ARROW_LEFT : UiIcons.ARROW_LEFT_DISABLED);
        List<String> lore = new ArrayList<>();
        if (context != null && !context.isBlank()) lore.add("§7" + context);
        lore.add("§7Page §f" + (page + 1) + "§7/§f" + (maxPage + 1));
        if (!enabled) lore.add(next ? "§8No next page." : "§8No previous page.");
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (enabled) SoulTableUiKeys.tagAction(meta, plugin, action);
            item.setItemMeta(meta);
        }
        return UiIcons.apply(item, icon);
    }

    public static ItemStack backToHubItem(GlovedPlugin plugin) {
        ItemStack item = SoulTableItem.createDisplayStack();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§eBack to Hub").decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            components.add(LEGACY.deserialize("§7Return to the Soul Table hub.").decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            SoulTableUiKeys.tagAction(meta, plugin, SoulTableUiKeys.ACTION_BACK_HUB);
            item.setItemMeta(meta);
        }
        return UiIcons.apply(item, UiIcons.HOME);
    }

    /** Generic back button with custom name/lore — used by screens that go back to a non-hub parent. */
    public static ItemStack backButton(GlovedPlugin plugin, String displayName, String loreLine) {
        ItemStack item = SoulTableItem.createDisplayStack();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(displayName).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            components.add(LEGACY.deserialize(loreLine).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            SoulTableUiKeys.tagAction(meta, plugin, SoulTableUiKeys.ACTION_BACK_HUB);
            item.setItemMeta(meta);
        }
        return UiIcons.apply(item, UiIcons.BACK);
    }

    private static String iconForAction(String action) {
        if (action == null) return null;
        return switch (action) {
            case SoulTableUiKeys.ACTION_PREV -> UiIcons.ARROW_LEFT;
            case SoulTableUiKeys.ACTION_NEXT -> UiIcons.ARROW_RIGHT;
            case SoulTableUiKeys.ACTION_BACK_HUB -> UiIcons.HOME;
            case SoulTableUiKeys.ACTION_CLOSE -> UiIcons.CLOSE;
            case SoulTableUiKeys.ACTION_GUIDE -> UiIcons.GUIDE;
            case SoulTableUiKeys.ACTION_OPEN_COMBINE -> UiIcons.COMBINE;
            case SoulTableUiKeys.ACTION_OPEN_ENHANCE -> UiIcons.ENHANCE;
            case SoulTableUiKeys.ACTION_OPEN_REFORGE -> UiIcons.REFORGE;
            case SoulTableUiKeys.ACTION_OPEN_SALVAGE -> UiIcons.SALVAGE;
            case SoulTableUiKeys.ACTION_OPEN_CONDENSE -> UiIcons.CONDENSE;
            case SoulTableUiKeys.ACTION_OPEN_INFUSION -> UiIcons.INFUSION;
            case SoulTableUiKeys.ACTION_RECIPES -> UiIcons.GUIDE;
            default -> null;
        };
    }

    public static ItemStack infoItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            if (lore != null) {
                for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack soulItem(GlovedPlugin plugin, SoulInstance soul, java.util.UUID viewerId, boolean selected) {
        ItemStack item = new ItemStack(SoulDropItem.materialFor(soul.getEntityType()));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String mobName = SoulLoreBuilder.pretty(soul.getFamilyKey());
        String qualityStr = SoulLoreBuilder.fmt(SoulQuality.percent(soul), 2) + "%";
        meta.displayName(Component.empty()
                .append(soul.getRarity().coloredNameComponent())
                .append(Component.text(" " + mobName + " Soul", soul.getRarity().getColor())
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(" §7[" + qualityStr + "]", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.addAll(SoulLoreBuilder.statsSection(plugin, soul, viewerId, true));
        lore.addAll(SoulLoreBuilder.traitsSection(soul));
        if (selected) {
            lore.add(LEGACY.deserialize("§a§lSELECTED").decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(LEGACY.deserialize("§eClick to select").decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (selected) glint(meta);
        SoulTableUiKeys.tagSoul(meta, plugin, soul.getSoulId());
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack selectionSlotItem(GlovedPlugin plugin, SoulInstance soul, java.util.UUID viewerId, int index) {
        if (soul == null) {
            return infoItem(Material.GRAY_STAINED_GLASS_PANE, "§8Empty slot " + (index + 1),
                    List.of("§7Pick a soul from the grid."));
        }
        ItemStack item = soulItem(plugin, soul, viewerId, true);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore();
            if (lore == null) lore = new ArrayList<>();
            else lore = new ArrayList<>(lore);
            lore.add(Component.empty());
            lore.add(LEGACY.deserialize("§cClick to deselect").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            SoulTableUiKeys.tagSoul(meta, plugin, soul.getSoulId());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static void glint(ItemMeta meta) {
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
            // Older API: selection is still readable from the SELECTED lore line.
        }
    }

    public static String costLabel(int cost) {
        return cost == Integer.MAX_VALUE ? "N/A" : String.valueOf(cost);
    }

    public static void actionBar(Player player, String message, NamedTextColor color) {
        if (player == null) return;
        player.sendActionBar(Component.text(message, color));
    }

    public static Component chatPrefix() {
        return Component.text("[Soul Table] ", NamedTextColor.LIGHT_PURPLE);
    }
}
