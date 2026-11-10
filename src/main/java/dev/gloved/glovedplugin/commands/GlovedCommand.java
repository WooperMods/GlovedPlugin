package dev.gloved.glovedplugin.commands;

import dev.gloved.glovedplugin.DebugLog;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.editor.EditSession;
import dev.gloved.glovedplugin.editor.MenuEditorInv;
import dev.gloved.glovedplugin.handlers.MenuHandler;
import dev.gloved.glovedplugin.summon.EffectiveSummonStats;
import dev.gloved.glovedplugin.summon.SummonConfig;
import dev.gloved.glovedplugin.summon.SummonProfile;
import dev.gloved.glovedplugin.summon.soul.SoulGenerator;
import dev.gloved.glovedplugin.summon.soul.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.SoulService;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class GlovedCommand implements BasicCommand {

    private final GlovedPlugin plugin;

    public GlovedCommand(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("pvp")) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Only players can toggle PvP.");
                return;
            }
            boolean disabled = plugin.togglePvp(player);
            String msg = "Gloved Combat PvP is now " + (disabled ? "OFF" : "ON") + " for you.";
            player.sendMessage(Component.text(msg));
            plugin.logInfo("Player " + player.getName() + " toggled Gloved PvP to " + (disabled ? "OFF" : "ON"));
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Only players can open the menu.");
                return;
            }
            // Opening a window discards the carried stack: stash it first.
            // Full inventory: refuse instead, menu stays closed, items untouched.
            if (!MenuHandler.stashCursor(player)) {
                player.sendMessage("Free an inventory slot first!");
                return;
            }
            new MenuInv(plugin).openMenu(player);
            return;
        }

        if (args[0].equalsIgnoreCase("edit")) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Only players can edit the menu.");
                return;
            }
            if (!source.getSender().hasPermission("gloved.edit")) {
                source.getSender().sendMessage(Component.text("You don't have permission to do that."));
                return;
            }
            MenuEditorInv.openEditorFor(plugin, player);
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!source.getSender().hasPermission("gloved.reload")) {
                source.getSender().sendMessage(Component.text("You don't have permission to do that."));
                return;
            }
            plugin.reloadMenuConfig();
            for (Player player : Bukkit.getOnlinePlayers()) {
                MenuHandler.giveMenuOpener(player);
                dev.gloved.glovedplugin.summon.soul.SoulItem.give(player);
            }
            source.getSender().sendMessage(Component.text("GlovedPlugin config reloaded."));
            GlovedPlugin.logInfo("Config reloaded!");
            return;
        }

        if (args[0].equalsIgnoreCase("debug")) {
            if (!source.getSender().hasPermission("gloved.debug")) {
                source.getSender().sendMessage(Component.text("You don't have permission to do that."));
                return;
            }
            boolean on = args.length < 2 || !args[1].equalsIgnoreCase("off");
            if (args.length > 1) {
                if (args[1].equalsIgnoreCase("on")) on = true;
                else if (args[1].equalsIgnoreCase("off")) on = false;
                else {
                    source.getSender().sendMessage(Component.text("Usage: /gloved debug [on|off]"));
                    return;
                }
            } else {
                on = !DebugLog.isEnabled();
            }
            if (!DebugLog.setEnabled(plugin, on)) {
                source.getSender().sendMessage(Component.text("Could not write debug state, check console."));
                return;
            }
            source.getSender().sendMessage(Component.text("GlovedPlugin debug logging " + (on ? "ON (plugins/GlovedPlugin/debug.log)" : "OFF")));
            return;
        }

        if (args[0].equalsIgnoreCase("summon")) {
            if (!source.getSender().hasPermission("gloved.summon")) {
                source.getSender().sendMessage(Component.text("You don't have permission to do that."));
                return;
            }
            handleSummonStatus(source, args);
            return;
        }

        if (args[0].equalsIgnoreCase("soul") || args[0].equalsIgnoreCase("souls")) {
            handleSoulCommand(source, args);
            return;
        }

        if (args[0].equalsIgnoreCase("enchant")) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Only players can enchant their held item.");
                return;
            }
            if (args.length < 2) {
                source.getSender().sendMessage(Component.text("Usage: /gloved enchant <enchant> [level|remove]"));
                return;
            }
            String enchant = args[1];
            String level = args.length >= 3 ? args[2] : null;
            var result = dev.gloved.glovedplugin.enchant.EnchantBridge.apply(player, enchant, level);
            source.getSender().sendMessage(Component.text(result.message));
            return;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            if (!source.getSender().hasPermission("gloved.reset")) {
                source.getSender().sendMessage(Component.text("You don't have permission to do that."));
                return;
            }
            plugin.saveResource("config.yml", true);
            plugin.reloadMenuConfig();
            for (Player player : Bukkit.getOnlinePlayers()) {
                MenuHandler.giveMenuOpener(player);
            }
            source.getSender().sendMessage(Component.text("GlovedPlugin config reset to defaults."));
            GlovedPlugin.logInfo("Config reset to default!");
            return;
        }

        if (args[0].equalsIgnoreCase("emreset")) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Only players can reset an EliteMobs custom item.");
                return;
            }
            if (!source.getSender().hasPermission("gloved.emreset")) {
                source.getSender().sendMessage(Component.text("You don't have permission to do that."));
                return;
            }
            var result = dev.gloved.glovedplugin.enchant.EliteMobsItemReset.resetHeldItem(player);
            source.getSender().sendMessage(Component.text(result.message));
            return;
        }

        if (args[0].equalsIgnoreCase("rankglyphs") || args[0].equalsIgnoreCase("ranks")) {
            if (!source.getSender().hasPermission("gloved.rankglyphs")) {
                source.getSender().sendMessage(Component.text("You don't have permission to do that."));
                return;
            }
            try {
                var svc = new dev.gloved.glovedplugin.rankglyph.RankGlyphService(plugin);
                String sub = args.length > 1 ? args[1].toLowerCase() : "all";
                switch (sub) {
                    case "generate", "gen", "pack" -> svc.runGenerate(source.getSender());
                    case "apply", "prefixes", "lp" -> svc.runApply(source.getSender());
                    case "status", "info" -> svc.runStatus(source.getSender());
                    case "all", "sync", "run" -> svc.runAll(source.getSender());
                    default -> source.getSender().sendMessage(Component.text("Usage: /gloved rankglyphs <all|generate|apply|status>"));
                }
            } catch (NoClassDefFoundError | NoSuchMethodError e) {
                source.getSender().sendMessage(Component.text("RankGlyphs unavailable: missing dependency (" + e.getMessage() + "). Ensure LuckPerms is installed and GlovedPlugin declares it (restart server after updating the jar).", net.kyori.adventure.text.format.NamedTextColor.RED));
            } catch (Exception e) {
                source.getSender().sendMessage(Component.text("RankGlyphs error: " + e.getMessage(), net.kyori.adventure.text.format.NamedTextColor.RED));
                plugin.getLogger().warning("rankglyphs command failed: " + e);
            }
            return;
        }

        source.getSender().sendMessage(Component.text("Usage: /gloved <menu|reload|reset|edit|debug|summon|soul|pvp|enchant|emreset|rankglyphs>"));
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            return List.of("menu", "reload", "reset", "edit", "pvp", "debug", "summon", "soul", "enchant", "emreset", "rankglyphs");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return List.of("on", "off");
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("summon")) {
            SummonConfig sc = plugin.getSummonConfig();
            List<String> out = new ArrayList<>();
            String q = args[args.length - 1] == null ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            java.util.function.Predicate<String> match = s -> q.isEmpty() || s.toLowerCase(Locale.ROOT).startsWith(q);
            if (args.length == 2 || args.length == 3) {
                // Suggest allowed dynamic soul families (entity types) and online players.
                if (sc != null) {
                    for (org.bukkit.entity.EntityType et : org.bukkit.entity.EntityType.values()) {
                        try {
                            if (sc.getProfile(et) == null) continue;
                        } catch (Throwable ignored) { continue; }
                        if (match.test(et.name())) out.add(et.name());
                    }
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (match.test(p.getName())) out.add(p.getName());
                }
            }
            // de-dup while preserving order
            java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>(out);
            return new ArrayList<>(uniq);
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("soul") || args[0].equalsIgnoreCase("souls"))) {
            return suggestSoul(source, args);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("rankglyphs") || args[0].equalsIgnoreCase("ranks"))) {
            return List.of("all", "generate", "apply", "status");
        }
        if (args[0].equalsIgnoreCase("enchant")) {
            if (args.length == 2) {
                return dev.gloved.glovedplugin.enchant.EnchantBridge.suggest(args[1]);
            }
            if (args.length == 3) {
                String q = args[2] == null ? "" : args[2].toLowerCase();
                java.util.List<String> levels = new java.util.ArrayList<>(List.of("1", "2", "3", "4", "5", "10", "remove"));
                levels.removeIf(s -> !q.isEmpty() && !s.startsWith(q));
                return levels;
            }
        }
        return List.of();
    }

    private void handleSummonStatus(@NotNull CommandSourceStack source, @NotNull String[] args) {
        SummonConfig sc = plugin.getSummonConfig();
        if (sc == null) {
            source.getSender().sendMessage(Component.text("Summon module is not initialized."));
            return;
        }
        // Resolve args: /gloved summon [family|player] [player|family]
        // Dynamic soul system: "profile" is an entity-type family key (e.g. ZOMBIE) if allowed.
        Player targetPlayer = null;
        SummonProfile profile = null;
        String profileArg = null;
        if (args.length >= 2) {
            Player asPlayer = Bukkit.getPlayerExact(args[1]);
            if (asPlayer == null) asPlayer = Bukkit.getPlayer(args[1]);
            SummonProfile asProfile = sc.getProfile(args[1]);
            if (asProfile != null) {
                profile = asProfile;
                profileArg = profile.name;
                if (args.length >= 3) {
                    targetPlayer = Bukkit.getPlayerExact(args[2]);
                    if (targetPlayer == null) targetPlayer = Bukkit.getPlayer(args[2]);
                }
            } else if (asPlayer != null) {
                targetPlayer = asPlayer;
                if (args.length >= 3) {
                    SummonProfile p2 = sc.getProfile(args[2]);
                    if (p2 != null) { profile = p2; profileArg = p2.name; }
                }
            } else {
                // try case-insensitive entity type resolve
                try {
                    org.bukkit.entity.EntityType et = org.bukkit.entity.EntityType.valueOf(args[1].trim().toUpperCase(Locale.ROOT));
                    SummonProfile p2 = sc.getProfile(et);
                    if (p2 != null) { profile = p2; profileArg = p2.name; }
                    if (profile != null && args.length >= 3) {
                        targetPlayer = Bukkit.getPlayerExact(args[2]);
                        if (targetPlayer == null) targetPlayer = Bukkit.getPlayer(args[2]);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (targetPlayer == null && source.getSender() instanceof Player p) targetPlayer = p;

        var progression = plugin.getSummonProgression();
        int level = 0;
        if (targetPlayer != null && progression != null) {
            try { level = progression.getSummoningLevel(targetPlayer.getUniqueId()); } catch (Throwable ignored) {}
        }
        boolean aurasksPresent = Bukkit.getPluginManager().getPlugin("AuraSkills") != null;
        String bridge = progression == null ? "none" : progression.getClass().getSimpleName();
        dev.gloved.glovedplugin.summon.progression.OwnerCombatStats ocs = (targetPlayer != null && progression != null)
                ? progression.resolveOwnerCombatStats(targetPlayer.getUniqueId())
                : dev.gloved.glovedplugin.summon.progression.OwnerCombatStats.unavailable();

        // Crit: AuraSkills-authoritative by default (owner crit_chance/crit_damage stats), capped.
        double critChance = 0.0;
        double critMult = sc.getCritMinDamageMultiplier();
        String critDetail;
        if (sc.isCritEnabled()) {
            if (sc.isCritSourceAuraSkills() && ocs.available()) {
                critChance = Math.min(sc.getCritMaxChance(), Math.max(0.0, ocs.critChancePercentPoints() / 100.0));
                critMult = Math.max(sc.getCritMinDamageMultiplier(), Math.min(sc.getCritMaxDamageMultiplier(), 1.0 + ocs.critDamageTrait() / 100.0));
                critDetail = "AuraSkills crit_chance=" + String.format(Locale.ROOT, "%.2f", ocs.critChancePercentPoints())
                        + " crit_damage_trait=" + String.format(Locale.ROOT, "%.2f", ocs.critDamageTrait());
            } else if (sc.isCritSourceAuraSkills()) {
                critDetail = "AuraSkills stats unavailable (no double-dip)";
            } else {
                critChance = Math.min(sc.getCritMaxChance(), Math.max(0.0, sc.getCritLegacyBaseChance() + sc.getCritLegacyPerLevel() * Math.max(0, level)));
                critMult = sc.getCritLegacyDamageMultiplier();
                critDetail = "legacy (summoning level formula)";
            }
        } else {
            critDetail = "disabled";
        }

        int debuffLvl = resolveAbilityDisplayLevel(targetPlayer, progression, sc, "gloved/summoning_debuff", sc.getAbilityDebuffUnlockLevel(), level);
        int cleaveLvl = resolveAbilityDisplayLevel(targetPlayer, progression, sc, "gloved/summoning_cleave", sc.getAbilityCleaveUnlockLevel(), level);
        int lifestealLvl = resolveAbilityDisplayLevel(targetPlayer, progression, sc, "gloved/summoning_lifesteal", sc.getAbilityLifestealUnlockLevel(), level);

        source.getSender().sendMessage(Component.text("=== Gloved Summon Status ===", net.kyori.adventure.text.format.NamedTextColor.GOLD));
        source.getSender().sendMessage(Component.text("Player: " + (targetPlayer != null ? targetPlayer.getName() : "(none)"), net.kyori.adventure.text.format.NamedTextColor.YELLOW));
        source.getSender().sendMessage(Component.text("Summoning level: " + level + " | Bridge: " + bridge + " | AuraSkills: " + (aurasksPresent ? "present" : "missing"), net.kyori.adventure.text.format.NamedTextColor.GRAY));
        source.getSender().sendMessage(Component.text("Crit (source=" + sc.getCritSource() + "): " + (sc.isCritEnabled() ? "on" : "off")
                + " chance=" + fmtPct(critChance) + " (cap " + fmtPct(sc.getCritMaxChance()) + ") mult=x" + String.format(Locale.ROOT, "%.3f", critMult)
                + " [" + critDetail + "]", net.kyori.adventure.text.format.NamedTextColor.AQUA));
        source.getSender().sendMessage(Component.text("Owner Strength->minion damage: " + (sc.isStrengthDamageEnabled() ? "on" : "off")
                + " attackDamageTrait=" + String.format(Locale.ROOT, "%.2f", ocs.attackDamageTrait())
                + " per=" + sc.getStrengthDamagePerAttackDamageTrait() + " maxBonus=" + sc.getStrengthDamageMaxBonus(), net.kyori.adventure.text.format.NamedTextColor.AQUA));

        source.getSender().sendMessage(Component.text("Abilities (Archery/Fighting-like pacing, multi-level): "
                + "Debuff unlock L" + sc.getAbilityDebuffUnlockLevel() + " abilLvl=" + debuffLvl + "=" + (debuffLvl > 0 && sc.isAbilityDebuffEnabled() ? "active" : "locked/off")
                + " chance=" + fmtPct(sc.getAbilityDebuffChance(Math.max(1, debuffLvl))) + " dur=" + sc.getAbilityDebuffDurationTicks(Math.max(1, debuffLvl)) + "t"
                + " | Cleave unlock L" + sc.getAbilityCleaveUnlockLevel() + " abilLvl=" + cleaveLvl + "=" + (cleaveLvl > 0 && sc.isAbilityCleaveEnabled() ? "active" : "locked/off")
                + " chance=" + fmtPct(sc.getAbilityCleaveChance(Math.max(1, cleaveLvl))) + " r=" + String.format(Locale.ROOT, "%.2f", sc.getAbilityCleaveRadius(Math.max(1, cleaveLvl)))
                + " ratio=" + fmtPct(sc.getAbilityCleaveDamageRatio(Math.max(1, cleaveLvl))) + (sc.isAbilityCleaveDamageRatioUsesBaseDamage() ? " base" : " final")
                + " | Lifesteal unlock L" + sc.getAbilityLifestealUnlockLevel() + " abilLvl=" + lifestealLvl + "=" + (lifestealLvl > 0 && sc.isAbilityLifestealEnabled() ? "active" : "locked/off")
                + " heal=" + fmtPct(sc.getAbilityLifestealHealPercentMaxHp(Math.max(1, lifestealLvl))) + " maxHP debuffed x" + sc.getAbilityLifestealDebuffedBonusMultiplier(),
                net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        source.getSender().sendMessage(Component.text("Rewards (AuraSkills, when generated): +1 crit_chance/level, +1 crit_damage/2 levels, +1 strength/2 levels. Run /skills reload after content generation/migration if UI is stale.",
                net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));

        if (profile != null) {
            EffectiveSummonStats eff = EffectiveSummonStats.resolve(targetPlayer, profile, sc, progression);
            source.getSender().sendMessage(Component.text("Soul family [" + profileArg + "]: batch=" + profile.minionsPerHit + " typeMaxActive=" + profile.maxActive
                    + " | effective batch=" + eff.minionsPerHit + " typeMaxActive=" + eff.maxActive + " (lvl " + eff.summoningLevel + ")",
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
        } else {
            source.getSender().sendMessage(Component.text("Soul family: (none/invalid) - pass /gloved summon <ENTITY_TYPE> [player]", net.kyori.adventure.text.format.NamedTextColor.RED));
        }

        var ability = plugin.getSummonAbilityService();
        if (ability != null) {
            var d = ability.diagnostics();
            source.getSender().sendMessage(Component.text("Diagnostics (since enable/reload): debuffProcs=" + d.debuffProcs + " cleaveProcs=" + d.cleaveProcs + " lifestealHits=" + d.lifestealHits,
                    net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
    }

    private void handleSoulCommand(@NotNull CommandSourceStack source, @NotNull String[] args) {
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
            case "quality" -> java.util.Comparator.comparingDouble((SoulInstance x) -> dev.gloved.glovedplugin.summon.soul.SoulQuality.percent(x)).reversed();
            case "rarity" -> java.util.Comparator.comparingInt((SoulInstance s) -> s.getRarity().ordinal()).reversed().thenComparing(java.util.Comparator.comparingDouble((SoulInstance x) -> dev.gloved.glovedplugin.summon.soul.SoulQuality.percent(x)).reversed());
            case "type", "family" -> java.util.Comparator.comparing(SoulInstance::getFamilyKey).thenComparing(java.util.Comparator.comparingDouble((SoulInstance x) -> dev.gloved.glovedplugin.summon.soul.SoulQuality.percent(x)).reversed());
            case "recent" -> java.util.Comparator.comparingLong(SoulInstance::getCreatedAt).reversed();
            default -> java.util.Comparator.comparing((SoulInstance s) -> s.isFavorite() ? 0 : 1)
                    .thenComparing(java.util.Comparator.comparingDouble((SoulInstance x) -> dev.gloved.glovedplugin.summon.soul.SoulQuality.percent(x)).reversed())
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
        new dev.gloved.glovedplugin.summon.soul.ui.SoulVaultInv(plugin, player.getUniqueId(), page, type, rarity, sort).open(player);
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
            source.getSender().sendMessage(Component.text("Soul config: enabled=" + soulCfg.isEnabled() + " globalCapBase=" + soulCfg.computeGlobalMaxActive(0) + " now=" + soulCfg.computeGlobalMaxActive(level) + " castCDBase=" + String.format(java.util.Locale.ROOT, "%.1fs", soulCfg.getCastBaseCooldownTicks()/20.0) + " manaBase=" + String.format(java.util.Locale.ROOT, "%.1f", soulCfg.getCastBaseManaCost()), net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
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
            source.getSender().sendMessage(Component.text("Selected: " + SoulGenerator.describe(selected) + " | castMana=" + String.format(java.util.Locale.ROOT, "%.1f", mana) + " castCD=" + String.format(java.util.Locale.ROOT, "%.1fs", cdTicks/20.0) + (rem > 0 ? " | cdLeft=" + String.format(java.util.Locale.ROOT, "%.1fs", rem/1000.0) : " | ready"), net.kyori.adventure.text.format.NamedTextColor.GREEN));
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
        try { dev.gloved.glovedplugin.summon.soul.SoulItem.refresh(player, plugin); } catch (Throwable ignored) {}
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
        try { dev.gloved.glovedplugin.summon.soul.SoulItem.refresh(player, plugin); } catch (Throwable ignored) {}
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
        try { dev.gloved.glovedplugin.summon.soul.SoulItem.refresh(player, plugin); } catch (Throwable ignored) {}
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

    private void handleSoulHelp(CommandSourceStack source) {
        source.getSender().sendMessage(Component.text("=== Gloved Soul Commands ===", net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE));
        source.getSender().sendMessage(Component.text("/gloved soul menu|cast|list|info|codex|loadout|select <slot>|equip <slot> <id>|unequip <slot>", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        source.getSender().sendMessage(Component.text("/gloved soul inspect <id> | compare <idA> <idB> | lock <id> | favorite <id> | salvage <id>", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        source.getSender().sendMessage(Component.text("/gloved soul give <player> <ENTITY_TYPE> [rarity] | simulate <ENTITY_TYPE> [rarity] [count]", net.kyori.adventure.text.format.NamedTextColor.DARK_AQUA));
        source.getSender().sendMessage(Component.text("Soul Focus (hotbar slot 9): Right-click = Vault, Left-click = Cast selected soul", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        source.getSender().sendMessage(Component.text("Permission: gloved.soul", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
    }

    private java.util.Collection<String> suggestSoul(@NotNull CommandSourceStack source, @NotNull String[] args) {
        String q = args[args.length - 1] == null ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        java.util.function.Predicate<String> match = s -> q.isEmpty() || s.toLowerCase(Locale.ROOT).startsWith(q);
        if (args.length == 2) {
            java.util.List<String> subs = new ArrayList<>();
            for (String s : List.of("list", "info", "codex", "help", "menu", "cast", "give", "simulate", "select", "equip", "unequip", "loadout", "favorite", "salvage", "inspect", "compare")) {
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
            if (sub.equals("give")) return suggestEntityTypes(q);
            if (sub.equals("equip")) return suggestOwnedSoulPrefixes(source, q);
            if (sub.equals("compare")) return suggestOwnedSoulPrefixes(source, q);
            if (sub.equals("simulate") || sub.equals("sim")) {
                List<String> out = new ArrayList<>(suggestRarities(q));
                for (String n : List.of("1","3","5","10")) if (match.test(n)) out.add(n);
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
            if (sub.equals("give")) return suggestRarities(q);
            if (sub.equals("simulate") || sub.equals("sim")) {
                List<String> out = new ArrayList<>();
                for (String n : List.of("1","3","5","10","20")) if (match.test(n)) out.add(n);
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

    private static int resolveAbilityDisplayLevel(Player targetPlayer, dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge progression,
                                                  SummonConfig sc, String abilityId, int fallbackUnlock, int skillLevel) {
        if (targetPlayer != null && progression != null && sc != null && sc.isAbilitiesUseAuraAbilityLevels()) {
            try { int lvl = progression.getAbilityLevel(targetPlayer.getUniqueId(), abilityId); if (lvl > 0) return lvl; } catch (Throwable ignored) {}
        }
        return skillLevel >= fallbackUnlock ? 1 : 0;
    }

    private static String unlocked(int level, int unlock, boolean enabled) {
        if (!enabled) return "off";
        return level >= unlock ? "unlocked" : "locked";
    }

    private static String fmtPct(double v) {
        return String.format(Locale.ROOT, "%.2f%%", v * 100.0);
    }
}
