package dev.gloved.glovedplugin.summon.soul.trait;

import dev.gloved.glovedplugin.core.logging.DebugLog;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime registry of soul trait definitions. */
public final class SoulTraitRegistry {
    private static volatile SoulTraitRegistry active;

    private final Map<String, SoulTraitDefinition> defs = new LinkedHashMap<>();
    private final Set<String> unknownLogged = ConcurrentHashMap.newKeySet();
    private List<SoulTraitDefinition> allView = List.of();
    private List<SoulTraitDefinition> rollableView = List.of();
    private boolean frozen;

    /** Returns the active registry (set during bootstrap), or null if not initialized. */
    public static SoulTraitRegistry getActive() {
        return active;
    }

    /** Sets the active registry for global resolution helpers. */
    public static void setActive(SoulTraitRegistry registry) {
        active = registry;
    }

    public synchronized void register(SoulTraitDefinition def) {
        if (def == null) throw new IllegalArgumentException("def");
        if (frozen) throw new IllegalStateException("SoulTraitRegistry is frozen");
        String id = SoulTraitBuilder.canonicalize(def.id());
        if (id == null || id.isBlank()) throw new IllegalArgumentException("def.id blank");
        if (defs.containsKey(id)) {
            throw new IllegalStateException("Duplicate soul trait id: " + id);
        }
        defs.put(id, def);
        rebuildViews();
    }

    public synchronized Optional<SoulTraitDefinition> byId(String id) {
        String key = SoulTraitBuilder.canonicalize(id);
        if (key == null) return Optional.empty();
        SoulTraitDefinition def = defs.get(key);
        if (def == null) {
            logUnknownOnce(key);
        }
        return Optional.ofNullable(def);
    }

    public SoulTraitDefinition getOrNull(String id) {
        return byId(id).orElse(null);
    }

    public boolean isRegistered(String id) {
        String key = SoulTraitBuilder.canonicalize(id);
        return key != null && defs.containsKey(key);
    }

    public synchronized List<SoulTraitDefinition> all() {
        return allView;
    }

    public synchronized List<SoulTraitDefinition> rollable() {
        return rollableView;
    }

    public synchronized void freeze() {
        this.frozen = true;
    }

    public synchronized void clear() {
        if (frozen) frozen = false;
        defs.clear();
        unknownLogged.clear();
        rebuildViews();
    }

    private void rebuildViews() {
        List<SoulTraitDefinition> all = new ArrayList<>(defs.values());
        allView = Collections.unmodifiableList(all);
        List<SoulTraitDefinition> rollable = new ArrayList<>();
        for (SoulTraitDefinition d : all) {
            if (d != null && d.rollable()) rollable.add(d);
        }
        rollableView = Collections.unmodifiableList(rollable);
    }

    private void logUnknownOnce(String id) {
        if (id == null) return;
        if (unknownLogged.add(id)) {
            try { DebugLog.log("INFO", "[Souls] Unknown/unregistered trait id ignored: " + id); } catch (Throwable ignored) {}
        }
    }
}
