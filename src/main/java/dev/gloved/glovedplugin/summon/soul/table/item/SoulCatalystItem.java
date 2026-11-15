package dev.gloved.glovedplugin.summon.soul.table.item;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** PDC-tagged Soul Catalyst material item used by endgame Soul Table Infusion. */
public final class SoulCatalystItem {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SoulCatalystItem() {}

    public static NamespacedKey pdcKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_catalyst");
    }

    public static NamespacedKey modelKey() {
        return new NamespacedKey("gloved", "soul_catalyst");
    }

    public static ItemStack create(GlovedPlugin plugin, int amount, SoulTableConfig cfg) {
        Material material = cfg != null ? cfg.catalystMaterial() : Material.NETHER_STAR;
        String name = cfg != null ? cfg.catalystName() : "§dSoul Catalyst";
        if (name != null) name = name.replace('&', '§');
        ItemStack item = new ItemStack(material != null ? material : Material.NETHER_STAR, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(LEGACY.deserialize(name != null ? name : "§dSoul Catalyst").decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(LEGACY.deserialize("§7A focused catalyst for soul infusion.").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(LEGACY.deserialize("§7Used in §dSoul Table Infusion§7").decoration(TextDecoration.ITALIC, false));
        lore.add(LEGACY.deserialize("§7to add or replace soul traits.").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(pdcKey(plugin), PersistentDataType.BYTE, (byte) 1);
        try { meta.setItemModel(modelKey()); } catch (Throwable ignored) {}
        item.setItemMeta(meta);
        item.setAmount(Math.max(1, amount));
        return item;
    }

    public static boolean isCatalyst(GlovedPlugin plugin, ItemStack item) {
        if (plugin == null || item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(pdcKey(plugin), PersistentDataType.BYTE)
                || meta.getPersistentDataContainer().has(pdcKey(plugin), PersistentDataType.INTEGER);
    }

    public static int countInInventory(GlovedPlugin plugin, Player player) {
        if (plugin == null || player == null) return 0;
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isCatalyst(plugin, stack)) total += stack.getAmount();
        }
        return total;
    }

    /** Removes up to {@code amount} tagged catalyst items. Returns true if fully removed. */
    public static boolean removeFromInventory(GlovedPlugin plugin, Player player, int amount) {
        if (plugin == null || player == null || amount <= 0) return true;
        int left = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (!isCatalyst(plugin, stack)) continue;
            int take = Math.min(left, stack.getAmount());
            int newAmount = stack.getAmount() - take;
            if (newAmount <= 0) contents[i] = null;
            else stack.setAmount(newAmount);
            left -= take;
        }
        if (left <= 0) {
            player.getInventory().setStorageContents(contents);
            return true;
        }
        return false;
    }
}
