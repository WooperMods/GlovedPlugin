package dev.gloved.glovedplugin.summon.soul.trap;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

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
        ItemStack result = buildTrapItem(tier);
        result.setAmount(4);

        if ("snare".equals(tier.id)) {
            ShapelessRecipe recipe = new ShapelessRecipe(key, result);
            // Add all ingredients (shapeless = any order)
            for (SoulTrapTier.MaterialIngredient ing : tier.ingredients) {
                recipe.addIngredient(ing.material);
            }
            for (SoulTrapTier.MaterialIngredient ing : tier.extraIngredients) {
                recipe.addIngredient(ing.material);
            }
            Bukkit.addRecipe(recipe);
        } else {
            ShapedRecipe recipe = createShapedRecipe(key, result, tier);
            if (recipe == null) {
                plugin.getLogger().warning("[Summon] No shaped recipe defined for trap tier: " + tier.id);
                return;
            }
            Bukkit.addRecipe(recipe);
        }

        plugin.getLogger().info("[Summon] Registered trap recipe: " + tier.id + " -> " + tier.displayName);
    }

    /**
     * 3x3 (9-slot) shaped recipes for traps above Snare, themed by rarity and
     * increasing in cost/difficulty.
     */
    private ShapedRecipe createShapedRecipe(NamespacedKey key, ItemStack result, SoulTrapTier tier) {
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        switch (tier.id) {
            case "trap" -> {
                // Uncommon: simple soul-binding vessel (easy 3x3 step above Snare)
                recipe.shape("SSS", "ABA", "SSS");
                recipe.setIngredient('S', Material.SOUL_SAND);
                recipe.setIngredient('A', Material.ARROW);
                recipe.setIngredient('B', Material.GLASS_BOTTLE);
            }
            case "cage" -> {
                // Rare: reinforced cage lit with soul lanterns and an amethyst focus
                recipe.shape("ILI", "IAI", "ILI");
                recipe.setIngredient('I', Material.IRON_BARS);
                recipe.setIngredient('L', Material.SOUL_LANTERN);
                recipe.setIngredient('A', Material.AMETHYST_SHARD);
            }
            case "chalice" -> {
                // Epic: Nether-forged chalice requiring rarer soul materials
                recipe.shape("COC", "ATA", "COC");
                recipe.setIngredient('C', Material.CRYING_OBSIDIAN);
                recipe.setIngredient('O', Material.GOLD_INGOT);
                recipe.setIngredient('A', Material.AMETHYST_SHARD);
                recipe.setIngredient('T', Material.GHAST_TEAR);
            }
            case "relic" -> {
                // Legendary: diamond lattice bound by echo shards and bottled experience
                recipe.shape("DED", "EBE", "DED");
                recipe.setIngredient('D', Material.DIAMOND);
                recipe.setIngredient('E', Material.ECHO_SHARD);
                recipe.setIngredient('B', Material.EXPERIENCE_BOTTLE);
            }
            case "prism" -> {
                // Mythic: netherite prism resonating with echo shards and dragon's breath
                recipe.shape("NEN", "EDE", "NEN");
                recipe.setIngredient('N', Material.NETHERITE_INGOT);
                recipe.setIngredient('E', Material.ECHO_SHARD);
                recipe.setIngredient('D', Material.DRAGON_BREATH);
            }
            default -> {
                return null;
            }
        }
        return recipe;
    }

    /** Build the trap ItemStack with custom name, lore, and PDC tag. */
    public static ItemStack createTrapItem(SoulTrapTier tier) {
        return buildTrapItem(tier);
    }

    /** Build the trap ItemStack with custom name, lore, and PDC tag. */
    private static ItemStack buildTrapItem(SoulTrapTier tier) {
        ItemStack item = new ItemStack(Material.POTION, 1);
        var meta = item.getItemMeta();
        if (meta == null) return item;

        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer serializer =
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
                        .character('&')
                        .hexColors()
                        .useUnusualXRepeatedCharacterHexFormat()
                        .build();

        // Custom display name with color codes, no italics
        net.kyori.adventure.text.Component displayName;
        if ("prism".equals(tier.id)) {
            displayName = serializer.deserialize("&c&lSoul Prism")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        } else {
            displayName = serializer.deserialize(tier.displayName)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
        }
        meta.displayName(displayName);

        // Lore describing the trap
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.empty());
        lore.add(serializer.deserialize(
                "§7Capture a mob's soul without killing it.")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(serializer.deserialize(soulQualityLine(tier))
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

        // Color the potion to match the soul rarity this trap tends to capture
        if (meta instanceof PotionMeta potionMeta) {
            potionMeta.setColor(potionColorForTier(tier));
            try {
                potionMeta.setBasePotionType(PotionType.WATER);
            } catch (Throwable ignored) {
                // Fallback for API differences; custom color is still applied
            }
        }

        // Hide vanilla potion tooltip text like "No Effects"
        try {
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        } catch (Throwable ignored) {}
        try {
            meta.addItemFlags(ItemFlag.valueOf("HIDE_POTION_EFFECTS"));
        } catch (Throwable ignored) {}

        // Allow stacking up to 64 (potions default to stack of 1)
        meta.setMaxStackSize(64);

        item.setItemMeta(meta);
        return item;
    }

    private static Color potionColorForTier(SoulTrapTier tier) {
        if (tier == null) return Color.WHITE;
        return switch (tier.id) {
            case "snare" -> Color.fromRGB(255, 255, 255);   // Common (§f)
            case "trap" -> Color.fromRGB(85, 255, 85);      // Uncommon (§a)
            case "cage" -> Color.fromRGB(85, 85, 255);      // Rare (§9)
            case "chalice" -> Color.fromRGB(170, 0, 170);   // Epic (§5)
            case "relic" -> Color.fromRGB(255, 170, 0);     // Legendary (§6)
            case "prism" -> Color.fromRGB(255, 85, 85);     // Mythic (§c red)
            default -> Color.WHITE;
        };
    }

    private static String soulQualityLine(SoulTrapTier tier) {
        return switch (tier.id) {
            case "snare" -> "§7Can draw out even the most ordinary souls.";
            case "trap" -> "§7Better suited to drawing out finer souls.";
            case "cage" -> "§7Tends to capture stronger, rarer souls.";
            case "chalice" -> "§7Attuned to souls of great power.";
            case "relic" -> "§7Resonates with truly legendary souls.";
            case "prism" -> "§7Draws forth only the most mythic souls.";
            default -> "§7Draws out souls of varying quality.";
        };
    }
}
