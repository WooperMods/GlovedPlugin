package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Progression hook: v1 XP grants + v2 scaling/ability unlock queries.
 * Implementations must be safe when AuraSkills is absent.
 */
public interface SummonProgressionBridge {
    void onEnable();
    void onReload(SummonConfig config);
    void onDisable();

    void onMinionHit(Player owner, LivingEntity minion, LivingEntity target, String profileName, double finalDamage);

    void recordKillCredit(Player owner, LivingEntity minion, LivingEntity victim, String profileName, long nowTick);

    void onEntityDeath(LivingEntity victim, long nowTick);

    /** Summoning level for progression scaling/ability unlocks; 0 when unavailable. */
    int getSummoningLevel(UUID ownerId);

    /** AuraSkills ability level for a namespaced ability id (e.g. gloved/summoning_debuff); 0 when unavailable. */
    default int getAbilityLevel(UUID ownerId, String abilityId) { return 0; }

    /** Owner combat stats snapshot for minion crit/strength (AuraSkills-authoritative when available). */
    default OwnerCombatStats resolveOwnerCombatStats(UUID ownerId) { return OwnerCombatStats.unavailable(); }

    default void onPlayerQuit(UUID ownerId) {}
}
