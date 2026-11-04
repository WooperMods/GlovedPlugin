package dev.gloved.glovedplugin.lootr.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.Block;
import org.bukkit.block.Container;

public class ChestOpenListener implements Listener {
    // Placeholder implementation for clean-room re-implementation
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (!e.hasBlock()) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!(block.getState() instanceof Container)) return;
        // TODO: detect lootr container, open per-player inventory
        // For now just log
    }
}
