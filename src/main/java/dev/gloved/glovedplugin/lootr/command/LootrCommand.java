package dev.gloved.glovedplugin.lootr.command;

import dev.gloved.glovedplugin.GlovedPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

public class LootrCommand implements BasicCommand {
    private final GlovedPlugin plugin;

    public LootrCommand(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();
        if (plugin.getLootrManager() == null) {
            sender.sendMessage("§cLootr module not enabled");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage("§eLootr module active");
            sender.sendMessage("§7Use /lootr reload|info|stats|clear|reset|list|convert|generate");
            return;
        }
        String sub = args[0].toLowerCase();
        if (!sender.hasPermission("gloved.lootr.admin") && !sub.equals("info") && !sub.equals("stats")) {
            sender.sendMessage("§cNo permission");
            return;
        }
        dev.gloved.glovedplugin.lootr.manager.LootrManager manager = plugin.getLootrManager();
        switch (sub) {
            case "reload":
                java.io.File lootrFile = new java.io.File(plugin.getDataFolder(), "lootr.yml");
                org.bukkit.configuration.file.FileConfiguration lootrCfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(lootrFile);
                sender.sendMessage("§aLootr config reloaded");
                return;
            case "info":
                sender.sendMessage("§eLootr info:");
                sender.sendMessage("§7Enabled: " + manager.getConfig().enabled);
                sender.sendMessage("§7Containers tracked: " + getContainerCount(manager));
                return;
            case "stats":
                sender.sendMessage("§eLootr stats:");
                sender.sendMessage("§7Containers: " + getContainerCount(manager));
                sender.sendMessage("§7Player loot entries: " + getPlayerLootCount(manager));
                return;
            case "clear":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /lootr clear <player>");
                    return;
                }
                org.bukkit.entity.Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found");
                    return;
                }
                clearPlayerLoot(manager, target.getUniqueId());
                sender.sendMessage("§aCleared loot for " + target.getName());
                return;
            case "reset":
                dev.gloved.glovedplugin.lootr.model.ContainerId resetId = null;
                if (args.length == 1) {
                    if (!(sender instanceof org.bukkit.entity.Player player)) {
                        sender.sendMessage("§cOnly players can reset by looking at a container");
                        return;
                    }
                    org.bukkit.block.Block block = player.getTargetBlockExact(5);
                    if (block == null || !(block.getState() instanceof org.bukkit.block.Container)) {
                        sender.sendMessage("§cLook at a container to reset");
                        return;
                    }
                    resetId = new dev.gloved.glovedplugin.lootr.model.ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                } else if (args.length >= 5) {
                    try {
                        String worldName = args[1];
                        int x = Integer.parseInt(args[2]);
                        int y = Integer.parseInt(args[3]);
                        int z = Integer.parseInt(args[4]);
                        org.bukkit.World world = plugin.getServer().getWorld(worldName);
                        if (world == null) {
                            sender.sendMessage("§cWorld not found");
                            return;
                        }
                        resetId = new dev.gloved.glovedplugin.lootr.model.ContainerId(world.getUID(), x, y, z);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage("§cInvalid coordinates");
                        return;
                    }
                } else {
                    sender.sendMessage("§cUsage: /lootr reset [world x y z] or look at a container and run /lootr reset");
                    return;
                }
                resetContainerLoot(manager, resetId);
                sender.sendMessage("§aReset loot for container at " + resetId.x() + "," + resetId.y() + "," + resetId.z());
                return;
            case "convert":
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("§cOnly players can convert");
                    return;
                }
                org.bukkit.block.Block block = player.getTargetBlockExact(5);
                if (block == null || !(block.getState() instanceof org.bukkit.block.Container)) {
                    sender.sendMessage("§cLook at a container to convert");
                    return;
                }
                dev.gloved.glovedplugin.lootr.model.ContainerId cid = new dev.gloved.glovedplugin.lootr.model.ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
                String lootTableKey = "";
                if (block.getState() instanceof org.bukkit.loot.Lootable lootable) {
                    org.bukkit.loot.LootTable table = lootable.getLootTable();
                    if (table != null) {
                        lootTableKey = table.getKey().toString();
                        // Clear vanilla loot to avoid duplicate generation
                        lootable.setLootTable(null);
                        lootable.setSeed(0);
                        block.getState().update(true, false);
                        // Clear inventory
                        ((org.bukkit.block.Container) block.getState()).getInventory().clear();
                    }
                }
                manager.getRegistry().register(cid, lootTableKey);
                sender.sendMessage("§aContainer converted to Lootr" + (lootTableKey.isEmpty() ? "" : " with loot table"));
                return;
            case "generate":
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("§cOnly players can generate");
                    return;
                }
                org.bukkit.Location loc = player.getLocation().clone().subtract(0, 1, 0);
                org.bukkit.block.Block b = loc.getBlock();
                if (!b.getType().isAir() && b.getType() != org.bukkit.Material.CHEST) {
                    sender.sendMessage("§cBlock in the way");
                    return;
                }
                b.setType(org.bukkit.Material.CHEST);
                dev.gloved.glovedplugin.lootr.model.ContainerId genId = new dev.gloved.glovedplugin.lootr.model.ContainerId(player.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
                java.util.List<String> tables = java.util.List.of(
                    "minecraft:chests/simple_dungeon",
                    "minecraft:chests/abandoned_mineshaft",
                    "minecraft:chests/shipwreck_treasure",
                    "minecraft:chests/buried_treasure",
                    "minecraft:chests/desert_pyramid",
                    "minecraft:chests/jungle_temple",
                    "minecraft:chests/stronghold_corridor",
                    "minecraft:chests/woodland_mansion",
                    "minecraft:chests/end_city_treasure",
                    "minecraft:chests/nether_bridge"
                );
                String chosen = tables.get(new java.util.Random().nextInt(tables.size()));
                manager.getRegistry().register(genId, chosen);
                sender.sendMessage("§aGenerated Lootr chest at " + b.getX() + "," + b.getY() + "," + b.getZ() + " with " + chosen);
                return;
            case "list":
                sender.sendMessage("§eRegistered containers:");
                listContainers(sender, manager);
                return;
            default:
                sender.sendMessage("§cUnknown subcommand");
                return;
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "info", "stats", "clear", "reset", "list", "convert", "generate");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            return plugin.getServer().getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList();
        }
        return List.of();
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
}
