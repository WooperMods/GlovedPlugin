package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.Bukkit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory XP cap and kill-attribution state for summon progression v1. */
final class SummonXpState {
    static final class KillCredit {
        final UUID victimUuid;
        final UUID ownerUuid;
        final String profileName;
        final long creditTick;

        KillCredit(UUID victimUuid, UUID ownerUuid, String profileName, long creditTick) {
            this.victimUuid = victimUuid;
            this.ownerUuid = ownerUuid;
            this.profileName = profileName;
            this.creditTick = creditTick;
        }
    }

    private static final class XpSample {
        final long tick;
        final double amount;
        XpSample(long tick, double amount) { this.tick = tick; this.amount = amount; }
    }

    private final Map<UUID, Long> lastHitXpTickByOwner = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHitXpTickByOwnerTarget = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<XpSample>> hitXpWindowByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<XpSample>> killXpWindowByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, KillCredit> recentKillCredit = new ConcurrentHashMap<>();

    void clear() {
        lastHitXpTickByOwner.clear();
        lastHitXpTickByOwnerTarget.clear();
        hitXpWindowByOwner.clear();
        killXpWindowByOwner.clear();
        recentKillCredit.clear();
    }

    boolean tryConsumeHitXp(UUID ownerId, UUID targetId, SummonConfig cfg, long nowTick) {
        double amount = cfg.getProgressionHitXpAmount();
        if (amount <= 0) return false;
        long lastOwner = lastHitXpTickByOwner.getOrDefault(ownerId, Long.MIN_VALUE / 4);
        if (nowTick - lastOwner < cfg.getProgressionHitOwnerCooldownTicks()) return false;
        String key = ownerId + ":" + targetId;
        long lastPair = lastHitXpTickByOwnerTarget.getOrDefault(key, Long.MIN_VALUE / 4);
        if (nowTick - lastPair < cfg.getProgressionHitTargetCooldownTicks()) return false;
        if (!consumeWindow(hitXpWindowByOwner, ownerId, amount, cfg.getProgressionHitMaxPerMinute(), nowTick)) return false;
        lastHitXpTickByOwner.put(ownerId, nowTick);
        lastHitXpTickByOwnerTarget.put(key, nowTick);
        return true;
    }

    boolean tryConsumeKillXp(UUID ownerId, SummonConfig cfg, long nowTick) {
        double amount = cfg.getProgressionKillXpAmount();
        if (amount <= 0) return false;
        return consumeWindow(killXpWindowByOwner, ownerId, amount, cfg.getProgressionKillMaxPerMinute(), nowTick);
    }

    void recordKillCredit(UUID victimId, UUID ownerId, String profileName, long nowTick) {
        if (victimId == null || ownerId == null) return;
        recentKillCredit.put(victimId, new KillCredit(victimId, ownerId, profileName, nowTick));
    }

    KillCredit consumeKillCredit(UUID victimId, SummonConfig cfg, long nowTick) {
        KillCredit credit = recentKillCredit.remove(victimId);
        if (credit == null) return null;
        if (nowTick - credit.creditTick > cfg.getProgressionKillAttributionWindowTicks()) return null;
        if (Bukkit.getPlayer(credit.ownerUuid) == null) return null;
        return credit;
    }

    private static boolean consumeWindow(Map<UUID, Deque<XpSample>> windows, UUID ownerId, double amount, double maxPerMinute, long nowTick) {
        if (maxPerMinute <= 0) return false;
        Deque<XpSample> dq = windows.computeIfAbsent(ownerId, k -> new ArrayDeque<>());
        synchronized (dq) {
            long cutoff = nowTick - 1200L; // 60s
            while (!dq.isEmpty() && dq.peekFirst().tick < cutoff) dq.removeFirst();
            double total = 0;
            for (XpSample s : dq) total += s.amount;
            if (total + amount > maxPerMinute + 1.0e-9) return false;
            dq.addLast(new XpSample(nowTick, amount));
            return true;
        }
    }
}
