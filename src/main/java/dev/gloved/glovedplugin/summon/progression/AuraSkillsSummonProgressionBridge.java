package dev.gloved.glovedplugin.summon.progression;

import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.registry.NamespacedRegistry;
import dev.aurelium.auraskills.api.skill.CustomSkill;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.Bukkit;

import java.util.UUID;

public final class AuraSkillsSummonProgressionBridge extends BaseSummonProgressionBridge {
    private volatile Skill cachedSkill;
    private volatile boolean warnedMissingSkill;

    public AuraSkillsSummonProgressionBridge(GlovedPlugin plugin, SummonConfig config) {
        super(plugin, config);
    }

    @Override
    public void onEnable() {
        activate();
    }

    @Override
    public void onReload(SummonConfig config) {
        super.onReload(config);
        activate();
    }

    private synchronized void activate() {
        try {
            if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) {
                cachedSkill = null;
                return;
            }
            AuraSkillsApi api = AuraSkillsApi.get();
            if (api == null) {
                cachedSkill = null;
                return;
            }
            String skillId = config != null ? config.getProgressionSkillId() : "summoning";
            if (skillId == null || skillId.isBlank()) skillId = "summoning";
            NamespacedId id = NamespacedId.of("gloved", skillId);
            Skill existing = null;
            try {
                existing = api.getGlobalRegistry().getSkill(id);
            } catch (Throwable ignored) {}
            if (existing == null) {
                try {
                    NamespacedRegistry registry = api.useRegistry("gloved", plugin.getDataFolder());
                    CustomSkill skill = CustomSkill.builder(id)
                            .displayName("Summoning")
                            .description("Progress by commanding summoned minions in combat.")
                            .build();
                    registry.registerSkill(skill);
                    existing = skill;
                    GlovedPlugin.logDebug("[SummonXP] Registered AuraSkills custom skill " + id);
                } catch (Throwable t) {
                    // May already be registered or API state not ready; try lookup again.
                    try {
                        existing = api.getGlobalRegistry().getSkill(id);
                    } catch (Throwable ignored) {}
                    if (existing == null) {
                        GlovedPlugin.logDebug("[SummonXP] Could not register/resolve AuraSkills skill " + id + ": " + t);
                    }
                }
            }
            cachedSkill = existing;
            warnedMissingSkill = false;
        } catch (Throwable t) {
            cachedSkill = null;
            GlovedPlugin.logDebug("[SummonXP] AuraSkills activation failed: " + t);
        }
    }

    @Override
    protected boolean isReady() {
        return cachedSkill != null && Bukkit.getPluginManager().getPlugin("AuraSkills") != null;
    }

    @Override
    protected void grantXp(UUID ownerId, double amount) {
        try {
            if (ownerId == null || amount <= 0) return;
            AuraSkillsApi api = AuraSkillsApi.get();
            Skill skill = cachedSkill;
            if (api == null || skill == null) {
                if (!warnedMissingSkill) {
                    warnedMissingSkill = true;
                    GlovedPlugin.logDebug("[SummonXP] XP grant skipped: AuraSkills skill/api unavailable");
                }
                return;
            }
            if (Bukkit.getPlayer(ownerId) == null) return;
            SkillsUser user = api.getUser(ownerId);
            if (user == null) return;
            if (config != null && !config.isProgressionApplyMultipliers()) {
                user.addSkillXpRaw(skill, amount);
            } else {
                user.addSkillXp(skill, amount);
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] XP grant failed: " + t);
        }
    }
}
