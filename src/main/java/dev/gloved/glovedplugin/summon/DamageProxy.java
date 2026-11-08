package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.summon.phasing.PhasingMinionDamage;
import dev.gloved.glovedplugin.summon.phasing.PhasingMob;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;

public class DamageProxy implements Listener {
    private final SummonInstanceTracker tracker;

    public DamageProxy(SummonInstanceTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        try {
            LivingEntity damagerEntity = null;
            if (event.getDamager() instanceof LivingEntity le) {
                damagerEntity = le;
            } else if (event.getDamager() instanceof org.bukkit.entity.Projectile proj) {
                if (proj.getShooter() instanceof LivingEntity shooter) {
                    damagerEntity = shooter;
                }
            }
            if (damagerEntity == null) return;

            UUID ownerId = tracker.getOwner(damagerEntity.getUniqueId());
            if (ownerId == null) return;
            Player player = Bukkit.getPlayer(ownerId);

            // MinionAI already applies level-scaled damage with the owner as causing entity.
            // Let those hits through instead of cancelling them.
            DamageSource source = event.getDamageSource();
            if (source != null && source.getCausingEntity() instanceof Player causing
                    && causing.getUniqueId().equals(ownerId)) {
                if (player != null) {
                    GlovedPlugin.logDebug("[Summon] Player-credited minion hit allowed through for " + player.getName());
                }
                return;
            }

            // Phasing/vex minions run in vanilla AI mode (MinionAI doesn't apply their hits).
            // Convert their vanilla attacks to owner-credited, level-scaled damage.
            if (player != null && event.getEntity() instanceof LivingEntity target
                    && PhasingMob.usesVanillaAI(damagerEntity)) {
                GlovedPlugin plugin = GlovedPlugin.getPlugin(GlovedPlugin.class);
                event.setCancelled(true);
                if (!CombatTargetRules.isProtected(plugin, player, target)) {
                    PhasingMinionDamage.applyOwnerCreditedScaledDamage(player, damagerEntity, target, tracker, plugin);
                } else {
                    GlovedPlugin.logDebug("[Summon] Phasing/vanilla-mode minion hit cancelled for protected target " + target.getName());
                }
                return;
            }

            // Cancel the minion's own vanilla melee damage; MinionAI applies the real hit.
            event.setCancelled(true);
            if (player != null) {
                GlovedPlugin.logDebug("[Summon] Vanilla minion damage cancelled for " + player.getName());
            }
        } catch (Throwable t) {
            GlovedPlugin.getPlugin(GlovedPlugin.class).getLogger().log(java.util.logging.Level.WARNING,
                    "[Summon] Error in DamageProxy (suppressed to avoid kick)", t);
        }
    }


}
