package dev.gloved.glovedplugin.summon.soul.trait;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Fluent builder for {@link SoulTraitDefinition}. Does not self-register. */
public final class SoulTraitBuilder {
    private String id;
    private String displayName;
    private List<String> guideDescription = List.of();
    private int order;
    private boolean rollable = true;
    private Predicate<SoulTraitRollContext> rollCondition = ctx -> true;
    private SoulTraitHooks hooks = SoulTraitHooks.NONE;

    private SoulTraitBuilder() {}

    public static SoulTraitBuilder trait(String id) {
        SoulTraitBuilder b = new SoulTraitBuilder();
        b.id = id;
        return b;
    }

    public SoulTraitBuilder displayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public SoulTraitBuilder description(String... lines) {
        if (lines == null || lines.length == 0) {
            this.guideDescription = List.of();
        } else {
            List<String> out = new ArrayList<>(lines.length);
            for (String l : lines) appendDescriptionLines(out, l);
            this.guideDescription = List.copyOf(out);
        }
        return this;
    }

    public SoulTraitBuilder guideDescription(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            this.guideDescription = List.of();
        } else {
            List<String> out = new ArrayList<>(lines.size());
            for (String l : lines) appendDescriptionLines(out, l);
            this.guideDescription = List.copyOf(out);
        }
        return this;
    }

    private static void appendDescriptionLines(List<String> out, String raw) {
        if (raw == null) return;
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        for (String part : normalized.split("[;\n]", -1)) {
            if (part == null) continue;
            String line = part.trim();
            if (!line.isEmpty()) out.add(line);
        }
    }

    public SoulTraitBuilder order(int order) {
        this.order = order;
        return this;
    }

    public SoulTraitBuilder rollable(boolean rollable) {
        this.rollable = rollable;
        return this;
    }

    /** Optional generation-time eligibility condition (in addition to {@link #rollable(boolean)}). */
    public SoulTraitBuilder rollIf(Predicate<SoulTraitRollContext> condition) {
        this.rollCondition = condition != null ? condition : (ctx -> true);
        return this;
    }

    public SoulTraitBuilder hooks(SoulTraitHooks hooks) {
        this.hooks = hooks != null ? hooks : SoulTraitHooks.NONE;
        return this;
    }

    public SoulTraitDefinition build() {
        String canonicalId = canonicalize(id);
        if (canonicalId == null || canonicalId.isBlank()) {
            throw new IllegalArgumentException("Trait id must be non-blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Trait displayName must be non-blank for " + canonicalId);
        }
        if (guideDescription == null || guideDescription.isEmpty()) {
            throw new IllegalArgumentException("Trait guideDescription must be non-empty for " + canonicalId);
        }
        SoulTraitHooks safeHooks = hooks != null ? hooks : SoulTraitHooks.NONE;
        Predicate<SoulTraitRollContext> safeCondition = rollCondition != null ? rollCondition : (ctx -> true);
        return new DefaultSoulTraitDefinition(canonicalId, displayName, List.copyOf(guideDescription), order, rollable, safeCondition, safeHooks);
    }

    static String canonicalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        return t.toUpperCase(Locale.ROOT);
    }

    private record DefaultSoulTraitDefinition(
            String id,
            String displayName,
            List<String> guideDescription,
            int order,
            boolean rollable,
            Predicate<SoulTraitRollContext> rollCondition,
            SoulTraitHooks hooks
    ) implements SoulTraitDefinition {
        private DefaultSoulTraitDefinition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            guideDescription = guideDescription == null ? List.of() : List.copyOf(guideDescription);
            rollCondition = rollCondition != null ? rollCondition : (ctx -> true);
            Objects.requireNonNull(hooks, "hooks");
        }

        @Override
        public boolean canRoll(SoulTraitRollContext ctx) {
            if (!rollable) return false;
            try {
                return rollCondition.test(ctx);
            } catch (Throwable ignored) {
                return true;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SoulTraitDefinition that)) return false;
            return id.equals(that.id());
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
