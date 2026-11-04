package dev.gloved.glovedplugin.lootr.config;

import org.bukkit.configuration.file.FileConfiguration;

public class LootrConfig {
    public boolean enabled;
    public boolean autoConvert;
    public boolean perPlayerLoot;
    public int maxTrackedChests;
    public java.util.List<String> containers;
    public java.util.List<String> disabledWorlds;
    public boolean protectionBreak;
    public boolean protectionHopper;
    public boolean protectionExplosion;
    public boolean protectionPiston;
    public int breakConfirmTime;
    public boolean bypassProtection;
    public boolean particlesEnabled;
    public String particleType;
    public boolean particlesOnlyUnlooted;
    public String inventoryTitle;
    public boolean playSound;
    public boolean eliteEnabled;
    public double eliteChance;
    public double eliteLuckMultiplier;
    public int eliteMinItems;
    public int eliteMaxItems;
    public String eliteTitleSuffix;
    public double eliteRegularChance;
    public int eliteRegularMaxItems;
    public int autoSaveInterval;

    public static LootrConfig load(FileConfiguration cfg) {
        LootrConfig c = new LootrConfig();
        c.enabled = cfg.getBoolean("enabled", true);
        c.autoConvert = cfg.getBoolean("auto-convert", true);
        c.perPlayerLoot = cfg.getBoolean("per-player-loot", true);
        c.maxTrackedChests = cfg.getInt("max-tracked-chests", 50000);
        c.containers = cfg.getStringList("containers");
        if (c.containers.isEmpty()) c.containers = java.util.List.of("CHEST", "BARREL", "SHULKER_BOX", "MINECART_CHEST");
        c.disabledWorlds = cfg.getStringList("disabled-worlds");
        c.protectionBreak = cfg.getBoolean("protection.prevent-break", true);
        c.protectionHopper = cfg.getBoolean("protection.prevent-hopper", true);
        c.protectionExplosion = cfg.getBoolean("protection.prevent-explosion", true);
        c.protectionPiston = cfg.getBoolean("protection.prevent-piston", true);
        c.breakConfirmTime = cfg.getInt("protection.break-confirm-time", 3);
        c.bypassProtection = cfg.getBoolean("protection.bypass-protection", true);
        c.particlesEnabled = cfg.getBoolean("visuals.particles-enabled", true);
        c.particleType = cfg.getString("visuals.particle-type", "VILLAGER_HAPPY");
        c.particlesOnlyUnlooted = cfg.getBoolean("visuals.particles-only-unlooted", true);
        c.inventoryTitle = cfg.getString("inventory.title", "&8[&6✦&8] &eLoot Chest");
        c.playSound = cfg.getBoolean("inventory.play-sound", true);
        c.eliteEnabled = cfg.getBoolean("elite.enabled", true);
        c.eliteChance = cfg.getDouble("elite.chance", 0.08);
        c.eliteLuckMultiplier = cfg.getDouble("elite.luck-multiplier", 0.02);
        c.eliteMinItems = cfg.getInt("elite.min-items", 1);
        c.eliteMaxItems = cfg.getInt("elite.max-items", 3);
        c.eliteTitleSuffix = cfg.getString("elite.title-suffix", "&6[Elite]");
        c.eliteRegularChance = cfg.getDouble("elite.regular-chance", 0.65);
        c.eliteRegularMaxItems = cfg.getInt("elite.regular-max-items", 6);
        c.autoSaveInterval = cfg.getInt("storage.auto-save-interval", 5);
        return c;
    }
}
