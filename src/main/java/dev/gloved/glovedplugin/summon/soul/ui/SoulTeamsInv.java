package dev.gloved.glovedplugin.summon.soul.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.item.SoulDropItem;
import dev.gloved.glovedplugin.summon.soul.item.SoulLoreBuilder;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.ui.UiIcons;
import net.kyori.adventure.text.Component;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Teams view for soul team/loadout system.
 * Right-click Soul Focus opens this view. Click slot -> vault picker for assignment.
 */
public class SoulTeamsInv implements InventoryHolder {
    public static final String TITLE_PREFIX = "Soul Groups";

    private final GlovedPlugin plugin;
    private final UUID viewerId;

    public SoulTeamsInv(GlovedPlugin plugin, UUID viewerId) {
        this.plugin = plugin;
        this.viewerId = viewerId;
    }

    public static NamespacedKey navKey(GlovedPlugin plugin) { return new NamespacedKey(plugin, "soul_teams_nav"); }
    public static NamespacedKey teamKey(GlovedPlugin plugin) { return new NamespacedKey(plugin, "soul_teams_team"); }
    public static NamespacedKey slotKey(GlovedPlugin plugin) { return new NamespacedKey(plugin, "soul_teams_slot"); }

    public UUID getViewerId() { return viewerId; }

    public void open(Player player) {
        if (player == null) return;
        player.openInventory(getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        SoulService svc = plugin.getSoulService();
        int selectedTeam = svc != null ? svc.getSelectedTeam(viewerId) : 1;
        int activeTeam = -1;
        try {
            if (plugin.getSummonManager() != null) activeTeam = plugin.getSummonManager().getActiveTeamIndex(viewerId);
        } catch (Throwable ignored) {}
        boolean hasActive = false;
        try { hasActive = plugin.getSummonManager() != null && plugin.getSummonManager().hasActiveTeamSummons(viewerId); } catch (Throwable ignored) {}
        if (activeTeam == -1 && hasActive) activeTeam = -999; // mixed/legacy

        int teamCount = 4;
        int slotsPerTeam = 4;
        try {
            var cfg = svc != null ? svc.soulConfig() : null;
            if (cfg != null) {
                teamCount = Math.max(1, Math.min(4, cfg.getTeamCount()));
                slotsPerTeam = Math.max(1, Math.min(4, cfg.getTeamSlotsPerTeam()));
            }
        } catch (Throwable ignored) {}

        String title = "§5" + TITLE_PREFIX + " §7(Selected: " + selectedTeam + ")";
        Inventory inv = Bukkit.createInventory(this, 54, LegacyComponentSerializer.legacySection().deserialize(title));

        // Filler
        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Vertical layout: teams in columns 2,4,6,8 with empty columns between.
        // Per team column: row 0 = team label, rows 1..slotsPerTeam = soul slots, row 5 = activate/recall.
        int[] teamColumns = {2, 4, 6, 8};
        for (int t = 1; t <= teamCount; t++) {
            int col = teamColumns[t - 1];
            boolean sel = t == selectedTeam;
            boolean act = t == activeTeam;
            inv.setItem(col, teamLabelItem(t, sel, act)); // row 0
            Map<Integer, UUID> slots = svc != null ? svc.getTeam(viewerId, t) : Map.of();
            for (int s = 0; s < slotsPerTeam; s++) {
                int invSlot = (1 + s) * 9 + col; // rows 1..4
                UUID soulId = slots.get(s);
                SoulInstance soul = soulId != null && svc != null ? svc.getSoul(soulId) : null;
                inv.setItem(invSlot, teamSlotItem(t, s, soul, svc));
            }
            inv.setItem(5 * 9 + col, activateTeamItem(t, sel, act)); // row 5
        }

        // Global controls (column 0)
        inv.setItem(0 * 9 + 0, navItem(Material.CHEST, "§dSoul Vault", "vault", UiIcons.VAULT,
                "§7Browse, manage, extract, and salvage souls"));
        inv.setItem(4 * 9 + 0, navItem(Material.BARRIER, "§eRecall Active Group", "recall", UiIcons.BACK));
        inv.setItem(5 * 9 + 0, navItem(Material.OAK_DOOR, "§cClose", "close", UiIcons.CLOSE));

        return inv;
    }

    private ItemStack teamLabelItem(int team, boolean selected, boolean active) {
        Material mat = active ? Material.LIME_CONCRETE : (selected ? Material.YELLOW_CONCRETE : Material.LIGHT_BLUE_CONCRETE);
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = "§bGroup " + team + (active ? " §a(Active)" : selected ? " §e(Selected)" : "");
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, "select_team");
            meta.getPersistentDataContainer().set(teamKey(plugin), PersistentDataType.INTEGER, team);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack activateTeamItem(int team, boolean selected, boolean active) {
        ItemStack it = new ItemStack(active ? Material.REDSTONE_TORCH : Material.ENDER_EYE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = active ? "§eRecall Group " + team : "§aActivate Group " + team;
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, "activate");
            meta.getPersistentDataContainer().set(teamKey(plugin), PersistentDataType.INTEGER, team);
            it.setItemMeta(meta);
        }
        return UiIcons.apply(it, active ? UiIcons.BACK : UiIcons.TEAMS);
    }

    private ItemStack teamSlotItem(int team, int slot, SoulInstance soul, SoulService svc) {
        if (soul == null) {
            ItemStack it = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§7Empty Slot").decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        LegacyComponentSerializer.legacySection().deserialize("§aLeft-click: assign").decoration(TextDecoration.ITALIC, false)
                ));
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, "slot");
                meta.getPersistentDataContainer().set(teamKey(plugin), PersistentDataType.INTEGER, team);
                meta.getPersistentDataContainer().set(slotKey(plugin), PersistentDataType.INTEGER, slot);
                it.setItemMeta(meta);
            }
            return it;
        }
        ItemStack it = new ItemStack(SoulDropItem.materialFor(soul.getEntityType()));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String mobName = prettyMobName(soul.getFamilyKey());
            Component rarityName = soul.getRarity().coloredNameComponent();
            Component rest = (soul.getRarity() == SoulRarity.MYTHIC)
                    ? Component.text(" " + mobName + " Soul", net.kyori.adventure.text.format.NamedTextColor.RED).decoration(TextDecoration.BOLD, false)
                    : Component.text(" " + mobName + " Soul", soul.getRarity().getColor());
            meta.displayName(Component.empty().append(rarityName).append(rest).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.addAll(SoulLoreBuilder.statsSection(plugin, soul, viewerId));
            lore.addAll(SoulLoreBuilder.traitsSection(soul));
            lore.add(Component.empty());
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§aLeft-click: replace").decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§eRight-click: clear").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, "slot");
            meta.getPersistentDataContainer().set(teamKey(plugin), PersistentDataType.INTEGER, team);
            meta.getPersistentDataContainer().set(slotKey(plugin), PersistentDataType.INTEGER, slot);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack navItem(Material mat, String name, String action, String iconModel, String... loreLines) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name).decoration(TextDecoration.ITALIC, false));
            if (loreLines != null && loreLines.length > 0) {
                List<Component> lore = new ArrayList<>();
                for (String l : loreLines) lore.add(LegacyComponentSerializer.legacySection().deserialize(l).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (action != null) meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, action);
            it.setItemMeta(meta);
        }
        return UiIcons.apply(it, iconModel);
    }

    private static ItemStack pane(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name).decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String prettyMobName(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }
}
