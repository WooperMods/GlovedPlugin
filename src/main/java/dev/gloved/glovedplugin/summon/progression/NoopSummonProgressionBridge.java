package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class NoopSummonProgressionBridge implements SummonProgressionBridge {
    @Override public void onEnable() {}
    @Override public void onReload(SummonConfig config) {}
    @Override public void onDisable() {}
    @Override public void onMinionHit(Player owner, LivingEntity minion, LivingEntity target, String profileName, double finalDamage) {}
    @Override public void recordKillCredit(Player owner, LivingEntity minion, LivingEntity victim, String profileName, long nowTick) {}
    @Override public void onEntityDeath(LivingEntity victim, long nowTick) {}
    @Override public int getSummoningLevel(UUID ownerId) { return 0; }
    @Override public int getAbilityLevel(UUID ownerId, String abilityId) { return 0; }
    @Override public OwnerCombatStats resolveOwnerCombatStats(UUID ownerId) { return OwnerCombatStats.unavailable(); }
}
