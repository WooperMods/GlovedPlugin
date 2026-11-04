package dev.gloved.glovedplugin.lootr.config;

import org.bukkit.configuration.file.FileConfiguration;

public class LootrConfig {
    public boolean enabled;
    public boolean autoConvert;
    public boolean perPlayerLoot;
    public int maxTrackedChests;
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
    public boolean elitemobsEnabled;
    public boolean elitemobsMixVanilla;
    public String elitemobsScale;
    public int autoSaveInterval;

    public static LootrConfig load(FileConfiguration cfg) {
        LootrConfig c = new LootrConfig();
        c.enabled = cfg.getBoolean("lootr.enabled", true);
        c.autoConvert = cfg.getBoolean("lootr.auto-convert", true);
        c.perPlayerLoot = cfg.getBoolean("lootr.per-player-loot", true);
        c.maxTrackedChests = cfg.getInt("lootr.max-tracked-chests", 50000);
        c.protectionBreak = cfg.getBoolean("lootr.protection.prevent-break", true);
        c.protectionHopper = cfg.getBoolean("lootr.protection.prevent-hopper", true);
        c.protectionExplosion = cfg.getBoolean("lootr.protection.prevent-explosion", true);
        c.protectionPiston = cfg.getBoolean("lootr.protection.prevent-piston", true);
        c.breakConfirmTime = cfg.getInt("lootr.protection.break-confirm-time", 3);
        c.bypassProtection = cfg.getBoolean("lootr.protection.bypass-protection", true);
        c.particlesEnabled = cfg.getBoolean("lootr.visuals.particles-enabled", true);
        c.particleType = cfg.getString("lootr.visuals.particle-type", "VILLAGER_HAPPY");
        c.particlesOnlyUnlooted = cfg.getBoolean("lootr.visuals.particles-only-unlooted", true);
        c.inventoryTitle = cfg.getString("lootr.inventory.title", "&8[&6✦&8] &eLoot Chest");
        c.playSound = cfg.getBoolean("lootr.inventory.play-sound", true);
        c.elitemobsEnabled = cfg.getBoolean("lootr.elitemobs.enabled", true);
        c.elitemobsMixVanilla = cfg.getBoolean("lootr.elitemobs.mix-vanilla", true);
        c.elitemobsScale = cfg.getString("lootr.elitemobs.scale", "COMBAT_LEVEL");
        c.autoSaveInterval = cfg.getInt("lootr.storage.auto-save-interval", 5);
        return c;
    }
}
