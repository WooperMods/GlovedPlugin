package dev.gloved.glovedplugin.editor;

import dev.gloved.glovedplugin.MenuConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts menu items between {@link MenuConfig.MenuItem} records and the
 * plain maps Bukkit stores in config.yml. Pure data, no server access, so the
 * round trip is verifiable headless. Every written value matches what
 * {@code MenuConfig.load} reads back, including explicit nulls for auto
 * anchor/slot.
 */
public final class MenuEditorCodec {

    private MenuEditorCodec() {}

    public static Map<String, Object> serializeItem(MenuConfig.MenuItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("material", item.material().name());
        map.put("name", item.name());
        map.put("lore", new ArrayList<>(item.lore()));
        map.put("command", item.command());
        map.put("enabled", item.enabled());
        map.put("op", item.opOnly());
        map.put("anchor", item.anchor() == null ? null : item.anchor().name().toLowerCase(Locale.ROOT));
        map.put("offset-x", item.offsetX());
        map.put("offset-y", item.offsetY());
        map.put("slot", item.slot());
        return map;
    }

    public static List<Map<String, Object>> serializeItems(List<MenuConfig.MenuItem> items) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MenuConfig.MenuItem item : items) out.add(serializeItem(item));
        return out;
    }
}
