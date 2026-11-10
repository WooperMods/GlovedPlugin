package dev.gloved.glovedplugin.summon.progression;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Map;

/** Resolves config effect names on modern Paper (Registry.MOB_EFFECT) with legacy aliases. */
public final class PotionEffectResolver {
    private static final Map<String, String> LEGACY = Map.ofEntries(
            Map.entry("SLOW", "slowness"),
            Map.entry("FAST_DIGGING", "haste"),
            Map.entry("SLOW_DIGGING", "mining_fatigue"),
            Map.entry("INCREASE_DAMAGE", "strength"),
            Map.entry("HEAL", "instant_health"),
            Map.entry("HARM", "instant_damage"),
            Map.entry("JUMP", "jump_boost"),
            Map.entry("CONFUSION", "nausea"),
            Map.entry("DAMAGE_RESISTANCE", "resistance")
    );

    private PotionEffectResolver() {}

    public static PotionEffectType resolve(String input) {
        if (input == null) return null;
        String raw = input.trim();
        if (raw.isEmpty()) return null;
        String lower = raw.toLowerCase(Locale.ROOT);
        try {
            NamespacedKey key = NamespacedKey.fromString(lower);
            if (key != null) {
                PotionEffectType type = Registry.MOB_EFFECT.get(key);
                if (type != null) return type;
            }
        } catch (Throwable ignored) {}
        String alias = LEGACY.get(raw.toUpperCase(Locale.ROOT));
        if (alias != null) {
            try {
                PotionEffectType type = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(alias));
                if (type != null) return type;
            } catch (Throwable ignored) {}
        }
        // Final fallback for environments where constant names differ.
        try {
            for (PotionEffectType t : Registry.MOB_EFFECT) {
                if (t != null && t.getKey() != null && t.getKey().getKey().equalsIgnoreCase(lower)) return t;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
