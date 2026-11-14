package dev.gloved.glovedplugin.summon.soul.trait.ctx;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge;
import dev.gloved.glovedplugin.summon.runtime.SummonManager;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Post-primary-hit context for trait proc hooks. */
public final class HitContext {
    private final GlovedPlugin plugin;
    private final SummonConfig summonConfig;
    private final SummonManager summonManager;
    private final SummonCombatService combatService;
    private final SummonProgressionBridge progression;
    private final LivingEntity minion;
    private final Player owner;
    private final LivingEntity target;
    private final SoulInstance soul;
    private final SoulStatSnapshot soulSnapshot;
    private final String profileName;
    private final double finalDamage;
    private final double baseDamage;
    private final boolean crit;
    private final boolean targetDebuffedBefore;
    private final long nowTick;

    public HitContext(GlovedPlugin plugin, SummonConfig summonConfig, SummonManager summonManager,
                      SummonCombatService combatService, SummonProgressionBridge progression,
                      LivingEntity minion, Player owner, LivingEntity target, SoulInstance soul,
                      SoulStatSnapshot soulSnapshot, String profileName, double finalDamage, double baseDamage,
                      boolean crit, boolean targetDebuffedBefore, long nowTick) {
        this.plugin = plugin;
        this.summonConfig = summonConfig;
        this.summonManager = summonManager;
        this.combatService = combatService;
        this.progression = progression;
        this.minion = minion;
        this.owner = owner;
        this.target = target;
        this.soul = soul;
        this.soulSnapshot = soulSnapshot;
        this.profileName = profileName;
        this.finalDamage = finalDamage;
        this.baseDamage = baseDamage;
        this.crit = crit;
        this.targetDebuffedBefore = targetDebuffedBefore;
        this.nowTick = nowTick;
    }

    public GlovedPlugin plugin() { return plugin; }
    public SummonConfig summonConfig() { return summonConfig; }
    public SummonManager summonManager() { return summonManager; }
    public SummonCombatService combatService() { return combatService; }
    public SummonProgressionBridge progression() { return progression; }
    public LivingEntity minion() { return minion; }
    public Player owner() { return owner; }
    public LivingEntity target() { return target; }
    public SoulInstance soul() { return soul; }
    public SoulStatSnapshot soulSnapshot() { return soulSnapshot; }
    public String profileName() { return profileName; }
    public double finalDamage() { return finalDamage; }
    public double baseDamage() { return baseDamage; }
    public boolean crit() { return crit; }
    public boolean targetDebuffedBefore() { return targetDebuffedBefore; }
    public long nowTick() { return nowTick; }
}
