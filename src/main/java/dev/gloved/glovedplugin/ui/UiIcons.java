package dev.gloved.glovedplugin.ui;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/**
 * Custom UI icon item-model helpers for inventory GUIs.
 *
 * <p>Models/textures are provided by {@link UiPackService} (GlovedUI resource pack).</p>
 */
public final class UiIcons {

    public static final String NS = "gloved";

    // Navigation
    public static final String ARROW_LEFT = "ui/arrow_left";
    public static final String ARROW_RIGHT = "ui/arrow_right";
    public static final String ARROW_LEFT_DISABLED = "ui/arrow_left_disabled";
    public static final String ARROW_RIGHT_DISABLED = "ui/arrow_right_disabled";
    public static final String BACK = "ui/back";
    public static final String CLOSE = "ui/close";

    // Common / entry
    public static final String HOME = "ui/home";
    public static final String GUIDE = "ui/guide";
    public static final String TEAMS = "ui/teams";
    public static final String VAULT = "ui/vault";

    // Soul Table processes
    public static final String COMBINE = "ui/combine";
    public static final String ENHANCE = "ui/enhance";
    public static final String REFORGE = "ui/reforge";
    public static final String SALVAGE = "ui/salvage";
    public static final String CONDENSE = "ui/condense";
    public static final String INFUSION = "ui/infusion";

    private UiIcons() {}

    public static NamespacedKey key(String modelPath) {
        if (modelPath == null || modelPath.isBlank()) throw new IllegalArgumentException("modelPath");
        String normalized = modelPath.trim().toLowerCase(Locale.ROOT);
        return new NamespacedKey(NS, normalized);
    }

    /** Applies a custom item model (assets/gloved/items/<modelPath>.json). Returns the same item for chaining. */
    public static ItemStack apply(ItemStack item, String modelPath) {
        if (item == null || modelPath == null || modelPath.isBlank()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        try {
            meta.setItemModel(key(modelPath));
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // Older API / no custom model support: keep base material.
        }
        return item;
    }

    /** Applies a custom item model when enabled and modelPath is provided. */
    public static ItemStack applyIf(ItemStack item, String modelPath, boolean enabled) {
        return enabled ? apply(item, modelPath) : item;
    }
}
