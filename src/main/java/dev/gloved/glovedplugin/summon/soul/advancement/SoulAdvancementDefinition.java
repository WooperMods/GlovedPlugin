package dev.gloved.glovedplugin.summon.soul.advancement;

import org.bukkit.NamespacedKey;

import java.util.Objects;

/** Immutable definition of a soul advancement module entry: identity, reward, and behavior hooks. */
public interface SoulAdvancementDefinition {
    /** Stable advancement key (e.g. gloved:souls/capture_first). */
    NamespacedKey key();

    /** Stable order for deterministic iteration/registration. */
    default int order() { return 0; }

    /** One-time Essence reward granted when this advancement is first completed. */
    default int essenceReward() { return 0; }

    SoulAdvancementHooks hooks();

    static SoulAdvancementDefinition of(NamespacedKey key, int order, int essenceReward, SoulAdvancementHooks hooks) {
        Objects.requireNonNull(key, "key");
        SoulAdvancementHooks safeHooks = hooks != null ? hooks : SoulAdvancementHooks.NONE;
        int reward = Math.max(0, essenceReward);
        return new SoulAdvancementDefinition() {
            @Override public NamespacedKey key() { return key; }
            @Override public int order() { return order; }
            @Override public int essenceReward() { return reward; }
            @Override public SoulAdvancementHooks hooks() { return safeHooks; }
        };
    }

    static SoulAdvancementDefinition of(NamespacedKey key, int order, SoulAdvancementHooks hooks) {
        return of(key, order, 0, hooks);
    }

    static SoulAdvancementDefinition of(NamespacedKey key, SoulAdvancementHooks hooks) {
        return of(key, 0, 0, hooks);
    }
}
