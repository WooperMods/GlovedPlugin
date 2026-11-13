package dev.gloved.glovedplugin.combat.targeting;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Optional FreeMinecraftModels hook. Reflection-only so FMM stays a
 * soft-depend: when installed, plays the {@code attack} animation on the
 * player's modelled weapon/disguise each swing. No-op otherwise.
 *
 * <p>Phase 3: replace with the typed {@code ModeledEntity#playAnimation}
 * API (compileOnly {@code com.magmaguy:FreeMinecraftModels}) once server
 * pack + models are authored.
 */
public final class FmmBridge {

    private static volatile Boolean available;

    private FmmBridge() {}

    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        boolean present = Bukkit.getPluginManager().getPlugin("FreeMinecraftModels") != null;
        try {
            Class.forName("com.magmaguy.freeminecraftmodels.api.ModeledEntity");
            present = true;
        } catch (ClassNotFoundException e) {
            // Plugin name present but API relocated — stay silent, stay safe.
        }
        available = present;
        return present;
    }

    public static void invalidate() {
        available = null;
    }

    public static void onSwing(Player player, boolean offhand) {
        if (!isAvailable()) return;
        try {
            Class<?> api = Class.forName("com.magmaguy.freeminecraftmodels.api.ModeledEntity");
            var method = api.getMethod("playAnimation",
                    org.bukkit.entity.Entity.class, String.class, boolean.class, boolean.class);
            method.invoke(null, player, "attack", true, false);
        } catch (ReflectiveOperationException | IllegalArgumentException e) {
            available = false;
        }
    }
}
