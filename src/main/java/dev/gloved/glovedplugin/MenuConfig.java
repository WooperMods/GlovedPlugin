package dev.gloved.glovedplugin;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
                           Anchor anchor, int offsetX, int offsetY, Integer slot) {}

    private Component titleComponent;
    private String rawTitle;
    private int rows;
    private int itemsPerRow;
    private Sound sound;
    private List<MenuItem> items;
    private Map<Integer, MenuItem> layout;

    private Material openerMaterial;
    private int openerSlot;
    private String openerName;
    private List<String> openerLore;

    public Component titleComponent() { return titleComponent; }
    public String rawTitle() { return rawTitle; }
    public int rows() { return rows; }
    public int itemsPerRow() { return itemsPerRow; }
    public Sound sound() { return sound; }
    public List<MenuItem> items() { return items; }
    public Map<Integer, MenuItem> layout() { return layout; }
    public Material openerMaterial() { return openerMaterial; }
    public int openerSlot() { return openerSlot; }
    public String openerName() { return openerName; }
    public List<String> openerLore() { return openerLore; }

    public String commandForSlot(int slot) {
        MenuItem item = layout.get(slot);
        return item == null ? null : item.command();
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
        try {
            mc.sound = Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            GlovedPlugin.logWarning("Unknown sound '" + soundName + "', defaulting to ENTITY_EXPERIENCE_ORB_PICKUP");
            mc.sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        }

        mc.items = new ArrayList<>();
        List<?> rawItems = cfg.getList("menu.items", List.of());
        for (Object obj : rawItems) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            Object enabledObj = map.get("enabled");
            boolean enabled = enabledObj == null || toBoolean(enabledObj, true);
            if (!enabled) continue;
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
            mc.items.add(new MenuItem(mat, name, lore, command, anchor, offsetX, offsetY, slot));
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

        mc.layout = buildLayout(mc.rows, mc.itemsPerRow, mc.items);
        return mc;
    }

    private static int toInt(Object o, int def) {
        if (o == null) return def;
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

    static Map<Integer, MenuItem> buildLayout(int rows, int perRow, List<MenuItem> items) {
        Map<Integer, MenuItem> layout = new HashMap<>();
        if (items.isEmpty()) return layout;
        int size = rows * 9;

        List<MenuItem> flow = new ArrayList<>();

        // 1. Pinned items first: explicit slot wins, then anchor + offsets.
        for (MenuItem mi : items) {
            Integer pinned = null;
            if (mi.slot() != null) {
                pinned = mi.slot();
            } else if (mi.anchor() != null) {
                int base = anchorBaseSlot(mi.anchor(), rows);
                int baseRow = base / 9;
                int baseCol = base % 9;
                int row = Math.min(rows - 1, Math.max(0, baseRow + mi.offsetY()));
                int col = Math.min(8, Math.max(0, baseCol + mi.offsetX()));
                pinned = row * 9 + col;
            }
            if (pinned != null) {
                if (pinned < 0 || pinned >= size) {
                    GlovedPlugin.logWarning("Pinned slot " + pinned + " out of bounds, skipping " + mi.material());
                    continue;
                }
                if (layout.containsKey(pinned)) {
                    GlovedPlugin.logWarning("Slot collision at " + pinned + ", overwriting with " + mi.material());
                }
                layout.put(pinned, mi);
            } else {
                flow.add(mi);
            }
        }

        if (flow.isEmpty()) return layout;

        // 2. Flow items: centered block, every row symmetric.
        // Odd counts fill contiguously (e.g. 3 -> cols 3,4,5).
        // Even counts skip the middle column (e.g. 2 -> cols 3,5) so
        // left/right empty slots stay equal and the row reads as centered.
        // Vertical block uses ceil so a remainder pushes down one row
        // instead of sitting high.
        int usedRows = (int) Math.ceil(flow.size() / (double) perRow);
        if (usedRows > rows) usedRows = rows;
        int firstRow = (rows - usedRows + 1) / 2;
        List<Integer> candidates = new ArrayList<>();
        for (int r = 0; r < usedRows; r++) {
            int remaining = Math.min(perRow, flow.size() - r * perRow);
            for (int col : centeredCols(remaining)) {
                candidates.add((firstRow + r) * 9 + col);
            }
        }
        int candIdx = 0;
        for (MenuItem mi : flow) {
            while (candIdx < candidates.size() && layout.containsKey(candidates.get(candIdx))) {
                candIdx++;
            }
            if (candIdx >= candidates.size()) {
                // Fallback: next free slot inside the block, else skip.
                boolean placedFallback = false;
                for (int r = 0; r < usedRows && !placedFallback; r++) {
                    for (int c = 0; c < 9 && !placedFallback; c++) {
                        int slot = (firstRow + r) * 9 + c;
                        if (!layout.containsKey(slot)) {
                            layout.put(slot, mi);
                            placedFallback = true;
                        }
                    }
                }
                if (!placedFallback) {
                    GlovedPlugin.logWarning("Menu full, skipping " + mi.material());
                }
                continue;
            }
            layout.put(candidates.get(candIdx++), mi);
        }
        return layout;
    }
}
