package dev.gloved.glovedplugin.enchant;

import dev.gloved.glovedplugin.GlovedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class EliteMobsItemReset {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public static final class Result {
        public final boolean ok;
        public final String message;
        public Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private EliteMobsItemReset() {}

    public static Result resetHeldItem(Player player) {
        if (player == null) return new Result(false, "Only players can use this command.");
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            return new Result(false, "Hold an EliteMobs custom item in your main hand.");
        }
        if (!EnchantBridge.eliteMobsPresent()) {
            return new Result(false, "EliteMobs is not available.");
        }
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) {
            return new Result(false, "That item has no metadata and cannot be matched to an EliteMobs custom item.");
        }
        String customId = EnchantBridge.emCustomItemId(hand);
        if (customId == null || customId.isBlank()) {
            customId = EnchantBridge.emCustomItemId(meta);
        }
        if (customId == null || customId.isBlank()) {
            boolean emItem = false;
            try {
                ClassLoader cl = EnchantBridge.emCl();
                Class<?> mgr = Class.forName("com.magmaguy.elitemobs.api.utils.EliteItemManager", true, cl);
                Object r = mgr.getMethod("isEliteMobsItem", ItemStack.class).invoke(null, hand);
                emItem = r instanceof Boolean b && b;
            } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[emreset] No custom item ID for " + hand.getType() + " isEliteMobsItem=" + emItem + " metaKeys=" + meta.getPersistentDataContainer().getKeys());
            if (emItem) {
                return new Result(false, "That is an EliteMobs item, but not a custom item from customitems/*.yml (no custom item ID found). Procedurally generated elite gear can't be reset to a custom item config.");
            }
            return new Result(false, "That item is not an EliteMobs custom item (no custom item ID found).");
        }

        Plugin em = Bukkit.getPluginManager().getPlugin("EliteMobs");
        File customItemsDir = em != null ? new File(em.getDataFolder(), "customitems") : null;
        if (customItemsDir == null || !customItemsDir.isDirectory()) {
            return new Result(false, "EliteMobs customitems directory not found.");
        }

        File configFile = findConfigFile(customItemsDir, customId);
        if (configFile == null) {
            GlovedPlugin.logDebug("[emreset] No config match for customId=" + customId + " in " + customItemsDir);
            return new Result(false, "No matching custom item config found for '" + customId + "'.");
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        String cfgName = toLegacy(cfg.getString("name"));
        List<String> cfgLore = toLegacyLore(cfg.getStringList("lore"));

        String itemName = getItemNameLegacy(meta);
        List<String> itemLore = getItemLoreLegacy(meta);

        boolean matches = equalsNormalized(itemName, cfgName) && loreEquals(itemLore, cfgLore);
        if (matches) {
            return new Result(true, "Held item already matches config defaults (" + configFile.getName() + ").");
        }

        // Delete 1 from stack, then regive via EM console command
        ItemStack updated = hand.clone();
        if (updated.getAmount() > 1) {
            updated.setAmount(updated.getAmount() - 1);
            player.getInventory().setItemInMainHand(updated);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        player.updateInventory();

        String giveArg = configFile.getName(); // e.g. bee_swarm_sword.yml (unique across subfolders)
        String command = "em loot give " + player.getName() + " " + giveArg;
        boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        GlovedPlugin.logDebug("[emreset] customId=" + customId + " config=" + configFile.getPath()
                + " differs -> dispatched '" + command + "' result=" + dispatched);
        if (!dispatched) {
            // Fallback: try raw ID
            String fallback = "em loot give " + player.getName() + " " + customId;
            boolean d2 = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fallback);
            GlovedPlugin.logDebug("[emreset] fallback '" + fallback + "' result=" + d2);
            if (d2) {
                return new Result(true, "Item reset to config defaults (" + customId + ").");
            }
            return new Result(false, "Removed 1 item, but failed to dispatch EliteMobs give command. Try: /em loot give " + player.getName() + " " + giveArg);
        }
        return new Result(true, "Item reset to config defaults (" + configFile.getName() + ").");
    }

    private static File findConfigFile(File dir, String customId) {
        if (customId == null) return null;
        String id = customId.trim();
        if (id.isEmpty()) return null;
        List<File> all = new ArrayList<>();
        collectYml(dir, all);
        String idLower = id.toLowerCase();
        String idBase = stripYml(idLower);
        // exact filename matches first
        for (File f : all) {
            String n = f.getName().toLowerCase();
            if (n.equals(idLower) || n.equals(idLower + ".yml") || stripYml(n).equals(idBase)) {
                return f;
            }
        }
        // also match EM-style id that might be a path/key -> basename compare
        String baseName = new File(id.replace('\\', '/')).getName().toLowerCase();
        String baseNoExt = stripYml(baseName);
        for (File f : all) {
            if (stripYml(f.getName().toLowerCase()).equals(baseNoExt)) return f;
        }
        return null;
    }

    private static void collectYml(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) collectYml(f, out);
            else if (f.isFile() && f.getName().toLowerCase().endsWith(".yml")) out.add(f);
        }
    }

    private static String stripYml(String s) {
        if (s == null) return "";
        return s.endsWith(".yml") ? s.substring(0, s.length() - 4) : s;
    }

    private static String toLegacy(String s) {
        if (s == null || s.isEmpty()) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private static List<String> toLegacyLore(List<String> lore) {
        List<String> out = new ArrayList<>();
        if (lore == null) return out;
        for (String line : lore) out.add(toLegacy(line));
        return out;
    }

    private static String getItemNameLegacy(ItemMeta meta) {
        try {
            Component c = meta.displayName();
            if (c == null) return "";
            return LEGACY.serialize(c);
        } catch (Throwable t) {
            // fallback for older API shapes
            try {
                if (meta.hasDisplayName()) return toLegacy(meta.getDisplayName());
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static List<String> getItemLoreLegacy(ItemMeta meta) {
        List<String> out = new ArrayList<>();
        try {
            List<Component> lore = meta.lore();
            if (lore == null) return out;
            for (Component c : lore) out.add(c == null ? "" : LEGACY.serialize(c));
            return out;
        } catch (Throwable t) {
            try {
                if (meta.hasLore() && meta.getLore() != null) {
                    for (String s : meta.getLore()) out.add(toLegacy(s));
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static boolean equalsNormalized(String a, String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.equals(bb);
    }

    private static boolean loreEquals(List<String> a, List<String> b) {
        if (a == null) a = List.of();
        if (b == null) b = List.of();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!equalsNormalized(a.get(i), b.get(i))) return false;
        }
        return true;
    }
}
