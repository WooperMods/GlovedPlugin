package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

public class ProtectionListener implements Listener {
    private final LootrManager manager;
    private final java.util.Map<java.util.UUID, java.util.Map<ContainerId, Long>> confirmMap = new java.util.concurrent.ConcurrentHashMap<>();

    public ProtectionListener(LootrManager manager) {
        this.manager = manager;
    }

    private boolean isProtected(Block block) {
        if (!(block.getState() instanceof Container)) return false;
        // world disable check
        if (manager.getConfig().disabledWorlds != null && manager.getConfig().disabledWorlds.contains(block.getWorld().getName())) return false;
        // container type filter
        if (manager.getConfig().containers != null) {
            String type = block.getType().name();
            if (!manager.getConfig().containers.contains(type)) return false;
        }
        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        return manager.getRegistry().isRegistered(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (!isProtected(block)) return;
        if (manager.getConfig().protectionBreak == false) return;
        if (!e.getPlayer().isSneaking()) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cYou must be crouching to break Lootr chests");
            return;
        }
        java.util.UUID playerId = e.getPlayer().getUniqueId();
        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        long now = System.currentTimeMillis();
        java.util.Map<ContainerId, Long> playerConfirms = confirmMap.computeIfAbsent(playerId, k -> new java.util.HashMap<>());
        Long last = playerConfirms.get(id);
        int confirmTimeMs = manager.getConfig().breakConfirmTime * 1000;
        if (last != null && now - last <= confirmTimeMs) {
            // confirmed, allow break and cleanup registry
            playerConfirms.remove(id);
            manager.getRegistry().register(id, null); // placeholder, actual removal would be better
            // allow break - do not cancel
            return;
        } else {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cLootr chest protected. Break again within " + manager.getConfig().breakConfirmTime + "s to confirm.");
            playerConfirms.put(id, now);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        if (manager.getConfig().protectionHopper == false) return;
        if (e.getSource().getHolder() instanceof org.bukkit.block.BlockState state) {
            if (state instanceof org.bukkit.block.Container) {
                org.bukkit.block.Block block = state.getBlock();
                if (isProtected(block)) {
                    e.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (manager.getConfig().protectionExplosion == false) return;
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (manager.getConfig().protectionExplosion == false) return;
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (manager.getConfig().protectionPiston == false) return;
        if (e.getBlocks().stream().anyMatch(this::isProtected)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (manager.getConfig().protectionPiston == false) return;
        if (e.getBlocks().stream().anyMatch(this::isProtected)) {
            e.setCancelled(true);
        }
    }
}
