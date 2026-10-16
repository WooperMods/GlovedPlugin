package dev.gloved.glovedplugin.combat;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@code combat.yml}. Supports Better Combat-style parent presets via
 * {@code presets.<id>} plus per-material overrides, and a flat
 * {@code weapons.<MATERIAL>} section for simple servers.
 */
public final class WeaponRegistry {

    private final Map<Material, WeaponAttributes> byMaterial = new EnumMap<>(Material.class);
    private final Map<String, WeaponAttributes> presets = new java.util.HashMap<>();
    private WeaponAttributes fallback;

    private boolean enabled = true;
    private boolean holdToSpam = true;
    private boolean trailFx = true;
    private boolean offAngleAssist = true;
    private long proactiveMinInterval = 6L;
    private boolean dualWield = true;
    private double dualWieldSpeedBonus = 0.2;
    private double sweepFalloff = 0.5;
    private boolean suppressMiningWithWeapons = true;
    private boolean sneakBypass = true;
    private boolean adventureModeWhileArmed = true;
    private long comboResetTicks = 40L;
    private double minAttackStrength = 0.9;
    private double maceFallingRangeBonus = 3.0;

    public void load(GlovedPlugin plugin) {
        byMaterial.clear();
        presets.clear();
        plugin.saveResource("combat.yml", false);
        File file = new File(plugin.getDataFolder(), "combat.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        enabled = cfg.getBoolean("enabled", true);
        holdToSpam = cfg.getBoolean("hold_to_spam", true);
        trailFx = cfg.getBoolean("trail_fx", true);
        offAngleAssist = cfg.getBoolean("off_angle_assist", true);
        proactiveMinInterval = Math.max(1L, cfg.getLong("proactive_min_interval_ticks", 6L));
        dualWield = cfg.getBoolean("dual_wield", true);
        dualWieldSpeedBonus = cfg.getDouble("dual_wield_speed_bonus", 0.2);
        sweepFalloff = cfg.getDouble("sweep_falloff_multiplier", 0.5);
        suppressMiningWithWeapons = cfg.getBoolean("suppress_mining_with_weapons", true);
        sneakBypass = cfg.getBoolean("sneak_bypass", true);
        adventureModeWhileArmed = cfg.getBoolean("adventure_mode_while_armed", true);
        comboResetTicks = cfg.getLong("combo_reset_ticks", 40L);
        minAttackStrength = Math.clamp(cfg.getDouble("min_attack_strength", 0.9), 0.0, 1.0);
        maceFallingRangeBonus = Math.max(0.0, cfg.getDouble("mace_falling_range_bonus", 3.0));

        fallback = readAttributes(cfg.getConfigurationSection("fallback"),
                new WeaponAttributes(0.0, 90.0,
                        WeaponAttributes.HitboxShape.HORIZONTAL_PLANE, false,
                        List.of(AttackStage.defaults())));

        ConfigurationSection presetsSec = cfg.getConfigurationSection("presets");
        if (presetsSec != null) {
            for (String key : presetsSec.getKeys(false)) {
                ConfigurationSection sec = presetsSec.getConfigurationSection(key);
                if (sec == null) continue;
                String parent = sec.getString("parent", null);
                WeaponAttributes base = parent != null
                        ? presets.getOrDefault(parent.toLowerCase(Locale.ROOT), fallback)
                        : fallback;
                presets.put(key.toLowerCase(Locale.ROOT), readAttributes(sec, base));
            }
        }

        ConfigurationSection weapons = cfg.getConfigurationSection("weapons");
        if (weapons != null) {
            for (String key : weapons.getKeys(false)) {
                Material mat;
                try {
                    mat = Material.valueOf(key.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    GlovedPlugin.logWarning("combat.yml: unknown material '" + key + "', skipping");
                    continue;
                }
                if (weapons.isString(key)) {
                    String presetId = weapons.getString(key, "").toLowerCase(Locale.ROOT);
                    byMaterial.put(mat, presets.getOrDefault(presetId, fallback));
                    continue;
                }
                ConfigurationSection sec = weapons.getConfigurationSection(key);
                if (sec == null) continue;
                String presetId = sec.getString("preset", null);
                WeaponAttributes base = presetId != null
                        ? presets.getOrDefault(presetId.toLowerCase(Locale.ROOT), fallback)
                        : fallback;
                byMaterial.put(mat, readAttributes(sec, base));
            }
        }

        seedVanillaDefaults();
        GlovedPlugin.logInfo("Combat loaded: " + byMaterial.size() + " weapons, "
                + presets.size() + " presets (enabled=" + enabled + ")");
    }

    private WeaponAttributes readAttributes(ConfigurationSection sec, WeaponAttributes base) {
        if (sec == null) return base;
        double rangeBonus = sec.contains("range_bonus")
                ? sec.getDouble("range_bonus")
                : sec.getDouble("attack_range", base.rangeBonus());
        double angle = sec.getDouble("angle", base.baseAngleDegrees());
        WeaponAttributes.HitboxShape shape = WeaponAttributes.HitboxShape.parse(
                sec.getString("hitbox", null), base.hitbox());
        boolean twoHanded = sec.getBoolean("two_handed", base.twoHanded());

        List<AttackStage> stages = base.attacks();
        List<?> rawAttacks = sec.getList("attacks", null);
        if (rawAttacks != null) {
            stages = new ArrayList<>();
            for (Object o : rawAttacks) {
                if (!(o instanceof Map<?, ?> map)) continue;
                double dmg = toDouble(map.get("damage_multiplier"), 1.0);
                double rangeMul = toDouble(map.get("range_multiplier"), 1.0);
                double a = toDouble(map.get("angle"), angle);
                int upswing = (int) toDouble(map.get("upswing_ticks"), 0);
                String sound = map.get("swing_sound") != null
                        ? String.valueOf(map.get("swing_sound")) : "ENTITY_PLAYER_ATTACK_SWEEP";
                String particles = map.get("particles") != null
                        ? String.valueOf(map.get("particles")) : "SWEEP_ATTACK";
                stages.add(new AttackStage(dmg, rangeMul, a, upswing, sound, particles));
            }
            if (stages.isEmpty()) stages = base.attacks();
        }
        return new WeaponAttributes(rangeBonus, angle, shape, twoHanded, List.copyOf(stages));
    }

    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Minimal vanilla-feel coverage so unconfigured swords/axes still arc. */
    private void seedVanillaDefaults() {
        WeaponAttributes sword = new WeaponAttributes(0.5, 100.0,
                WeaponAttributes.HitboxShape.HORIZONTAL_PLANE, false,
                List.of(new AttackStage(1.0, 1.0, 100.0, 0,
                                "ENTITY_PLAYER_ATTACK_SWEEP", "SWEEP_ATTACK"),
                        new AttackStage(1.1, 0.95, 80.0, 0,
                                "ENTITY_PLAYER_ATTACK_SWEEP", "SWEEP_ATTACK")));
        WeaponAttributes axe = new WeaponAttributes(0.25, 70.0,
                WeaponAttributes.HitboxShape.VERTICAL_PLANE, false,
                List.of(AttackStage.defaults()));
        WeaponAttributes spear = new WeaponAttributes(1.5, 35.0,
                WeaponAttributes.HitboxShape.FORWARD_BOX, true,
                List.of(AttackStage.defaults()));
        WeaponAttributes dagger = new WeaponAttributes(-0.5, 70.0,
                WeaponAttributes.HitboxShape.FORWARD_BOX, false,
                List.of(new AttackStage(0.8, 1.0, 70.0, 0,
                                "ENTITY_PLAYER_ATTACK_SWEEP", "CRIT"),
                        new AttackStage(0.8, 1.0, 70.0, 0,
                                "ENTITY_PLAYER_ATTACK_SWEEP", "CRIT"),
                        new AttackStage(1.2, 1.0, 70.0, 0,
                                "ENTITY_PLAYER_ATTACK_SWEEP", "CRIT")));
        for (Material m : Material.values()) {
            if (byMaterial.containsKey(m)) continue;
            String n = m.name();
            if (n.endsWith("_SWORD")) byMaterial.put(m, sword);
            else if (n.endsWith("_AXE")) byMaterial.put(m, axe);
            else if (n.equals("TRIDENT") || n.equals("MACE")) byMaterial.put(m, spear);
            else if (n.endsWith("_HOE") || n.equals("STICK") || n.equals("BLAZE_ROD")) byMaterial.put(m, dagger);
        }
    }

    public WeaponAttributes forMaterial(Material mat) {
        return byMaterial.getOrDefault(mat, fallback != null ? fallback : new WeaponAttributes(
                0, 90, WeaponAttributes.HitboxShape.HORIZONTAL_PLANE, false,
                List.of(AttackStage.defaults())));
    }

    public boolean isEnabled() { return enabled; }
    public boolean isHoldToSpam() { return holdToSpam; }
    public boolean isTrailFx() { return trailFx; }
    public boolean isOffAngleAssist() { return offAngleAssist; }
    public long getProactiveMinInterval() { return proactiveMinInterval; }
    public boolean isDualWield() { return dualWield; }
    public double getDualWieldSpeedBonus() { return dualWieldSpeedBonus; }
    public double getSweepFalloff() { return sweepFalloff; }
    public boolean isSuppressMiningWithWeapons() { return suppressMiningWithWeapons; }
    public boolean isSneakBypass() { return sneakBypass; }
    public boolean isAdventureModeWhileArmed() { return adventureModeWhileArmed; }
    public long getComboResetTicks() { return comboResetTicks; }
    public double getMinAttackStrength() { return minAttackStrength; }
    public double getMaceFallingRangeBonus() { return maceFallingRangeBonus; }
}
