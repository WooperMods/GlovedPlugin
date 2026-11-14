package dev.gloved.glovedplugin.summon.soul.domain;

import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitDefinition;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitHooks;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRegistry;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry-backed soul trait identity (replaces legacy enum). Persisted IDs remain unchanged.
 */
public final class SoulTrait {
    private final String id;

    private SoulTrait(String id) {
        this.id = id;
    }

    public static SoulTrait of(String id) {
        String canonical = canonicalize(id);
        return canonical == null ? null : new SoulTrait(canonical);
    }

    /** Compatibility alias for enum-style parsing; returns null for unknown/blank ids. */
    public static SoulTrait fromId(String id) {
        return of(id);
    }

    /** Compatibility helper similar to enum valueOf, but tolerant/canonicalizing; returns null if blank. */
    public static SoulTrait valueOf(String id) {
        SoulTrait t = of(id);
        if (t == null) throw new IllegalArgumentException("Unknown soul trait id: " + id);
        return t;
    }

    public static SoulTrait fromString(String id) {
        return of(id);
    }

    /** Stable persisted/canonical identifier. */
    public String id() { return id; }

    /** Compatibility alias for enum {@code name()}. */
    public String name() { return id; }

    /** Resolved definition from the active registry, if registered. */
    public Optional<SoulTraitDefinition> definitionOptional() {
        SoulTraitRegistry reg = SoulTraitRegistry.getActive();
        if (reg == null) return Optional.empty();
        return reg.byId(id);
    }

    public SoulTraitDefinition definition() {
        return definitionOptional().orElse(null);
    }

    public String displayName() {
        SoulTraitDefinition def = definition();
        if (def != null && def.displayName() != null && !def.displayName().isBlank()) return def.displayName();
        return prettyFallback(id);
    }

    public List<String> guideDescription() {
        SoulTraitDefinition def = definition();
        if (def != null && def.guideDescription() != null && !def.guideDescription().isEmpty()) {
            return def.guideDescription();
        }
        return List.of("No description available.");
    }

    public SoulTraitHooks hooks() {
        SoulTraitDefinition def = definition();
        return def != null && def.hooks() != null ? def.hooks() : SoulTraitHooks.NONE;
    }

    /** All registered traits in stable registry order (compat helper). */
    public static List<SoulTrait> all() {
        SoulTraitRegistry reg = SoulTraitRegistry.getActive();
        if (reg == null) return List.of();
        return reg.all().stream().map(d -> new SoulTrait(d.id())).collect(Collectors.toUnmodifiableList());
    }

    /** Compatibility alias for enum values(). */
    public static SoulTrait[] values() {
        List<SoulTrait> all = all();
        return all.toArray(new SoulTrait[0]);
    }

    public static String canonicalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        return t.toUpperCase(Locale.ROOT);
    }

    private static String prettyFallback(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SoulTrait that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
