package dev.gloved.glovedplugin.summon.soul.advancement;

import org.bukkit.NamespacedKey;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime registry of soul advancement definitions (module-driven hook dispatch). */
public final class SoulAdvancementRegistry {
    private static volatile SoulAdvancementRegistry active;

    private final Map<String, SoulAdvancementDefinition> defs = new LinkedHashMap<>();
    private final Set<String> unknownLogged = ConcurrentHashMap.newKeySet();
    private List<SoulAdvancementDefinition> allView = List.of();
    private boolean frozen;

    public static SoulAdvancementRegistry getActive() { return active; }
    public static void setActive(SoulAdvancementRegistry registry) { active = registry; }

    public synchronized void register(SoulAdvancementDefinition def) {
        if (def == null) throw new IllegalArgumentException("def");
        if (frozen) throw new IllegalStateException("SoulAdvancementRegistry is frozen");
        NamespacedKey key = def.key();
        if (key == null) throw new IllegalArgumentException("def.key null");
        String id = key.toString();
        if (defs.containsKey(id)) throw new IllegalStateException("Duplicate soul advancement id: " + id);
        defs.put(id, def);
        rebuildViews();
    }

    public synchronized Optional<SoulAdvancementDefinition> byKey(NamespacedKey key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(defs.get(key.toString()));
    }

    public synchronized List<SoulAdvancementDefinition> all() { return allView; }

    public synchronized void freeze() { this.frozen = true; }

    public synchronized void clear() {
        if (frozen) frozen = false;
        defs.clear();
        unknownLogged.clear();
        rebuildViews();
    }

    private void rebuildViews() {
        List<SoulAdvancementDefinition> all = new ArrayList<>(defs.values());
        all.sort(Comparator.comparingInt(SoulAdvancementDefinition::order).thenComparing(d -> d.key().toString()));
        allView = Collections.unmodifiableList(all);
    }
}
