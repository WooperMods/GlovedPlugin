package dev.gloved.glovedplugin.summon.soul.trait.ctx;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Mutable ability/proc modifier context used by SummonAbilityService hooks. */
public final class AbilityContext {
    private final GlovedPlugin plugin;
    private final SummonConfig summonConfig;
    private final Player owner;
    private final LivingEntity minion;
    private final LivingEntity primaryTarget;
    private final SoulInstance soul;
    private final SoulStatSnapshot soulSnapshot;
    private final String profileName;
    private final long nowTick;

    private double abilityPotencyMult = 1.0;
    private double debuffChanceMult = 1.0;
    private boolean hexingDebuffProc; // for accent/VFX
    private double cleaveChanceMult = 1.0;
    private double cleaveRatioMult = 1.0;
    private boolean splinteringCleaveProc;
    private double lifestealHealMult = 1.0;
    private double lifestealPerHitCapMult = 1.0;
    private boolean vampiricLifesteal;

    public AbilityContext(GlovedPlugin plugin, SummonConfig summonConfig, Player owner, LivingEntity minion,
                          LivingEntity primaryTarget, SoulInstance soul, SoulStatSnapshot soulSnapshot,
                          String profileName, long nowTick) {
        this.plugin = plugin;
        this.summonConfig = summonConfig;
        this.owner = owner;
        this.minion = minion;
        this.primaryTarget = primaryTarget;
        this.soul = soul;
        this.soulSnapshot = soulSnapshot;
        this.profileName = profileName;
        this.nowTick = nowTick;
    }

    public GlovedPlugin plugin() { return plugin; }
    public SummonConfig summonConfig() { return summonConfig; }
    public Player owner() { return owner; }
    public LivingEntity minion() { return minion; }
    public LivingEntity primaryTarget() { return primaryTarget; }
    public SoulInstance soul() { return soul; }
    public SoulStatSnapshot soulSnapshot() { return soulSnapshot; }
    public String profileName() { return profileName; }
    public long nowTick() { return nowTick; }

    public double abilityPotencyMult() { return abilityPotencyMult; }
    public void multiplyAbilityPotency(double factor) { this.abilityPotencyMult *= factor; }

    public double debuffChanceMult() { return debuffChanceMult; }
    public void multiplyDebuffChance(double factor) { this.debuffChanceMult *= factor; }
    public boolean hexingDebuffProc() { return hexingDebuffProc; }
    public void markHexingDebuffProc() { this.hexingDebuffProc = true; }

    public double cleaveChanceMult() { return cleaveChanceMult; }
    public void multiplyCleaveChance(double factor) { this.cleaveChanceMult *= factor; }
    public double cleaveRatioMult() { return cleaveRatioMult; }
    public void multiplyCleaveRatio(double factor) { this.cleaveRatioMult *= factor; }
    public boolean splinteringCleaveProc() { return splinteringCleaveProc; }
    public void markSplinteringCleaveProc() { this.splinteringCleaveProc = true; }

    public double lifestealHealMult() { return lifestealHealMult; }
    public void multiplyLifestealHeal(double factor) { this.lifestealHealMult *= factor; }
    public double lifestealPerHitCapMult() { return lifestealPerHitCapMult; }
    public void multiplyLifestealPerHitCap(double factor) { this.lifestealPerHitCapMult *= factor; }
    public boolean vampiricLifesteal() { return vampiricLifesteal; }
    public void markVampiricLifesteal() { this.vampiricLifesteal = true; }
}
