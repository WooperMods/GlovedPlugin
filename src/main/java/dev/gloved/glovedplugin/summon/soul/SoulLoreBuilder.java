package dev.gloved.glovedplugin.summon.soul;

import dev.gloved.glovedplugin.GlovedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Shared lore blocks for souls so the vault, physical drop items, the Soul Table previews and
 * {@code /gloved soul inspect} always show the exact same numbers.
 */
public final class SoulLoreBuilder {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SoulLoreBuilder() {}

    private static Component line(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    public static Component header(String label) {
        return line("§7§l§m-----§r§7<§l" + label + "§r§7>§7§l§m-----");
    }

    public static Component separator() {
        return line("§7§l§m-----------------");
    }

    /** Traits section (empty list when the soul has no traits). */
    public static List<Component> traitsSection(SoulInstance soul) {
        List<Component> out = new ArrayList<>();
        if (soul == null) return out;
        List<SoulTrait> traits = soul.getTraits();
        if (traits.isEmpty() && soul.getTrait() != null) traits = List.of(soul.getTrait());
        if (traits.isEmpty()) return out;
        out.add(header("Traits"));
        for (SoulTrait trait : traits) {
            out.add(line("§d" + pretty(trait.name())));
        }
        return out;
    }

    /**
     * Stats section: health (with the rolling soul quality / enhanced base) plus every captured
     * base stat, the enhancement level and the rolled affixes.
     */
    public static List<Component> statsSection(GlovedPlugin plugin, SoulInstance soul) {
        return statsSection(plugin, soul, null);
    }

    public static List<Component> statsSection(GlovedPlugin plugin, SoulInstance soul, UUID viewerId) {
        List<Component> out = new ArrayList<>();
        out.add(header("Stats"));
        if (soul == null) {
            out.add(line("§7- None"));
            out.add(separator());
            return out;
        }

        SoulService service = plugin != null ? plugin.getSoulService() : null;
        SoulBaseStats base = service != null
                ? service.effectiveBaseStats(soul)
                : (soul.getBaseStats() != null ? soul.getBaseStats() : SoulBaseStats.defaults(soul.getEntityType()));

        double spawnedHp = service != null ? service.computeSpawnedMaxHealth(viewerId, soul) : base.health();
        out.add(line("§e" + fmt(spawnedHp, 0) + " HP §7(max health)"));
        out.add(line("§c" + fmt(base.attackDamage(), 2) + " §7attack damage"));
        out.add(line("§b" + fmt(base.movementSpeed(), 3) + " §7movement speed"));
        out.add(line("§9" + fmt(base.armor(), 2) + " §7armor"));
        out.add(line("§8" + fmt(base.armorToughness(), 2) + " §7armor toughness"));
        out.add(line("§7" + fmt(base.knockbackResistance(), 3) + " §7knockback resistance"));
        out.add(line("§6" + fmt(base.attackKnockback(), 2) + " §7attack knockback"));
        out.add(line("§a" + fmt(base.attackSpeed(), 2) + " §7attack speed"));

        int level = soul.getEnhanceLevel();
        if (level > 0) {
            var tableCfg = service != null ? service.tableConfig() : null;
            double perLevel = tableCfg != null ? tableCfg.getEnhancePerLevelBonus() : 0.025;
            double bonus = perLevel * 100.0 * level;
            out.add(line("§dEnhancement §fLv " + level + " §7(+" + fmt(bonus, 1) + "% stats)"));
        }

        if (soul.getAffixes().isEmpty()) {
            out.add(line("§7- No affixes"));
        } else {
            for (SoulAffixRoll affix : soul.getAffixes()) {
                out.add(line("§a" + SoulAffixType.format(affix)));
            }
        }
        out.add(separator());
        return out;
    }

    public static String fmt(double value, int decimals) {
        if (!Double.isFinite(value)) return "0";
        if (decimals <= 0) return String.valueOf(Math.round(value));
        String s = String.format(Locale.ROOT, "%." + decimals + "f", value);
        // trim trailing zeros (0.230 -> 0.23, 4.00 -> 4)
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String pretty(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }
}
