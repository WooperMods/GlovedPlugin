package dev.gloved.glovedplugin.summon.soul.trait.ctx;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;

import java.util.UUID;

/** Mutable context for soul stat/estimate preview hooks (must match runtime parity). */
public final class PreviewContext {
    private final GlovedPlugin plugin;
    private final SummonConfig summonConfig;
    private final UUID viewerId;
    private final SoulInstance soul;
    private final SoulStatSnapshot soulSnapshot;
    private double outgoingDamageMult = 1.0;
    private double critChanceBonus;
    private double critDamageBonus;

    public PreviewContext(GlovedPlugin plugin, SummonConfig summonConfig, UUID viewerId,
                          SoulInstance soul, SoulStatSnapshot soulSnapshot) {
        this.plugin = plugin;
        this.summonConfig = summonConfig;
        this.viewerId = viewerId;
        this.soul = soul;
        this.soulSnapshot = soulSnapshot;
    }

    public GlovedPlugin plugin() { return plugin; }
    public SummonConfig summonConfig() { return summonConfig; }
    public UUID viewerId() { return viewerId; }
    public SoulInstance soul() { return soul; }
    public SoulStatSnapshot soulSnapshot() { return soulSnapshot; }

    public double outgoingDamageMult() { return outgoingDamageMult; }
    public void multiplyOutgoingDamage(double factor) { this.outgoingDamageMult *= factor; }

    public double critChanceBonus() { return critChanceBonus; }
    public void addCritChanceBonus(double v) { this.critChanceBonus += v; }

    public double critDamageBonus() { return critDamageBonus; }
    public void addCritDamageBonus(double v) { this.critDamageBonus += v; }
}
