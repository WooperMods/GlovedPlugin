package dev.gloved.glovedplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import dev.gloved.glovedplugin.Menus.MenuInv;
import dev.gloved.glovedplugin.combat.CombatListener;
import dev.gloved.glovedplugin.combat.GlovedBridge;
import dev.gloved.glovedplugin.combat.FmmBridge;
import dev.gloved.glovedplugin.combat.WeaponRegistry;
import dev.gloved.glovedplugin.commands.GlovedCommand;
import dev.gloved.glovedplugin.commands.Menu;
import dev.gloved.glovedplugin.editor.EditSessionCache;
import dev.gloved.glovedplugin.handlers.MenuHandler;
import dev.gloved.glovedplugin.handlers.TorchHandler;

import java.util.logging.Logger;

/**
 * The MenuInv class implements the InventoryHolder interface.
 * It represents a custom inventory menu for the GlovedPlugin.
 * This class is responsible for creating the menu, setting up the items in the menu, and providing access to the menu.
 * It uses the Bukkit API to create an inventory and the Kyori Adventure API to create text components for item names and lore.
 */
public final class GlovedPlugin extends JavaPlugin {

    private static Logger logger;

    private MenuConfig menuConfig;
    private final WeaponRegistry weaponRegistry = new WeaponRegistry();
    private CombatListener combatListener;
    private GlovedBridge glovedBridge;
    private final java.util.Set<java.util.UUID> pvpDisabled = new java.util.HashSet<>();
    private dev.gloved.glovedplugin.lootr.manager.LootrManager lootrManager;

    {
        logger = getLogger();
    }

    /**
     * This method is used to log information messages in a specific format.
     * The format is: <###>-------------< {message} >-------------<###>
     *
     * @param message The message that needs to be logged.
     */
    public static void logInfo(String message) {
        logger.info("<##>-------------< " + message + " >-------------<##>");
        DebugLog.log("INFO", message);
    }

    public static void logDebug(String message) {
        if (!DebugLog.isEnabled()) return;
        logger.info("<##>-------------< " + message + " >-------------<##>");
        DebugLog.log("INFO", message);
    }

    /**
     * This method is used to log warning messages in a specific format.
     * The format is: <###>-------------< {message} >-------------<###>
     *
     * @param message The warning message that needs to be logged.
     */
    public static void logWarning(String message) {
        logger.warning("<###>-------------< " + message + " >-------------<###>");
        DebugLog.log("WARNING", message);
    }

    /**
     * This method is used to create a Component from a given text string.
     * It uses Adventure's LegacyComponentSerializer to convert legacy & codes.
     *
     * @param input The text string that needs to be converted into a Component.
     * @return The created Component.
     */
    public static Component makeComponent(String input) {
        
        return LegacyComponentSerializer.legacyAmpersand().deserialize(input);
    }

    /**
     * This method is called when the plugin is enabled.
     * It logs a message indicating that the GlovedPlugin has been loaded.
     * It also initializes various handlers and menus, and registers the plugin with FastInvManager.
     */
    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        ensureConfigDefaults();
        this.menuConfig = MenuConfig.load(this);
        Menu.refreshTitle(this.menuConfig);

        // Initialize TorchHandler
        new TorchHandler(this);
        // Initialize MenuHandler
        new MenuHandler(this);
        // Initialize MenuInv
        new MenuInv(this);

        // Initialize Lootr module
        java.io.File lootrFile = new java.io.File(getDataFolder(), "lootr.yml");
        if (!lootrFile.exists()) {
            saveResource("lootr.yml", false);
        } else {
            // Backfill new lootr keys into existing server files without
            // overwriting anything the admin has set.
            try {
                org.bukkit.configuration.file.FileConfiguration lootrBackfill = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(lootrFile);
                boolean changed = false;
                if (!lootrBackfill.isSet("elite.regular-chance")) {
                    lootrBackfill.set("elite.regular-chance", 0.65);
                    changed = true;
                }
                if (!lootrBackfill.isSet("elite.regular-max-items")) {
                    lootrBackfill.set("elite.regular-max-items", 6);
                    changed = true;
                }
                if (!lootrBackfill.isSet("elite.extra-chance")) {
                    lootrBackfill.set("elite.extra-chance", 0.65);
                    changed = true;
                }
                if (!lootrBackfill.isSet("elite.up-jitter-chance")) {
                    lootrBackfill.set("elite.up-jitter-chance", 0.65);
                    changed = true;
                }
                if (!lootrBackfill.isSet("elite.up-jitter-max")) {
                    lootrBackfill.set("elite.up-jitter-max", 3);
                    changed = true;
                }
                if (changed) lootrBackfill.save(lootrFile);
            } catch (Exception e) {
                logInfo("Could not backfill lootr.yml defaults: " + e);
            }
        }
        org.bukkit.configuration.file.FileConfiguration lootrCfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(lootrFile);
        dev.gloved.glovedplugin.lootr.config.LootrConfig lootrConfig = dev.gloved.glovedplugin.lootr.config.LootrConfig.load(lootrCfg);
        if (lootrConfig.enabled) {
            this.lootrManager = new dev.gloved.glovedplugin.lootr.manager.LootrManager(this, lootrConfig);
            this.lootrManager.load();
            getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.lootr.listener.ChestOpenListener(this, this.lootrManager), this);
            getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.lootr.listener.ProtectionListener(this.lootrManager), this);
            getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.lootr.listener.ChestAutoRegisterListener(this.lootrManager), this);
            getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.lootr.listener.LootrChestPlaceListener(this, this.lootrManager), this);
            logInfo("Lootr module enabled");
        }

        // Server-side Better Combat-lite (arc + combo + sweep + dual-wield).
        // Soft-depends only: safe with or without FMM / PacketEvents / RPM.
        DebugLog.init(this);
        this.weaponRegistry.load(this);
        this.combatListener = new CombatListener(this, this.weaponRegistry);
        this.glovedBridge = new GlovedBridge(this, this.combatListener);
        FmmBridge.invalidate();

        // Register menu command using Paper's registerCommand API
        Menu menuCommand = new Menu();
        this.registerCommand("menu", "Custom menu for Commands", menuCommand);
        this.registerCommand("gloved", "GlovedPlugin admin command", new GlovedCommand(this));
        this.registerCommand("lootr", "Lootr per-player loot commands", new dev.gloved.glovedplugin.lootr.command.LootrCommand(this));
        logInfo("Registered Menu Command!");

        // Hot-reload safe: PlayerJoin doesn't fire for already-online players
        // when using a runtime plugin reloader, so refresh the opener for everyone.
        // This also replaces stale items created before the color-code fix.
        for (Player player : Bukkit.getOnlinePlayers()) {
            MenuHandler.giveMenuOpener(player);
        }

        logInfo("GlovedPlugin has been loaded!");
        EditSessionCache.loadAll(this);

    }

    public MenuConfig getMenuConfig() {
        return menuConfig;
    }

    public void reloadMenuConfig() {
        reloadConfig();
        ensureConfigDefaults();
        this.menuConfig = MenuConfig.load(this);
        Menu.refreshTitle(this.menuConfig);
        this.weaponRegistry.load(this);
        FmmBridge.invalidate();
        if (this.combatListener != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.combatListener.applyReach(player);
                this.combatListener.applyGameMode(player);
            }
        }
    }

    public WeaponRegistry getWeaponRegistry() {
        return weaponRegistry;
    }

    public GlovedBridge getGlovedBridge() {
        return glovedBridge;
    }

    public dev.gloved.glovedplugin.lootr.manager.LootrManager getLootrManager() {
        return lootrManager;
    }

    public boolean isPvpDisabled(org.bukkit.entity.Player player) {
        return pvpDisabled.contains(player.getUniqueId());
    }

    public boolean togglePvp(org.bukkit.entity.Player player) {
        java.util.UUID id = player.getUniqueId();
        boolean disabled;
        if (pvpDisabled.contains(id)) {
            pvpDisabled.remove(id);
            disabled = false;
        } else {
            pvpDisabled.add(id);
            disabled = true;
        }
        return disabled;
    }

    public java.util.Set<java.util.UUID> getPvpDisabledSet() {
        return pvpDisabled;
    }

    /**
     * Writes any missing options from the bundled default config.yml into the
     * server's config file (present values are never overwritten). Keeps older
     * config files complete after updates add new options. Note: Bukkit cannot
     * preserve comments when saving, so the file loses its comments the first
     * time this fills something in; the full reference lives in README.md.
     */
    public void ensureConfigDefaults() {
        try {
            org.bukkit.configuration.file.FileConfiguration cfg = getConfig();
            cfg.options().copyDefaults(true);
            backfillItemOptions(cfg);
            purgeRedundantCommandLore(cfg);
            saveConfig();
        } catch (Exception e) {
            getLogger().warning("GlovedPlugin could not write missing config defaults: " + e);
        }
    }

    /**
     * Removes a leading lore line that merely repeats the button's command,
     * since the menu now renders the command as the first lore line
     * automatically. Only exact matches (ignoring color codes and a leading
     * slash) on the FIRST line are removed; everything else is untouched, so
     * running this twice changes nothing the second time.
     */
    public static void purgeRedundantCommandLore(org.bukkit.configuration.file.FileConfiguration cfg) {
        java.util.List<?> items = cfg.getList("menu.items");
        if (items == null) return;
        for (Object obj : items) {
            if (!(obj instanceof java.util.Map)) continue;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> item = (java.util.Map<String, Object>) obj;
            Object cmdObj = item.get("command");
            if (cmdObj == null || String.valueOf(cmdObj).isBlank()) continue;
            String cmd = String.valueOf(cmdObj).strip();
            Object loreObj = item.get("lore");
            if (!(loreObj instanceof java.util.List) || ((java.util.List<?>) loreObj).isEmpty()) continue;
            Object first = ((java.util.List<?>) loreObj).get(0);
            if (first == null) continue;
            String stripped = String.valueOf(first).replaceAll("[&§](?i)[0-9a-fk-or]", "").strip();
            String bare = stripped.startsWith("/") ? stripped.substring(1).strip() : stripped;
            if (bare.equalsIgnoreCase(cmd)) {
                java.util.List<Object> lore = new java.util.ArrayList<>((java.util.List<?>) loreObj);
                lore.remove(0);
                item.put("lore", lore);
            }
        }
    }

    /**
     * Adds missing per-item option keys to every entry of {@code menu.items}.
     * Bukkit's defaults-merge cannot reach inside existing lists, so this fills
     * them explicitly. Every written value matches the loader's fallback
     * exactly (enabled=true, offsets=0, empty lore, blank anchor/slot meaning
     * auto layout), so files change but behavior never does. Identity keys
     * (material/name/command) are left alone.
     */
    public static void backfillItemOptions(org.bukkit.configuration.file.FileConfiguration cfg) {
        java.util.List<?> items = cfg.getList("menu.items");
        if (items == null) return;
        for (Object obj : items) {
            if (!(obj instanceof java.util.Map)) continue;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> item = (java.util.Map<String, Object>) obj;
            if (!item.containsKey("enabled")) item.put("enabled", true);
            if (!item.containsKey("anchor")) item.put("anchor", null);
            if (!item.containsKey("offset-x") && !item.containsKey("offsetX")) item.put("offset-x", 0);
            if (!item.containsKey("offset-y") && !item.containsKey("offsetY")) item.put("offset-y", 0);
            if (!item.containsKey("slot")) item.put("slot", null);
            if (!item.containsKey("lore")) item.put("lore", java.util.List.of());
            if (!item.containsKey("op")) item.put("op", false);
        }
    }

    /**
     * This method is called when the plugin is disabled.
     * It logs a message indicating that the GlovedPlugin has been disabled.
     */
    @Override
    public void onDisable() {
        try {
            if (glovedBridge != null) glovedBridge.shutdown();
        } catch (Exception ignored) {}
        EditSessionCache.storeAll(this);
        // Close any open menu views so a hot-reload doesn't leave ghost
        // inventories / cursor items attached. Fully guarded: an exception
        // here would abort the disable and orphan our listeners, leaving a
        // stale handler wiping cursors after reload.
        try {
            Component title = menuConfig != null ? menuConfig.titleComponent() : Menu.menuComponent;
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    if (player.getOpenInventory().title().equals(title)) {
                        player.closeInventory();
                    }
                    ItemStack cursor = player.getItemOnCursor();
                    if (cursor != null && cursor.getType() != Material.AIR && MenuInv.isMenuOpener(cursor)) {
                        player.setItemOnCursor(null);
                    }
                } catch (Exception e) {
                    getLogger().warning("GlovedPlugin onDisable cleanup failed for a player: " + e);
                }
            }
        } catch (Exception e) {
            getLogger().warning("GlovedPlugin onDisable failed: " + e);
        }
        if (lootrManager != null) {
            lootrManager.shutdown();
        }
        logInfo("GlovedPlugin has been disabled!");
    }


}
