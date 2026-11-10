package dev.gloved.glovedplugin.summon.soul.trap;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Registers shapeless crafting recipes for all soul trap tiers.
 * Each trap item carries a PDC tag {@code gloved:summon_trap} with the tier ID
 * so listeners can identify which trap was used.
 */
public class SoulTrapRecipeManager {
    private final GlovedPlugin plugin;
    private final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer serializer =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();

    public SoulTrapRecipeManager(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerRecipes() {
        // Remove any existing trap recipes first (reload-safe)
        for (SoulTrapTier tier : SoulTrapTier.all()) {
            NamespacedKey key = new NamespacedKey(plugin, "soul_trap_" + tier.id);
            Bukkit.removeRecipe(key);
        }

        for (SoulTrapTier tier : SoulTrapTier.all()) {
            registerTierRecipe(tier);
        }
        plugin.getLogger().info("[Summon] Registered " + SoulTrapTier.all().size() + " soul trap recipes");
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
        lore.add(serializer.deserialize(
                String.format("§7Success: §f%d%%", (int) Math.round(tier.successChance * 100)))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(net.kyori.adventure.text.Component.empty());
        lore.add(serializer.deserialize(
                "§7Right-click a living mob to attempt capture.")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);

        // PDC tag so listeners can identify the trap tier
        var pdc = meta.getPersistentDataContainer();
        var key = new org.bukkit.NamespacedKey("gloved", "summon_trap");
        pdc.set(key, PersistentDataType.STRING, tier.id);

        item.setItemMeta(meta);
        return item;
    }
}
