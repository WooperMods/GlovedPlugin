package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.manager.PlayerLootService;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import dev.gloved.glovedplugin.lootr.util.EliteMobsIntegration;
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

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!e.hasBlock()) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!(block.getState() instanceof Container)) return;
        // world disable check
        if (manager.getConfig().disabledWorlds != null && manager.getConfig().disabledWorlds.contains(block.getWorld().getName())) return;
        // container type filter
        if (manager.getConfig().containers != null) {
            String type = block.getType().name();
            if (!manager.getConfig().containers.contains(type)) return;
        }

        Container containerState = (Container) block.getState();
        if (!(containerState instanceof Lootable)) return;

        Player player = e.getPlayer();
        ContainerId id = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        boolean justRegistered = false;
        if (!manager.getRegistry().isRegistered(id)) {
            Lootable lootable = (Lootable) containerState;
            LootTable table = lootable.getLootTable();
            if (table != null) {
                manager.getRegistry().register(id, table.getKey().toString());
                // Clear vanilla loot
                lootable.setLootTable(null);
                lootable.setSeed(0);
                containerState.update(true, false);
                block.getState().update();
                // Clear inventory
                containerState.getInventory().clear();
                GlovedPlugin.logDebug("Lootr registered container " + id.toStringKey() + " with loot " + table.getKey());
                justRegistered = true;
            } else {
                return;
            }
        }

        e.setCancelled(true);
        openPerPlayerInventory(player, block, containerState, id, justRegistered);
    }

    private void openPerPlayerInventory(Player player, Block block, Container containerState, ContainerId id, boolean justRegistered) {
        if (manager.getConfig().particlesEnabled) {
            org.bukkit.Particle particle = parseParticle(manager.getConfig().particleType);
            if (particle != null) {
                // particles only unlooted?
                boolean unlooted = !lootService.hasOpened(id, player.getUniqueId());
                if (!manager.getConfig().particlesOnlyUnlooted || unlooted) {
                    block.getWorld().spawnParticle(
                        particle,
                        block.getLocation().add(0.5,0.5,0.5),
                        5, 0.2, 0.2, 0.2, 0
                    );
                }
            }
        }
        // sound handled later

        byte[] blob = lootService.getInventoryBlob(id, player.getUniqueId());
        Inventory inv;
        if (blob != null && blob.length > 0) {
            inv = Bukkit.createInventory(null, containerState.getInventory().getSize(), manager.getConfig().inventoryTitle);
            ItemStack[] items = InventorySerializer.deserialize(blob);
            for (int i = 0; i < Math.min(items.length, inv.getSize()); i++) {
                if (items[i] != null) inv.setItem(i, items[i]);
            }
            inventoryMap.put(inv, id);
            playerMap.put(inv, player.getUniqueId());
            player.openInventory(inv);
            if (manager.getConfig().playSound) {
                player.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.6f, 1.0f);
            }
            return;
        }

        inv = Bukkit.createInventory(null, containerState.getInventory().getSize(), manager.getConfig().inventoryTitle);
        String lootTableKeyStr = manager.getRegistry().getLootTable(id);
        if (lootTableKeyStr != null) {
            try {
                org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(lootTableKeyStr);
                if (key != null) {
                    org.bukkit.loot.LootTable table = Bukkit.getLootTable(key);
                    if (table != null) {
                        LootContext ctx = new LootContext.Builder(block.getLocation()).build();
                        table.fillInventory(inv, new Random(), ctx);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (manager.getConfig().elitemobsEnabled && EliteMobsIntegration.isAvailable()) {
            int combatLevel = EliteMobsIntegration.getCombatLevel(player);
            // Mix EliteMobs items: add a random weapon/armor with elite level
            if (new java.util.Random().nextDouble() < 0.3) {
                ItemStack eliteItem = new ItemStack(org.bukkit.Material.DIAMOND_SWORD);
                eliteItem = EliteMobsIntegration.applyEliteLevel(eliteItem, combatLevel);
                inv.addItem(eliteItem);
            }
            // Optionally tag existing items
            if (manager.getConfig().elitemobsMixVanilla) {
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack is = inv.getItem(i);
                    if (is != null && is.getType().isItem() && new java.util.Random().nextDouble() < 0.1) {
                        inv.setItem(i, EliteMobsIntegration.applyEliteLevel(is, combatLevel));
                    }
                }
            }
        }

        lootService.saveInventory(id, player.getUniqueId(), inv);
        inventoryMap.put(inv, id);
        playerMap.put(inv, player.getUniqueId());
        player.openInventory(inv);
        if (manager.getConfig().playSound) {
            player.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.6f, 1.0f);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        Inventory inv = e.getInventory();
        ContainerId id = inventoryMap.remove(inv);
        UUID uuid = playerMap.remove(inv);
        if (id != null && uuid != null) {
            lootService.saveInventory(id, uuid, inv);
            if (manager.getConfig().playSound && e.getPlayer() instanceof Player player) {
                player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_CHEST_CLOSE, 0.6f, 1.0f);
            }
        }
    }

    private org.bukkit.Particle parseParticle(String name) {
        if (name == null) return null;
        try {
            return org.bukkit.Particle.valueOf(name.toUpperCase());
        } catch (Exception ex) {
            return org.bukkit.Particle.HAPPY_VILLAGER;
        }
    }
}
