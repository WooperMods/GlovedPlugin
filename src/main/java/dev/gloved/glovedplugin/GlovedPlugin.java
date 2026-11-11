package dev.gloved.glovedplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
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
    private dev.gloved.glovedplugin.summon.SummonConfig summonConfig;
    private dev.gloved.glovedplugin.summon.SummonManager summonManager;
    private dev.gloved.glovedplugin.summon.SummonInstanceTracker summonTracker;
    private dev.gloved.glovedplugin.summon.MinionAI minionAI;
    private dev.gloved.glovedplugin.summon.nametag.ClumpNametagManager clumpNametagManager;
    private dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge summonProgression;
    private dev.gloved.glovedplugin.summon.progression.SummonAbilityService summonAbilityService;
    private dev.gloved.glovedplugin.summon.soul.db.SoulDatabase soulDatabase;
    private dev.gloved.glovedplugin.summon.soul.SoulService soulService;
    private dev.gloved.glovedplugin.summon.SummonAbilityIndicator summonAbilityIndicator;
    private dev.gloved.glovedplugin.damagehologram.DamageHologramModule damageHologramModule;
    private dev.gloved.glovedplugin.summon.soul.trap.SoulTrapRecipeManager trapRecipeManager;
    private dev.gloved.glovedplugin.summon.soul.trap.SoulTrapListener trapListener;
    private dev.gloved.glovedplugin.summon.soul.table.SoulTableManager soulTableManager;
    private dev.gloved.glovedplugin.summon.soul.table.SoulTableService soulTableService;
    private dev.gloved.glovedplugin.summon.soul.table.SoulTablePackService soulTablePackService;
    private dev.gloved.glovedplugin.summon.soul.table.SoulTableRecipeManager soulTableRecipeManager;

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
        // Try connect to mod bridge for players already online
        try {
            this.glovedBridge.refreshOnlinePlayers();
        } catch (Exception ignored) {}
        FmmBridge.invalidate();

        // Modular summon module (dynamic soul system)
        this.summonConfig = dev.gloved.glovedplugin.summon.SummonConfig.load(this);
        this.soulDatabase = new dev.gloved.glovedplugin.summon.soul.db.SoulDatabase(this);
        try {
            this.soulDatabase.init();
        } catch (Exception e) {
            getLogger().severe("Failed to init soul database (souls.db): " + e);
        }
        this.soulService = new dev.gloved.glovedplugin.summon.soul.SoulService(this, this.soulDatabase);
        // Backfill max_health for existing captured souls that predate this field.
        try {
            this.soulService.getRepository().backfillMissingMaxHealth();
        } catch (Exception e) {
            getLogger().warning("[Souls] Failed to backfill missing max_health: " + e);
        }
        // Soul Table station (block registry, overlay entities, GUI, pack, recipe) + stat backfill.
        this.soulTableManager = new dev.gloved.glovedplugin.summon.soul.table.SoulTableManager(this, this.soulDatabase);
        this.soulTableService = new dev.gloved.glovedplugin.summon.soul.table.SoulTableService(this, this.soulDatabase, this.soulService);
        getServer().getPluginManager().registerEvents(
                new dev.gloved.glovedplugin.summon.soul.table.SoulTableListener(this, this.soulTableManager), this);
        this.soulTableRecipeManager = new dev.gloved.glovedplugin.summon.soul.table.SoulTableRecipeManager(this);
        this.soulTableRecipeManager.registerRecipes();
        getServer().getPluginManager().registerEvents(this.soulTableRecipeManager, this);
        this.soulTablePackService = new dev.gloved.glovedplugin.summon.soul.table.SoulTablePackService(this);
        // Write the pack immediately so ResourcePackManager (which enables after GlovedPlugin) picks it
        // up during its own startup merge. The ServerLoadEvent pass below re-deploys and runs `rspm reload`
        // for the case where ResourcePackManager enabled first (e.g. /reload confirm).
        try {
            this.soulTablePackService.generateAndDeploy();
        } catch (Throwable t) {
            getLogger().warning("[SoulTable] Resource pack deployment failed: " + t.getMessage());
        }
        try {
            var tableCfg = this.summonConfig.getSoulTableConfig();
            if (tableCfg.isStatsEnabled() && tableCfg.isBackfillLegacy()) {
                int updated = this.soulService.getRepository().backfillLegacyBaseStats(tableCfg.getStatConfig());
                if (updated > 0) logInfo("Backfilled base stats for " + updated + " legacy soul(s)");
            }
        } catch (Exception e) {
            getLogger().warning("[Souls] Failed to backfill legacy base stats: " + e);
        }
        // Worlds/entities are ready once the server has finished loading all plugins; rehydrate tables
        // and refresh the resource pack then (ResourcePackManager may enable after GlovedPlugin).
        getServer().getPluginManager().registerEvents(new StartupListener(), this);
        // Initialize soul trap system (recipes + listener)
        this.trapRecipeManager = new dev.gloved.glovedplugin.summon.soul.trap.SoulTrapRecipeManager(this);
        this.trapRecipeManager.registerRecipes();
        getServer().getPluginManager().registerEvents(this.trapRecipeManager, this);
        this.trapListener = new dev.gloved.glovedplugin.summon.soul.trap.SoulTrapListener(this, this.summonConfig, this.soulService);
        getServer().getPluginManager().registerEvents(this.trapListener, this);
        getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.summon.soul.SoulCaptureListener(this, this.soulService), this);
        getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.summon.soul.SoulPickupListener(this), this);
        getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.summon.soul.SoulItemListener(this), this);
        this.summonTracker = new dev.gloved.glovedplugin.summon.SummonInstanceTracker(this);
        this.minionAI = new dev.gloved.glovedplugin.summon.MinionAI(this, this.summonTracker);
        this.summonManager = new dev.gloved.glovedplugin.summon.SummonManager(this, this.summonConfig, this.summonTracker, this.minionAI);
        this.minionAI.setSummon(this.summonConfig, this.summonManager);
        this.summonProgression = createSummonProgressionBridge();
        this.minionAI.setProgression(this.summonProgression);
        this.summonAbilityService = new dev.gloved.glovedplugin.summon.progression.SummonAbilityService(this, this.summonProgression);
        this.minionAI.setAbilityService(this.summonAbilityService);
        this.summonProgression.onEnable();
        // Auto-reload AuraSkills skill into menu after first player joins if it is missing
        if (this.summonProgression instanceof dev.gloved.glovedplugin.summon.progression.AuraSkillsSummonProgressionBridge auraBridge) {
            getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                private boolean firstJoinHandled = false;
                @org.bukkit.event.EventHandler
                public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                    if (firstJoinHandled) return;
                    firstJoinHandled = true;
                    // give a tick for AuraSkills to finish loading user data
                    getServer().getScheduler().runTaskLater(GlovedPlugin.this, auraBridge::ensureSkillPresentAndReload, 20L);
                }
            }, this);
        }
        getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.summon.SummonListener(this, this.summonConfig, this.summonManager, this.summonTracker), this);
        this.summonAbilityIndicator = new dev.gloved.glovedplugin.summon.SummonAbilityIndicator(this, this.summonConfig);
        getServer().getPluginManager().registerEvents(this.summonAbilityIndicator, this);
        getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.summon.DamageProxy(this.summonTracker, this.minionAI), this);
        getServer().getPluginManager().registerEvents(new dev.gloved.glovedplugin.summon.progression.SummonKillAttributionListener(() -> this.summonProgression, () -> this.summonConfig), this);
        this.clumpNametagManager = new dev.gloved.glovedplugin.summon.nametag.ClumpNametagManager(this, this.summonTracker);
        getServer().getPluginManager().registerEvents(this.clumpNametagManager, this);
        this.clumpNametagManager.start();
        // Clean up orphaned summon entities/proxies (e.g. leftover after restart/crash or owner logout edge cases)
        getServer().getScheduler().runTaskLater(this, () -> {
            this.summonTracker.cleanupOrphans();
            this.clumpNametagManager.cleanupOrphanProxies();
        }, 40L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            this.summonTracker.cleanupOrphans();
            this.clumpNametagManager.cleanupOrphanProxies();
        }, 20L * 60L, 20L * 60L);
        // Give soul focus item to online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            try { dev.gloved.glovedplugin.summon.soul.SoulItem.give(player); } catch (Exception ignored) {}
        }
        logInfo("Modular summon module " + (this.summonConfig.isEnabled() ? "enabled" : "disabled") + " (dynamic souls)");

        // AuraSkills-compatible damage holograms (FancyHolograms), excluding EliteMobs targets.
        try {
            this.damageHologramModule = new dev.gloved.glovedplugin.damagehologram.DamageHologramModule(this);
            this.damageHologramModule.load();
        } catch (Throwable t) {
            logWarning("Failed to initialize damage hologram module: " + t);
        }

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
            dev.gloved.glovedplugin.summon.soul.SoulItem.give(player);
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
        if (this.summonConfig != null) {
            // Reload in place so listeners/managers that hold this reference see the new values.
            this.summonConfig.reload();
        }
        if (this.soulTableRecipeManager != null) {
            this.soulTableRecipeManager.registerRecipes();
        }
        if (this.soulTablePackService != null) {
            try {
                var packResult = this.soulTablePackService.generateAndDeploy();
                if (!packResult.ok()) logWarning("[SoulTable] " + packResult.message());
            } catch (Throwable t) {
                logWarning("[SoulTable] Resource pack deployment failed: " + t);
            }
        }
        if (this.summonProgression != null) {
            this.summonProgression = refreshSummonProgressionBridge(this.summonProgression);
            if (this.minionAI != null) this.minionAI.setProgression(this.summonProgression);
            this.summonAbilityService = new dev.gloved.glovedplugin.summon.progression.SummonAbilityService(this, this.summonProgression);
            if (this.minionAI != null) this.minionAI.setAbilityService(this.summonAbilityService);
            this.summonProgression.onReload(this.summonConfig);
        }
        if (this.clumpNametagManager != null) {
            this.clumpNametagManager.restart();
        }
        if (this.damageHologramModule != null) {
            try {
                this.damageHologramModule.reload();
            } catch (Throwable t) {
                logWarning("Failed to reload damage hologram module: " + t);
            }
        }
        if (this.combatListener != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.combatListener.applyReach(player);
                this.combatListener.applyGameMode(player);
            }
        }
        // Refresh mod bridge for online players after reload
        if (this.glovedBridge != null) {
            try {
                this.glovedBridge.refreshOnlinePlayers();
            } catch (Exception ignored) {}
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

    public dev.gloved.glovedplugin.summon.SummonConfig getSummonConfig() {
        return summonConfig;
    }

    public dev.gloved.glovedplugin.summon.SummonManager getSummonManager() {
        return summonManager;
    }

    public dev.gloved.glovedplugin.summon.SummonInstanceTracker getSummonTracker() {
        return summonTracker;
    }

    public dev.gloved.glovedplugin.summon.soul.db.SoulDatabase getSoulDatabase() {
        return soulDatabase;
    }

    public dev.gloved.glovedplugin.summon.soul.SoulService getSoulService() {
        return soulService;
    }

    public dev.gloved.glovedplugin.summon.soul.table.SoulTableManager getSoulTableManager() {
        return soulTableManager;
    }

    public dev.gloved.glovedplugin.summon.soul.table.SoulTableService getSoulTableService() {
        return soulTableService;
    }

    public dev.gloved.glovedplugin.summon.soul.table.SoulTablePackService getSoulTablePackService() {
        return soulTablePackService;
    }

    public dev.gloved.glovedplugin.summon.soul.table.SoulTableRecipeManager getSoulTableRecipeManager() {
        return soulTableRecipeManager;
    }

    public dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge getSummonProgression() {
        return summonProgression;
    }

    public dev.gloved.glovedplugin.summon.progression.SummonAbilityService getSummonAbilityService() {
        return summonAbilityService;
    }

    public dev.gloved.glovedplugin.damagehologram.DamageHologramModule getDamageHologramModule() {
        return damageHologramModule;
    }

    private dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge createSummonProgressionBridge() {
        dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge noop = new dev.gloved.glovedplugin.summon.progression.NoopSummonProgressionBridge();
        if (summonConfig == null || !summonConfig.isProgressionEnabled() || !summonConfig.isProgressionAuraSkillsEnabled()) {
            return noop;
        }
        if (getServer().getPluginManager().getPlugin("AuraSkills") == null) {
            logDebug("[SummonXP] AuraSkills not present; summon progression XP disabled");
            return noop;
        }
        try {
            Class<?> cls = Class.forName("dev.gloved.glovedplugin.summon.progression.AuraSkillsSummonProgressionBridge");
            Object bridge = cls.getConstructor(GlovedPlugin.class, dev.gloved.glovedplugin.summon.SummonConfig.class)
                    .newInstance(this, summonConfig);
            if (bridge instanceof dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge spb) {
                logInfo("Summon progression: AuraSkills bridge enabled");
                return spb;
            }
        } catch (Throwable t) {
            logDebug("[SummonXP] AuraSkills bridge unavailable: " + t);
        }
        return noop;
    }

    private dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge refreshSummonProgressionBridge(
            dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge current) {
        boolean wantAura = summonConfig != null && summonConfig.isProgressionEnabled() && summonConfig.isProgressionAuraSkillsEnabled()
                && getServer().getPluginManager().getPlugin("AuraSkills") != null;
        if (!wantAura) {
            if (!(current instanceof dev.gloved.glovedplugin.summon.progression.NoopSummonProgressionBridge)) {
                try { current.onDisable(); } catch (Exception ignored) {}
                return new dev.gloved.glovedplugin.summon.progression.NoopSummonProgressionBridge();
            }
            return current;
        }
        if (current instanceof dev.gloved.glovedplugin.summon.progression.AuraSkillsSummonProgressionBridge) return current;
        try { current.onDisable(); } catch (Exception ignored) {}
        return createSummonProgressionBridge();
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
        if (summonAbilityService != null) {
            try { summonAbilityService.clear(); } catch (Exception ignored) {}
        }
        if (summonProgression != null) {
            try { summonProgression.onDisable(); } catch (Exception ignored) {}
        }
        if (summonAbilityIndicator != null) {
            try { summonAbilityIndicator.stop(); } catch (Exception ignored) {}
        }
        if (clumpNametagManager != null) {
            clumpNametagManager.stop();
        }
        // Cleanup summoned minions
        if (summonTracker != null) {
            summonTracker.removeAll();
            GlovedPlugin.logInfo("Cleaned up all summoned minions on disable");
        }
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
        if (damageHologramModule != null) {
            try { damageHologramModule.shutdown(); } catch (Exception ignored) {}
        }
        if (lootrManager != null) {
            lootrManager.shutdown();
        }
        if (soulDatabase != null) {
            try { soulDatabase.close(); } catch (Exception ignored) {}
        }
        logInfo("GlovedPlugin has been disabled!");
    }

    /**
     * Post-startup pass. {@link ServerLoadEvent} fires after every plugin is enabled and the worlds
     * are loaded, which is the earliest point where ResourcePackManager can accept `rspm reload` and
     * where Soul Table overlays can be rehydrated safely.
     */
    private final class StartupListener implements Listener {
        @EventHandler
        public void onServerLoad(ServerLoadEvent event) {
            if (soulTablePackService != null) {
                try {
                    var result = soulTablePackService.generateAndDeploy();
                    if (!result.ok()) logWarning("[SoulTable] " + result.message());
                } catch (Throwable t) {
                    getLogger().warning("[SoulTable] Resource pack deployment failed: " + t.getMessage());
                }
            }
            if (soulTableManager != null) {
                soulTableManager.loadAll();
            }
        }
    }
}
