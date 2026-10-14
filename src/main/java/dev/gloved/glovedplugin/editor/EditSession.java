package dev.gloved.glovedplugin.editor;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.MenuConfig;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A per-player working copy of the menu. Created from the live config when
 * {@code /gloved edit} opens; nothing touches config.yml until save.
 * MenuItems are records, so every edit replaces the element (lore lists are
 * deep-copied at snapshot so appends can't leak into the live config).
 */
public class EditSession {

    private static final Map<UUID, EditSession> SESSIONS = new HashMap<>();

    public String title;
    public int rows;
    public int itemsPerRow;
    public String soundName;
    public boolean fillerEnabled;
    public Material fillerMaterial;
    public String fillerName;
    public List<String> fillerLore;
    public Material openerMaterial;
    public int openerSlot;
    public String openerName;
    public List<String> openerLore;
    public final List<MenuConfig.MenuItem> items = new ArrayList<>();

    /** config.yml mtime at snapshot, for save-conflict detection. */
    public final long fileModifiedAt;
    /** Fingerprint of the working copy at snapshot time; compared to spot real edits. */
    public String cleanFingerprint;
    /** Item index armed for delete confirmation, -1 when none. */
    public int armedDelete = -1;
    public long armedDeleteAt = 0;
    /** Second save confirms overwriting an externally changed file. */
    public boolean armedSave = false;

    EditSession(long fileModifiedAt) {
        this.fileModifiedAt = fileModifiedAt;
    }

    public static EditSession snapshot(MenuConfig config, long fileModifiedAt) {
        EditSession s = new EditSession(fileModifiedAt);
        s.title = config.rawTitle();
        s.rows = config.rows();
        s.itemsPerRow = config.itemsPerRow();
        s.soundName = config.soundName();
        s.fillerEnabled = config.fillerEnabled();
        s.fillerMaterial = config.fillerMaterial();
        s.fillerName = config.fillerName();
        s.fillerLore = new ArrayList<>(config.fillerLore());
        s.openerMaterial = config.openerMaterial();
        s.openerSlot = config.openerSlot();
        s.openerName = config.openerName();
        s.openerLore = new ArrayList<>(config.openerLore());
        for (MenuConfig.MenuItem mi : config.items()) {
            s.items.add(new MenuConfig.MenuItem(mi.material(), mi.name(), new ArrayList<>(mi.lore()),
                    mi.command(), mi.anchor(), mi.offsetX(), mi.offsetY(), mi.slot(), mi.opOnly(), mi.enabled()));
        }
        s.cleanFingerprint = s.fingerprint();
        return s;
    }

    /** Deterministic snapshot of everything the user can change. */
    public String fingerprint() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("title", title);
        state.put("rows", rows);
        state.put("itemsPerRow", itemsPerRow);
        state.put("soundName", soundName);
        state.put("fillerEnabled", fillerEnabled);
        state.put("fillerMaterial", fillerMaterial == null ? null : fillerMaterial.name());
        state.put("fillerName", fillerName);
        state.put("fillerLore", new ArrayList<>(fillerLore));
        state.put("openerMaterial", openerMaterial == null ? null : openerMaterial.name());
        state.put("openerSlot", openerSlot);
        state.put("openerName", openerName);
        state.put("openerLore", new ArrayList<>(openerLore));
        state.put("items", MenuEditorCodec.serializeItems(items));
        return new com.google.gson.Gson().toJson(state);
    }

    /** True when the working copy differs from what was snapshotted. */
    public boolean isDirty() {
        return cleanFingerprint == null || !cleanFingerprint.equals(fingerprint());
    }

    public void applyTo(FileConfiguration cfg) {
        cfg.set("menu.title", title);
        cfg.set("menu.rows", rows);
        cfg.set("menu.items-per-row", itemsPerRow);
        cfg.set("menu.sound", soundName);
        cfg.set("menu.filler.enabled", fillerEnabled);
        cfg.set("menu.filler.material", fillerMaterial.name());
        cfg.set("menu.filler.name", fillerName);
        cfg.set("menu.filler.lore", new ArrayList<>(fillerLore));
        cfg.set("menu.items", MenuEditorCodec.serializeItems(items));
        cfg.set("opener.material", openerMaterial.name());
        cfg.set("opener.slot", openerSlot);
        cfg.set("opener.name", openerName);
        cfg.set("opener.lore", new ArrayList<>(openerLore));
    }

    public static long configModifiedAt(GlovedPlugin plugin) {
        return new File(plugin.getDataFolder(), "config.yml").lastModified();
    }

    public static EditSession get(UUID player) {
        return SESSIONS.get(player);
    }

    public static java.util.Map<UUID, EditSession> all() {
        return java.util.Collections.unmodifiableMap(SESSIONS);
    }

    public static void put(UUID player, EditSession session) {
        SESSIONS.put(player, session);
    }

    public static void close(UUID player) {
        SESSIONS.remove(player);
        PendingInput.cancel(player);
    }
}
