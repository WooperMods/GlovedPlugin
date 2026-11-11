package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.table.ui.SoulTableInv;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;

/**
 * Block / interaction plumbing for placed Soul Tables.
 *
 * <p>Only registered positions are affected; ordinary Smithing Tables on the server keep their
 * vanilla model and GUI.</p>
 */
public final class SoulTableListener implements Listener {

    private final GlovedPlugin plugin;
    private final SoulTableManager manager;

    public SoulTableListener(GlovedPlugin plugin, SoulTableManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private SoulTableConfig config() {
        var summon = plugin.getSummonConfig();
        return summon != null ? summon.getSoulTableConfig() : null;
    }

    // ------------------------------------------------------------------ placement

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        SoulTableConfig cfg = config();
        if (cfg != null && !cfg.isEnabled()) return;
        ItemStack item = event.getItemInHand();
        if (!SoulTableItem.isSoulTable(plugin, item)) return;
        Block block = event.getBlockPlaced();
        if (block.getType() != Material.SMITHING_TABLE) return;

        try {
            manager.register(block, event.getPlayer().getUniqueId());
            event.getPlayer().sendActionBar(Component.text("Soul Table placed", NamedTextColor.LIGHT_PURPLE));
        } catch (SQLException | RuntimeException e) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("Could not place Soul Table (database error)", NamedTextColor.RED));
            plugin.getLogger().warning("[SoulTable] Placement failed at " + block.getLocation() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ breaking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        SoulTableRow row = manager.getAt(block);
        if (row == null) return;

        Player player = event.getPlayer();
        SoulTableConfig cfg = config();
        if (cfg != null && cfg.isBreakOwnerOnly()
                && !row.ownerId().equals(player.getUniqueId())
                && !player.hasPermission("gloved.soul.table.break")) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Only the owner can break this Soul Table", NamedTextColor.RED));
            return;
        }

        if (!manager.unregister(row.tableId())) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Could not remove Soul Table (database error)", NamedTextColor.RED));
            return;
        }

        // Suppress the vanilla Smithing Table drop and give back the tagged station item instead.
        event.setDropItems(false);
        Location center = SoulTableManager.center(row);
        if (center != null && center.getWorld() != null) {
            center.getWorld().dropItemNaturally(center, SoulTableItem.create(plugin));
        }
        player.sendActionBar(Component.text("Soul Table removed", NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        SoulTableRow row = manager.getAt(block);
        if (row == null) return;

        event.setCancelled(true);
        open(event.getPlayer(), row);
    }

    /** Clicks on the overlay display are treated like clicks on the block. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractOverlay(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (!(event.getRightClicked() instanceof ItemDisplay)) return;
        SoulTableRow row = manager.getByOverlay(event.getRightClicked());
        if (row == null) return;
        event.setCancelled(true);
        open(event.getPlayer(), row);
    }

    private void open(Player player, SoulTableRow row) {
        SoulTableConfig cfg = config();
        if (cfg != null && !cfg.isEnabled()) {
            player.sendActionBar(Component.text("Soul Tables are disabled", NamedTextColor.RED));
            return;
        }
        if (cfg != null && !cfg.isUsePublic()
                && !row.ownerId().equals(player.getUniqueId())
                && !player.hasPermission("gloved.soul.table.break")) {
            player.sendActionBar(Component.text("This Soul Table is private", NamedTextColor.RED));
            return;
        }
        new SoulTableInv(plugin, player, row).open(player);
    }

    /**
     * Covers plugins/commands that open the vanilla Smithing GUI for a registered block directly.
     * Our own GUI is a plain chest inventory, so it never reaches this branch.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.SMITHING) return;
        Location location = event.getInventory().getLocation();
        if (location == null) return;
        if (manager.getAt(location) == null) return;
        event.setCancelled(true);
    }

    // ------------------------------------------------------------------ chunk lifecycle

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.handleChunkLoad(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.handleChunkUnload(event.getChunk());
    }
}
