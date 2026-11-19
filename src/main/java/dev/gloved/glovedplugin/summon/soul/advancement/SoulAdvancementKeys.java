package dev.gloved.glovedplugin.summon.soul.advancement;

import org.bukkit.NamespacedKey;

/** Stable advancement IDs for the Soul progression tree. */
public final class SoulAdvancementKeys {

    public static final String NAMESPACE = "gloved";

    public static final NamespacedKey ROOT = key("souls/root");
    public static final NamespacedKey CAPTURE_FIRST = key("souls/capture_first");
    public static final NamespacedKey CAPTURE_MYTHIC = key("souls/capture_mythic");
    public static final NamespacedKey CAPTURE_10 = key("souls/capture_10");
    public static final NamespacedKey CAPTURE_50 = key("souls/capture_50");
    public static final NamespacedKey CAPTURE_100 = key("souls/capture_100");
    public static final NamespacedKey GROUP_ACTIVATE = key("souls/group_activate");
    public static final NamespacedKey RESPAWN_FIRST = key("souls/respawn_first");
    public static final NamespacedKey MASTERY_5 = key("souls/mastery_5");
    public static final NamespacedKey MASTERY_20 = key("souls/mastery_20");
    public static final NamespacedKey CODEX_MASTERED = key("souls/codex_mastered");
    public static final NamespacedKey TABLE_FIRST = key("souls/table_first");

    // Additional milestones
    public static final NamespacedKey CAPTURE_EPIC = key("souls/capture_epic");
    public static final NamespacedKey CAPTURE_LEGENDARY = key("souls/capture_legendary");
    public static final NamespacedKey CAPTURE_250 = key("souls/capture_250");
    public static final NamespacedKey CAPTURE_500 = key("souls/capture_500");
    public static final NamespacedKey GROUP_10 = key("souls/group_10");
    public static final NamespacedKey GROUP_50 = key("souls/group_50");
    public static final NamespacedKey RESPAWN_10 = key("souls/respawn_10");
    public static final NamespacedKey MASTERY_10 = key("souls/mastery_10");
    public static final NamespacedKey TABLE_ENHANCE = key("souls/table_enhance");
    public static final NamespacedKey TABLE_REFORGE = key("souls/table_reforge");
    public static final NamespacedKey TABLE_COMBINE = key("souls/table_combine");
    public static final NamespacedKey TABLE_SALVAGE = key("souls/table_salvage");

    private SoulAdvancementKeys() {}

    private static NamespacedKey key(String path) {
        return new NamespacedKey(NAMESPACE, path);
    }
}
