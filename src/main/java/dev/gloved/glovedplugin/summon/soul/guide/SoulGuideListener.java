package dev.gloved.glovedplugin.summon.soul.guide;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.table.ui.SoulTableGuideInv;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;

/** Grants the Soul Guide once per player on join and opens the guide GUI when the item is used. */
public final class SoulGuideListener implements Listener {

    private final GlovedPlugin plugin;

    public SoulGuideListener(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        var key = SoulGuideItem.receivedKey(plugin);
        var pdc = player.getPersistentDataContainer();
        if (pdc.has(key, PersistentDataType.BYTE)) return;

        ItemStack guide = SoulGuideItem.create(plugin);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(guide);
        boolean dropped = false;
        if (leftover != null && !leftover.isEmpty()) {
            for (ItemStack stack : leftover.values()) {
                if (stack == null || stack.getType().isAir()) continue;
                player.getWorld().dropItemNaturally(player.getLocation(), stack);
                dropped = true;
            }
        }
        // Mark as received so the guide is only granted once per player.
        pdc.set(key, PersistentDataType.BYTE, (byte) 1);

        if (dropped) {
            player.sendMessage(Component.text("Your Soul Guide was dropped at your feet (inventory full).", NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!SoulGuideItem.isSoulGuide(plugin, item)) {
            // Also check off-hand if main-hand item wasn't the guide (some cases)
            if (event.getHand() != EquipmentSlot.OFF_HAND) {
                ItemStack off = player.getInventory().getItemInOffHand();
                if (SoulGuideItem.isSoulGuide(plugin, off)) {
                    item = off;
                } else {
                    return;
                }
            } else {
                return;
            }
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () ->
                new SoulTableGuideInv(plugin, player, null, SoulTableGuideInv.Page.MAIN).open(player));
    }
}
