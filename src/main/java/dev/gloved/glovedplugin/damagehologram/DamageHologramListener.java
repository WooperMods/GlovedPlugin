package dev.gloved.glovedplugin.damagehologram;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.damagehologram.integration.AuraSkillsCompat;
import dev.gloved.glovedplugin.damagehologram.integration.EliteMobsExclusion;
import dev.gloved.glovedplugin.damagehologram.provider.HologramProvider;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.text.NumberFormat;

public class DamageHologramListener implements Listener {

    private final DamageHologramModule module;

    public DamageHologramListener(DamageHologramModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        try {
            DamageHologramConfig cfg = module.getConfig();
            HologramProvider provider = module.getProvider();
            if (cfg == null || provider == null || !provider.isAvailable()) return;

            if (event.isCancelled()) return;
            if (!(event.getEntity() instanceof LivingEntity victim)) return;
            if (cfg.isWorldDisabled(victim.getWorld().getName())) return;

            Player player;
            if (event.getDamager() instanceof Player p) {
                player = p;
            } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
                player = shooter;
                if (player.equals(victim)) return; // no self damage hologram
                if (event.getFinalDamage() <= 0) return;
            } else {
                return;
            }

            // Core divergence: skip EliteMobs mobs
            if (cfg.excludeEliteMobs && EliteMobsExclusion.isEliteMob(victim)) {
                return;
            }

            double finalDamage = event.getFinalDamage();
            if (finalDamage < cfg.minDamage) return;

            boolean critical = AuraSkillsCompat.isCritical(player);
            double hpScaling = 1.0;
            if (cfg.scaling) {
                hpScaling = AuraSkillsCompat.getHpActionBarScaling();
            }

            NumberFormat nf = module.getNumberFormat();
            String text = DamageHologramFormatter.format(finalDamage, critical, cfg.scaling, hpScaling,
                    cfg.decimalDisplayWhenLessThan, nf, cfg.defaultColor, cfg.criticalColors);
            Location loc = DamageHologramLocation.compute(victim, cfg);
            provider.createHologram(loc, text, player);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[DamageHologram] Listener error: " + t);
        }
    }
}
