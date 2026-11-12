package dev.gloved.glovedplugin.summon.soul;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** PDC-tagged hotbar item for quick soul casting and vault access. */
public final class SoulItem {
    private SoulItem() {}

    public static final int SLOT = 8; // last hotbar slot

    public static NamespacedKey key() {
        return new NamespacedKey(JavaPlugin.getPlugin(GlovedPlugin.class), "soul-item");
    }

    public static ItemStack create() {
        return create(null, null);
    }

    public static ItemStack create(Player player, GlovedPlugin plugin) {
        ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§d§lSoul Focus").decoration(TextDecoration.ITALIC, false));
            meta.lore(buildLore(player, plugin));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static java.util.List<Component> buildLore(Player player, GlovedPlugin plugin) {
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Left-click: §aCast / Recall selected soul").decoration(TextDecoration.ITALIC, false));
        lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Right-click: §bOpen Soul Vault").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Selected: " + selectedSoulText(player, plugin)).decoration(TextDecoration.ITALIC, false));
        lore.add(LegacyComponentSerializer.legacySection().deserialize("§8Bound to your soul collection").decoration(TextDecoration.ITALIC, false));
        return lore;
    }

    private static String selectedSoulText(Player player, GlovedPlugin plugin) {
        try {
            GlovedPlugin gp = plugin != null ? plugin : JavaPlugin.getPlugin(GlovedPlugin.class);
            if (player == null || gp == null || gp.getSoulService() == null) return "§7None";
            SoulInstance soul = gp.getSoulService().getSelectedSoul(player.getUniqueId());
            if (soul == null) return "§7None";
            String sep = soul.getRarity() == SoulRarity.MYTHIC ? " §r§c" : " ";
            return soul.getRarity().coloredNameLegacy() + sep + prettyMobName(soul.getFamilyKey()) + " Soul";
        } catch (Throwable ignored) {
            return "§7None";
        }
    }

    private static String prettyMobName(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(java.util.Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    public static boolean isSoulItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
    }

    public static void give(Player player) {
        if (player == null) return;
        GlovedPlugin plugin = null;
        try { plugin = JavaPlugin.getPlugin(GlovedPlugin.class); } catch (Throwable ignored) {}
        give(player, plugin);
    }

    public static void give(Player player, GlovedPlugin plugin) {
        if (player == null) return;
        if (plugin == null) {
            try { plugin = JavaPlugin.getPlugin(GlovedPlugin.class); } catch (Throwable ignored) {}
        }
        ItemStack desired = create(player, plugin);
        // remove duplicates elsewhere
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            if (i == SLOT) continue;
            ItemStack it = player.getInventory().getItem(i);
            if (isSoulItem(it)) player.getInventory().setItem(i, null);
        }
        ItemStack cur = player.getInventory().getItem(SLOT);
        if (isSoulItem(cur)) {
            // keep slot occupied; refresh name/lore
            player.getInventory().setItem(SLOT, desired);
            return;
        }
        // If occupied by other item, try to move it
        if (cur != null && !cur.getType().isAir()) {
            var leftover = player.getInventory().addItem(cur.clone());
            if (!leftover.isEmpty()) {
                // cannot move; do not overwrite player item
                return;
            }
        }
        player.getInventory().setItem(SLOT, desired);
    }

    /** Refresh the Soul Focus item lore (e.g. after selection changes). */
    public static void refresh(Player player, GlovedPlugin plugin) {
        give(player, plugin);
    }

    public static void openVault(GlovedPlugin plugin, Player player) {
        if (plugin == null || player == null) return;
        new SoulVaultInv(plugin, player.getUniqueId(), 0, null, null, "default").open(player);
    }
}
