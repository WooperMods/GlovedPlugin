package dev.gloved.glovedplugin.lootr.integration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import dev.gloved.glovedplugin.core.GlovedPlugin;

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
            if (p == null || !p.isEnabled()) {
                GlovedPlugin.logDebug("EliteMobsIntegration.isAvailable false: plugin missing or disabled p=" + p);
                return false;
            }
            // Verify class is loadable via the plugin's classloader
            Class.forName("com.magmaguy.elitemobs.playerdata.database.PlayerData", true, p.getClass().getClassLoader());
            return true;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("EliteMobsIntegration.isAvailable false: throwable " + t);
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

    /**
     * Applies EliteMobs' own per-item level jitter ({@code LootTables.setItemTier}),
     * the same roll every mob drop uses: small level-scaled chance of +1, 10% of
     * −2, 40% of −1, 50% unchanged, floored at 0. Chest loot then varies around
     * the player's combat level exactly like mob drops.
     */
    public static int jitterItemLevel(int level) {
        if (!isAvailable()) return level;
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null) return level;
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> lootTables = Class.forName("com.magmaguy.elitemobs.items.LootTables", true, cl);
            Method setItemTier = lootTables.getMethod("setItemTier", int.class);
            Object result = setItemTier.invoke(null, level);
            return (int) (double) (Double) result;
        } catch (Exception e) {
            return level;
        }
    }

    public static int scoreItemQuality(ItemStack stack) {
        if (stack == null) return 0;
        try {
            if (!stack.hasItemMeta()) return 0;
            var meta = stack.getItemMeta();
            int score = 0;
            // Vanilla enchantments as a proxy
            score += meta.getEnchants().size() * 10;
            // Lore length is a proxy for EliteMobs custom enchantments
            if (meta.hasLore()) {
                score += meta.getLore().size();
            }
            // Prefer rarer materials as a weak signal
            score += stack.getType().getMaxStackSize() > 1 ? 0 : 1;
            return score;
        } catch (Exception e) {
            return 0;
        }
    }

    public static ItemStack generateRandomLootBest(Player player, int level, int attempts) {
        if (!isAvailable()) return null;
        ItemStack best = null;
        int bestScore = -1;
        for (int i = 0; i < attempts; i++) {
            ItemStack candidate = generateRandomLoot(player, level);
            int score = scoreItemQuality(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public static ItemStack generateRandomLoot(Player player, int level) {
        if (!isAvailable()) {
            GlovedPlugin.logDebug("EliteMobsIntegration.generateRandomLoot not available for player " + player.getName() + " level " + level);
            return null;
        }
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null) return null;
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> lootTables = Class.forName("com.magmaguy.elitemobs.items.LootTables", true, cl);
            Method generateItemStack = lootTables.getMethod("generateItemStack", int.class, Player.class, Class.forName("com.magmaguy.elitemobs.mobconstructor.EliteEntity", true, cl));
            Object result = generateItemStack.invoke(null, level, player, null);
            ItemStack stack = (ItemStack) result;
            GlovedPlugin.logDebug("EliteMobsIntegration.generateRandomLoot result=" + (stack != null ? stack.getType() : "null") + " for player " + player.getName() + " level " + level);
            return stack;
        } catch (Exception e) {
            GlovedPlugin.logDebug("EliteMobsIntegration.generateRandomLoot exception " + e);
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
