package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ExplosionPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ProtectionListener implements Listener {
    private final Map<UUID, Map<Block, Long>> breakConfirm = new ConcurrentHashMap<>();
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (!(block.getState() instanceof Container)) return;
        Player player = e.getPlayer();
        if (player.hasPermission("gloved.lootr.bypass")) return;
        
        // TODO: check registry
        // For demo, prevent break with confirm
        GlovedPlugin plugin = GlovedPlugin.getPlugin(GlovedPlugin.class);
        // We need manager, but for now cancel
        // Simplified: cancel break
        // e.setCancelled(true);
        // player.sendMessage("§cLootr chest protected");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        // TODO: prevent hopper from moving items from lootr containers
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        // TODO: remove lootr containers from explosion
        e.blockList().removeIf(block -> block.getState() instanceof Container);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(block -> block.getState() instanceof Container);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        e.getBlocks().forEach(block -> {
            if (block.getState() instanceof Container) {
                e.setCancelled(true);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        e.getBlocks().forEach(block -> {
            if (block.getState() instanceof Container) {
                e.setCancelled(true);
            }
        });
    }
}
