package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.block.Container;
import org.bukkit.loot.Lootable;
import org.bukkit.loot.LootTable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

public class ChestAutoRegisterListener implements Listener {
    private final LootrManager manager;

    public ChestAutoRegisterListener(LootrManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (org.bukkit.block.BlockState state : e.getChunk().getTileEntities()) {
            if (state instanceof Container) {
                Container c = (Container) state;
                if (c instanceof Lootable) {
                    Lootable lootable = (Lootable) c;
                    LootTable table = lootable.getLootTable();
                    if (table != null) {
                        org.bukkit.block.Block block = c.getBlock();
                        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                        manager.getRegistry().register(id, table.getKey().toString());
                    }
                }
            }
        }
    }
}
