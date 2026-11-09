package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.function.Supplier;

public class SummonKillAttributionListener implements Listener {
    private final Supplier<SummonProgressionBridge> bridgeSupplier;
    private final Supplier<SummonConfig> configSupplier;

    public SummonKillAttributionListener(Supplier<SummonProgressionBridge> bridgeSupplier, Supplier<SummonConfig> configSupplier) {
        this.bridgeSupplier = bridgeSupplier;
        this.configSupplier = configSupplier;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        try {
            SummonConfig cfg = configSupplier.get();
            if (cfg == null || !cfg.isProgressionEnabled() || !cfg.isProgressionKillXpEnabled()) return;
            LivingEntity victim = event.getEntity();
            SummonProgressionBridge bridge = bridgeSupplier.get();
            if (bridge == null) return;
            long nowTick;
            try { nowTick = Bukkit.getCurrentTick(); } catch (Throwable t) { nowTick = System.currentTimeMillis() / 50L; }
            bridge.onEntityDeath(victim, nowTick);
        } catch (Throwable ignored) {}
    }
}
