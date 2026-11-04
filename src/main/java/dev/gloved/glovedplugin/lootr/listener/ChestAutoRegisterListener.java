package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.loot.Lootable;

public class ChestAutoRegisterListener implements Listener {
    private final LootrManager manager;

    public ChestAutoRegisterListener(LootrManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        e.getChunk().getLoadedEntities().forEach(entity -> {});
        // Scan blocks in chunk
        e.getChunk().getBlocks().forEach(block -> {
            if (!(block.getState() instanceof Container)) return;
            Container state = (Container) block.getState();
            if (!(state instanceof Lootable)) return;
            Lootable lootable = (Lootable) state;
            if (lootable.getLootTable() == null) return;
            ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
            if (!manager.getRegistry().isRegistered(id)) {
                manager.getRegistry().register(id, lootable.getLootTable().getKey().toString());
            }
        });
    }
}
