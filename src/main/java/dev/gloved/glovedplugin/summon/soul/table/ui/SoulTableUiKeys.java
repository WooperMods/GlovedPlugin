package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Central PDC keys and action helpers for Soul Table screens. */
public final class SoulTableUiKeys {

    public static final String ACTION_BACK_HUB = "back_hub";
    public static final String ACTION_CLOSE = "close";
    public static final String ACTION_GUIDE = "guide";
    public static final String ACTION_PREV = "prev";
    public static final String ACTION_NEXT = "next";
    public static final String ACTION_CLEAR = "clear";
    public static final String ACTION_CONFIRM = "confirm";

    public static final String ACTION_OPEN_COMBINE = "open_combine";
    public static final String ACTION_OPEN_ENHANCE = "open_enhance";
    public static final String ACTION_OPEN_REFORGE = "open_reforge";
    public static final String ACTION_OPEN_SALVAGE = "open_salvage";
    public static final String ACTION_OPEN_CONDENSE = "open_condense";
    public static final String ACTION_OPEN_INFUSION = "open_infusion";

    public static final String ACTION_RECIPES = "recipes";
    public static final String ACTION_RECIPE_DETAIL = "recipe:";

    // Condense
    public static final String ACTION_CONDENSE = "condense";
    public static final String ACTION_DECONDENSE = "decondense";
    public static final String ACTION_AMOUNT = "amount:"; // prefix + units

    // Infusion
    public static final String ACTION_MODE_ADD = "mode_add";
    public static final String ACTION_MODE_REPLACE = "mode_replace";
    public static final String ACTION_SELECT_TRAIT = "trait:"; // prefix + traitId
    public static final String ACTION_SELECT_REPLACE_SLOT = "slot:"; // prefix + traitId

    private SoulTableUiKeys() {
    }

    public static NamespacedKey soulIdKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_table_soul_id");
    }

    public static NamespacedKey actionKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_table_action");
    }

    public static void tagAction(ItemMeta meta, GlovedPlugin plugin, String action) {
        if (meta == null || plugin == null || action == null) return;
        meta.getPersistentDataContainer().set(actionKey(plugin), PersistentDataType.STRING, action);
    }

    public static void tagSoul(ItemMeta meta, GlovedPlugin plugin, UUID soulId) {
        if (meta == null || plugin == null || soulId == null) return;
        meta.getPersistentDataContainer().set(soulIdKey(plugin), PersistentDataType.STRING, soulId.toString());
    }

    public static String readAction(GlovedPlugin plugin, ItemStack item) {
        try {
            ItemMeta meta = item != null ? item.getItemMeta() : null;
            return meta != null ? meta.getPersistentDataContainer().get(actionKey(plugin), PersistentDataType.STRING) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static UUID readSoulId(GlovedPlugin plugin, ItemStack item) {
        try {
            ItemMeta meta = item != null ? item.getItemMeta() : null;
            if (meta == null) return null;
            String raw = meta.getPersistentDataContainer().get(soulIdKey(plugin), PersistentDataType.STRING);
            return raw != null && !raw.isBlank() ? UUID.fromString(raw) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
