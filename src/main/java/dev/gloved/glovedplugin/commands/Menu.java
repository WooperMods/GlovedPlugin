package dev.gloved.glovedplugin.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import dev.gloved.glovedplugin.MenuConfig;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.GlovedPlugin;

import org.jetbrains.annotations.NotNull;

/**
 * The Menu class implements BasicCommand for Paper.
 * It handles the "menu" command and opens the custom menu inventory.
 */
public class Menu implements BasicCommand {

    public static Component menuComponent = GlovedPlugin.makeComponent("GlovedMenu");

    public static void refreshTitle(MenuConfig config) {
        menuComponent = config.titleComponent();
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("Only players can open menu.");
            return;
        }
        GlovedPlugin plugin = JavaPlugin.getPlugin(GlovedPlugin.class);
        new MenuInv(plugin).openMenu(player);
    }
}
