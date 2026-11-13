package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixType;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableItem;
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
import java.util.List;
import java.util.UUID;

/**
 * Read-only guide menu for the Soul Table: traits, affixes, stats/rarity, and table actions.
 */
public final class SoulTableGuideInv implements InventoryHolder {

    public enum Page { MAIN, TRAITS, AFFIXES, STATS, TABLE }

    private static final int SIZE = 54;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 49;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final Page page;
    private final Inventory inventory;

    public SoulTableGuideInv(GlovedPlugin plugin, Player player, UUID tableId, Page page) {
        this.plugin = plugin;
        this.viewerId = player != null ? player.getUniqueId() : null;
        this.tableId = tableId;
        this.page = page != null ? page : Page.MAIN;
        String title = switch (this.page) {
            case MAIN -> "Soul Guide";
            case TRAITS -> "Soul Guide: Traits";
            case AFFIXES -> "Soul Guide: Affixes";
            case STATS -> "Soul Guide: Stats & Rarity";
            case TABLE -> "Soul Guide: Soul Table";
        };
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(title, NamedTextColor.DARK_AQUA));
        build();
    }

    public static NamespacedKey actionKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_guide_action");
    }

    public UUID getViewerId() { return viewerId; }
    public UUID getTableId() { return tableId; }

    @Override
    public @NotNull Inventory getInventory() { return inventory; }

    public void open(Player player) {
        if (player == null) return;
        player.openInventory(inventory);
    }

    private boolean isStandalone() {
        return tableId == null;
    }

    private void build() {
        inventory.clear();
        for (int i = 45; i < 54; i++) inventory.setItem(i, filler());
        switch (page) {
            case MAIN -> buildMain();
            case TRAITS -> buildTraits();
            case AFFIXES -> buildAffixes();
            case STATS -> buildStats();
            case TABLE -> buildTable();
        }
        if (page == Page.MAIN) {
            if (isStandalone()) {
                inventory.setItem(SLOT_BACK, filler());
            } else {
                inventory.setItem(SLOT_BACK, soulTableActionItem("§dBack to Soul Table", "back_table",
                        List.of("§7Return to the Soul Table.")));
            }
        } else {
            inventory.setItem(SLOT_BACK, actionItem(Material.ARROW, "§eBack", "back_main",
                    List.of("§7Return to guide categories.")));
        }
        inventory.setItem(SLOT_CLOSE, actionItem(Material.BARRIER, "§cClose", "close", List.of("§7Close this guide.")));
    }

    private void buildMain() {
        // Intro
        inventory.setItem(13, infoItem(Material.BOOK, "§b§lSoul Guide", List.of(
                "§7Learn what souls do and how to",
                "§7make them stronger at the Soul Table.",
                "",
                "§ePick a topic below to read more.")));

        // Categories in a centered row
        inventory.setItem(28, actionItem(Material.NETHER_STAR, "§d§lTraits", "page:TRAITS", List.of(
                "§7Special perks your summons can have.",
                "",
                "§eClick to view")));
        inventory.setItem(30, actionItem(Material.AMETHYST_SHARD, "§d§lAffixes", "page:AFFIXES", List.of(
                "§7Bonus stats rolled onto a soul.",
                "",
                "§eClick to view")));
        inventory.setItem(32, actionItem(Material.IRON_SWORD, "§d§lStats & Rarity", "page:STATS", List.of(
                "§7Health, damage, speed, and more.",
                "",
                "§eClick to view")));
        inventory.setItem(34, soulTableActionItem("§d§lSoul Table", "page:TABLE", List.of(
                "§7Upgrade, reroll, and recycle souls.",
                "",
                "§eClick to view")));
    }

    private void buildTraits() {
        inventory.setItem(4, infoItem(Material.NETHER_STAR, "§d§lTraits", List.of(
                "§7Traits are special perks a soul can have.",
                "§7Better souls can roll more traits.")));

        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        SoulTrait[] traits = SoulTrait.values();
        for (int i = 0; i < traits.length && i < slots.length; i++) {
            inventory.setItem(slots[i], traitItem(traits[i]));
        }
    }

    private ItemStack traitItem(SoulTrait trait) {
        String name = pretty(trait.name());
        List<String> lore = new ArrayList<>();
        switch (trait) {
            case VAMPIRIC -> {
                lore.add("§7Your summons heal a bit when");
                lore.add("§7dealing damage.");
            }
            case FRENZIED -> {
                lore.add("§7Hits harder and lands critical");
                lore.add("§7strikes more often.");
            }
            case BULWARK -> {
                lore.add("§7Tougher summons that take");
                lore.add("§7less damage in fights.");
            }
            case HEXING -> {
                lore.add("§7More likely to weaken enemies");
                lore.add("§7when your summons hit.");
            }
            case SPLINTERING -> {
                lore.add("§7Better at hurting groups of");
                lore.add("§7enemies at once.");
            }
            case UNSTABLE -> {
                lore.add("§7Attacks can burst and damage");
                lore.add("§7nearby enemies.");
            }
            case PACKBOUND -> {
                lore.add("§7Fights stronger when other");
                lore.add("§7summons are nearby.");
            }
        }
        return infoItem(Material.ENCHANTED_BOOK, "§d" + name, lore);
    }

    private void buildAffixes() {
        boolean durationEnabled = isDurationEnabled();
        boolean costReductionEnabled = isCostReductionEnabled();
        inventory.setItem(4, infoItem(Material.AMETHYST_SHARD, "§d§lAffixes", List.of(
                "§7Affixes are bonus stats rolled onto a soul.",
                "§7Better souls can get more and stronger bonuses.")));

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20};
        SoulAffixType[] types = SoulAffixType.values();
        int slotIndex = 0;
        for (SoulAffixType type : types) {
            if (!durationEnabled && type == SoulAffixType.ENDURING) continue;
            if (!costReductionEnabled && type == SoulAffixType.COMMANDER) continue;
            if (slotIndex >= slots.length) break;
            inventory.setItem(slots[slotIndex++], affixItem(type));
        }


    }

    private boolean isDurationEnabled() {
        try {
            var svc = plugin.getSoulService();
            if (svc == null || svc.soulConfig() == null) return false;
            return svc.soulConfig().getLifetimeTicks() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isCostReductionEnabled() {
        try {
            var svc = plugin.getSoulService();
            if (svc == null || svc.soulConfig() == null) return false;
            var cfg = svc.soulConfig();
            var summonCfg = plugin.getSummonConfig();
            boolean manaRelevant = cfg.isCastManaEnabled() && cfg.getCastBaseManaCost() > 0.0
                    && (summonCfg == null || summonCfg.isManaEnabled());
            boolean cooldownRelevant = cfg.getCastBaseCooldownTicks() > 0 || cfg.getCastSharedGcdTicks() > 0;
            return manaRelevant || cooldownRelevant;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ItemStack affixItem(SoulAffixType type) {
        List<String> lore = new ArrayList<>();
        switch (type) {
            case BRUTAL -> {
                lore.add("§7Makes your summons hit harder.");
            }
            case VIGOROUS -> {
                lore.add("§7Gives your summons more health.");
            }
            case SWIFT -> {
                lore.add("§7Makes your summons move faster.");
            }
            case PRECISE -> {
                lore.add("§7Helps your summons land critical");
                lore.add("§7hits more often.");
            }
            case DEADLY -> {
                lore.add("§7Makes your summons' critical");
                lore.add("§7hits deal more damage.");
            }
            case TENACIOUS -> {
                lore.add("§7Helps your summons take less damage.");
            }
            case ENDURING -> {
                lore.add("§7Lets your summons stay around longer.");
            }
            case RESONANT -> {
                lore.add("§7Makes your summons' special effects");
                lore.add("§7stronger.");
            }
            case COMMANDER -> {
                lore.add("§7Makes summoning cost a bit less.");
            }
            case CAPACITY -> {
                lore.add("§7Lets this soul call more summons");
                lore.add("§7at once.");
            }
        }
        return infoItem(Material.PAPER, "§a" + type.friendlyName(), lore);
    }

    private void buildStats() {
        inventory.setItem(4, infoItem(Material.IRON_SWORD, "§d§lStats & Rarity", List.of(
                "§7Every soul has stats that decide how",
                "§7strong its summons will be.")));

        inventory.setItem(19, infoItem(Material.RED_DYE, "§cHealth", List.of(
                "§7How tough your summons are.",
                "§7More health means they last longer.")));
        inventory.setItem(20, infoItem(Material.SHIELD, "§9Defense", List.of(
                "§7Helps your summons take less",
                "§7damage from hits.")));
        inventory.setItem(21, infoItem(Material.IRON_SWORD, "§cDamage", List.of(
                "§7How hard your summons hit.",
                "§7Higher rarity and upgrades boost it.")));
        inventory.setItem(22, infoItem(Material.FEATHER, "§bMove Speed", List.of(
                "§7How fast your summons move.")));
        inventory.setItem(23, infoItem(Material.CLOCK, "§aAttack Speed", List.of(
                "§7How quickly your summons attack.")));
        inventory.setItem(24, infoItem(Material.SLIME_BALL, "§3Summons", List.of(
                "§7How many allies this soul calls at once.",
                "§7Better souls and upgrades can call more.")));
        inventory.setItem(25, infoItem(Material.EXPERIENCE_BOTTLE, "§dEnhancing", List.of(
                "§7Enhancing a soul improves its stats",
                "§7and can let it call more summons.",
                "§7Changes apply on your next summon.")));

        List<String> rar = new ArrayList<>();
        rar.add("§7Higher rarity means better potential.");
        rar.add("");
        for (SoulRarity r : SoulRarity.values()) {
            rar.add(" " + r.coloredNameLegacy());
        }
        inventory.setItem(31, infoItem(Material.DIAMOND, "§bRarity Tiers", rar));
    }

    private void buildTable() {
        inventory.setItem(4, soulTableInfoItem("§d§lSoul Table", List.of(
                "§7Upgrade and manage your souls here.",
                "§7Most upgrades cost §bEssence§7.")));

        inventory.setItem(19, infoItem(Material.ANVIL, "§aCombine", List.of(
                "§7Merge §f3§7 souls of the same type",
                "§7and rarity into a higher rarity.",
                "",
                "§8Mythic souls can't be combined.")));
        inventory.setItem(20, infoItem(Material.EXPERIENCE_BOTTLE, "§aEnhance", List.of(
                "§7Level up a soul to improve its stats.",
                "§7Pick §f1§7 soul, then confirm.")));
        inventory.setItem(21, infoItem(Material.GRINDSTONE, "§aReforge", List.of(
                "§7Reroll a soul's stats and bonuses",
                "§7while keeping its type and rarity.",
                "§7Pick §f1§7 soul, then confirm.")));
        inventory.setItem(22, infoItem(Material.HOPPER, "§aSalvage", List.of(
                "§7Break down souls you don't need",
                "§7to get §bEssence§7 back.",
                "§7You can salvage several at once.")));
        inventory.setItem(23, infoItem(Material.AMETHYST_SHARD, "§bEssence", List.of(
                "§7Used to combine, enhance, and reforge.",
                "§7Earn it by salvaging souls.")));
        inventory.setItem(24, infoItem(Material.BOOK, "§eHow to Use", List.of(
                "§7Click souls in the grid to select them.",
                "§7Check the button at the bottom to confirm.")));
    }

    public void handleClick(Player player, int rawSlot, org.bukkit.event.inventory.ClickType clickType) {
        if (player == null) return;
        if (viewerId != null && !viewerId.equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (rawSlot < 0 || rawSlot >= SIZE) return;
        ItemStack clicked = inventory.getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) return;
        String action = readAction(clicked);
        if (action == null) return;
        switch (action) {
            case "back_table" -> openSoulTable(player);
            case "back_main" -> new SoulTableGuideInv(plugin, player, tableId, Page.MAIN).open(player);
            case "close" -> player.closeInventory();
            default -> {
                if (action.startsWith("page:")) {
                    try {
                        Page target = Page.valueOf(action.substring(5));
                        new SoulTableGuideInv(plugin, player, tableId, target).open(player);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    private void openSoulTable(Player player) {
        if (tableId == null) {
            player.closeInventory();
            return;
        }
        try {
            var tableMgr = plugin.getSoulTableManager();
            if (tableMgr != null) {
                var row = tableMgr.get(tableId);
                if (row != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> new SoulTableInv(plugin, player, row).open(player));
                    return;
                }
            }
        } catch (Throwable ignored) {}
        Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
    }

    private String readAction(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            return meta != null ? meta.getPersistentDataContainer().get(actionKey(plugin), PersistentDataType.STRING) : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    private ItemStack infoItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack soulTableActionItem(String name, String action, List<String> lore) {
        ItemStack item = SoulTableItem.createDisplayStack();
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

    private ItemStack soulTableInfoItem(String name, List<String> lore) {
        ItemStack item = SoulTableItem.createDisplayStack();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
            List<Component> components = new ArrayList<>();
            for (String line : lore) components.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(components);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
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

    private static String pretty(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(java.util.Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }
}
