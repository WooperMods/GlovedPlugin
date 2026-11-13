package dev.gloved.glovedplugin.enchant;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Applies/removes vanilla and EliteMobs enchantments on held items.
 * EliteMobs integration is EM-native via reflection into EM internals; all EM calls are guarded.
 */
public final class EnchantBridge {
    private EnchantBridge() {}

    public static final class Result {
        public final boolean ok;
        public final String message;
        public Result(boolean ok, String message) { this.ok = ok; this.message = message; }
        public static Result ok(String m) { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    public static boolean eliteMobsPresent() {
        Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
        return p != null && p.isEnabled();
    }

    public static ClassLoader emCl() {
        Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
        return p != null ? p.getClass().getClassLoader() : null;
    }

    public static List<String> suggest(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> out = new LinkedHashSet<>();

        // Vanilla enchantments
        try {
            Registry<Enchantment> reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            for (Enchantment e : reg) {
                addIfMatch(out, e.getKey().getKey(), q);
                addIfMatch(out, e.getKey().toString(), q);
            }
        } catch (Throwable ignored) {
            try {
                for (Enchantment e : Registry.ENCHANTMENT) {
                    addIfMatch(out, e.getKey().getKey(), q);
                }
            } catch (Throwable ignored2) {}
        }

        // EliteMobs config/custom enchantments
        if (eliteMobsPresent()) {
            try {
                ClassLoader cl = emCl();
                Class<?> cfgClass = Class.forName("com.magmaguy.elitemobs.config.enchantments.EnchantmentsConfig", true, cl);
                Method getEnchantments = cfgClass.getMethod("getEnchantments");
                Object mapObj = getEnchantments.invoke(null);
                if (mapObj instanceof Map<?, ?> map) {
                    for (var entry : map.entrySet()) {
                        if (entry.getKey() != null) addIfMatch(out, String.valueOf(entry.getKey()), q);
                        try {
                            Object field = entry.getValue();
                            if (field != null) {
                                Method getName = field.getClass().getMethod("getName");
                                Object name = getName.invoke(field);
                                if (name != null) addIfMatch(out, normalizeToken(String.valueOf(name)), q);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[Enchant] EM EnchantmentsConfig suggest failed: " + t);
            }
            try {
                ClassLoader cl = emCl();
                Class<?> cat = Class.forName("com.magmaguy.elitemobs.items.EliteEnchantmentCatalog", true, cl);
                Method defIds = cat.getMethod("definitionIds");
                Object ids = defIds.invoke(null);
                if (ids instanceof Set<?> set) {
                    for (Object id : set) if (id != null) addIfMatch(out, String.valueOf(id), q);
                }
            } catch (Throwable ignored) {}
        }

        // Dynamic soul system: summon profile/enchant-name tokens are no longer used.

        ArrayList<String> list = new ArrayList<>(out);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        if (list.size() > 60) return list.subList(0, 60);
        return list;
    }

    private static void addIfMatch(LinkedHashSet<String> out, String candidate, String q) {
        if (candidate == null) return;
        String c = candidate.trim();
        if (c.isEmpty()) return;
        if (q.isEmpty() || c.toLowerCase(Locale.ROOT).startsWith(q) || c.toLowerCase(Locale.ROOT).contains(q)) {
            out.add(c);
        }
    }

    private static String normalizeToken(String s) {
        return s.toLowerCase(Locale.ROOT).replace('&', ' ').replace('§', ' ').replaceAll("\\s+", "_").replaceAll("[^a-z0-9_\\-:.]", "");
    }

    public static Result apply(Player player, String enchantArg, String levelArg) {
        if (player == null) return Result.fail("Only players can enchant held items.");
        if (!player.hasPermission("gloved.enchant")) return Result.fail("You don't have permission to do that.");
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return Result.fail("Hold an item in your main hand.");

        boolean remove = "remove".equalsIgnoreCase(enchantArg) || "clear".equalsIgnoreCase(enchantArg) || "0".equals(levelArg);
        int level = 1;
        if (!remove) {
            if (levelArg != null) {
                try {
                    level = Integer.parseInt(levelArg);
                } catch (NumberFormatException e) {
                    return Result.fail("Level must be a number (or use 0/remove).");
                }
            }
            if (level <= 0) remove = true;
        }

        Resolved resolved = resolve(enchantArg);
        if (resolved == null) {
            GlovedPlugin.logDebug("[Enchant] resolve failed: arg=" + enchantArg + " item=" + item.getType());
            return Result.fail("Unknown enchantment: " + enchantArg);
        }

        GlovedPlugin.logDebug("[Enchant] apply start: player=" + player.getName()
                + " arg=" + enchantArg + " levelArg=" + levelArg
                + " resolved=" + (resolved.vanilla != null ? "vanilla:" + resolved.vanilla.getKey() : "em:" + resolved.emId)
                + " display=" + resolved.display
                + " remove=" + remove + " level=" + (remove ? 0 : level)
                + " item=" + item.getType() + " isEm=" + isEliteMobsItem(item)
                + " emPresent=" + eliteMobsPresent());

        try {
            Result r;
            if (resolved.vanilla != null) {
                r = applyVanilla(player, item, resolved, remove ? 0 : level);
            } else {
                r = applyEliteMobs(player, item, resolved, remove ? 0 : level);
            }
            GlovedPlugin.logDebug("[Enchant] apply result: ok=" + r.ok + " msg=" + r.message
                    + " postIsEm=" + isEliteMobsItem(player.getInventory().getItemInMainHand()));
            return r;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] apply failed for " + enchantArg + ": " + t);
            t.printStackTrace();
            return Result.fail("Failed to apply enchantment: " + t.getMessage());
        }
    }

    private static final class Resolved {
        final Enchantment vanilla;
        final String emId;
        final String display;
        Resolved(Enchantment vanilla, String emId, String display) {
            this.vanilla = vanilla; this.emId = emId; this.display = display;
        }
    }

    private static Resolved resolve(String arg) {
        if (arg == null || arg.isBlank()) return null;
        String raw = arg.trim();

        Enchantment vanilla = resolveVanilla(raw);
        Resolved em = resolveEliteMobs(raw);

        if (raw.contains(":")) {
            String ns = raw.substring(0, raw.indexOf(':')).toLowerCase(Locale.ROOT);
            if ("minecraft".equals(ns)) {
                if (vanilla != null) return new Resolved(vanilla, null, vanilla.getKey().getKey());
            }
            if ("elitemobs".equals(ns)) {
                String id = normalizeId(raw.substring(raw.indexOf(':') + 1));
                if (em != null) return em;
                return new Resolved(null, id, id);
            }
        }

        if (em != null && em.emId != null) return em;
        if (vanilla != null) return new Resolved(vanilla, null, vanilla.getKey().getKey());
        return em;
    }

    private static Enchantment resolveVanilla(String raw) {
        try {
            String plain = raw.toLowerCase(Locale.ROOT).trim();
            NamespacedKey key = NamespacedKey.fromString(plain);
            if (key == null || key.getNamespace().isEmpty()) key = NamespacedKey.minecraft(plain.replace(' ', '_'));
            try {
                Registry<Enchantment> reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
                Enchantment e = reg.get(key);
                if (e != null) return e;
                String k = plain.contains(":") ? plain.substring(plain.indexOf(':') + 1) : plain;
                e = reg.get(NamespacedKey.minecraft(k.replace(' ', '_')));
                if (e != null) return e;
            } catch (Throwable ignored) {}
            return Registry.ENCHANTMENT.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Resolved resolveEliteMobs(String raw) {
        if (!eliteMobsPresent()) return null;
        try {
            ClassLoader cl = emCl();
            Class<?> cfgClass = Class.forName("com.magmaguy.elitemobs.config.enchantments.EnchantmentsConfig", true, cl);
            Method getEnchantments = cfgClass.getMethod("getEnchantments");
            Object mapObj = getEnchantments.invoke(null);
            if (!(mapObj instanceof Map<?, ?> map)) return null;
            String wantNorm = normalizeId(raw);
            String wantName = stripFormattingPlain(raw).toLowerCase(Locale.ROOT);
            for (var entry : map.entrySet()) {
                String id = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                if (id == null) continue;
                String name = null;
                try {
                    Object field = entry.getValue();
                    if (field != null) name = String.valueOf(field.getClass().getMethod("getName").invoke(field));
                } catch (Throwable ignored) {}
                if (id.equalsIgnoreCase(raw.trim()) || normalizeId(id).equals(wantNorm)
                        || (name != null && stripFormattingPlain(name).equalsIgnoreCase(wantName))
                        || (name != null && normalizeId(name).equals(wantNorm))) {
                    return new Resolved(null, normalizeId(id), name != null ? stripFormattingPlain(name) : id);
                }
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] EM resolve failed: " + t);
        }
        return null;
    }

    private static String normalizeId(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.contains(":")) v = v.substring(v.indexOf(':') + 1);
        return v.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static String stripFormattingPlain(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[&§][0-9A-FK-ORX]", "").replaceAll("(?i)[&§]#[0-9A-F]{6}", "").trim();
    }

    private static Result applyVanilla(Player player, ItemStack item, Resolved resolved, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Result.fail("That item cannot hold enchantments.");
        boolean isEmItem = isEliteMobsItem(item);
        GlovedPlugin.logDebug("[Enchant] vanilla path: enchant=" + resolved.vanilla.getKey()
                + " level=" + level + " item=" + item.getType() + " isEm=" + isEmItem
                + " customId=" + emCustomItemId(meta));

        if (meta instanceof EnchantmentStorageMeta esm) {
            if (level <= 0) esm.removeStoredEnchant(resolved.vanilla);
            else esm.addStoredEnchant(resolved.vanilla, level, true);
        } else {
            if (level <= 0) meta.removeEnchant(resolved.vanilla);
            else meta.addEnchant(resolved.vanilla, level, true);
        }

        if (isEmItem && eliteMobsPresent()) {
            try {
                if (level <= 0) {
                    meta.getPersistentDataContainer().remove(resolved.vanilla.getKey());
                } else {
                    emRegisterEnchantment(meta, resolved.vanilla.getKey(), level);
                }
                GlovedPlugin.logDebug("[Enchant] EM vanilla PDC sync ok, hasKey=" + meta.getPersistentDataContainer().has(resolved.vanilla.getKey(), PersistentDataType.INTEGER));
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[Enchant] EM vanilla sync failed: " + t);
            }
        }
        item.setItemMeta(meta);

        boolean refreshed = true;
        if (isEmItem) refreshed = refreshEliteMobsLore(player, item, resolved.vanilla.getKey());

        player.getInventory().setItemInMainHand(item);
        String name = resolved.vanilla.getKey().getKey();
        String msg = (level <= 0 ? "Removed " + name + "." : "Applied " + name + " " + level + ".");
        if (isEmItem && !refreshed) msg += " (EM lore refresh did not run; enable /gloved debug on for details)";
        return Result.ok(msg);
    }

    private static Result applyEliteMobs(Player player, ItemStack item, Resolved resolved, int level) {
        if (!eliteMobsPresent()) return Result.fail("EliteMobs is not available; cannot apply EM enchantments.");

        boolean wasEm = isEliteMobsItem(item);
        GlovedPlugin.logDebug("[Enchant] EM path: emId=" + resolved.emId + " display=" + resolved.display
                + " level=" + level + " item=" + item.getType() + " wasEm=" + wasEm);
        if (!wasEm) {
            emRegisterEliteItem(item);
            emEnsureEliteLevel(item);
            GlovedPlugin.logDebug("[Enchant] registered as EM item, nowEm=" + isEliteMobsItem(item));
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return Result.fail("That item cannot hold enchantments.");
        GlovedPlugin.logDebug("[Enchant] pre-tag: customId=" + emCustomItemId(meta) + " loreLines=" + loreSize(meta));
        NamespacedKey key = new NamespacedKey("elitemobs", resolved.emId);
        try {
            if (level <= 0) {
                meta.getPersistentDataContainer().remove(key);
                item.setItemMeta(meta);
            } else {
                emRegisterEnchantment(meta, key, level);
                item.setItemMeta(meta);
            }
            ItemMeta check = item.getItemMeta();
            Integer pdcVal = check != null ? check.getPersistentDataContainer().get(key, PersistentDataType.INTEGER) : null;
            GlovedPlugin.logDebug("[Enchant] tag written: key=" + key + " pdcLevel=" + pdcVal);
        } catch (Throwable t) {
            if (level <= 0) meta.getPersistentDataContainer().remove(key);
            else meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, level);
            item.setItemMeta(meta);
            GlovedPlugin.logDebug("[Enchant] EM tag path failed, used PDC fallback: " + t);
        }

        boolean refreshed = refreshEliteMobsLore(player, item, key);
        player.getInventory().setItemInMainHand(item);
        String name = resolved.display != null ? resolved.display : resolved.emId;
        String msg = (level <= 0 ? "Removed " + name + "." : "Applied " + name + " " + level + ".");
        if (!refreshed) msg += " (EM lore refresh did not run; enable /gloved debug on for details)";
        return Result.ok(msg);
    }

    private static boolean isEliteMobsItem(ItemStack item) {
        if (!eliteMobsPresent() || item == null) return false;
        try {
            ClassLoader cl = emCl();
            Class<?> mgr = Class.forName("com.magmaguy.elitemobs.api.utils.EliteItemManager", true, cl);
            Method m = mgr.getMethod("isEliteMobsItem", ItemStack.class);
            Object r = m.invoke(null, item);
            return r instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void emRegisterEliteItem(ItemStack item) {
        try {
            ClassLoader cl = emCl();
            Class<?> mgr = Class.forName("com.magmaguy.elitemobs.api.utils.EliteItemManager", true, cl);
            Method m = mgr.getMethod("registerEliteItem", ItemStack.class);
            m.invoke(null, item);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] registerEliteItem failed: " + t);
        }
    }

    private static void emRegisterEnchantment(ItemMeta meta, NamespacedKey key, int level) throws Exception {
        ClassLoader cl = emCl();
        Class<?> tagger = Class.forName("com.magmaguy.elitemobs.items.ItemTagger", true, cl);
        Method m = tagger.getMethod("registerEnchantment", ItemMeta.class, NamespacedKey.class, int.class);
        m.invoke(null, meta, key, level);
    }

    private static void emEnsureEliteLevel(ItemStack item) {
        if (!eliteMobsPresent() || item == null) return;
        try {
            ClassLoader cl = emCl();
            Class<?> mgr = Class.forName("com.magmaguy.elitemobs.api.utils.EliteItemManager", true, cl);
            Method getLevel = mgr.getMethod("getRoundedItemLevel", ItemStack.class);
            Object r = getLevel.invoke(null, item);
            int lvl = r instanceof Integer i ? i : 0;
            if (lvl <= 0) {
                Method set = mgr.getMethod("setEliteLevel", ItemStack.class, int.class);
                set.invoke(null, item, 1);
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] EM level init failed (non-fatal): " + t);
        }
    }

    private static boolean refreshEliteMobsLore(Player player, ItemStack item, NamespacedKey changedKey) {
        if (!eliteMobsPresent() || item == null) return false;
        try {
            if (!isEliteMobsItem(item)) {
                GlovedPlugin.logDebug("[Enchant] Lore refresh skipped: not an EliteMobs item");
                return false;
            }
            int beforeLore = loreSize(item.getItemMeta());
            String customId = item.getItemMeta() != null ? emCustomItemId(item.getItemMeta()) : null;
            GlovedPlugin.logDebug("[Enchant] lore refresh start: customId=" + customId + " loreBefore=" + beforeLore
                    + " changedKey=" + changedKey);

            // Best-effort: nudge EM catalog (guarded). Won't fix EliteItemLore's static presentation.
            try {
                ClassLoader cl = emCl();
                Class<?> cat = Class.forName("com.magmaguy.elitemobs.items.EliteEnchantmentCatalog", true, cl);
                try { cat.getMethod("prepare").invoke(null); } catch (Throwable ignored) {}
                try { cat.getMethod("publish").invoke(null); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            ClassLoader cl = emCl();
            Class<?> loreClass = Class.forName("com.magmaguy.elitemobs.items.EliteItemLore", true, cl);
            Throwable last = null;
            Object built = null;
            String ctorUsed = null;

            try {
                built = loreClass.getConstructor(ItemStack.class, boolean.class).newInstance(item, false);
                ctorUsed = "(ItemStack,boolean)";
            } catch (Throwable t) { last = t; }

            if (built == null) {
                try {
                    built = loreClass.getConstructor(ItemStack.class, boolean.class, boolean.class).newInstance(item, false, false);
                    ctorUsed = "(ItemStack,boolean,boolean)";
                    last = null;
                } catch (Throwable t) { last = t; }
            }

            if (built == null) {
                try {
                    Class<?> ctx = Class.forName("com.magmaguy.elitemobs.items.itemconstructor.ItemConstructionContext", true, cl);
                    built = loreClass.getConstructor(ItemStack.class, boolean.class, boolean.class, ctx).newInstance(item, false, false, null);
                    ctorUsed = "(ItemStack,boolean,boolean,ItemConstructionContext:null)";
                    last = null;
                } catch (Throwable t) { last = t; }
            }

            if (built == null) {
                GlovedPlugin.logDebug("[Enchant] EliteItemLore construction failed: " + last);
                return false;
            }

            try {
                Method getStack = built.getClass().getMethod("getItemStack");
                Object out = getStack.invoke(built);
                if (out instanceof ItemStack outStack && outStack.getType() != org.bukkit.Material.AIR) {
                    ItemMeta outMeta = outStack.getItemMeta();
                    if (outMeta != null) item.setItemMeta(outMeta);
                    GlovedPlugin.logDebug("[Enchant] applied getItemStack() meta from EliteItemLore");
                }
            } catch (Throwable ignored) {}
            normalizeLoreItalics(item);

            // Try magmacore/EM presentation refresh paths that may use live resolver state.
            tryRefreshPresentation(item);

            debugItemEnchantmentState(item, changedKey);

            // Fallback: EM custom/script enchants (e.g. summon markers) may not render if EliteItemLore's
            // static presentation doesn't include them. If the PDC key is present but the display line is
            // missing, inject/remove a best-effort "Custom Enchants" lore line.
            if (changedKey != null && "elitemobs".equals(changedKey.getNamespace())) {
                ensureCustomEnchantLoreFallback(item, changedKey);
            }
            normalizeLoreItalics(item);

            if (player != null) player.getInventory().setItemInMainHand(item);

            int afterLore = loreSize(item.getItemMeta());
            boolean hasKey = changedKey != null && item.getItemMeta() != null
                    && item.getItemMeta().getPersistentDataContainer().has(changedKey, PersistentDataType.INTEGER);
            GlovedPlugin.logDebug("[Enchant] EliteItemLore refreshed " + ctorUsed
                    + " loreBefore=" + beforeLore + " loreAfter=" + afterLore
                    + " keyPresent=" + hasKey + " customId=" + customId);
            return true;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] EliteItemLore refresh failed: " + t);
            return false;
        }
    }

    private static void ensureCustomEnchantLoreFallback(ItemStack item, NamespacedKey key) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            Integer level = meta.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
            String display = emEnchantDisplayName(key.getKey());
            String plainRaw = stripFormattingPlain(display);
            final String matchPlain = plainRaw.isEmpty() ? key.getKey() : plainRaw;

            List<String> lore = new ArrayList<>();
            if (meta.lore() != null) {
                for (net.kyori.adventure.text.Component c : meta.lore()) {
                    lore.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c));
                }
            }

            boolean hasLine = lore.stream().anyMatch(line -> stripFormattingPlain(line).equalsIgnoreCase(matchPlain)
                    || stripFormattingPlain(line).toLowerCase(Locale.ROOT).contains(matchPlain.toLowerCase(Locale.ROOT)));

            if (level != null && level > 0) {
                if (hasLine) return; // EM or prior fallback already rendered it
                // Best-effort: match common EM custom enchant rendering. If display has no color, default gray.
                String colored = (display.indexOf('§') >= 0) ? display : ("§7" + display);
                String entry = colored + (level > 1 ? " " + level : "");
                int headerIdx = -1;
                int footerIdx = -1;
                for (int i = 0; i < lore.size(); i++) {
                    String p = stripFormattingPlain(lore.get(i)).toLowerCase(Locale.ROOT);
                    if (headerIdx < 0 && p.contains("custom enchant")) headerIdx = i;
                }
                if (headerIdx >= 0) {
                    // insert before footer-like separator after header, else after header
                    int insertAt = headerIdx + 1;
                    for (int i = headerIdx + 1; i < lore.size(); i++) {
                        String raw = lore.get(i);
                        String p = stripFormattingPlain(raw);
                        if (p.replace("§", "").replace("-", "").replace("—", "").trim().isEmpty() && raw.contains("m")) {
                            footerIdx = i; break;
                        }
                        // stop before next major section
                        if (p.startsWith("Sells for") || p.toLowerCase(Locale.ROOT).contains("soulbound") || p.contains("Info")) {
                            insertAt = i; footerIdx = -1; break;
                        }
                        insertAt = i + 1;
                    }
                    if (footerIdx >= 0) insertAt = footerIdx;
                    lore.add(Math.min(insertAt, lore.size()), entry);
                } else {
                    // no custom section; append a minimal section before sell/value if possible
                    int insertAt = lore.size();
                    for (int i = 0; i < lore.size(); i++) {
                        if (stripFormattingPlain(lore.get(i)).toLowerCase(Locale.ROOT).startsWith("sells for")) { insertAt = i; break; }
                    }
                    List<String> block = List.of(
                            "§7§l§m------§7< §3§lCustom Enchants§7 >§7§l§m------",
                            entry,
                            "§7§l§m-----------------------------"
                    );
                    lore.addAll(insertAt, block);
                }
                applyLegacyLore(meta, lore);
                item.setItemMeta(meta);
                GlovedPlugin.logDebug("[Enchant] fallback lore injected for " + key + " as '" + stripFormattingPlain(entry) + "'");
            } else {
                if (!hasLine) return;
                lore.removeIf(line -> stripFormattingPlain(line).equalsIgnoreCase(matchPlain)
                        || stripFormattingPlain(line).toLowerCase(Locale.ROOT).contains(matchPlain.toLowerCase(Locale.ROOT)));
                // clean empty custom section if we created one (best-effort)
                cleanupEmptyCustomSection(lore);
                applyLegacyLore(meta, lore);
                item.setItemMeta(meta);
                GlovedPlugin.logDebug("[Enchant] fallback lore removed for " + key);
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] fallback lore update failed: " + t);
        }
    }

    private static void cleanupEmptyCustomSection(List<String> lore) {
        for (int i = 0; i < lore.size(); i++) {
            if (stripFormattingPlain(lore.get(i)).toLowerCase(Locale.ROOT).contains("custom enchant")) {
                // if next is footer-like separator, remove header+footer
                if (i + 1 < lore.size()) {
                    String next = lore.get(i + 1);
                    String p = stripFormattingPlain(next);
                    boolean sep = p.trim().isEmpty() && next.contains("m");
                    if (sep) {
                        lore.remove(i + 1);
                        lore.remove(i);
                    }
                }
                break;
            }
        }
    }

    private static void applyLegacyLore(ItemMeta meta, List<String> lines) {
        var ser = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
        List<net.kyori.adventure.text.Component> comps = new ArrayList<>();
        for (String l : lines) {
            net.kyori.adventure.text.Component c = ser.deserialize(l == null ? "" : l);
            if (c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC) == net.kyori.adventure.text.format.TextDecoration.State.NOT_SET) {
                c = c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
            }
            comps.add(c);
        }
        meta.lore(comps);
    }

    private static void normalizeLoreItalics(ItemStack item) {
        if (item == null) return;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || meta.lore() == null) return;
            List<net.kyori.adventure.text.Component> fixed = new ArrayList<>();
            boolean changed = false;
            for (var c : meta.lore()) {
                if (c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC) == net.kyori.adventure.text.format.TextDecoration.State.NOT_SET) {
                    fixed.add(c.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                    changed = true;
                } else {
                    fixed.add(c);
                }
            }
            if (changed) { meta.lore(fixed); item.setItemMeta(meta); }
        } catch (Throwable ignored) {}
    }

    private static void tryRefreshPresentation(ItemStack item) {
        if (!eliteMobsPresent() || item == null) return;
        // EliteItemLore.ENCHANTMENT_PRESENTATION is static final (stale). Try other presentation instances.
        try {
            ClassLoader cl = emCl();
            Class<?> eei = Class.forName("com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems", true, cl);
            Object items = eei.getField("ITEMS").get(null);
            if (items != null) {
                Method refresh = items.getClass().getMethod("refreshPresentation", ItemStack.class);
                Object out = refresh.invoke(items, item);
                if (out instanceof ItemStack outStack && outStack.getType() != org.bukkit.Material.AIR && outStack.getItemMeta() != null) {
                    item.setItemMeta(outStack.getItemMeta());
                    normalizeLoreItalics(item);
                    GlovedPlugin.logDebug("[Enchant] EliteEnchantmentItems.ITEMS.refreshPresentation applied");
                }
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] EliteEnchantmentItems.ITEMS.refreshPresentation failed: " + t);
        }
        // Also try via EliteItemLore's own presentation field (read-only usage; cannot replace final)
        try {
            ClassLoader cl = emCl();
            Class<?> lore = Class.forName("com.magmaguy.elitemobs.items.EliteItemLore", true, cl);
            java.lang.reflect.Field f = null;
            try { f = lore.getDeclaredField("ENCHANTMENT_PRESENTATION"); } catch (NoSuchFieldException ignored) {}
            if (f != null) {
                f.setAccessible(true);
                Object pres = f.get(null);
                if (pres != null) {
                    Method refresh = pres.getClass().getMethod("refreshPresentation", ItemStack.class);
                    Object out = refresh.invoke(pres, item);
                    if (out instanceof ItemStack outStack && outStack.getType() != org.bukkit.Material.AIR && outStack.getItemMeta() != null) {
                        item.setItemMeta(outStack.getItemMeta());
                        normalizeLoreItalics(item);
                        GlovedPlugin.logDebug("[Enchant] EliteItemLore.ENCHANTMENT_PRESENTATION.refreshPresentation applied");
                    }
                }
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] EliteItemLore.ENCHANTMENT_PRESENTATION.refreshPresentation failed: " + t);
        }
    }

    private static void debugItemEnchantmentState(ItemStack item, NamespacedKey changedKey) {
        if (!eliteMobsPresent() || item == null) return;
        try {
            ClassLoader cl = emCl();
            Class<?> tagger = Class.forName("com.magmaguy.elitemobs.items.ItemTagger", true, cl);
            try {
                Method m = tagger.getMethod("getItemEnchantmentConfigFields", ItemStack.class);
                Object r = m.invoke(null, item);
                GlovedPlugin.logDebug("[Enchant] ItemTagger.getItemEnchantmentConfigFields=" + r);
            } catch (Throwable t) { GlovedPlugin.logDebug("[Enchant] getItemEnchantmentConfigFields failed: " + t); }
            try {
                Method m = tagger.getMethod("getItemEnchantments", ItemStack.class);
                Object r = m.invoke(null, item);
                GlovedPlugin.logDebug("[Enchant] ItemTagger.getItemEnchantments=" + r);
            } catch (Throwable t) { GlovedPlugin.logDebug("[Enchant] getItemEnchantments failed: " + t); }
            Class<?> eei = Class.forName("com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems", true, cl);
            try {
                Method m = eei.getMethod("custom", ItemStack.class);
                Object r = m.invoke(null, item);
                GlovedPlugin.logDebug("[Enchant] EliteEnchantmentItems.custom=" + r);
            } catch (Throwable t) { GlovedPlugin.logDebug("[Enchant] EliteEnchantmentItems.custom failed: " + t); }
            try {
                Object items = eei.getField("ITEMS").get(null);
                if (items != null && item.getItemMeta() != null) {
                    Method ins = items.getClass().getMethod("inspectCustom", ItemMeta.class);
                    Object r = ins.invoke(items, item.getItemMeta());
                    GlovedPlugin.logDebug("[Enchant] ITEMS.inspectCustom=" + r);
                }
            } catch (Throwable t) { GlovedPlugin.logDebug("[Enchant] ITEMS.inspectCustom failed: " + t); }
            try {
                Object items = eei.getField("ITEMS").get(null);
                if (items != null) {
                    Method ins = items.getClass().getMethod("inspect", ItemStack.class);
                    Object r = ins.invoke(items, item);
                    GlovedPlugin.logDebug("[Enchant] ITEMS.inspect=" + r);
                }
            } catch (Throwable t) { GlovedPlugin.logDebug("[Enchant] ITEMS.inspect failed: " + t); }
            // Catalog/config recognition of the changed key
            if (changedKey != null) {
                String id = changedKey.getKey();
                try {
                    Class<?> cfgClass = Class.forName("com.magmaguy.elitemobs.config.enchantments.EnchantmentsConfig", true, cl);
                    Object field = cfgClass.getMethod("getEnchantment", String.class).invoke(null, id);
                    if (field == null) field = cfgClass.getMethod("getEnchantment", String.class).invoke(null, normalizeId(id));
                    GlovedPlugin.logDebug("[Enchant] EnchantmentsConfig.getEnchantment(" + id + ")=" + (field != null ? "present" : "null"));
                    if (field != null) {
                        try { GlovedPlugin.logDebug("[Enchant] config getEnchantment(bukkit)=" + field.getClass().getMethod("getEnchantment").invoke(field)); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) { GlovedPlugin.logDebug("[Enchant] config lookup failed: " + t); }
                try {
                    Class<?> cat = Class.forName("com.magmaguy.elitemobs.items.EliteEnchantmentCatalog", true, cl);
                    Object def = cat.getMethod("definition", String.class).invoke(null, id);
                    if (def == null) def = cat.getMethod("definition", String.class).invoke(null, normalizeId(id));
                    GlovedPlugin.logDebug("[Enchant] EliteEnchantmentCatalog.definition(" + id + ")=" + (def != null ? "present" : "null"));
                    try {
                        Object ids = cat.getMethod("definitionIds").invoke(null);
                        boolean has = false;
                        if (ids instanceof Set<?> s) for (Object o : s) if (o != null && normalizeId(String.valueOf(o)).equals(normalizeId(id))) { has = true; break; }
                        GlovedPlugin.logDebug("[Enchant] catalog definitionIds contains " + id + "=" + has);
                    } catch (Throwable ignored) {}
                } catch (Throwable t) { GlovedPlugin.logDebug("[Enchant] catalog lookup failed: " + t); }
            }
            if (changedKey != null && item.getItemMeta() != null) {
                Integer lvl = item.getItemMeta().getPersistentDataContainer().get(changedKey, PersistentDataType.INTEGER);
                GlovedPlugin.logDebug("[Enchant] direct PDC " + changedKey + "=" + lvl);
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] debugItemEnchantmentState failed: " + t);
        }
    }

    private static String emEnchantDisplayName(String emId) {
        if (emId == null) return "";
        String fallback = prettyId(emId);
        if (!eliteMobsPresent()) return fallback;
        try {
            ClassLoader cl = emCl();
            String norm = normalizeId(emId);
            Class<?> cfgClass = Class.forName("com.magmaguy.elitemobs.config.enchantments.EnchantmentsConfig", true, cl);

            // 1) Scan map first (most reliable; resolve uses this)
            try {
                Method getEnchantments = cfgClass.getMethod("getEnchantments");
                Object mapObj = getEnchantments.invoke(null);
                if (mapObj instanceof Map<?, ?> map) {
                    for (var e : map.entrySet()) {
                        String id = e.getKey() == null ? null : String.valueOf(e.getKey());
                        if (id == null) continue;
                        if (normalizeId(id).equals(norm) || id.equalsIgnoreCase(emId) || normalizeId(id).equalsIgnoreCase(normalizeId(emId))) {
                            String name = readConfigFieldName(e.getValue());
                            if (name != null) return name;
                        }
                    }
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[Enchant] EM display name map scan failed for " + emId + ": " + t);
            }

            // 2) Direct lookup variants
            try {
                Method get = cfgClass.getMethod("getEnchantment", String.class);
                for (String candidate : new String[]{norm, emId, emId.replace('-', '_'), norm.replace('_', '-')}) {
                    Object field = get.invoke(null, candidate);
                    String name = readConfigFieldName(field);
                    if (name != null) return name;
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[Enchant] EM display name direct lookup failed for " + emId + ": " + t);
            }

            // 3) Catalog definition text (if available)
            try {
                Class<?> cat = Class.forName("com.magmaguy.elitemobs.items.EliteEnchantmentCatalog", true, cl);
                Method text = cat.getMethod("text", String.class, String.class, String.class);
                for (String candidate : new String[]{norm, emId}) {
                    Object r = text.invoke(null, candidate, "name", null);
                    if (r != null) {
                        String converted = String.valueOf(r).replace('&', '§').trim();
                        if (!stripFormattingPlain(converted).isEmpty()) return converted;
                    }
                }
            } catch (Throwable ignored) {}

            GlovedPlugin.logDebug("[Enchant] EM display name not found for " + emId + " (norm=" + norm + "), using fallback " + fallback);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Enchant] EM display name lookup failed for " + emId + ": " + t);
        }
        return fallback;
    }

    private static String readConfigFieldName(Object field) {
        if (field == null) return null;
        try {
            Object name = field.getClass().getMethod("getName").invoke(field);
            if (name != null) {
                String converted = String.valueOf(name).replace('&', '§').trim();
                if (!stripFormattingPlain(converted).isEmpty()) return converted;
            }
        } catch (Throwable ignored) {}
        // Try getFilename or other common accessors
        for (String mName : new String[]{"getFilename", "getFileName"}) {
            try {
                Object v = field.getClass().getMethod(mName).invoke(field);
                if (v != null) {
                    String s = prettyId(String.valueOf(v));
                    if (!s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String prettyId(String emId) {
        String s = normalizeId(emId).replace('_', ' ').replace('-', ' ').trim();
        if (s.isEmpty()) return emId == null ? "" : emId;
        String[] parts = s.split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) b.append(p.substring(1));
        }
        return b.toString();
    }

    public static String emCustomItemId(ItemStack item) {
        if (!eliteMobsPresent() || item == null) return null;
        // Try direct ItemStack-based APIs first (matches current EliteMobs API)
        try {
            ClassLoader cl = emCl();
            Class<?> tagger = Class.forName("com.magmaguy.elitemobs.items.ItemTagger", true, cl);
            for (String name : List.of("getCustomItemId", "getCustomItemFilename", "getCustomFilename")) {
                try {
                    Method m = tagger.getMethod(name, ItemStack.class);
                    Object r = m.invoke(null, item);
                    if (r != null && !String.valueOf(r).isBlank()) return String.valueOf(r);
                } catch (NoSuchMethodException ignored) {}
            }
            Class<?> mgr = Class.forName("com.magmaguy.elitemobs.api.utils.EliteItemManager", true, cl);
            for (String name : List.of("getCustomItemFilename", "getCustomItemId", "getCustomItem")) {
                try {
                    Method m = mgr.getMethod(name, ItemStack.class);
                    Object r = m.invoke(null, item);
                    if (r != null && !String.valueOf(r).isBlank()) return String.valueOf(r);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        ItemMeta meta = item.getItemMeta();
        return meta != null ? emCustomItemId(meta) : null;
    }

    public static String emCustomItemId(ItemMeta meta) {
        if (!eliteMobsPresent() || meta == null) return null;
        try {
            ClassLoader cl = emCl();
            Class<?> tagger = Class.forName("com.magmaguy.elitemobs.items.ItemTagger", true, cl);
            for (String name : List.of("getCustomItemId", "getCustomItemFilename", "getCustomFilename", "getEnchantmentFilename")) {
                try {
                    Method m = tagger.getMethod(name, ItemMeta.class);
                    Object r = m.invoke(null, meta);
                    if (r != null && !String.valueOf(r).isBlank()) return String.valueOf(r);
                } catch (NoSuchMethodException ignored) {}
            }
            Class<?> mgr = Class.forName("com.magmaguy.elitemobs.api.utils.EliteItemManager", true, cl);
            for (String name : List.of("getCustomItemFilename", "getCustomItemId", "getCustomItem")) {
                try {
                    Method m = mgr.getMethod(name, ItemMeta.class);
                    Object r = m.invoke(null, meta);
                    if (r != null && !String.valueOf(r).isBlank()) return String.valueOf(r);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int loreSize(ItemMeta meta) {
        if (meta == null || meta.lore() == null) return 0;
        return meta.lore().size();
    }
}
