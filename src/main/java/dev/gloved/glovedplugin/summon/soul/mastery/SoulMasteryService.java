package dev.gloved.glovedplugin.summon.soul.mastery;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulRepository;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-soul mastery progression (MVP): XP gain from summon combat hooks, level-up, and claimable milestones.
 * Modular/extendable: additional XP sources and reward handlers can be added later.
 */
public final class SoulMasteryService {

    public record XpSource(String key, SoulMasteryConfig.SourceConfig config) {}

    private final GlovedPlugin plugin;
    private final SoulService soulService;
    private final SoulRepository repository;

    // Anti-abuse/cap state (best-effort, in-memory)
    private final ConcurrentHashMap<UUID, Long> lastSoulHitTick = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteBucket> soulHitXpMinute = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteBucket> soulKillXpMinute = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, MinuteBucket> ownerHitXpMinute = new ConcurrentHashMap<>();
    // Serialize mastery awards per soul to prevent duplicate level-up processing/messages from races.
    private final ConcurrentHashMap<UUID, Object> masteryAwardLocks = new ConcurrentHashMap<>();

    public SoulMasteryService(GlovedPlugin plugin, SoulService soulService) {
        this.plugin = plugin;
        this.soulService = soulService;
        this.repository = soulService.getRepository();
    }

    public SoulMasteryConfig config() {
        try {
            var sc = plugin.getSummonConfig();
            return sc != null ? sc.getSoulMasteryConfig() : new SoulMasteryConfig();
        } catch (Throwable t) {
            return new SoulMasteryConfig();
        }
    }

    public boolean isEnabled() {
        return config().isEnabled();
    }

    public void onSummonHit(Player owner, UUID soulId, double finalDamage, long nowTick) {
        if (!isEnabled() || owner == null || soulId == null) return;
        SoulMasteryConfig cfg = config();
        var src = cfg.getHitSource();
        if (!src.enabled() || src.xp() <= 0.0) return;
        if (finalDamage < src.minFinalDamage()) return;
        if (src.perSoulCooldownTicks() > 0) {
            Long last = lastSoulHitTick.get(soulId);
            if (last != null && (nowTick - last) < src.perSoulCooldownTicks()) return;
            lastSoulHitTick.put(soulId, nowTick);
        }
        double grant = src.xp();
        if (src.perMinuteCap() > 0.0) {
            grant = soulHitXpMinute.computeIfAbsent(soulId, k -> new MinuteBucket()).consume(nowTick, grant, src.perMinuteCap());
        }
        if (grant <= 0.0) return;
        awardXp(owner, soulId, grant, "hit");
    }

    public void onSummonKill(Player owner, UUID soulId, long nowTick) {
        if (!isEnabled() || owner == null || soulId == null) return;
        SoulMasteryConfig cfg = config();
        var src = cfg.getKillSource();
        if (!src.enabled() || src.xp() <= 0.0) return;
        double grant = src.xp();
        if (src.perMinuteCap() > 0.0) {
            grant = soulKillXpMinute.computeIfAbsent(soulId, k -> new MinuteBucket()).consume(nowTick, grant, src.perMinuteCap());
        }
        if (grant <= 0.0) return;
        awardXp(owner, soulId, grant, "kill");
    }

    public void onSoulCaptured(Player owner, SoulInstance soul) {
        if (!isEnabled() || owner == null || soul == null) return;
        SoulMasteryConfig cfg = config();
        var src = cfg.getCaptureSource();
        if (!src.enabled() || src.xp() <= 0.0) return;
        awardXp(owner, soul.getSoulId(), src.xp(), "capture");
    }

    public void awardXp(Player owner, UUID soulId, double amount, String reason) {
        if (!isEnabled() || owner == null || soulId == null || amount <= 0.0) return;
        Object lock = masteryAwardLocks.computeIfAbsent(soulId, k -> new Object());
        synchronized (lock) {
            try {
                SoulInstance soul = soulService.getSoulCached(soulId);
                if (soul == null) soul = repository.getSoul(soulId);
                if (soul == null || !owner.getUniqueId().equals(soul.getOwnerId())) return;

                SoulMasteryConfig cfg = config();
                int maxLevel = cfg.getMaxLevel();
                int startLevel = Math.max(0, Math.min(maxLevel, soul.getMasteryLevel()));
                int level = startLevel;
                long xp = Math.max(0L, soul.getMasteryXp()) + Math.max(0L, Math.round(amount));
                while (level < maxLevel) {
                    int need = Math.max(1, cfg.xpToNextLevel(level));
                    if (xp < need) break;
                    xp -= need;
                    level++;
                }
                if (level >= maxLevel) xp = 0;

                boolean leveled = level > startLevel;

                // No state change (e.g. capped XP grant) - avoid unnecessary writes/notifications.
                int newXp = (int) Math.min(Integer.MAX_VALUE, xp);
                if (!leveled && newXp == Math.max(0, soul.getMasteryXp()) && level == startLevel) {
                    return;
                }

                Set<Integer> claimed = new LinkedHashSet<>(soul.getMasteryClaimedMilestones());
                SoulInstance updated = soul.withMastery(newXp, level, claimed);
                repository.updateSoulMastery(soulId, updated.getMasteryXp(), updated.getMasteryLevel(), updated.getMasteryClaimedMilestones());
                soulService.invalidateSoul(soulId, owner.getUniqueId());

                if (leveled) {
                    owner.sendMessage("§d[Soul Mastery] §f" + prettySoul(soul) + " §7reached §bLv " + level + " §7(" + cfg.rankName(level) + ")");
                    try { owner.playSound(owner.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f); } catch (Throwable ignored) {}
                    // Notify codex of mastery progression/milestone thresholds
                    try {
                        var codex = soulService.getCodexService();
                        if (codex != null) codex.onSoulMasteryLevelReached(owner, updated);
                    } catch (Throwable ignored) {}
                }
                try {
                    var adv = plugin.getSoulAdvancementService();
                    if (adv != null && level >= 5) adv.onMasteryLevelReached(owner, level);
                } catch (Throwable ignored) {}
            } catch (SQLException e) {
                GlovedPlugin.logDebug("[SoulMastery] awardXp failed for soul=" + soulId + ": " + e);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SoulMastery] awardXp error: " + t);
            }
        }
    }

    /** Claim a reached milestone reward (MVP: essence). Returns true if claimed now. */
    public boolean claimMilestone(Player owner, UUID soulId, int milestoneLevel) {
        if (owner == null || soulId == null) return false;
        SoulMasteryConfig cfg = config();
        if (!cfg.isEnabled()) return false;
        var reward = cfg.getMilestone(milestoneLevel);
        if (reward == null) return false;
        try {
            SoulInstance soul = soulService.getSoulCached(soulId);
            if (soul == null) soul = repository.getSoul(soulId);
            if (soul == null || !owner.getUniqueId().equals(soul.getOwnerId())) return false;
            if (soul.getMasteryLevel() < milestoneLevel) {
                owner.sendMessage("§c[Soul Mastery] Milestone not reached yet.");
                return false;
            }
            if (soul.hasClaimedMasteryMilestone(milestoneLevel)) {
                owner.sendMessage("§7[Soul Mastery] Milestone already claimed.");
                return false;
            }
            // Grant reward (modular hook point for future reward handlers)
            if (reward.type() == SoulMasteryConfig.RewardType.ESSENCE && reward.amount() > 0) {
                soulService.addEssence(owner.getUniqueId(), reward.amount());
            }
            Set<Integer> claimed = new LinkedHashSet<>(soul.getMasteryClaimedMilestones());
            claimed.add(milestoneLevel);
            SoulInstance updated = soul.withMastery(soul.getMasteryXp(), soul.getMasteryLevel(), claimed);
            repository.updateSoulMastery(soulId, updated.getMasteryXp(), updated.getMasteryLevel(), updated.getMasteryClaimedMilestones());
            soulService.invalidateSoul(soulId, owner.getUniqueId());
            owner.sendMessage("§d[Soul Mastery] §aClaimed §f" + reward.label() + " §7for " + prettySoul(updated)
                    + (reward.type() == SoulMasteryConfig.RewardType.ESSENCE && reward.amount() > 0 ? " §b(+" + reward.amount() + " Essence)" : ""));
            try { owner.playSound(owner.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f); } catch (Throwable ignored) {}
            return true;
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulMastery] claimMilestone failed: " + e);
            return false;
        }
    }

    public List<SoulMasteryConfig.MilestoneReward> claimableMilestones(SoulInstance soul) {
        if (soul == null || !isEnabled()) return List.of();
        List<SoulMasteryConfig.MilestoneReward> out = new ArrayList<>();
        for (var e : config().getMilestones().entrySet()) {
            if (soul.getMasteryLevel() >= e.getKey() && !soul.hasClaimedMasteryMilestone(e.getKey())) out.add(e.getValue());
        }
        return out;
    }

    private static String prettySoul(SoulInstance soul) {
        if (soul == null) return "Soul";
        String name = soul.getFamilyKey() != null ? soul.getFamilyKey() : "Soul";
        name = name.replace('_', ' ');
        return soul.getRarity().coloredNameLegacy() + " §f" + toTitle(name) + " Soul";
    }

    private static String toTitle(String s) {
        if (s == null || s.isBlank()) return "Soul";
        String[] parts = s.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? s : sb.toString();
    }

    private static final class MinuteBucket {
        long windowStartTick;
        double used;
        synchronized double consume(long nowTick, double want, double cap) {
            if (cap <= 0.0) return Math.max(0.0, want);
            if (nowTick - windowStartTick >= 1200L) { // ~60s
                windowStartTick = nowTick;
                used = 0.0;
            }
            double room = Math.max(0.0, cap - used);
            double give = Math.min(Math.max(0.0, want), room);
            used += give;
            return give;
        }
    }
}
