package dev.gloved.glovedplugin.summon.soul.trait.ctx;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Mutable crit modifier context. */
public final class CritContext {
    private final GlovedPlugin plugin;
    private final SummonConfig summonConfig;
    private final Player owner;
    private final LivingEntity minion;
    private final LivingEntity target;
    private final SoulInstance soul;
    private final SoulStatSnapshot soulSnapshot;
    private final double baseDamage;
    private double critChanceBonus;
    private double critDamageBonus;

    public CritContext(GlovedPlugin plugin, SummonConfig summonConfig, Player owner, LivingEntity minion,
                       LivingEntity target, SoulInstance soul, SoulStatSnapshot soulSnapshot, double baseDamage) {
        this.plugin = plugin;
        this.summonConfig = summonConfig;
        this.owner = owner;
        this.minion = minion;
        this.target = target;
        this.soul = soul;
        this.soulSnapshot = soulSnapshot;
        this.baseDamage = baseDamage;
    }

    public GlovedPlugin plugin() { return plugin; }
    public SummonConfig summonConfig() { return summonConfig; }
    public Player owner() { return owner; }
    public LivingEntity minion() { return minion; }
    public LivingEntity target() { return target; }
    public SoulInstance soul() { return soul; }
    public SoulStatSnapshot soulSnapshot() { return soulSnapshot; }
    public double baseDamage() { return baseDamage; }

    public double critChanceBonus() { return critChanceBonus; }
    public void addCritChanceBonus(double v) { this.critChanceBonus += v; }

    public double critDamageBonus() { return critDamageBonus; }
    public void addCritDamageBonus(double v) { this.critDamageBonus += v; }
}
