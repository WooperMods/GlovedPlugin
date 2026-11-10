package dev.gloved.glovedplugin.damagehologram;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Effective settings for the AuraSkills-compatible damage hologram module.
 * Values are primarily read live from AuraSkills' config.yml (Mode A), with
 * module-specific toggles and fallbacks from damage-holograms.yml.
 * The module is always enabled regardless of AuraSkills' damage_holograms.enabled.
 */
public class DamageHologramConfig {

    public final boolean excludeEliteMobs;
    public final String provider; // auto | fancyholograms
    public final Set<String> moduleDisabledWorlds;

    // Effective AuraSkills-mirrored values
    public final boolean scaling;
    public final double minDamage;
    public final double decimalDisplayWhenLessThan;
    public final int decimalMaxAmount;
    public final double offsetX;
    public final double offsetY;
    public final double offsetZ;
    public final boolean randomOffsetEnabled;
    public final double randomXMin;
    public final double randomXMax;
    public final double randomYMin;
    public final double randomYMax;
    public final double randomZMin;
    public final double randomZMax;
    public final ChatColor defaultColor;
    public final List<ChatColor> criticalColors;
    public final Set<String> auraDisabledWorlds;

    public DamageHologramConfig(boolean excludeEliteMobs, String provider, Set<String> moduleDisabledWorlds,
                                boolean scaling, double minDamage, double decimalDisplayWhenLessThan, int decimalMaxAmount,
                                double offsetX, double offsetY, double offsetZ,
                                boolean randomOffsetEnabled,
                                double randomXMin, double randomXMax, double randomYMin, double randomYMax, double randomZMin, double randomZMax,
                                ChatColor defaultColor, List<ChatColor> criticalColors, Set<String> auraDisabledWorlds) {
        this.excludeEliteMobs = excludeEliteMobs;
        this.provider = provider;
        this.moduleDisabledWorlds = moduleDisabledWorlds;
        this.scaling = scaling;
        this.minDamage = minDamage;
        this.decimalDisplayWhenLessThan = decimalDisplayWhenLessThan;
        this.decimalMaxAmount = decimalMaxAmount;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.randomOffsetEnabled = randomOffsetEnabled;
        this.randomXMin = randomXMin;
        this.randomXMax = randomXMax;
        this.randomYMin = randomYMin;
        this.randomYMax = randomYMax;
        this.randomZMin = randomZMin;
        this.randomZMax = randomZMax;
        this.defaultColor = defaultColor;
        this.criticalColors = criticalColors;
        this.auraDisabledWorlds = auraDisabledWorlds;
    }

    public boolean isWorldDisabled(String worldName) {
        if (worldName == null) return false;
        if (auraDisabledWorlds.contains(worldName)) return true;
        return moduleDisabledWorlds.contains(worldName);
    }

    public static DamageHologramConfig load(File moduleFile, File auraConfigFile) {
        FileConfiguration module = YamlConfiguration.loadConfiguration(moduleFile);
        FileConfiguration aura = auraConfigFile != null && auraConfigFile.isFile()
                ? YamlConfiguration.loadConfiguration(auraConfigFile) : null;

        boolean excludeEliteMobs = module.getBoolean("exclude-elitemobs", true);
        String provider = module.getString("provider", "auto");
        if (provider == null || provider.isBlank()) provider = "auto";
        provider = provider.trim().toLowerCase(Locale.ROOT);

        Set<String> moduleDisabled = new HashSet<>(module.getStringList("disabled-worlds"));
        Set<String> auraDisabled = new HashSet<>();
        if (aura != null) {
            auraDisabled.addAll(aura.getStringList("disabled_worlds"));
        }

        ConfigurationSection fb = module.getConfigurationSection("fallback");

        boolean scaling = getAuraBool(aura, "damage_holograms.scaling", fbBool(fb, "scaling", false));
        double minDamage = getAuraDouble(aura, "damage_holograms.min_damage", fbDouble(fb, "min-damage", 0.0));
        double decimalLessThan = getAuraDouble(aura, "damage_holograms.decimal.display_when_less_than",
                getAuraDouble(aura, "damage_holograms.decimal.less_than", fbDouble(fb, "decimal.display-when-less-than", 100.0)));
        int decimalMax = getAuraInt(aura, "damage_holograms.decimal.max_amount", fbInt(fb, "decimal.max-amount", 2));

        double offX = getAuraDouble(aura, "damage_holograms.offset.x", fbDouble(fb, "offset.x", 0.0));
        double offY = getAuraDouble(aura, "damage_holograms.offset.y", fbDouble(fb, "offset.y", 0.0));
        double offZ = getAuraDouble(aura, "damage_holograms.offset.z", fbDouble(fb, "offset.z", 0.0));
        boolean randEnabled = getAuraBool(aura, "damage_holograms.offset.random.enabled", fbBool(fb, "offset.random.enabled", false));
        double rxMin = getAuraDouble(aura, "damage_holograms.offset.random.x_min", fbDouble(fb, "offset.random.x-min", -0.5));
        double rxMax = getAuraDouble(aura, "damage_holograms.offset.random.x_max", fbDouble(fb, "offset.random.x-max", 0.5));
        double ryMin = getAuraDouble(aura, "damage_holograms.offset.random.y_min", fbDouble(fb, "offset.random.y-min", 0.0));
        double ryMax = getAuraDouble(aura, "damage_holograms.offset.random.y_max", fbDouble(fb, "offset.random.y-max", 0.5));
        double rzMin = getAuraDouble(aura, "damage_holograms.offset.random.z_min", fbDouble(fb, "offset.random.z-min", -0.5));
        double rzMax = getAuraDouble(aura, "damage_holograms.offset.random.z_max", fbDouble(fb, "offset.random.z-max", 0.5));

        String defColorName = getAuraString(aura, "damage_holograms.colors.default", fbString(fb, "colors.default", "GRAY"));
        ChatColor defaultColor = parseChatColor(defColorName, ChatColor.GRAY);

        List<String> critNames = aura != null && aura.isList("damage_holograms.colors.critical.digits")
                ? aura.getStringList("damage_holograms.colors.critical.digits")
                : (fb != null ? fb.getStringList("colors.critical-digits") : List.of());
        if (critNames == null || critNames.isEmpty()) {
            critNames = List.of("WHITE", "YELLOW", "GOLD", "RED");
        }
        List<ChatColor> criticalColors = new ArrayList<>();
        for (String s : critNames) {
            criticalColors.add(parseChatColor(s, ChatColor.WHITE));
        }

        return new DamageHologramConfig(excludeEliteMobs, provider, moduleDisabled, scaling, minDamage, decimalLessThan,
                Math.max(0, decimalMax), offX, offY, offZ, randEnabled, rxMin, rxMax, ryMin, ryMax, rzMin, rzMax,
                defaultColor, criticalColors, auraDisabled);
    }

    private static boolean getAuraBool(FileConfiguration aura, String path, boolean def) {
        return aura != null && aura.contains(path) ? aura.getBoolean(path) : def;
    }

    private static double getAuraDouble(FileConfiguration aura, String path, double def) {
        return aura != null && aura.contains(path) ? aura.getDouble(path) : def;
    }

    private static int getAuraInt(FileConfiguration aura, String path, int def) {
        return aura != null && aura.contains(path) ? aura.getInt(path) : def;
    }

    private static String getAuraString(FileConfiguration aura, String path, String def) {
        if (aura == null || !aura.contains(path)) return def;
        String v = aura.getString(path);
        return v == null ? def : v;
    }

    private static boolean fbBool(ConfigurationSection fb, String path, boolean def) {
        return fb != null ? fb.getBoolean(path, def) : def;
    }

    private static double fbDouble(ConfigurationSection fb, String path, double def) {
        return fb != null ? fb.getDouble(path, def) : def;
    }

    private static int fbInt(ConfigurationSection fb, String path, int def) {
        return fb != null ? fb.getInt(path, def) : def;
    }

    private static String fbString(ConfigurationSection fb, String path, String def) {
        return fb != null ? fb.getString(path, def) : def;
    }

    private static ChatColor parseChatColor(String name, ChatColor def) {
        if (name == null) return def;
        try {
            return ChatColor.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return def;
        }
    }
}
