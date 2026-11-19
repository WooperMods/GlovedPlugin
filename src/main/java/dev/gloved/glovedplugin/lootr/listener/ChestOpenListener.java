package dev.gloved.glovedplugin.lootr.listener;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.lootr.LootrModule;
import dev.gloved.glovedplugin.lootr.service.PlayerLootService;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import dev.gloved.glovedplugin.lootr.integration.EliteMobsIntegration;
import dev.gloved.glovedplugin.lootr.util.InventorySerializer;
import dev.gloved.glovedplugin.summon.soul.table.item.CondensedSoulEssenceItem;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class ChestOpenListener implements Listener {
    private final GlovedPlugin plugin;
    private final LootrModule manager;
    private final PlayerLootService lootService;
    private final java.util.concurrent.ConcurrentHashMap<Inventory, ContainerId> inventoryMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Inventory, UUID> playerMap = new java.util.concurrent.ConcurrentHashMap<>();

    public ChestOpenListener(GlovedPlugin plugin, LootrModule manager) {
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
        GlovedPlugin.logDebug("Lootr interact player=" + player.getName() + " id=" + id.toStringKey() + " isRegistered=" + manager.getRegistry().isRegistered(id));
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
        GlovedPlugin.logDebug("Lootr openPerPlayerInventory start id=" + id.toStringKey() + " justRegistered=" + justRegistered);
        String baseTitle = org.bukkit.ChatColor.translateAlternateColorCodes('&', manager.getConfig().inventoryTitle);
        UUID uuid = player.getUniqueId();
        byte[] blob = lootService.getInventoryBlob(id, uuid);
        int eliteFlag = lootService.getEliteFlag(id, uuid);
        Long firstOpened = lootService.getFirstOpened(id, uuid);
        boolean eliteAvailable = manager.getConfig().eliteEnabled && EliteMobsIntegration.isAvailable();
        // Container-level elite marker: an admin-forced elite chest is elite for
        // every player who opens it, not just the one who generated it.
        boolean containerElite = manager.getRegistry().isElite(id);
        final long REROLL_MS = 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        boolean expired = firstOpened == null || now - firstOpened >= REROLL_MS;
        boolean customSnapshot = isCustomNamespaceSnapshot(id);
        boolean eliteChest = eliteAvailable && (eliteFlag == 1 || containerElite);
        String title = eliteChest ? "§6Elite Chest" : baseTitle;
        int size = containerState.getInventory().getSize();
        Inventory inv;
        GlovedPlugin.logDebug("Lootr open check: player=" + player.getName() + " container=" + id.toStringKey()
                + " blobExists=" + (blob != null && blob.length > 0) + " firstOpened=" + firstOpened
                + " expired=" + expired + " eliteFlag=" + eliteFlag + " containerElite=" + containerElite
                + " customSnapshot=" + customSnapshot
                + " eliteEnabled=" + manager.getConfig().eliteEnabled + " eliteMobsAvailable=" + EliteMobsIntegration.isAvailable());

        // Serve existing per-player loot. For custom-namespace snapshots there is no timed reroll
        // (only reset/recreate clears), so keep the player's existing copy even if "expired".
        if (blob != null && blob.length > 0 && (!expired || customSnapshot)) {
            inv = Bukkit.createInventory(null, size, title);
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
            if (containerElite) {
                isElite = true;
                lootService.setEliteFlag(id, uuid, 1);
                GlovedPlugin.logDebug("Lootr container-level elite applied for player " + player.getName() + " container " + id.toStringKey());
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
                    GlovedPlugin.logDebug("Lootr elite roll success for player " + player.getName() + " container " + id.toStringKey() + " chance=" + chance);
                } else {
                    GlovedPlugin.logDebug("Lootr elite roll fail for player " + player.getName() + " container " + id.toStringKey() + " chance=" + chance);
                }
            }
        }
        lootService.setEliteFlag(id, uuid, isElite ? 1 : 0);
        GlovedPlugin.logDebug("Lootr isElite final=" + isElite + " eliteFlag=" + eliteFlag + " eliteMobsAvailable=" + EliteMobsIntegration.isAvailable());

        title = isElite ? "§6Elite Chest" : baseTitle;
        if (isElite) {
            inv = Bukkit.createInventory(null, size, title);
            int combatLevel = EliteMobsIntegration.getCombatLevel(player);
            // Guarantee the minimum (elite.min-items), then one coin toss per extra
            // elite item at elite.extra-chance (default 65%) until a miss or the
            // elite.max-items cap.
            int min = Math.max(1, manager.getConfig().eliteMinItems);
            int max = Math.min(manager.getConfig().eliteMaxItems, size);
            if (max < min) max = min;
            java.util.Random rand = new java.util.Random();
            int count = min;
            while (count < max && rand.nextDouble() < manager.getConfig().eliteExtraChance) {
                count++;
            }
            GlovedPlugin.logDebug("Lootr elite chest generating " + count + "/" + max + " items (min=" + min + ") for " + player.getName());
            addEliteItems(inv, player, combatLevel, count, rand, manager.getConfig().eliteUpJitterChance, manager.getConfig().eliteUpJitterMax);
            GlovedPlugin.logDebug("Lootr elite chest generated " + inv.getSize() + " slots, " + count + " requested for " + player.getName());
        } else {
            inv = Bukkit.createInventory(null, size, title);
            if (customSnapshot) {
                // Custom (non-minecraft namespace): generate base loot once as a shared template,
                // then give each player their own copy. Bonuses are rolled per player on copy.
                byte[] templateBlob = lootService.getTemplateBlob(id);
                if (templateBlob == null || templateBlob.length == 0) {
                    String lootTableKeyStr = manager.getRegistry().getLootTable(id);
                    GlovedPlugin.logDebug("Lootr custom snapshot: creating template for " + id.toStringKey() + " lootTableKey=" + lootTableKeyStr);
                    Inventory templateInv = Bukkit.createInventory(null, size, title);
                    boolean filled = fillLootTableBase(templateInv, id, lootTableKeyStr, player, block, containerState);
                    templateBlob = InventorySerializer.serialize(templateInv);
                    if (filled) {
                        lootService.saveTemplate(id, templateBlob, lootTableKeyStr);
                    } else {
                        GlovedPlugin.logDebug("Lootr custom snapshot: template not saved (loot table unresolved) for " + id.toStringKey());
                    }
                } else {
                    GlovedPlugin.logDebug("Lootr custom snapshot: reusing template for " + id.toStringKey());
                }
                copyInventoryContents(templateBlob, inv);
            } else {
                String lootTableKeyStr = manager.getRegistry().getLootTable(id);
                GlovedPlugin.logDebug("Lootr open: container=" + id.toStringKey() + " lootTableKey=" + lootTableKeyStr);
                fillLootTableBase(inv, id, lootTableKeyStr, player, block, containerState);
            }
            // Elite items can also hide in regular chests: a coin-toss roll per item
            // using elite.regular-chance. One success = 1 elite item, each further
            // success = another item, capped at elite.regular-max-items. Rolled per player.
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
                    addEliteItems(inv, player, combatLevel, eliteCount, rollRandom, 0.0, 0);
                }
            }
        }

        // Condensed essence bonus: rolled per player (including per-player copies of custom snapshots).
        tryAddCondensedSoulEssence(inv);

        lootService.setFirstOpened(id, uuid, now);
        lootService.saveInventory(id, uuid, inv);
        inventoryMap.put(inv, id);
        playerMap.put(inv, uuid);
        player.openInventory(inv);
        if (manager.getConfig().playSound) {
            player.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.6f, 1.0f);
        }
    }

    private boolean isCustomNamespaceSnapshot(ContainerId id) {
        if (!manager.getConfig().customNamespaceSnapshot) return false;
        String lootTableKeyStr = manager.getRegistry().getLootTable(id);
        return isNonMinecraftNamespace(lootTableKeyStr);
    }

    private boolean isNonMinecraftNamespace(String lootTableKeyStr) {
        if (lootTableKeyStr == null || lootTableKeyStr.isEmpty()) return false;
        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(lootTableKeyStr);
            return key != null && !"minecraft".equals(key.getNamespace());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fills the base loot table contents into an inventory (no elite injection, no condensed essence).
     * Used for normal non-elite generation and for creating custom-namespace snapshot templates.
     *
     * @return {@code true} if a loot table was resolved and fill was attempted, {@code false} otherwise
     */
    private boolean fillLootTableBase(Inventory inv, ContainerId id, String lootTableKeyStr, Player player, Block block, Container containerState) {
        if (lootTableKeyStr == null || lootTableKeyStr.isEmpty()) {
            GlovedPlugin.logDebug("Lootr fillInventory: no loot table key for " + id.toStringKey());
            return false;
        }
        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(lootTableKeyStr);
            if (key == null) {
                GlovedPlugin.logDebug("Lootr fillInventory: invalid key " + lootTableKeyStr);
                return false;
            }
            org.bukkit.loot.LootTable table = Bukkit.getLootTable(key);
            if (table == null && containerState instanceof org.bukkit.loot.Lootable lootable) {
                table = lootable.getLootTable();
            }
            if (table == null) {
                GlovedPlugin.logDebug("Lootr fillInventory: table=" + key + " not found for " + id.toStringKey());
                return false;
            }
            LootContext.Builder ctxBuilder = new LootContext.Builder(block.getLocation())
                    .killer(player);
            if (player.getAttribute(Attribute.LUCK) != null) {
                ctxBuilder = ctxBuilder.luck((float) player.getAttribute(Attribute.LUCK).getValue());
            }
            LootContext ctx = ctxBuilder.build();
            table.fillInventory(inv, new Random(), ctx);
            GlovedPlugin.logDebug("Lootr fillInventory: table=" + table.getKey() + " success items=" + countItems(inv));
            return true;
        } catch (Exception e) {
            GlovedPlugin.logDebug("Lootr fillInventory exception: " + e);
            return false;
        }
    }

    private void copyInventoryContents(byte[] templateBlob, Inventory inv) {
        ItemStack[] items = InventorySerializer.deserialize(templateBlob);
        for (int i = 0; i < Math.min(items.length, inv.getSize()); i++) {
            ItemStack item = items[i];
            if (item != null) inv.setItem(i, item.clone());
        }
    }

    private void tryAddCondensedSoulEssence(Inventory inv) {
        if (inv == null || !manager.getConfig().condensedEssenceEnabled) return;
        double chance = manager.getConfig().condensedEssenceChance;
        if (chance <= 0.0) return;
        Random rand = new Random();
        if (rand.nextDouble() >= chance) return;

        int min = Math.max(1, manager.getConfig().condensedEssenceMinAmount);
        int max = Math.max(min, manager.getConfig().condensedEssenceMaxAmount);
        int amount = min + (max > min ? rand.nextInt(max - min + 1) : 0);
        if (amount <= 0) return;

        try {
            var summonCfg = plugin.getSummonConfig();
            var soulTableCfg = summonCfg != null ? summonCfg.getSoulTableConfig() : null;
            ItemStack item = CondensedSoulEssenceItem.create(plugin, amount, soulTableCfg);
            if (item == null || item.getType().isAir()) return;
            var leftover = inv.addItem(item);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    // If the chest is full, place into a random occupied slot as a bonus replacement.
                    List<Integer> slots = new ArrayList<>();
                    for (int i = 0; i < inv.getSize(); i++) {
                        if (inv.getItem(i) != null) slots.add(i);
                    }
                    if (!slots.isEmpty()) {
                        inv.setItem(slots.get(rand.nextInt(slots.size())), drop);
                    }
                }
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("Lootr condensed essence injection failed: " + t.getMessage());
        }
    }

    private int countItems(Inventory inv) {
        int count = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.getType() != org.bukkit.Material.AIR) count++;
        }
        return count;
    }

    private void addEliteItems(Inventory inv, Player player, int combatLevel, int count, java.util.Random rand, double upJitterChance, int upJitterMax) {
        int size = inv.getSize();
        // Slots still open. When empty slots run out, elite items overwrite
        // vanilla loot (random slot) instead of failing to place.
        List<Integer> emptySlots = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            if (inv.getItem(slot) == null) emptySlots.add(slot);
        }
        Set<Integer> eliteSlots = new HashSet<>();
        int generated = 0;
        int overwrote = 0;
        int attempts = 0;
        while (generated < count && attempts < count * 10) {
            // Mirror the mob-drop level jitter: each item rolls around the base
            // level (mostly −0/−1, rare −2, small level-scaled +1 chance).
            int tier = EliteMobsIntegration.jitterItemLevel(combatLevel);
            // Elite chest upward jitter: chance per step to go +1 level
            if (upJitterChance > 0 && upJitterMax > 0) {
                int steps = 0;
                while (steps < upJitterMax && rand.nextDouble() < upJitterChance) {
                    tier++;
                    steps++;
                }
            }
            // Bias quality by sampling a few candidates and picking the best
            ItemStack item = EliteMobsIntegration.generateRandomLootBest(player, tier, 5);
            attempts++;
            GlovedPlugin.logDebug("Lootr addEliteItems attempt tier=" + tier + " item=" + (item != null ? item.getType() : "null") + " generated=" + generated + "/" + count);
            if (item == null || item.getType() == org.bukkit.Material.AIR) continue;
            int slot = -1;
            if (!emptySlots.isEmpty()) {
                int idx = rand.nextInt(emptySlots.size());
                slot = emptySlots.remove(idx);
            } else {
                List<Integer> overwritable = new ArrayList<>();
                for (int s = 0; s < size; s++) {
                    if (inv.getItem(s) != null && !eliteSlots.contains(s)) overwritable.add(s);
                }
                if (overwritable.isEmpty()) {
                    // Only slots holding other elite items remain; overwrite one of them.
                    for (int s = 0; s < size; s++) {
                        if (inv.getItem(s) != null) overwritable.add(s);
                    }
                }
                if (overwritable.isEmpty()) break;
                slot = overwritable.get(rand.nextInt(overwritable.size()));
                overwrote++;
            }
            inv.setItem(slot, item.clone());
            eliteSlots.add(slot);
            generated++;
        }
        GlovedPlugin.logDebug("Lootr elite items placed: requested=" + count + " placed=" + generated + " overwroteVanilla=" + overwrote + " inventorySize=" + size + " player=" + player.getName());
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
