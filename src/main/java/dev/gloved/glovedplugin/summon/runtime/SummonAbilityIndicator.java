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
                        String base = "§dSoul Group§7: §f" + selectedTeam + " Selected";
                        if (hasActive) {
                            String activeInfo = formatActiveTeamInfo(p, activeTeam);
                            if (activeTeam == selectedTeam) {
                                base += " §7| " + activeInfo;
                            } else {
                                base += " §7| " + (activeTeam > 0 ? "Group " + activeTeam + " " : "") + activeInfo;
                            }
                        }
                        try {
                            p.sendActionBar(Component.text(base + " §8| " + status));
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }, 2L, 2L);
        } catch (Throwable ignored) {}
    }

    private String formatActiveTeamInfo(Player player, int activeTeam) {
        int slotsPerTeam = 4;
        try {
            SoulService souls = plugin.getSoulService();
            var cfg = souls != null ? souls.soulConfig() : null;
            if (cfg != null) slotsPerTeam = Math.max(1, Math.min(4, cfg.getTeamSlotsPerTeam()));
        } catch (Throwable ignored) {}

        int activeCount = 0;
        try {
            var tracker = plugin.getSummonTracker();
            if (tracker != null && player != null && activeTeam > 0) {
                var teamKey = new org.bukkit.NamespacedKey(plugin, "summon_team_index");
                for (org.bukkit.entity.LivingEntity le : tracker.getActiveMinions(player.getUniqueId())) {
                    if (le == null || le.isDead()) continue;
                    Integer t = le.getPersistentDataContainer().get(teamKey, org.bukkit.persistence.PersistentDataType.INTEGER);
                    if (t != null && t == activeTeam) activeCount++;
                }
            }
        } catch (Throwable ignored) {}

        String info = "§aActive §f" + activeCount + "/" + slotsPerTeam;
        try {
            var respawn = plugin.getSummonRespawnService();
            if (respawn != null && player != null && activeTeam > 0) {
                double next = respawn.getNextPendingRemainingSeconds(player.getUniqueId(), activeTeam);
                if (next >= 0.0) {
                    info += " §d" + formatSeconds(next);
                }
            }
        } catch (Throwable ignored) {}
        return info;
    }

    private static String formatSeconds(double seconds) {
        double s = Math.max(0.0, seconds);
        if (s >= 60.0) {
            int m = (int) (s / 60.0);
            int sec = (int) Math.ceil(s - m * 60.0);
            if (sec >= 60) { m++; sec -= 60; }
            return m + "m" + sec + "s";
        }
        return String.format(java.util.Locale.ROOT, "%.0fs", Math.ceil(s));
    }

    public void stop() {
        try { if (task != null) task.cancel(); } catch (Throwable ignored) {}
        task = null;
    }
}
