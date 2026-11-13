package dev.gloved.glovedplugin.summon.soul.item;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv;

public class SoulItemListener implements Listener {
    private final GlovedPlugin plugin;

    public SoulItemListener(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        SoulItem.give(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> SoulItem.give(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(SoulItem::isSoulItem);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = event.getItemDrop().getItemStack();

        if (SoulItem.isSoulItem(stack)) {
            event.setCancelled(true);
            SoulItem.give(player);
            player.updateInventory();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (SoulItem.isSoulItem(event.getMainHandItem()) || SoulItem.isSoulItem(event.getOffHandItem())) {
            event.setCancelled(true);
            event.getPlayer().updateInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // Soul Focus item: prevent moving/placing
        if (SoulItem.isSoulItem(current) || SoulItem.isSoulItem(cursor)) {
            event.setCancelled(true);
            player.setItemOnCursor(null);
            plugin.getServer().getScheduler().runTask(plugin, () -> SoulItem.give(player));
            player.updateInventory();
        }
        if (event.getHotbarButton() >= 0) {
            ItemStack hb = player.getInventory().getItem(event.getHotbarButton());
            if (SoulItem.isSoulItem(hb)) {
                event.setCancelled(true);
                plugin.getServer().getScheduler().runTask(plugin, () -> SoulItem.give(player));
                player.updateInventory();
            }
        }

        // Right-click a physical soul item in inventory to add it to the Soul Vault
        // (exclude Soul Vault GUI where clicks are handled separately)
        if (event.isCancelled()) return;
        ItemStack clicked = event.getCurrentItem();
        if (SoulDropItem.isSoulDrop(plugin, clicked)) {
            try { SoulDropItem.refresh(plugin, clicked); event.setCurrentItem(clicked); } catch (Throwable ignored) {}
        }
        if (event.getView().getTopInventory().getHolder() instanceof SoulVaultInv) return;
        if (event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) return;
        if (!SoulDropItem.isSoulDrop(plugin, clicked)) return;

        event.setCancelled(true);
        SoulService svc = plugin.getSoulService();
        SoulInstance soul = SoulDropItem.readSoul(plugin, clicked);
        UUID ownerId = SoulDropItem.readOwner(plugin, clicked);
        if (svc == null || soul == null || ownerId == null) {
            player.sendMessage("§c[Soul] Could not add that soul to your vault.");
            return;
        }
        boolean claimedFromOther = !player.getUniqueId().equals(ownerId);
        try {
            SoulService.CollectResult res = svc.collectDroppedSoul(player, soul);
            if (res.stored()) {
                int amt = clicked.getAmount();
                if (amt <= 1) event.setCurrentItem(null);
                else { clicked.setAmount(amt - 1); event.setCurrentItem(clicked); }
                player.sendMessage("§d[Soul] §a" + SoulService.describe(soul) + " §7" + (claimedFromOther ? "claimed and added to your Soul Vault." : "added to your Soul Vault."));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
            } else if (res.convertedToEssence()) {
                int amt = clicked.getAmount();
                if (amt <= 1) event.setCurrentItem(null);
                else { clicked.setAmount(amt - 1); event.setCurrentItem(clicked); }
                player.sendMessage("§d[Soul] §7Vault full: " + SoulService.describe(soul) + " → §b+" + res.essence() + " Essence");
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 0.9f);
            } else {
                player.sendMessage("§c[Soul] Could not add soul to vault (" + res.reason() + ").");
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Souls] Inventory right-click vault add failed: " + t);
            player.sendMessage("§c[Soul] Could not add that soul to your vault.");
        }
        player.updateInventory();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (SoulItem.isSoulItem(event.getOldCursor())) {
            event.setCancelled(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> SoulItem.give(player));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        try {
            if (SoulDropItem.isSoulDrop(plugin, event.getItemInHand())) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        try {
            ItemStack item = event.getHand() == EquipmentSlot.HAND
                    ? event.getPlayer().getInventory().getItemInMainHand()
                    : event.getPlayer().getInventory().getItemInOffHand();
            if (SoulDropItem.isSoulDrop(plugin, item)) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == null) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            // fallback
            item = event.getHand() == EquipmentSlot.HAND
                    ? player.getInventory().getItemInMainHand()
                    : player.getInventory().getItemInOffHand();
        }

        // Soul Focus item behavior
        if (SoulItem.isSoulItem(item)) {
            if (event.getHand() != EquipmentSlot.HAND) { event.setCancelled(true); return; }
            Action a = event.getAction();
            if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
                event.setCancelled(true);
                SoulItem.openVault(plugin, player);
                return;
            }
            if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
                SoulService svc = plugin.getSoulService();
                if (svc == null) {
                    player.sendMessage("§cSoul system unavailable.");
                    return;
                }
                svc.castSelected(player);
            }
            return;
        }

        // Right-click a physical soul item to add it to the Soul Vault (and prevent placement/use)
        if (SoulDropItem.isSoulDrop(plugin, item)) {
            // refresh legacy lore/name in-place
            try {
                SoulDropItem.refresh(plugin, item);
                if (event.getHand() == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(item);
                else player.getInventory().setItemInOffHand(item);
            } catch (Throwable ignored) {}

            Action a = event.getAction();
            if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
                // still prevent accidental use/placement interactions
                event.setCancelled(true);
                return;
            }
            event.setCancelled(true);
            SoulService svc = plugin.getSoulService();
            SoulInstance soul = SoulDropItem.readSoul(plugin, item);
            UUID ownerId = SoulDropItem.readOwner(plugin, item);
            if (svc == null || soul == null || ownerId == null) {
                player.sendMessage("§c[Soul] Could not add that soul to your vault.");
                return;
            }
            boolean claimedFromOther = !player.getUniqueId().equals(ownerId);
            try {
                SoulService.CollectResult res = svc.collectDroppedSoul(player, soul);
                if (res.stored()) {
                    consumeHeldSoulItem(player, item, event.getHand());
                    player.sendMessage("§d[Soul] §a" + SoulService.describe(soul) + " §7" + (claimedFromOther ? "claimed and added to your Soul Vault." : "added to your Soul Vault."));
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
                } else if (res.convertedToEssence()) {
                    consumeHeldSoulItem(player, item, event.getHand());
                    player.sendMessage("§d[Soul] §7Vault full: " + SoulService.describe(soul) + " → §b+" + res.essence() + " Essence");
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 0.9f);
                } else {
                    player.sendMessage("§c[Soul] Could not add soul to vault (" + res.reason() + ").");
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[Souls] Right-click vault add failed: " + t);
                player.sendMessage("§c[Soul] Could not add that soul to your vault.");
            }
            player.updateInventory();
        }
    }

    private void consumeHeldSoulItem(Player player, ItemStack handItem, EquipmentSlot hand) {
        try {
            if (player == null || handItem == null) return;
            if (hand == EquipmentSlot.OFF_HAND) {
                ItemStack off = player.getInventory().getItemInOffHand();
                if (off != null && SoulDropItem.isSoulDrop(plugin, off)) {
                    int amt = off.getAmount();
                    if (amt <= 1) player.getInventory().setItemInOffHand(null);
                    else { off.setAmount(amt - 1); player.getInventory().setItemInOffHand(off); }
                    return;
                }
            } else {
                ItemStack main = player.getInventory().getItemInMainHand();
                if (main != null && SoulDropItem.isSoulDrop(plugin, main)) {
                    int amt = main.getAmount();
                    if (amt <= 1) player.getInventory().setItemInMainHand(null);
                    else { main.setAmount(amt - 1); player.getInventory().setItemInMainHand(main); }
                    return;
                }
            }
            // fallback: remove one matching soul drop
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack it = player.getInventory().getItem(i);
                if (it != null && SoulDropItem.isSoulDrop(plugin, it)) {
                    if (it.isSimilar(handItem)) {
                        int amt = it.getAmount();
                        if (amt <= 1) player.getInventory().setItem(i, null);
                        else { it.setAmount(amt - 1); player.getInventory().setItem(i, it); }
                        return;
                    }
                }
            }
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack it = player.getInventory().getItem(i);
                if (it != null && SoulDropItem.isSoulDrop(plugin, it)) {
                    int amt = it.getAmount();
                    if (amt <= 1) player.getInventory().setItem(i, null);
                    else { it.setAmount(amt - 1); player.getInventory().setItem(i, it); }
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}
