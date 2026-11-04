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
            sender.sendMessage("§7Use /lootr reload|info|stats|clear|reset|list");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (!sender.hasPermission("gloved.lootr.admin") && !sub.equals("info") && !sub.equals("stats")) {
            sender.sendMessage("§cNo permission");
            return true;
        }
        switch (sub) {
            case "reload":
                plugin.reloadConfig();
                // reload lootr config
                sender.sendMessage("§aLootr config reloaded");
                return true;
            case "info":
                sender.sendMessage("§eLootr info:");
                sender.sendMessage("§7Enabled: true");
                return true;
            case "stats":
                sender.sendMessage("§eLootr stats placeholder");
                return true;
            case "clear":
                sender.sendMessage("§eClear command not implemented");
                return true;
            case "reset":
                sender.sendMessage("§eReset command not implemented");
                return true;
            case "list":
                sender.sendMessage("§eList command not implemented");
                return true;
            default:
                sender.sendMessage("§cUnknown subcommand");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "info", "stats", "clear", "reset", "list");
        }
        return List.of();
    }
}
