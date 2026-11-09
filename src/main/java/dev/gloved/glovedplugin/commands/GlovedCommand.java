package dev.gloved.glovedplugin.commands;

import dev.gloved.glovedplugin.DebugLog;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.editor.EditSession;
import dev.gloved.glovedplugin.editor.MenuEditorInv;
import dev.gloved.glovedplugin.handlers.MenuHandler;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

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

        source.getSender().sendMessage(Component.text("Usage: /gloved <menu|reload|reset|edit|debug|pvp|enchant|emreset|rankglyphs>"));
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            return List.of("menu", "reload", "reset", "edit", "pvp", "debug", "enchant", "emreset", "rankglyphs");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return List.of("on", "off");
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
}
