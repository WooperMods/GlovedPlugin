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
            inventory.setItem(SLOT_BACK, soulTableActionItem("§dBack to Soul Table", "back_table",
                    List.of("§7Return to the Soul Table.")));
        } else {
            inventory.setItem(SLOT_BACK, actionItem(Material.ARROW, "§eBack", "back_main",
                    List.of("§7Return to guide categories.")));
        }
        inventory.setItem(SLOT_CLOSE, actionItem(Material.BARRIER, "§cClose", "close", List.of("§7Close this guide.")));
    }

    private void buildMain() {
        inventory.setItem(10, actionItem(Material.NETHER_STAR, "§dTraits", "page:TRAITS", List.of(
                "§7Special soul traits rolled by rarity.",
                "§7Each trait gives unique combat effects.",
                "§eClick to view details.")));
        inventory.setItem(12, actionItem(Material.AMETHYST_SHARD, "§dAffixes", "page:AFFIXES", List.of(
                "§7Stat-boosting affix rolls on souls.",
                "§7Higher rarity allows stronger rolls.",
                "§eClick to view details.")));
        inventory.setItem(14, actionItem(Material.IRON_SWORD, "§dStats & Rarity", "page:STATS", List.of(
                "§7Base stats, quality, rarity, enhancement.",
                "§7How souls scale when summoned.",
                "§eClick to view details.")));
        inventory.setItem(16, soulTableActionItem("§dSoul Table Actions", "page:TABLE", List.of(
                "§7Combine, Enhance, Reforge, Salvage.",
                "§7Costs, rules, and Essence basics.",
                "§eClick to view details.")));
        inventory.setItem(22, infoItem(Material.BOOK, "§b§lSoul Guide", List.of(
                "§7A quick reference for souls and the Soul Table.",
                "§7Hover items in each section for details.",
                "§7Only guide info is shown here; your souls are unchanged.")));
    }

    private void buildTraits() {
        inventory.setItem(4, infoItem(Material.NETHER_STAR, "§dTraits", List.of(
                "§7Traits are special bonuses/modifiers rolled on souls.",
                "§7Higher rarities can roll more traits.",
                "§7Traits appear in the soul tooltip and affect your summons.")));
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
                lore.add("§7Improves summon lifesteal/healing effects.");
                lore.add("§7Synergizes with lifesteal ability healing.");
            }
            case FRENZIED -> {
                lore.add("§7Increases summon damage output.");
                lore.add("§7Grants bonus crit chance and crit damage.");
                lore.add("§7Slightly boosts ability potency.");
            }
            case BULWARK -> {
                lore.add("§7Tanky defensive trait for summons.");
                lore.add("§7Reduces incoming damage taken by the summon.");
                lore.add("§7Helps summons survive longer in combat.");
            }
            case HEXING -> {
                lore.add("§7Increases the chance to apply debuffs on hit.");
            }
            case SPLINTERING -> {
                lore.add("§7Improves cleave/AoE pressure.");
                lore.add("§7Increases cleave chance and cleave damage ratio.");
            }
            case UNSTABLE -> {
                lore.add("§7Chance to unleash an unstable AoE burst on hit.");
                lore.add("§7Can damage nearby valid targets around the impact.");
            }
            case PACKBOUND -> {
                lore.add("§7Stronger when fighting alongside allied summons.");
                lore.add("§7Gains bonus damage based on nearby allied summons.");
            }
        }
        return infoItem(Material.ENCHANTED_BOOK, "§d" + name, lore);
    }

    private void buildAffixes() {
        boolean durationEnabled = isDurationEnabled();
        boolean costReductionEnabled = isCostReductionEnabled();
        inventory.setItem(4, infoItem(Material.AMETHYST_SHARD, "§dAffixes", List.of(
                "§7Affixes are rolled stat modifiers on souls.",
                "§7Rarity controls how many and how strong they can be.",
                "§7In Soul Table tooltips, each affix shows its roll quality §7[q%].")));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20};
        SoulAffixType[] types = SoulAffixType.values();
        int slotIndex = 0;
        for (SoulAffixType type : types) {
            if (!durationEnabled && type == SoulAffixType.ENDURING) continue;
            if (!costReductionEnabled && type == SoulAffixType.COMMANDER) continue;
            if (slotIndex >= slots.length) break;
            inventory.setItem(slots[slotIndex++], affixItem(type));
        }
        List<String> note = new ArrayList<>();
        note.add("§7Enhancement increases a soul's base stats by level.");
        note.add("§7Higher enhancement levels grant stronger summoned stats.");
        inventory.setItem(22, infoItem(Material.EXPERIENCE_BOTTLE, "§bEnhancement", note));
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
        lore.add("§7+" + type.friendlyName() + " affix roll.");
        switch (type) {
            case BRUTAL -> lore.add("§7Increases summon damage dealt.");
            case VIGOROUS -> lore.add("§7Increases summon max health.");
            case SWIFT -> lore.add("§7Increases summon movement speed (capped for safety).");
            case PRECISE -> lore.add("§7Increases summon critical hit chance.");
            case DEADLY -> lore.add("§7Increases summon critical hit damage.");
            case TENACIOUS -> lore.add("§7Improves summon durability/defensive scaling.");
            case ENDURING -> {
                lore.add("§7Increases summon lifetime/uptime.");
            }
            case RESONANT -> lore.add("§7Increases ability/proc potency.");
            case COMMANDER -> lore.add("§7Improves cast efficiency (cooldown/mana/cost scaling).");
            case CAPACITY -> {
                lore.add("§7Increases that soul's Summons by a mild percentage.");
                lore.add("§7Lets a soul summon a larger group (within caps/ceilings).");
            }
        }
        lore.add("§8Shown with roll quality [q%] in Soul Table tooltips.");
        return infoItem(Material.PAPER, "§a" + type.friendlyName(), lore);
    }

    private void buildStats() {
        SoulTableConfig cfg = plugin.getSoulService() != null ? plugin.getSoulService().tableConfig() : null;
        int maxEnh = cfg != null ? cfg.getEnhanceMaxLevel() : 10;
        double perLvl = cfg != null ? cfg.getEnhancePerLevelBonus() * 100.0 : 2.5;
        inventory.setItem(4, infoItem(Material.IRON_SWORD, "§dStats & Rarity", List.of(
                "§7Souls capture/roll base stats by family and rarity.",
                "§7Displayed stats reflect effective summoned values",
                "§7(base stats, enhancement, affixes, and owner bonuses where applicable).")));

        inventory.setItem(19, infoItem(Material.RED_DYE, "§cHealth (HP)", List.of(
                "§7Minion maximum health when summoned.",
                "§7Influenced by rolled base, enhancement, and affixes.")));
        inventory.setItem(20, infoItem(Material.IRON_SWORD, "§cAttack Damage", List.of(
                "§7Minion offensive power (shown as ATK/ATK DMG).",
                "§7Influenced by base stats, enhancement, affixes, traits, and bonuses.")));
        inventory.setItem(21, infoItem(Material.FEATHER, "§bMovement Speed", List.of(
                "§7How fast the summon moves.",
                "§7Subject to gameplay caps for stability.")));
        inventory.setItem(22, infoItem(Material.CLOCK, "§aAttack Speed", List.of(
                "§7How quickly the summon attacks (APS).",
                "§7Rolled/capped within configured stat ranges.")));
        inventory.setItem(23, infoItem(Material.NETHER_STAR, "§dQuality", List.of(
                "§7Total quality reflects rarity band + affix roll strength.",
                "§7Higher rarity sets a higher quality floor/range.",
                "§7Soul Table shows total quality in the soul name.")));
        inventory.setItem(24, infoItem(Material.EXPERIENCE_BOTTLE, "§dEnhancement", List.of(
                "§7Enhance levels increase soul stats (max " + maxEnh + ").",
                "§7Bonus: about §f+" + String.format(java.util.Locale.ROOT, "%.1f", perLvl) + "%§7 stats per level.",
                "§7Milestones can increase Summons at higher levels.",
                "§7Applied on next summon; active summons are not refreshed.")));

        inventory.setItem(25, infoItem(Material.SLIME_BALL, "§3Summons", List.of(
                "§7How many summons that soul can call in its group.",
                "§7Rolled on the soul and scales with rarity (with caps/archetype tuning).",
                "§7The §aSummons§7 affix can give a mild % bonus (not rolled on souls with a fixed max Summons).",
                "§7Reforge can reroll base Summons; enhancement can increase it at milestones.",
                "§7Hard per-type overrides remain absolute ceilings (e.g. Warden = 1).")));

        List<String> rar = new ArrayList<>();
        rar.add("§7Rarity affects rolls, caps, and progression value.");
        for (SoulRarity r : SoulRarity.values()) {
            rar.add(r.coloredNameLegacy() + " §7- higher tiers allow stronger outcomes.");
        }
        inventory.setItem(31, infoItem(Material.DIAMOND, "§bRarities", rar));
    }

    private void buildTable() {
        SoulTableConfig cfg = plugin.getSoulService() != null ? plugin.getSoulService().tableConfig() : new SoulTableConfig();
        inventory.setItem(4, soulTableInfoItem("§dSoul Table", List.of(
                "§7Use the Soul Table to manage and upgrade souls.",
                "§7All mutations are validated and transactional.")));

        inventory.setItem(19, infoItem(Material.ANVIL, "§dCombine", List.of(
                "§7Combine §f3§7 souls of the same family and rarity.",
                "§7Result: next rarity (Mythic cannot be combined).",
                "§7Consumes Essence based on target rarity.")));
        inventory.setItem(20, infoItem(Material.EXPERIENCE_BOTTLE, "§dEnhance", List.of(
                "§7Raise one soul's enhancement level.",
                "§7Max level: §f" + cfg.getEnhanceMaxLevel(),
                "§7Cost scales by current level and consumes Essence.")));
        inventory.setItem(21, infoItem(Material.GRINDSTONE, "§dReforge", List.of(
                "§7Reroll affixes, base stats, and Summons capacity at the same rarity.",
                "§7Keeps family, rarity, enhancement level, and identity.",
                "§7Consumes Essence by rarity.")));
        inventory.setItem(22, infoItem(Material.HOPPER, "§dSalvage", List.of(
                "§7Convert selected souls into Essence.",
                "§7Bulk action with confirmation timer (if configured).",
                "§7Destroyed souls are removed from your vault.")));
        inventory.setItem(23, infoItem(Material.AMETHYST_SHARD, "§bEssence", List.of(
                "§7Currency used for Combine, Enhance, and Reforge.",
                "§7Gained from salvage and (when configured) vault overflow.",
                "§7Check your balance in the Soul Table/vault UI.")));
        inventory.setItem(24, infoItem(Material.BOOK, "§bSelection Tips", List.of(
                "§7Click souls to select/deselect in the table grid.",
                "§7Combine needs 3 matching souls; Enhance/Reforge need 1.",
                "§7Salvage can select multiple souls.")));
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
        try {
            var tableMgr = plugin.getSoulTableManager();
            if (tableMgr != null && tableId != null) {
                var row = tableMgr.get(tableId);
                if (row != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> new SoulTableInv(plugin, player, row).open(player));
                    return;
                }
            }
        } catch (Throwable ignored) {}
        // Fallback: reopen a fresh table view bound to this context-less player if possible.
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                var tableMgr = plugin.getSoulTableManager();
                if (tableMgr == null) { player.closeInventory(); return; }
                // No known row; just close (table can be reopened by interacting with block).
                player.closeInventory();
            } catch (Throwable ignored) { player.closeInventory(); }
        });
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
