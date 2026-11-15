package dev.gloved.glovedplugin.summon.soul.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.*;
import dev.gloved.glovedplugin.summon.soul.service.*;
import dev.gloved.glovedplugin.summon.soul.item.*;
import dev.gloved.glovedplugin.summon.soul.db.*;
import dev.gloved.glovedplugin.summon.soul.ui.*;
import dev.gloved.glovedplugin.summon.soul.table.*;
import dev.gloved.glovedplugin.summon.soul.table.ui.*;
import dev.gloved.glovedplugin.summon.soul.trap.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulQuality;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.item.SoulDropItem;
import dev.gloved.glovedplugin.summon.soul.item.SoulLoreBuilder;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.ui.UiIcons;

/**
 * Simple paginated vault GUI for dynamic souls.
 * Left-click: select soul into player's selected loadout slot (equip+select context)
 * Right-click: inspect in chat
 * Sneak + left click: toggle favorite
 * Drop: extract as physical soul item (tradeable)
 * Sprint + Drop: salvage for essence (if allowed)
 */
public class SoulVaultInv implements InventoryHolder {
    public static final String TITLE_PREFIX = "Soul Vault";

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final int page;
    private final String filterType;
    private final SoulRarity filterRarity;
    private final String sortMode;
    private final List<SoulInstance> pageSouls;
    private final int totalFiltered;
    private final int maxPage;
    // Team slot assignment picker context (null = legacy/browse mode)
    private final Integer pickerTeam;
    private final Integer pickerSlot;

    public SoulVaultInv(GlovedPlugin plugin, UUID viewerId, int page, String filterType, SoulRarity filterRarity, String sortMode) {
        this(plugin, viewerId, page, filterType, filterRarity, sortMode, null, null);
    }

    public SoulVaultInv(GlovedPlugin plugin, UUID viewerId, int page, String filterType, SoulRarity filterRarity, String sortMode,
                        Integer pickerTeam, Integer pickerSlot) {
        this.plugin = plugin;
        this.viewerId = viewerId;
        this.filterType = filterType;
        this.filterRarity = filterRarity;
        this.sortMode = sortMode == null ? "default" : sortMode;
        this.pickerTeam = pickerTeam;
        this.pickerSlot = pickerSlot;
        PageData pd = loadPage(Math.max(0, page));
        this.page = pd.page;
        this.pageSouls = pd.items;
        this.totalFiltered = pd.total;
        this.maxPage = pd.maxPage;
    }

    public static NamespacedKey soulIdKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_vault_id");
    }

    public static NamespacedKey navKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_vault_nav");
    }

    public UUID getViewerId() { return viewerId; }
    public int getPage() { return page; }
    public String getFilterType() { return filterType; }
    public SoulRarity getFilterRarity() { return filterRarity; }
    public String getSortMode() { return sortMode; }
    public Integer getPickerTeam() { return pickerTeam; }
    public Integer getPickerSlot() { return pickerSlot; }
    public boolean isPickerMode() { return pickerTeam != null && pickerSlot != null; }

    private record PageData(List<SoulInstance> items, int page, int total, int maxPage) {}

    private PageData loadPage(int requestedPage) {
        SoulService svc = plugin.getSoulService();
        if (svc == null) return new PageData(List.of(), 0, 0, 0);
        List<SoulInstance> list = svc.listSoulsFiltered(viewerId, filterType, filterRarity, 0, 200);
        Comparator<SoulInstance> cmp = switch (sortMode.toLowerCase(Locale.ROOT)) {
            case "quality" -> Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed();
            case "rarity" -> Comparator.comparingInt((SoulInstance s) -> s.getRarity().ordinal()).reversed()
                    .thenComparing(Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed());
            case "type", "family" -> Comparator.comparing(SoulInstance::getFamilyKey).thenComparing(Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed());
            case "recent" -> Comparator.comparingLong(SoulInstance::getCreatedAt).reversed();
            default -> Comparator.comparing((SoulInstance s) -> s.isFavorite() ? 0 : 1)
                    .thenComparing(Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed())
                    .thenComparing(Comparator.comparingLong(SoulInstance::getCreatedAt).reversed());
        };
        list = new ArrayList<>(list);
        list.sort(cmp);
        int total = list.size();
        int max = Math.max(0, (total - 1) / 45);
        int p = Math.min(Math.max(0, requestedPage), max);
        if (total == 0) return new PageData(List.of(), 0, 0, 0);
        int from = p * 45;
        if (from >= total) return new PageData(List.of(), p, total, max);
        int to = Math.min(total, from + 45);
        return new PageData(list.subList(from, to), p, total, max);
    }

    public void open(Player player) {
        if (player == null) return;
        player.openInventory(getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        SoulService svc = plugin.getSoulService();
        int total = svc != null ? svc.countSouls(viewerId) : 0;
        var st = svc != null ? svc.getState(viewerId) : null;
        int cap = st != null ? st.vaultCapacity() : 0;
        int essence = st != null ? st.essence() : 0;
        String title;
        if (isPickerMode()) {
            title = maxPage > 0
                    ? "Select Soul T" + pickerTeam + " S" + (pickerSlot + 1) + " (" + (page + 1) + "/" + (maxPage + 1) + ")"
                    : "Select Soul T" + pickerTeam + " S" + (pickerSlot + 1);
        } else {
            title = maxPage > 0
                    ? TITLE_PREFIX + " (" + (page + 1) + "/" + (maxPage + 1) + ") " + total + "/" + cap
                    : TITLE_PREFIX + " " + total + "/" + cap;
        }
        Inventory inv = Bukkit.createInventory(this, 54, LegacyComponentSerializer.legacySection().deserialize("§5" + title));

        for (int i = 0; i < pageSouls.size() && i < 45; i++) {
            inv.setItem(i, toItem(pageSouls.get(i)));
        }

        // controls row
        if (maxPage > 0) {
            if (page > 0) {
                inv.setItem(45, navItem(Material.ARROW, "§ePrevious Page", "prev", UiIcons.ARROW_LEFT));
            } else {
                inv.setItem(45, navItem(Material.GRAY_STAINED_GLASS_PANE, "§7Previous Page", null, UiIcons.ARROW_LEFT_DISABLED));
            }
            if (page < maxPage) {
                inv.setItem(53, navItem(Material.ARROW, "§eNext Page", "next", UiIcons.ARROW_RIGHT));
            } else {
                inv.setItem(53, navItem(Material.GRAY_STAINED_GLASS_PANE, "§7Next Page", null, UiIcons.ARROW_RIGHT_DISABLED));
            }
        } else {
            inv.setItem(45, filler());
            inv.setItem(53, filler());
        }
        inv.setItem(49, infoItem(total, cap, essence));
        inv.setItem(47, navItem(Material.PAPER, "§bFilter: " + (filterType == null || filterType.isBlank() ? "ALL" : filterType), "filter", null));
        inv.setItem(48, navItem(Material.AMETHYST_SHARD, "§bRarity: " + (filterRarity == null ? "ALL" : filterRarity.name()), "rarity", null));
        inv.setItem(50, navItem(Material.COMPARATOR, "§bSort: " + sortMode, "sort", null));
        if (isPickerMode()) {
            inv.setItem(51, navItem(Material.ARROW, "§eBack to Teams", "back", UiIcons.TEAMS));
            boolean hasAssigned = false;
            try {
                if (svc != null && pickerTeam != null && pickerSlot != null) {
                    var teamSlots = svc.getTeam(viewerId, pickerTeam);
                    UUID assignedId = teamSlots != null ? teamSlots.get(pickerSlot) : null;
                    hasAssigned = assignedId != null;
                }
            } catch (Throwable ignored) {}
            if (hasAssigned) {
                inv.setItem(52, navItem(Material.BARRIER, "§cClear Slot", "clear_slot", UiIcons.CLOSE));
            } else {
                inv.setItem(52, filler());
            }
        } else {
            inv.setItem(51, navItem(Material.ARROW, "§eBack to Teams", "back", UiIcons.TEAMS));
            inv.setItem(52, combatModeItem());
        }

        // help
        if (isPickerMode()) {
            boolean hasAssigned = false;
            try {
                if (svc != null && pickerTeam != null && pickerSlot != null) {
                    var teamSlots = svc.getTeam(viewerId, pickerTeam);
                    UUID assignedId = teamSlots != null ? teamSlots.get(pickerSlot) : null;
                    hasAssigned = assignedId != null;
                }
            } catch (Throwable ignored) {}
            ItemStack help = navItem(Material.BOOK, "§dControls", "help", UiIcons.GUIDE);
            ItemMeta hm = help.getItemMeta();
            if (hm != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Left Click: §aAssign to Team " + pickerTeam + " Slot " + (pickerSlot + 1)).decoration(TextDecoration.ITALIC, false));
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Right Click: §fInspect soul").decoration(TextDecoration.ITALIC, false));
                lore.add(LegacyComponentSerializer.legacySection().deserialize(hasAssigned
                        ? "§7Use §cClear Slot §7or §eBack to Teams §7below"
                        : "§7Use §eBack to Teams §7below").decoration(TextDecoration.ITALIC, false));
                hm.lore(lore);
                help.setItemMeta(hm);
            }
            inv.setItem(46, help);
        } else {
            ItemStack help = navItem(Material.BOOK, "§dControls", "help", UiIcons.GUIDE);
            ItemMeta hm = help.getItemMeta();
            if (hm != null) {
                hm.lore(List.of(
                        LegacyComponentSerializer.legacySection().deserialize("§7Left Click: Equip to selected slot").decoration(TextDecoration.ITALIC, false),
                        LegacyComponentSerializer.legacySection().deserialize("§7Right Click: Inspect").decoration(TextDecoration.ITALIC, false),
                        LegacyComponentSerializer.legacySection().deserialize("§7Sneak + Left Click: Toggle favorite").decoration(TextDecoration.ITALIC, false),
                        LegacyComponentSerializer.legacySection().deserialize("§7Drop: Extract as item").decoration(TextDecoration.ITALIC, false),
                        LegacyComponentSerializer.legacySection().deserialize("§7Sprint + Drop: Salvage (if allowed)").decoration(TextDecoration.ITALIC, false)
                ));
                help.setItemMeta(hm);
            }
            inv.setItem(46, help);
        }

        return inv;
    }

    private ItemStack navItem(Material mat, String name, String action) {
        return navItem(mat, name, action, null);
    }

    private ItemStack navItem(Material mat, String name, String action, String iconModel) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name).decoration(TextDecoration.ITALIC, false));
            if (action != null) meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, action);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            it.setItemMeta(meta);
        }
        return UiIcons.apply(it, iconModel);
    }

    private ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(" ").decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack infoItem(int total, int cap, int essence) {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            SoulService svc = plugin.getSoulService();
            var st = svc != null ? svc.getState(viewerId) : null;
            int sel = st != null ? st.selectedSlot() : 0;
            String mode = svc != null ? svc.getSummonCombatMode(viewerId) : "AGGRESSIVE";
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§dSoul Vault").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Souls: §f" + total + "/" + cap).decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Essence: §b" + essence).decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Selected slot: §f" + sel).decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Filter: §f" + (filterType == null || filterType.isBlank() ? "ALL" : filterType) + " §7Rarity: §f" + (filterRarity == null ? "ALL" : filterRarity.name())).decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Sort: §f" + sortMode).decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Summon mode: " + ("DEFENSIVE".equals(mode) ? "§eDefensive" : "§cAggressive")).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack combatModeItem() {
        SoulService svc = plugin.getSoulService();
        String mode = svc != null ? svc.getSummonCombatMode(viewerId) : "AGGRESSIVE";
        boolean defensive = "DEFENSIVE".equalsIgnoreCase(mode);
        ItemStack it = new ItemStack(defensive ? Material.SHIELD : Material.IRON_SWORD);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = defensive ? "§eSummon Mode: Defensive" : "§cSummon Mode: Aggressive";
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            if (defensive) {
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Summons attack threats to you or").decoration(TextDecoration.ITALIC, false));
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§7themselves/recent attackers only.").decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Summons auto-target nearby valid").decoration(TextDecoration.ITALIC, false));
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§7hostiles/threats.").decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§aClick to toggle Defensive/Aggressive").decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Applies to active and future summons.").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, "combat");
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack toItem(SoulInstance soul) {
        Material mat = materialFor(soul);
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String mobName = prettyMobName(soul.getFamilyKey());
            Component rarityName = soul.getRarity().coloredNameComponent();
            Component rest = (soul.getRarity() == SoulRarity.MYTHIC)
                    ? Component.text(" " + mobName + " Soul", net.kyori.adventure.text.format.NamedTextColor.RED)
                            .decoration(TextDecoration.BOLD, false)
                    : Component.text(" " + mobName + " Soul", soul.getRarity().getColor());
            Component name = Component.empty()
                    .append(rarityName)
                    .append(rest)
                    .decoration(TextDecoration.ITALIC, false);
            meta.displayName(name);
            List<Component> lore = new ArrayList<>();
            lore.addAll(SoulLoreBuilder.statsSection(plugin, soul, viewerId));
            lore.addAll(SoulLoreBuilder.traitsSection(soul));
            String flags = (soul.isFavorite() ? "§e★ Fav " : "");
            if (!flags.isBlank()) lore.add(LegacyComponentSerializer.legacySection().deserialize(flags.trim()).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            if (isPickerMode()) {
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§aLeft Click§7 Assign to Team " + pickerTeam + " Slot " + (pickerSlot + 1)).decoration(TextDecoration.ITALIC, false));
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§bRight Click§7 Inspect").decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§aLeft Click§7 Equip/Select  §bRight Click§7 Inspect").decoration(TextDecoration.ITALIC, false));
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§eSneak + Left Click§7 Toggle favorite").decoration(TextDecoration.ITALIC, false));
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§bDrop§7 Extract item  §cSprint + Drop§7 Salvage").decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(soulIdKey(plugin), PersistentDataType.STRING, soul.getSoulId().toString());
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String prettyMobName(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(java.util.Locale.ROOT).split("[_\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "-";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String rarityColor(SoulRarity r) {
        if (r == null) return "§f";
        return switch (r) {
            case COMMON -> "§f";
            case UNCOMMON -> "§a";
            case RARE -> "§9";
            case EPIC -> "§5";
            case LEGENDARY -> "§6";
            case MYTHIC -> "§c"; // kept for compatibility; prefer coloredNameComponent()
        };
    }

    private static Material materialFor(SoulInstance soul) {
        if (soul == null) return Material.EGG;
        return SoulDropItem.materialFor(soul.getEntityType());
    }
}
