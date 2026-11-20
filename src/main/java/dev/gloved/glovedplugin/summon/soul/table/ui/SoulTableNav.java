package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.guide.SoulGuideBook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Central navigation for Soul Table screens and guide return targets. */
public final class SoulTableNav {

    private SoulTableNav() {
    }

    public static void openHub(GlovedPlugin plugin, Player player, UUID tableId) {
        if (plugin == null || player == null) return;
        SoulTableRow row = resolveRow(plugin, tableId);
        if (row == null) {
            player.closeInventory();
            return;
        }
        new SoulTableHubInv(plugin, player, row).open(player);
    }

    public static void openProcess(GlovedPlugin plugin, Player player, UUID tableId, SoulTableReturnTarget process) {
        if (plugin == null || player == null) return;
        SoulTableReturnTarget target = process != null ? process : SoulTableReturnTarget.HUB;
        SoulTableRow row = resolveRow(plugin, tableId);
        if (row == null) {
            player.closeInventory();
            return;
        }
        switch (target) {
            case COMBINE -> new SoulTableCombineInv(plugin, player, row).open(player);
            case ENHANCE -> new SoulTableEnhanceInv(plugin, player, row).open(player);
            case REFORGE -> new SoulTableReforgeInv(plugin, player, row).open(player);
            case SALVAGE -> new SoulTableSalvageInv(plugin, player, row).open(player);
            case CONDENSE -> new SoulTableCondenseInv(plugin, player, row).open(player);
            case INFUSION -> new SoulTableInfusionInv(plugin, player, row).open(player);
            case HUB -> new SoulTableHubInv(plugin, player, row).open(player);
        }
    }

    /** Opens the Soul Guide as a read-only written book (standalone reference; no table-context return). */
    public static void openGuide(GlovedPlugin plugin, Player player) {
        if (plugin == null || player == null) return;
        new SoulGuideBook(plugin).open(player);
    }

    public static void returnToTarget(GlovedPlugin plugin, Player player, UUID tableId, SoulTableReturnTarget target) {
        if (plugin == null || player == null) return;
        if (tableId == null) {
            player.closeInventory();
            return;
        }
        SoulTableReturnTarget resolved = target != null ? target : SoulTableReturnTarget.HUB;
        if (resolved == SoulTableReturnTarget.HUB) {
            openHub(plugin, player, tableId);
        } else {
            openProcess(plugin, player, tableId, resolved);
        }
    }

    public static void runSync(GlovedPlugin plugin, Runnable runnable) {
        if (plugin == null || runnable == null) return;
        if (Bukkit.isPrimaryThread()) runnable.run();
        else Bukkit.getScheduler().runTask(plugin, runnable);
    }

    public static SoulTableRow resolveRow(GlovedPlugin plugin, UUID tableId) {
        if (plugin == null || tableId == null) return null;
        try {
            var mgr = plugin.getSoulTableManager();
            return mgr != null ? mgr.get(tableId) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
