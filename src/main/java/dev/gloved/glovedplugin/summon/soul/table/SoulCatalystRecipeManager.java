package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.table.item.CondensedSoulEssenceItem;
import dev.gloved.glovedplugin.summon.soul.table.item.SoulCatalystItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers the Soul Catalyst crafting recipe (reload-safe). */
public final class SoulCatalystRecipeManager implements Listener {

    private final GlovedPlugin plugin;
    private final NamespacedKey key;

    public SoulCatalystRecipeManager(GlovedPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "soul_catalyst");
    }

    public NamespacedKey key() {
        return key;
    }

    public void registerRecipes() {
        Bukkit.removeRecipe(key);

        SoulTableConfig cfg = config();
        if (cfg == null || !cfg.isCatalystEnabled() || !cfg.isCatalystRecipeEnabled()) return;

        List<String> pattern = cfg.getCatalystRecipePattern();
        if (pattern == null || pattern.size() != 3) {
            plugin.getLogger().warning("[SoulTable] Catalyst recipe pattern must have exactly 3 rows; skipping registration.");
            return;
        }
        for (String row : pattern) {
            if (row == null || row.length() != 3) {
                plugin.getLogger().warning("[SoulTable] Every catalyst recipe row must be 3 characters; skipping registration.");
                return;
            }
        }

        Map<Character, Material> ingredients = cfg.getCatalystRecipeIngredients();
        Map<Character, String> special = cfg.getCatalystRecipeSpecialIngredients();
        Set<Character> used = new LinkedHashSet<>();
        for (String row : pattern) {
            for (char c : row.toCharArray()) if (c != ' ') used.add(c);
        }
        for (char c : used) {
            if (!ingredients.containsKey(c) && !special.containsKey(c)) {
                plugin.getLogger().warning("[SoulTable] Catalyst recipe key '" + c + "' has no ingredient; skipping registration.");
                return;
            }
        }

        ShapedRecipe recipe = new ShapedRecipe(key, SoulCatalystItem.create(plugin, cfg.getCatalystRecipeAmount(), cfg));
        recipe.shape(pattern.toArray(new String[0]));
        for (char c : used) {
            if (ingredients.containsKey(c)) {
                recipe.setIngredient(c, ingredients.get(c));
            } else if ("CONDENSED_SOUL_ESSENCE".equalsIgnoreCase(special.get(c))) {
                try {
                    recipe.setIngredient(c, new RecipeChoice.ExactChoice(CondensedSoulEssenceItem.create(plugin, 1, cfg)));
                } catch (Throwable t) {
                    // Fallback: match by material only (custom handling could be added via craft listeners if needed).
                    recipe.setIngredient(c, cfg.condensedMaterial());
                    plugin.getLogger().warning("[SoulTable] ExactChoice unavailable for condensed essence; using material fallback: " + t.getMessage());
                }
            }
        }

        try {
            Bukkit.addRecipe(recipe);
            plugin.getLogger().info("[SoulTable] Registered soul catalyst recipe");
            for (Player player : Bukkit.getOnlinePlayers()) discover(player);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[SoulTable] Could not register catalyst recipe: " + e.getMessage());
        }
    }

    public void discover(Player player) {
        if (player == null) return;
        try { player.discoverRecipes(List.of(key)); } catch (Throwable ignored) {}
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        SoulTableConfig cfg = config();
        if (cfg == null || !cfg.isCatalystEnabled() || !cfg.isCatalystRecipeEnabled()) return;
        discover(event.getPlayer());
    }

    private SoulTableConfig config() {
        var summon = plugin.getSummonConfig();
        return summon != null ? summon.getSoulTableConfig() : null;
    }
}
