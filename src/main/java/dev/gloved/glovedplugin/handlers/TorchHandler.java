package dev.gloved.glovedplugin.handlers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import dev.gloved.glovedplugin.GlovedPlugin;

import static dev.gloved.glovedplugin.GlovedPlugin.logInfo;

/**
 * The TorchHandler class implements the Listener interface.
 * This class is responsible for handling events related to torches in the game.
 * It includes methods to handle events such as placing a torch in the game.
 * Each of these methods is annotated with the @EventHandler annotation to indicate that they are event handlers.
 */
public class TorchHandler implements Listener {

    /**
     * Constructor for the TorchHandler class.
     * This constructor is responsible for registering the TorchHandler as an event listener.
     * It first retrieves the plugin manager from the Bukkit server.
     * Then, it registers this instance of TorchHandler as an event listener with the plugin manager.
     * After registering the event listener, it logs a message indicating that the TorchHandler has been registered.
     *
     * @param plugin The instance of the GlovedPlugin that this TorchHandler is associated with.
     */
    public TorchHandler(GlovedPlugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        logInfo("Registered TorchHandler!");
    }

    /**
     * Event handler for block place events.
     * This method is triggered when a block is placed in the game.
     * It first retrieves the block that was placed.
     * Then, it checks if the block type is not a torch.
     * If the block is not a torch, the method returns immediately.
     * If the block is a torch, it logs a message indicating the location where the torch was placed.
     *
     * @param event The block place event.
     */
    @EventHandler
    public void onTorchPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.TORCH) return;

        logInfo("Torch placed at " + block.getLocation());
    }
}
