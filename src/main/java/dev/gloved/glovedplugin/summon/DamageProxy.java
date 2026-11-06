package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class DamageProxy implements Listener {
    private final SummonConfig config;
    private final SummonInstanceTracker tracker;

    public DamageProxy(SummonConfig config, SummonInstanceTracker tracker) {
        this.config = config;
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!config.isEnabled()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

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
        Player player = GlovedPlugin.getPlugin(GlovedPlugin.class).getServer().getPlayer(ownerId);
        if (player == null || !player.isOnline()) return;

        // Get mob type from PDC
        NamespacedKey mobTypeKey = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_mobtype");
        if (!damagerEntity.getPersistentDataContainer().has(mobTypeKey, PersistentDataType.STRING)) return;
        String mobType = damagerEntity.getPersistentDataContainer().get(mobTypeKey, PersistentDataType.STRING);
        if (mobType == null) return;
        // Find matching profile by mob type
        SummonProfile profile = null;
        for (SummonProfile p : config.getProfiles().values()) {
            if (p.mobTypes.stream().anyMatch(m -> m.equalsIgnoreCase(mobType))) {
                profile = p;
                break;
            }
        }
        if (profile == null) return;

        // Cancel vanilla minion damage to prevent double-hit and avoid bee sting damage
        event.setCancelled(true);
        if (profile.damageProxy) {
            // Legacy proxy: apply plain damage as player
            double damage = event.getDamage();
            victim.damage(damage, player);
            GlovedPlugin.logDebug("[Summon] Damage proxied from minion to player " + player.getName());
        } else {
            GlovedPlugin.logDebug("[Summon] Vanilla minion damage cancelled for " + player.getName());
        }
    }
}
