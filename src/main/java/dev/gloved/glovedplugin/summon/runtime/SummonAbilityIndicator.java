package dev.gloved.glovedplugin.summon.runtime;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.item.SoulItem;

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
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p == null || !p.isOnline()) continue;
                        // Only show while holding the Soul Focus
                        if (!SoulItem.isSoulItem(p.getInventory().getItemInMainHand())
                                && !SoulItem.isSoulItem(p.getInventory().getItemInOffHand())) {
                            continue;
                        }
                        int selectedTeam = 1;
                        try { selectedTeam = souls.getSelectedTeam(p.getUniqueId()); } catch (Throwable ignored) {}
                        int activeTeam = -1;
                        boolean hasActive = false;
                        try {
                            if (mgr != null) {
                                activeTeam = mgr.getActiveTeamIndex(p.getUniqueId());
                                hasActive = mgr.hasActiveTeamSummons(p.getUniqueId());
                            }
                        } catch (Throwable ignored) {}
                        String status;
                        if (hasActive && activeTeam == selectedTeam) {
                            status = "§eRecall";
                        } else if (hasActive) {
                            status = "§bHotswap";
                        } else {
                            status = "§aReady";
                        }
                        SoulConfig scfg = souls.soulConfig();
                        try {
                            double manaCost = scfg != null ? scfg.getCastActivateManaCost() : 0.0;
                            boolean manaOn = scfg != null && scfg.isCastManaEnabled() && manaCost > 0.0
                                    && config != null && config.isManaEnabled();
                            if (manaOn && !(hasActive && activeTeam == selectedTeam)) {
                                status += " §7(-" + String.format(Locale.ROOT, "%.1f", manaCost) + " mana)";
                            }
                        } catch (Throwable ignored) {}
                        String base = "§dSoul Groups§7: §fSelected " + selectedTeam;
                        if (hasActive) base += " §7| Active " + (activeTeam > 0 ? activeTeam : "?");
                        try {
                            p.sendActionBar(Component.text(base + " §8| " + status));
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }, 2L, 2L);
        } catch (Throwable ignored) {}
    }

    public void stop() {
        try { if (task != null) task.cancel(); } catch (Throwable ignored) {}
        task = null;
    }
}
