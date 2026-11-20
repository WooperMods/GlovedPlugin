package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.item.SoulLoreBuilder;
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

/** Enhance process: select 1 soul -> preview level/cost/stats -> confirm. */
public final class SoulTableEnhanceInv implements SoulTableScreen {

    private static final int SIZE = 54;
    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;
    private static final int SLOT_BACK = 0;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_SEL_1 = 46;
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
    private List<SoulInstance> allSouls = List.of();
    private Map<UUID, SoulInstance> soulIndex = Map.of();
    private int essence = 0;

    public SoulTableEnhanceInv(GlovedPlugin plugin, Player player, SoulTableRow table) {
        this.plugin = plugin;
        this.viewerId = player.getUniqueId();
        this.tableId = table != null ? table.tableId() : null;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Soul Table - Enhance", NamedTextColor.LIGHT_PURPLE));
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
        for (int slot : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 47, 48}) inventory.setItem(slot, SoulTableUiItems.filler());
        inventory.setItem(SLOT_BACK, SoulTableUiItems.backToHubItem(plugin));

        int from = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = from + i;
            if (index >= allSouls.size()) break;
            SoulInstance soul = allSouls.get(index);
            inventory.setItem(GRID_START + i, SoulTableUiItems.soulItem(plugin, soul, viewerId, selected.contains(soul.getSoulId())));
        }

        inventory.setItem(SLOT_PREV, SoulTableUiItems.pageNavItem(plugin, false, page > 0, page, maxPage, null));
        List<SoulInstance> chosen = selectedSouls();
        inventory.setItem(SLOT_SEL_1, SoulTableUiItems.selectionSlotItem(plugin, chosen.size() > 0 ? chosen.get(0) : null, viewerId, 0));
        inventory.setItem(SLOT_ESSENCE, essenceItem(chosen));
        inventory.setItem(SLOT_CONFIRM, confirmItem(chosen));
        inventory.setItem(SLOT_CLEAR, SoulTableUiItems.actionItem(plugin, Material.BARRIER, "§cClear Selection",
                SoulTableUiKeys.ACTION_CLEAR, List.of("§7Removes every selected soul.")));
        inventory.setItem(SLOT_GUIDE, SoulTableUiItems.actionItem(plugin, Material.BOOK, "§b§lSoul Guide",
                SoulTableUiKeys.ACTION_GUIDE, List.of("§7Learn about traits, bonuses, stats,",
                        "§7and what each table action does.", "", "§eClick to open")));
        inventory.setItem(SLOT_NEXT, SoulTableUiItems.pageNavItem(plugin, true, page < maxPage, page, maxPage, null));
    }

    private ItemStack essenceItem(List<SoulInstance> chosen) {
        int level = chosen.size() == 1 ? chosen.get(0).getEnhanceLevel() : 0;
        int cost = service() != null ? service().essenceCostEnhance(level) : config().enhanceCost(level);
        List<String> lore = new ArrayList<>();
        lore.add("§7Cost: §b" + SoulTableUiItems.costLabel(cost));
        lore.add("§7Pick one soul, then confirm the level-up.");
        return SoulTableUiItems.infoItem(Material.AMETHYST_SHARD, "§b§lSoul Essence: §f" + essence, lore);
    }

    private ItemStack confirmItem(List<SoulInstance> chosen) {
        SoulTableConfig cfg = config();
        SoulTableService svc = service();
        boolean enabled = false;
        String name = "§7Confirm";
        List<String> lore = new ArrayList<>();

        if (chosen.size() != 1) {
            lore.add("§7Select exactly §f1§7 soul.");
        } else {
            SoulInstance soul = chosen.get(0);
            int level = soul.getEnhanceLevel();
            if (level >= cfg.getEnhanceMaxLevel()) {
                lore.add("§cMaximum soul level reached.");
            } else {
                int cost = svc != null ? svc.essenceCostEnhance(level) : cfg.enhanceCost(level);
                lore.add("§7Level: §f" + level + " §7→ §f" + (level + 1));
                lore.add("§7Bonus: §a+" + SoulLoreBuilder.fmt(cfg.getEnhancePerLevelBonus() * 100.0, 1) + "% base stats per level");
                SoulService ss = plugin.getSoulService();
                if (ss != null) {
                    SoulInstance preview = soul.withEnhanceLevel(level + 1);
                    double hp = ss.computeSpawnedMaxHealth(viewerId, preview);
                    double atk = ss.estimateAverageHitDamage(viewerId, preview);
                    SoulBaseStats pBase = ss.effectiveBaseStats(preview);
                    String mv = pBase.movementSpeedTierLabel();
                    double aps = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS,
                            Math.min(SoulBaseStats.MAX_ATTACK_SPEED_APS, pBase.attackSpeed()));
                    double def = ss.computeSpawnedDefense(preview);
                    int cap = ss.effectiveGroupCapacity(preview);
                    lore.add("§7Preview (next summon):");
                    lore.add("§e " + SoulLoreBuilder.fmt(hp, 2) + " §7HP  §9" + SoulLoreBuilder.fmt(def, 2) + " §7DEF");
                    lore.add("§c " + SoulLoreBuilder.fmt(atk, 2) + " §7ATK  §b" + mv + " §7MV SPD");
                    lore.add("§a " + SoulLoreBuilder.fmt(aps, 2) + " §7APS  §3" + cap + " §7Summons");
                }
                lore.add("§7Cost: §b" + SoulTableUiItems.costLabel(cost));
                if (essence < cost) lore.add("§cNot enough Essence");
                else enabled = true;
            }
        }

        Material material = enabled ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE;
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
            case SoulTableUiKeys.ACTION_CLEAR -> { selected.clear(); refresh(); }
            case SoulTableUiKeys.ACTION_GUIDE ->
                    SoulTableNav.openGuide(plugin, player);
            case SoulTableUiKeys.ACTION_CONFIRM -> confirm(player);
            default -> { }
        }
    }

    private void toggleSelection(Player player, UUID soulId) {
        SoulInstance soul = soulIndex.get(soulId);
        if (soul == null) return;
        if (selected.contains(soulId)) selected.remove(soulId);
        else {
            selected.clear();
            selected.add(soulId);
        }
        refresh();
    }

    private void confirm(Player player) {
        List<SoulInstance> chosen = selectedSouls();
        SoulTableService svc = service();
        if (svc == null) {
            SoulTableUiItems.actionBar(player, "Soul system is not initialized.", NamedTextColor.RED);
            return;
        }
        if (chosen.size() != 1) {
            SoulTableUiItems.actionBar(player, "Select exactly 1 soul.", NamedTextColor.RED);
            return;
        }
        SoulTableService.TableResult result = svc.enhance(viewerId, chosen.get(0).getSoulId());
        finishEnhance(player, result);
    }

    private void finish(Player player, SoulTableService.TableResult result, String successMessage, boolean clear) {
        if (result == null) {
            SoulTableUiItems.actionBar(player, "Operation failed.", NamedTextColor.RED);
            return;
        }
        if (!result.ok()) {
            SoulTableUiItems.actionBar(player, result.message(), NamedTextColor.RED);
            refresh();
            return;
        }
        if (clear) selected.clear();
        player.sendMessage(SoulTableUiItems.chatPrefix().append(Component.text(result.message(), NamedTextColor.GRAY)));
        SoulTableUiItems.actionBar(player, successMessage, NamedTextColor.GREEN);
        refresh();
    }

    private void finishEnhance(Player player, SoulTableService.TableResult result) {
        if (result == null) {
            SoulTableUiItems.actionBar(player, "Operation failed.", NamedTextColor.RED);
            return;
        }
        if (!result.ok()) {
            SoulTableUiItems.actionBar(player, result.message(), NamedTextColor.RED);
            refresh();
            return;
        }
        UUID keepSelected = null;
        SoulInstance resultSoul = result.result();
        if (resultSoul != null) {
            keepSelected = resultSoul.getSoulId();
        } else if (!selected.isEmpty()) {
            keepSelected = selected.iterator().next();
        }
        player.sendMessage(SoulTableUiItems.chatPrefix().append(Component.text(result.message(), NamedTextColor.GRAY)));
        SoulTableUiItems.actionBar(player, "Soul enhanced.", NamedTextColor.GREEN);
        refresh();
        // Keep the enhanced soul selected so it can be leveled again without reselecting.
        selected.clear();
        if (keepSelected != null && soulIndex.containsKey(keepSelected)) {
            selected.add(keepSelected);
            refresh();
        }
    }
}
