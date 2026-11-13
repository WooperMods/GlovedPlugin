package dev.gloved.glovedplugin.summon.runtime;

import org.bukkit.inventory.ItemStack;

/**
 * Legacy enchant/lore-based summon trigger detection.
 * Retired in the fully-dynamic soul system (summoning no longer depends on gear enchants).
 * Kept as a stub for source compatibility during transition.
 */
@Deprecated(forRemoval = false)
public final class SummonEnchant {
    private SummonEnchant() {}

    /** @return always null (no enchant/profile mapping). */
    public static String getProfile(ItemStack item) {
        return null;
    }

    public static boolean isValid(ItemStack item) {
        return false;
    }

    public static void clearProfile(ItemStack item) {
        // no-op
    }
}
