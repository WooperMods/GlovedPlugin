package dev.gloved.glovedplugin.summon.soul.table.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulTableRepository.SoulTableRow;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import dev.gloved.glovedplugin.summon.soul.item.SoulLoreBuilder;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableService;
import dev.gloved.glovedplugin.summon.soul.table.item.SoulCatalystItem;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitDefinition;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Infusion process: add or replace a soul trait (endgame mastery). */
public final class SoulTableInfusionInv implements SoulTableScreen {

    private enum Mode { ADD, REPLACE }

    private static final int SIZE = 54;
    private static final int GRID_START = 9;
    private static final int GRID_SIZE = 27; // 3 rows for soul/trait selection area
    private static final int SLOT_BACK = 0;
    private static final int SLOT_MODE = 1;
    private static final int SLOT_GUIDE = 8;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_CLEAR = 46;
    private static final int SLOT_PREVIEW = 48;
    private static final int SLOT_ESSENCE = 49;
    private static final int SLOT_CONFIRM = 50;
    private static final int SLOT_NEXT = 53;
    // current trait slots (for replace selection) shown when a soul is selected
    private static final int[] TRAIT_SLOTS = {36, 37, 38, 39, 40, 41};

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final SoulTableConfig FALLBACK = new SoulTableConfig();

    private final GlovedPlugin plugin;
    private final UUID viewerId;
    private final UUID tableId;
    private final Inventory inventory;

    private int page = 0;
    private int traitPage = 0;
    private List<SoulInstance> allSouls = List.of();
    private Map<UUID, SoulInstance> soulIndex = Map.of();
    private int essence = 0;
    private UUID selectedSoulId = null;
    private Mode mode = Mode.ADD;
    private String replaceTraitId = null;
    private String chosenTraitId = null;
    private boolean pickingTrait = false; // when true, grid shows eligible traits instead of souls

    public SoulTableInfusionInv(GlovedPlugin plugin, Player player, SoulTableRow table) {
        this.plugin = plugin;
        this.viewerId = player.getUniqueId();
        this.tableId = table != null ? table.tableId() : null;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Soul Table - Infusion", NamedTextColor.LIGHT_PURPLE));
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

    private SoulTableConfig cfg() {
        SoulService svc = plugin.getSoulService();
        SoulTableConfig c = svc != null ? svc.tableConfig() : null;
        return c != null ? c : FALLBACK;
    }

    private SoulTableService tableService() { return plugin.getSoulTableService(); }

    private SoulInstance selectedSoul() {
        return selectedSoulId != null ? soulIndex.get(selectedSoulId) : null;
    }

    @Override
    public void refresh() {
        SoulTableData.Snapshot snap = SoulTableData.load(plugin.getSoulService(), viewerId);
        allSouls = snap.souls();
        soulIndex = snap.index();
        essence = snap.essence();
        if (selectedSoulId != null && !soulIndex.containsKey(selectedSoulId)) {
            selectedSoulId = null;
            replaceTraitId = null;
            chosenTraitId = null;
            pickingTrait = false;
        }
        SoulInstance soul = selectedSoul();
        if (soul != null) {
            if (replaceTraitId != null && !soul.hasTraitId(replaceTraitId)) replaceTraitId = null;
            if (chosenTraitId != null) {
                SoulTableService svc = tableService();
                boolean stillEligible = false;
                if (svc != null) {
                    for (SoulTraitDefinition d : svc.eligibleInfusionTraits(soul, mode == Mode.REPLACE ? replaceTraitId : null)) {
                        if (d != null && chosenTraitId.equalsIgnoreCase(d.id())) { stillEligible = true; break; }
                    }
                }
                if (!stillEligible) chosenTraitId = null;
            }
        } else {
            pickingTrait = false;
        }

        int maxSoulPage = Math.max(0, (allSouls.size() - 1) / GRID_SIZE);
        page = Math.max(0, Math.min(page, maxSoulPage));

        inventory.clear();
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, SoulTableUiItems.filler());

        inventory.setItem(SLOT_BACK, SoulTableUiItems.backToHubItem(plugin));
        inventory.setItem(SLOT_MODE, modeItem());
        inventory.setItem(SLOT_GUIDE, SoulTableUiItems.actionItem(plugin, Material.BOOK, "§b§lSoul Guide",
                SoulTableUiKeys.ACTION_GUIDE, List.of("§7Infusion adds or replaces traits",
                        "§7(endgame). Applies on next summon.", "", "§eClick to open")));

        int navPage;
        int navMaxPage;
        String navContext;
        if (pickingTrait && soul != null) {
            int maxTraitPage = renderTraitPicker(soul);
            navPage = traitPage;
            navMaxPage = maxTraitPage;
            navContext = "Trait page";
        } else {
            pickingTrait = false;
            renderSoulGrid();
            navPage = page;
            navMaxPage = maxSoulPage;
            navContext = "Soul page";
        }
        renderBottom(soul);

        inventory.setItem(SLOT_PREV, SoulTableUiItems.pageNavItem(plugin, false, navPage > 0, navPage, navMaxPage, navContext));
        inventory.setItem(SLOT_NEXT, SoulTableUiItems.pageNavItem(plugin, true, navPage < navMaxPage, navPage, navMaxPage, navContext));
    }

    private void renderSoulGrid() {
        int from = page * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE; i++) {
            int index = from + i;
            if (index >= allSouls.size()) break;
            SoulInstance soul = allSouls.get(index);
            boolean selected = selectedSoulId != null && selectedSoulId.equals(soul.getSoulId());
            inventory.setItem(GRID_START + i, SoulTableUiItems.soulItem(plugin, soul, viewerId, selected));
        }
        // show selected soul + current traits panel in bottom info area
        SoulInstance soul = selectedSoul();
        for (int i = 0; i < TRAIT_SLOTS.length; i++) {
            int slot = TRAIT_SLOTS[i];
            inventory.setItem(slot, traitSlotItem(soul, i));
        }
        inventory.setItem(42, pickTraitButton(soul));
        inventory.setItem(43, selectedSoulInfo(soul));
        inventory.setItem(44, prereqInfo(soul));
    }

    private int renderTraitPicker(SoulInstance soul) {
        SoulTableService svc = tableService();
        List<SoulTraitDefinition> eligible = svc != null
                ? svc.eligibleInfusionTraits(soul, mode == Mode.REPLACE ? replaceTraitId : null)
                : List.of();
        int maxTraitPage = Math.max(0, (eligible.size() - 1) / GRID_SIZE);
        traitPage = Math.max(0, Math.min(traitPage, maxTraitPage));
        int from = traitPage * GRID_SIZE;
        for (int i = 0; i < GRID_SIZE; i++) {
            int idx = from + i;
            if (idx >= eligible.size()) break;
            SoulTraitDefinition def = eligible.get(idx);
            inventory.setItem(GRID_START + i, traitChoiceItem(soul, def));
        }
        inventory.setItem(42, SoulTableUiItems.actionItem(plugin, Material.BARRIER, "§cBack to Souls",
                SoulTableUiKeys.ACTION_CLEAR, List.of("§7Stop picking a trait and",
                        "§7return to soul selection.")));
        inventory.setItem(43, selectedSoulInfo(soul));
        inventory.setItem(44, prereqInfo(soul));
        for (int i = 0; i < TRAIT_SLOTS.length; i++) inventory.setItem(TRAIT_SLOTS[i], traitSlotItem(soul, i));
        return maxTraitPage;
    }

    private ItemStack modeItem() {
        boolean add = mode == Mode.ADD;
        Material mat = add ? Material.LIME_DYE : Material.ORANGE_DYE;
        String name = add ? "§aMode: ADD TRAIT" : "§eMode: REPLACE TRAIT";
        List<String> lore = new ArrayList<>();
        lore.add(add ? "§7Add a new trait if below cap." : "§7Replace one existing trait.");
        lore.add("§7Replace is cheaper than Add (config).");
        lore.add("");
        lore.add("§eClick to toggle mode");
        ItemStack item = SoulTableUiItems.actionItem(plugin, mat, name,
                add ? SoulTableUiKeys.ACTION_MODE_REPLACE : SoulTableUiKeys.ACTION_MODE_ADD, lore);
        if (item.getItemMeta() != null) {
            SoulTableUiItems.glint(item.getItemMeta());
            item.setItemMeta(item.getItemMeta());
        }
        return item;
    }

    private ItemStack traitSlotItem(SoulInstance soul, int index) {
        if (soul == null) {
            return SoulTableUiItems.infoItem(Material.GRAY_STAINED_GLASS_PANE, "§8Current Trait " + (index + 1),
                    List.of("§7Select a soul first."));
        }
        List<SoulTrait> traits = soul.getTraits();
        if (index >= traits.size() || traits.get(index) == null) {
            return SoulTableUiItems.infoItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§8Empty Trait Slot",
                    List.of(mode == Mode.ADD ? "§7Can be filled by Infusion (Add)." : "§7No trait in this slot."));
        }
        SoulTrait trait = traits.get(index);
        String id = trait.id();
        String display = trait.displayName();
        boolean selectedForReplace = mode == Mode.REPLACE && id != null && id.equalsIgnoreCase(replaceTraitId);
        List<String> lore = new ArrayList<>();
        lore.add("§7ID: §f" + id);
        List<String> desc = trait.guideDescription();
        if (desc != null) for (String line : desc) lore.add(line.startsWith("§") ? line : "§7" + line);
        if (mode == Mode.REPLACE) {
            lore.add("");
            lore.add(selectedForReplace ? "§aSelected to replace" : "§eClick to select for replacement");
        }
        ItemStack item = SoulTableUiItems.infoItem(selectedForReplace ? Material.ENCHANTED_BOOK : Material.BOOK,
                (selectedForReplace ? "§a" : "§d") + display, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            SoulTableUiKeys.tagAction(meta, plugin, SoulTableUiKeys.ACTION_SELECT_REPLACE_SLOT + id);
            if (selectedForReplace) SoulTableUiItems.glint(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack pickTraitButton(SoulInstance soul) {
        if (soul == null) {
            return SoulTableUiItems.infoItem(Material.GRAY_DYE, "§7Pick Trait",
                    List.of("§7Select a soul first."));
        }
        SoulTableService svc = tableService();
        String prereq = svc != null ? svc.infusionPrereqFailure(soul) : "Unavailable";
        if (prereq != null) {
            return SoulTableUiItems.infoItem(Material.GRAY_DYE, "§7Pick Trait", List.of("§c" + prereq));
        }
        if (mode == Mode.REPLACE && replaceTraitId == null) {
            return SoulTableUiItems.infoItem(Material.GRAY_DYE, "§7Pick Replacement Trait",
                    List.of("§7Select a current trait slot to replace."));
        }
        if (mode == Mode.ADD) {
            SoulTableConfig cfg = cfg();
            if (soul.traitCount() >= cfg.infusionTraitCap(soul.getRarity())) {
                return SoulTableUiItems.infoItem(Material.GRAY_DYE, "§7Pick Trait",
                        List.of("§cTrait capacity reached (" + cfg.infusionTraitCap(soul.getRarity()) + ")."));
            }
        }
        return SoulTableUiItems.actionItem(plugin, Material.WRITABLE_BOOK, "§d§lPick Trait",
                SoulTableUiKeys.ACTION_CONFIRM.replace("confirm", "pick_trait"), // not confirm; custom handled
                List.of("§7Browse eligible traits to infuse.", "", "§eClick to browse"));
    }

    private ItemStack selectedSoulInfo(SoulInstance soul) {
        if (soul == null) {
            return SoulTableUiItems.infoItem(Material.PAPER, "§eNo Soul Selected",
                    List.of("§7Select a soul from the grid.", "§7Then choose mode and trait."));
        }
        List<String> lore = new ArrayList<>();
        lore.add("§7Rarity: " + soul.getRarity().coloredNameLegacy());
        lore.add("§7Enhance: §f" + soul.getEnhanceLevel());
        lore.add("§7Traits: §f" + soul.traitCount() + "§7/§f" + cfg().infusionTraitCap(soul.getRarity()));
        lore.add("§7Family: §f" + SoulLoreBuilder.pretty(soul.getFamilyKey()));
        if (chosenTraitId != null) lore.add("§7Chosen: §d" + prettyTrait(chosenTraitId));
        return SoulTableUiItems.infoItem(Material.NAME_TAG, "§dTarget Soul", lore);
    }

    private ItemStack prereqInfo(SoulInstance soul) {
        SoulTableConfig cfg = cfg();
        List<String> lore = new ArrayList<>();
        lore.add("§7Min rarity: §f" + cfg.infusionMinRarity().name());
        lore.add("§7Min enhance: §f" + cfg.infusionMinEnhanceLevel());
        lore.add("§7Catalysts owned: §f" + SoulCatalystItem.countInInventory(plugin, Bukkit.getPlayer(viewerId)));
        if (soul != null) {
            String p = tableService() != null ? tableService().infusionPrereqFailure(soul) : null;
            lore.add(p == null ? "§aPrerequisites met." : "§c" + p);
        }
        return SoulTableUiItems.infoItem(Material.NETHER_STAR, "§5Infusion Requirements", lore);
    }

    private ItemStack traitChoiceItem(SoulInstance soul, SoulTraitDefinition def) {
        if (def == null) return SoulTableUiItems.filler();
        SoulTableService svc = tableService();
        boolean chosen = def.id() != null && def.id().equalsIgnoreCase(chosenTraitId);
        List<String> lore = new ArrayList<>();
        List<String> desc = def.guideDescription();
        if (desc != null) for (String line : desc) lore.add(line.startsWith("§") ? line : "§7" + line);
        if (svc != null && soul != null) {
            List<String> warnings = svc.infusionWarnings(soul, def, mode == Mode.REPLACE ? replaceTraitId : null);
            if (!warnings.isEmpty()) {
                lore.add("");
                lore.addAll(warnings);
            }
        }
        lore.add("");
        lore.add(chosen ? "§aSelected trait" : "§eClick to choose this trait");
        ItemStack item = SoulTableUiItems.infoItem(chosen ? Material.ENCHANTED_BOOK : Material.KNOWLEDGE_BOOK,
                (chosen ? "§a" : "§d") + def.displayName(), lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            SoulTableUiKeys.tagAction(meta, plugin, SoulTableUiKeys.ACTION_SELECT_TRAIT + def.id());
            if (chosen) SoulTableUiItems.glint(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void renderBottom(SoulInstance soul) {
        SoulTableService svc = tableService();
        SoulTableConfig cfg = cfg();
        Player player = Bukkit.getPlayer(viewerId);
        int catalysts = player != null ? SoulCatalystItem.countInInventory(plugin, player) : 0;
        String action = mode == Mode.ADD ? "ADD" : "REPLACE";
        SoulRarity rarity = soul != null ? soul.getRarity() : null;
        int eCost = rarity != null ? cfg.infusionEssenceCost(action, rarity) : 0;
        int cCost = cfg.infusionCatalystCost(action);

        List<String> eLore = new ArrayList<>();
        eLore.add("§7Mode: §f" + action);
        eLore.add("§7Essence cost: §b" + (rarity != null ? eCost : "-"));
        eLore.add("§7Catalyst cost: §d" + (rarity != null ? cCost : "-"));
        eLore.add("§7Catalysts owned: §f" + catalysts);
        inventory.setItem(SLOT_ESSENCE, SoulTableUiItems.infoItem(Material.AMETHYST_SHARD,
                "§b§lSoul Essence: §f" + essence, eLore));

        inventory.setItem(SLOT_PREVIEW, previewItem(soul, eCost, cCost, catalysts));
        inventory.setItem(SLOT_CONFIRM, confirmItem(soul, eCost, cCost, catalysts));
        inventory.setItem(SLOT_CLEAR, SoulTableUiItems.actionItem(plugin, Material.BARRIER, "§cClear / Back",
                SoulTableUiKeys.ACTION_CLEAR, List.of("§7Clear infusion choices.",
                        "§7If browsing traits, returns to souls.")));
    }

    private ItemStack previewItem(SoulInstance soul, int eCost, int cCost, int catalysts) {
        List<String> lore = new ArrayList<>();
        if (soul == null) {
            lore.add("§7Select a soul to preview infusion.");
            return SoulTableUiItems.infoItem(Material.SPYGLASS, "§eInfusion Preview", lore);
        }
        SoulTableService svc = tableService();
        lore.add("§7Target: §f" + SoulLoreBuilder.pretty(soul.getFamilyKey()) + " §7(" + soul.getRarity().name() + ")");
        lore.add("§7Mode: §f" + mode.name());
        if (mode == Mode.REPLACE) {
            lore.add("§7Replace: §f" + (replaceTraitId != null ? prettyTrait(replaceTraitId) : "§c<none selected>"));
        }
        lore.add("§7Chosen: §f" + (chosenTraitId != null ? prettyTrait(chosenTraitId) : "§c<none selected>"));
        lore.add("");
        lore.add("§7Before traits: §f" + formatTraits(soul.getTraits()));
        String after = projectedTraitText(soul);
        lore.add("§7After traits:  §a" + after);
        lore.add("");
        lore.add("§7Essence: §b" + eCost + " §7(you: §f" + essence + "§7)");
        lore.add("§7Catalysts: §d" + cCost + " §7(you: §f" + catalysts + "§7)");
        String prereq = svc != null ? svc.infusionPrereqFailure(soul) : null;
        if (prereq != null) lore.add("§c" + prereq);
        if (svc != null && chosenTraitId != null) {
            SoulTraitDefinition def = null;
            try { def = svc.eligibleInfusionTraits(soul, mode == Mode.REPLACE ? replaceTraitId : null)
                    .stream().filter(d -> d.id() != null && d.id().equalsIgnoreCase(chosenTraitId)).findFirst().orElse(null); } catch (Throwable ignored) {}
            if (def == null) {
                try { def = dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRegistry.getActive().byId(chosenTraitId).orElse(null); } catch (Throwable ignored) {}
            }
            if (def != null) {
                List<String> warnings = svc.infusionWarnings(soul, def, mode == Mode.REPLACE ? replaceTraitId : null);
                for (String w : warnings) lore.add(w);
            }
        }
        lore.add("");
        lore.add("§8Applies on next summon.");
        return SoulTableUiItems.infoItem(Material.SPYGLASS, "§eInfusion Preview", lore);
    }

    private ItemStack confirmItem(SoulInstance soul, int eCost, int cCost, int catalysts) {
        SoulTableService svc = tableService();
        boolean ok = false;
        List<String> lore = new ArrayList<>();
        if (svc == null) {
            lore.add("§cSoul system unavailable.");
        } else if (soul == null) {
            lore.add("§7Select a soul.");
        } else {
            String prereq = svc.infusionPrereqFailure(soul);
            if (prereq != null) lore.add("§c" + prereq);
            if (mode == Mode.REPLACE && replaceTraitId == null) lore.add("§cSelect a trait to replace.");
            if (chosenTraitId == null) lore.add("§cSelect a trait to infuse.");
            if (essence < eCost) lore.add("§cNot enough Essence.");
            if (catalysts < cCost) lore.add("§cNot enough Soul Catalysts.");
            if (mode == Mode.ADD) {
                if (soul.traitCount() >= cfg().infusionTraitCap(soul.getRarity())) lore.add("§cTrait capacity reached.");
                if (chosenTraitId != null && soul.hasTraitId(chosenTraitId)) lore.add("§cTrait already present.");
            } else {
                if (replaceTraitId != null && !soul.hasTraitId(replaceTraitId)) lore.add("§cReplace source trait missing.");
                if (chosenTraitId != null && soul.hasTraitId(chosenTraitId)) lore.add("§cTrait already present.");
            }
            if (cfg().isInfusionStrictRollConditions() && chosenTraitId != null) {
                try {
                    var defOpt = dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRegistry.getActive().byId(chosenTraitId);
                    if (defOpt.isPresent()) {
                        // preview/warnings path covers strict info; canInfuse* enforces on commit
                    }
                } catch (Throwable ignored) {}
            }
            boolean can = prereq == null && chosenTraitId != null
                    && (mode == Mode.ADD || replaceTraitId != null)
                    && essence >= eCost && catalysts >= cCost;
            if (mode == Mode.ADD) can = can && svc.canInfuseAdd(soul, chosenTraitId);
            else can = can && svc.canInfuseReplace(soul, replaceTraitId, chosenTraitId);
            ok = can;
            if (ok) {
                lore.add("§aReady to infuse.");
                lore.add("§7Cost: §b" + eCost + " Essence§7, §d" + cCost + " Catalyst(s)");
                lore.add("§8Applies on next summon.");
            }
        }
        ItemStack item = new ItemStack(ok ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LEGACY.deserialize(ok ? "§aConfirm Infusion" : "§7Confirm Infusion").decoration(TextDecoration.ITALIC, false));
            List<Component> comps = new ArrayList<>();
            for (String line : lore) comps.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            meta.lore(comps);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (ok) SoulTableUiItems.glint(meta);
            SoulTableUiKeys.tagAction(meta, plugin, SoulTableUiKeys.ACTION_CONFIRM);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String projectedTraitText(SoulInstance soul) {
        if (soul == null) return "-";
        List<String> names = new ArrayList<>();
        if (mode == Mode.REPLACE && replaceTraitId != null) {
            for (SoulTrait t : soul.getTraits()) {
                if (t == null) continue;
                if (replaceTraitId.equalsIgnoreCase(t.id())) {
                    if (chosenTraitId != null) names.add(prettyTrait(chosenTraitId));
                } else names.add(t.displayName());
            }
            if (chosenTraitId == null) return formatTraits(soul.getTraits());
        } else {
            for (SoulTrait t : soul.getTraits()) if (t != null) names.add(t.displayName());
            if (mode == Mode.ADD && chosenTraitId != null) names.add(prettyTrait(chosenTraitId));
        }
        return names.isEmpty() ? "None" : String.join("§7, §d", names);
    }

    private String formatTraits(List<SoulTrait> traits) {
        if (traits == null || traits.isEmpty()) return "None";
        List<String> names = new ArrayList<>();
        for (SoulTrait t : traits) if (t != null) names.add(t.displayName());
        return names.isEmpty() ? "None" : String.join("§7, §d", names);
    }

    private String prettyTrait(String id) {
        if (id == null) return "?";
        try {
            var reg = dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRegistry.getActive();
            if (reg != null) {
                var d = reg.byId(id);
                if (d.isPresent()) return d.get().displayName();
            }
        } catch (Throwable ignored) {}
        String[] parts = id.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? id : sb.toString();
    }

    @Override
    public void handleClick(Player player, int rawSlot, ClickType clickType) {
        if (player == null) return;
        if (!viewerId.equals(player.getUniqueId())) { player.closeInventory(); return; }
        if (rawSlot < 0 || rawSlot >= SIZE) return;
        ItemStack clicked = inventory.getItem(rawSlot);
        if (clicked == null || clicked.getType().isAir()) return;
        String action = SoulTableUiKeys.readAction(plugin, clicked);
        UUID soulId = SoulTableUiKeys.readSoulId(plugin, clicked);

        if (action != null) {
            handleAction(player, action);
            return;
        }
        if (soulId != null) {
            selectSoul(soulId);
            return;
        }
    }

    private void handleAction(Player player, String action) {
        if (action == null) return;
        switch (action) {
            case SoulTableUiKeys.ACTION_BACK_HUB -> SoulTableNav.openHub(plugin, player, tableId);
            case SoulTableUiKeys.ACTION_GUIDE ->
                    SoulTableNav.openGuide(plugin, player, tableId, SoulTableGuideInv.Page.MAIN, SoulTableReturnTarget.INFUSION);
            case SoulTableUiKeys.ACTION_PREV -> {
                if (pickingTrait) { if (traitPage > 0) traitPage--; }
                else if (page > 0) page--;
                refresh();
            }
            case SoulTableUiKeys.ACTION_NEXT -> {
                if (pickingTrait) {
                    SoulInstance soul = selectedSoul();
                    SoulTableService svc = tableService();
                    int count = (soul != null && svc != null)
                            ? svc.eligibleInfusionTraits(soul, mode == Mode.REPLACE ? replaceTraitId : null).size() : 0;
                    int max = Math.max(0, (count - 1) / GRID_SIZE);
                    if (traitPage < max) traitPage++;
                } else {
                    int max = Math.max(0, (allSouls.size() - 1) / GRID_SIZE);
                    if (page < max) page++;
                }
                refresh();
            }
            case SoulTableUiKeys.ACTION_MODE_ADD -> { mode = Mode.ADD; replaceTraitId = null; chosenTraitId = null; refresh(); }
            case SoulTableUiKeys.ACTION_MODE_REPLACE -> { mode = Mode.REPLACE; chosenTraitId = null; refresh(); }
            case SoulTableUiKeys.ACTION_CLEAR -> {
                if (pickingTrait) { pickingTrait = false; traitPage = 0; }
                else { selectedSoulId = null; replaceTraitId = null; chosenTraitId = null; traitPage = 0; pickingTrait = false; }
                refresh();
            }
            case SoulTableUiKeys.ACTION_CONFIRM -> confirm(player);
            default -> {
                if ("pick_trait".equals(action)) {
                    SoulInstance soul = selectedSoul();
                    if (soul == null) return;
                    SoulTableService svc = tableService();
                    if (svc == null) return;
                    if (svc.infusionPrereqFailure(soul) != null) {
                        SoulTableUiItems.actionBar(player, svc.infusionPrereqFailure(soul), NamedTextColor.RED);
                        return;
                    }
                    if (mode == Mode.REPLACE && replaceTraitId == null) {
                        SoulTableUiItems.actionBar(player, "Select a current trait to replace.", NamedTextColor.RED);
                        return;
                    }
                    pickingTrait = true;
                    traitPage = 0;
                    refresh();
                } else if (action.startsWith(SoulTableUiKeys.ACTION_SELECT_TRAIT)) {
                    chosenTraitId = action.substring(SoulTableUiKeys.ACTION_SELECT_TRAIT.length());
                    refresh();
                } else if (action.startsWith(SoulTableUiKeys.ACTION_SELECT_REPLACE_SLOT)) {
                    if (mode == Mode.REPLACE) {
                        replaceTraitId = action.substring(SoulTableUiKeys.ACTION_SELECT_REPLACE_SLOT.length());
                        chosenTraitId = null; // replacing source changed
                        pickingTrait = false;
                    }
                    refresh();
                }
            }
        }
    }

    private void selectSoul(UUID soulId) {
        SoulInstance soul = soulIndex.get(soulId);
        if (soul == null) return;
        if (soulId.equals(selectedSoulId)) {
            // keep selected; click again no-op (clear via button)
        } else {
            selectedSoulId = soulId;
            replaceTraitId = null;
            chosenTraitId = null;
            traitPage = 0;
            pickingTrait = false;
        }
        refresh();
    }

    private void confirm(Player player) {
        SoulTableService svc = tableService();
        SoulInstance soul = selectedSoul();
        if (svc == null) { SoulTableUiItems.actionBar(player, "Soul system is not initialized.", NamedTextColor.RED); return; }
        if (soul == null) { SoulTableUiItems.actionBar(player, "Select a soul.", NamedTextColor.RED); return; }
        if (chosenTraitId == null) { SoulTableUiItems.actionBar(player, "Select a trait to infuse.", NamedTextColor.RED); return; }
        SoulTableService.TableResult res;
        if (mode == Mode.ADD) {
            res = svc.infuseAddTrait(viewerId, player, soul.getSoulId(), chosenTraitId);
        } else {
            if (replaceTraitId == null) { SoulTableUiItems.actionBar(player, "Select a trait to replace.", NamedTextColor.RED); return; }
            res = svc.infuseReplaceTrait(viewerId, player, soul.getSoulId(), replaceTraitId, chosenTraitId);
        }
        if (res == null || !res.ok()) {
            SoulTableUiItems.actionBar(player, res != null ? res.message() : "Infusion failed.", NamedTextColor.RED);
        } else {
            SoulTableUiItems.actionBar(player, "Infusion complete.", NamedTextColor.GREEN);
            player.sendMessage(SoulTableUiItems.chatPrefix().append(Component.text(res.message(), NamedTextColor.GRAY)));
            // keep soul selected context but reset choices
            chosenTraitId = null;
            replaceTraitId = null;
            pickingTrait = false;
        }
        refresh();
    }
}
