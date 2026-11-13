package dev.gloved.glovedplugin.menu.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.menu.config.MenuConfig;
import org.bukkit.Material;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists unsaved edit sessions to {@code edit-sessions.json} so they
 * survive relogs and restarts. Stored on every editor close and on disable,
 * reloaded on enable. Sessions whose data no longer parses (e.g. a material
 * renamed across versions) are skipped with a warning, never fatal.
 */
public final class EditSessionCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "edit-sessions.json";

    private EditSessionCache() {}

    static class CachedItem {
        String material;
        String name;
        List<String> lore;
        String command;
        String anchor;
        int offsetX;
        int offsetY;
        Integer slot;
        boolean op;
        boolean enabled;
    }

    static class CachedSession {
        String title;
        int rows;
        int itemsPerRow;
        String soundName;
        boolean fillerEnabled;
        String fillerMaterial;
        String fillerName;
        List<String> fillerLore;
        String openerMaterial;
        int openerSlot;
        String openerName;
        List<String> openerLore;
        List<CachedItem> items;
        long fileModifiedAt;
    }

    static CachedSession toJson(EditSession session) {
        CachedSession out = new CachedSession();
        out.title = session.title;
        out.rows = session.rows;
        out.itemsPerRow = session.itemsPerRow;
        out.soundName = session.soundName;
        out.fillerEnabled = session.fillerEnabled;
        out.fillerMaterial = session.fillerMaterial.name();
        out.fillerName = session.fillerName;
        out.fillerLore = new ArrayList<>(session.fillerLore);
        out.openerMaterial = session.openerMaterial.name();
        out.openerSlot = session.openerSlot;
        out.openerName = session.openerName;
        out.openerLore = new ArrayList<>(session.openerLore);
        out.items = new ArrayList<>();
        for (MenuConfig.MenuItem mi : session.items) {
            CachedItem item = new CachedItem();
            item.material = mi.material().name();
            item.name = mi.name();
            item.lore = new ArrayList<>(mi.lore());
            item.command = mi.command();
            item.anchor = mi.anchor() == null ? null : mi.anchor().name();
            item.offsetX = mi.offsetX();
            item.offsetY = mi.offsetY();
            item.slot = mi.slot();
            item.op = mi.opOnly();
            item.enabled = mi.enabled();
            out.items.add(item);
        }
        out.fileModifiedAt = session.fileModifiedAt;
        return out;
    }

    /** Returns null when the cached data is unusable (caller skips it). */
    static EditSession fromJson(CachedSession cached) {
        if (cached == null || cached.items == null) return null;
        Material fillerMaterial;
        Material openerMaterial;
        try {
            fillerMaterial = Material.valueOf(cached.fillerMaterial);
            openerMaterial = Material.valueOf(cached.openerMaterial);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        EditSession session = new EditSession(cached.fileModifiedAt);
        session.title = cached.title;
        session.rows = cached.rows;
        session.itemsPerRow = cached.itemsPerRow;
        session.soundName = cached.soundName;
        session.fillerEnabled = cached.fillerEnabled;
        session.fillerMaterial = fillerMaterial;
        session.fillerName = cached.fillerName;
        session.fillerLore = cached.fillerLore == null ? new ArrayList<>() : new ArrayList<>(cached.fillerLore);
        session.openerMaterial = openerMaterial;
        session.openerSlot = cached.openerSlot;
        session.openerName = cached.openerName;
        session.openerLore = cached.openerLore == null ? new ArrayList<>() : new ArrayList<>(cached.openerLore);
        for (CachedItem item : cached.items) {
            if (item == null) return null;
            Material material;
            try {
                material = Material.valueOf(item.material);
            } catch (IllegalArgumentException | NullPointerException e) {
                return null;
            }
            MenuConfig.Anchor anchor = item.anchor == null ? null : MenuConfig.Anchor.parse(item.anchor);
            session.items.add(new MenuConfig.MenuItem(material, item.name,
                    item.lore == null ? new ArrayList<>() : new ArrayList<>(item.lore),
                    item.command, anchor, item.offsetX, item.offsetY, item.slot, item.op, item.enabled));
        }
        session.cleanFingerprint = session.fingerprint();
        return session;
    }

    private static File file(GlovedPlugin plugin) {
        return new File(plugin.getDataFolder(), FILE_NAME);
    }

    public static void storeAll(GlovedPlugin plugin) {
        try {
            plugin.getDataFolder().mkdirs();
            Map<String, CachedSession> map = new LinkedHashMap<>();
            for (Map.Entry<UUID, EditSession> entry : EditSession.all().entrySet()) {
                map.put(entry.getKey().toString(), toJson(entry.getValue()));
            }
            Files.writeString(file(plugin).toPath(), GSON.toJson(map));
        } catch (Exception e) {
            plugin.getLogger().warning("GlovedPlugin could not cache menu edits: " + e);
        }
    }

    public static void loadAll(GlovedPlugin plugin) {
        File file = file(plugin);
        if (!file.isFile()) return;
        try {
            String json = Files.readString(file.toPath());
            Type type = new TypeToken<Map<String, CachedSession>>() {}.getType();
            Map<String, CachedSession> map = GSON.fromJson(json, type);
            if (map == null) return;
            int kept = 0;
            int dropped = 0;
            for (Map.Entry<String, CachedSession> entry : map.entrySet()) {
                UUID id;
                try {
                    id = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException e) {
                    dropped++;
                    continue;
                }
                EditSession session = fromJson(entry.getValue());
                if (session == null) {
                    dropped++;
                    continue;
                }
                EditSession.put(id, session);
                kept++;
            }
            if (kept + dropped > 0) {
                plugin.getLogger().info("GlovedPlugin restored " + kept + " cached menu edit(s)"
                        + (dropped == 0 ? "" : ", skipped " + dropped));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("GlovedPlugin could not load cached menu edits: " + e);
        }
    }
}
