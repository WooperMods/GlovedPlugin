package dev.gloved.glovedplugin.commands;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.Menus.MenuInv;
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
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Only players can open the menu.");
                return;
            }
            new MenuInv(plugin).openMenu(player);
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

        source.getSender().sendMessage(Component.text("Usage: /gloved <menu|reload|reset>"));
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            return List.of("menu", "reload", "reset");
        }
        return List.of();
    }
}
