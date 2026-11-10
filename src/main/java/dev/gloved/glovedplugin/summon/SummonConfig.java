package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.DynamicSoulTemplate;
import dev.gloved.glovedplugin.summon.soul.EntityCategory;
import dev.gloved.glovedplugin.summon.soul.SoulConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

/**
 * Summon/soul configuration. Fully-dynamic soul system: no static profile catalog is required;
 * summon templates are resolved from EntityType + category/global defaults via SoulConfig.
 */
public class SummonConfig {
    private final JavaPlugin plugin;
    private boolean enabled;
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
    private boolean progressionCaptureXpEnabled = true;
    private double progressionCaptureXpAmount = 2.0;
    private double progressionCaptureXpMaxPerMinute = 60.0;
    private boolean progressionCastXpEnabled = true;
    private double progressionCastXpAmount = 1.0;
    private double progressionCastXpMaxPerMinute = 40.0;
    private boolean progressionGenerateAuraSkillsContentFiles = true;

    // AuraSkills mana cost per summon trigger (batch).
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

    // Minion crit
    private boolean critEnabled = true;
    private String critSource = "auraskills";
    private double critMaxChance = 0.40;
    private double critMinDamageMultiplier = 1.0;
    private double critMaxDamageMultiplier = 3.0;
    private double critLegacyBaseChance = 0.0;
    private double critLegacyPerLevel = 0.008;
    private double critLegacyDamageMultiplier = 1.5;

    // Minion damage owner strength
    private boolean strengthDamageEnabled = true;
    private double strengthDamagePerAttackDamageTrait = 0.20;
    private double strengthDamageMaxBonus = 12.0;

    // Minion damage reduction from owner toughness
    private boolean toughnessDamageReductionEnabled = true;
    private double toughnessDamageReductionPerPoint = 0.02;
    private double toughnessDamageReductionMaxBonus = 0.50;

    // Soul Traps
    private boolean soulTrapsEnabled = true;
    private double soulTrapsEliteResistance = 0.25;
    private double soulTrapsBossResistance = 1.0;
    private double soulTrapsMinHpToTrap = 5.0;
    private long soulTrapsTrapExhaustionTicks = 12000L; // 10 minutes

    // Abilities
    private boolean abilitiesEnabled = true;
    private boolean abilitiesUseAuraAbilityLevels = true;

    private boolean abilityDebuffEnabled = true;
    private int abilityDebuffUnlockLevel = 2;
    private double abilityDebuffBaseChance = 0.15;
    private double abilityDebuffChancePerLevel = 0.015;
    private double abilityDebuffMaxChance = 0.45;
    private int abilityDebuffCooldownTicks = 60;
    private String abilityDebuffEffect = "SLOWNESS";
    private int abilityDebuffDurationTicks = 60;
    private int abilityDebuffDurationPerLevel = 4;
    private int abilityDebuffMaxDurationTicks = 120;
    private int abilityDebuffAmplifier = 0;
    private boolean abilityDebuffSynergyAnyCommonDebuff = true;

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

    private boolean abilityLifestealEnabled = true;
    private int abilityLifestealUnlockLevel = 6;
    private double abilityLifestealBaseHealPercentMaxHp = 0.0075;
    private double abilityLifestealHealPercentPerLevel = 0.0010;
    private double abilityLifestealMaxHealPercentMaxHp = 0.03;
    private double abilityLifestealDebuffedBonusMultiplier = 1.25;
    private double abilityLifestealPerHitHealCap = 2.5;
    private double abilityLifestealMaxHealPerMinute = 40.0;

    private boolean warnedLegacyCrit;

    // Dynamic soul config
    private SoulConfig soulConfig = new SoulConfig();

    private SummonConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static SummonConfig load(JavaPlugin plugin) {
        SummonConfig cfg = new SummonConfig(plugin);
        cfg.reload();
        return cfg;
    }

    /** Re-reads summon.yml into this instance (listeners/managers keep the same reference). */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "summon.yml");
        if (!file.exists()) {
            plugin.saveResource("summon.yml", false);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        backfill(yaml);
        // Soul config backfill + load
        SoulConfig.backfill(yaml);
        try { yaml.save(file); } catch (Exception ignored) {}

        enabled = yaml.getBoolean("enabled", true);
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
        progressionHitXpAmount = safeNonNegative(yaml.getDouble("progression.xp.minion-hit.amount", 2.0));
        progressionHitOwnerCooldownTicks = Math.max(0, yaml.getInt("progression.xp.minion-hit.owner-cooldown-ticks", 10));
        progressionHitTargetCooldownTicks = Math.max(0, yaml.getInt("progression.xp.minion-hit.per-target-cooldown-ticks", 40));
        progressionHitMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.minion-hit.max-per-minute", 300.0));
        progressionKillXpEnabled = yaml.getBoolean("progression.xp.minion-kill.enabled", true);
        progressionKillXpAmount = safeNonNegative(yaml.getDouble("progression.xp.minion-kill.amount", 20.0));
        progressionKillAttributionWindowTicks = Math.max(1, yaml.getInt("progression.xp.minion-kill.attribution-window-ticks", 200));
        progressionKillMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.minion-kill.max-per-minute", 200.0));
        progressionCaptureXpEnabled = yaml.getBoolean("progression.xp.soul-capture.enabled", true);
        progressionCaptureXpAmount = safeNonNegative(yaml.getDouble("progression.xp.soul-capture.amount", 5.0));
        progressionCaptureXpMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.soul-capture.max-per-minute", 150.0));
        progressionCastXpEnabled = yaml.getBoolean("progression.xp.soul-cast.enabled", true);
        progressionCastXpAmount = safeNonNegative(yaml.getDouble("progression.xp.soul-cast.amount", 3.0));
        progressionCastXpMaxPerMinute = safeNonNegative(yaml.getDouble("progression.xp.soul-cast.max-per-minute", 100.0));

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
        if (!yaml.isSet("progression.crit.max-chance")) critMaxChance = 0.40;
        critMinDamageMultiplier = Math.max(1.0, yaml.getDouble("progression.crit.min-damage-multiplier", 1.0));
        critMaxDamageMultiplier = Math.max(critMinDamageMultiplier, yaml.getDouble("progression.crit.max-damage-multiplier", 3.0));
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
        toughnessDamageReductionEnabled = yaml.getBoolean("progression.minion-damage.owner-toughness.enabled", true);
        toughnessDamageReductionPerPoint = clamp(yaml.getDouble("progression.minion-damage.owner-toughness.per-point", 0.02), 0.0, 1.0);
        toughnessDamageReductionMaxBonus = clamp(yaml.getDouble("progression.minion-damage.owner-toughness.max-bonus", 0.50), 0.0, 1.0);

        // Soul Traps
        soulTrapsEnabled = yaml.getBoolean("soulTraps.enabled", true);
        soulTrapsEliteResistance = clamp(yaml.getDouble("soulTraps.eliteResistance", 0.25), 0.0, 1.0);
        soulTrapsBossResistance = clamp(yaml.getDouble("soulTraps.bossResistance", 1.0), 0.0, 1.0);
        soulTrapsMinHpToTrap = safeNonNegative(yaml.getDouble("soulTraps.minHpToTrap", 5.0));
        soulTrapsTrapExhaustionTicks = Math.max(0, yaml.getInt("soulTraps.trapExhaustionTicks", 12000));

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

        this.soulConfig = SoulConfig.load(yaml);

        if (yaml.isConfigurationSection("profiles")) {
            GlovedPlugin.logInfo("[Summon] Fully-dynamic soul system active: static 'profiles:' catalog is ignored (entity-type souls are resolved dynamically).");
        }
    }

    private static void backfill(FileConfiguration cfg) {
        if (!cfg.isSet("enabled")) cfg.set("enabled", true);
        setIfMissing(cfg, "clumped-nametags.enabled", true);
        setIfMissing(cfg, "clumped-nametags.min-count", 4);
        setIfMissing(cfg, "clumped-nametags.radius", 2.0);
        setIfMissing(cfg, "clumped-nametags.update-ticks", 10);
        setIfMissing(cfg, "clumped-nametags.format", "{nametag} x{count}");
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
        setIfMissing(cfg, "progression.xp.soul-capture.enabled", true);
        setIfMissing(cfg, "progression.xp.soul-capture.amount", 2.0);
        setIfMissing(cfg, "progression.xp.soul-capture.max-per-minute", 60.0);
        setIfMissing(cfg, "progression.xp.soul-cast.enabled", true);
        setIfMissing(cfg, "progression.xp.soul-cast.amount", 1.0);
        setIfMissing(cfg, "progression.xp.soul-cast.max-per-minute", 40.0);
        // Scaling
        setIfMissing(cfg, "progression.scaling.enabled", true);
        setIfMissing(cfg, "progression.scaling.chance.per-level", 0.005);
        setIfMissing(cfg, "progression.scaling.chance.max", 0.95);
        setIfMissing(cfg, "progression.scaling.minions-per-hit.per-level", 0.10);
        setIfMissing(cfg, "progression.scaling.minions-per-hit.max", 12);
        setIfMissing(cfg, "progression.scaling.max-active.per-level", 0.25);
        setIfMissing(cfg, "progression.scaling.max-active.max", 30);
        // Crit
        setIfMissing(cfg, "progression.crit.enabled", true);
        setIfMissing(cfg, "progression.crit.source", "auraskills");
        setIfMissing(cfg, "progression.crit.max-chance", 0.40);
        setIfMissing(cfg, "progression.crit.min-damage-multiplier", 1.0);
        setIfMissing(cfg, "progression.crit.max-damage-multiplier", 3.0);
        // Strength
        setIfMissing(cfg, "progression.minion-damage.owner-strength.enabled", true);
        setIfMissing(cfg, "progression.minion-damage.owner-strength.per-attack-damage-trait", 0.20);
        setIfMissing(cfg, "progression.minion-damage.owner-strength.max-bonus", 12.0);
        setIfMissing(cfg, "progression.minion-damage.owner-toughness.enabled", true);
        setIfMissing(cfg, "progression.minion-damage.owner-toughness.per-point", 0.02);
        setIfMissing(cfg, "progression.minion-damage.owner-toughness.max-bonus", 0.50);

        // Soul Traps
        setIfMissing(cfg, "soulTraps.enabled", true);
        setIfMissing(cfg, "soulTraps.eliteResistance", 0.25);
        setIfMissing(cfg, "soulTraps.bossResistance", 1.0);
        setIfMissing(cfg, "soulTraps.minHpToTrap", 5.0);
        setIfMissing(cfg, "soulTraps.trapExhaustionTicks", 12000);
        // Abilities
        setIfMissing(cfg, "progression.abilities.enabled", true);
        setIfMissing(cfg, "progression.abilities.use-auraskills-ability-levels", true);
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
    }

    private static void setIfMissing(FileConfiguration cfg, String path, Object value) {
        if (cfg != null && !cfg.isSet(path)) cfg.set(path, value);
    }

    private static double safeNonNegative(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return 0;
        return v;
    }

    private static double clamp(double v, double min, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    // --- Dynamic soul/template resolution (replaces static profiles) ---

    /** Resolve a runtime summon template for an entity-type family key (e.g. "ZOMBIE"), or null if disallowed. */
    public SummonProfile getProfile(String familyKey) {
        if (familyKey == null) return null;
        DynamicSoulTemplate t = soulConfig.resolveTemplate(familyKey);
        if (t == null) return null;
        return toProfile(t);
    }

    /** Resolve a runtime summon template for an entity type, or null if disallowed. */
    public SummonProfile getProfile(EntityType type) {
        if (type == null) return null;
        DynamicSoulTemplate t = soulConfig.resolveTemplate(type);
        if (t == null) return null;
        return toProfile(t);
    }

    /** No static profile catalog in dynamic mode; returns empty map for compatibility. */
    public Map<String, SummonProfile> getProfiles() {
        return Collections.emptyMap();
    }

    public SoulConfig getSoulConfig() { return soulConfig; }

    private static SummonProfile toProfile(DynamicSoulTemplate t) {
        return new SummonProfile(t.name, t.entityType != null ? t.entityType.name() : t.name,
                1.0, t.minionsPerHit, t.maxActive, t.nametagColor != null ? t.nametagColor.name() : null, "", t.category);
    }

    // --- Existing getters (preserved) ---

    public boolean isEnabled() { return enabled; }
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
    public boolean isProgressionCaptureXpEnabled() { return progressionCaptureXpEnabled; }
    public double getProgressionCaptureXpAmount() { return progressionCaptureXpAmount; }
    public double getProgressionCaptureXpMaxPerMinute() { return progressionCaptureXpMaxPerMinute; }
    public boolean isProgressionCastXpEnabled() { return progressionCastXpEnabled; }
    public double getProgressionCastXpAmount() { return progressionCastXpAmount; }
    public double getProgressionCastXpMaxPerMinute() { return progressionCastXpMaxPerMinute; }
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
    @Deprecated public double getCritBaseChance() { return critLegacyBaseChance; }
    @Deprecated public double getCritPerLevel() { return critLegacyPerLevel; }
    @Deprecated public double getCritDamageMultiplier() { return critLegacyDamageMultiplier; }
    public double getCritLegacyBaseChance() { return critLegacyBaseChance; }
    public double getCritLegacyPerLevel() { return critLegacyPerLevel; }
    public double getCritLegacyDamageMultiplier() { return critLegacyDamageMultiplier; }

    public boolean isStrengthDamageEnabled() { return strengthDamageEnabled; }
    public double getStrengthDamagePerAttackDamageTrait() { return strengthDamagePerAttackDamageTrait; }
    public double getStrengthDamageMaxBonus() { return strengthDamageMaxBonus; }

    public boolean isToughnessDamageReductionEnabled() { return toughnessDamageReductionEnabled; }
    public double getToughnessDamageReductionPerPoint() { return toughnessDamageReductionPerPoint; }
    public double getToughnessDamageReductionMaxBonus() { return toughnessDamageReductionMaxBonus; }

    // Soul Traps
    public boolean isSoulTrapsEnabled() { return soulTrapsEnabled; }
    public double getSoulTrapsEliteResistance() { return soulTrapsEliteResistance; }
    public double getSoulTrapsBossResistance() { return soulTrapsBossResistance; }
    public double getSoulTrapsMinHpToTrap() { return soulTrapsMinHpToTrap; }
    public long getSoulTrapsTrapExhaustionTicks() { return soulTrapsTrapExhaustionTicks; }

    public boolean isAbilitiesEnabled() { return abilitiesEnabled; }
    public boolean isAbilitiesUseAuraAbilityLevels() { return abilitiesUseAuraAbilityLevels; }

    public boolean isAbilityDebuffEnabled() { return abilityDebuffEnabled; }
    public int getAbilityDebuffUnlockLevel() { return abilityDebuffUnlockLevel; }
    public double getAbilityDebuffBaseChance() { return abilityDebuffBaseChance; }
    public double getAbilityDebuffChancePerLevel() { return abilityDebuffChancePerLevel; }
    public double getAbilityDebuffMaxChance() { return abilityDebuffMaxChance; }
    public double getAbilityDebuffChance(int abilityLevel) {
        int lvl = Math.max(1, abilityLevel);
        return clamp(abilityDebuffBaseChance + abilityDebuffChancePerLevel * (lvl - 1), 0.0, abilityDebuffMaxChance);
    }
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
