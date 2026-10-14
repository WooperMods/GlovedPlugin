package dev.gloved.glovedplugin.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player combo cursor: which {@code attacks[]} stage fires next, which
 * hand dual-wield should use, and whether an early (on-cooldown) swing is
 * queued for hold-to-spam.
 */
public final class ComboTracker {

    public static final class State {
        public int comboIndex;
        public long lastSwingTick;
        public boolean nextIsOffhand;
        public boolean pendingQueued;
        public long pendingTick;
        /** Stage armed by the last arm swing, consumed by the damage event. */
        public int armedStage = -1;
        public long armedTick = Long.MIN_VALUE;
        public boolean armedOffhand;
        /** Swings in the current burst window (hold-to-spam detection). */
        public int burst;
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    public State state(UUID id) {
        return states.computeIfAbsent(id, k -> new State());
    }

    public void clear(UUID id) {
        states.remove(id);
    }

    /** Advance combo, resetting if idle longer than {@code resetTicks}. */
    public int nextStage(UUID id, long nowTick, long resetTicks, int comboSize) {
        State s = state(id);
        if (nowTick - s.lastSwingTick > resetTicks) {
            s.comboIndex = 0;
        }
        int stage = comboSize <= 0 ? 0 : Math.floorMod(s.comboIndex, comboSize);
        s.comboIndex++;
        s.lastSwingTick = nowTick;
        s.pendingQueued = false;
        return stage;
    }
}
