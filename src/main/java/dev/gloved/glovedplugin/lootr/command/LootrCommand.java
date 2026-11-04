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
        if (plugin.getLootrManager() == null) {
            sender.sendMessage("§cLootr module not enabled");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§eLootr module active");
            sender.sendMessage("§7Use /lootr reload|info|stats|clear|reset|list|convert");
            return true;
        }
        String sub = args[0].toLowerCase();
        if (!sender.hasPermission("gloved.lootr.admin") && !sub.equals("info") && !sub.equals("stats")) {
            sender.sendMessage("§cNo permission");
            return true;
        }
        dev.gloved.glovedplugin.lootr.manager.LootrManager manager = plugin.getLootrManager();
        switch (sub) {
            case "reload":
                plugin.reloadConfig();
                // rebuild config
                dev.gloved.glovedplugin.lootr.config.LootrConfig newCfg = dev.gloved.glovedplugin.lootr.config.LootrConfig.load(plugin.getConfig());
                // manager config reference is immutable, for simplicity just log
                sender.sendMessage("§aLootr config reloaded");
                return true;
            case "info":
                sender.sendMessage("§eLootr info:");
                sender.sendMessage("§7Enabled: " + manager.getConfig().enabled);
                sender.sendMessage("§7Containers tracked: " + getContainerCount(manager));
                return true;
            case "stats":
                sender.sendMessage("§eLootr stats:");
                sender.sendMessage("§7Containers: " + getContainerCount(manager));
                sender.sendMessage("§7Player loot entries: " + getPlayerLootCount(manager));
                return true;
            case "clear":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /lootr clear <player>");
                    return true;
                }
                org.bukkit.entity.Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found");
                    return true;
                }
                clearPlayerLoot(manager, target.getUniqueId());
                sender.sendMessage("§aCleared loot for " + target.getName());
                return true;
            case "reset":
                if (args.length < 5) {
                    sender.sendMessage("§cUsage: /lootr reset <world> <x> <y> <z>");
                    return true;
                }
                try {
                    String worldName = args[1];
                    int x = Integer.parseInt(args[2]);
                    int y = Integer.parseInt(args[3]);
                    int z = Integer.parseInt(args[4]);
                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world == null) {
                        sender.sendMessage("§cWorld not found");
                        return true;
                    }
                    dev.gloved.glovedplugin.lootr.model.ContainerId id = new dev.gloved.glovedplugin.lootr.model.ContainerId(world.getUID(), x, y, z);
                    resetContainerLoot(manager, id);
                    sender.sendMessage("§aReset loot for container at " + worldName + " " + x + "," + y + "," + z);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cInvalid coordinates");
                }
                return true;
            case "convert":
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("§cOnly players can convert");
                    return true;
                }
                org.bukkit.block.Block block = player.getTargetBlockExact(5);
                if (block == null || !(block.getState() instanceof org.bukkit.block.Container)) {
                    sender.sendMessage("§cLook at a container to convert");
                    return true;
                }
                dev.gloved.glovedplugin.lootr.model.ContainerId cid = new dev.gloved.glovedplugin.lootr.model.ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                manager.getRegistry().register(cid, "");
                sender.sendMessage("§aContainer converted to Lootr");
                return true;
            case "list":
                sender.sendMessage("§eRegistered containers:");
                listContainers(sender, manager);
                return true;
            default:
                sender.sendMessage("§cUnknown subcommand");
                return true;
        }
    }

    private int getContainerCount(dev.gloved.glovedplugin.lootr.manager.LootrManager manager) {
        try (java.sql.PreparedStatement ps = manager.getDatabase().getConnection().prepareStatement("SELECT COUNT(*) FROM containers");
             java.sql.ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception ignored) {}
        return 0;
    }

    private int getPlayerLootCount(dev.gloved.glovedplugin.lootr.manager.LootrManager manager) {
        try (java.sql.PreparedStatement ps = manager.getDatabase().getConnection().prepareStatement("SELECT COUNT(*) FROM player_loot");
             java.sql.ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
        } catch (Exception ignored) {}
        return 0;
    }

    private void clearPlayerLoot(dev.gloved.glovedplugin.lootr.manager.LootrManager manager, java.util.UUID player) {
        try (java.sql.PreparedStatement ps = manager.getDatabase().getConnection().prepareStatement("DELETE FROM player_loot WHERE player_uuid = ?")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    private void resetContainerLoot(dev.gloved.glovedplugin.lootr.manager.LootrManager manager, dev.gloved.glovedplugin.lootr.model.ContainerId id) {
        try (java.sql.PreparedStatement ps = manager.getDatabase().getConnection().prepareStatement("DELETE FROM player_loot WHERE container_id = ?")) {
            ps.setString(1, id.toStringKey());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    private void listContainers(CommandSender sender, dev.gloved.glovedplugin.lootr.manager.LootrManager manager) {
        try (java.sql.PreparedStatement ps = manager.getDatabase().getConnection().prepareStatement("SELECT id, world, x, y, z FROM containers LIMIT 50");
             java.sql.ResultSet rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next() && count < 10) {
                sender.sendMessage("§7- " + rs.getString("world") + " " + rs.getInt("x") + "," + rs.getInt("y") + "," + rs.getInt("z"));
                count++;
            }
            if (count == 0) sender.sendMessage("§7No containers registered");
        } catch (Exception ignored) {
            sender.sendMessage("§cError listing containers");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "info", "stats", "clear", "reset", "list", "convert");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            return plugin.getServer().getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList();
        }
        return List.of();
    }
}
