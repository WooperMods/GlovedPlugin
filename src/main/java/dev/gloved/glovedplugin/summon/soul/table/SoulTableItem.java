package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and identifies the Soul Table item.
 *
 * <p>The item is a plain {@link Material#SMITHING_TABLE} carrying a PDC tag (identity, survives
 * resource-pack removal) plus the {@code gloved:soul_table} item model (appearance, supplied by the
 * generated pack).</p>
 */
public final class SoulTableItem {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SoulTableItem() {}

    /** Item model id shared by the block item and the full-block ItemDisplay overlay. */
    public static NamespacedKey itemModelKey() {
        return new NamespacedKey("gloved", "soul_table");
    }

    /** PDC tag: presence marks an item as a Soul Table. */
    public static NamespacedKey tagKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_table");
    }

    /** PDC tag on the overlay ItemDisplay, holding the table UUID as a string. */
    public static NamespacedKey overlayKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_table_overlay");
    }

    public static ItemStack create(GlovedPlugin plugin) {
        ItemStack item = new ItemStack(Material.SMITHING_TABLE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(LEGACY.deserialize("§d§lSoul Table").decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(LEGACY.deserialize("§7A soul-forging station.").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(LEGACY.deserialize("§7Place it, then right-click to open.").decoration(TextDecoration.ITALIC, false));
        lore.add(LEGACY.deserialize("§8Combine §7· §8Enhance §7· §8Reforge §7· §8Salvage").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(tagKey(plugin), PersistentDataType.BYTE, (byte) 1);
        try {
            meta.setItemModel(itemModelKey());
        } catch (Throwable ignored) {
            // Older API: the table still works without a custom model.
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Clean stack used by the full-block overlay (no name/lore shown). */
    public static ItemStack createDisplayStack() {
        ItemStack item = new ItemStack(Material.SMITHING_TABLE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            try {
                meta.setItemModel(itemModelKey());
            } catch (Throwable ignored) {}
            item.setItemMeta(meta);
        }
        return item;
    }

    /** PDC-based identity check (not the item model, so removal of the pack cannot break it). */
    public static boolean isSoulTable(GlovedPlugin plugin, ItemStack item) {
        if (plugin == null || item == null || item.getType() != Material.SMITHING_TABLE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(tagKey(plugin), PersistentDataType.BYTE)
                || meta.getPersistentDataContainer().has(tagKey(plugin), PersistentDataType.INTEGER);
    }
}
