package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

abstract class BaseSummonProgressionBridge implements SummonProgressionBridge {
    protected final GlovedPlugin plugin;
    protected volatile SummonConfig config;
    protected final SummonXpState state = new SummonXpState();

    protected BaseSummonProgressionBridge(GlovedPlugin plugin, SummonConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    protected abstract boolean isReady();
    protected abstract void grantXp(UUID ownerId, double amount);

    protected boolean active() {
        SummonConfig cfg = config;
        return cfg != null && cfg.isProgressionEnabled() && cfg.isProgressionAuraSkillsEnabled() && isReady();
    }

    @Override
    public void onEnable() {}

    @Override
    public void onReload(SummonConfig config) {
        this.config = config;
        this.state.clear();
    }

    @Override
    public void onDisable() {
        this.state.clear();
    }

    @Override
    public void onMinionHit(Player owner, LivingEntity minion, LivingEntity target, String profileName, double finalDamage) {
        try {
            SummonConfig cfg = config;
            if (owner == null || minion == null || target == null || cfg == null) return;
            if (!cfg.isProgressionEnabled() || !cfg.isProgressionAuraSkillsEnabled()) return;
            if (!cfg.isProgressionHitXpEnabled() && !cfg.isProgressionKillXpEnabled()) return;
            long nowTick = currentTick();
            // Always record kill credit when kill XP is enabled, even if hit XP is disabled/capped.
            if (cfg.isProgressionKillXpEnabled() && !target.isDead()) {
                state.recordKillCredit(target.getUniqueId(), owner.getUniqueId(), profileName, nowTick);
            }
            if (!cfg.isProgressionHitXpEnabled() || !active()) return;
            if (cfg.getProgressionHitXpAmount() <= 0) return;
            if (state.tryConsumeHitXp(owner.getUniqueId(), target.getUniqueId(), cfg, nowTick)) {
                GlovedPlugin.logDebug("[SummonXP] Hit XP " + cfg.getProgressionHitXpAmount() + " -> " + owner.getName()
                        + " profile=" + profileName + " target=" + target.getType());
                grantXp(owner.getUniqueId(), cfg.getProgressionHitXpAmount());
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Hit hook error: " + t);
        }
    }

    @Override
    public void recordKillCredit(Player owner, LivingEntity minion, LivingEntity victim, String profileName, long nowTick) {
        try {
            SummonConfig cfg = config;
            if (owner == null || victim == null || cfg == null || !cfg.isProgressionEnabled() || !cfg.isProgressionKillXpEnabled()) return;
            state.recordKillCredit(victim.getUniqueId(), owner.getUniqueId(), profileName, nowTick);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onEntityDeath(LivingEntity victim, long nowTick) {
        try {
            SummonConfig cfg = config;
            if (victim == null || cfg == null) return;
            if (!cfg.isProgressionEnabled() || !cfg.isProgressionKillXpEnabled() || !active()) {
                // Still consume stale credit to bound cache if progression off? cheap enough to ignore.
                return;
            }
            SummonXpState.KillCredit credit = state.consumeKillCredit(victim.getUniqueId(), cfg, nowTick);
            if (credit == null) return;
            if (cfg.getProgressionKillXpAmount() <= 0) return;
            if (!state.tryConsumeKillXp(credit.ownerUuid, cfg, nowTick)) {
                GlovedPlugin.logDebug("[SummonXP] Kill XP capped for owner=" + credit.ownerUuid);
                return;
            }
            Player owner = Bukkit.getPlayer(credit.ownerUuid);
            GlovedPlugin.logDebug("[SummonXP] Kill XP " + cfg.getProgressionKillXpAmount() + " -> "
                    + (owner != null ? owner.getName() : credit.ownerUuid)
                    + " victim=" + victim.getType() + " profile=" + credit.profileName);
            grantXp(credit.ownerUuid, cfg.getProgressionKillXpAmount());
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Kill hook error: " + t);
        }
    }

    protected static long currentTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable ignored) {
            return System.currentTimeMillis() / 50L;
        }
    }
}
