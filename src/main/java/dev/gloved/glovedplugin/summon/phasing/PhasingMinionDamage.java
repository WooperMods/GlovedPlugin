package dev.gloved.glovedplugin.summon.phasing;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.lootr.util.EliteMobsIntegration;
import dev.gloved.glovedplugin.summon.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.SummonProfile;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/** Owner-credited damage handling for vanilla-AI phasing minions (e.g. Vex). */
public final class PhasingMinionDamage {
    private PhasingMinionDamage() {}

    public static void applyOwnerCreditedScaledDamage(Player owner, LivingEntity minion, LivingEntity target,
                                                      SummonInstanceTracker tracker, GlovedPlugin plugin) {
        try {
            GlovedPlugin p = plugin != null ? plugin : GlovedPlugin.getPlugin(GlovedPlugin.class);
            if (CombatTargetRules.isProtected(p, owner, target)) {
                GlovedPlugin.logDebug("[Summon] Phasing/vanilla-mode minion hit skipped protected target " + (target != null ? target.getName() : "null"));
                return;
            }
            int ownerLevel = EliteMobsIntegration.getCombatLevel(owner);
            boolean isElite = isEliteMob(target);
            double damage = isElite ? 7.0 + ownerLevel * 0.4 : 7.0;
            DamageSource src = DamageSource.builder(DamageType.GENERIC)
                    .withDirectEntity(minion)
                    .withCausingEntity(owner)
                    .build();
            target.damage(damage, src);
            try {
                dev.gloved.glovedplugin.summon.MinionAI.applyKnockback(minion, owner, target);
            } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[Summon] Phasing/vanilla-mode minion hit credited to " + owner.getName()
                    + " damage=" + damage + " elite=" + isElite + " level=" + ownerLevel);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Failed to apply owner-credited damage for phasing minion: " + t);
        }
    }

    public static boolean isEliteMob(LivingEntity entity) {
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null) return false;
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> eliteClass = Class.forName("com.magmaguy.elitemobs.mobconstructor.EliteEntity", true, cl);
            return eliteClass.isInstance(entity);
        } catch (Throwable t) {
            return false;
        }
    }
}
