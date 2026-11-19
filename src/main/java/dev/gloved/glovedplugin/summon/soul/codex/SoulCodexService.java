package dev.gloved.glovedplugin.summon.soul.codex;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulRepository;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

/**
 * Mob soul codex progression service (MVP, extends existing basic codex).
 * Tracks tier progress and simple claimable rewards; modular for future objectives/reward types.
 */
public final class SoulCodexService {

    public enum Tier { DISCOVERED, COLLECTED, REFINED, MASTERED }

    public record TierState(boolean reached, boolean claimed, SoulCodexConfig.TierReward reward) {}

    private final GlovedPlugin plugin;
    private final SoulService soulService;
    private final SoulRepository repository;

    public SoulCodexService(GlovedPlugin plugin, SoulService soulService) {
        this.plugin = plugin;
        this.soulService = soulService;
        this.repository = soulService.getRepository();
    }

    public SoulCodexConfig config() {
        try {
            var sc = plugin.getSummonConfig();
            return sc != null ? sc.getSoulCodexConfig() : new SoulCodexConfig();
        } catch (Throwable t) {
            return new SoulCodexConfig();
        }
    }

    public boolean isEnabled() { return config().isEnabled(); }

    /** Called after capture/collect upsert; can emit first-discovery feedback. */
    public void onSoulAcquired(Player player, SoulInstance soul, boolean firstDiscoveryHint) {
        if (!isEnabled() || player == null || soul == null) return;
        // Best-effort: discovery toast on new entries is approximated by caller/context in MVP.
        // Future: compare previous state for precise first-time events.
        if (firstDiscoveryHint) {
            player.sendMessage("§b[Codex] §fNew soul discovered: §e" + prettyFamily(soul.getFamilyKey()));
            try { player.playSound(player.getLocation(), Sound.UI_TOAST_IN, 0.7f, 1.3f); } catch (Throwable ignored) {}
        }
    }

    /** Hook: a soul reached a refined state (e.g. enhanced to threshold). */
    public void onSoulRefined(Player owner, SoulInstance soul) {
        if (!isEnabled() || owner == null || soul == null) return;
        try {
            SoulRepository.CodexEntry before = repository.getCodexEntry(owner.getUniqueId(), soul.getFamilyKey());
            repository.incrementCodexRefined(owner.getUniqueId(), soul.getFamilyKey());
            SoulRepository.CodexEntry after = repository.getCodexEntry(owner.getUniqueId(), soul.getFamilyKey());
            if (after != null && after.refinedCount() == 1 && (before == null || before.refinedCount() <= 0)) {
                owner.sendMessage("§b[Codex] §dRefined tier reached: §f" + prettyFamily(soul.getFamilyKey()));
                try { owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.1f); } catch (Throwable ignored) {}
            }
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Codex] onSoulRefined failed: " + e);
        }
    }

    /** Hook: soul mastery reached a configured mastery threshold for codex progress. */
    public void onSoulMasteryLevelReached(Player owner, SoulInstance soul) {
        if (!isEnabled() || owner == null || soul == null) return;
        SoulCodexConfig cfg = config();
        if (soul.getMasteryLevel() < cfg.getMasteredMasteryLevel()) return;
        try {
            SoulRepository.CodexEntry before = repository.getCodexEntry(owner.getUniqueId(), soul.getFamilyKey());
            repository.incrementCodexMastered(owner.getUniqueId(), soul.getFamilyKey());
            SoulRepository.CodexEntry after = repository.getCodexEntry(owner.getUniqueId(), soul.getFamilyKey());
            if (after != null && after.masteredCount() == 1 && (before == null || before.masteredCount() <= 0)) {
                owner.sendMessage("§b[Codex] §6Mastered tier reached: §f" + prettyFamily(soul.getFamilyKey()));
                try { owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f); } catch (Throwable ignored) {}
                try {
                    var adv = plugin.getSoulAdvancementService();
                    if (adv != null) adv.onCodexMasteredReached(owner);
                } catch (Throwable ignored) {}
            }
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Codex] onSoulMasteryLevelReached failed: " + e);
        }
    }

    public TierState tierState(SoulRepository.CodexEntry entry, Tier tier) {
        SoulCodexConfig cfg = config();
        if (entry == null || tier == null) return new TierState(false, false, new SoulCodexConfig.TierReward(SoulCodexConfig.RewardType.NONE, 0, 1));
        return switch (tier) {
            case DISCOVERED -> new TierState(entry.capturedCount() > 0, entry.discoveredClaimed(), cfg.getDiscoveredReward());
            case COLLECTED -> new TierState(entry.capturedCount() >= cfg.getCollectedReward().minCaptured(), entry.collectedClaimed(), cfg.getCollectedReward());
            case REFINED -> new TierState(entry.refinedCount() > 0 || entry.masteredCount() > 0, entry.refinedClaimed(), cfg.getRefinedReward());
            case MASTERED -> new TierState(entry.masteredCount() > 0, entry.masteredClaimed(), cfg.getMasteredReward());
        };
    }

    /** Claim a codex tier reward for a mob soul. Returns true if claimed now. */
    public boolean claimTier(Player owner, String familyKey, Tier tier) {
        if (!isEnabled() || owner == null || familyKey == null || tier == null) return false;
        try {
            SoulRepository.CodexEntry entry = repository.getCodexEntry(owner.getUniqueId(), familyKey);
            if (entry == null) { owner.sendMessage("§c[Codex] No codex entry found."); return false; }
            TierState st = tierState(entry, tier);
            if (!st.reached()) { owner.sendMessage("§c[Codex] Tier not reached yet."); return false; }
            if (st.claimed()) { owner.sendMessage("§7[Codex] Tier already claimed."); return false; }
            boolean ok = repository.setCodexClaimed(owner.getUniqueId(), familyKey, tier.name(), true);
            if (!ok) { owner.sendMessage("§7[Codex] Tier already claimed."); return false; }
            var reward = st.reward();
            if (reward != null && reward.type() == SoulCodexConfig.RewardType.ESSENCE && reward.amount() > 0) {
                soulService.addEssence(owner.getUniqueId(), reward.amount());
            }
            owner.sendMessage("§b[Codex] §aClaimed §f" + tier.name().charAt(0) + tier.name().substring(1).toLowerCase(Locale.ROOT)
                    + " §7for " + prettyFamily(familyKey)
                    + (reward != null && reward.type() == SoulCodexConfig.RewardType.ESSENCE && reward.amount() > 0 ? " §b(+" + reward.amount() + " Essence)" : ""));
            try { owner.playSound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f); } catch (Throwable ignored) {}
            return true;
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Codex] claimTier failed: " + e);
            owner.sendMessage("§c[Codex] Failed to claim tier.");
            return false;
        }
    }

    public static String prettyFamily(String familyKey) {
        if (familyKey == null || familyKey.isBlank()) return "Unknown";
        String[] parts = familyKey.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? familyKey : sb.toString();
    }
}
