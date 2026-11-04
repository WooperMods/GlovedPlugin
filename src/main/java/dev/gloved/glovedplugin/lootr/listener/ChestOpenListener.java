package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.manager.PlayerLootService;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import dev.gloved.glovedplugin.lootr.util.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.Lootable;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

import java.util.Random;
import java.util.UUID;
import java.util.WeakHashMap;

public class ChestOpenListener implements Listener {
    private final GlovedPlugin plugin;
    private final LootrManager manager;
    private final PlayerLootService lootService;
    private final WeakHashMap<Inventory, ContainerId> inventoryMap = new WeakHashMap<>();
    private final WeakHashMap<Inventory, UUID> playerMap = new WeakHashMap<>();

    public ChestOpenListener(GlovedPlugin plugin, LootrManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.lootService = new PlayerLootService(manager.getDatabase().getConnection());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (!e.hasBlock()) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!(block.getState() instanceof Container)) return;
        if (!e.getAction().toString().contains("RIGHT_CLICK")) return;
        Player player = e.getPlayer();
        if (player.hasPermission("gloved.lootr.bypass")) return;

        Container containerState = (Container) block.getState();
        if (!(containerState instanceof Lootable)) return;

        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        if (!manager.getRegistry().isRegistered(id)) {
            Lootable lootable = (Lootable) containerState;
            LootTable table = lootable.getLootTable();
            if (table != null) {
                manager.getRegistry().register(id, table.getKey().toString());
                GlovedPlugin.logDebug("Lootr registered container " + id.toStringKey() + " with loot " + table.getKey());
            } else {
                return;
            }
        }

        e.setCancelled(true);
        openPerPlayerInventory(player, block, containerState, id);
    }

    private void openPerPlayerInventory(Player player, Block block, Container containerState, ContainerId id) {
        if (manager.getConfig().particlesEnabled) {
            block.getWorld().spawnParticle(
                org.bukkit.Particle.HAPPY_VILLAGER,
                block.getLocation().add(0.5,0.5,0.5),
                5, 0.2, 0.2, 0.2, 0
            );
        }

        byte[] blob = lootService.getInventoryBlob(id, player.getUniqueId());
        Inventory inv;
        if (blob != null && blob.length > 0) {
            inv = Bukkit.createInventory(null, containerState.getInventory().getSize(), manager.getConfig().inventoryTitle);
            ItemStack[] items = InventorySerializer.deserialize(blob);
            for (int i = 0; i < Math.min(items.length, inv.getSize()); i++) {
                if (items[i] != null) inv.setItem(i, items[i]);
            }
            // Ensure items persist after inventory close
            inventoryMap.put(inv, id);
            playerMap.put(inv, player.getUniqueId());
            player.openInventory(inv);
            return;
        }

        inv = Bukkit.createInventory(null, containerState.getInventory().getSize(), manager.getConfig().inventoryTitle);
        Lootable lootable = (Lootable) containerState;
        LootTable table = lootable.getLootTable();
        if (table != null) {
            LootContext ctx = new LootContext.Builder(block.getLocation()).build();
            table.fillInventory(inv, new Random(), ctx);
        }
        if (manager.getConfig().elitemobsEnabled) {
            ItemStack elite = new ItemStack(org.bukkit.Material.NETHER_STAR);
            var meta = elite.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§6Elite Gear");
                elite.setItemMeta(meta);
            }
            inv.addItem(elite);
        }

        lootService.saveInventory(id, player.getUniqueId(), inv);
        inventoryMap.put(inv, id);
        playerMap.put(inv, player.getUniqueId());
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        ContainerId id = inventoryMap.remove(inv);
        UUID uuid = playerMap.remove(inv);
        if (id != null && uuid != null) {
            lootService.saveInventory(id, uuid, inv);
        }
    }
}
