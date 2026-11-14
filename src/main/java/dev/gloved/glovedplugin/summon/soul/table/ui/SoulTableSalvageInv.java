package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Salvage process: multi-select souls -> total yield -> double-confirm arm window -> confirm. */
public final class SoulTableSalvageInv implements SoulTableScreen {

    private static final int SIZE = 54;
    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;
    private static final int SLOT_BACK = 0;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_ESSENCE = 49;
    private static final int SLOT_CONFIRM = 50;
    private static final int SLOT_CLEAR = 51;
    private static final int SLOT_GUIDE = 52;
    private static final int SLOT_NEXT = 53;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final SoulTableConfig FALLBACK_CONFIG = new SoulTableConfig();

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final Inventory inventory;
    private final LinkedHashSet<UUID> selected = new LinkedHashSet<>();

    private int page = 0;
    private long salvageArmedUntil = 0L;
    private List<SoulInstance> allSouls = List.of();
    private Map<UUID, SoulInstance> soulIndex = Map.of();
    private int essence = 0;

    public SoulTableSalvageInv(GlovedPlugin plugin, Player player, SoulTableRow table) {
        this.plugin = plugin;
        this.viewerId = player.getUniqueId();
        this.tableId = table != null ? table.tableId() : null;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Soul Table - Salvage", NamedTextColor.LIGHT_PURPLE));
        refresh();
    }

    @Override public UUID getViewerId() { return viewerId; }
    @Override public UUID getTableId() { return tableId; }
    @Override public @NotNull Inventory getInventory() { return inventory; }

    @Override
    public void open(Player player) {
        if (player == null) return;
        refresh();
        player.openInventory(inventory);
    }

    private SoulTableConfig config() {
        SoulTableConfig cfg = plugin.getSoulService() != null ? plugin.getSoulService().tableConfig() : null;
        return cfg != null ? cfg : FALLBACK_CONFIG;
    }

    private SoulTableService service() { return plugin.getSoulTableService(); }

    private List<SoulInstance> selectedSouls() {
        List<SoulInstance> out = new ArrayList<>();
        for (UUID id : selected) {
            SoulInstance soul = soulIndex.get(id);
            if (soul != null) out.add(soul);
        }
        return out;
    }

    @Override
    public void refresh() {
        SoulTableData.Snapshot snap = SoulTableData.load(plugin.getSoulService(), viewerId);
        allSouls = snap.souls();
        soulIndex = snap.index();
        essence = snap.essence();
        SoulTableData.pruneSelection(selected, soulIndex);

        int maxPage = Math.max(0, (allSouls.size() - 1) / GRID_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        inventory.clear();
        for (int slot : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 46, 47, 48}) inventory.setItem(slot, SoulTableUiItems.filler());
        inventory.setItem(SLOT_BACK, SoulTableUiItems.backToHubItem(plugin));

        int from = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = from + i;
            if (index >= allSouls.size()) break;
            SoulInstance soul = allSouls.get(index);
            inventory.setItem(GRID_START + i, SoulTableUiItems.soulItem(plugin, soul, viewerId, selected.contains(soul.getSoulId())));
        }

        inventory.setItem(SLOT_PREV, SoulTableUiItems.actionItem(plugin, Material.ARROW, "§ePrevious Page",
                SoulTableUiKeys.ACTION_PREV, List.of("§7Page §f" + (page + 1) + "§7/§f" + (maxPage + 1))));
        inventory.setItem(SLOT_ESSENCE, essenceItem());
        inventory.setItem(SLOT_CONFIRM, confirmItem());
        inventory.setItem(SLOT_CLEAR, SoulTableUiItems.actionItem(plugin, Material.BARRIER, "§cClear Selection",
                SoulTableUiKeys.ACTION_CLEAR, List.of("§7Removes every selected soul.")));
        inventory.setItem(SLOT_GUIDE, SoulTableUiItems.actionItem(plugin, Material.BOOK, "§b§lSoul Guide",
                SoulTableUiKeys.ACTION_GUIDE, List.of("§7Learn about traits, bonuses, stats,",
                        "§7and what each table action does.", "", "§eClick to open")));
        inventory.setItem(SLOT_NEXT, SoulTableUiItems.actionItem(plugin, Material.ARROW, "§eNext Page",
                SoulTableUiKeys.ACTION_NEXT, List.of("§7Page §f" + (page + 1) + "§7/§f" + (maxPage + 1))));
    }

    private ItemStack essenceItem() {
        List<SoulInstance> chosen = selectedSouls();
        int total = 0;
        SoulService ss = plugin.getSoulService();
        for (SoulInstance soul : chosen) {
            total += ss != null ? ss.essenceValueOf(soul.getRarity()) : SoulService.essenceValue(soul.getRarity());
        }
        List<String> lore = new ArrayList<>();
        lore.add("§7Selected: §f" + chosen.size() + " §7soul(s)");
        lore.add("§7Value: §b" + total + " Essence");
        return SoulTableUiItems.infoItem(Material.AMETHYST_SHARD, "§b§lSoul Essence: §f" + essence, lore);
    }

    private ItemStack confirmItem() {
        List<SoulInstance> chosen = selectedSouls();
        boolean armed = System.currentTimeMillis() < salvageArmedUntil;
        boolean enabled = false;
        String name = "§7Confirm";
        List<String> lore = new ArrayList<>();

        if (chosen.isEmpty()) {
            lore.add("§7Select at least §f1§7 soul.");
        } else {
            int total = 0;
            SoulService ss = plugin.getSoulService();
            for (SoulInstance soul : chosen) {
                total += ss != null ? ss.essenceValueOf(soul.getRarity()) : SoulService.essenceValue(soul.getRarity());
            }
            if (armed) {
                enabled = true;
                name = "§c§lClick again to confirm";
                lore.add("§7Converts §f" + chosen.size() + "§7 soul(s) into §b" + total + " Essence§7.");
                long seconds = Math.max(0, (salvageArmedUntil - System.currentTimeMillis() + 999) / 1000);
                lore.add("§cExpires in " + seconds + "s");
            } else {
                enabled = true;
                name = "§e§lConfirm Salvage";
                lore.add("§7Converts §f" + chosen.size() + "§7 soul(s) into §b" + total + " Essence§7.");
                lore.add("§cClick twice to confirm.");
            }
        }

        Material material = armed ? Material.RED_CONCRETE : enabled ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (enabled) SoulTableUiItems.glint(meta);
            SoulTableUiKeys.tagAction(meta, plugin, SoulTableUiKeys.ACTION_CONFIRM);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void handleClick(Player player, int rawSlot, ClickType clickType) {
        if (player == null) return;
        if (!viewerId.equals(player.getUniqueId())) { player.closeInventory(); return; }
        if (rawSlot < 0 || rawSlot >= SIZE) return;
        ItemStack clicked = inventory.getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) return;

        String action = SoulTableUiKeys.readAction(plugin, clicked);
        if (action != null) {
            handleAction(player, action);
            return;
        }
        UUID soulId = SoulTableUiKeys.readSoulId(plugin, clicked);
        if (soulId != null) toggleSelection(player, soulId);
    }

    private void handleAction(Player player, String action) {
        switch (action) {
            case SoulTableUiKeys.ACTION_BACK_HUB -> SoulTableNav.openHub(plugin, player, tableId);
            case SoulTableUiKeys.ACTION_PREV -> { if (page > 0) page--; refresh(); }
            case SoulTableUiKeys.ACTION_NEXT -> {
                int maxPage = Math.max(0, (allSouls.size() - 1) / GRID_SIZE);
                if (page < maxPage) page++;
                refresh();
            }
            case SoulTableUiKeys.ACTION_CLEAR -> { selected.clear(); salvageArmedUntil = 0L; refresh(); }
            case SoulTableUiKeys.ACTION_GUIDE ->
                    SoulTableNav.openGuide(plugin, player, tableId, SoulTableGuideInv.Page.MAIN, SoulTableReturnTarget.SALVAGE);
            case SoulTableUiKeys.ACTION_CONFIRM -> confirm(player);
            default -> { }
        }
    }

    private void toggleSelection(Player player, UUID soulId) {
        SoulInstance soul = soulIndex.get(soulId);
        if (soul == null) return;
        if (!selected.remove(soulId)) selected.add(soulId);
        salvageArmedUntil = 0L;
        refresh();
    }

    private void confirm(Player player) {
        List<SoulInstance> chosen = selectedSouls();
        SoulTableService svc = service();
        if (svc == null) {
            SoulTableUiItems.actionBar(player, "Soul system is not initialized.", NamedTextColor.RED);
            return;
        }
        if (chosen.isEmpty()) {
            SoulTableUiItems.actionBar(player, "Select at least 1 soul.", NamedTextColor.RED);
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= salvageArmedUntil) {
            int seconds = Math.max(0, config().getSalvageConfirmationSeconds());
            if (seconds <= 0) {
                performSalvage(player, chosen);
            } else {
                salvageArmedUntil = now + seconds * 1000L;
                refresh();
                Bukkit.getScheduler().runTaskLater(plugin, this::refreshIfOpen, seconds * 20L);
            }
        } else {
            performSalvage(player, chosen);
        }
    }

    private void performSalvage(Player player, List<SoulInstance> chosen) {
        List<UUID> ids = new ArrayList<>();
        for (SoulInstance soul : chosen) ids.add(soul.getSoulId());
        SoulTableService svc = service();
        if (svc == null) {
            SoulTableUiItems.actionBar(player, "Soul system is not initialized.", NamedTextColor.RED);
            return;
        }
        SoulTableService.TableResult result = svc.salvage(viewerId, ids);
        salvageArmedUntil = 0L;
        if (result == null) {
            SoulTableUiItems.actionBar(player, "Operation failed.", NamedTextColor.RED);
            return;
        }
        if (!result.ok()) {
            SoulTableUiItems.actionBar(player, result.message(), NamedTextColor.RED);
            refresh();
            return;
        }
        selected.clear();
        player.sendMessage(SoulTableUiItems.chatPrefix().append(Component.text(result.message(), NamedTextColor.GRAY)));
        SoulTableUiItems.actionBar(player, "Souls salvaged.", NamedTextColor.GREEN);
        refresh();
    }

    private void refreshIfOpen() {
        Player player = Bukkit.getPlayer(viewerId);
        if (player == null) return;
        if (player.getOpenInventory().getTopInventory().getHolder() != this) return;
        refresh();
    }
}
