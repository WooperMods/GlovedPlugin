package dev.gloved.glovedplugin.damagehologram.provider;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface HologramProvider {
    /** @return true if this provider can be used (plugin/API available). */
    boolean isAvailable();

    /** Create and schedule deletion of a damage hologram matching AuraSkills behavior. */
    void createHologram(Location location, String text, Player player);

    /** Provider name for logging/debug. */
    String name();

    /** Optional cleanup hook. */
    default void shutdown() {}
}
