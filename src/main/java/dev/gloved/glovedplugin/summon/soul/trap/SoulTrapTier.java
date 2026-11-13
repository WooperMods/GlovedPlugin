package dev.gloved.glovedplugin.summon.soul.trap;

import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Data for a single soul trap tier: crafting ingredients, success chance,
 * rarity floor/bias, and the debuff applied to the mob on successful capture.
 */
public final class SoulTrapTier {
    public final String id;
    public final String displayName;
    public final List<MaterialIngredient> ingredients;
    public final List<MaterialIngredient> extraIngredients;
    public final double successChance;
    public final SoulRarity rarityFloor;
    public final int rarityBias;
    public final PotionEffectType mobEffectType;
    public final int mobEffectAmplifier;
    public final int mobEffectDurationTicks;
    public final double mobHpDamage;

    private SoulTrapTier(String id, String displayName, List<MaterialIngredient> ingredients,
                         List<MaterialIngredient> extraIngredients, double successChance,
                         SoulRarity rarityFloor, int rarityBias,
                         PotionEffectType mobEffectType, int mobEffectAmplifier,
                         int mobEffectDurationTicks, double mobHpDamage) {
        this.id = id;
        this.displayName = displayName;
        this.ingredients = ingredients;
        this.extraIngredients = extraIngredients;
        this.successChance = successChance;
        this.rarityFloor = rarityFloor;
        this.rarityBias = rarityBias;
        this.mobEffectType = mobEffectType;
        this.mobEffectAmplifier = mobEffectAmplifier;
        this.mobEffectDurationTicks = mobEffectDurationTicks;
        this.mobHpDamage = mobHpDamage;
    }

    public static SoulTrapTier snare() {
        List<MaterialIngredient> ingredients = List.of(
                new MaterialIngredient(Material.SOUL_SAND, 1),
                new MaterialIngredient(Material.GLASS_BOTTLE, 1),
                new MaterialIngredient(Material.STRING, 1)
        );
        return new SoulTrapTier("snare", "&fSoul Snare", ingredients, List.of(),
                0.40, SoulRarity.COMMON, 0,
                PotionEffectType.SLOWNESS, 1, 600, 5.0);
    }

    public static SoulTrapTier trap() {
        List<MaterialIngredient> ingredients = List.of(
                new MaterialIngredient(Material.SOUL_SAND, 1),
                new MaterialIngredient(Material.ARROW, 1),
                new MaterialIngredient(Material.GLASS_BOTTLE, 1)
        );
        return new SoulTrapTier("trap", "&aSoul Trap", ingredients, List.of(),
                0.55, SoulRarity.UNCOMMON, 1,
                PotionEffectType.SLOWNESS, 2, 900, 5.0);
    }

    public static SoulTrapTier cage() {
        List<MaterialIngredient> ingredients = List.of(
                new MaterialIngredient(Material.IRON_BARS, 1),
                new MaterialIngredient(Material.IRON_BARS, 1),
                new MaterialIngredient(Material.IRON_BARS, 1),
                new MaterialIngredient(Material.AMETHYST_SHARD, 1)
        );
        return new SoulTrapTier("cage", "&9Soul Cage", ingredients, List.of(),
                0.70, SoulRarity.RARE, 2,
                PotionEffectType.WEAKNESS, 1, 1200, 5.0);
    }

    public static SoulTrapTier chalice() {
        List<MaterialIngredient> ingredients = List.of(
                new MaterialIngredient(Material.GOLD_INGOT, 1),
                new MaterialIngredient(Material.GOLD_INGOT, 1),
                new MaterialIngredient(Material.GOLD_INGOT, 1),
                new MaterialIngredient(Material.AMETHYST_SHARD, 1)
        );
        List<MaterialIngredient> extra = List.of(
                new MaterialIngredient(Material.GHAST_TEAR, 1)
        );
        return new SoulTrapTier("chalice", "&5Soul Chalice", ingredients, extra,
                0.85, SoulRarity.EPIC, 3,
                PotionEffectType.POISON, 1, 1800, 5.0);
    }

    public static SoulTrapTier relic() {
        List<MaterialIngredient> ingredients = List.of(
                new MaterialIngredient(Material.DIAMOND, 1),
                new MaterialIngredient(Material.DIAMOND, 1),
                new MaterialIngredient(Material.ECHO_SHARD, 1)
        );
        List<MaterialIngredient> extra = List.of(
                new MaterialIngredient(Material.EXPERIENCE_BOTTLE, 1)
        );
        return new SoulTrapTier("relic", "&6Soul Relic", ingredients, extra,
                0.95, SoulRarity.LEGENDARY, 4,
                PotionEffectType.BLINDNESS, 1, 2400, 5.0);
    }

    public static SoulTrapTier prism() {
        List<MaterialIngredient> ingredients = List.of(
                new MaterialIngredient(Material.NETHERITE_INGOT, 1),
                new MaterialIngredient(Material.NETHERITE_INGOT, 1),
                new MaterialIngredient(Material.ECHO_SHARD, 1)
        );
        List<MaterialIngredient> extra = List.of(
                new MaterialIngredient(Material.DRAGON_BREATH, 1)
        );
        return new SoulTrapTier("prism", "&c&lSoul Prism", ingredients, extra,
                1.00, SoulRarity.MYTHIC, 5,
                PotionEffectType.WITHER, 1, 3600, 5.0);
    }

    public static List<SoulTrapTier> all() {
        return List.of(snare(), trap(), cage(), chalice(), relic(), prism());
    }

    public static SoulTrapTier fromId(String id) {
        for (SoulTrapTier tier : all()) {
            if (tier.id.equals(id)) return tier;
        }
        return null;
    }

    public static SoulTrapTier fromItem(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return null;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        var key = new NamespacedKey("gloved", "summon_trap");
        String tierId = pdc.get(key, PersistentDataType.STRING);
        if (tierId == null) return null;
        return fromId(tierId);
    }

    /** A material + count pair for shapeless recipe ingredients. */
    public static final class MaterialIngredient {
        public final Material material;
        public final int count;

        public MaterialIngredient(Material material, int count) {
            this.material = material;
            this.count = count;
        }
    }
}
