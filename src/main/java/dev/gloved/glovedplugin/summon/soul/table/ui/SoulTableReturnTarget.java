package dev.gloved.glovedplugin.summon.soul.table.ui;

import java.util.Locale;

/** Navigation target for returning from the Soul Guide to a Soul Table context. */
public enum SoulTableReturnTarget {
    HUB,
    COMBINE,
    ENHANCE,
    REFORGE,
    SALVAGE,
    CONDENSE,
    INFUSION;

    public static SoulTableReturnTarget fromNullable(String raw) {
        if (raw == null || raw.isBlank()) return HUB;
        try {
            return SoulTableReturnTarget.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return HUB;
        }
    }
}
