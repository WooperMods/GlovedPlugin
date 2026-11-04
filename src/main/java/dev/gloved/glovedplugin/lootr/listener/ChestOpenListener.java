package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.manager.LootrManager;
import dev.gloved.glovedplugin.lootr.manager.PlayerLootService;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import dev.gloved.glovedplugin.lootr.util.EliteMobsIntegration;
import dev.gloved.glovedplugin.lootr.util.InventorySerializer;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
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

public class ChestOpenListener implements Listener {
    private final GlovedPlugin plugin;
    private final LootrManager manager;
    private final PlayerLootService lootService;
    private final java.util.concurrent.ConcurrentHashMap<Inventory, ContainerId> inventoryMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Inventory, UUID> playerMap = new java.util.concurrent.ConcurrentHashMap<>();

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
        String baseTitle = org.bukkit.ChatColor.translateAlternateColorCodes('&', manager.getConfig().inventoryTitle);
        String suffix = org.bukkit.ChatColor.translateAlternateColorCodes('&', manager.getConfig().eliteTitleSuffix);
        UUID uuid = player.getUniqueId();
        byte[] blob = lootService.getInventoryBlob(id, uuid);
        int eliteFlag = lootService.getEliteFlag(id, uuid);
        boolean eliteAvailable = manager.getConfig().eliteEnabled && EliteMobsIntegration.isAvailable();
        String title = baseTitle + (eliteFlag == 1 && eliteAvailable ? suffix : "");
        Inventory inv;
        GlovedPlugin.logDebug("Lootr open check: player=" + player.getName() + " container=" + id.toStringKey() + " blobExists=" + (blob != null && blob.length > 0) + " eliteFlag=" + eliteFlag + " eliteEnabled=" + manager.getConfig().eliteEnabled + " eliteMobsAvailable=" + EliteMobsIntegration.isAvailable());
        if (blob != null && blob.length > 0) {
            inv = Bukkit.createInventory(null, containerState.getInventory().getSize(), title);
            ItemStack[] items = InventorySerializer.deserialize(blob);
            for (int i = 0; i < Math.min(items.length, inv.getSize()); i++) {
                if (items[i] != null) inv.setItem(i, items[i]);
            }
            inventoryMap.put(inv, id);
            playerMap.put(inv, uuid);
            player.openInventory(inv);
            if (manager.getConfig().playSound) {
                player.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.6f, 1.0f);
            }
            return;
        }

        boolean isElite = false;
        if (manager.getConfig().eliteEnabled && EliteMobsIntegration.isAvailable()) {
            if (eliteFlag == 1) {
                isElite = true;
            } else {
                double luck = 0;
                if (player.getAttribute(Attribute.LUCK) != null) {
                    luck = player.getAttribute(Attribute.LUCK).getValue();
                }
                double chance = manager.getConfig().eliteChance + luck * manager.getConfig().eliteLuckMultiplier;
                if (chance < 0) chance = 0;
                if (chance > 1) chance = 1;
                if (new Random().nextDouble() < chance) {
                    isElite = true;
                    lootService.setEliteFlag(id, uuid, 1);
                    GlovedPlugin.logDebug("Lootr elite roll success for player " + player.getName() + " container " + id.toStringKey() + " chance=" + chance);
                } else {
                    GlovedPlugin.logDebug("Lootr elite roll fail for player " + player.getName() + " container " + id.toStringKey() + " chance=" + chance);
                }
            }
        }
        GlovedPlugin.logDebug("Lootr isElite final=" + isElite + " eliteFlag=" + eliteFlag + " eliteMobsAvailable=" + EliteMobsIntegration.isAvailable());

        int size = containerState.getInventory().getSize();
        if (isElite) {
            title = baseTitle + suffix;
            inv = Bukkit.createInventory(null, size, title);
            int combatLevel = EliteMobsIntegration.getCombatLevel(player);
            int min = manager.getConfig().eliteMinItems;
            int max = manager.getConfig().eliteMaxItems;
            if (min < 1) min = 1;
            if (max < min) max = min;
            java.util.Random rand = new java.util.Random();
            int baseCount = min + rand.nextInt(max - min + 1);
            int count = Math.min(size, baseCount * 4); // elite chests get 4x items (doubled twice)
            GlovedPlugin.logDebug("Lootr elite chest generating " + count + " items (base=" + baseCount + ") for " + player.getName());
            addEliteItems(inv, player, combatLevel, count, rand);
            GlovedPlugin.logDebug("Lootr elite chest generated " + inv.getSize() + " slots, " + count + " requested for " + player.getName());
        } else {
            inv = Bukkit.createInventory(null, size, title);
            String lootTableKeyStr = manager.getRegistry().getLootTable(id);
            GlovedPlugin.logDebug("Lootr open: container=" + id.toStringKey() + " lootTableKey=" + lootTableKeyStr);
            if (lootTableKeyStr != null && !lootTableKeyStr.isEmpty()) {
                try {
                    org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(lootTableKeyStr);
                    if (key != null) {
                        org.bukkit.loot.LootTable table = Bukkit.getLootTable(key);
                        if (table != null) {
                            LootContext ctx = new LootContext.Builder(block.getLocation()).build();
                            table.fillInventory(inv, new Random(), ctx);
                            GlovedPlugin.logDebug("Lootr fillInventory: table=" + key + " success");
                        }
                    }
                } catch (Exception e) {
                    GlovedPlugin.logDebug("Lootr fillInventory exception: " + e);
                }
            }
            // Elite items can also hide in regular chests: a coin-toss roll per item
            // using elite.regular-chance. One success = 1 elite item, each further
            // success = another item, capped at elite.regular-max-items. With the
            // default 50% chance, at-least-one lands 50% of the time and longer
            // streaks halve per coin toss.
            double regularChance = manager.getConfig().eliteRegularChance;
            if (eliteAvailable && regularChance > 0) {
                int maxItems = Math.max(1, manager.getConfig().eliteRegularMaxItems);
                Random rollRandom = new Random();
                int eliteCount = 0;
                while (eliteCount < maxItems && rollRandom.nextDouble() < regularChance) {
                    eliteCount++;
                }
                if (eliteCount > 0) {
                    int combatLevel = EliteMobsIntegration.getCombatLevel(player);
                    GlovedPlugin.logDebug("Lootr regular chest elite roll success: " + eliteCount + " items, combatLevel=" + combatLevel);
                    addEliteItems(inv, player, combatLevel, eliteCount, rollRandom);
                }
            }
        }

        lootService.saveInventory(id, uuid, inv);
        inventoryMap.put(inv, id);
        playerMap.put(inv, uuid);
        player.openInventory(inv);
        if (manager.getConfig().playSound) {
            player.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.6f, 1.0f);
        }
    }

    private void addEliteItems(Inventory inv, Player player, int combatLevel, int count, java.util.Random rand) {
        int size = inv.getSize();
        int generated = 0;
        int attempts = 0;
        while (generated < count && attempts < count * 10) {
            ItemStack item = EliteMobsIntegration.generateRandomLoot(player, combatLevel);
            attempts++;
            if (item != null && item.getType() != org.bukkit.Material.AIR) {
                int tries = 0;
                while (tries < size * 2) {
                    int slot = rand.nextInt(size);
                    if (inv.getItem(slot) == null) {
                        inv.setItem(slot, item.clone());
                        generated++;
                        break;
                    }
                    tries++;
                }
            }
        }
        GlovedPlugin.logDebug("Lootr elite items placed: requested=" + count + " placed=" + generated + " inventorySize=" + size + " player=" + player.getName());
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

}
