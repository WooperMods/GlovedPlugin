package dev.gloved.glovedplugin.lootr.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

public class ProtectionListener implements Listener {
    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        // TODO: prevent break for lootr containers
    }
}
