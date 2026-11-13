package dev.gloved.glovedplugin.commands.gloved;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulGenerator;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.soul.domain.SoulQuality;
import dev.gloved.glovedplugin.summon.soul.item.SoulItem;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableItem;
import dev.gloved.glovedplugin.summon.soul.trap.SoulTrapRecipeManager;
import dev.gloved.glovedplugin.summon.soul.trap.SoulTrapTier;
import dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv;

/**
 * Internal soul subcommand handler extracted from {@link GlovedCommand} (T2.4).
 * Behavior-preserving: preserves permissions, messages, parsing, side effects, and suggestions.
 */
public final class GlovedSoulSubcommandHandler {

    private final GlovedPlugin plugin;

    public GlovedSoulSubcommandHandler(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

public void handle(@NotNull CommandSourceStack source, @NotNull String[] args) {
    if (!source.getSender().hasPermission("gloved.soul")) {
        source.getSender().sendMessage(Component.text("You don't have permission to do that."));
        return;
    }
    SoulService svc = plugin.getSoulService();
    if (svc == null) {
        source.getSender().sendMessage(Component.text("Soul system is not initialized."));
        return;
    }
    String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
    switch (sub) {
        case "list" -> handleSoulList(source, args, svc);
        case "give" -> handleSoulGive(source, args, svc);
        case "simulate", "sim" -> handleSoulSimulate(source, args, svc);
        case "info", "status" -> handleSoulInfo(source, args, svc);
        case "select" -> handleSoulSelect(source, args, svc);
        case "equip" -> handleSoulEquip(source, args, svc);
        case "unequip" -> handleSoulUnequip(source, args, svc);
        case "loadout" -> handleSoulLoadout(source, args, svc);
        case "favorite", "fav" -> handleSoulFavorite(source, args, svc);
        case "salvage" -> handleSoulSalvage(source, args, svc);
        case "inspect" -> handleSoulInspect(source, args, svc);
        case "compare" -> handleSoulCompare(source, args, svc);
        case "menu", "vault", "gui" -> handleSoulMenu(source, args, svc);
        case "cast", "summon" -> handleSoulCast(source, svc);
        case "codex" -> handleSoulCodex(source, args, svc);
        case "trap", "traps" -> handleSoulTrapGive(source, args);
        case "table" -> handleSoulTable(source, args);
        case "essence" -> handleSoulEssence(source, args, svc);
        case "help" -> handleSoulHelp(source);
        default -> handleSoulHelp(source);
    }
}

private void handleSoulList(CommandSourceStack source, String[] args, SoulService svc) {
    Player target = null;
    int page = 0;
    String typeFilter = null;
    SoulRarity rarityFilter = null;
    String sort = "default";
    // /gloved soul list [page] [type|-] [rarity|-] [sort] [player]
    if (args.length >= 3) {
        int idx = 2;
        // player first?
        Player p0 = Bukkit.getPlayerExact(args[idx]);
        if (p0 == null) p0 = Bukkit.getPlayer(args[idx]);
        if (p0 != null && args.length == 3) { target = p0; idx++; }
        else if (isInt(args[idx])) { try { page = Math.max(0, Integer.parseInt(args[idx]) - 1); } catch (Exception ignored) {} idx++; }
        else if (p0 != null) { target = p0; idx++; }

        if (idx < args.length && target == null) {
            // could be page or type or player
            Player p = Bukkit.getPlayerExact(args[idx]);
            if (p == null) p = Bukkit.getPlayer(args[idx]);
            if (p != null && idx == args.length - 1) { target = p; idx++; }
        }
        if (idx < args.length) { if (!args[idx].equals("-") && !args[idx].equalsIgnoreCase("any")) typeFilter = args[idx].toUpperCase(Locale.ROOT); idx++; }
        if (idx < args.length) {
            if (!args[idx].equals("-") && !args[idx].equalsIgnoreCase("any")) rarityFilter = SoulRarity.fromString(args[idx], null);
            if (rarityFilter == null && (args[idx].equalsIgnoreCase("quality") || args[idx].equalsIgnoreCase("rarity") || args[idx].equalsIgnoreCase("type") || args[idx].equalsIgnoreCase("recent") || args[idx].equalsIgnoreCase("default"))) {
                sort = args[idx].toLowerCase(Locale.ROOT);
            }
            idx++;
        }
        if (idx < args.length) {
            String s = args[idx];
            if (s.equalsIgnoreCase("quality") || s.equalsIgnoreCase("rarity") || s.equalsIgnoreCase("type") || s.equalsIgnoreCase("recent") || s.equalsIgnoreCase("default")) {
                sort = s.toLowerCase(Locale.ROOT); idx++;
            }
        }
        if (idx < args.length && target == null) {
            Player p = Bukkit.getPlayerExact(args[idx]); if (p == null) p = Bukkit.getPlayer(args[idx]); if (p != null) target = p;
        }
    }
    if (target == null) {
        if (source.getSender() instanceof Player p) target = p;
        else {
            source.getSender().sendMessage(Component.text("Console must specify a player: /gloved soul list <page> [type|-] [rarity|-] [sort] <player>"));
            return;
        }
    }
    var state = svc.getState(target.getUniqueId());
    int total = svc.countSouls(target.getUniqueId());
    java.util.List<SoulInstance> souls = svc.listSoulsFiltered(target.getUniqueId(), typeFilter, rarityFilter, 0, 500);
    souls = new java.util.ArrayList<>(souls);
    java.util.Comparator<SoulInstance> cmp = switch (sort) {
        case "quality" -> java.util.Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed();
        case "rarity" -> java.util.Comparator.comparingInt((SoulInstance s) -> s.getRarity().ordinal()).reversed().thenComparing(java.util.Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed());
        case "type", "family" -> java.util.Comparator.comparing(SoulInstance::getFamilyKey).thenComparing(java.util.Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed());
        case "recent" -> java.util.Comparator.comparingLong(SoulInstance::getCreatedAt).reversed();
        default -> java.util.Comparator.comparing((SoulInstance s) -> s.isFavorite() ? 0 : 1)
                .thenComparing(java.util.Comparator.comparingDouble((SoulInstance x) -> SoulQuality.percent(x)).reversed())
                .thenComparing(java.util.Comparator.comparingLong(SoulInstance::getCreatedAt).reversed());
    };
    souls.sort(cmp);
    int pageSize = 8;
    int from = Math.min(page * pageSize, Math.max(0, souls.size()));
    int to = Math.min(souls.size(), from + pageSize);
    java.util.List<SoulInstance> pageList = souls.subList(from, to);
    int essence = state != null ? state.essence() : 0;
    int cap = state != null ? state.vaultCapacity() : 0;
    source.getSender().sendMessage(Component.text("=== Souls: " + target.getName() + " ===", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
    source.getSender().sendMessage(Component.text("Vault: " + total + "/" + cap + " | Essence: " + essence + " | Showing " + pageList.size() + "/" + souls.size() + " (filtered) | Page " + (page + 1) + " | Sort: " + sort, net.kyori.adventure.text.format.NamedTextColor.GRAY));
    if (typeFilter != null || rarityFilter != null) {
        source.getSender().sendMessage(Component.text("Filters: type=" + (typeFilter == null ? "-" : typeFilter) + " rarity=" + (rarityFilter == null ? "-" : rarityFilter.name()), net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
    }
    if (pageList.isEmpty()) {
        source.getSender().sendMessage(Component.text("(no souls)", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        return;
    }
    int i = 1 + from;
    for (SoulInstance s : pageList) {
        source.getSender().sendMessage(Component.text(i++ + ". " + SoulGenerator.describe(s) + " [" + s.getSoulId().toString().substring(0, 8) + "]",
                net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }
}

private static boolean isInt(String s) {
    if (s == null) return false;
    try { Integer.parseInt(s); return true; } catch (Exception e) { return false; }
}

private void handleSoulMenu(CommandSourceStack source, String[] args, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) {
        source.getSender().sendMessage(Component.text("Only players can open the soul vault menu."));
        return;
    }
    String type = null; SoulRarity rarity = null; String sort = "default"; int page = 0;
    if (args.length >= 3 && isInt(args[2])) { try { page = Math.max(0, Integer.parseInt(args[2]) - 1); } catch (Exception ignored) {} }
    if (args.length >= 3 && !isInt(args[2])) { if (!args[2].equals("-")) type = args[2].toUpperCase(Locale.ROOT); }
    if (args.length >= 4) { if (!args[3].equals("-")) rarity = SoulRarity.fromString(args[3], null); }
    if (args.length >= 5) sort = args[4].toLowerCase(Locale.ROOT);
    new SoulVaultInv(plugin, player.getUniqueId(), page, type, rarity, sort).open(player);
}

private void handleSoulCast(CommandSourceStack source, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) {
        source.getSender().sendMessage(Component.text("Only players can cast souls."));
        return;
    }
    svc.castSelected(player);
}

private void handleSoulGive(CommandSourceStack source, String[] args, SoulService svc) {
    // /gloved soul give <player> <entityType> [rarity]
    if (args.length < 4) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul give <player> <ENTITY_TYPE> [rarity]"));
        return;
    }
    Player target = Bukkit.getPlayerExact(args[2]);
    if (target == null) target = Bukkit.getPlayer(args[2]);
    if (target == null) {
        source.getSender().sendMessage(Component.text("Player not found: " + args[2]));
        return;
    }
    EntityType type = parseEntityType(args[3]);
    if (type == null) {
        source.getSender().sendMessage(Component.text("Unknown entity type: " + args[3]));
        return;
    }
    SoulRarity rarity = null;
    if (args.length >= 5) {
        rarity = SoulRarity.fromString(args[4], null);
        if (rarity == null) {
            source.getSender().sendMessage(Component.text("Unknown rarity: " + args[4]));
            return;
        }
    }
    SoulService.CaptureResult res = svc.giveSoul(target, type, rarity);
    if (res.captured()) {
        source.getSender().sendMessage(Component.text("Gave " + (res.soul() != null ? SoulGenerator.describe(res.soul()) : type.name())
                + " to " + target.getName() + (res.convertedToEssence() ? " (vault full → essence)" : ""), net.kyori.adventure.text.format.NamedTextColor.GREEN));
    } else {
        source.getSender().sendMessage(Component.text("Failed to give soul: " + res.reason(), net.kyori.adventure.text.format.NamedTextColor.RED));
    }
}

private void handleSoulSimulate(CommandSourceStack source, String[] args, SoulService svc) {
    // /gloved soul simulate <entityType> [rarity] [count]
    if (args.length < 3) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul simulate <ENTITY_TYPE> [rarity] [count]"));
        return;
    }
    EntityType type = parseEntityType(args[2]);
    if (type == null) {
        source.getSender().sendMessage(Component.text("Unknown entity type: " + args[2]));
        return;
    }
    SoulRarity rarity = null;
    int count = 1;
    if (args.length >= 4) {
        SoulRarity r = SoulRarity.fromString(args[3], null);
        if (r != null) {
            rarity = r;
            if (args.length >= 5) { try { count = Integer.parseInt(args[4]); } catch (Exception ignored) {} }
        } else {
            try { count = Integer.parseInt(args[3]); } catch (Exception ignored) {}
        }
    }
    count = Math.max(1, Math.min(20, count));
    source.getSender().sendMessage(Component.text("=== Soul Simulation: " + type.name() + " x" + count + " ===", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
    for (int i = 0; i < count; i++) {
        SoulInstance s = svc.simulate(type, rarity);
        source.getSender().sendMessage(Component.text((i + 1) + ". " + SoulGenerator.describe(s), net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }
}

private void handleSoulInfo(CommandSourceStack source, String[] args, SoulService svc) {
    Player target = null;
    if (args.length >= 3) {
        target = Bukkit.getPlayerExact(args[2]);
        if (target == null) target = Bukkit.getPlayer(args[2]);
    }
    if (target == null && source.getSender() instanceof Player p) target = p;
    if (target == null) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul info [player]"));
        return;
    }
    var state = svc.getState(target.getUniqueId());
    int total = svc.countSouls(target.getUniqueId());
    int essence = state != null ? state.essence() : 0;
    int cap = state != null ? state.vaultCapacity() : 0;
    int sel = state != null ? state.selectedSlot() : 0;
    int level = svc.getSummoningLevel(target.getUniqueId());
    int codexCount = svc.countCodex(target.getUniqueId());
    source.getSender().sendMessage(Component.text("=== Soul Info: " + target.getName() + " ===", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
    source.getSender().sendMessage(Component.text("Summoning level: " + level + " | Vault: " + total + "/" + cap + " | Essence: " + essence + " | Selected slot: " + sel, net.kyori.adventure.text.format.NamedTextColor.GRAY));
    source.getSender().sendMessage(Component.text("Codex discovered: " + codexCount + " families", net.kyori.adventure.text.format.NamedTextColor.GRAY));
    var soulCfg = svc.soulConfig();
    if (soulCfg != null) {
        double luck = Math.min(soulCfg.getCaptureLuckMaxBonus(), soulCfg.getCaptureLuckPerLevel() * level);
        source.getSender().sendMessage(Component.text("Capture: base=" + fmtPct(soulCfg.getCaptureBaseChance()) + " luck=" + fmtPct(luck) + " minionKills=" + (soulCfg.isCaptureMinionKills() ? "on x" + soulCfg.getCaptureMinionKillMultiplier() : "off") + " pity=" + (soulCfg.isPityEnabled() ? "on" : "off"), net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
        String manaBase = soulCfg.isCastManaEnabled() ? " manaBase=" + String.format(java.util.Locale.ROOT, "%.1f", soulCfg.getCastBaseManaCost()) : "";
        source.getSender().sendMessage(Component.text("Soul config: enabled=" + soulCfg.isEnabled() + " globalCapBase=" + soulCfg.computeGlobalMaxActive(0) + " now=" + soulCfg.computeGlobalMaxActive(level) + " castCDBase=" + String.format(java.util.Locale.ROOT, "%.1fs", soulCfg.getCastBaseCooldownTicks()/20.0) + manaBase, net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
        if (state != null && state.pityJson() != null) {
            source.getSender().sendMessage(Component.text("Pity: " + state.pityJson(), net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        }
    }
    SoulInstance selected = svc.getSelectedSoul(target.getUniqueId());
    if (selected != null && soulCfg != null) {
        long rem = 0;
        try { if (plugin.getSummonManager() != null) rem = plugin.getSummonManager().getSoulCastCooldownRemainingMs(target.getUniqueId()); } catch (Throwable ignored) {}
        int cdTicks = svc.computeCastCooldownTicks(selected, soulCfg);
        double mana = svc.computeCastManaCost(selected, soulCfg);
        String manaPart = (soulCfg.isCastManaEnabled() && mana > 0.0) ? " | castMana=" + String.format(java.util.Locale.ROOT, "%.1f", mana) : "";
        source.getSender().sendMessage(Component.text("Selected: " + SoulGenerator.describe(selected) + manaPart + " | castCD=" + String.format(java.util.Locale.ROOT, "%.1fs", cdTicks/20.0) + (rem > 0 ? " | cdLeft=" + String.format(java.util.Locale.ROOT, "%.1fs", rem/1000.0) : " | ready"), net.kyori.adventure.text.format.NamedTextColor.GREEN));
    } else {
        source.getSender().sendMessage(Component.text("Selected soul: (none)", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }
}

private void handleSoulSelect(CommandSourceStack source, String[] args, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) {
        source.getSender().sendMessage(Component.text("Only players can select a soul slot."));
        return;
    }
    if (args.length < 3) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul select <slot>"));
        return;
    }
    int slot;
    try { slot = Integer.parseInt(args[2]); } catch (Exception e) { source.getSender().sendMessage(Component.text("Invalid slot.")); return; }
    if (!svc.selectSlot(player.getUniqueId(), slot)) {
        source.getSender().sendMessage(Component.text("Could not select slot " + slot + " (out of range or unavailable).", net.kyori.adventure.text.format.NamedTextColor.RED));
        return;
    }
    SoulInstance sel = svc.getSelectedSoul(player.getUniqueId());
    try { SoulItem.refresh(player, plugin); } catch (Throwable ignored) {}
    source.getSender().sendMessage(Component.text("Selected slot " + slot + (sel != null ? ": " + SoulGenerator.describe(sel) : " (empty)"), net.kyori.adventure.text.format.NamedTextColor.GREEN));
}

private void handleSoulEquip(CommandSourceStack source, String[] args, SoulService svc) {
    // /gloved soul equip <slot> <soulIdPrefix>
    if (!(source.getSender() instanceof Player player)) {
        source.getSender().sendMessage(Component.text("Only players can equip souls."));
        return;
    }
    if (args.length < 4) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul equip <slot> <soulIdPrefix>"));
        return;
    }
    int slot;
    try { slot = Integer.parseInt(args[2]); } catch (Exception e) { source.getSender().sendMessage(Component.text("Invalid slot.")); return; }
    SoulInstance soul = svc.findOwnedSoul(player.getUniqueId(), args[3]);
    if (soul == null) {
        source.getSender().sendMessage(Component.text("Soul not found or ambiguous (use a longer ID prefix from /gloved soul list).", net.kyori.adventure.text.format.NamedTextColor.RED));
        return;
    }
    if (!svc.equipSoul(player.getUniqueId(), slot, soul)) {
        source.getSender().sendMessage(Component.text("Could not equip soul to slot " + slot + ".", net.kyori.adventure.text.format.NamedTextColor.RED));
        return;
    }
    try { SoulItem.refresh(player, plugin); } catch (Throwable ignored) {}
    source.getSender().sendMessage(Component.text("Equipped " + SoulGenerator.describe(soul) + " to slot " + slot + ".", net.kyori.adventure.text.format.NamedTextColor.GREEN));
}

private void handleSoulUnequip(CommandSourceStack source, String[] args, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) {
        source.getSender().sendMessage(Component.text("Only players can unequip souls."));
        return;
    }
    if (args.length < 3) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul unequip <slot>"));
        return;
    }
    int slot;
    try { slot = Integer.parseInt(args[2]); } catch (Exception e) { source.getSender().sendMessage(Component.text("Invalid slot.")); return; }
    if (!svc.unequipSlot(player.getUniqueId(), slot)) {
        source.getSender().sendMessage(Component.text("Could not unequip slot " + slot + ".", net.kyori.adventure.text.format.NamedTextColor.RED));
        return;
    }
    try { SoulItem.refresh(player, plugin); } catch (Throwable ignored) {}
    source.getSender().sendMessage(Component.text("Unequipped slot " + slot + ".", net.kyori.adventure.text.format.NamedTextColor.GREEN));
}

private void handleSoulLoadout(CommandSourceStack source, String[] args, SoulService svc) {
    Player target = (source.getSender() instanceof Player p) ? p : null;
    if (target == null) { source.getSender().sendMessage(Component.text("Only players can view loadout.")); return; }
    var state = svc.getState(target.getUniqueId());
    int slots = svc.loadoutSlotsFor(target.getUniqueId());
    var loadout = svc.getLoadout(target.getUniqueId());
    source.getSender().sendMessage(Component.text("=== Soul Loadout (" + target.getName() + ") ===", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
    source.getSender().sendMessage(Component.text("Slots: " + slots + " | Selected: " + (state != null ? state.selectedSlot() : 0), net.kyori.adventure.text.format.NamedTextColor.GRAY));
    for (int i = 0; i < slots; i++) {
        java.util.UUID sid = loadout.get(i);
        String label = "(empty)";
        if (sid != null) {
            SoulInstance s = svc.getSoul(sid);
            label = s != null ? SoulGenerator.describe(s) + " [" + SoulService.shortId(s) + "]" : sid.toString().substring(0, 8);
        }
        boolean sel = state != null && state.selectedSlot() == i;
        source.getSender().sendMessage(Component.text((sel ? "§a> " : "§7  ") + "Slot " + i + ": §f" + label));
    }
}

private void handleSoulFavorite(CommandSourceStack source, String[] args, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) { source.getSender().sendMessage(Component.text("Only players can modify souls.")); return; }
    if (args.length < 3) { source.getSender().sendMessage(Component.text("Usage: /gloved soul favorite <soulIdPrefix>")); return; }
    SoulInstance soul = svc.findOwnedSoul(player.getUniqueId(), args[2]);
    if (soul == null) { source.getSender().sendMessage(Component.text("Soul not found or ambiguous.")); return; }
    boolean ok = svc.toggleFavorite(player.getUniqueId(), soul);
    if (!ok) { source.getSender().sendMessage(Component.text("Failed to update soul.")); return; }
    String state = soul.isFavorite() ? "favorited" : "unfavorited";
    source.getSender().sendMessage(Component.text("Soul " + SoulService.shortId(soul) + " " + state + ".", net.kyori.adventure.text.format.NamedTextColor.GREEN));
}

private void handleSoulSalvage(CommandSourceStack source, String[] args, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) { source.getSender().sendMessage(Component.text("Only players can salvage souls.")); return; }
    if (args.length < 3) { source.getSender().sendMessage(Component.text("Usage: /gloved soul salvage <soulIdPrefix>")); return; }
    SoulInstance soul = svc.findOwnedSoul(player.getUniqueId(), args[2]);
    if (soul == null) { source.getSender().sendMessage(Component.text("Soul not found or ambiguous.")); return; }
    SoulService.SalvageResult res = svc.salvage(player.getUniqueId(), soul);
    if (!res.ok()) {
        source.getSender().sendMessage(Component.text("Cannot salvage: " + res.reason(), net.kyori.adventure.text.format.NamedTextColor.RED));
        return;
    }
    source.getSender().sendMessage(Component.text("Salvaged " + SoulGenerator.describe(soul) + " for +" + res.essenceGained() + " essence.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
}

private void handleSoulInspect(CommandSourceStack source, String[] args, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) { source.getSender().sendMessage(Component.text("Only players can inspect souls.")); return; }
    if (args.length < 3) { source.getSender().sendMessage(Component.text("Usage: /gloved soul inspect <soulIdPrefix>")); return; }
    SoulInstance soul = svc.findOwnedSoul(player.getUniqueId(), args[2]);
    if (soul == null) { source.getSender().sendMessage(Component.text("Soul not found or ambiguous.")); return; }
    for (String line : svc.inspect(soul).split("\n")) {
        source.getSender().sendMessage(Component.text(line));
    }
}

private void handleSoulCompare(CommandSourceStack source, String[] args, SoulService svc) {
    if (!(source.getSender() instanceof Player player)) { source.getSender().sendMessage(Component.text("Only players can compare souls.")); return; }
    if (args.length < 4) { source.getSender().sendMessage(Component.text("Usage: /gloved soul compare <soulIdPrefixA> <soulIdPrefixB>")); return; }
    SoulInstance a = svc.findOwnedSoul(player.getUniqueId(), args[2]);
    SoulInstance b = svc.findOwnedSoul(player.getUniqueId(), args[3]);
    if (a == null || b == null) { source.getSender().sendMessage(Component.text("One or both souls not found/ambiguous.")); return; }
    for (String line : svc.compare(a, b).split("\n")) {
        source.getSender().sendMessage(Component.text(line));
    }
}

private static EntityType parseEntityType(String raw) {
    if (raw == null) return null;
    try { return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return null; }
}

private void handleSoulCodex(CommandSourceStack source, String[] args, SoulService svc) {
    Player target = null;
    int limit = 20;
    if (args.length >= 3) {
        Player p = Bukkit.getPlayerExact(args[2]); if (p == null) p = Bukkit.getPlayer(args[2]);
        if (p != null) target = p; else { try { limit = Integer.parseInt(args[2]); } catch (Exception ignored) {} }
    }
    if (args.length >= 4) { try { limit = Integer.parseInt(args[3]); } catch (Exception ignored) {} }
    if (target == null && source.getSender() instanceof Player p) target = p;
    if (target == null) { source.getSender().sendMessage(Component.text("Usage: /gloved soul codex [player] [limit]")); return; }
    limit = Math.max(1, Math.min(100, limit));
    var entries = svc.listCodex(target.getUniqueId(), limit);
    int total = svc.countCodex(target.getUniqueId());
    source.getSender().sendMessage(Component.text("=== Soul Codex: " + target.getName() + " (" + Math.min(entries.size(), total) + "/" + total + ") ===", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
    if (entries.isEmpty()) {
        source.getSender().sendMessage(Component.text("(no discoveries yet)", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        return;
    }
    int i = 1;
    for (var e : entries) {
        source.getSender().sendMessage(Component.text(i++ + ". " + e.familyKey() + " §7- best §f" + e.highestRarity() + " §7captured §f" + e.capturedCount()));
    }
}

private void handleSoulTrapGive(CommandSourceStack source, String[] args) {
    // /gloved soul trap <tier> [amount] [player]
    if (args.length < 3) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul trap <snare|trap|cage|chalice|relic|prism> [amount] [player]"));
        return;
    }
    var tier = SoulTrapTier.fromId(args[2].toLowerCase(Locale.ROOT));
    if (tier == null) {
        source.getSender().sendMessage(Component.text("Unknown trap tier: " + args[2] + " (expected snare|trap|cage|chalice|relic|prism)", net.kyori.adventure.text.format.NamedTextColor.RED));
        return;
    }
    int amount = 1;
    Player target = null;
    if (args.length >= 4) {
        try {
            amount = Integer.parseInt(args[3]);
        } catch (Exception ignored) {
            target = Bukkit.getPlayerExact(args[3]);
            if (target == null) target = Bukkit.getPlayer(args[3]);
            if (target == null) {
                source.getSender().sendMessage(Component.text("Invalid amount or player: " + args[3], net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
        }
    }
    if (args.length >= 5) {
        target = Bukkit.getPlayerExact(args[4]);
        if (target == null) target = Bukkit.getPlayer(args[4]);
        if (target == null) {
            source.getSender().sendMessage(Component.text("Player not found: " + args[4], net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
    }
    if (target == null) {
        if (source.getSender() instanceof Player p) target = p;
        else {
            source.getSender().sendMessage(Component.text("Console must specify a player: /gloved soul trap <tier> [amount] <player>"));
            return;
        }
    }
    amount = Math.max(1, Math.min(64, amount));
    var item = SoulTrapRecipeManager.createTrapItem(tier);
    item.setAmount(amount);
    var leftover = target.getInventory().addItem(item);
    if (!leftover.isEmpty()) {
        for (var drop : leftover.values()) target.getWorld().dropItemNaturally(target.getLocation(), drop);
    }
    source.getSender().sendMessage(Component.text("Gave " + amount + "x " + tier.displayName.replace("&", "§") + " §ato " + target.getName(), net.kyori.adventure.text.format.NamedTextColor.GREEN));
}

private void handleSoulEssence(@NotNull CommandSourceStack source, @NotNull String[] args, SoulService svc) {
    // /gloved soul essence <give|set|remove> <player> <amount>
    if (args.length < 4) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul essence <give|set|remove> <player> <amount>"));
        return;
    }
    String action = args[2].toLowerCase(Locale.ROOT);
    if (!action.equals("give") && !action.equals("set") && !action.equals("remove")) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul essence <give|set|remove> <player> <amount>"));
        return;
    }
    if (args.length < 5) {
        source.getSender().sendMessage(Component.text("Usage: /gloved soul essence " + action + " <player> <amount>"));
        return;
    }
    Player target = Bukkit.getPlayerExact(args[3]);
    if (target == null) target = Bukkit.getPlayer(args[3]);
    if (target == null) {
        source.getSender().sendMessage(Component.text("Player not found: " + args[3]));
        return;
    }
    int amount;
    try {
        amount = Integer.parseInt(args[4]);
    } catch (NumberFormatException e) {
        source.getSender().sendMessage(Component.text("Invalid amount: " + args[4]));
        return;
    }
    if (action.equals("give")) {
        if (amount <= 0) {
            source.getSender().sendMessage(Component.text("Amount must be positive."));
            return;
        }
        svc.addEssence(target.getUniqueId(), amount);
        int total = svc.getEssence(target.getUniqueId());
        source.getSender().sendMessage(Component.text("Gave " + amount + " essence to " + target.getName() + " (now " + total + ").", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        if (!source.getSender().equals(target)) {
            target.sendMessage(Component.text("+" + amount + " essence.", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        }
    } else if (action.equals("set")) {
        if (amount < 0) {
            source.getSender().sendMessage(Component.text("Amount must be zero or positive."));
            return;
        }
        svc.setEssence(target.getUniqueId(), amount);
        int total = svc.getEssence(target.getUniqueId());
        source.getSender().sendMessage(Component.text("Set " + target.getName() + "'s essence to " + total + ".", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        if (!source.getSender().equals(target)) {
            target.sendMessage(Component.text("Your essence is now " + total + ".", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        }
    } else {
        if (amount <= 0) {
            source.getSender().sendMessage(Component.text("Amount must be positive."));
            return;
        }
        int total = svc.removeEssence(target.getUniqueId(), amount);
        source.getSender().sendMessage(Component.text("Removed " + amount + " essence from " + target.getName() + " (now " + total + ").", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        if (!source.getSender().equals(target)) {
            target.sendMessage(Component.text("-" + amount + " essence (now " + total + ").", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        }
    }
}

private void handleSoulTable(@NotNull CommandSourceStack source, @NotNull String[] args) {
    var sender = source.getSender();
    if (args.length < 3) {
        sender.sendMessage(Component.text("/gloved soul table give [player] [amount]", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/gloved soul table list [page]", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        sender.sendMessage(Component.text("/gloved soul table reload", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        return;
    }
    String action = args[2].toLowerCase(Locale.ROOT);
    switch (action) {
        case "give" -> {
            if (!sender.hasPermission("gloved.soul.table.give")) {
                sender.sendMessage(Component.text("You don't have permission to do that.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            Player target = sender instanceof Player p ? p : null;
            int index = 3;
            if (args.length > index) {
                Player named = Bukkit.getPlayerExact(args[index]);
                if (named == null) named = Bukkit.getPlayer(args[index]);
                if (named != null) {
                    target = named;
                    index++;
                }
            }
            if (target == null) {
                sender.sendMessage(Component.text("Console must specify a player: /gloved soul table give <player> [amount]", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            int amount = 1;
            if (args.length > index) {
                try {
                    amount = Integer.parseInt(args[index]);
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            amount = Math.max(1, Math.min(64, amount));
            var item = SoulTableItem.create(plugin);
            item.setAmount(amount);
            var leftover = target.getInventory().addItem(item);
            for (var drop : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            sender.sendMessage(Component.text("Gave " + amount + "x Soul Table to " + target.getName(),
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
        }
        case "list" -> {
            if (!sender.hasPermission("gloved.soul.table.give")) {
                sender.sendMessage(Component.text("You don't have permission to do that.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            var manager = plugin.getSoulTableManager();
            if (manager == null) {
                sender.sendMessage(Component.text("Soul Table system is not initialized.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            int page = 0;
            if (args.length > 3) {
                try { page = Math.max(0, Integer.parseInt(args[3]) - 1); } catch (NumberFormatException ignored) {}
            }
            var rows = manager.listAll();
            int perPage = 10;
            int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
            if (page >= pages) page = pages - 1;
            int from = page * perPage;
            int to = Math.min(rows.size(), from + perPage);
            sender.sendMessage(Component.text("=== Soul Tables (" + (page + 1) + "/" + pages + ", " + rows.size() + ") ===",
                    net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
            if (rows.isEmpty()) {
                sender.sendMessage(Component.text("No placed Soul Tables.", net.kyori.adventure.text.format.NamedTextColor.GRAY));
                return;
            }
            for (int i = from; i < to; i++) {
                var row = rows.get(i);
                String owner;
                Player online = Bukkit.getPlayer(row.ownerId());
                if (online != null) owner = online.getName();
                else {
                    try { owner = Bukkit.getOfflinePlayer(row.ownerId()).getName(); } catch (Throwable ignored) { owner = null; }
                    if (owner == null) owner = row.ownerId().toString().substring(0, 8);
                }
                sender.sendMessage(Component.text("#" + (i + 1) + " " + row.world() + " " + row.x() + "," + row.y() + "," + row.z()
                        + " owner=" + owner, net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
        }
        case "reload" -> {
            if (!sender.hasPermission("gloved.reload") && !sender.hasPermission("gloved.soul.table.give")) {
                sender.sendMessage(Component.text("You don't have permission to do that.", net.kyori.adventure.text.format.NamedTextColor.RED));
                return;
            }
            if (plugin.getSummonConfig() != null) plugin.getSummonConfig().reload();
            var recipes = plugin.getSoulTableRecipeManager();
            if (recipes != null) recipes.registerRecipes();
            var pack = plugin.getSoulTablePackService();
            if (pack != null) {
                var result = pack.generateAndDeploy();
                sender.sendMessage(Component.text("[SoulTable] " + result.message(),
                        result.ok() ? net.kyori.adventure.text.format.NamedTextColor.GREEN
                                : net.kyori.adventure.text.format.NamedTextColor.RED));
            }
            sender.sendMessage(Component.text("Soul Table config reloaded.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
        }
        default -> sender.sendMessage(Component.text("Unknown subcommand. Use give, list or reload.",
                net.kyori.adventure.text.format.NamedTextColor.RED));
    }
}

private void handleSoulHelp(CommandSourceStack source) {
    source.getSender().sendMessage(Component.text("=== Gloved Soul Commands ===", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
    source.getSender().sendMessage(Component.text("/gloved soul menu|cast|list|info|codex|loadout|select <slot>|equip <slot> <id>|unequip <slot>", net.kyori.adventure.text.format.NamedTextColor.GRAY));
    source.getSender().sendMessage(Component.text("/gloved soul inspect <id> | compare <idA> <idB> | lock <id> | favorite <id> | salvage <id>", net.kyori.adventure.text.format.NamedTextColor.GRAY));
    source.getSender().sendMessage(Component.text("/gloved soul give <player> <ENTITY_TYPE> [rarity] | simulate <ENTITY_TYPE> [rarity] [count]", net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
    source.getSender().sendMessage(Component.text("/gloved soul trap <tier> [amount] [player] - give a soul trap item", net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
    source.getSender().sendMessage(Component.text("/gloved soul essence <give|set|remove> <player> <amount>", net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
    source.getSender().sendMessage(Component.text("/gloved soul table give [player] [amount] | list [page] | reload", net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
    source.getSender().sendMessage(Component.text("Soul Focus (hotbar slot 9): Right-click = Vault, Left-click = Cast selected soul, Sneak + Left-click = Recall souls", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
    source.getSender().sendMessage(Component.text("Permission: gloved.soul, gloved.soul.table.give, gloved.soul.table.break", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
}

public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
    String q = args[args.length - 1] == null ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    java.util.function.Predicate<String> match = s -> q.isEmpty() || s.toLowerCase(Locale.ROOT).startsWith(q);
    if (args.length == 2) {
        java.util.List<String> subs = new ArrayList<>();
        for (String s : List.of("list", "info", "codex", "help", "menu", "cast", "give", "simulate", "select", "equip", "unequip", "loadout", "favorite", "salvage", "inspect", "compare", "trap", "table", "essence")) {
            if (match.test(s)) subs.add(s);
        }
        return subs;
    }
    String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
    if (args.length == 3) {
        if (sub.equals("give") || sub.equals("info") || sub.equals("codex")) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
            if (sub.equals("codex")) for (String n : List.of("10","20","50")) if (match.test(n)) out.add(n);
            return out;
        }
        if (sub.equals("trap") || sub.equals("traps")) {
            java.util.List<String> out = new ArrayList<>();
            for (String t : List.of("snare", "trap", "cage", "chalice", "relic", "prism")) if (match.test(t)) out.add(t);
            return out;
        }
        if (sub.equals("table")) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("give", "list", "reload")) if (match.test(s)) out.add(s);
            return out;
        }
        if (sub.equals("essence")) {
            List<String> out = new ArrayList<>();
            if (match.test("give")) out.add("give");
            if (match.test("set")) out.add("set");
            if (match.test("remove")) out.add("remove");
            return out;
        }
        if (sub.equals("list")) {
            List<String> out = new ArrayList<>();
            for (String n : List.of("1","2","3","4","5")) if (match.test(n)) out.add(n);
            out.addAll(suggestEntityTypes(q));
            for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
            return out;
        }
        if (sub.equals("menu") || sub.equals("vault") || sub.equals("gui")) {
            List<String> out = new ArrayList<>();
            for (String n : List.of("1","2","3")) if (match.test(n)) out.add(n);
            out.addAll(suggestEntityTypes(q));
            return out;
        }
        if (sub.equals("simulate") || sub.equals("sim")) return suggestEntityTypes(q);
        if (sub.equals("select") || sub.equals("unequip") || sub.equals("equip")) return suggestSlots(q);
        if (List.of("favorite","fav","salvage","inspect","compare").contains(sub)) return suggestOwnedSoulPrefixes(source, q);
    }
    if (args.length == 4 && (sub.equals("list") || sub.equals("menu") || sub.equals("vault") || sub.equals("gui"))) {
        List<String> out = new ArrayList<>(suggestRarities(q));
        if (match.test("-") || q.equals("-")) out.add("-");
        if (sub.equals("list")) for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
        return out;
    }
    if (args.length == 5 && (sub.equals("list") || sub.equals("menu") || sub.equals("vault") || sub.equals("gui"))) {
        List<String> out = new ArrayList<>();
        for (String s : List.of("default","quality","rarity","type","recent","-")) if (match.test(s)) out.add(s);
        return out;
    }
    if (args.length == 4) {
        if (sub.equals("essence")) {
            String action = args[2] == null ? "" : args[2].toLowerCase(Locale.ROOT);
            if (action.equals("give") || action.equals("set") || action.equals("remove")) {
                List<String> out = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
                return out;
            }
        }
        if (sub.equals("table")) {
            String action = args[2] == null ? "" : args[2].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (action.equals("give")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
            } else if (action.equals("list")) {
                for (String n : List.of("1", "2", "3", "4", "5")) if (match.test(n)) out.add(n);
            }
            return out;
        }
        if (sub.equals("give")) return suggestEntityTypes(q);
        if (sub.equals("equip")) return suggestOwnedSoulPrefixes(source, q);
        if (sub.equals("compare")) return suggestOwnedSoulPrefixes(source, q);
        if (sub.equals("simulate") || sub.equals("sim")) {
            List<String> out = new ArrayList<>(suggestRarities(q));
            for (String n : List.of("1","3","5","10")) if (match.test(n)) out.add(n);
            return out;
        }
        if (sub.equals("trap") || sub.equals("traps")) {
            List<String> out = new ArrayList<>();
            for (String n : List.of("1","4","16","32","64")) if (match.test(n)) out.add(n);
            for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
            return out;
        }
        if (sub.equals("list")) {
            List<String> out = new ArrayList<>();
            for (String n : List.of("1","2","3","4","5")) if (match.test(n)) out.add(n);
            for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
            return out;
        }
    }
    if (args.length == 5) {
        if (sub.equals("essence") && args[2] != null) {
            String action = args[2].toLowerCase(Locale.ROOT);
            if (action.equals("give") || action.equals("set") || action.equals("remove")) {
                List<String> out = new ArrayList<>();
                List<String> nums = action.equals("set")
                        ? List.of("0", "1", "5", "10", "25", "50", "100", "500")
                        : List.of("1", "5", "10", "25", "50", "100", "500");
                for (String n : nums) if (match.test(n)) out.add(n);
                return out;
            }
        }
        if (sub.equals("table") && args[2] != null && args[2].equalsIgnoreCase("give")) {
            List<String> out = new ArrayList<>();
            for (String n : List.of("1", "2", "4", "8", "16")) if (match.test(n)) out.add(n);
            return out;
        }
        if (sub.equals("give")) return suggestRarities(q);
        if (sub.equals("simulate") || sub.equals("sim")) {
            List<String> out = new ArrayList<>();
            for (String n : List.of("1","3","5","10","20")) if (match.test(n)) out.add(n);
            return out;
        }
        if (sub.equals("trap") || sub.equals("traps")) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (match.test(p.getName())) out.add(p.getName());
            return out;
        }
    }
    return List.of();
}

private java.util.List<String> suggestSlots(String qLower) {
    java.util.List<String> out = new ArrayList<>();
    for (String s : List.of("0","1","2","3","4")) if (qLower.isEmpty() || s.startsWith(qLower)) out.add(s);
    return out;
}

private java.util.List<String> suggestOwnedSoulPrefixes(@NotNull CommandSourceStack source, String qLower) {
    java.util.List<String> out = new ArrayList<>();
    if (!(source.getSender() instanceof Player player)) return out;
    SoulService svc = plugin.getSoulService();
    if (svc == null) return out;
    try {
        for (SoulInstance s : svc.listSouls(player.getUniqueId(), 0, 20)) {
            String shortId = SoulService.shortId(s);
            if (qLower.isEmpty() || shortId.toLowerCase(Locale.ROOT).startsWith(qLower) || s.getSoulId().toString().toLowerCase(Locale.ROOT).startsWith(qLower)) {
                out.add(shortId);
            }
        }
    } catch (Throwable ignored) {}
    return out;
}

private java.util.List<String> suggestEntityTypes(String qLower) {
    java.util.List<String> out = new ArrayList<>();
    SummonConfig sc = plugin.getSummonConfig();
    for (EntityType et : EntityType.values()) {
        try {
            if (sc != null && sc.getProfile(et) == null) continue;
        } catch (Throwable ignored) { continue; }
        String n = et.name();
        if (qLower.isEmpty() || n.toLowerCase(Locale.ROOT).startsWith(qLower)) out.add(n);
        if (out.size() >= 60) break;
    }
    // if none matched due to config, fallback to some common living types
    if (out.isEmpty()) {
        for (EntityType et : EntityType.values()) {
            if (!et.isAlive()) continue;
            String n = et.name();
            if (qLower.isEmpty() || n.toLowerCase(Locale.ROOT).startsWith(qLower)) out.add(n);
            if (out.size() >= 40) break;
        }
    }
    return out;
}

private java.util.List<String> suggestRarities(String qLower) {
    java.util.List<String> out = new ArrayList<>();
    for (SoulRarity r : SoulRarity.values()) {
        String n = r.name();
        if (qLower.isEmpty() || n.toLowerCase(Locale.ROOT).startsWith(qLower)) out.add(n);
    }
    return out;
}

    static String fmtPct(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", v * 100.0);
    }

}
