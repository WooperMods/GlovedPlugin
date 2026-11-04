package dev.gloved.glovedplugin.lootr.command;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public class LootrCommand implements CommandExecutor, TabCompleter {
    private final GlovedPlugin plugin;

    public LootrCommand(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eLootr module active");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("gloved.lootr.admin")) {
                    sender.sendMessage("§cNo permission");
                    return true;
                }
                plugin.reloadConfig();
                sender.sendMessage("§aLootr reloaded");
                return true;
            case "info":
                sender.sendMessage("§eLootr info placeholder");
                return true;
            default:
                sender.sendMessage("§cUnknown subcommand");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return List.of("reload", "info", "stats", "clear", "reset", "list");
    }
}
