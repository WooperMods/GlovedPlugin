package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ShapedRecipe;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers the 3x3 Soul Table crafting recipe from {@code souls.table.recipe.*}. */
public final class SoulTableRecipeManager implements Listener {

    private final GlovedPlugin plugin;
    private final NamespacedKey key;

    public SoulTableRecipeManager(GlovedPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "soul_table");
    }

    public NamespacedKey key() {
        return key;
    }

    public void registerRecipes() {
        // Always remove first so /gloved reload cannot stack duplicate recipe instances.
        Bukkit.removeRecipe(key);

        SoulTableConfig cfg = config();
        if (cfg == null || !cfg.isRecipeEnabled()) return;

        List<String> pattern = cfg.getRecipePattern();
        if (pattern == null || pattern.size() != 3) {
            plugin.getLogger().warning("[SoulTable] Recipe pattern must have exactly 3 rows; skipping registration.");
            return;
        }
        for (String row : pattern) {
            if (row == null || row.length() != 3) {
                plugin.getLogger().warning("[SoulTable] Every recipe row must be 3 characters; skipping registration.");
                return;
            }
        }

        Map<Character, Material> ingredients = cfg.getRecipeIngredients();
        Set<Character> used = new LinkedHashSet<>();
        for (String row : pattern) {
            for (char c : row.toCharArray()) {
                if (c != ' ') used.add(c);
            }
        }
        for (char c : used) {
            if (ingredients.get(c) == null) {
                plugin.getLogger().warning("[SoulTable] Recipe key '" + c + "' has no ingredient; skipping registration.");
                return;
            }
        }

        ShapedRecipe recipe = new ShapedRecipe(key, SoulTableItem.create(plugin));
        recipe.shape(pattern.toArray(new String[0]));
        for (char c : used) recipe.setIngredient(c, ingredients.get(c));

        try {
            Bukkit.addRecipe(recipe);
            plugin.getLogger().info("[SoulTable] Registered soul table recipe");
            for (Player player : Bukkit.getOnlinePlayers()) discover(player);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[SoulTable] Could not register recipe: " + e.getMessage());
        }
    }

    public void discover(Player player) {
        if (player == null) return;
        try {
            player.discoverRecipes(List.of(key));
        } catch (Throwable ignored) {
            // Recipe discovery is cosmetic; crafting still works.
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        SoulTableConfig cfg = config();
        if (cfg == null || !cfg.isRecipeEnabled()) return;
        discover(event.getPlayer());
    }

    private SoulTableConfig config() {
        var summon = plugin.getSummonConfig();
        return summon != null ? summon.getSoulTableConfig() : null;
    }
}
