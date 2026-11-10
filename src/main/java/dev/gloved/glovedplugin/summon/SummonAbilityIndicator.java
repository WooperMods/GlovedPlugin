package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.SoulService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;

/**
 * Action-bar indicator for dynamic soul summoning: selected soul + cast readiness (cooldown/mana/cap hints).
 */
public class SummonAbilityIndicator implements Listener {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private BukkitTask task;

    public SummonAbilityIndicator(GlovedPlugin plugin, SummonConfig config) {
        this.plugin = plugin;
        this.config = config;
        start();
    }

    private void start() {
        stop();
        try {
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                try {
                    SoulService souls = plugin.getSoulService();
                    if (souls == null) return;
                    SummonManager mgr = plugin.getSummonManager();
                    dev.gloved.glovedplugin.summon.SummonInstanceTracker tracker = plugin.getSummonTracker();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p == null || !p.isOnline()) continue;
                        // Only show while holding the Soul Focus
                        if (!dev.gloved.glovedplugin.summon.soul.SoulItem.isSoulItem(p.getInventory().getItemInMainHand())
                                && !dev.gloved.glovedplugin.summon.soul.SoulItem.isSoulItem(p.getInventory().getItemInOffHand())) {
                            continue;
                        }
                        SoulInstance sel = souls.getSelectedSoul(p.getUniqueId());
                        if (sel == null) continue;
                        SoulConfig scfg = souls.soulConfig();
                        String base = "§dSoul§7: §f" + dev.gloved.glovedplugin.summon.soul.SoulService.describe(sel);
                        String status = "§aReady";
                        try {
                            if (mgr != null) {
                                long rem = mgr.getSoulCastCooldownRemainingMs(p.getUniqueId());
                                if (rem > 0) {
                                    status = "§cCD " + String.format(Locale.ROOT, "%.1fs", rem / 1000.0);
                                } else if (scfg != null && tracker != null) {
                                    int level = souls.getSummoningLevel(p.getUniqueId());
                                    int globalMax = scfg.computeGlobalMaxActive(level);
                                    int globalCount = tracker.getActiveMinions(p.getUniqueId()).size();
                                    if (globalCount >= globalMax) {
                                        status = "§eCap " + globalCount + "/" + globalMax;
                                    } else {
                                        double manaCost = souls.computeCastManaCost(sel, scfg);
                                        status = "§aReady §7(-" + String.format(Locale.ROOT, "%.1f", manaCost) + " mana)";
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                        try {
                            p.sendActionBar(Component.text(base + " §8| " + status));
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }, 40L, 40L);
        } catch (Throwable ignored) {}
    }

    public void stop() {
        try { if (task != null) task.cancel(); } catch (Throwable ignored) {}
        task = null;
    }
}
