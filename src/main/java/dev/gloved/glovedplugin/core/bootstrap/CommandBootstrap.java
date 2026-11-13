package dev.gloved.glovedplugin.core.bootstrap;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.commands.GlovedRootCommand;
import dev.gloved.glovedplugin.menu.command.MenuCommand;
import dev.gloved.glovedplugin.lootr.command.LootrCommand;

/**
 * Registers plugin commands. Preserves command names, descriptions, and registration order.
 */
public final class CommandBootstrap {

    private CommandBootstrap() {
    }

    public static void register(GlovedPlugin plugin) {
        // Register menu command using Paper's registerCommand API
        MenuCommand menuCommand = new MenuCommand();
        plugin.registerCommand("menu", "Custom menu for Commands", menuCommand);
        plugin.registerCommand("gloved", "GlovedPlugin admin command", new GlovedRootCommand(plugin));
        plugin.registerCommand("lootr", "Lootr per-player loot commands", new LootrCommand(plugin));
        GlovedPlugin.logInfo("Registered Menu Command!");
    }
}
