package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.bukkit.persistence.PersistentDataType;

public class LootrChestPlaceListener implements Listener {
    private final GlovedPlugin plugin;
    private final LootrManager manager;
    private final NamespacedKey keyMode;
    private final NamespacedKey keyLoot;
    private final NamespacedKey keyId;

    public LootrChestPlaceListener(GlovedPlugin plugin, LootrManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.keyMode = new NamespacedKey(plugin, "lootr_mode");
        this.keyLoot = new NamespacedKey(plugin, "lootr_loot");
        this.keyId = new NamespacedKey(plugin, "lootr_id");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (item == null || !item.hasItemMeta()) return;
        if (item.getType() != org.bukkit.Material.CHEST) return;

        var meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        if (!pdc.has(keyMode, PersistentDataType.STRING)) return;
        if (!pdc.has(keyLoot, PersistentDataType.STRING)) return;

        String mode = pdc.get(keyMode, PersistentDataType.STRING);
        String lootTable = pdc.get(keyLoot, PersistentDataType.STRING);
        boolean elite = "elite".equalsIgnoreCase(mode);

        Block block = e.getBlockPlaced();
        if (!(block.getState() instanceof Container)) return;

        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());

        GlovedPlugin.logDebug("Lootr place detected id=" + id.toStringKey() + " mode=" + mode + " loot=" + lootTable + " elite=" + elite);

        // Register container
        manager.getRegistry().upsert(id, lootTable == null ? "" : lootTable, elite);

        // Clear vanilla loot to avoid duplication
        if (block.getState() instanceof Lootable lootable) {
            lootable.setLootTable(null);
            lootable.setSeed(0);
            block.getState().update(true, false);
        }
        // Clear inventory
        Container containerState = (Container) block.getState();
        containerState.getInventory().clear();
        containerState.update(true, false);

        // Optional feedback
        if (e.getPlayer() != null) {
            String msg = elite ? "§aPlaced Elite Lootr chest" : "§aPlaced Lootr chest";
            if (!lootTable.isEmpty()) msg += " with " + lootTable;
            e.getPlayer().sendMessage(msg);
        }
    }
}
