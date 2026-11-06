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
        if (c.has(KEY, PersistentDataType.STRING)) {
            String cached = c.get(KEY, PersistentDataType.STRING);
            GlovedPlugin.logDebug("[SummonEnchant] Cached profile: " + cached);
            // If cached profile is a known bad marker, fall through to re-detect
            if (!"elitemobsitem".equals(cached)) {
                GlovedPlugin.logDebug("[SummonEnchant] Returning cached profile");
                return cached;
            }
            // Invalidate bad cache
            GlovedPlugin.logDebug("[SummonEnchant] Invalid cached profile, clearing");
            c.remove(KEY);
            item.setItemMeta(meta);
        }
        String profile = detectEliteMobsSummon(item, meta, c);
        GlovedPlugin.logDebug("[SummonEnchant] detectEliteMobsSummon result: " + profile + " for item " + item.getType());
        if (profile != null) {
            c.set(KEY, PersistentDataType.STRING, profile);
            item.setItemMeta(meta);
            GlovedPlugin.logDebug("[SummonEnchant] Persisted profile " + profile);
        } else {
            GlovedPlugin.logDebug("[SummonEnchant] No profile detected for item " + item.getType());
        }
        return profile;
    }

    private static String detectEliteMobsSummon(ItemStack item, ItemMeta meta, PersistentDataContainer c) {
        try {
            // Prefer lore parsing for config-driven summons
            if (meta.hasLore()) {
                for (String line : meta.getLore()) {
                    String lower = line.toLowerCase();
                    if (lower.contains("summon") || lower.contains("spawn")) {
                        GlovedPlugin.logDebug("[SummonEnchant] Checking summon lore line: " + line);
                        String profile = parseProfileFromLore(line);
                        GlovedPlugin.logDebug("[SummonEnchant] Parsed profile from lore: " + profile);
                        if (profile != null) {
                            GlovedPlugin.logDebug("[SummonEnchant] Lore parsed profile: " + profile + " from " + line);
                            return profile;
                        }
                    }
                }
            }
            // Fallback: try PDC enchant keys, but ignore generic markers
            int keyCount = 0;
            for (NamespacedKey nk : c.getKeys()) {
                keyCount++;
                GlovedPlugin.logDebug("[SummonEnchant] PDC key[" + keyCount + "]: " + nk.getNamespace() + ":" + nk.getKey());
                if (!"elitemobs".equals(nk.getNamespace())) continue;
                String key = nk.getKey();
                if ("elitemobsitem".equals(key) || "custom_item_id".equals(key)) {
                    GlovedPlugin.logDebug("[SummonEnchant] Skipping generic elitemobs key: " + key);
                    continue;
                }
                GlovedPlugin.logDebug("[SummonEnchant] Found elitemobs enchant PDC key: " + nk + " -> profile " + key);
                return key;
            }
            GlovedPlugin.logDebug("[SummonEnchant] No usable elitemobs enchant PDC keys found");
        } catch (Exception e) {
            GlovedPlugin.logDebug("[SummonEnchant] Exception during detection: " + e);
            e.printStackTrace();
        }
        return null;
    }

    private static String parseProfileFromLore(String line) {
        String lower = line.toLowerCase();
        // Expect patterns like "can spawn a bee swarm on hit" or "can summon pillager raiders on hit"
        // Also support armor phrasing like "summons bee_swarm when hit" or "spawns ... on damage"
        String[] trigger = {"can summon ", "can spawn ", "summons ", "spawns ", "summon ", "spawn "};
        String[] endMarkers = {" on hit", " when hit", " on damage", " when damaged", "."};
        for (String t : trigger) {
            int idx = lower.indexOf(t);
            if (idx >= 0) {
                int start = idx + t.length();
                int end = line.length();
                for (String m : endMarkers) {
                    int found = lower.indexOf(m, start);
                    if (found >= 0 && found < end) {
                        end = found;
                        break;
                    }
                }
                String raw = line.substring(start, end).trim();
                // Remove leading "a " or "an "
                if (raw.startsWith("a ")) raw = raw.substring(2);
                if (raw.startsWith("an ")) raw = raw.substring(3);
                // Convert to snake_case
                String profile = raw.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim().replaceAll("\\s+", "_");
                if (!profile.isEmpty()) return profile;
            }
        }
        return null;
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
