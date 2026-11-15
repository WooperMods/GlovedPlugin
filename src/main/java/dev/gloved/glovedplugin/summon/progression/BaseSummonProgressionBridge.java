package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
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
    public void onPlayerQuit(UUID ownerId) {
        state.onPlayerQuit(ownerId);
    }

    @Override
    public int getAbilityLevel(UUID ownerId, String abilityId) {
        return 0;
    }

    @Override
    public OwnerCombatStats resolveOwnerCombatStats(UUID ownerId) {
        return OwnerCombatStats.unavailable();
    }

    @Override
    public void onMinionHit(Player owner, LivingEntity minion, LivingEntity target, String profileName, double finalDamage) {
        try {
            SummonConfig cfg = config;
            if (owner == null || minion == null || target == null || cfg == null) return;
            UUID soulId = resolveSoulId(minion);
            // Soul mastery hit hook (independent of AuraSkills progression enablement; gated by mastery config inside service)
            try {
                if (soulId != null && plugin.getSoulService() != null && plugin.getSoulService().getMasteryService() != null) {
                    plugin.getSoulService().getMasteryService().onSummonHit(owner, soulId, finalDamage, currentTick());
                }
            } catch (Throwable ignored) {}
            if (!cfg.isProgressionEnabled() || !cfg.isProgressionAuraSkillsEnabled()) return;
            if (!cfg.isProgressionHitXpEnabled() && !cfg.isProgressionKillXpEnabled()) return;
            long nowTick = currentTick();
            // Always record kill credit when kill XP is enabled, even if hit XP is disabled/capped.
            if (cfg.isProgressionKillXpEnabled() && !target.isDead()) {
                state.recordKillCredit(target.getUniqueId(), owner.getUniqueId(), soulId, profileName, nowTick);
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
            UUID soulId = resolveSoulId(minion);
            state.recordKillCredit(victim.getUniqueId(), owner.getUniqueId(), soulId, profileName, nowTick);
        } catch (Throwable ignored) {}
    }

    @Override
    public int getSummoningLevel(UUID ownerId) {
        return 0;
    }

    @Override
    public void onEntityDeath(LivingEntity victim, long nowTick) {
        try {
            SummonConfig cfg = config;
            if (victim == null || cfg == null) return;
            SummonXpState.KillCredit credit = state.consumeKillCredit(victim.getUniqueId(), cfg, nowTick);
            if (credit == null) return;

            // Soul mastery kill hook (independent of AuraSkills XP enablement/readiness)
            try {
                if (credit.soulId != null && plugin.getSoulService() != null && plugin.getSoulService().getMasteryService() != null) {
                    Player owner = Bukkit.getPlayer(credit.ownerUuid);
                    if (owner != null) plugin.getSoulService().getMasteryService().onSummonKill(owner, credit.soulId, nowTick);
                }
            } catch (Throwable ignored) {}

            if (!cfg.isProgressionEnabled() || !cfg.isProgressionKillXpEnabled() || !active()) {
                return;
            }
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

    private UUID resolveSoulId(LivingEntity minion) {
        try {
            if (minion == null || plugin == null) return null;
            org.bukkit.NamespacedKey k = new org.bukkit.NamespacedKey(plugin, "summon_soul_id");
            String id = minion.getPersistentDataContainer().get(k, org.bukkit.persistence.PersistentDataType.STRING);
            if (id == null || id.isBlank()) return null;
            return java.util.UUID.fromString(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void onSoulCaptured(Player owner, LivingEntity victim, String familyKey) {
        onSoulCaptured(owner, victim, familyKey, SoulRarity.COMMON);
    }

    @Override
    public void onSoulCaptured(Player owner, LivingEntity victim, String familyKey, SoulRarity rarity) {
        try {
            SummonConfig cfg = config;
            if (owner == null || cfg == null || !cfg.isProgressionEnabled() || !cfg.isProgressionCaptureXpEnabled() || !active()) return;
            if (cfg.getProgressionCaptureXpAmount() <= 0) return;
            SoulRarity r = rarity == null ? SoulRarity.COMMON : rarity;
            double amount = cfg.getProgressionCaptureXpAmount() * r.getCaptureXpMultiplier();
            if (amount <= 0) return;
            long nowTick = currentTick();
            if (!state.tryConsumeCaptureXp(owner.getUniqueId(), amount, cfg, nowTick)) return;
            GlovedPlugin.logDebug("[SummonXP] Capture XP " + amount + " (" + r.name() + ") -> " + owner.getName() + " family=" + familyKey);
            grantXp(owner.getUniqueId(), amount);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Capture hook error: " + t);
        }
    }

    @Override
    public void onSoulCast(Player owner, String familyKey) {
        try {
            SummonConfig cfg = config;
            if (owner == null || cfg == null || !cfg.isProgressionEnabled() || !cfg.isProgressionCastXpEnabled() || !active()) return;
            if (cfg.getProgressionCastXpAmount() <= 0) return;
            long nowTick = currentTick();
            if (!state.tryConsumeCastXp(owner.getUniqueId(), cfg, nowTick)) return;
            GlovedPlugin.logDebug("[SummonXP] Cast XP " + cfg.getProgressionCastXpAmount() + " -> " + owner.getName() + " family=" + familyKey);
            grantXp(owner.getUniqueId(), cfg.getProgressionCastXpAmount());
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Cast hook error: " + t);
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
