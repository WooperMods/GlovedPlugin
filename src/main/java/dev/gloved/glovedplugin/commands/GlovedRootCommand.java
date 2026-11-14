package dev.gloved.glovedplugin.commands;

import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.menu.editor.EditSession;
import dev.gloved.glovedplugin.menu.editor.MenuEditorInv;
import dev.gloved.glovedplugin.menu.listener.MenuHandler;
import dev.gloved.glovedplugin.summon.runtime.EffectiveSummonStats;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.soul.domain.SoulGenerator;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
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
import dev.gloved.glovedplugin.commands.gloved.GlovedSoulSubcommandHandler;
import dev.gloved.glovedplugin.menu.command.MenuOpenService;
import dev.gloved.glovedplugin.summon.soul.item.SoulItem;
import dev.gloved.glovedplugin.enchant.EliteMobsItemReset;
import dev.gloved.glovedplugin.enchant.EnchantBridge;
import dev.gloved.glovedplugin.rankglyph.RankGlyphService;
import dev.gloved.glovedplugin.summon.progression.OwnerCombatStats;
import dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge;

public class GlovedRootCommand implements BasicCommand {

    private final GlovedPlugin plugin;
    private final GlovedSoulSubcommandHandler soulSubcommandHandler;

    public GlovedRootCommand(GlovedPlugin plugin) {
        this.plugin = plugin;
        this.soulSubcommandHandler = new GlovedSoulSubcommandHandler(plugin);
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("pvp")) {
            handlePvp(source);
            return;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            handleMenu(source);
            return;
        }

        if (args[0].equalsIgnoreCase("edit")) {
            handleEdit(source);
            return;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            handleReload(source);
            return;
        }

        if (args[0].equalsIgnoreCase("debug")) {
            handleDebug(source, args);
            return;
        }

        if (args[0].equalsIgnoreCase("summon")) {
            handleSummon(source, args);
            return;
        }

        if (args[0].equalsIgnoreCase("soul") || args[0].equalsIgnoreCase("souls")) {
            soulSubcommandHandler.handle(source, args);
            return;
        }

        if (args[0].equalsIgnoreCase("enchant")) {
            handleEnchant(source, args);
            return;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            handleReset(source);
            return;
        }

        if (args[0].equalsIgnoreCase("emreset")) {
            handleEmReset(source);
            return;
        }

        if (args[0].equalsIgnoreCase("rankglyphs") || args[0].equalsIgnoreCase("ranks")) {
            handleRankGlyphs(source, args);
            return;
        }

        source.getSender().sendMessage(Component.text("Usage: /gloved <menu|reload|reset|edit|debug|summon|soul|pvp|enchant|emreset|rankglyphs>"));
    }

    private void handlePvp(@NotNull CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players can toggle PvP.");
            return;
        }
        boolean disabled = plugin.togglePvp(player);
        String msg = "Gloved Combat PvP is now " + (disabled ? "OFF" : "ON") + " for you.";
        player.sendMessage(Component.text(msg));
        plugin.logInfo("Player " + player.getName() + " toggled Gloved PvP to " + (disabled ? "OFF" : "ON"));
    }

    private void handleMenu(@NotNull CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players can open the menu.");
            return;
        }
        MenuOpenService.tryOpenMainMenu(plugin, player);
    }

    private void handleEdit(@NotNull CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players can edit the menu.");
            return;
        }
        if (!source.getSender().hasPermission("gloved.edit")) {
            source.getSender().sendMessage(Component.text("You don't have permission to do that."));
            return;
        }
        MenuEditorInv.openEditorFor(plugin, player);
    }

    private void handleReload(@NotNull CommandSourceStack source) {
        if (!source.getSender().hasPermission("gloved.reload")) {
            source.getSender().sendMessage(Component.text("You don't have permission to do that."));
            return;
        }
        plugin.reloadMenuConfig();
        for (Player player : Bukkit.getOnlinePlayers()) {
            MenuHandler.giveMenuOpener(player);
            SoulItem.give(player);
        }
        source.getSender().sendMessage(Component.text("GlovedPlugin config reloaded."));
        GlovedPlugin.logInfo("Config reloaded!");
    }

    private void handleDebug(@NotNull CommandSourceStack source, @NotNull String[] args) {
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
    }

    private void handleSummon(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!source.getSender().hasPermission("gloved.summon")) {
            source.getSender().sendMessage(Component.text("You don't have permission to do that."));
            return;
        }
        handleSummonStatus(source, args);
    }

    private void handleEnchant(@NotNull CommandSourceStack source, @NotNull String[] args) {
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
        var result = EnchantBridge.apply(player, enchant, level);
        source.getSender().sendMessage(Component.text(result.message));
    }

    private void handleReset(@NotNull CommandSourceStack source) {
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
    }

    private void handleEmReset(@NotNull CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players can reset an EliteMobs custom item.");
            return;
        }
        if (!source.getSender().hasPermission("gloved.emreset")) {
            source.getSender().sendMessage(Component.text("You don't have permission to do that."));
            return;
        }
        var result = EliteMobsItemReset.resetHeldItem(player);
        source.getSender().sendMessage(Component.text(result.message));
    }

    private void handleRankGlyphs(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!source.getSender().hasPermission("gloved.rankglyphs")) {
            source.getSender().sendMessage(Component.text("You don't have permission to do that."));
            return;
        }
        try {
            var svc = new RankGlyphService(plugin);
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
            return soulSubcommandHandler.suggest(source, args);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("rankglyphs") || args[0].equalsIgnoreCase("ranks"))) {
            return List.of("all", "generate", "apply", "status");
        }
        if (args[0].equalsIgnoreCase("enchant")) {
            if (args.length == 2) {
                return EnchantBridge.suggest(args[1]);
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
        OwnerCombatStats ocs = (targetPlayer != null && progression != null)
                ? progression.resolveOwnerCombatStats(targetPlayer.getUniqueId())
                : OwnerCombatStats.unavailable();

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
        source.getSender().sendMessage(Component.text("Rewards (AuraSkills, when generated): +1 crit_chance every level, odd levels +1 crit_damage, even levels +1 strength. Run /skills reload after content generation/migration if UI is stale.",
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

    private static int resolveAbilityDisplayLevel(Player targetPlayer, SummonProgressionBridge progression,
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
