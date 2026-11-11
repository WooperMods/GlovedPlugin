package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.lootr.util.EliteMobsIntegration;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.UUID;

public class DamageProxy implements Listener {
    private final SummonInstanceTracker tracker;
    private final MinionAI minionAI;

    public DamageProxy(SummonInstanceTracker tracker, MinionAI minionAI) {
        this.tracker = tracker;
        this.minionAI = minionAI;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        try {
            LivingEntity damagerEntity = null;
            if (event.getDamager() instanceof LivingEntity le) {
                damagerEntity = le;
            } else if (event.getDamager() instanceof Projectile proj) {
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
                    GlovedPlugin.logDebug("[Summon] Player-credited minion hit allowed through for " + player.getName()
                            + " damager=" + (damagerEntity != null ? damagerEntity.getType() : "null")
                            + " target=" + (event.getEntity() != null ? event.getEntity().getType() : "null")
                            + " damage=" + event.getDamage());
                }
                return;
            }

            LivingEntity target = event.getEntity() instanceof LivingEntity ? (LivingEntity) event.getEntity() : null;

            // Direct minion melee damage: cancelled here; MinionAI applies the real (scaled) hit.
            if (!(event.getDamager() instanceof Projectile)) {
                event.setCancelled(true);
                if (player != null) {
                    GlovedPlugin.logDebug("[Summon] Vanilla minion damage cancelled for " + player.getName());
                }
                return;
            }

            // Minion projectile / ranged hit: convert to the same owner-credited scaled damage
            // pipeline used by melee so arrows / breeze etc. actually deal damage, but rate-limit
            // by the minion's attack speed (APS) so ranged minions can't hit faster than intended.
            event.setCancelled(true);
            if (minionAI != null && player != null && player.isOnline() && target != null
                    && !CombatTargetRules.isProtected(GlovedPlugin.getPlugin(GlovedPlugin.class), player, target)) {
                long now = Bukkit.getCurrentTick();
                if (minionAI.tryConsumeAttackCooldown(damagerEntity, player, now)) {
                    // Damage base is derived from the soul's effective attack stat inside applyMinionHit.
                    minionAI.applyMinionHit(damagerEntity, player, target, 0.0, now, null);
                    GlovedPlugin.logDebug("[Summon] Converted ranged minion hit for " + player.getName()
                            + " target=" + target.getName() + " (cooldown consumed)");
                } else {
                    GlovedPlugin.logDebug("[Summon] Ranged minion hit rate-limited for " + player.getName()
                            + " damager=" + damagerEntity.getType() + " target=" + target.getName());
                }
            } else if (player != null) {
                GlovedPlugin.logDebug("[Summon] Converted ranged minion hit ignored for " + player.getName()
                        + " target=" + (target != null ? target.getName() : "null"));
            }
        } catch (Throwable t) {
            GlovedPlugin.getPlugin(GlovedPlugin.class).getLogger().log(java.util.logging.Level.WARNING,
                    "[Summon] Error in DamageProxy (suppressed to avoid kick)", t);
        }
    }
}
