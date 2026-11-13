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

    public SoulVaultInv(GlovedPlugin plugin, UUID viewerId, int page, String filterType, SoulRarity filterRarity, String sortMode) {
        this.plugin = plugin;
        this.viewerId = viewerId;
        this.page = Math.max(0, page);
        this.filterType = filterType;
        this.filterRarity = filterRarity;
        this.sortMode = sortMode == null ? "default" : sortMode;
        this.pageSouls = loadPage();
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

    private List<SoulInstance> loadPage() {
        SoulService svc = plugin.getSoulService();
        if (svc == null) return List.of();
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
        int from = page * 45;
        if (from >= list.size()) return List.of();
        int to = Math.min(list.size(), from + 45);
        return list.subList(from, to);
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
        String title = TITLE_PREFIX + " (" + (page + 1) + ") " + total + "/" + cap;
        Inventory inv = Bukkit.createInventory(this, 54, LegacyComponentSerializer.legacySection().deserialize("§5" + title));

        for (int i = 0; i < pageSouls.size() && i < 45; i++) {
            inv.setItem(i, toItem(pageSouls.get(i)));
        }

        // controls row
        inv.setItem(45, navItem(Material.ARROW, "§ePrevious Page", "prev"));
        inv.setItem(49, infoItem(total, cap, essence));
        inv.setItem(53, navItem(Material.ARROW, "§eNext Page", "next"));
        inv.setItem(47, navItem(Material.PAPER, "§bFilter: " + (filterType == null || filterType.isBlank() ? "ALL" : filterType), "filter"));
        inv.setItem(48, navItem(Material.AMETHYST_SHARD, "§bRarity: " + (filterRarity == null ? "ALL" : filterRarity.name()), "rarity"));
        inv.setItem(50, navItem(Material.COMPARATOR, "§bSort: " + sortMode, "sort"));
        inv.setItem(51, navItem(Material.BARRIER, "§cClose", "close"));
        inv.setItem(52, combatModeItem());

        // help
        ItemStack help = new ItemStack(Material.BOOK);
        ItemMeta hm = help.getItemMeta();
        if (hm != null) {
            hm.displayName(LegacyComponentSerializer.legacySection().deserialize("§dControls").decoration(TextDecoration.ITALIC, false));
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

        return inv;
    }

    private ItemStack navItem(Material mat, String name, String action) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, action);
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
            lore.addAll(SoulLoreBuilder.traitsSection(soul));
            lore.addAll(SoulLoreBuilder.statsSection(plugin, soul, viewerId));
            String flags = (soul.isFavorite() ? "§e★ Fav " : "");
            if (!flags.isBlank()) lore.add(LegacyComponentSerializer.legacySection().deserialize(flags.trim()).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§aLeft Click§7 Equip/Select  §bRight Click§7 Inspect").decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§eSneak + Left Click§7 Toggle favorite").decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§bDrop§7 Extract item  §cSprint + Drop§7 Salvage").decoration(TextDecoration.ITALIC, false));
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
