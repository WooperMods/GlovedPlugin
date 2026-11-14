package dev.gloved.glovedplugin.summon.soul.trait;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Generic per-minion per-trait proc cooldown (ICD) tracker in server ticks. */
public final class TraitProcCooldowns {
    private final Map<String, Long> lastProcTick = new ConcurrentHashMap<>();

    private static String key(UUID minionId, String traitId) {
        return (minionId != null ? minionId : new UUID(0, 0)) + ":" + (traitId != null ? traitId : "");
    }

    /** Returns true if cooldown has elapsed and consumes it (records nowTick). */
    public boolean tryConsume(UUID minionId, String traitId, long nowTick, int cooldownTicks) {
        String k = key(minionId, traitId);
        if (cooldownTicks <= 0) {
            lastProcTick.put(k, nowTick);
            return true;
        }
        Long last = lastProcTick.get(k);
        if (last != null && nowTick - last < cooldownTicks) return false;
        lastProcTick.put(k, nowTick);
        return true;
    }

    public void clear(UUID minionId) {
        if (minionId == null) return;
        String prefix = minionId + ":";
        lastProcTick.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void clearAll() {
        lastProcTick.clear();
    }
}
