package dev.gloved.glovedplugin.summon.soul.codex.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.codex.SoulCodexService;
import dev.gloved.glovedplugin.summon.soul.db.SoulRepository;
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
import java.util.UUID;

/**
 * Paginated mob soul codex menu (MVP): shows discovered mob souls, tier progress,
 * and allows claiming reached tier rewards.
 */
public class SoulCodexInv implements InventoryHolder {
    public static final String TITLE_PREFIX = "Soul Codex";

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final int page;
    private final List<SoulRepository.CodexEntry> pageEntries;
    private final int total;
    private final int maxPage;

    public SoulCodexInv(GlovedPlugin plugin, UUID viewerId, int page) {
        this.plugin = plugin;
        this.viewerId = viewerId;
        PageData pd = loadPage(Math.max(0, page));
        this.page = pd.page;
        this.pageEntries = pd.items;
        this.total = pd.total;
        this.maxPage = pd.maxPage;
    }

    public static NamespacedKey navKey(GlovedPlugin plugin) { return new NamespacedKey(plugin, "soul_codex_nav"); }
    public static NamespacedKey familyKey(GlovedPlugin plugin) { return new NamespacedKey(plugin, "soul_codex_family"); }

    public UUID getViewerId() { return viewerId; }
    public int getPage() { return page; }

    private record PageData(List<SoulRepository.CodexEntry> items, int page, int total, int maxPage) {}

    private PageData loadPage(int requestedPage) {
        SoulService svc = plugin.getSoulService();
        if (svc == null) return new PageData(List.of(), 0, 0, 0);
        int totalCount = svc.countCodex(viewerId);
        if (totalCount <= 0) return new PageData(List.of(), 0, 0, 0);
        // repository list is ordered; fetch a reasonable window for pagination
        List<SoulRepository.CodexEntry> all = svc.listCodex(viewerId, 500);
        int total = Math.max(totalCount, all.size());
        int max = Math.max(0, (total - 1) / 45);
        int p = Math.min(Math.max(0, requestedPage), max);
        int from = p * 45;
        if (from >= all.size()) return new PageData(List.of(), p, total, max);
        int to = Math.min(all.size(), from + 45);
        return new PageData(all.subList(from, to), p, total, max);
    }

    public void open(Player player) {
        if (player == null) return;
        player.openInventory(getInventory());
    }

    @Override
    public @NotNull Inventory getInventory() {
        String title = maxPage > 0
                ? "§b" + TITLE_PREFIX + " §7(" + (page + 1) + "/" + (maxPage + 1) + ") §8" + total
                : "§b" + TITLE_PREFIX + " §8" + total;
        Inventory inv = Bukkit.createInventory(this, 54, LegacyComponentSerializer.legacySection().deserialize(title));

        ItemStack filler = pane(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        SoulService svc = plugin.getSoulService();
        SoulCodexService codex = svc != null ? svc.getCodexService() : null;

        for (int i = 0; i < pageEntries.size() && i < 45; i++) {
            inv.setItem(i, entryItem(pageEntries.get(i), codex));
        }

        if (maxPage > 0) {
            if (page > 0) inv.setItem(45, navItem(Material.ARROW, "§ePrevious Page", "prev", UiIcons.ARROW_LEFT));
            else inv.setItem(45, navItem(Material.GRAY_STAINED_GLASS_PANE, "§7Previous Page", null, UiIcons.ARROW_LEFT_DISABLED));
            if (page < maxPage) inv.setItem(53, navItem(Material.ARROW, "§eNext Page", "next", UiIcons.ARROW_RIGHT));
            else inv.setItem(53, navItem(Material.GRAY_STAINED_GLASS_PANE, "§7Next Page", null, UiIcons.ARROW_RIGHT_DISABLED));
        }

        inv.setItem(48, infoItem());
        inv.setItem(49, navItem(Material.ARROW, "§eBack to Groups", "back", UiIcons.TEAMS));
        inv.setItem(50, helpItem());

        return inv;
    }

    private ItemStack entryItem(SoulRepository.CodexEntry e, SoulCodexService codex) {
        boolean anyClaimable = false;
        if (codex != null && e != null) {
            for (SoulCodexService.Tier t : SoulCodexService.Tier.values()) {
                var st = codex.tierState(e, t);
                if (st.reached() && !st.claimed()) { anyClaimable = true; break; }
            }
        }
        ItemStack it = new ItemStack(anyClaimable ? Material.ENCHANTED_BOOK : Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null && e != null) {
            String famPretty = prettyFamilyKey(e.familyKey());
            meta.displayName(LegacyComponentSerializer.legacySection()
                    .deserialize("§b" + famPretty + " §8(" + e.familyKey() + ")")
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Best rarity: §f" + safe(e.highestRarity())).decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Captured: §f" + e.capturedCount() + " §8| §7Refined: §f" + e.refinedCount() + " §8| §7Mastered: §f" + e.masteredCount()).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            if (codex != null) {
                lore.add(tierLine(codex, e, SoulCodexService.Tier.DISCOVERED, "Discovered"));
                lore.add(tierLine(codex, e, SoulCodexService.Tier.COLLECTED, "Collected"));
                lore.add(tierLine(codex, e, SoulCodexService.Tier.REFINED, "Refined"));
                lore.add(tierLine(codex, e, SoulCodexService.Tier.MASTERED, "Mastered"));
                lore.add(Component.empty());
                if (anyClaimable) {
                    lore.add(LegacyComponentSerializer.legacySection().deserialize("§aLeft-click: claim next available tier").decoration(TextDecoration.ITALIC, false));
                } else {
                    lore.add(LegacyComponentSerializer.legacySection().deserialize("§7No claimable tier rewards right now").decoration(TextDecoration.ITALIC, false));
                }
            } else {
                lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Codex service unavailable").decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(familyKey(plugin), PersistentDataType.STRING, e.familyKey());
            meta.getPersistentDataContainer().set(navKey(plugin), PersistentDataType.STRING, "entry");
            it.setItemMeta(meta);
        }
        return it;
    }

    private static Component tierLine(SoulCodexService codex, SoulRepository.CodexEntry e, SoulCodexService.Tier tier, String label) {
        var st = codex.tierState(e, tier);
        String state;
        if (st.claimed()) state = "§aCLAIMED";
        else if (st.reached()) state = "§eCLAIMABLE";
        else state = "§8LOCKED";
        String reward = "";
        if (st.reward() != null && st.reward().amount() > 0) {
            reward = " §7(§b+" + st.reward().amount() + " " + st.reward().type().name().toLowerCase(Locale.ROOT) + "§7)";
        }
        String suffix = "";
        if (tier == SoulCodexService.Tier.COLLECTED && st.reward() != null && !st.reached()) {
            suffix = " §8[" + e.capturedCount() + "/" + st.reward().minCaptured() + "]";
        }
        return LegacyComponentSerializer.legacySection()
                .deserialize("§7" + label + ": " + state + reward + suffix)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack infoItem() {
        ItemStack it = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§bSoul Codex").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Discovered mob " + (total == 1 ? "soul" : "souls") + ": §f" + total).decoration(TextDecoration.ITALIC, false));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Tiers: §fDiscovered §8/ §fCollected §8/ §fRefined §8/ §fMastered").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Progress updates from captures, refining, and mastery.").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack helpItem() {
        ItemStack it = navItem(Material.BOOK, "§dControls", "help", UiIcons.GUIDE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§aLeft-click entry§7: claim next reached tier").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
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

    private static String prettyFamilyKey(String familyKey) {
        if (familyKey == null || familyKey.isBlank()) return "Unknown";
        String[] parts = familyKey.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? familyKey : sb.toString();
    }

    private static String safe(String s) { return s == null || s.isBlank() ? "-" : s; }
}
