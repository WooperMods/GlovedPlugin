package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.ContainerState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

import java.util.UUID;

public class ChestOpenListener implements Listener {
    private final GlovedPlugin plugin;
    private final LootrManager manager;

    public ChestOpenListener(GlovedPlugin plugin, LootrManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (!e.hasBlock()) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!(block.getState() instanceof ContainerState)) return;
        if (!e.getAction().toString().contains("RIGHT_CLICK")) return;
        Player player = e.getPlayer();
        if (player.hasPermission("gloved.lootr.bypass")) return;

        ContainerState state = (ContainerState) block.getState();
        if (!(state instanceof Lootable)) return;

        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        if (!manager.getRegistry().isRegistered(id)) {
            // auto-register if loot table present
            Lootable lootable = (Lootable) state;
            NamespacedKey key = lootable.getLootTable();
            if (key != null) {
                manager.getRegistry().register(block, key.toString());
                GlovedPlugin.logDebug("Lootr registered container " + id.toStringKey() + " with loot " + key);
            } else {
                return;
            }
        }

        e.setCancelled(true);
        openPerPlayerInventory(player, block, state);
    }

    private void openPerPlayerInventory(Player player, Block block, ContainerState state) {
        Inventory inv = Bukkit.createInventory(null, state.getInventory().getSize(), manager.getConfig().inventoryTitle);
        // TODO: load per-player inventory from DB, generate if first open
        // For now, generate vanilla loot
        Lootable lootable = (Lootable) state;
        NamespacedKey key = lootable.getLootTable();
        if (key != null) {
            LootTable table = Bukkit.getLootTable(key);
            LootContext ctx = new LootContext.Builder(block.getWorld())
                .seed(block.getWorld().getSeed())
                .build();
            table.fillInventory(inv, player, ctx);
        }
        // Mix EliteMobs if enabled
        if (manager.getConfig().elitemobsEnabled) {
            ItemStack elite = new ItemStack(org.bukkit.Material.NETHER_STAR);
            var meta = elite.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§6Elite Gear");
                elite.setItemMeta(meta);
            }
            inv.addItem(elite);
        }
        player.openInventory(inv);
    }
}
