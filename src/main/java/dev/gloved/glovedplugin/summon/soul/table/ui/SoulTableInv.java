package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixRoll;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixType;
import dev.gloved.glovedplugin.summon.soul.item.SoulDropItem;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.item.SoulLoreBuilder;
import dev.gloved.glovedplugin.summon.soul.domain.SoulQuality;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository;

/**
 * 54-slot Soul Table GUI with Combine / Enhance / Reforge / Bulk Salvage tabs.
 *
 * <p>Unlike the vault, this menu rebuilds its existing {@link Inventory} in place so selection and
 * tab state survive refreshes. Nothing is consumed until a confirm action succeeds.</p>
 */
public final class SoulTableInv implements InventoryHolder {

    public enum Tab {
        COMBINE("Combine", Material.ANVIL),
        ENHANCE("Enhance", Material.EXPERIENCE_BOTTLE),
        REFORGE("Reforge", Material.GRINDSTONE),
        SALVAGE("Salvage", Material.HOPPER);

        private final String label;
        private final Material icon;

        Tab(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private static final int SIZE = 54;
    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 36;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_SEL_1 = 46;
    private static final int SLOT_SEL_2 = 47;
    private static final int SLOT_SEL_3 = 48;
    private static final int SLOT_ESSENCE = 49;
    private static final int SLOT_CONFIRM = 50;
    private static final int SLOT_CLEAR = 51;
    private static final int SLOT_GUIDE = 52;
    private static final int SLOT_INFO = 8;
    private static final int SLOT_NEXT = 53;
    private static final int[] TAB_SLOTS = {0, 2, 4, 6};
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final SoulTableConfig FALLBACK_CONFIG = new SoulTableConfig();

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final Inventory inventory;
    private final LinkedHashSet<UUID> selected = new LinkedHashSet<>();

    private Tab tab = Tab.COMBINE;
    private int page = 0;
    private long salvageArmedUntil = 0L;
    private List<SoulInstance> allSouls = List.of();
    private Map<UUID, SoulInstance> soulIndex = Map.of();
    private int essence = 0;

    public SoulTableInv(GlovedPlugin plugin, Player player, SoulTableRow table) {
        this.plugin = plugin;
        this.viewerId = player.getUniqueId();
        this.tableId = table != null ? table.tableId() : null;
        this.tab = loadLastTab();
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Soul Table", NamedTextColor.LIGHT_PURPLE));
        refresh();
    }

    private Tab loadLastTab() {
        try {
            SoulService svc = plugin.getSoulService();
            if (svc == null) return Tab.COMBINE;
            String raw = svc.getSoulTableTab(viewerId);
            if (raw == null) return Tab.COMBINE;
            return Tab.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            return Tab.COMBINE;
        }
    }

    private void saveLastTabAsync(Tab tab) {
        if (tab == null || plugin == null) return;
        final UUID owner = viewerId;
        final String name = tab.name();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SoulService svc = plugin.getSoulService();
                if (svc != null) svc.setSoulTableTab(owner, name);
            } catch (Throwable ignored) {}
        });
    }

    public static NamespacedKey soulIdKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_table_soul_id");
    }

    public static NamespacedKey actionKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_table_action");
    }

    public UUID getViewerId() {
        return viewerId;
    }

    public UUID getTableId() {
        return tableId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        if (player == null) return;
        refresh();
        player.openInventory(inventory);
    }

    // ------------------------------------------------------------------ data

    private SoulTableConfig config() {
        SoulTableConfig cfg = plugin.getSoulService() != null ? plugin.getSoulService().tableConfig() : null;
        return cfg != null ? cfg : FALLBACK_CONFIG;
    }

    private SoulTableService service() {
        return plugin.getSoulTableService();
    }

    private void loadData() {
        SoulService svc = plugin.getSoulService();
        if (svc == null) {
            allSouls = List.of();
            soulIndex = Map.of();
            essence = 0;
            selected.clear();
            return;
        }
        List<SoulInstance> souls = new ArrayList<>();
        for (int p = 0; p < 40; p++) {
            List<SoulInstance> chunk = svc.listSoulsFiltered(viewerId, null, null, p, 50);
            souls.addAll(chunk);
            if (chunk.size() < 50) break;
        }
        souls.sort(Comparator
                .comparingInt((SoulInstance s) -> s.getRarity().ordinal()).reversed()
                .thenComparing(Comparator.comparingDouble((SoulInstance s) -> SoulQuality.percent(s)).reversed())
                .thenComparing(Comparator.comparingLong((SoulInstance s) -> s.getCreatedAt()).reversed()));
        allSouls = souls;
        Map<UUID, SoulInstance> index = new HashMap<>();
        for (SoulInstance soul : souls) index.put(soul.getSoulId(), soul);
        soulIndex = index;
        essence = svc.getEssence(viewerId);
        selected.removeIf(id -> !index.containsKey(id));
    }

    private List<SoulInstance> selectedSouls() {
        List<SoulInstance> out = new ArrayList<>();
        for (UUID id : selected) {
            SoulInstance soul = soulIndex.get(id);
            if (soul != null) out.add(soul);
        }
        return out;
    }

    // ------------------------------------------------------------------ rendering

    public void refresh() {
        loadData();
        int maxPage = Math.max(0, (allSouls.size() - 1) / GRID_SIZE);
        if (page > maxPage) page = maxPage;
        if (page < 0) page = 0;

        inventory.clear();

        for (int i = 0; i < TAB_SLOTS.length; i++) {
            inventory.setItem(TAB_SLOTS[i], tabItem(Tab.values()[i]));
        }
        for (int slot : new int[]{1, 3, 5, 7}) inventory.setItem(slot, filler());
        inventory.setItem(SLOT_INFO, infoItem());

        int from = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = from + i;
            if (index >= allSouls.size()) break;
            SoulInstance soul = allSouls.get(index);
            inventory.setItem(GRID_START + i, soulItem(soul, selected.contains(soul.getSoulId())));
        }

        inventory.setItem(SLOT_PREV, actionItem(Material.ARROW, "§ePrevious Page", "prev", List.of(
                "§7Page §f" + (page + 1) + "§7/§f" + (maxPage + 1))));
        inventory.setItem(SLOT_SEL_1, selectionItem(0));
        inventory.setItem(SLOT_SEL_2, selectionItem(1));
        inventory.setItem(SLOT_SEL_3, selectionItem(2));
        inventory.setItem(SLOT_ESSENCE, essenceItem());
        inventory.setItem(SLOT_CONFIRM, confirmItem());
        inventory.setItem(SLOT_CLEAR, actionItem(Material.BARRIER, "§cClear Selection", "clear",
                List.of("§7Removes every selected soul.")));
        inventory.setItem(SLOT_GUIDE, actionItem(Material.BOOK, "§bSoul Guide", "guide",
                List.of("§7View traits, affixes, stats, and table info.",
                        "§eClick to open.")));
        inventory.setItem(SLOT_NEXT, actionItem(Material.ARROW, "§eNext Page", "next", List.of(
                "§7Page §f" + (page + 1) + "§7/§f" + (maxPage + 1))));
    }

    private ItemStack tabItem(Tab value) {
        boolean active = value == tab;
        ItemStack item = new ItemStack(value.icon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize((active ? "§d§l" : "§7") + value.label)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(LEGACY.deserialize(active ? "§aSelected" : "§7Click to open").decoration(TextDecoration.ITALIC, false));
            switch (value) {
                case COMBINE -> lore.add(LEGACY.deserialize("§7Combine 3 same-family, same-rarity souls.").decoration(TextDecoration.ITALIC, false));
                case ENHANCE -> lore.add(LEGACY.deserialize("§7Raise one soul's level (max " + config().getEnhanceMaxLevel() + ").").decoration(TextDecoration.ITALIC, false));
                case REFORGE -> lore.add(LEGACY.deserialize("§7Reroll a soul's affixes at its rarity.").decoration(TextDecoration.ITALIC, false));
                case SALVAGE -> lore.add(LEGACY.deserialize("§7Convert any selected souls into Essence.").decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (active) glint(meta);
            meta.getPersistentDataContainer().set(actionKey(plugin), PersistentDataType.STRING, "tab:" + value.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§d§lSoul Table").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(LEGACY.deserialize("§7Essence: §b" + essence).decoration(TextDecoration.ITALIC, false));
            lore.add(LEGACY.deserialize("§7Vault souls: §f" + allSouls.size()).decoration(TextDecoration.ITALIC, false));
            lore.add(LEGACY.deserialize("§7Tab: §f" + tab.label).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(LEGACY.deserialize(hintFor(tab)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String hintFor(Tab tab) {
        return switch (tab) {
            case COMBINE -> "§7Pick 3 souls of one family + rarity.";
            case ENHANCE -> "§7Pick one soul, then confirm the level-up.";
            case REFORGE -> "§7Pick one soul; affixes are rerolled.";
            case SALVAGE -> "§7Pick any souls, then confirm twice.";
        };
    }

    private ItemStack selectionItem(int index) {
        List<SoulInstance> chosen = selectedSouls();
        if (index >= chosen.size()) {
            ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(LEGACY.deserialize("§8Empty slot " + (index + 1)).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(LEGACY.deserialize("§7Pick a soul from the grid.").decoration(TextDecoration.ITALIC, false)));
                item.setItemMeta(meta);
            }
            return item;
        }
        SoulInstance soul = chosen.get(index);
        ItemStack item = soulItem(soul, true);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.lore();
            if (lore == null) lore = new ArrayList<>();
            else lore = new ArrayList<>(lore);
            lore.add(Component.empty());
            lore.add(LEGACY.deserialize("§cClick to deselect").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(soulIdKey(plugin), PersistentDataType.STRING, soul.getSoulId().toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack essenceItem() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize("§b§lSoul Essence: §f" + essence).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            List<SoulInstance> chosen = selectedSouls();
            switch (tab) {
                case COMBINE -> {
                    SoulRarity target = chosen.size() == 3 ? config().nextRarity(chosen.get(0).getRarity()) : null;
                    int cost = target != null ? service() != null ? service().essenceCostCombine(target) : config().combineCost(target) : Integer.MAX_VALUE;
                    lore.add(LEGACY.deserialize("§7Cost: §b" + costLabel(cost)).decoration(TextDecoration.ITALIC, false));
                    lore.add(LEGACY.deserialize("§7Target: §f" + (target != null ? target.name() : "-")).decoration(TextDecoration.ITALIC, false));
                }
                case ENHANCE -> {
                    int level = chosen.size() == 1 ? chosen.get(0).getEnhanceLevel() : 0;
                    int cost = service() != null ? service().essenceCostEnhance(level) : config().enhanceCost(level);
                    lore.add(LEGACY.deserialize("§7Cost: §b" + costLabel(cost)).decoration(TextDecoration.ITALIC, false));
                }
                case REFORGE -> {
                    SoulRarity rarity = chosen.size() == 1 ? chosen.get(0).getRarity() : null;
                    int cost = rarity != null ? service() != null ? service().essenceCostReforge(rarity) : config().reforgeCost(rarity) : Integer.MAX_VALUE;
                    lore.add(LEGACY.deserialize("§7Cost: §b" + costLabel(cost)).decoration(TextDecoration.ITALIC, false));
                }
                case SALVAGE -> {
                    int total = 0;
                    for (SoulInstance soul : chosen) {
                        SoulService ss = plugin.getSoulService();
                        total += ss != null ? ss.essenceValueOf(soul.getRarity()) : SoulService.essenceValue(soul.getRarity());
                    }
                    lore.add(LEGACY.deserialize("§7Selected: §f" + chosen.size() + " §7soul(s)").decoration(TextDecoration.ITALIC, false));
                    lore.add(LEGACY.deserialize("§7Value: §b" + total + " Essence").decoration(TextDecoration.ITALIC, false));
                }
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack confirmItem() {
        List<SoulInstance> chosen = selectedSouls();
        boolean enabled = false;
        boolean armed = tab == Tab.SALVAGE && System.currentTimeMillis() < salvageArmedUntil;
        String name = "§7Confirm";
        List<String> lore = new ArrayList<>();
        SoulTableConfig cfg = config();
        SoulTableService svc = service();

        switch (tab) {
            case COMBINE -> {
                if (chosen.size() != 3) {
                    lore.add("§7Select exactly §f3§7 souls.");
                } else {
                    SoulRarity rarity = chosen.get(0).getRarity();
                    SoulRarity target = cfg.nextRarity(rarity);
                    if (rarity == SoulRarity.MYTHIC || target == null) {
                        lore.add("§cMythic souls cannot be combined.");
                    } else {
                        int cost = svc != null ? svc.essenceCostCombine(target) : cfg.combineCost(target);
                        lore.add("§7Result: " + target.coloredNameLegacy() + " §f" + SoulLoreBuilder.pretty(chosen.get(0).getFamilyKey()) + " Soul");
                        lore.add("§7Cost: §b" + costLabel(cost));
                        if (essence < cost) lore.add("§cNot enough Essence");
                        else enabled = true;
                    }
                }
            }
            case ENHANCE -> {
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
                        lore.add("§7Bonus: §a+" + SoulLoreBuilder.fmt(cfg.getEnhancePerLevelBonus() * 100.0, 1) + "% stats");
                        lore.add("§7Cost: §b" + costLabel(cost));
                        if (essence < cost) lore.add("§cNot enough Essence");
                        else enabled = true;
                    }
                }
            }
            case REFORGE -> {
                if (chosen.size() != 1) {
                    lore.add("§7Select exactly §f1§7 soul.");
                } else {
                    SoulInstance soul = chosen.get(0);
                    int cost = svc != null ? svc.essenceCostReforge(soul.getRarity()) : cfg.reforgeCost(soul.getRarity());
                    lore.add("§7Affixes will be rerolled at " + soul.getRarity().coloredNameLegacy() + "§7.");
                    lore.add("§7Family, rarity, base stats and level are kept.");
                    lore.add("§eCurrent soul:");
                    for (Component c : SoulLoreBuilder.statsSection(plugin, soul, viewerId, true)) {
                        lore.add(LEGACY.serialize(c));
                    }
                    lore.add("§7Cost: §b" + costLabel(cost));
                    if (essence < cost) lore.add("§cNot enough Essence");
                    else enabled = true;
                }
            }
            case SALVAGE -> {
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
            if (enabled) glint(meta);
            meta.getPersistentDataContainer().set(actionKey(plugin), PersistentDataType.STRING, "confirm");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack actionItem(Material material, String name, String action, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(actionKey(plugin), PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack soulItem(SoulInstance soul, boolean isSelected) {
        ItemStack item = new ItemStack(SoulDropItem.materialFor(soul.getEntityType()));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String mobName = SoulLoreBuilder.pretty(soul.getFamilyKey());
        String qualityStr = SoulLoreBuilder.fmt(SoulQuality.percent(soul), 2) + "%";
        meta.displayName(Component.empty()
                .append(soul.getRarity().coloredNameComponent())
                .append(Component.text(" " + mobName + " Soul", soul.getRarity().getColor())
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(" §7[" + qualityStr + "]", NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.addAll(SoulLoreBuilder.traitsSection(soul));
        lore.addAll(SoulLoreBuilder.statsSection(plugin, soul, viewerId, true));
        if (isSelected) {
            lore.add(LEGACY.deserialize("§a§lSELECTED").decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(LEGACY.deserialize("§eClick to select").decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (isSelected) glint(meta);
        meta.getPersistentDataContainer().set(soulIdKey(plugin), PersistentDataType.STRING, soul.getSoulId().toString());
        item.setItemMeta(meta);
        return item;
    }

    // Soul stats/affixes/traits lore is centralized in SoulLoreBuilder to keep vault/table/drop/item views consistent.
    // Use statsSection(plugin, soul, viewerId, true) in Soul Table contexts that show per-affix quality.

    private static void glint(ItemMeta meta) {
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (Throwable ignored) {
            // Older API: selection is still readable from the SELECTED lore line.
        }
    }

    private static String costLabel(int cost) {
        return cost == Integer.MAX_VALUE ? "N/A" : String.valueOf(cost);
    }

    // ------------------------------------------------------------------ clicks

    public void handleClick(Player player, int rawSlot, org.bukkit.event.inventory.ClickType clickType) {
        if (player == null) return;
        if (!viewerId.equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (rawSlot < 0 || rawSlot >= SIZE) return;
        ItemStack clicked = inventory.getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) return;

        String action = readAction(clicked);
        if (action != null) {
            handleAction(player, action);
            return;
        }
        UUID soulId = readSoulId(clicked);
        if (soulId != null) toggleSelection(player, soulId);
    }

    private void handleAction(Player player, String action) {
        if (action.startsWith("tab:")) {
            try {
                tab = Tab.valueOf(action.substring(4));
            } catch (IllegalArgumentException ignored) {
                return;
            }
            saveLastTabAsync(tab);
            selected.clear();
            salvageArmedUntil = 0L;
            page = 0;
            refresh();
            return;
        }
        switch (action) {
            case "prev" -> {
                if (page > 0) page--;
                refresh();
            }
            case "next" -> {
                int maxPage = Math.max(0, (allSouls.size() - 1) / GRID_SIZE);
                if (page < maxPage) page++;
                refresh();
            }
            case "clear" -> {
                selected.clear();
                salvageArmedUntil = 0L;
                refresh();
            }
            case "guide" -> Bukkit.getScheduler().runTask(plugin, () ->
                    new SoulTableGuideInv(plugin, player, tableId, SoulTableGuideInv.Page.MAIN).open(player));
            case "confirm" -> confirm(player);
            default -> {
                // ignore unknown actions (future-proofing)
            }
        }
    }

    private void toggleSelection(Player player, UUID soulId) {
        SoulInstance soul = soulIndex.get(soulId);
        if (soul == null) return;

        switch (tab) {
            case COMBINE -> {
                if (selected.contains(soulId)) {
                    selected.remove(soulId);
                } else {
                    if (soul.getRarity() == SoulRarity.MYTHIC) {
                        actionBar(player, "Mythic souls cannot be combined.", NamedTextColor.RED);
                        return;
                    }
                    if (selected.size() >= 3) {
                        actionBar(player, "Select up to 3 souls.", NamedTextColor.RED);
                        return;
                    }
                    if (!selected.isEmpty()) {
                        SoulInstance first = soulIndex.get(selected.iterator().next());
                        if (first != null && (first.getRarity() != soul.getRarity()
                                || !first.getFamilyKey().equals(soul.getFamilyKey()))) {
                            actionBar(player, "Souls must share the same family and rarity", NamedTextColor.RED);
                            return;
                        }
                    }
                    selected.add(soulId);
                }
            }
            case ENHANCE, REFORGE -> {
                if (selected.contains(soulId)) {
                    selected.remove(soulId);
                } else {
                    selected.clear();
                    selected.add(soulId);
                }
            }
            case SALVAGE -> {
                if (!selected.remove(soulId)) selected.add(soulId);
            }
        }
        salvageArmedUntil = 0L;
        refresh();
    }

    private void confirm(Player player) {
        List<SoulInstance> chosen = selectedSouls();
        SoulTableService svc = service();
        if (svc == null) {
            actionBar(player, "Soul system is not initialized.", NamedTextColor.RED);
            return;
        }
        SoulTableConfig cfg = config();

        switch (tab) {
            case COMBINE -> {
                if (chosen.size() != 3) {
                    actionBar(player, "Select exactly 3 souls.", NamedTextColor.RED);
                    return;
                }
                SoulRarity target = cfg.nextRarity(chosen.get(0).getRarity());
                if (target == null || chosen.get(0).getRarity() == SoulRarity.MYTHIC) {
                    actionBar(player, "Mythic souls cannot be combined.", NamedTextColor.RED);
                    return;
                }
                List<UUID> ids = new ArrayList<>();
                for (SoulInstance soul : chosen) ids.add(soul.getSoulId());
                SoulTableService.TableResult result = svc.combine(viewerId, ids, target);
                finish(player, result, "Combined into " + target.name() + ".", true);
            }
            case ENHANCE -> {
                if (chosen.size() != 1) {
                    actionBar(player, "Select exactly 1 soul.", NamedTextColor.RED);
                    return;
                }
                SoulTableService.TableResult result = svc.enhance(viewerId, chosen.get(0).getSoulId());
                finish(player, result, "Soul enhanced.", true);
            }
            case REFORGE -> {
                if (chosen.size() != 1) {
                    actionBar(player, "Select exactly 1 soul.", NamedTextColor.RED);
                    return;
                }
                SoulTableService.TableResult result = svc.reforge(viewerId, chosen.get(0).getSoulId());
                finish(player, result, "Soul reforged.", false);
            }
            case SALVAGE -> {
                if (chosen.isEmpty()) {
                    actionBar(player, "Select at least 1 soul.", NamedTextColor.RED);
                    return;
                }
                long now = System.currentTimeMillis();
                if (now >= salvageArmedUntil) {
                    int seconds = Math.max(0, cfg.getSalvageConfirmationSeconds());
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
        }
    }

    private void performSalvage(Player player, List<SoulInstance> chosen) {
        List<UUID> ids = new ArrayList<>();
        for (SoulInstance soul : chosen) ids.add(soul.getSoulId());
        SoulTableService svc = service();
        if (svc == null) {
            actionBar(player, "Soul system is not initialized.", NamedTextColor.RED);
            return;
        }
        SoulTableService.TableResult result = svc.salvage(viewerId, ids);
        salvageArmedUntil = 0L;
        finish(player, result, "Souls salvaged.", true);
    }

    private void finish(Player player, SoulTableService.TableResult result, String successMessage, boolean clear) {
        if (result == null) {
            actionBar(player, "Operation failed.", NamedTextColor.RED);
            return;
        }
        if (!result.ok()) {
            actionBar(player, result.message(), NamedTextColor.RED);
            refresh();
            return;
        }
        if (clear) selected.clear();
        salvageArmedUntil = 0L;
        player.sendMessage(Component.text("[Soul Table] ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(result.message(), NamedTextColor.GRAY)));
        actionBar(player, successMessage, NamedTextColor.GREEN);
        refresh();
    }

    private void refreshIfOpen() {
        Player player = Bukkit.getPlayer(viewerId);
        if (player == null) return;
        if (player.getOpenInventory().getTopInventory().getHolder() != this) return;
        refresh();
    }

    // ------------------------------------------------------------------ pdc helpers

    private String readAction(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            return meta != null ? meta.getPersistentDataContainer()
                    .get(actionKey(plugin), PersistentDataType.STRING) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private UUID readSoulId(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;
            String raw = meta.getPersistentDataContainer().get(soulIdKey(plugin), PersistentDataType.STRING);
            return raw != null && !raw.isBlank() ? UUID.fromString(raw) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void actionBar(Player player, String message, NamedTextColor color) {
        if (player == null) return;
        player.sendActionBar(Component.text(message, color));
    }
}
