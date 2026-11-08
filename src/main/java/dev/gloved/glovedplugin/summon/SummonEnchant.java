package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class SummonEnchant {
    private static final NamespacedKey KEY = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_profile");

    public static String getProfile(ItemStack item) {
        if (item == null) {
            GlovedPlugin.logDebug("[SummonEnchant] Item null");
            return null;
        }
        if (!item.hasItemMeta()) {
            GlovedPlugin.logDebug("[SummonEnchant] Item has no meta: " + item.getType());
            return null;
        }
        var meta = item.getItemMeta();
        PersistentDataContainer c = meta.getPersistentDataContainer();
        GlovedPlugin.logDebug("[SummonEnchant] Inspecting item " + item.getType() + " with " + c.getKeys().size() + " PDC keys, lore present=" + meta.hasLore());
        if (meta.hasLore()) {
            int i = 0;
            for (String l : meta.getLore()) {
                GlovedPlugin.logDebug("[SummonEnchant] Lore[" + i++ + "]: " + l);
            }
        }
        // Detection rules changed (enchant-name match, no PDC fallback). Always detect from lore
        // so stale/wrong cache entries from older versions cannot keep triggering.
        String detected = detectEliteMobsSummon(item, meta);
        GlovedPlugin.logDebug("[SummonEnchant] detectEliteMobsSummon result: " + detected + " for item " + item.getType());
        String cached = c.has(KEY, PersistentDataType.STRING) ? c.get(KEY, PersistentDataType.STRING) : null;
        if (detected != null) {
            if (!detected.equals(cached)) {
                c.set(KEY, PersistentDataType.STRING, detected);
                item.setItemMeta(meta);
                GlovedPlugin.logDebug("[SummonEnchant] Persisted profile " + detected);
            }
            return detected;
        }
        if (cached != null) {
            c.remove(KEY);
            item.setItemMeta(meta);
            GlovedPlugin.logDebug("[SummonEnchant] Cleared stale cached profile: " + cached);
        }
        GlovedPlugin.logDebug("[SummonEnchant] No profile detected for item " + item.getType());
        return null;
    }

    private static String detectEliteMobsSummon(ItemStack item, ItemMeta meta) {
        try {
            if (!meta.hasLore() || meta.getLore() == null) {
                GlovedPlugin.logDebug("[SummonEnchant] No lore on item " + item.getType());
                return null;
            }
            GlovedPlugin plugin = GlovedPlugin.getPlugin(GlovedPlugin.class);
            SummonConfig cfg = plugin != null ? plugin.getSummonConfig() : null;
            if (cfg == null) return null;

            // Build stripped lore once; required: a profile's enchant-name must appear in lore
            // (formatting stripped) for that profile to trigger.
            java.util.List<String> strippedLore = new java.util.ArrayList<>();
            for (String line : meta.getLore()) {
                strippedLore.add(stripFormatting(line));
            }
            // Deterministic: check profiles sorted by key; prefer longest matching enchant name to avoid substring ambiguity.
            java.util.List<SummonProfile> ordered = new java.util.ArrayList<>(cfg.getProfiles().values());
            ordered.sort((a, b) -> {
                String ak = a.name != null ? a.name : "";
                String bk = b.name != null ? b.name : "";
                return ak.compareTo(bk);
            });
            String bestName = null;
            int bestLen = -1;
            String bestNeedle = null;
            for (SummonProfile profile : ordered) {
                String enchantName = profile.enchantName;
                if (enchantName == null || enchantName.isBlank()) continue;
                String needle = stripFormatting(enchantName).toLowerCase();
                boolean matched = false;
                for (String line : strippedLore) {
                    if (line.toLowerCase().contains(needle)) { matched = true; break; }
                }
                if (matched && needle.length() > bestLen) {
                    bestName = profile.name;
                    bestLen = needle.length();
                    bestNeedle = enchantName;
                }
            }
            if (bestName != null) {
                GlovedPlugin.logDebug("[SummonEnchant] Lore matched enchant-name \"" + bestNeedle
                        + "\" -> profile " + bestName + " for item " + item.getType());
                return bestName;
            }
            GlovedPlugin.logDebug("[SummonEnchant] No configured enchant-name matched lore for item " + item.getType());
        } catch (Exception e) {
            GlovedPlugin.logDebug("[SummonEnchant] Exception during detection: " + e);
            e.printStackTrace();
        }
        return null;
    }

    private static String stripFormatting(String s) {
        if (s == null) return "";
        // Strip legacy &/§ color codes and common hex forms like &#RRGGBB / <#RRGGBB>.
        return s.replaceAll("(?i)[&§][0-9A-FK-ORX]", "")
                .replaceAll("(?i)[&§]#[0-9A-F]{6}", "")
                .replaceAll("(?i)<#[0-9A-F]{6}>", "");
    }

    private static boolean isKnownProfile(String profile) {
        try {
            GlovedPlugin plugin = GlovedPlugin.getPlugin(GlovedPlugin.class);
            SummonConfig cfg = plugin != null ? plugin.getSummonConfig() : null;
            return cfg != null && cfg.getProfile(profile) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValid(ItemStack item) {
        return getProfile(item) != null;
    }

    public static void clearProfile(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return;
        var meta = item.getItemMeta();
        var c = meta.getPersistentDataContainer();
        if (c.has(KEY, PersistentDataType.STRING)) {
            c.remove(KEY);
            item.setItemMeta(meta);
            GlovedPlugin.logDebug("[SummonEnchant] Cleared cached profile");
        }
    }
}
