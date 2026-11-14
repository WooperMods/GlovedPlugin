package dev.gloved.glovedplugin.summon.soul.trait;

import java.util.List;

/** Immutable definition of a soul trait: identity, presentation, and behavior hooks. */
public interface SoulTraitDefinition {
    /** Stable persisted key (e.g. VAMPIRIC). Must remain unchanged for existing traits. */
    String id();

    String displayName();

    /** Player-facing guide/lore description lines (immutable, non-empty). */
    List<String> guideDescription();

    /** Stable sort order for guide/pool iteration. */
    int order();

    /** Included in generation roll pool when true. */
    default boolean rollable() { return true; }

    /** Optional generation-time eligibility condition (in addition to {@link #rollable()}). */
    default boolean canRoll(SoulTraitRollContext ctx) { return true; }

    SoulTraitHooks hooks();
}
