package dev.gloved.glovedplugin.lootr.command;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import dev.gloved.glovedplugin.lootr.LootrModule;
import dev.gloved.glovedplugin.lootr.model.ContainerId;
import dev.gloved.glovedplugin.lootr.service.PlayerLootService;

public class LootrCommand implements BasicCommand {
    private final GlovedPlugin plugin;

    public LootrCommand(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        CommandSender sender = stack.getSender();
        if (plugin.getLootrModule() == null) {
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
        LootrModule manager = plugin.getLootrModule();
        PlayerLootService playerLootService =
                new PlayerLootService(manager.getDatabase().getConnection());
        switch (sub) {
            case "reload":
                java.io.File lootrFile = new java.io.File(plugin.getDataFolder(), "lootr.yml");
                org.bukkit.configuration.file.FileConfiguration lootrCfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(lootrFile);
                sender.sendMessage("§aLootr config reloaded");
                return;
            case "info":
                sender.sendMessage("§eLootr info:");
                sender.sendMessage("§7Enabled: " + manager.getConfig().enabled);
                sender.sendMessage("§7Containers tracked: " + manager.getRegistry().count());
                return;
            case "stats":
                sender.sendMessage("§eLootr stats:");
                sender.sendMessage("§7Containers: " + manager.getRegistry().count());
                sender.sendMessage("§7Player loot entries: " + playerLootService.countAll());
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
                playerLootService.deleteByPlayer(target.getUniqueId());
                sender.sendMessage("§aCleared loot for " + target.getName());
                return;
            case "reset":
                ContainerId resetId = null;
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
                    resetId = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
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
                        resetId = new ContainerId(world.getUID(), x, y, z);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage("§cInvalid coordinates");
                        return;
                    }
                } else {
                    sender.sendMessage("§cUsage: /lootr reset [world x y z] or look at a container and run /lootr reset");
                    return;
                }
                playerLootService.resetContainerLoot(resetId);
                sender.sendMessage("§aReset loot for container at " + resetId.x() + "," + resetId.y() + "," + resetId.z() + " §7(elite status preserved)");
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
                ContainerId cid = new ContainerId(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
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
                String mode = "normal";
                if (args.length >= 2) {
                    mode = args[1].toLowerCase();
                    if (!mode.equals("normal") && !mode.equals("elite") && !mode.equals("blank")) {
                        sender.sendMessage("§cUsage: /lootr generate [normal|elite|blank]");
                        return;
                    }
                }
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
                // blank = empty chest with no loot table at all
                String chosen = mode.equals("blank") ? "" : tables.get(new java.util.Random().nextInt(tables.size()));
                GlovedPlugin.logDebug("Lootr generate mode=" + mode + " chosen=" + chosen);
                // Create Lootr chest item
                org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.CHEST);
                org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    String displayName = mode.equals("elite") ? "§6Elite Chest" : "§6Loot Chest";
                    meta.setDisplayName(displayName);
                    java.util.List<String> lore = new java.util.ArrayList<>();
                    lore.add("§7Right-click to place");
                    if (!chosen.isEmpty()) {
                        lore.add("§7Loot table: " + chosen);
                    } else if (mode.equals("blank")) {
                        lore.add("§7Blank chest");
                    }
                    lore.add("§7Mode: " + mode);
                    meta.setLore(lore);
                    org.bukkit.NamespacedKey keyMode = new org.bukkit.NamespacedKey(plugin, "lootr_mode");
                    org.bukkit.NamespacedKey keyLoot = new org.bukkit.NamespacedKey(plugin, "lootr_loot");
                    org.bukkit.NamespacedKey keyId = new org.bukkit.NamespacedKey(plugin, "lootr_id");
                    org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
                    pdc.set(keyMode, org.bukkit.persistence.PersistentDataType.STRING, mode);
                    pdc.set(keyLoot, org.bukkit.persistence.PersistentDataType.STRING, chosen);
                    pdc.set(keyId, org.bukkit.persistence.PersistentDataType.STRING, java.util.UUID.randomUUID().toString());
                    item.setItemMeta(meta);
                }
                player.getInventory().addItem(item);
                if (mode.equals("elite")) {
                    sender.sendMessage("§aGiven Elite Lootr chest item" + (chosen.isEmpty() ? "" : " with " + chosen));
                } else {
                    sender.sendMessage("§aGiven Lootr chest item" + (chosen.isEmpty() ? " (blank)" : " with " + chosen));
                }
                return;
            case "list":
                sender.sendMessage("§eRegistered containers:");
                java.util.List<ContainerId> containers = manager.getRegistry().list(50);
                int count = 0;
                for (ContainerId id : containers) {
                    if (count >= 10) break;
                    sender.sendMessage("§7- " + id.world() + " " + id.x() + "," + id.y() + "," + id.z());
                    count++;
                }
                if (count == 0) sender.sendMessage("§7No containers registered");
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
}
