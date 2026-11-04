package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.lootr.manager.LootrManager;
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
        // Placeholder for bulk scan. Registration happens on interact to avoid heavy scanning.
        // If needed, implement async scanning here.
    }
}
