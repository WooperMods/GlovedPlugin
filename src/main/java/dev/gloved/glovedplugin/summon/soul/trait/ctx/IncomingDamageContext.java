package dev.gloved.glovedplugin.summon.soul.trait.ctx;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.UUID;

/** Mutable context for incoming damage mitigation hooks on summoned minions. */
public final class IncomingDamageContext {
    private final GlovedPlugin plugin;
    private final LivingEntity victimMinion;
    private final Entity attacker;
    private final UUID ownerId;
    private final SoulInstance soul;
    private final SoulStatSnapshot soulSnapshot;
    private final double baseDamage;
    private double addedReduction;
    private boolean damageCancelled;

    public IncomingDamageContext(GlovedPlugin plugin, LivingEntity victimMinion, Entity attacker, UUID ownerId,
                                 SoulInstance soul, SoulStatSnapshot soulSnapshot, double baseDamage) {
        this.plugin = plugin;
        this.victimMinion = victimMinion;
        this.attacker = attacker;
        this.ownerId = ownerId;
        this.soul = soul;
        this.soulSnapshot = soulSnapshot;
        this.baseDamage = baseDamage;
    }

    public GlovedPlugin plugin() { return plugin; }
    public LivingEntity victimMinion() { return victimMinion; }
    public Entity attacker() { return attacker; }
    public UUID ownerId() { return ownerId; }
    public SoulInstance soul() { return soul; }
    public SoulStatSnapshot soulSnapshot() { return soulSnapshot; }
    public double baseDamage() { return baseDamage; }
    public double incomingDamage() { return baseDamage; }

    public double addedReduction() { return addedReduction; }
    public void addReduction(double amount) { this.addedReduction += amount; }

    /** When true, the summon should take no damage from this hit (used by reflection-style procs). */
    public boolean isDamageCancelled() { return damageCancelled; }
    public void cancelDamage() { this.damageCancelled = true; }
}
