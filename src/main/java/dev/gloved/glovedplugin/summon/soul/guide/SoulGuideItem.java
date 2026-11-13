package dev.gloved.glovedplugin.summon.soul.guide;

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
 * Custom Soul Guide item: opens the Soul Guide GUI when right-clicked.
 * Identity is PDC-based so name/lore can change safely.
 */
public final class SoulGuideItem {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SoulGuideItem() {}

    public static NamespacedKey tagKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_guide");
    }

    public static NamespacedKey receivedKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_guide_received");
    }

    public static ItemStack create(GlovedPlugin plugin) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.displayName(LEGACY.deserialize("§b§lSoul Guide").decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(LEGACY.deserialize("§7Learn about souls, traits, and upgrades.").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(LEGACY.deserialize("§eRight-click §7to open the guide.").decoration(TextDecoration.ITALIC, false));
        lore.add(LEGACY.deserialize("§8You can craft another if you lose it.").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
            // Older API: item still functions without visible glint.
        }
        meta.getPersistentDataContainer().set(tagKey(plugin), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isSoulGuide(GlovedPlugin plugin, ItemStack item) {
        if (plugin == null || item == null || item.getType() != Material.BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        var pdc = meta.getPersistentDataContainer();
        return pdc.has(tagKey(plugin), PersistentDataType.BYTE)
                || pdc.has(tagKey(plugin), PersistentDataType.INTEGER);
    }
}
