package dev.gloved.glovedplugin.lootr.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

public class EliteMobsIntegration {
    private static boolean eliteMobsAvailable = false;
    private static Class<?> playerDataClass;
    private static Method getCombatLevelMethod;

    static {
        try {
            Class.forName("com.magmaguy.elitemobs.playerdata.database.PlayerData");
            eliteMobsAvailable = true;
        } catch (ClassNotFoundException ignored) {
            eliteMobsAvailable = false;
        }
    }

    public static boolean isAvailable() {
        try {
            org.bukkit.plugin.Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null || !p.isEnabled()) return false;
            // Verify class is loadable via the plugin's classloader
            Class.forName("com.magmaguy.elitemobs.playerdata.database.PlayerData", true, p.getClass().getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int getCombatLevel(Player player) {
        if (!isAvailable()) return 1;
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null) return 1;
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> calcClass = Class.forName("com.magmaguy.elitemobs.skills.CombatLevelCalculator", true, cl);
            Method calcMethod = calcClass.getMethod("calculateCombatLevel", java.util.UUID.class);
            Object result = calcMethod.invoke(null, player.getUniqueId());
            if (result instanceof Integer) {
                int level = (Integer) result;
                return level > 0 ? level : 1;
            }
        } catch (Exception ignored) {}
        // Fallback to PlaceholderAPI
        try {
            Class<?> placeholderAPI = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method setPlaceholders = placeholderAPI.getMethod("setPlaceholders", Player.class, String.class);
            String result = (String) setPlaceholders.invoke(null, player, "%elitemobs_player_combat_level%");
            if (result != null && !result.equals("%elitemobs_player_combat_level%")) {
                try {
                    return Integer.parseInt(result.trim());
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return 1;
    }

    public static ItemStack generateRandomLoot(Player player, int level) {
        if (!isAvailable()) return null;
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null) return null;
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> lootTables = Class.forName("com.magmaguy.elitemobs.items.LootTables", true, cl);
            Method generateItemStack = lootTables.getMethod("generateItemStack", int.class, Player.class, Class.forName("com.magmaguy.elitemobs.mobconstructor.EliteEntity", true, cl));
            Object result = generateItemStack.invoke(null, level, player, null);
            return (ItemStack) result;
        } catch (Exception e) {
            // Log could be added
            return null;
        }
    }

    public static ItemStack applyEliteLevel(ItemStack item, int level) {
        if (item == null) return item;
        if (!isAvailable()) return item;
        try {
            org.bukkit.plugin.Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin("EliteMobs");
            ClassLoader cl = p != null ? p.getClass().getClassLoader() : null;
            Class<?> eliteItemManager = Class.forName("com.magmaguy.elitemobs.api.utils.EliteItemManager", true, cl);
            Method setLevel = eliteItemManager.getMethod("setEliteLevel", ItemStack.class, int.class);
            setLevel.invoke(null, item, level);
            return item;
        } catch (Exception e) {
            return item;
        }
    }
}
