package dev.gloved.glovedplugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class MenuConfig {

    public enum Anchor {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        LEFT, CENTER, RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT;

        public static Anchor parse(String raw) {
            if (raw == null) return null;
            String n = raw.toLowerCase().replaceAll("[_\\- ]", "");
            return switch (n) {
                case "topleft" -> TOP_LEFT;
                case "top", "topcenter", "topcentre", "topmiddle" -> TOP_CENTER;
                case "topright" -> TOP_RIGHT;
                case "left", "middleleft", "centerleft" -> LEFT;
                case "center", "middle", "middlecenter" -> CENTER;
                case "right", "middleright", "centerright" -> RIGHT;
                case "bottomleft" -> BOTTOM_LEFT;
                case "bottom", "bottomcenter", "bottomcentre", "bottommiddle" -> BOTTOM_CENTER;
                case "bottomright" -> BOTTOM_RIGHT;
                default -> null;
            };
        }
    }

    public record MenuItem(Material material, String name, List<String> lore, String command,
                           Anchor anchor, int offsetX, int offsetY, Integer slot, boolean opOnly,
                           boolean enabled) {
        /** Operator-only buttons are visible (and clickable) solely to server operators. */
        public boolean visibleTo(boolean isOp) {
            return !opOnly || isOp;
        }
    }

    private Component titleComponent;
    private String rawTitle;
    private int rows;
    private int itemsPerRow;
    private Sound sound;
    private String soundName;
    private List<MenuItem> items;
    private Map<Integer, MenuItem> layout;

    private Material openerMaterial;
    private int openerSlot;
    private String openerName;
    private List<String> openerLore;

    private boolean fillerEnabled;
    private Material fillerMaterial;
    private String fillerName;
    private List<String> fillerLore;

    public Component titleComponent() { return titleComponent; }
    public String rawTitle() { return rawTitle; }
    public int rows() { return rows; }
    public int itemsPerRow() { return itemsPerRow; }
    public Sound sound() { return sound; }
    public String soundName() { return soundName; }
    public List<MenuItem> items() { return items; }
    public Map<Integer, MenuItem> layout() { return layout; }
    public Material openerMaterial() { return openerMaterial; }
    public int openerSlot() { return openerSlot; }
    public String openerName() { return openerName; }
    public List<String> openerLore() { return openerLore; }
    public boolean fillerEnabled() { return fillerEnabled; }
    public Material fillerMaterial() { return fillerMaterial; }
    public String fillerName() { return fillerName; }
    public List<String> fillerLore() { return fillerLore; }

    public String commandForSlot(int slot, boolean isOp) {
        MenuItem item = layout.get(slot);
        if (item == null || !item.visibleTo(isOp)) return null;
        return item.command();
    }

    public static MenuConfig load(GlovedPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        MenuConfig mc = new MenuConfig();

        mc.rawTitle = cfg.getString("menu.title", "GlovedMenu");
        mc.titleComponent = GlovedPlugin.makeComponent(mc.rawTitle);

        mc.rows = cfg.getInt("menu.rows", 6);
        if (mc.rows < 1 || mc.rows > 6) {
            GlovedPlugin.logWarning("menu.rows out of range (1-6), defaulting to 6");
            mc.rows = 6;
        }

        mc.itemsPerRow = cfg.getInt("menu.items-per-row", 5);
        if (mc.itemsPerRow < 1 || mc.itemsPerRow > 9) {
            GlovedPlugin.logWarning("menu.items-per-row out of range (1-9), defaulting to 5");
            mc.itemsPerRow = 5;
        }

        String soundName = cfg.getString("menu.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        mc.sound = parseSound(soundName);
        mc.soundName = soundName;

        mc.items = new ArrayList<>();
        List<?> rawItems = cfg.getList("menu.items", List.of());
        for (Object obj : rawItems) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            Object enabledObj = map.get("enabled");
            // Disabled items are kept (the editor can re-enable them); only the
            // layout skips them. Blank-command or unknown-material entries are
            // still dropped as unusable.
            boolean enabled = enabledObj == null || toBoolean(enabledObj, true);
            Object matObj = map.get("material");
            String matName = matObj != null ? String.valueOf(matObj) : "STONE";
            Material mat;
            try {
                mat = Material.valueOf(matName);
            } catch (IllegalArgumentException e) {
                GlovedPlugin.logWarning("Unknown material '" + matName + "', skipping item");
                continue;
            }
            Object nameObj = map.get("name");
            String name = nameObj != null ? String.valueOf(nameObj) : "&f" + matName;
            List<String> lore = new ArrayList<>();
            Object loreObj = map.get("lore");
            if (loreObj instanceof List<?> loreList) {
                for (Object l : loreList) lore.add(String.valueOf(l));
            }
            Object cmdObj = map.get("command");
            String command = cmdObj != null ? String.valueOf(cmdObj) : "";
            if (command.isBlank()) {
                GlovedPlugin.logWarning("Item " + matName + " has no command, skipping");
                continue;
            }
            Object anchorObj = map.get("anchor");
            Anchor anchor = anchorObj != null ? Anchor.parse(String.valueOf(anchorObj)) : null;
            if (anchorObj != null && anchor == null) {
                GlovedPlugin.logWarning("Unknown anchor '" + anchorObj + "' for " + matName + ", ignoring anchor");
            }
            int offsetX = toInt(map.get("offset-x"), 0);
            int offsetY = toInt(map.get("offset-y"), 0);
            // Back-compat aliases
            if (map.get("offsetX") != null) offsetX = toInt(map.get("offsetX"), offsetX);
            if (map.get("offsetY") != null) offsetY = toInt(map.get("offsetY"), offsetY);
            Object slotObj = map.get("slot");
            Integer slot = slotObj != null ? toInt(slotObj, -1) : null;
            if (slot != null && slot < 0) slot = null;
            boolean opOnly = toBoolean(map.get("op"), false);
            mc.items.add(new MenuItem(mat, name, lore, command, anchor, offsetX, offsetY, slot, opOnly, enabled));
        }

        ConfigurationSection opener = cfg.getConfigurationSection("opener");
        String openerMatName = opener != null ? opener.getString("material", "CHEST") : "CHEST";
        try {
            mc.openerMaterial = Material.valueOf(openerMatName);
        } catch (IllegalArgumentException e) {
            GlovedPlugin.logWarning("Unknown opener material '" + openerMatName + "', defaulting to CHEST");
            mc.openerMaterial = Material.CHEST;
        }
        mc.openerSlot = opener != null ? opener.getInt("slot", 17) : 17;
        mc.openerName = opener != null ? opener.getString("name", "&b&lMenu") : "&b&lMenu";
        mc.openerLore = opener != null ? opener.getStringList("lore") : List.of("&aClick to Open the Menu!");
        if (mc.openerLore.isEmpty()) mc.openerLore = List.of("&aClick to Open the Menu!");

        mc.fillerEnabled = cfg.getBoolean("menu.filler.enabled", true);
        String fillerMatName = cfg.getString("menu.filler.material", "BLACK_STAINED_GLASS_PANE");
        try {
            mc.fillerMaterial = Material.valueOf(fillerMatName);
        } catch (IllegalArgumentException e) {
            GlovedPlugin.logWarning("Unknown filler material '" + fillerMatName + "', defaulting to BLACK_STAINED_GLASS_PANE");
            mc.fillerMaterial = Material.BLACK_STAINED_GLASS_PANE;
        }
        mc.fillerName = cfg.getString("menu.filler.name", " ");
        if (mc.fillerName == null) mc.fillerName = " ";
        mc.fillerLore = cfg.getStringList("menu.filler.lore");

        mc.layout = buildLayout(mc.rows, mc.itemsPerRow, mc.items);
        return mc;
    }

    /**
     * Resolves a sound by namespaced key ({@code minecraft:entity.experience_orb.pickup})
     * or legacy enum-style name ({@code ENTITY_EXPERIENCE_ORB_PICKUP}) via the
     * Paper registry, avoiding the deprecated-for-removal {@code Sound.valueOf}.
     */
    public static Sound parseSound(String raw) {
        String fallback = "ENTITY_EXPERIENCE_ORB_PICKUP";
        if (raw == null || raw.isBlank()) {
            GlovedPlugin.logWarning("Missing menu.sound, defaulting to " + fallback);
            return resolveSound(fallback);
        }
        Sound sound = resolveSound(raw.trim());
        if (sound == null) {
            GlovedPlugin.logWarning("Unknown sound '" + raw + "', defaulting to " + fallback);
            return resolveSound(fallback);
        }
        return sound;
    }

    private static Sound resolveSound(String input) {
        Registry<Sound> sounds = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);
        NamespacedKey key = NamespacedKey.fromString(input.toLowerCase(Locale.ROOT));
        if (key != null) {
            Sound byKey = sounds.get(key);
            if (byKey != null) return byKey;
        }
        // Legacy UPPER_SNAKE names (e.g. ENTITY_EXPERIENCE_ORB_PICKUP) match the
        // registry's public constants. Compared reflectively: the enum-style
        // accessors (valueOf/values/getKey/key) are all deprecated for removal.
        for (Field field : Sound.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Sound.class.isAssignableFrom(field.getType())
                    && field.getName().equalsIgnoreCase(input)) {
                try {
                    return (Sound) field.get(null);
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int toInt(Object o, int def) {        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean toBoolean(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase();
        return switch (s) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> def;
        };
    }

    static int anchorBaseSlot(Anchor anchor, int rows) {
        int lastRow = rows - 1;
        int midRow = (rows - 1) / 2;
        return switch (anchor) {
            case TOP_LEFT -> 0;
            case TOP_CENTER -> 4;
            case TOP_RIGHT -> 8;
            case LEFT -> midRow * 9;
            case CENTER -> midRow * 9 + 4;
            case RIGHT -> midRow * 9 + 8;
            case BOTTOM_LEFT -> lastRow * 9;
            case BOTTOM_CENTER -> lastRow * 9 + 4;
            case BOTTOM_RIGHT -> lastRow * 9 + 8;
        };
    }

    static List<Integer> centeredCols(int count) {
        List<Integer> cols = new ArrayList<>();
        if (count <= 0) return cols;
        if (count >= 9) {
            for (int c = 0; c < 9; c++) cols.add(c);
            return cols;
        }
        if (count % 2 == 1) {
            int start = 4 - count / 2;
            for (int c = start; c < start + count; c++) cols.add(c);
        } else {
            int half = count / 2;
            for (int c = 4 - half; c <= 3; c++) cols.add(c);
            for (int c = 5; c < 5 + half; c++) cols.add(c);
        }
        return cols;
    }

    static List<Integer> colsFor(int count, int anchorCol) {
        List<Integer> cols = new ArrayList<>();
        if (count <= 0) return cols;
        if (count >= 9) {
            for (int c = 0; c < 9; c++) cols.add(c);
            return cols;
        }
        if (anchorCol <= 2) {
            for (int c = 0; c < count; c++) cols.add(c);
            return cols;
        }
        if (anchorCol >= 6) {
            for (int c = 8 - count + 1; c <= 8; c++) cols.add(c);
            return cols;
        }
        return centeredCols(count);
    }

    public static Map<Integer, MenuItem> buildLayout(int rows, int perRow, List<MenuItem> items) {
        Map<Integer, MenuItem> layout = new HashMap<>();
        items = items.stream().filter(MenuItem::enabled).toList();
        if (items.isEmpty()) return layout;
        int size = rows * 9;

        // Explicit slots pin first. Everything else resolves an anchor
        // (default CENTER) plus offsets to a candidate slot; items sharing a
        // candidate arrange around it as a block instead of overwriting.
        Map<Integer, List<MenuItem>> groups = new LinkedHashMap<>();
        for (MenuItem mi : items) {
            if (mi.slot() != null) {
                int pinned = mi.slot();
                if (pinned < 0 || pinned >= size) {
                    GlovedPlugin.logWarning("Pinned slot " + pinned + " out of bounds, skipping " + mi.material());
                    continue;
                }
                if (layout.containsKey(pinned)) {
                    GlovedPlugin.logWarning("Slot collision at " + pinned + ", overwriting with " + mi.material());
                }
                layout.put(pinned, mi);
                continue;
            }
            Anchor anchor = mi.anchor() != null ? mi.anchor() : Anchor.CENTER;
            int base = anchorBaseSlot(anchor, rows);
            int row = Math.min(rows - 1, Math.max(0, base / 9 + mi.offsetY()));
            int col = Math.min(8, Math.max(0, base % 9 + mi.offsetX()));
            int candidate = row * 9 + col;
            groups.computeIfAbsent(candidate, k -> new ArrayList<>()).add(mi);
        }

        for (Map.Entry<Integer, List<MenuItem>> groupEntry : groups.entrySet()) {
            int candidate = groupEntry.getKey();
            List<MenuItem> group = groupEntry.getValue();
            if (group.size() == 1 && !layout.containsKey(candidate)) {
                layout.put(candidate, group.get(0));
                continue;
            }
            // Shared anchor: block centered on the anchor row, columns
            // justified to the anchor side (middle anchors center, edge
            // anchors align outward). Taken spots are skipped.
            int candRow = candidate / 9;
            int candCol = candidate % 9;
            int usedRows = Math.min(rows, (int) Math.ceil(group.size() / (double) perRow));
            int firstRow = Math.min(rows - usedRows, Math.max(0, candRow - (usedRows - 1) / 2));
            List<Integer> spots = new ArrayList<>();
            for (int r = 0; r < usedRows; r++) {
                int remaining = Math.min(perRow, group.size() - r * perRow);
                for (int col : colsFor(remaining, candCol)) {
                    spots.add((firstRow + r) * 9 + col);
                }
            }
            int idx = 0;
            for (MenuItem mi : group) {
                while (idx < spots.size() && layout.containsKey(spots.get(idx))) idx++;
                if (idx < spots.size()) {
                    layout.put(spots.get(idx++), mi);
                    continue;
                }
                // Fallback: nearest free slot in the group rows by column
                // distance, then anywhere, then skip with a warning.
                Integer free = null;
                int bestDist = Integer.MAX_VALUE;
                for (int r = 0; r < usedRows; r++) {
                    for (int c = 0; c < 9; c++) {
                        int slot = (firstRow + r) * 9 + c;
                        if (layout.containsKey(slot)) continue;
                        int dist = Math.abs(c - candCol) + Math.abs((firstRow + r) - candRow) * 2;
                        if (dist < bestDist) {
                            bestDist = dist;
                            free = slot;
                        }
                    }
                }
                if (free == null) {
                    for (int s = 0; s < size && free == null; s++) {
                        if (!layout.containsKey(s)) free = s;
                    }
                }
                if (free != null) {
                    layout.put(free, mi);
                } else {
                    GlovedPlugin.logWarning("Menu full, skipping " + mi.material());
                }
            }
        }
        return layout;
    }
}
