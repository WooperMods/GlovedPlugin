package dev.gloved.glovedplugin.summon.progression;

import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.item.ItemContext;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.registry.NamespacedRegistry;
import dev.aurelium.auraskills.api.skill.CustomSkill;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import org.bukkit.Bukkit;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuraSkillsSummonProgressionBridge extends BaseSummonProgressionBridge {
    private static final int CONTENT_VERSION = 2;
    private static final NamespacedId DEBUFF_ID = NamespacedId.of("gloved", "summoning_debuff");
    private static final NamespacedId CLEAVE_ID = NamespacedId.of("gloved", "summoning_cleave");
    private static final NamespacedId LIFESTEAL_ID = NamespacedId.of("gloved", "summoning_lifesteal");

    private volatile Skill cachedSkill;
    private volatile boolean warnedMissingSkill;
    private final Map<UUID, LevelCache> levelCache = new ConcurrentHashMap<>();
    private final SummonOwnerCombatStatsResolver statResolver;
    private volatile org.bukkit.scheduler.BukkitTask activationRetryTask;
    private volatile int activationAttempts;
    private volatile boolean activationReadyLogged;
    private volatile boolean autoReloadRequested;

    private record LevelCache(int level, long expiresAtMs) {}

    public AuraSkillsSummonProgressionBridge(GlovedPlugin plugin, SummonConfig config) {
        super(plugin, config);
        this.statResolver = new SummonOwnerCombatStatsResolver(() -> this.config);
    }

    @Override
    public void onEnable() {
        scheduleActivateRetry(true);
    }

    @Override
    public void onReload(SummonConfig config) {
        super.onReload(config);
        levelCache.clear();
        statResolver.clear();
        activationAttempts = 0;
        activationReadyLogged = false;
        autoReloadRequested = false;
        scheduleActivateRetry(true);
    }

    @Override
    public void onDisable() {
        cancelActivationRetry();
        levelCache.clear();
        statResolver.clear();
        autoReloadRequested = false;
        super.onDisable();
    }

    private void requestAuraSkillsReloadIfNeeded() {
        try {
            if (autoReloadRequested) return;
            if (plugin == null || !plugin.isEnabled()) return;
            if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) return;
            if (config == null || !config.isProgressionEnabled() || !config.isProgressionAuraSkillsEnabled()) return;
            autoReloadRequested = true;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (!plugin.isEnabled()) return;
                    if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) return;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "skills reload");
                    GlovedPlugin.logInfo("[SummonXP] Auto-reloaded AuraSkills to load summoning progression content.");
                    // Re-resolve skill/ability references after AuraSkills finishes reloading
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            activationAttempts = 0;
                            cachedSkill = null;
                            scheduleActivateRetry(false);
                        } catch (Throwable ignored) {}
                    }, 60L);
                } catch (Throwable t) {
                    GlovedPlugin.logDebug("[SummonXP] Auto-reload after activation failed: " + t);
                }
            }, 20L); // 1s delay to let registration/content settle
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Could not schedule AuraSkills auto-reload: " + t);
        }
    }

    @Override
    public void onPlayerQuit(UUID ownerId) {
        super.onPlayerQuit(ownerId);
        if (ownerId != null) {
            levelCache.remove(ownerId);
            statResolver.onPlayerQuit(ownerId);
        }
    }

    private void cancelActivationRetry() {
        try {
            org.bukkit.scheduler.BukkitTask t = activationRetryTask;
            activationRetryTask = null;
            if (t != null) t.cancel();
        } catch (Throwable ignored) {}
    }

    private void scheduleActivateRetry(boolean immediate) {
        cancelActivationRetry();
        if (plugin == null) return;
        if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) {
            cachedSkill = null;
            return;
        }
        if (immediate) {
            try {
                if (activate()) return;
            } catch (Throwable ignored) {}
        }
        activationRetryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) {
                    cancelActivationRetry();
                    return;
                }
                if (activate()) {
                    cancelActivationRetry();
                } else {
                    activationAttempts++;
                    // stop spamming retries forever; keep a long tail but bounded
                    if (activationAttempts >= 60) { // ~5 minutes at 5s interval
                        GlovedPlugin.logDebug("[SummonXP] AuraSkills activation still unavailable after retries; will remain inactive until reload/restart when API is ready.");
                        cancelActivationRetry();
                    }
                }
            } catch (Throwable t) {
                activationAttempts++;
                GlovedPlugin.logDebug("[SummonXP] AuraSkills activation retry error: " + t);
                if (activationAttempts >= 60) cancelActivationRetry();
            }
        }, immediate ? 40L : 100L, 100L); // first retry in 2s if immediate failed, then every 5s
    }

    private synchronized boolean activate() {
        try {
            if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) {
                cachedSkill = null;
                return true; // no integration needed
            }
            AuraSkillsApi api;
            try {
                api = AuraSkillsApi.get();
            } catch (Throwable t) {
                // API not initialized yet (common when AuraSkills loads after us); retry later.
                cachedSkill = null;
                if (activationAttempts == 0) {
                    GlovedPlugin.logDebug("[SummonXP] AuraSkills plugin present but API not initialized yet; will retry activation.");
                }
                return false;
            }
            if (api == null) {
                cachedSkill = null;
                if (activationAttempts == 0) {
                    GlovedPlugin.logDebug("[SummonXP] AuraSkills API unavailable; will retry activation.");
                }
                return false;
            }
            String skillId = config != null ? config.getProgressionSkillId() : "summoning";
            if (skillId == null || skillId.isBlank()) skillId = "summoning";
            NamespacedId id = NamespacedId.of("gloved", skillId);
            Skill existing = null;
            try {
                existing = api.getGlobalRegistry().getSkill(id);
            } catch (Throwable ignored) {}
            NamespacedRegistry registry = api.useRegistry("gloved", plugin.getDataFolder());
            SummonConfig cfg = config;

            // Archery/Fighting-like pacing: unlock 2/4/6, level_up 5, multi-level (max 0 = skill-capped).
            tryGenerateOrMigrateAuraSkillsContentFiles(cfg);
            tryGenerateRewardsFile(cfg);

            // Ability values/info aligned to GlovedPlugin's real formulas (primary/secondary) so tooltips show scaled stats.
            double debuffChanceBase = cfg != null ? cfg.getAbilityDebuffBaseChance() * 100.0 : 15.0;
            double debuffChancePer = cfg != null ? cfg.getAbilityDebuffChancePerLevel() * 100.0 : 1.5;
            // Show duration in seconds (more readable). GlovedPlugin uses ticks (20 ticks/s).
            double debuffDurBaseTicks = cfg != null ? cfg.getAbilityDebuffDurationTicks(1) : 60;
            double debuffDurPerTicks = cfg != null ? Math.max(0.0, cfg.getAbilityDebuffDurationTicks(2) - cfg.getAbilityDebuffDurationTicks(1)) : 4;
            double debuffDurBaseSec = debuffDurBaseTicks / 20.0;
            double debuffDurPerSec = debuffDurPerTicks / 20.0;
            Object debuff = registerAbility(api, registry, "summoning_debuff", "Debuff",
                    "Minion hits can debuff targets. Chance and duration improve with ability level.",
                    "{value}% Chance, {value_2}s Duration", 2, 5, debuffChanceBase, debuffChancePer, debuffDurBaseSec, debuffDurPerSec);

            double cleaveChanceBase = cfg != null ? cfg.getAbilityCleaveChance(1) * 100.0 : 12.0;
            double cleaveChancePer = cfg != null ? Math.max(0.0, (cfg.getAbilityCleaveChance(2) - cfg.getAbilityCleaveChance(1)) * 100.0) : 1.2;
            double cleaveRatioBase = cfg != null ? cfg.getAbilityCleaveDamageRatio(1) * 100.0 : 25.0;
            double cleaveRatioPer = cfg != null ? Math.max(0.0, (cfg.getAbilityCleaveDamageRatio(2) - cfg.getAbilityCleaveDamageRatio(1)) * 100.0) : 1.5;
            Object cleave = registerAbility(api, registry, "summoning_cleave", "Cleave",
                    "Minion hits can deal AoE damage to nearby enemies. Chance and damage improve with ability level.",
                    "{value}% Chance, {value_2}% Damage", 4, 5, cleaveChanceBase, cleaveChancePer, cleaveRatioBase, cleaveRatioPer);

            double lsBase = cfg != null ? cfg.getAbilityLifestealHealPercentMaxHp(1) * 100.0 : 0.75;
            double lsPer = cfg != null ? Math.max(0.0, (cfg.getAbilityLifestealHealPercentMaxHp(2) - cfg.getAbilityLifestealHealPercentMaxHp(1)) * 100.0) : 0.10;
            Object lifesteal = registerAbility(api, registry, "summoning_lifesteal", "Life-steal",
                    "Minion damage heals a portion of your max HP. Healing improves with ability level.",
                    "{value}% Max HP", 6, 5, lsBase, lsPer, null, null);

            ItemContext menuItem = ItemContext.builder()
                    .material("allay_spawn_egg")
                    .group("third_row")
                    .order(7)
                    .build();
            if (existing == null) {
                try {
                    var builder = CustomSkill.builder(id)
                            .displayName("Summoning")
                            .description("Progress by commanding summoned minions in combat. Grants crit stats and unlocks minion abilities.")
                            .item(menuItem);
                    tryAttachAbility(builder, debuff);
                    tryAttachAbility(builder, cleave);
                    tryAttachAbility(builder, lifesteal);
                    CustomSkill skill = builder.build();
                    registry.registerSkill(skill);
                    existing = skill;
                    GlovedPlugin.logDebug("[SummonXP] Registered AuraSkills custom skill " + id + " with multi-level abilities (menu: allay_spawn_egg third_row/7)");
                } catch (Throwable t) {
                    try { existing = api.getGlobalRegistry().getSkill(id); } catch (Throwable ignored) {}
                    if (existing == null) {
                        GlovedPlugin.logDebug("[SummonXP] Could not register/resolve AuraSkills skill " + id + ": " + t);
                    }
                }
            } else {
                boolean menuFixed = tryUpdateSkillMenuItem(existing, menuItem);
                if (!menuFixed) menuFixed = tryReregisterSkillWithMenu(api, registry, id, menuItem, debuff, cleave, lifesteal);
                GlovedPlugin.logDebug("[SummonXP] AuraSkills skill " + id + " already registered; abilities/rewards ensured"
                        + (menuFixed ? ", menu item updated (allay_spawn_egg third_row/7)" : "") + ". Run /skills reload or restart if UI/menu is stale.");
            }
            cachedSkill = existing;
            warnedMissingSkill = false;
            if (!activationReadyLogged) {
                activationReadyLogged = true;
                GlovedPlugin.logDebug("[SummonXP] AuraSkills progression integration activated" + (activationAttempts > 0 ? " after retry" : "") + ".");
            }
            requestAuraSkillsReloadIfNeeded();
            return cachedSkill != null || Bukkit.getPluginManager().getPlugin("AuraSkills") == null;
        } catch (Throwable t) {
            cachedSkill = null;
            String msg = String.valueOf(t);
            if (msg.contains("AuraSkillsApi is not initialized") || msg.contains("not initialized")) {
                if (activationAttempts == 0) {
                    GlovedPlugin.logDebug("[SummonXP] AuraSkills API not ready yet; will retry activation.");
                }
                return false;
            }
            GlovedPlugin.logDebug("[SummonXP] AuraSkills activation failed: " + t);
            return false;
        }
    }

    @Override
    protected boolean isReady() {
        return Bukkit.getPluginManager().getPlugin("AuraSkills") != null;
    }

    private Skill resolveSkillForUse(AuraSkillsApi api) {
        Skill skill = cachedSkill;
        if (api == null) return skill;
        try {
            String skillId = config != null ? config.getProgressionSkillId() : "summoning";
            if (skillId == null || skillId.isBlank()) skillId = "summoning";
            NamespacedId id = NamespacedId.of("gloved", skillId.trim().toLowerCase());
            Skill fresh = api.getGlobalRegistry().getSkill(id);
            if (fresh != null) {
                cachedSkill = fresh;
                return fresh;
            }
        } catch (Throwable ignored) {}
        return skill;
    }

    @Override
    protected void grantXp(UUID ownerId, double amount) {
        try {
            if (ownerId == null || amount <= 0) return;
            if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) return;
            AuraSkillsApi api;
            try { api = AuraSkillsApi.get(); } catch (Throwable t) { return; }
            if (api == null) return;
            if (Bukkit.getPlayer(ownerId) == null) return;
            SkillsUser user = api.getUser(ownerId);
            if (user == null) return;
            Skill skill = resolveSkillForUse(api);
            if (skill == null) {
                // try to (re)activate in background; skip this grant
                try { scheduleActivateRetry(false); } catch (Throwable ignored) {}
                return;
            }
            if (config != null && !config.isProgressionApplyMultipliers()) {
                user.addSkillXpRaw(skill, amount);
            } else {
                user.addSkillXp(skill, amount);
            }
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            if (msg.contains("is not loaded") || msg.contains("AuraSkillsApi is not initialized")) {
                // Skill/API not fully ready yet; retry activation and skip this grant quietly
                try { scheduleActivateRetry(false); } catch (Throwable ignored) {}
                GlovedPlugin.logDebug("[SummonXP] XP grant deferred: " + msg);
                return;
            }
            GlovedPlugin.logDebug("[SummonXP] XP grant failed: " + t);
        }
    }

    @Override
    public int getAbilityLevel(UUID ownerId, String abilityId) {
        try {
            if (ownerId == null || abilityId == null || Bukkit.getPlayer(ownerId) == null) return 0;
            AuraSkillsApi api = AuraSkillsApi.get();
            if (api == null) return 0;
            SkillsUser user = api.getUser(ownerId);
            if (user == null) return 0;
            NamespacedId nid = parseAbilityId(abilityId);
            if (nid == null) return 0;
            Ability ability = api.getGlobalRegistry().getAbility(nid);
            if (ability == null) return 0;
            return Math.max(0, user.getAbilityLevel(ability));
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Ability level lookup failed for " + abilityId + ": " + t);
            return 0;
        }
    }

    @Override
    public OwnerCombatStats resolveOwnerCombatStats(UUID ownerId) {
        return statResolver.resolve(ownerId);
    }

    private static NamespacedId parseAbilityId(String abilityId) {
        try {
            if (abilityId == null) return null;
            String s = abilityId.trim().toLowerCase();
            if (s.equals("gloved/summoning_debuff") || s.endsWith("summoning_debuff")) return DEBUFF_ID;
            if (s.equals("gloved/summoning_cleave") || s.endsWith("summoning_cleave")) return CLEAVE_ID;
            if (s.equals("gloved/summoning_lifesteal") || s.endsWith("summoning_lifesteal")) return LIFESTEAL_ID;
            if (s.contains("/")) return NamespacedId.fromDefault(s);
            return NamespacedId.of("gloved", s);
        } catch (Throwable t) {
            return null;
        }
    }

    private Object registerAbility(AuraSkillsApi api, NamespacedRegistry registry, String id, String display, String description, String info,
                                   int unlockLevel, int levelUp, double baseValue, double valuePerLevel,
                                   Double secondaryBaseValue, Double secondaryValuePerLevel) {
        try {
            NamespacedId nid = NamespacedId.of("gloved", id);
            try {
                Object existing = api.getGlobalRegistry().getAbility(nid);
                if (existing != null) {
                    tryUpdateAbilityText(existing, display, description, info);
                    tryUpdateAbilityValues(existing, baseValue, valuePerLevel, secondaryBaseValue, secondaryValuePerLevel);
                    return existing;
                }
            } catch (Throwable ignored) {}
            Class<?> customAbilityClass = Class.forName("dev.aurelium.auraskills.api.ability.CustomAbility");
            Object builder = customAbilityClass.getMethod("builder", NamespacedId.class).invoke(null, nid);
            Class<?> bClass = builder.getClass();
            try { builder = bClass.getMethod("displayName", String.class).invoke(builder, display); } catch (Throwable ignored) {}
            try { builder = bClass.getMethod("description", String.class).invoke(builder, description); } catch (Throwable ignored) {}
            try { builder = bClass.getMethod("info", String.class).invoke(builder, info); } catch (Throwable ignored) {}
            try { builder = bClass.getMethod("unlock", int.class).invoke(builder, Math.max(0, unlockLevel)); } catch (Throwable ignored) {}
            try { builder = bClass.getMethod("levelUp", int.class).invoke(builder, Math.max(1, levelUp)); } catch (Throwable ignored) {}
            try { builder = bClass.getMethod("baseValue", double.class).invoke(builder, baseValue); } catch (Throwable ignored) {}
            try { builder = bClass.getMethod("valuePerLevel", double.class).invoke(builder, valuePerLevel); } catch (Throwable ignored) {}
            if (secondaryBaseValue != null) {
                try { builder = bClass.getMethod("secondaryBaseValue", double.class).invoke(builder, secondaryBaseValue); } catch (Throwable ignored) {}
                try { builder = bClass.getMethod("secondary_base_value", double.class).invoke(builder, secondaryBaseValue); } catch (Throwable ignored) {}
            }
            if (secondaryValuePerLevel != null) {
                try { builder = bClass.getMethod("secondaryValuePerLevel", double.class).invoke(builder, secondaryValuePerLevel); } catch (Throwable ignored) {}
                try { builder = bClass.getMethod("secondary_value_per_level", double.class).invoke(builder, secondaryValuePerLevel); } catch (Throwable ignored) {}
            }
            try { builder = bClass.getMethod("maxLevel", int.class).invoke(builder, 0); } catch (Throwable ignored) {}
            Object ability = bClass.getMethod("build").invoke(builder);
            try {
                Class<?> abilityClass = Class.forName("dev.aurelium.auraskills.api.ability.Ability");
                registry.getClass().getMethod("registerAbility", abilityClass).invoke(registry, ability);
            } catch (Throwable t) {
                try { registry.getClass().getMethod("registerAbility", ability.getClass()).invoke(registry, ability); } catch (Throwable ignored) {}
            }
            GlovedPlugin.logDebug("[SummonXP] Registered AuraSkills ability gloved/" + id + " (unlock " + unlockLevel + ", levelUp " + levelUp + ", info set)");
            return ability;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Could not register AuraSkills ability gloved/" + id + ": " + t);
            try {
                Object existing = AuraSkillsApi.get().getGlobalRegistry().getAbility(NamespacedId.of("gloved", id));
                tryUpdateAbilityText(existing, display, description, info);
                return existing;
            } catch (Throwable ignored) { return null; }
        }
    }

    private void tryUpdateAbilityValues(Object ability, double baseValue, double valuePerLevel, Double secondaryBaseValue, Double secondaryValuePerLevel) {
        if (ability == null) return;
        tryInvokeNum(ability, "setBaseValue", baseValue);
        tryInvokeNum(ability, "setValuePerLevel", valuePerLevel);
        Object defined = tryGet(ability, "getDefined");
        if (defined == null) defined = tryGet(ability, "getDefinedImpl");
        if (defined != null) {
            tryInvokeNum(defined, "setBaseValue", baseValue);
            tryInvokeNum(defined, "setValuePerLevel", valuePerLevel);
            trySetFieldNum(defined, "baseValue", baseValue);
            trySetFieldNum(defined, "valuePerLevel", valuePerLevel);
            trySetFieldNum(defined, "base_value", baseValue);
            trySetFieldNum(defined, "value_per_level", valuePerLevel);
        }
        trySetFieldNum(ability, "baseValue", baseValue);
        trySetFieldNum(ability, "valuePerLevel", valuePerLevel);
        tryUpdateAbilitySecondary(ability, secondaryBaseValue, secondaryValuePerLevel);
    }

    private void tryUpdateAbilitySecondary(Object ability, Double secondaryBaseValue, Double secondaryValuePerLevel) {
        if (ability == null) return;
        if (secondaryBaseValue != null) {
            tryInvokeNum(ability, "setSecondaryBaseValue", secondaryBaseValue);
            Object defined = tryGet(ability, "getDefined");
            if (defined == null) defined = tryGet(ability, "getDefinedImpl");
            if (defined != null) { tryInvokeNum(defined, "setSecondaryBaseValue", secondaryBaseValue); trySetFieldNum(defined, "secondaryBaseValue", secondaryBaseValue); }
            trySetFieldNum(ability, "secondaryBaseValue", secondaryBaseValue);
        }
        if (secondaryValuePerLevel != null) {
            tryInvokeNum(ability, "setSecondaryValuePerLevel", secondaryValuePerLevel);
            Object defined = tryGet(ability, "getDefined");
            if (defined == null) defined = tryGet(ability, "getDefinedImpl");
            if (defined != null) { tryInvokeNum(defined, "setSecondaryValuePerLevel", secondaryValuePerLevel); trySetFieldNum(defined, "secondaryValuePerLevel", secondaryValuePerLevel); }
            trySetFieldNum(ability, "secondaryValuePerLevel", secondaryValuePerLevel);
        }
    }

    private void tryInvokeNum(Object target, String method, double arg) {
        if (target == null || method == null) return;
        try { target.getClass().getMethod(method, double.class).invoke(target, arg); } catch (Throwable ignored) {}
    }

    private void trySetFieldNum(Object target, String field, double value) {
        if (target == null || field == null) return;
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            if (f.getType() == double.class || f.getType() == Double.class) f.set(target, value);
            else if (f.getType() == float.class || f.getType() == Float.class) f.set(target, (float) value);
        } catch (Throwable ignored) {}
    }

    /** Best-effort: update text on an already-registered ability (fixes raw-key tooltips when prior jar lacked .info()). */
    private void tryUpdateAbilityText(Object ability, String display, String description, String info) {
        if (ability == null) return;
        // Try setter methods on the ability and its defined/options objects
        tryInvoke(ability, "setDisplayName", display);
        tryInvoke(ability, "setDescription", description);
        tryInvoke(ability, "setInfo", info);
        tryInvoke(ability, "displayName", display);
        tryInvoke(ability, "description", description);
        tryInvoke(ability, "info", info);
        Object defined = tryGet(ability, "getDefined");
        if (defined == null) defined = tryGet(ability, "getDefinedImpl");
        if (defined != null) {
            tryInvoke(defined, "setDisplayName", display);
            tryInvoke(defined, "setDescription", description);
            tryInvoke(defined, "setInfo", info);
            trySetField(defined, "displayName", display);
            trySetField(defined, "description", description);
            trySetField(defined, "info", info);
            trySetField(defined, "infoMessage", info);
        }
        trySetField(ability, "displayName", display);
        trySetField(ability, "description", description);
        trySetField(ability, "info", info);
    }

    private void tryInvoke(Object target, String method, String arg) {
        if (target == null || method == null || arg == null) return;
        try { target.getClass().getMethod(method, String.class).invoke(target, arg); } catch (Throwable ignored) {}
    }

    private Object tryGet(Object target, String method) {
        if (target == null || method == null) return null;
        try { return target.getClass().getMethod(method).invoke(target); } catch (Throwable ignored) { return null; }
    }

    private void trySetField(Object target, String field, String value) {
        if (target == null || field == null || value == null) return;
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            if (String.class.equals(f.getType()) || CharSequence.class.isAssignableFrom(f.getType())) f.set(target, value);
        } catch (Throwable ignored) {}
    }

    private void tryAttachAbility(Object builder, Object ability) {
        if (builder == null || ability == null) return;
        try {
            Class<?> abilityClass = Class.forName("dev.aurelium.auraskills.api.ability.Ability");
            builder.getClass().getMethod("ability", abilityClass).invoke(builder, ability);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Could not attach ability to summoning skill: " + t);
        }
    }

    /** Best-effort: update an already-registered skill's menu ItemContext (fixes stone-block button). */
    private boolean tryUpdateSkillMenuItem(Skill skill, ItemContext menuItem) {
        if (skill == null || menuItem == null) return false;
        try {
            // Common shape: skill.getDefined().getItem()/setItem(...) or Defined/ItemContext fields.
            Object defined = null;
            try { defined = skill.getClass().getMethod("getDefined").invoke(skill); } catch (Throwable ignored) {}
            if (defined != null) {
                try { defined.getClass().getMethod("setItem", ItemContext.class).invoke(defined, menuItem); return true; } catch (Throwable ignored) {}
                try {
                    java.lang.reflect.Field f = defined.getClass().getDeclaredField("item");
                    f.setAccessible(true); f.set(defined, menuItem); return true;
                } catch (Throwable ignored) {}
            }
            // Direct field on skill
            for (String fn : new String[]{"item", "menuItem", "defined"}) {
                try {
                    java.lang.reflect.Field f = skill.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    Object cur = f.get(skill);
                    if (cur != null && cur.getClass().getName().contains("Defined")) {
                        try { cur.getClass().getMethod("setItem", ItemContext.class).invoke(cur, menuItem); return true; } catch (Throwable ignored) {}
                        try { java.lang.reflect.Field fi = cur.getClass().getDeclaredField("item"); fi.setAccessible(true); fi.set(cur, menuItem); return true; } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Could not update existing skill menu item: " + t);
        }
        return false;
    }

    private boolean tryReregisterSkillWithMenu(AuraSkillsApi api, NamespacedRegistry registry, NamespacedId id, ItemContext menuItem,
                                               Object debuff, Object cleave, Object lifesteal) {
        try {
            var builder = CustomSkill.builder(id)
                    .displayName("Summoning")
                    .description("Progress by commanding summoned minions in combat. Grants crit stats and unlocks minion abilities.")
                    .item(menuItem);
            tryAttachAbility(builder, debuff);
            tryAttachAbility(builder, cleave);
            tryAttachAbility(builder, lifesteal);
            CustomSkill skill = builder.build();
            registry.registerSkill(skill);
            return true;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Skill re-register with menu item failed (may need /skills reload/restart): " + t);
            return false;
        }
    }

    private void tryGenerateRewardsFile(SummonConfig cfg) {
        try {
            if (cfg == null || !cfg.isProgressionGenerateAuraSkillsContentFiles()) return;
            File rewardsDir = new File(plugin.getDataFolder(), "rewards");
            if (!rewardsDir.exists() && !rewardsDir.mkdirs()) return;
            File rewards = new File(rewardsDir, "summoning.yml");
            String content = rewardsV2Content();
            if (!rewards.exists()) {
                Files.writeString(rewards.toPath(), content, StandardCharsets.UTF_8);
                GlovedPlugin.logInfo("[SummonXP] Generated AuraSkills rewards file: rewards/summoning.yml (+1 crit_chance/level incl. L1, +1 crit_damage/2 levels, +1 strength/2 levels). Run /skills reload or restart if progression UI does not update.");
                return;
            }
            // Safe migration: only if still our legacy auto-generated rewards (start: 2 for crit_chance).
            String existing = Files.readString(rewards.toPath(), StandardCharsets.UTF_8);
            if (existing != null && existing.contains("Auto-generated by GlovedPlugin (AuraSkills summoning rewards)")
                    && existing.contains("start: 2") && existing.contains("auraskills/crit_chance")) {
                Files.writeString(rewards.toPath(), content, StandardCharsets.UTF_8);
                GlovedPlugin.logInfo("[SummonXP] Migrated rewards/summoning.yml: level 1 now grants +1 crit_chance. Run /skills reload or restart.");
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Could not generate/migrate AuraSkills rewards file: " + t);
        }
    }

    private String rewardsV2Content() {
        return """
                # Auto-generated by GlovedPlugin (AuraSkills summoning rewards). Safe to edit; never overwritten after migration unless legacy auto-gen.
                # Loaded from GlovedPlugin content dir via useRegistry("gloved", ...): rewards/summoning.yml
                # Summoning grants crit stats (minion crit is AuraSkills-authoritative by default in GlovedPlugin).
                patterns:
                  - type: stat
                    stat: auraskills/crit_chance
                    value: 1
                    pattern:
                      start: 1
                      interval: 1
                      stop: 100
                  - type: stat
                    stat: auraskills/crit_damage
                    value: 1
                    pattern:
                      start: 2
                      interval: 2
                      stop: 100
                  - type: stat
                    stat: auraskills/strength
                    value: 1
                    pattern:
                      start: 3
                      interval: 2
                      stop: 100
                levels: {}
                """;
    }

    /**
     * Best-effort AuraSkills content parity/migration: generate or safely migrate skills.yml/abilities.yml.
     * Never overwrites user-modified files; writes *.v2.example.yml and logs instructions when needed.
     */
    private void tryGenerateOrMigrateAuraSkillsContentFiles(SummonConfig cfg) {
        try {
            if (cfg == null || !cfg.isProgressionGenerateAuraSkillsContentFiles()) return;
            File dir = plugin.getDataFolder();
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;

            migrateOrWrite(dir, "skills.yml", skillsV2Content(), legacySkillsSignature());
            migrateOrWrite(dir, "abilities.yml", abilitiesV2Content(), legacyAbilitiesSignature());
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Could not generate/migrate AuraSkills content files: " + t);
        }
    }

    private interface SignatureCheck { boolean isLegacy(String content); }

    private SignatureCheck legacySkillsSignature() {
        return content -> content != null
                && content.contains("Auto-generated by GlovedPlugin")
                && !content.contains("content-v2")
                && content.contains("gloved/summoning")
                && (content.contains("summoning_debuff") || content.contains("abilities:"));
    }

    private SignatureCheck legacyAbilitiesSignature() {
        return content -> content != null
                && content.contains("Auto-generated by GlovedPlugin")
                && !content.contains("content-v2")
                && (content.contains("level_up: 1000000") || content.contains("unlock: 5") || content.contains("unlock: 10") || content.contains("unlock: 15") || content.contains("max_level: 1"));
    }

    private void migrateOrWrite(File dir, String name, String v2, SignatureCheck legacy) throws Exception {
        File file = new File(dir, name);
        File marker = new File(dir, ".gloved-summoning-content-v2");
        if (!file.exists()) {
            Files.writeString(file.toPath(), v2, StandardCharsets.UTF_8);
            GlovedPlugin.logDebug("[SummonXP] Generated AuraSkills content file: " + name);
            touchMarker(marker);
            return;
        }
        String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        if (existing != null && existing.contains("content-v2") && !legacy.isLegacy(existing)) {
            return; // already v2-ish
        }
        if (legacy.isLegacy(existing)) {
            Files.writeString(file.toPath(), v2, StandardCharsets.UTF_8);
            GlovedPlugin.logInfo("[SummonXP] Migrated auto-generated " + name + " to v2 (multi-level abilities, 2/4/6 unlock pacing). Run /skills reload or restart for AuraSkills UI to update.");
            touchMarker(marker);
            return;
        }
        // User-modified or unknown: never overwrite; provide example.
        File example = new File(dir, name.replace(".yml", ".v2.example.yml"));
        if (!example.exists()) {
            Files.writeString(example.toPath(), v2, StandardCharsets.UTF_8);
            GlovedPlugin.logWarning("[SummonXP] " + name + " appears customized; not overwritten. Wrote " + example.getName() + " (multi-level abilities/rewards pacing). Merge manually and run /skills reload.");
        }
        touchMarker(marker);
    }

    private void touchMarker(File marker) {
        try {
            if (!marker.exists()) Files.writeString(marker.toPath(), "v" + CONTENT_VERSION + "\n", StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }

    private String skillsV2Content() {
        return """
                # Auto-generated by GlovedPlugin (AuraSkills summoning content-v2). Safe to edit; never overwritten after generation unless legacy auto-gen.
                # Functional unlocks/effectiveness are executed by GlovedPlugin (uses AuraSkills ability levels when available).
                skills:
                  gloved/summoning:
                    enabled: true
                    display_name: Summoning
                    description: Progress by commanding summoned minions in combat.
                    max_level: 100
                    abilities:
                      - gloved/summoning_debuff
                      - gloved/summoning_cleave
                      - gloved/summoning_lifesteal
                """;
    }

    private String abilitiesV2Content() {
        SummonConfig cfg = this.config;
        double debuffChanceBase = cfg != null ? cfg.getAbilityDebuffChance(1) * 100.0 : 15.0;
        double debuffChancePer = cfg != null ? Math.max(0.0, (cfg.getAbilityDebuffChance(2) - cfg.getAbilityDebuffChance(1)) * 100.0) : 1.5;
        double debuffDurBaseSec = cfg != null ? cfg.getAbilityDebuffDurationTicks(1) / 20.0 : 3.0;
        double debuffDurPerSec = cfg != null ? Math.max(0.0, (cfg.getAbilityDebuffDurationTicks(2) - cfg.getAbilityDebuffDurationTicks(1)) / 20.0) : 0.2;
        double cleaveChanceBase = cfg != null ? cfg.getAbilityCleaveChance(1) * 100.0 : 12.0;
        double cleaveChancePer = cfg != null ? Math.max(0.0, (cfg.getAbilityCleaveChance(2) - cfg.getAbilityCleaveChance(1)) * 100.0) : 1.2;
        double cleaveRatioBase = cfg != null ? cfg.getAbilityCleaveDamageRatio(1) * 100.0 : 25.0;
        double cleaveRatioPer = cfg != null ? Math.max(0.0, (cfg.getAbilityCleaveDamageRatio(2) - cfg.getAbilityCleaveDamageRatio(1)) * 100.0) : 1.5;
        double lsBase = cfg != null ? cfg.getAbilityLifestealHealPercentMaxHp(1) * 100.0 : 0.75;
        double lsPer = cfg != null ? Math.max(0.0, (cfg.getAbilityLifestealHealPercentMaxHp(2) - cfg.getAbilityLifestealHealPercentMaxHp(1)) * 100.0) : 0.10;
        // Keep formatting reasonable
        return String.format("""
                # Auto-generated by GlovedPlugin (AuraSkills summoning content-v2). Safe to edit; never overwritten after generation unless legacy auto-gen.
                # Archery/Fighting-like pacing: unlock 2/4/6, level_up 5, multi-level. GlovedPlugin executes effects and scales by ability level.
                abilities:
                  gloved/summoning_debuff:
                    enabled: true
                    display_name: Debuff
                    description: Minion hits can debuff targets. Chance and duration improve with ability level.
                    unlock: 2
                    level_up: 5
                    max_level: 0
                    base_value: %.3f
                    value_per_level: %.3f
                    secondary_base_value: %.3f
                    secondary_value_per_level: %.3f
                  gloved/summoning_cleave:
                    enabled: true
                    display_name: Cleave
                    description: Minion hits can deal AoE damage to nearby enemies. Chance and damage improve with ability level.
                    unlock: 4
                    level_up: 5
                    max_level: 0
                    base_value: %.3f
                    value_per_level: %.3f
                    secondary_base_value: %.3f
                    secondary_value_per_level: %.3f
                  gloved/summoning_lifesteal:
                    enabled: true
                    display_name: Life-steal
                    description: Minion damage heals a portion of your max HP. Healing improves with ability level.
                    unlock: 6
                    level_up: 5
                    max_level: 0
                    base_value: %.3f
                    value_per_level: %.3f
                """, debuffChanceBase, debuffChancePer, debuffDurBaseSec, debuffDurPerSec, cleaveChanceBase, cleaveChancePer, cleaveRatioBase, cleaveRatioPer, lsBase, lsPer);
    }

    @Override
    public int getSummoningLevel(UUID ownerId) {
        try {
            if (ownerId == null || Bukkit.getPlayer(ownerId) == null) return 0;
            long now = System.currentTimeMillis();
            LevelCache cache = levelCache.get(ownerId);
            if (cache != null && cache.expiresAtMs > now) return cache.level;
            AuraSkillsApi api = AuraSkillsApi.get();
            Skill skill = cachedSkill;
            if (api == null || skill == null) return 0;
            SkillsUser user = api.getUser(ownerId);
            if (user == null) return 0;
            int level = Math.max(0, user.getSkillLevel(skill));
            levelCache.put(ownerId, new LevelCache(level, now + 1000L));
            return level;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonXP] Level lookup failed: " + t);
            return 0;
        }
    }

    /** Auto-reload AuraSkills a few times if the skill is missing from the menu after first join. */
    public void ensureSkillPresentAndReload() {
        if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) return;
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            if (api == null) return;
            NamespacedId summonId = NamespacedId.of("gloved", "summoning");
            Skill skill = null;
            try { skill = api.getGlobalRegistry().getSkill(summonId); } catch (Throwable ignored) {}
            if (skill != null) {
                GlovedPlugin.logDebug("[SummonXP] AuraSkills summoning skill present on join check, no reload needed.");
                return;
            }
        } catch (Throwable ignored) {}
        // Skill missing – attempt reloads
        GlovedPlugin.logDebug("[SummonXP] Summoning skill not seen in AuraSkills registry after join, attempting auto-reload.");
        for (int i = 1; i <= 3; i++) {
            final int attempt = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "skills reload");
                    GlovedPlugin.logDebug("[SummonXP] Auto-reloaded AuraSkills skills, attempt " + attempt);
                } catch (Throwable t) {
                    GlovedPlugin.logDebug("[SummonXP] Auto-reload failed: " + t);
                }
            }, 20L * (i * 5));
        }
        // Re-activate bridge after a short delay to re-register
        Bukkit.getScheduler().runTaskLater(plugin, () -> scheduleActivateRetry(false), 20L * 6);
    }
}
