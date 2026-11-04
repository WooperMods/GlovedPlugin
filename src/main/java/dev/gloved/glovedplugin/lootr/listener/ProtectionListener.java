package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ProtectionListener implements Listener {
    private final Map<UUID, Long> breakConfirm = new HashMap<>();

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        // TODO: check if block is registered lootr container
        Player player = e.getPlayer();
        if (player.hasPermission("gloved.lootr.bypass")) return;
        // placeholder: cancel break for demonstration
        // In full implementation, check registry and config
        // For now, allow break
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        // TODO: prevent piston moving registered containers
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        // TODO: prevent piston moving registered containers
    }
}
