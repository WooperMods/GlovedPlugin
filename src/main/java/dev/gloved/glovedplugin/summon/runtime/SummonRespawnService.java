package dev.gloved.glovedplugin.summon.runtime;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import dev.gloved.glovedplugin.summon.spawn.SummonEntitySpawner;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages pending auto-respawns for active soul-team slots (ephemeral by default).
 * One pending respawn per (owner, teamIndex, slotIndex).
 */
public class SummonRespawnService {

    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final Map<String, PendingRespawn> pendingBySlot = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, PendingRespawn>> pendingByOwner = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastKillChargeAt = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public SummonRespawnService(GlovedPlugin plugin, SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    public void start() {
        stop();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void stop() {
        if (tickTask != null) {
            try { tickTask.cancel(); } catch (Throwable ignored) {}
            tickTask = null;
        }
    }

    public void shutdown() {
        stop();
        cancelAll("shutdown");
    }

    public void reload() {
        // ephemeral: clear pending on reload to avoid orphan/stale state
        cancelAll("reload");
    }

    public boolean hasPending(UUID ownerId, int teamIndex, int slotIndex) {
        return pendingBySlot.containsKey(slotKey(ownerId, teamIndex, slotIndex));
    }

    public double getRemainingSeconds(UUID ownerId, int teamIndex, int slotIndex) {
        PendingRespawn p = pendingBySlot.get(slotKey(ownerId, teamIndex, slotIndex));
        if (p == null) return 0.0;
        return Math.max(0.0, (p.dueAtMs - System.currentTimeMillis()) / 1000.0);
    }

    /** Seconds until the next pending respawn for a team, or -1 if none pending. */
    public double getNextPendingRemainingSeconds(UUID ownerId, int teamIndex) {
        if (ownerId == null) return -1.0;
        Map<String, PendingRespawn> map = pendingByOwner.get(ownerId);
        if (map == null || map.isEmpty()) return -1.0;
        long now = System.currentTimeMillis();
        long best = Long.MAX_VALUE;
        boolean found = false;
        for (PendingRespawn p : map.values()) {
            if (p == null || p.teamIndex != teamIndex) continue;
            long rem = p.dueAtMs - now;
            if (rem < best) best = rem;
            found = true;
        }
        return found ? Math.max(0.0, best / 1000.0) : -1.0;
    }

    public void onPlayerQuit(UUID ownerId) {
        cancelAll(ownerId, "quit");
        if (ownerId != null) lastKillChargeAt.remove(ownerId);
    }

    public void cancelAll(String reason) {
        for (String key : pendingBySlot.keySet()) {
            PendingRespawn p = pendingBySlot.remove(key);
            if (p != null) removeOwnerIndex(p.ownerId, key);
        }
        pendingByOwner.clear();
    }

    public void cancelAll(UUID ownerId, String reason) {
        if (ownerId == null) return;
        Map<String, PendingRespawn> map = pendingByOwner.remove(ownerId);
        if (map == null) return;
        for (String key : map.keySet()) pendingBySlot.remove(key);
    }

    public void cancelTeam(UUID ownerId, int teamIndex, String reason) {
        if (ownerId == null) return;
        Map<String, PendingRespawn> map = pendingByOwner.get(ownerId);
        if (map == null) return;
        for (PendingRespawn p : java.util.List.copyOf(map.values())) {
            if (p != null && p.teamIndex == teamIndex) cancelSlot(ownerId, teamIndex, p.slotIndex, reason);
        }
    }

    public void cancelSlot(UUID ownerId, int teamIndex, int slotIndex, String reason) {
        String key = slotKey(ownerId, teamIndex, slotIndex);
        PendingRespawn p = pendingBySlot.remove(key);
        if (p != null) removeOwnerIndex(ownerId, key);
    }

    /** Cancel all pending respawns for a specific soul owned by the player (e.g. soul removed/deleted). */
    public void cancelPendingForSoul(UUID ownerId, UUID soulId, String reason) {
        if (ownerId == null || soulId == null) return;
        Map<String, PendingRespawn> map = pendingByOwner.get(ownerId);
        if (map == null || map.isEmpty()) return;
        for (PendingRespawn p : java.util.List.copyOf(map.values())) {
            if (p != null && soulId.equals(p.soulId)) {
                cancelSlot(ownerId, p.teamIndex, p.slotIndex, reason);
            }
        }
    }

    public void scheduleFromDeath(LivingEntity dead, UUID ownerId) {
        if (dead == null || ownerId == null || plugin.getSoulService() == null) return;
        SoulConfig scfg = soulConfig();
        if (scfg == null || !scfg.isRespawnEnabled()) {
            GlovedPlugin.logDebug("[SummonRespawn] Skip schedule: respawn disabled or config unavailable");
            return;
        }
        var pdc = dead.getPersistentDataContainer();
        Integer teamIndex = pdc.get(new NamespacedKey(plugin, "summon_team_index"), PersistentDataType.INTEGER);
        Integer slotIndex = pdc.get(new NamespacedKey(plugin, "summon_team_slot"), PersistentDataType.INTEGER);
        String soulIdRaw = pdc.get(new NamespacedKey(plugin, "summon_soul_id"), PersistentDataType.STRING);
        if (teamIndex == null || slotIndex == null || soulIdRaw == null || soulIdRaw.isBlank()) {
            GlovedPlugin.logDebug("[SummonRespawn] Skip schedule: missing team/slot/soul PDC tags");
            return;
        }
        UUID soulId;
        try { soulId = UUID.fromString(soulIdRaw); } catch (Exception e) {
            GlovedPlugin.logDebug("[SummonRespawn] Skip schedule: invalid soul id tag");
            return;
        }

        // Only for the currently active team
        int activeTeam = tracker != null ? tracker.getActiveTeamIndex(ownerId) : -1;
        if (activeTeam <= 0 || activeTeam != teamIndex) {
            GlovedPlugin.logDebug("[SummonRespawn] Skip schedule: team not active (active=" + activeTeam + ", deadTeam=" + teamIndex + ")");
            return;
        }

        SoulInstance soul = plugin.getSoulService().getSoulCached(soulId);
        if (soul == null || !ownerId.equals(soul.getOwnerId())) {
            GlovedPlugin.logDebug("[SummonRespawn] Skip schedule: soul missing or not owned (" + soulId + ")");
            return;
        }
        double effectiveSeconds = computeEffectiveRespawnSeconds(soul, scfg);
        String key = slotKey(ownerId, teamIndex, slotIndex);
        PendingRespawn pending = new PendingRespawn(ownerId, teamIndex, slotIndex, soulId, soul.getFamilyKey(),
                dead.getLocation().clone(), System.currentTimeMillis() + (long) (effectiveSeconds * 1000.0), 0);
        pendingBySlot.put(key, pending);
        pendingByOwner.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>()).put(key, pending);

        // Play death/pending cue at minion death location
        try { dev.gloved.glovedplugin.summon.vfx.SummonVfx.playSummonPendingDeath(dead, Bukkit.getPlayer(ownerId), scfg); } catch (Throwable ignored) {}
        GlovedPlugin.logDebug("[SummonRespawn] Scheduled slot " + key + " soul=" + soulId + " in " + String.format(java.util.Locale.ROOT, "%.2fs", effectiveSeconds));
    }

    public void onQualifyingKill(UUID ownerId, LivingEntity victim, UUID creditedMinionSoulId, boolean fromSummon, long nowTick) {
        SoulConfig scfg = soulConfig();
        if (ownerId == null || scfg == null || !scfg.isRespawnEnabled() || !scfg.isRespawnKillChargeEnabled()) return;
        if (victim == null) return;
        if (victim instanceof Player && !scfg.isRespawnKillChargeAllowPlayerVictim()) return;
        if (fromSummon && !scfg.isRespawnKillChargeCreditSummonKills()) return;
        if (!fromSummon && !scfg.isRespawnKillChargeCreditOwnerKills()) return;
        int activeTeam = tracker != null ? tracker.getActiveTeamIndex(ownerId) : -1;
        if (activeTeam <= 0) return;

        long nowMs = System.currentTimeMillis();
        int cdTicks = scfg.getRespawnKillChargeInternalCooldownTicks();
        if (cdTicks > 0) {
            long next = lastKillChargeAt.getOrDefault(ownerId, 0L);
            if (nowMs < next) return;
            lastKillChargeAt.put(ownerId, nowMs + cdTicks * 50L);
        }

        Map<String, PendingRespawn> ownerMap = pendingByOwner.get(ownerId);
        if (ownerMap == null || ownerMap.isEmpty()) return;
        boolean chargedAny = false;
        double floor = Math.max(SoulBaseStats.MIN_RESPAWN_SECONDS, scfg.getRespawnGlobalFloorSeconds());
        for (PendingRespawn p : ownerMap.values()) {
            if (p == null || p.teamIndex != activeTeam) continue;
            double remaining = Math.max(0.0, (p.dueAtMs - nowMs) / 1000.0);
            if (remaining <= 0.0) continue;
            double reduction = scfg.getRespawnKillChargeFlatSeconds() + remaining * scfg.getRespawnKillChargePercentOfRemaining();
            double maxPerKill = scfg.getRespawnKillChargeMaxReductionPerKillSeconds();
            if (maxPerKill > 0.0) reduction = Math.min(reduction, maxPerKill);
            double minRemain = Math.max(floor, scfg.getRespawnKillChargeMinRemainingAfterChargeSeconds());
            double maxAllowed = Math.max(0.0, remaining - minRemain);
            reduction = Math.max(0.0, Math.min(reduction, maxAllowed));
            if (reduction <= 0.001) continue;
            p.dueAtMs = Math.max(nowMs, p.dueAtMs - (long) (reduction * 1000.0));
            chargedAny = true;
        }
        if (chargedAny) {
            try { dev.gloved.glovedplugin.summon.vfx.SummonVfx.playRespawnKillCharge(Bukkit.getPlayer(ownerId), victim, scfg); } catch (Throwable ignored) {}
        }
    }

    private void tick() {
        SoulConfig scfg = soulConfig();
        if (scfg == null || !scfg.isRespawnEnabled()) return;
        long now = System.currentTimeMillis();
        // ambient cues (throttled)
        maybePlayAmbientPendingCues(now, scfg);
        for (PendingRespawn p : java.util.List.copyOf(pendingBySlot.values())) {
            if (p == null) continue;
            if (p.retryAtMs > now) continue;
            if (p.dueAtMs > now) continue;
            attemptRespawn(p, scfg);
        }
    }

    private void maybePlayAmbientPendingCues(long now, SoulConfig scfg) {
        if (!scfg.isRespawnVfxEnabled() || !scfg.isRespawnPendingCueEnabled()) return;
        int intervalTicks = Math.max(10, scfg.getRespawnPendingCueIntervalTicks());
        for (Map.Entry<UUID, Map<String, PendingRespawn>> e : pendingByOwner.entrySet()) {
            UUID ownerId = e.getKey();
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            PendingRespawn any = e.getValue().values().stream().findFirst().orElse(null);
            if (any == null) continue;
            if (now - any.lastAmbientCueMs < intervalTicks * 50L) continue;
            any.lastAmbientCueMs = now;
            try { dev.gloved.glovedplugin.summon.vfx.SummonVfx.playRespawnPendingAmbient(Bukkit.getPlayer(ownerId), scfg); } catch (Throwable ignored) {}
        }
    }

    private void attemptRespawn(PendingRespawn p, SoulConfig scfg) {
        String key = slotKey(p.ownerId, p.teamIndex, p.slotIndex);
        // Simplified: respawn when team is active, timer is up, and no live summon is out for this slot.
        int activeTeam = tracker != null ? tracker.getActiveTeamIndex(p.ownerId) : -1;
        if (activeTeam != p.teamIndex) {
            cancelSlot(p.ownerId, p.teamIndex, p.slotIndex, "inactive-team");
            return;
        }
        if (hasLiveEntityForSlot(p.ownerId, p.teamIndex, p.slotIndex)) {
            // Slot is already occupied; keep/clear pending as done to avoid duplicates.
            cancelSlot(p.ownerId, p.teamIndex, p.slotIndex, "slot-alive");
            return;
        }
        Player owner = Bukkit.getPlayer(p.ownerId);
        if (owner == null || !owner.isOnline()) { failOrRetry(p, scfg, "owner-offline"); return; }

        SoulInstance soul = plugin.getSoulService() != null ? plugin.getSoulService().getSoulCached(p.soulId) : null;
        if (soul == null || !p.ownerId.equals(soul.getOwnerId())) { cancelSlot(p.ownerId, p.teamIndex, p.slotIndex, "soul-missing"); return; }
        SummonProfile profile = resolveProfile(soul);
        if (profile == null || plugin.getSummonManager() == null) { failOrRetry(p, scfg, "profile-missing"); return; }

        Location spawnLoc = resolveSpawnLocation(owner, soul, p.lastDeathLocation);
        try {
            var result = SummonEntitySpawner.spawnConfiguredGroup(
                    plugin, plugin.getSummonConfig(), plugin.getSummonManager(), tracker,
                    plugin.getSummonCombatService(), owner, profile, soul, null, spawnLoc, 1,
                    "[SummonRespawn]", p.teamIndex, p.slotIndex);
            if (result != null && result.spawnedCount > 0) {
                pendingBySlot.remove(key);
                removeOwnerIndex(p.ownerId, key);
                try { dev.gloved.glovedplugin.summon.vfx.SummonVfx.playRespawnComplete(spawnLoc, owner, scfg); } catch (Throwable ignored) {}
                try {
                    var adv = plugin.getSoulAdvancementService();
                    if (adv != null) adv.onSoulRespawnCompleted(owner);
                } catch (Throwable ignored) {}
                GlovedPlugin.logDebug("[SummonRespawn] Respawned slot " + key + " soul=" + p.soulId);
                return;
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonRespawn] Respawn attempt failed for " + key + ": " + t);
        }
        failOrRetry(p, scfg, "spawn-failed");
    }

    private void failOrRetry(PendingRespawn p, SoulConfig scfg, String reason) {
        int max = Math.max(0, scfg.getRespawnRetryAttempts());
        if (p.retryCount < max) {
            p.retryCount++;
            p.retryAtMs = System.currentTimeMillis() + Math.max(1, scfg.getRespawnRetryDelayTicks()) * 50L;
            GlovedPlugin.logDebug("[SummonRespawn] Retry " + p.retryCount + "/" + max + " for slot " + slotKey(p.ownerId, p.teamIndex, p.slotIndex) + " reason=" + reason);
            return;
        }
        cancelSlot(p.ownerId, p.teamIndex, p.slotIndex, reason);
        GlovedPlugin.logDebug("[SummonRespawn] Cancelled slot " + slotKey(p.ownerId, p.teamIndex, p.slotIndex) + " reason=" + reason);
    }

    private Location resolveSpawnLocation(Player owner, SoulInstance soul, Location fallbackDeath) {
        try {
            // Prefer spawn near owner for safety/consistency; fallback to death location if owner location unusable.
            Location base = owner.getLocation();
            if (base != null && base.getWorld() != null) {
                Location cand = base.clone().add((Math.random() - 0.5) * 2.0, 0, (Math.random() - 0.5) * 2.0);
                if (cand.getWorld() != null) return cand;
            }
        } catch (Throwable ignored) {}
        if (fallbackDeath != null && fallbackDeath.getWorld() != null) return fallbackDeath.clone();
        return owner.getLocation();
    }

    private SummonProfile resolveProfile(SoulInstance soul) {
        try {
            if (soul == null || plugin.getSummonConfig() == null) return null;
            SummonProfile p = plugin.getSummonConfig().getProfile(soul.getEntityType());
            if (p == null) p = plugin.getSummonConfig().getProfile(soul.getFamilyKey());
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean hasLiveEntityForSlot(UUID ownerId, int teamIndex, int slotIndex) {
        if (tracker == null || ownerId == null) return false;
        NamespacedKey teamKey = new NamespacedKey(plugin, "summon_team_index");
        NamespacedKey slotKey = new NamespacedKey(plugin, "summon_team_slot");
        for (LivingEntity le : tracker.getActiveMinions(ownerId)) {
            if (le == null || le.isDead()) continue;
            Integer t = le.getPersistentDataContainer().get(teamKey, PersistentDataType.INTEGER);
            Integer s = le.getPersistentDataContainer().get(slotKey, PersistentDataType.INTEGER);
            if (t != null && s != null && t == teamIndex && s == slotIndex) return true;
        }
        return false;
    }



    public double computeEffectiveRespawnSeconds(SoulInstance soul, SoulConfig scfg) {
        if (soul == null) return Math.max(10.0, scfg != null ? scfg.getRespawnGlobalFloorSeconds() : 10.0);
        SoulBaseStats base = plugin.getSoulService() != null ? plugin.getSoulService().baseStatsOf(soul) : soul.getBaseStats();
        if (base == null) base = SoulBaseStats.defaults(soul.getEntityType());
        double seconds = base.respawnSeconds();
        if (seconds <= 0.0) seconds = SoulBaseStats.defaultRespawnSeconds(soul.getRarity(),
                plugin.getSummonConfig() != null && plugin.getSummonConfig().getSoulTableConfig() != null
                        ? plugin.getSummonConfig().getSoulTableConfig().getStatConfig() : new dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig.SoulStatConfig());
        // Enhancement reduces respawn time (bounded)
        if (scfg != null && scfg.isRespawnEnhanceReduces()) {
            double per = Math.max(0.0, scfg.getRespawnEnhancePerLevelReduction());
            double maxRed = Math.max(0.0, Math.min(0.95, scfg.getRespawnEnhanceMaxReduction()));
            double red = Math.min(maxRed, per * Math.max(0, soul.getEnhanceLevel()));
            seconds *= (1.0 - red);
        } else if (soul.getEnhanceLevel() > 0) {
            // fallback to base stat enhanced() semantics if config unavailable
            try {
                var cfg = plugin.getSummonConfig() != null ? plugin.getSummonConfig().getSoulTableConfig() : null;
                var statCfg = cfg != null ? cfg.getStatConfig() : new dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig.SoulStatConfig();
                double perLevel = cfg != null ? cfg.getEnhancePerLevelBonus() : 0.025;
                seconds = base.enhanced(soul.getEnhanceLevel(), perLevel, soul.getRarity(), statCfg).respawnSeconds();
            } catch (Throwable ignored) {}
        }
        // Affix reduction
        SoulStatSnapshot snap = plugin.getSoulService() != null ? plugin.getSoulService().getSnapshot(soul) : SoulStatSnapshot.fromSoul(soul);
        double reduction = Math.max(0.0, Math.min(0.95, snap.respawnReduction()));
        seconds *= (1.0 - reduction);
        double floor = scfg != null ? Math.max(SoulBaseStats.MIN_RESPAWN_SECONDS, scfg.getRespawnGlobalFloorSeconds()) : SoulBaseStats.MIN_RESPAWN_SECONDS;
        double ceil = scfg != null ? Math.max(floor, scfg.getRespawnGlobalCeilSeconds()) : SoulBaseStats.MAX_RESPAWN_SECONDS;
        return Math.max(floor, Math.min(ceil, seconds));
    }

    private SoulConfig soulConfig() {
        try { return plugin.getSummonConfig() != null ? plugin.getSummonConfig().getSoulConfig() : null; } catch (Throwable t) { return null; }
    }

    private void removeOwnerIndex(UUID ownerId, String key) {
        if (ownerId == null) return;
        Map<String, PendingRespawn> map = pendingByOwner.get(ownerId);
        if (map != null) {
            map.remove(key);
            if (map.isEmpty()) pendingByOwner.remove(ownerId);
        }
    }

    private static String slotKey(UUID ownerId, int teamIndex, int slotIndex) {
        return ownerId + ":" + teamIndex + ":" + slotIndex;
    }

    private static final class PendingRespawn {
        final UUID ownerId;
        final int teamIndex;
        final int slotIndex;
        final UUID soulId;
        final String familyKey;
        final Location lastDeathLocation;
        volatile long dueAtMs;
        int retryCount;
        long retryAtMs;
        long lastAmbientCueMs;

        PendingRespawn(UUID ownerId, int teamIndex, int slotIndex, UUID soulId, String familyKey,
                       Location lastDeathLocation, long dueAtMs, int retryCount) {
            this.ownerId = ownerId;
            this.teamIndex = teamIndex;
            this.slotIndex = slotIndex;
            this.soulId = soulId;
            this.familyKey = familyKey;
            this.lastDeathLocation = lastDeathLocation;
            this.dueAtMs = dueAtMs;
            this.retryCount = retryCount;
            this.retryAtMs = 0L;
            this.lastAmbientCueMs = 0L;
        }
    }
}
