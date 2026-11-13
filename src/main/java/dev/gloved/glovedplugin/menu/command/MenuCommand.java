package dev.gloved.glovedplugin.menu.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import dev.gloved.glovedplugin.menu.config.MenuConfig;
import dev.gloved.glovedplugin.core.GlovedPlugin;

import org.jetbrains.annotations.NotNull;

/**
 * The Menu class implements BasicCommand for Paper.
 * It handles the "menu" command and opens the custom menu inventory.
 */
public class MenuCommand implements BasicCommand {

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
        MenuOpenService.tryOpenMainMenu(plugin, player);
    }
}
