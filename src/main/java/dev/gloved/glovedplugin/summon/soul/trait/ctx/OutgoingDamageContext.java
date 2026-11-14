package dev.gloved.glovedplugin.summon.soul.trait.ctx;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.runtime.SummonManager;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Mutable context for outgoing summon damage modifier hooks. */
public final class OutgoingDamageContext {
    private final GlovedPlugin plugin;
    private final SummonConfig summonConfig;
    private final SummonManager summonManager;
    private final SummonCombatService combatService;
    private final LivingEntity minion;
    private final Player owner;
    private final LivingEntity target;
    private final SoulInstance soul;
    private final SoulStatSnapshot soulSnapshot;
    private final double baseDamage;
    private double damage;
    private boolean frenziedHitAccent;
    private boolean packboundActive;

    public OutgoingDamageContext(GlovedPlugin plugin, SummonConfig summonConfig, SummonManager summonManager,
                                 SummonCombatService combatService, LivingEntity minion, Player owner,
                                 LivingEntity target, SoulInstance soul, SoulStatSnapshot soulSnapshot,
                                 double baseDamage, double currentDamage) {
        this.plugin = plugin;
        this.summonConfig = summonConfig;
        this.summonManager = summonManager;
        this.combatService = combatService;
        this.minion = minion;
        this.owner = owner;
        this.target = target;
        this.soul = soul;
        this.soulSnapshot = soulSnapshot;
        this.baseDamage = baseDamage;
        this.damage = currentDamage;
    }

    public GlovedPlugin plugin() { return plugin; }
    public SummonConfig summonConfig() { return summonConfig; }
    public SummonManager summonManager() { return summonManager; }
    public SummonCombatService combatService() { return combatService; }
    public LivingEntity minion() { return minion; }
    public Player owner() { return owner; }
    public LivingEntity target() { return target; }
    public SoulInstance soul() { return soul; }
    public SoulStatSnapshot soulSnapshot() { return soulSnapshot; }
    public double baseDamage() { return baseDamage; }
    public double damage() { return damage; }

    public void setDamage(double damage) { this.damage = damage; }
    public void multiplyDamage(double factor) { this.damage *= factor; }

    public boolean frenziedHitAccent() { return frenziedHitAccent; }
    public void markFrenziedHitAccent() { this.frenziedHitAccent = true; }

    public boolean packboundActive() { return packboundActive; }
    public void markPackboundActive() { this.packboundActive = true; }
}
