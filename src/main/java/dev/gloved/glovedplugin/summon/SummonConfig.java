package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SummonConfig {
    private final JavaPlugin plugin;
    private final Map<String, SummonProfile> profiles = new LinkedHashMap<>();
    private boolean enabled;
    private boolean minionProcSummon = false;
    private boolean clumpedNametagsEnabled = true;
    private int clumpedNametagsMinCount = 4;
    private double clumpedNametagsRadius = 2.0;
    private int clumpedNametagsUpdateTicks = 10;
    private String clumpedNametagsFormat = "{nametag} x{count}";

    // Progression XP via AuraSkills.
    private boolean progressionEnabled = true;
    private boolean progressionAuraSkillsEnabled = true;
    private String progressionSkillId = "summoning";
    private boolean progressionApplyMultipliers = true;
    private boolean progressionHitXpEnabled = true;
    private double progressionHitXpAmount = 0.5;
    private int progressionHitOwnerCooldownTicks = 10;
    private int progressionHitTargetCooldownTicks = 40;
    private double progressionHitMaxPerMinute = 120.0;
    private boolean progressionKillXpEnabled = true;
    private double progressionKillXpAmount = 8.0;
    private int progressionKillAttributionWindowTicks = 200;
    private double progressionKillMaxPerMinute = 80.0;
    private boolean progressionGenerateAuraSkillsContentFiles = true;

    // AuraSkills mana cost per summon trigger (batch). consumeMana sends "not enough mana" feedback on failure.
    private boolean manaEnabled = true;
    private double manaCostPerTrigger = 3.0;
    private boolean manaAllowWhenAuraSkillsMissing = true;

    // Summon scaling (Summoning skill level).
    private boolean scalingEnabled = true;
    private double scalingChancePerLevel = 0.005;
    private double scalingChanceMax = 0.95;
    private double scalingMinionsPerLevel = 0.10;
    private int scalingMinionsPerHitMax = 12;
    private double scalingMaxActivePerLevel = 0.25;
    private int scalingMaxActiveMax = 30;

    // Minion crit: AuraSkills-authoritative by default (+1 crit_chance/level, +1 crit_damage/2 levels via rewards).
    private boolean critEnabled = true;
    private String critSource = "auraskills"; // auraskills | legacy
    private double critMaxChance = 0.40; // hard safety cap applied after AuraSkills stat (default 40%)
    private double critMinDamageMultiplier = 1.0;
    private double critMaxDamageMultiplier = 3.0;
    // Legacy (used only when critSource=legacy; kept for backward compatibility, no double-dip in auraskills mode)
    private double critLegacyBaseChance = 0.0;
    private double critLegacyPerLevel = 0.008;
    private double critLegacyDamageMultiplier = 1.5;

    // Minion damage can be affected by owner AuraSkills Strength (attack-damage trait), capped.
    private boolean strengthDamageEnabled = true;
    private double strengthDamagePerAttackDamageTrait = 0.20;
    private double strengthDamageMaxBonus = 12.0;

    // Abilities: multi-level, archery/fighting-like pacing (unlock 2/4/6, level_up 5 in AuraSkills content).
    // Execution prefers AuraSkills ability level; falls back to Summoning skill level >= unlock (virtual level 1).
    private boolean abilitiesEnabled = true;
    private boolean abilitiesUseAuraAbilityLevels = true;

    // Debuff (unlock 2)
    private boolean abilityDebuffEnabled = true;
    private int abilityDebuffUnlockLevel = 2;
    private double abilityDebuffBaseChance = 0.15;
    private double abilityDebuffChancePerLevel = 0.015; // ability level scaling
    private double abilityDebuffMaxChance = 0.45;
    private int abilityDebuffCooldownTicks = 60;
    private String abilityDebuffEffect = "SLOWNESS";
    private int abilityDebuffDurationTicks = 60;
    private int abilityDebuffDurationPerLevel = 4;
    private int abilityDebuffMaxDurationTicks = 120;
    private int abilityDebuffAmplifier = 0;
    private boolean abilityDebuffSynergyAnyCommonDebuff = true;

    // Cleave (unlock 4)
    private boolean abilityCleaveEnabled = true;
    private int abilityCleaveUnlockLevel = 4;
    private double abilityCleaveBaseChance = 0.12;
    private double abilityCleaveChancePerLevel = 0.012;
    private double abilityCleaveMaxChance = 0.40;
    private int abilityCleaveCooldownTicks = 80;
    private double abilityCleaveBaseRadius = 2.0;
    private double abilityCleaveRadiusPerLevel = 0.10;
    private double abilityCleaveMaxRadius = 4.0;
    private int abilityCleaveMaxTargets = 3;
    private double abilityCleaveBaseDamageRatio = 0.25;
    private double abilityCleaveDamageRatioPerLevel = 0.015;
    private double abilityCleaveMaxDamageRatio = 0.60;
    private double abilityCleaveMaxDamage = 8.0;
    private boolean abilityCleaveSpreadDebuff = true;
    private boolean abilityCleaveDamageRatioUsesBaseDamage = false;
    private double abilityCleaveMaxDamagePerMinute = 180.0;

    // Life-Steal (unlock 6)
    private boolean abilityLifestealEnabled = true;
    private int abilityLifestealUnlockLevel = 6;
    private double abilityLifestealBaseHealPercentMaxHp = 0.0075; // 0.75%
    private double abilityLifestealHealPercentPerLevel = 0.0010;  // +0.10% per ability level
    private double abilityLifestealMaxHealPercentMaxHp = 0.03;    // cap 3%
    private double abilityLifestealDebuffedBonusMultiplier = 1.25;
    private double abilityLifestealPerHitHealCap = 2.5;
    private double abilityLifestealMaxHealPerMinute = 40.0;

    private boolean warnedLegacyCrit;

    private SummonConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static SummonConfig load(JavaPlugin plugin) {
        SummonConfig cfg = new SummonConfig(plugin);
        cfg.reload();
        return cfg;
    }

    /**
     * Re-reads {@code summon.yml} into this instance. Listeners and managers keep a reference to
     * the same object, so {@code /gloved reload} applies changes without a restart.
     */
    public void reload() {
        profiles.clear();
        File file = new File(plugin.getDataFolder(), "summon.yml");
        if (!file.exists()) {
            plugin.saveResource("summon.yml", false);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        backfill(yaml);
        try { yaml.save(file); } catch (Exception ignored) {}
        enabled = yaml.getBoolean("enabled", true);
        minionProcSummon = yaml.getBoolean("minion-proc-summon", false);
        clumpedNametagsEnabled = yaml.getBoolean("clumped-nametags.enabled", true);
        clumpedNametagsMinCount = Math.max(2, yaml.getInt("clumped-nametags.min-count", 4));
        clumpedNametagsRadius = Math.max(0.5, yaml.getDouble("clumped-nametags.radius", 2.0));
        clumpedNametagsUpdateTicks = Math.max(1, yaml.getInt("clumped-nametags.update-ticks", 10));
        clumpedNametagsFormat = yaml.getString("clumped-nametags.format", "{nametag} x{count}");
        if ("&e{player}'s Minions x{count}".equals(clumpedNametagsFormat)) {
            clumpedNametagsFormat = "{nametag} x{count}";
        }

        progressionEnabled = yaml.getBoolean("progression.enabled", true);
        progressionAuraSkillsEnabled = yaml.getBoolean("progression.auraskills.enabled", true);
        progressionSkillId = yaml.getString("progression.auraskills.skill-id", "summoning");
        if (progressionSkillId == null || progressionSkillId.isBlank()) progressionSkillId = "summoning";
        progressionSkillId = progressionSkillId.trim().toLowerCase(Locale.ROOT);
        progressionApplyMultipliers = yaml.getBoolean("progression.auraskills.apply-multipliers", true);
        progressionGenerateAuraSkillsContentFiles = yaml.getBoolean("progression.auraskills.generate-content-files", true);
        manaEnabled = yaml.getBoolean("progression.mana.enabled", true);
        manaCostPerTrigger = safeNonNegative(yaml.getDouble("progression.mana.cost-per-trigger", 3.0));
        manaAllowWhenAuraSkillsMissing = yaml.getBoolean("progression.mana.allow-when-auraskills-missing", true);
        progressionHitXpEnabled = yaml.getBoolean("progression.xp.minion-hit.enabled", true);
        progressionHitXpAmount = safeNonNegative(yaml.getDouble("progression.xp.minion-hit.amount", 0.5));
        progressionHitOwnerCooldownTicks = Math.max(0, yaml.getInt("progression.xp.minion-hit.owner-cooldown-ticks", 10));
        progressionHitTargetCooldownTicks = Math.max(0, yaml.getInt("progression.xp.minion-hit.per-target-cooldown-ticks", 40));
        progressionHitMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.minion-hit.max-per-minute", 120.0));
        progressionKillXpEnabled = yaml.getBoolean("progression.xp.minion-kill.enabled", true);
        progressionKillXpAmount = safeNonNegative(yaml.getDouble("progression.xp.minion-kill.amount", 8.0));
        progressionKillAttributionWindowTicks = Math.max(1, yaml.getInt("progression.xp.minion-kill.attribution-window-ticks", 200));
        progressionKillMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.minion-kill.max-per-minute", 80.0));

        scalingEnabled = yaml.getBoolean("progression.scaling.enabled", true);
        scalingChancePerLevel = safeNonNegative(yaml.getDouble("progression.scaling.chance.per-level", 0.005));
        scalingChanceMax = clamp(yaml.getDouble("progression.scaling.chance.max", 0.95), 0.0, 1.0);
        scalingMinionsPerLevel = safeNonNegative(yaml.getDouble("progression.scaling.minions-per-hit.per-level", 0.10));
        scalingMinionsPerHitMax = Math.max(1, yaml.getInt("progression.scaling.minions-per-hit.max", 12));
        scalingMaxActivePerLevel = safeNonNegative(yaml.getDouble("progression.scaling.max-active.per-level", 0.25));
        scalingMaxActiveMax = Math.max(1, yaml.getInt("progression.scaling.max-active.max", 30));

        critEnabled = yaml.getBoolean("progression.crit.enabled", true);
        critSource = yaml.getString("progression.crit.source", "auraskills");
        if (critSource == null || critSource.isBlank()) critSource = "auraskills";
        critSource = critSource.trim().toLowerCase(Locale.ROOT);
        if (!critSource.equals("legacy")) critSource = "auraskills";
        critMaxChance = clamp(yaml.getDouble("progression.crit.max-chance", 0.40), 0.0, 1.0);
        // Preserve safety: default cap 40%; allow explicit config but keep sane upper bound.
        if (!yaml.isSet("progression.crit.max-chance")) critMaxChance = 0.40;
        critMinDamageMultiplier = Math.max(1.0, yaml.getDouble("progression.crit.min-damage-multiplier", 1.0));
        critMaxDamageMultiplier = Math.max(critMinDamageMultiplier, yaml.getDouble("progression.crit.max-damage-multiplier", 3.0));
        // Legacy fields (read for backward compatibility / legacy mode). Do not double-dip in auraskills mode.
        critLegacyBaseChance = clamp(yaml.getDouble("progression.crit.base-chance", 0.0), 0.0, 1.0);
        critLegacyPerLevel = safeNonNegative(yaml.getDouble("progression.crit.per-level", 0.008));
        critLegacyDamageMultiplier = Math.max(1.0, yaml.getDouble("progression.crit.damage-multiplier", 1.5));
        if (critSource.equals("auraskills") && (yaml.isSet("progression.crit.base-chance") || yaml.isSet("progression.crit.per-level")
                || yaml.isSet("progression.crit.damage-multiplier")) && !warnedLegacyCrit) {
            warnedLegacyCrit = true;
            GlovedPlugin.logDebug("[SummonCrit] progression.crit source=auraskills; legacy base-chance/per-level/damage-multiplier are ignored (no double-dip). Use max-chance/max-damage-multiplier caps.");
        }

        strengthDamageEnabled = yaml.getBoolean("progression.minion-damage.owner-strength.enabled", true);
        strengthDamagePerAttackDamageTrait = clamp(yaml.getDouble("progression.minion-damage.owner-strength.per-attack-damage-trait", 0.20), 0.0, 10.0);
        strengthDamageMaxBonus = clamp(yaml.getDouble("progression.minion-damage.owner-strength.max-bonus", 12.0), 0.0, 1000.0);

        abilitiesEnabled = yaml.getBoolean("progression.abilities.enabled", true);
        abilitiesUseAuraAbilityLevels = yaml.getBoolean("progression.abilities.use-auraskills-ability-levels", true);

        abilityDebuffEnabled = yaml.getBoolean("progression.abilities.debuff.enabled", true);
        abilityDebuffUnlockLevel = Math.max(0, yaml.getInt("progression.abilities.debuff.unlock-level", 2));
        abilityDebuffBaseChance = clamp(yaml.getDouble("progression.abilities.debuff.base-chance", yaml.getDouble("progression.abilities.debuff.chance", 0.15)), 0.0, 1.0);
        abilityDebuffChancePerLevel = clamp(yaml.getDouble("progression.abilities.debuff.chance-per-level", 0.015), 0.0, 1.0);
        abilityDebuffMaxChance = clamp(yaml.getDouble("progression.abilities.debuff.max-chance", 0.45), 0.0, 1.0);
        abilityDebuffCooldownTicks = Math.max(0, yaml.getInt("progression.abilities.debuff.cooldown-ticks", 60));
        abilityDebuffEffect = yaml.getString("progression.abilities.debuff.effect", "SLOWNESS");
        if (abilityDebuffEffect == null || abilityDebuffEffect.isBlank()) abilityDebuffEffect = "SLOWNESS";
        abilityDebuffDurationTicks = Math.max(1, yaml.getInt("progression.abilities.debuff.duration-ticks", 60));
        abilityDebuffDurationPerLevel = Math.max(0, yaml.getInt("progression.abilities.debuff.duration-per-level", 4));
        abilityDebuffMaxDurationTicks = Math.max(abilityDebuffDurationTicks, yaml.getInt("progression.abilities.debuff.max-duration-ticks", 120));
        abilityDebuffAmplifier = Math.max(0, yaml.getInt("progression.abilities.debuff.amplifier", 0));
        abilityDebuffSynergyAnyCommonDebuff = yaml.getBoolean("progression.abilities.debuff.synergy-any-common-debuff", true);

        abilityCleaveEnabled = yaml.getBoolean("progression.abilities.cleave.enabled", true);
        abilityCleaveUnlockLevel = Math.max(0, yaml.getInt("progression.abilities.cleave.unlock-level", 4));
        abilityCleaveBaseChance = clamp(yaml.getDouble("progression.abilities.cleave.base-chance", yaml.getDouble("progression.abilities.cleave.chance", 0.12)), 0.0, 1.0);
        abilityCleaveChancePerLevel = clamp(yaml.getDouble("progression.abilities.cleave.chance-per-level", 0.012), 0.0, 1.0);
        abilityCleaveMaxChance = clamp(yaml.getDouble("progression.abilities.cleave.max-chance", 0.40), 0.0, 1.0);
        abilityCleaveCooldownTicks = Math.max(0, yaml.getInt("progression.abilities.cleave.cooldown-ticks", 80));
        abilityCleaveBaseRadius = clamp(yaml.getDouble("progression.abilities.cleave.base-radius", yaml.getDouble("progression.abilities.cleave.radius", 2.0)), 0.1, 16.0);
        abilityCleaveRadiusPerLevel = clamp(yaml.getDouble("progression.abilities.cleave.radius-per-level", 0.10), 0.0, 16.0);
        abilityCleaveMaxRadius = clamp(yaml.getDouble("progression.abilities.cleave.max-radius", 4.0), 0.1, 16.0);
        abilityCleaveMaxTargets = Math.max(1, yaml.getInt("progression.abilities.cleave.max-targets", 3));
        abilityCleaveBaseDamageRatio = clamp(yaml.getDouble("progression.abilities.cleave.base-damage-ratio", yaml.getDouble("progression.abilities.cleave.damage-ratio", 0.25)), 0.0, 10.0);
        abilityCleaveDamageRatioPerLevel = clamp(yaml.getDouble("progression.abilities.cleave.damage-ratio-per-level", 0.015), 0.0, 10.0);
        abilityCleaveMaxDamageRatio = clamp(yaml.getDouble("progression.abilities.cleave.max-damage-ratio", 0.60), 0.0, 10.0);
        abilityCleaveMaxDamage = safeNonNegative(yaml.getDouble("progression.abilities.cleave.max-damage", 8.0));
        abilityCleaveSpreadDebuff = yaml.getBoolean("progression.abilities.cleave.spread-debuff", true);
        abilityCleaveDamageRatioUsesBaseDamage = yaml.getBoolean("progression.abilities.cleave.damage-ratio-uses-base-damage", false);
        abilityCleaveMaxDamagePerMinute = safeNonNegative(yaml.getDouble("progression.abilities.cleave.max-damage-per-minute", 180.0));

        abilityLifestealEnabled = yaml.getBoolean("progression.abilities.lifesteal.enabled", true);
        abilityLifestealUnlockLevel = Math.max(0, yaml.getInt("progression.abilities.lifesteal.unlock-level", 6));
        abilityLifestealBaseHealPercentMaxHp = clamp(yaml.getDouble("progression.abilities.lifesteal.base-heal-percent-max-hp",
                yaml.getDouble("progression.abilities.lifesteal.heal-percent-max-hp", 0.0075)), 0.0, 1.0);
        abilityLifestealHealPercentPerLevel = clamp(yaml.getDouble("progression.abilities.lifesteal.heal-percent-per-level", 0.0010), 0.0, 1.0);
        abilityLifestealMaxHealPercentMaxHp = clamp(yaml.getDouble("progression.abilities.lifesteal.max-heal-percent-max-hp", 0.03), 0.0, 1.0);
        abilityLifestealDebuffedBonusMultiplier = Math.max(1.0, yaml.getDouble("progression.abilities.lifesteal.debuffed-bonus-multiplier", 1.25));
        abilityLifestealPerHitHealCap = safeNonNegative(yaml.getDouble("progression.abilities.lifesteal.per-hit-heal-cap", 2.5));
        abilityLifestealMaxHealPerMinute = safeNonNegative(yaml.getDouble("progression.abilities.lifesteal.max-heal-per-minute", 40.0));

        if (yaml.isConfigurationSection("profiles")) {
            for (String key : yaml.getConfigurationSection("profiles").getKeys(false)) {
                var section = yaml.getConfigurationSection("profiles." + key);
                if (section == null) continue;
                if (section.isSet("mob-types") || section.isList("mob-types")) {
                    GlovedPlugin.logInfo("[Summon] Profile '" + key + "': 'mob-types' (list) is invalid; use 'mob-type' (single). "
                            + (section.isSet("mob-type") ? "Ignoring 'mob-types'." : "Falling back to first entry; migrate to 'mob-type'."));
                }
                String mobType = section.getString("mob-type");
                if (mobType == null || mobType.isBlank()) {
                    List<String> legacy = section.getStringList("mob-types");
                    if (legacy != null && !legacy.isEmpty()) mobType = legacy.get(0);
                }
                if (mobType == null || mobType.isBlank()) mobType = "BEE";
                double chance = section.getDouble("chance", 0.3);
                int minionsPerHit = section.getInt("minions-per-hit", section.getInt("minions", 2));
                int maxActive = section.getInt("max-active", 10);
                String nametagColor = section.getString("nametag-color", "YELLOW");
                String enchantName = section.getString("enchant-name");
                if (enchantName == null || enchantName.isBlank()) {
                    enchantName = deriveEnchantName(key);
                }
                SummonProfile profile = new SummonProfile(key, mobType,
                        chance, minionsPerHit, maxActive, nametagColor, enchantName);
                profiles.put(key, profile);
                GlovedPlugin.logDebug("[Summon] Loaded profile " + key + ": enchant-name=\"" + profile.enchantName
                        + "\", minions-per-hit=" + profile.minionsPerHit
                        + ", max-active=" + profile.maxActive + ", chance=" + profile.chance
                        + ", mob-type=" + profile.mobType);
            }
        }
        // Auto-generate missing EliteMobs enchantment marker files for configured profiles.
        EliteMobsSummonFileGenerator.generate(plugin, profiles);
    }

    private static void backfill(FileConfiguration cfg) {
        if (!cfg.isSet("enabled")) cfg.set("enabled", true);
        if (!cfg.isSet("minion-proc-summon")) cfg.set("minion-proc-summon", false);
        if (!cfg.isSet("clumped-nametags.enabled")) cfg.set("clumped-nametags.enabled", true);
        if (!cfg.isSet("clumped-nametags.min-count")) cfg.set("clumped-nametags.min-count", 4);
        if (!cfg.isSet("clumped-nametags.radius")) cfg.set("clumped-nametags.radius", 2.0);
        if (!cfg.isSet("clumped-nametags.update-ticks")) cfg.set("clumped-nametags.update-ticks", 10);
        if (!cfg.isSet("clumped-nametags.format")) cfg.set("clumped-nametags.format", "{nametag} x{count}");
        if ("&e{player}'s Minions x{count}".equals(cfg.getString("clumped-nametags.format"))) {
            cfg.set("clumped-nametags.format", "{nametag} x{count}");
        }
        // Progression / AuraSkills
        setIfMissing(cfg, "progression.enabled", true);
        setIfMissing(cfg, "progression.auraskills.enabled", true);
        setIfMissing(cfg, "progression.auraskills.skill-id", "summoning");
        setIfMissing(cfg, "progression.auraskills.apply-multipliers", true);
        setIfMissing(cfg, "progression.auraskills.generate-content-files", true);
        setIfMissing(cfg, "progression.mana.enabled", true);
        setIfMissing(cfg, "progression.mana.cost-per-trigger", 3.0);
        setIfMissing(cfg, "progression.mana.allow-when-auraskills-missing", true);
        // XP
        setIfMissing(cfg, "progression.xp.minion-hit.enabled", true);
        setIfMissing(cfg, "progression.xp.minion-hit.amount", 0.5);
        setIfMissing(cfg, "progression.xp.minion-hit.owner-cooldown-ticks", 10);
        setIfMissing(cfg, "progression.xp.minion-hit.per-target-cooldown-ticks", 40);
        setIfMissing(cfg, "progression.xp.minion-hit.max-per-minute", 120.0);
        setIfMissing(cfg, "progression.xp.minion-kill.enabled", true);
        setIfMissing(cfg, "progression.xp.minion-kill.amount", 8.0);
        setIfMissing(cfg, "progression.xp.minion-kill.attribution-window-ticks", 200);
        setIfMissing(cfg, "progression.xp.minion-kill.max-per-minute", 80.0);
        // Scaling
        setIfMissing(cfg, "progression.scaling.enabled", true);
        setIfMissing(cfg, "progression.scaling.chance.per-level", 0.005);
        setIfMissing(cfg, "progression.scaling.chance.max", 0.95);
        setIfMissing(cfg, "progression.scaling.minions-per-hit.per-level", 0.10);
        setIfMissing(cfg, "progression.scaling.minions-per-hit.max", 12);
        setIfMissing(cfg, "progression.scaling.max-active.per-level", 0.25);
        setIfMissing(cfg, "progression.scaling.max-active.max", 30);
        // Crit (AuraSkills-authoritative; legacy fields preserved if present, ignored in auraskills mode)
        setIfMissing(cfg, "progression.crit.enabled", true);
        setIfMissing(cfg, "progression.crit.source", "auraskills");
        setIfMissing(cfg, "progression.crit.max-chance", 0.40);
        setIfMissing(cfg, "progression.crit.min-damage-multiplier", 1.0);
        setIfMissing(cfg, "progression.crit.max-damage-multiplier", 3.0);
        // Strength -> minion damage
        setIfMissing(cfg, "progression.minion-damage.owner-strength.enabled", true);
        setIfMissing(cfg, "progression.minion-damage.owner-strength.per-attack-damage-trait", 0.20);
        setIfMissing(cfg, "progression.minion-damage.owner-strength.max-bonus", 12.0);
        // Abilities (Archery/Fighting-like pacing: unlock 2/4/6, level_up 5; multi-level effectiveness)
        setIfMissing(cfg, "progression.abilities.enabled", true);
        setIfMissing(cfg, "progression.abilities.use-auraskills-ability-levels", true);
        // Debuff
        setIfMissing(cfg, "progression.abilities.debuff.enabled", true);
        setIfMissing(cfg, "progression.abilities.debuff.unlock-level", 2);
        if (!cfg.isSet("progression.abilities.debuff.base-chance") && cfg.isSet("progression.abilities.debuff.chance"))
            cfg.set("progression.abilities.debuff.base-chance", cfg.getDouble("progression.abilities.debuff.chance"));
        setIfMissing(cfg, "progression.abilities.debuff.base-chance", 0.15);
        setIfMissing(cfg, "progression.abilities.debuff.chance-per-level", 0.015);
        setIfMissing(cfg, "progression.abilities.debuff.max-chance", 0.45);
        setIfMissing(cfg, "progression.abilities.debuff.cooldown-ticks", 60);
        setIfMissing(cfg, "progression.abilities.debuff.effect", "SLOWNESS");
        setIfMissing(cfg, "progression.abilities.debuff.duration-ticks", 60);
        setIfMissing(cfg, "progression.abilities.debuff.duration-per-level", 4);
        setIfMissing(cfg, "progression.abilities.debuff.max-duration-ticks", 120);
        setIfMissing(cfg, "progression.abilities.debuff.amplifier", 0);
        setIfMissing(cfg, "progression.abilities.debuff.synergy-any-common-debuff", true);
        // Cleave
        setIfMissing(cfg, "progression.abilities.cleave.enabled", true);
        setIfMissing(cfg, "progression.abilities.cleave.unlock-level", 4);
        if (!cfg.isSet("progression.abilities.cleave.base-chance") && cfg.isSet("progression.abilities.cleave.chance"))
            cfg.set("progression.abilities.cleave.base-chance", cfg.getDouble("progression.abilities.cleave.chance"));
        setIfMissing(cfg, "progression.abilities.cleave.base-chance", 0.12);
        setIfMissing(cfg, "progression.abilities.cleave.chance-per-level", 0.012);
        setIfMissing(cfg, "progression.abilities.cleave.max-chance", 0.40);
        setIfMissing(cfg, "progression.abilities.cleave.cooldown-ticks", 80);
        if (!cfg.isSet("progression.abilities.cleave.base-radius") && cfg.isSet("progression.abilities.cleave.radius"))
            cfg.set("progression.abilities.cleave.base-radius", cfg.getDouble("progression.abilities.cleave.radius"));
        setIfMissing(cfg, "progression.abilities.cleave.base-radius", 2.0);
        setIfMissing(cfg, "progression.abilities.cleave.radius-per-level", 0.10);
        setIfMissing(cfg, "progression.abilities.cleave.max-radius", 4.0);
        setIfMissing(cfg, "progression.abilities.cleave.max-targets", 3);
        if (!cfg.isSet("progression.abilities.cleave.base-damage-ratio") && cfg.isSet("progression.abilities.cleave.damage-ratio"))
            cfg.set("progression.abilities.cleave.base-damage-ratio", cfg.getDouble("progression.abilities.cleave.damage-ratio"));
        setIfMissing(cfg, "progression.abilities.cleave.base-damage-ratio", 0.25);
        setIfMissing(cfg, "progression.abilities.cleave.damage-ratio-per-level", 0.015);
        setIfMissing(cfg, "progression.abilities.cleave.max-damage-ratio", 0.60);
        setIfMissing(cfg, "progression.abilities.cleave.max-damage", 8.0);
        setIfMissing(cfg, "progression.abilities.cleave.spread-debuff", true);
        setIfMissing(cfg, "progression.abilities.cleave.damage-ratio-uses-base-damage", false);
        setIfMissing(cfg, "progression.abilities.cleave.max-damage-per-minute", 180.0);
        // Life-steal
        setIfMissing(cfg, "progression.abilities.lifesteal.enabled", true);
        setIfMissing(cfg, "progression.abilities.lifesteal.unlock-level", 6);
        if (!cfg.isSet("progression.abilities.lifesteal.base-heal-percent-max-hp") && cfg.isSet("progression.abilities.lifesteal.heal-percent-max-hp"))
            cfg.set("progression.abilities.lifesteal.base-heal-percent-max-hp", cfg.getDouble("progression.abilities.lifesteal.heal-percent-max-hp"));
        setIfMissing(cfg, "progression.abilities.lifesteal.base-heal-percent-max-hp", 0.0075);
        setIfMissing(cfg, "progression.abilities.lifesteal.heal-percent-per-level", 0.0010);
        setIfMissing(cfg, "progression.abilities.lifesteal.max-heal-percent-max-hp", 0.03);
        setIfMissing(cfg, "progression.abilities.lifesteal.debuffed-bonus-multiplier", 1.25);
        setIfMissing(cfg, "progression.abilities.lifesteal.per-hit-heal-cap", 2.5);
        setIfMissing(cfg, "progression.abilities.lifesteal.max-heal-per-minute", 40.0);

        for (String deprecated : List.of("default-profile")) {
            if (cfg.isSet(deprecated)) cfg.set(deprecated, null);
        }
        var profiles = cfg.getConfigurationSection("profiles");
        if (profiles == null) return;
        for (String key : profiles.getKeys(false)) {
            var s = profiles.getConfigurationSection(key);
            if (s == null) continue;
            if (s.isSet("minions") && !s.isSet("minions-per-hit")) {
                s.set("minions-per-hit", s.getInt("minions", 2));
            }
            for (String deprecated : List.of("ai", "damage-proxy", "tamed", "friendly",
                    "minions", "invulnerable", "cooldown_ticks", "lifetime_ticks",
                    "follow-range", "attack-range", "target-mode", "nametag-format",
                    "nametag-visible", "valid-slots")) {
                if (s.isSet(deprecated)) s.set(deprecated, null);
            }
            if (s.isSet("mob-types") || s.isList("mob-types")) {
                if (!s.isSet("mob-type") || String.valueOf(s.get("mob-type")).isBlank()) {
                    var legacy = s.getStringList("mob-types");
                    if (legacy != null && !legacy.isEmpty()) s.set("mob-type", legacy.get(0));
                }
                s.set("mob-types", null);
                GlovedPlugin.logInfo("[Summon] Profile '" + key + "': migrated invalid 'mob-types' -> 'mob-type'.");
            }
            setIfMissing(s, "mob-type", "BEE");
            setIfMissing(s, "chance", 0.3);
            setIfMissing(s, "minions-per-hit", 2);
            setIfMissing(s, "max-active", 10);
            setIfMissing(s, "nametag-color", "YELLOW");
            if (!s.isSet("enchant-name") || String.valueOf(s.get("enchant-name")).isBlank()) {
                s.set("enchant-name", deriveEnchantName(key));
            }
        }
    }

    private static void setIfMissing(org.bukkit.configuration.ConfigurationSection cfg, String path, Object value) {
        if (!cfg.isSet(path)) cfg.set(path, value);
    }

    private static String deriveEnchantName(String profileKey) {
        if (profileKey == null || profileKey.isBlank()) return "";
        String[] parts = profileKey.toLowerCase().replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static double safeNonNegative(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return 0;
        return v;
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    public boolean isEnabled() { return enabled; }
    public boolean isMinionProcSummon() { return minionProcSummon; }
    public SummonProfile getProfile(String name) { return profiles.get(name); }
    public Map<String, SummonProfile> getProfiles() { return profiles; }

    public boolean isClumpedNametagsEnabled() { return clumpedNametagsEnabled; }
    public int getClumpedNametagsMinCount() { return clumpedNametagsMinCount; }
    public double getClumpedNametagsRadius() { return clumpedNametagsRadius; }
    public int getClumpedNametagsUpdateTicks() { return clumpedNametagsUpdateTicks; }
    public String getClumpedNametagsFormat() { return clumpedNametagsFormat; }

    public boolean isProgressionEnabled() { return progressionEnabled; }
    public boolean isProgressionAuraSkillsEnabled() { return progressionAuraSkillsEnabled; }
    public String getProgressionSkillId() { return progressionSkillId; }
    public boolean isProgressionApplyMultipliers() { return progressionApplyMultipliers; }
    public boolean isProgressionHitXpEnabled() { return progressionHitXpEnabled; }
    public double getProgressionHitXpAmount() { return progressionHitXpAmount; }
    public int getProgressionHitOwnerCooldownTicks() { return progressionHitOwnerCooldownTicks; }
    public int getProgressionHitTargetCooldownTicks() { return progressionHitTargetCooldownTicks; }
    public double getProgressionHitMaxPerMinute() { return progressionHitMaxPerMinute; }
    public boolean isProgressionKillXpEnabled() { return progressionKillXpEnabled; }
    public double getProgressionKillXpAmount() { return progressionKillXpAmount; }
    public int getProgressionKillAttributionWindowTicks() { return progressionKillAttributionWindowTicks; }
    public double getProgressionKillMaxPerMinute() { return progressionKillMaxPerMinute; }
    public boolean isProgressionGenerateAuraSkillsContentFiles() { return progressionGenerateAuraSkillsContentFiles; }
    public boolean isManaEnabled() { return manaEnabled; }
    public double getManaCostPerTrigger() { return manaCostPerTrigger; }
    public boolean isManaAllowWhenAuraSkillsMissing() { return manaAllowWhenAuraSkillsMissing; }

    public boolean isScalingEnabled() { return scalingEnabled; }
    public double getScalingChancePerLevel() { return scalingChancePerLevel; }
    public double getScalingChanceMax() { return scalingChanceMax; }
    public double getScalingMinionsPerLevel() { return scalingMinionsPerLevel; }
    public int getScalingMinionsPerHitMax() { return scalingMinionsPerHitMax; }
    public double getScalingMaxActivePerLevel() { return scalingMaxActivePerLevel; }
    public int getScalingMaxActiveMax() { return scalingMaxActiveMax; }

    public boolean isCritEnabled() { return critEnabled; }
    public String getCritSource() { return critSource; }
    public boolean isCritSourceAuraSkills() { return "auraskills".equalsIgnoreCase(critSource); }
    public double getCritMaxChance() { return critMaxChance; }
    public double getCritMinDamageMultiplier() { return critMinDamageMultiplier; }
    public double getCritMaxDamageMultiplier() { return critMaxDamageMultiplier; }
    /** @deprecated legacy; ignored when source=auraskills (no double-dip). */
    @Deprecated public double getCritBaseChance() { return critLegacyBaseChance; }
    /** @deprecated legacy; ignored when source=auraskills. */
    @Deprecated public double getCritPerLevel() { return critLegacyPerLevel; }
    /** @deprecated legacy; ignored when source=auraskills. */
    @Deprecated public double getCritDamageMultiplier() { return critLegacyDamageMultiplier; }
    public double getCritLegacyBaseChance() { return critLegacyBaseChance; }
    public double getCritLegacyPerLevel() { return critLegacyPerLevel; }
    public double getCritLegacyDamageMultiplier() { return critLegacyDamageMultiplier; }

    public boolean isStrengthDamageEnabled() { return strengthDamageEnabled; }
    public double getStrengthDamagePerAttackDamageTrait() { return strengthDamagePerAttackDamageTrait; }
    public double getStrengthDamageMaxBonus() { return strengthDamageMaxBonus; }

    public boolean isAbilitiesEnabled() { return abilitiesEnabled; }
    public boolean isAbilitiesUseAuraAbilityLevels() { return abilitiesUseAuraAbilityLevels; }

    public boolean isAbilityDebuffEnabled() { return abilityDebuffEnabled; }
    public int getAbilityDebuffUnlockLevel() { return abilityDebuffUnlockLevel; }
    public double getAbilityDebuffBaseChance() { return abilityDebuffBaseChance; }
    public double getAbilityDebuffChancePerLevel() { return abilityDebuffChancePerLevel; }
    public double getAbilityDebuffMaxChance() { return abilityDebuffMaxChance; }
    /** Effective debuff chance for a resolved AuraSkills ability level (>=1). */
    public double getAbilityDebuffChance(int abilityLevel) {
        int lvl = Math.max(1, abilityLevel);
        return clamp(abilityDebuffBaseChance + abilityDebuffChancePerLevel * (lvl - 1), 0.0, abilityDebuffMaxChance);
    }
    /** Legacy getter: returns base chance (level 1). */
    public double getAbilityDebuffChance() { return abilityDebuffBaseChance; }
    public int getAbilityDebuffCooldownTicks() { return abilityDebuffCooldownTicks; }
    public String getAbilityDebuffEffect() { return abilityDebuffEffect; }
    public int getAbilityDebuffDurationTicks() { return abilityDebuffDurationTicks; }
    public int getAbilityDebuffDurationTicks(int abilityLevel) {
        int lvl = Math.max(1, abilityLevel);
        return Math.min(abilityDebuffMaxDurationTicks, Math.max(1, abilityDebuffDurationTicks + abilityDebuffDurationPerLevel * (lvl - 1)));
    }
    public int getAbilityDebuffAmplifier() { return abilityDebuffAmplifier; }
    public boolean isAbilityDebuffSynergyAnyCommonDebuff() { return abilityDebuffSynergyAnyCommonDebuff; }

    public boolean isAbilityCleaveEnabled() { return abilityCleaveEnabled; }
    public int getAbilityCleaveUnlockLevel() { return abilityCleaveUnlockLevel; }
    public double getAbilityCleaveBaseChance() { return abilityCleaveBaseChance; }
    public double getAbilityCleaveChance(int abilityLevel) {
        int lvl = Math.max(1, abilityLevel);
        return clamp(abilityCleaveBaseChance + abilityCleaveChancePerLevel * (lvl - 1), 0.0, abilityCleaveMaxChance);
    }
    public double getAbilityCleaveChance() { return abilityCleaveBaseChance; }
    public int getAbilityCleaveCooldownTicks() { return abilityCleaveCooldownTicks; }
    public double getAbilityCleaveRadius(int abilityLevel) {
        int lvl = Math.max(1, abilityLevel);
        return clamp(abilityCleaveBaseRadius + abilityCleaveRadiusPerLevel * (lvl - 1), 0.1, abilityCleaveMaxRadius);
    }
    public double getAbilityCleaveRadius() { return abilityCleaveBaseRadius; }
    public int getAbilityCleaveMaxTargets() { return abilityCleaveMaxTargets; }
    public double getAbilityCleaveDamageRatio(int abilityLevel) {
        int lvl = Math.max(1, abilityLevel);
        return clamp(abilityCleaveBaseDamageRatio + abilityCleaveDamageRatioPerLevel * (lvl - 1), 0.0, abilityCleaveMaxDamageRatio);
    }
    public double getAbilityCleaveDamageRatio() { return abilityCleaveBaseDamageRatio; }
    public double getAbilityCleaveMaxDamage() { return abilityCleaveMaxDamage; }
    public boolean isAbilityCleaveSpreadDebuff() { return abilityCleaveSpreadDebuff; }
    public boolean isAbilityCleaveDamageRatioUsesBaseDamage() { return abilityCleaveDamageRatioUsesBaseDamage; }
    public double getAbilityCleaveMaxDamagePerMinute() { return abilityCleaveMaxDamagePerMinute; }

    public boolean isAbilityLifestealEnabled() { return abilityLifestealEnabled; }
    public int getAbilityLifestealUnlockLevel() { return abilityLifestealUnlockLevel; }
    public double getAbilityLifestealHealPercentMaxHp(int abilityLevel) {
        int lvl = Math.max(1, abilityLevel);
        return clamp(abilityLifestealBaseHealPercentMaxHp + abilityLifestealHealPercentPerLevel * (lvl - 1),
                0.0, abilityLifestealMaxHealPercentMaxHp);
    }
    public double getAbilityLifestealHealPercentMaxHp() { return abilityLifestealBaseHealPercentMaxHp; }
    public double getAbilityLifestealDebuffedBonusMultiplier() { return abilityLifestealDebuffedBonusMultiplier; }
    public double getAbilityLifestealPerHitHealCap() { return abilityLifestealPerHitHealCap; }
    public double getAbilityLifestealMaxHealPerMinute() { return abilityLifestealMaxHealPerMinute; }
}
