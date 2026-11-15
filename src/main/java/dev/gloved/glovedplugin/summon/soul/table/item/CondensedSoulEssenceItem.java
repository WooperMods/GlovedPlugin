package dev.gloved.glovedplugin.summon.soul.table.item;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** PDC-tagged Condensed Soul Essence material item (stackable, table-convertible). */
public final class CondensedSoulEssenceItem {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private CondensedSoulEssenceItem() {}

    public static NamespacedKey pdcKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "condensed_soul_essence");
    }

    public static NamespacedKey modelKey() {
        return new NamespacedKey("gloved", "condensed_soul_essence");
    }

    public static ItemStack create(GlovedPlugin plugin, int amount, SoulTableConfig cfg) {
        int stack = Math.max(1, amount);
        int rate = cfg != null ? cfg.condenseRate() : 128;
        Material material = cfg != null ? cfg.condensedMaterial() : Material.AMETHYST_SHARD;
        String name = cfg != null ? cfg.condensedName() : "§bCondensed Soul Essence";
        if (name != null) name = name.replace('&', '§');
        ItemStack item = new ItemStack(material != null ? material : Material.AMETHYST_SHARD, Math.min(64, stack));
        applyMeta(plugin, item, stack, rate, name);
        item.setAmount(stack);
        return item;
    }

    public static void applyMeta(GlovedPlugin plugin, ItemStack item, int stackAmount, int rate, String displayName) {
        if (plugin == null || item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        int safeStack = Math.max(1, stackAmount);
        int safeRate = Math.max(1, rate);
        long total = (long) safeStack * (long) safeRate;
        String name = displayName != null ? displayName.replace('&', '§') : "§bCondensed Soul Essence";
        meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(LEGACY.deserialize("§7Value: §b" + total + " Essence §7(§b" + safeRate + " each§7)").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(LEGACY.deserialize("§eRight-click to redeem §b1§e (§b" + safeRate + " Essence§e).").decoration(TextDecoration.ITALIC, false));
        lore.add(LEGACY.deserialize("§7Used to craft §dSoul Catalysts§7.").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(pdcKey(plugin), PersistentDataType.BYTE, (byte) 1);
        try { meta.setItemModel(modelKey()); } catch (Throwable ignored) {}
        item.setItemMeta(meta);
    }

    public static void refreshMeta(GlovedPlugin plugin, ItemStack item, SoulTableConfig cfg) {
        if (plugin == null || item == null) return;
        int rate = cfg != null ? cfg.condenseRate() : 128;
        String name = cfg != null ? cfg.condensedName() : "§bCondensed Soul Essence";
        applyMeta(plugin, item, Math.max(1, item.getAmount()), rate, name);
    }

    public static boolean isCondensed(GlovedPlugin plugin, ItemStack item) {
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
            if (isCondensed(plugin, stack)) total += stack.getAmount();
        }
        return total;
    }

    public static boolean hasSpaceFor(Inventory inv, ItemStack item, int amount) {
        if (inv == null || item == null || amount <= 0) return true;
        ItemStack probe = item.clone();
        probe.setAmount(1);
        int remaining = amount;
        for (ItemStack slot : inv.getStorageContents()) {
            if (remaining <= 0) break;
            if (slot == null || slot.getType().isAir()) {
                remaining -= probe.getMaxStackSize();
            } else if (slot.isSimilar(probe)) {
                remaining -= Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            }
        }
        return remaining <= 0;
    }

    /** Removes up to {@code amount} tagged condensed items. Returns true if fully removed. */
    public static boolean removeFromInventory(GlovedPlugin plugin, Player player, int amount) {
        if (plugin == null || player == null || amount <= 0) return true;
        int left = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (!isCondensed(plugin, stack)) continue;
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

    public static void giveToPlayer(GlovedPlugin plugin, Player player, int amount, SoulTableConfig cfg) {
        if (plugin == null || player == null || amount <= 0) return;
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(64, remaining);
            ItemStack item = create(plugin, stackSize, cfg);
            item.setAmount(stackSize);
            var leftover = player.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
            remaining -= stackSize;
        }
    }
}
