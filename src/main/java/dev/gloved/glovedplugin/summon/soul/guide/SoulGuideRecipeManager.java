package dev.gloved.glovedplugin.summon.soul.guide;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.List;

/** Registers the shapeless Soul Guide crafting recipe (Book + Soul Sand). */
public final class SoulGuideRecipeManager implements Listener {

    private final GlovedPlugin plugin;
    private final NamespacedKey key;

    public SoulGuideRecipeManager(GlovedPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "soul_guide");
    }

    public NamespacedKey key() {
        return key;
    }

    public void registerRecipes() {
        // Always remove first so /gloved reload cannot stack duplicate recipe instances.
        Bukkit.removeRecipe(key);

        ShapelessRecipe recipe = new ShapelessRecipe(key, SoulGuideItem.create(plugin));
        recipe.addIngredient(Material.BOOK);
        recipe.addIngredient(Material.SOUL_SAND);

        try {
            Bukkit.addRecipe(recipe);
            plugin.getLogger().info("[SoulGuide] Registered soul guide recipe");
            for (Player player : Bukkit.getOnlinePlayers()) discover(player);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[SoulGuide] Could not register recipe: " + e.getMessage());
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
        discover(event.getPlayer());
    }
}
