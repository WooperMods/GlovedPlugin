package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * v1 progression hook: rewards/progression only (XP). No minion stat scaling.
 * Implementations must be safe when AuraSkills is absent.
 */
public interface SummonProgressionBridge {
    void onEnable();
    void onReload(SummonConfig config);
    void onDisable();

    void onMinionHit(Player owner, LivingEntity minion, LivingEntity target, String profileName, double finalDamage);

    void recordKillCredit(Player owner, LivingEntity minion, LivingEntity victim, String profileName, long nowTick);

    void onEntityDeath(LivingEntity victim, long nowTick);
}
