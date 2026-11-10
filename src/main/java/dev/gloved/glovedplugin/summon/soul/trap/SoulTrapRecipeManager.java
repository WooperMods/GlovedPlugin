package dev.gloved.glovedplugin.summon.soul.trap;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Registers shapeless crafting recipes for all soul trap tiers.
 * Each trap item carries a PDC tag {@code gloved:summon_trap} with the tier ID
 * so listeners can identify which trap was used.
 */
public class SoulTrapRecipeManager implements Listener {
    private final GlovedPlugin plugin;

    public SoulTrapRecipeManager(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerRecipes() {
        // Remove any existing trap recipes first (reload-safe)
        for (NamespacedKey key : getRecipeKeys()) {
            Bukkit.removeRecipe(key);
        }

        for (SoulTrapTier tier : SoulTrapTier.all()) {
            registerTierRecipe(tier);
        }
        plugin.getLogger().info("[Summon] Registered " + SoulTrapTier.all().size() + " soul trap recipes");

        // Make recipes visible in the recipe book for players already online.
        for (Player player : Bukkit.getOnlinePlayers()) {
            discoverAll(player);
        }
    }

    /** Namespaced keys for all soul trap recipes. */
    public List<NamespacedKey> getRecipeKeys() {
        List<NamespacedKey> keys = new ArrayList<>();
        for (SoulTrapTier tier : SoulTrapTier.all()) {
            keys.add(new NamespacedKey(plugin, "soul_trap_" + tier.id));
        }
        return keys;
    }

    /** Unlock all soul trap recipes in the player's recipe book. */
    public void discoverAll(Player player) {
        if (player == null) return;
        player.discoverRecipes(getRecipeKeys());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        discoverAll(event.getPlayer());
    }

    private void registerTierRecipe(SoulTrapTier tier) {
        NamespacedKey key = new NamespacedKey(plugin, "soul_trap_" + tier.id);
        ShapelessRecipe recipe = new ShapelessRecipe(key, buildTrapItem(tier));

        // Add all ingredients (shapeless = any order)
        for (SoulTrapTier.MaterialIngredient ing : tier.ingredients) {
            recipe.addIngredient(ing.material);
        }
        // Extra ingredients (e.g. ghast tear, dragon breath)
        for (SoulTrapTier.MaterialIngredient ing : tier.extraIngredients) {
            recipe.addIngredient(ing.material);
        }

        Bukkit.addRecipe(recipe);
        plugin.getLogger().info("[Summon] Registered trap recipe: " + tier.id + " -> " + tier.displayName);
    }

    /** Build the trap ItemStack with custom name, lore, and PDC tag. */
    private static ItemStack buildTrapItem(SoulTrapTier tier) {
        ItemStack item = new ItemStack(Material.POTION, 1);
        var meta = item.getItemMeta();
        if (meta == null) return item;

        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer serializer =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();

        // Custom display name with color codes, no italics
        net.kyori.adventure.text.Component displayName = serializer.deserialize(tier.displayName)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        meta.displayName(displayName);

        // Lore describing the trap
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.empty());
        lore.add(serializer.deserialize(
                "§7Capture a mob's soul without killing it.")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(net.kyori.adventure.text.Component.empty());
        lore.add(serializer.deserialize(
                "§7Right-click a living mob to take its soul.")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);

        // PDC tag so listeners can identify the trap tier
        var pdc = meta.getPersistentDataContainer();
        var key = new org.bukkit.NamespacedKey("gloved", "summon_trap");
        pdc.set(key, PersistentDataType.STRING, tier.id);

        // Allow stacking up to 64 (potions default to stack of 1)
        meta.setMaxStackSize(64);

        item.setItemMeta(meta);
        return item;
    }
}
