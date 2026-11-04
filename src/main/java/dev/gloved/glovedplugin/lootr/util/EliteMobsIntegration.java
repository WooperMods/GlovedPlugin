package dev.gloved.glovedplugin.lootr.util;

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
        if (!eliteMobsAvailable) return 1;
        // Try PlaceholderAPI fallback
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
        // Reflection fallback attempt
        try {
            // Attempt to get PlayerData via EliteMobs main instance
            Class<?> eliteMobsMain = Class.forName("com.magmaguy.elitemobs.EliteMobs");
            Method getPlugin = eliteMobsMain.getMethod("getInstance");
            Object instance = getPlugin.invoke(null);
            // Simplified; real path requires PlayerDataManager
            // Fallback to 1
        } catch (Exception ignored) {}
        return 1;
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
