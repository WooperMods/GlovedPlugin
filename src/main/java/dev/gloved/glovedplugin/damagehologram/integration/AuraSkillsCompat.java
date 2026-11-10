package dev.gloved.glovedplugin.damagehologram.integration;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/**
 * Compatibility helpers for AuraSkills (crit metadata and scaling value).
 */
public final class AuraSkillsCompat {

    private static boolean scalingWarned;

    private AuraSkillsCompat() {}

    public static boolean isCritical(Player player) {
        if (player == null) return false;
        try {
            return player.hasMetadata("skillsCritical");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Returns Traits.HP.optionDouble("action_bar_scaling") when available.
     * On failure, logs once and returns 1.0.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static double getHpActionBarScaling() {
        try {
            Class<?> traitsClass = Class.forName("dev.aurelium.auraskills.api.trait.Traits");
            Object hp = Enum.valueOf((Class<Enum>) traitsClass.asSubclass(Enum.class), "HP");
            Method m = traitsClass.getMethod("optionDouble", String.class);
            Object v = m.invoke(hp, "action_bar_scaling");
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        } catch (Throwable t) {
            warnScalingOnce(t);
            return 1.0;
        }
        warnScalingOnce(null);
        return 1.0;
    }

    private static void warnScalingOnce(Throwable t) {
        if (scalingWarned) return;
        scalingWarned = true;
        if (t != null) {
            GlovedPlugin.logWarning("[DamageHologram] AuraSkills scaling lookup failed (Traits.HP.action_bar_scaling), using scale=1.0: " + t);
        } else {
            GlovedPlugin.logWarning("[DamageHologram] AuraSkills scaling value unavailable, using scale=1.0");
        }
    }
}
