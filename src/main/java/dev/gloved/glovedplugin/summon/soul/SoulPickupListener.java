package dev.gloved.glovedplugin.summon.soul;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Soul drop items are picked up normally into the player's inventory.
 * Right-clicking the item in inventory adds it to the Soul Vault
 * (owner claims it, non-owners transfer ownership on claim).
 *
 * <p>A background scan re-applies nametag + glow to any soul drop item
 * sitting on the ground, ensuring dropped soul items are always visible
 * and identifiable even if created before the plugin loaded or the
 * display state was lost.</p>
 */
public class SoulPickupListener implements Listener {
    private final GlovedPlugin plugin;
    private BukkitTask scanTask;
    /** Items already given a nametag+glow this scan cycle, to avoid re-setting every tick. */
    private final Set<UUID> tagged = new HashSet<>();

    public SoulPickupListener(GlovedPlugin plugin) {
        this.plugin = plugin;
        startScan();
    }

    /** Scan all loaded worlds for soul drop items on the ground and apply nametag + glow. */
    private void startScan() {
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            tagged.clear();
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                for (Entity e : world.getEntities()) {
                    if (!(e instanceof Item item)) continue;
                    ItemStack stack = item.getItemStack();
                    if (!SoulDropItem.isSoulDrop(plugin, stack)) continue;
                    UUID id = item.getUniqueId();
                    if (!tagged.add(id)) continue;
                    // Glow
                    try { if (!item.isGlowing()) item.setGlowing(true); } catch (Throwable ignored) {}
                    // Nametag
                    SoulInstance soul = SoulDropItem.readSoul(plugin, stack);
                    String name = soul != null ? SoulDropItem.getDisplayName(soul) : "Soul";
                    try {
                        if (!java.util.Objects.equals(name, item.getCustomName())) {
                            item.setCustomName(name);
                            item.setCustomNameVisible(true);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
        // Do not cancel: let the item go into inventory via vanilla pickup.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        // Do not cancel: let the item go into inventory via vanilla pickup.
    }
}
