package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.guide.SoulGuideItem;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableItem;
import dev.gloved.glovedplugin.summon.soul.table.item.CondensedSoulEssenceItem;
import dev.gloved.glovedplugin.summon.soul.table.item.SoulCatalystItem;
import dev.gloved.glovedplugin.summon.soul.trap.SoulTrapRecipeManager;
import dev.gloved.glovedplugin.summon.soul.trap.SoulTrapTier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Describes a craftable soul-related item and its recipe, for the in-game
 * recipe viewer. Layout is a 3x3 grid (null = empty slot) plus a shapeless
 * flag. Ingredient names are the Material name shown to the player.
 */
public final class SoulTableRecipe {

    public final String id;
    public final String name;
    public final boolean shapeless;
    public final Material[][] grid; // 3x3, null = empty
    public final List<String> shapelessIngredients;

    private SoulTableRecipe(String id, String name, boolean shapeless,
                            Material[][] grid, List<String> shapelessIngredients) {
        this.id = id;
        this.name = name;
        this.shapeless = shapeless;
        this.grid = grid;
        this.shapelessIngredients = shapelessIngredients;
    }

    public static SoulTableRecipe shaped(String id, String name, Material[][] grid) {
        return new SoulTableRecipe(id, name, false, grid, null);
    }

    public static SoulTableRecipe shapeless(String id, String name, List<String> ingredients) {
        return new SoulTableRecipe(id, name, true, null, ingredients);
    }

    /** Creates the actual custom item stack (with PDC tags, name, lore, model) as it appears in-game. */
    public ItemStack createItem(GlovedPlugin plugin, SoulTableConfig cfg) {
        if (plugin == null) return new ItemStack(Material.AIR);

        return switch (id) {
            case "soul_guide" -> SoulGuideItem.create(plugin);
            case "soul_table" -> SoulTableItem.create(plugin);
            case "soul_catalyst" -> SoulCatalystItem.create(plugin, 1, cfg);
            case "soul_trap_snare" -> SoulTrapRecipeManager.createTrapItem(SoulTrapTier.snare());
            case "soul_trap_trap" -> SoulTrapRecipeManager.createTrapItem(SoulTrapTier.trap());
            case "soul_trap_cage" -> SoulTrapRecipeManager.createTrapItem(SoulTrapTier.cage());
            case "soul_trap_chalice" -> SoulTrapRecipeManager.createTrapItem(SoulTrapTier.chalice());
            case "soul_trap_relic" -> SoulTrapRecipeManager.createTrapItem(SoulTrapTier.relic());
            case "soul_trap_prism" -> SoulTrapRecipeManager.createTrapItem(SoulTrapTier.prism());
            case "condensed_essence" -> CondensedSoulEssenceItem.create(plugin, 1, cfg);
            default -> new ItemStack(Material.AIR);
        };
    }

    /** All craftable soul items and their recipes (matches config defaults). */
    public static List<SoulTableRecipe> all() {
        List<SoulTableRecipe> recipes = new ArrayList<>();

        // Soul Guide — shapeless: Book + Soul Sand
        recipes.add(shapeless("soul_guide", "Soul Guide",
                List.of("Book", "Soul Sand")));

        // Soul Table — shaped 3x3: SSS / PLP / ONO
        Material[][] soulTable = {
                {Material.SOUL_SAND, Material.SOUL_SAND, Material.SOUL_SAND},
                {Material.COBBLESTONE, Material.SOUL_TORCH, Material.COBBLESTONE},
                {Material.OAK_PLANKS, Material.IRON_INGOT, Material.OAK_PLANKS},
        };
        recipes.add(shaped("soul_table", "Soul Table", soulTable));

        // Soul Catalyst — shaped 3x3: NEN / ECE / NEN
        Material[][] catalyst = {
                {Material.NETHERITE_INGOT, Material.ECHO_SHARD, Material.NETHERITE_INGOT},
                {Material.ECHO_SHARD, Material.AMETHYST_SHARD, Material.ECHO_SHARD},
                {Material.NETHERITE_INGOT, Material.ECHO_SHARD, Material.NETHERITE_INGOT},
        };
        recipes.add(shaped("soul_catalyst", "Soul Catalyst", catalyst));

        // Soul Traps — Snare (shapeless), then shaped tiers
        recipes.add(shapeless("soul_trap_snare", "Soul Snare",
                List.of("Glass Bottle", "Soul Sand", "Spider Eye")));

        Material[][] trap = {
                {Material.IRON_INGOT, Material.SOUL_SAND, Material.IRON_INGOT},
                {Material.SOUL_SAND, Material.CHEST, Material.SOUL_SAND},
                {Material.IRON_INGOT, Material.SOUL_SAND, Material.IRON_INGOT},
        };
        recipes.add(shaped("soul_trap_trap", "Soul Trap", trap));

        Material[][] cage = {
                {Material.IRON_BARS, Material.SOUL_SAND, Material.IRON_BARS},
                {Material.SOUL_SAND, Material.CHEST, Material.SOUL_SAND},
                {Material.IRON_BARS, Material.SOUL_SAND, Material.IRON_BARS},
        };
        recipes.add(shaped("soul_trap_cage", "Soul Cage", cage));

        Material[][] chalice = {
                {Material.PRISMARINE_SHARD, Material.AMETHYST_SHARD, Material.PRISMARINE_SHARD},
                {Material.AMETHYST_SHARD, Material.CHEST, Material.AMETHYST_SHARD},
                {Material.PRISMARINE_SHARD, Material.AMETHYST_SHARD, Material.PRISMARINE_SHARD},
        };
        recipes.add(shaped("soul_trap_chalice", "Soul Chalice", chalice));

        Material[][] relic = {
                {Material.END_STONE, Material.RESPAWN_ANCHOR, Material.END_STONE},
                {Material.RESPAWN_ANCHOR, Material.CHEST, Material.RESPAWN_ANCHOR},
                {Material.END_STONE, Material.RESPAWN_ANCHOR, Material.END_STONE},
        };
        recipes.add(shaped("soul_trap_relic", "Soul Relic", relic));

        Material[][] prism = {
                {Material.AMETHYST_BLOCK, Material.END_CRYSTAL, Material.AMETHYST_BLOCK},
                {Material.END_CRYSTAL, Material.CHEST, Material.END_CRYSTAL},
                {Material.AMETHYST_BLOCK, Material.END_CRYSTAL, Material.AMETHYST_BLOCK},
        };
        recipes.add(shaped("soul_trap_prism", "Soul Prism", prism));

        return recipes;
    }

    public static SoulTableRecipe byId(String id) {
        for (SoulTableRecipe r : all()) {
            if (r.id.equals(id)) return r;
        }
        return null;
    }

    /** Player-friendly material display name. */
    public static String materialName(Material m) {
        if (m == null) return "—";
        String n = m.name().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        boolean cap = true;
        for (char c : n.toCharArray()) {
            if (c == '_') {
                cap = true;
                out.append(' ');
            } else {
                out.append(cap ? Character.toUpperCase(c) : c);
                cap = false;
            }
        }
        return out.toString();
    }

    public Component nameComponent() {
        return Component.text(name, NamedTextColor.YELLOW, TextDecoration.BOLD);
    }
}
