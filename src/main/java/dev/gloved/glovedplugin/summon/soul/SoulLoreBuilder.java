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

        // HP breakdown: base (soul base + owner bonus) + VIGOROUS affix (only when affix applies)
        String hpLine;
        if (service != null) {
            double[] hb = service.computeSpawnedMaxHealthBreakdown(viewerId, soul);
            double vigorous = soul != null ? soul.affixValue(SoulAffixType.VIGOROUS) : 0.0;
            if (Math.abs(hb[1]) >= 0.05 && Math.abs(vigorous) >= 0.00005) {
                StringBuilder parts = new StringBuilder();
                parts.append(fmt(hb[0], 2));
                parts.append("+").append(fmtPercent(vigorous * 100.0));
                hpLine = "§e" + fmt(hb[3], 2) + " §7HP §8(" + parts + ")";
            } else {
                hpLine = "§e" + fmt(hb[3], 2) + " §7HP";
            }
        } else {
            hpLine = "§e" + fmt(base.health(), 2) + " §7HP";
        }

        // ATK DMG: show base attack (soul base + owner strength bonus) and BRUTAL multiplier contribution
        double avgDmg = service != null ? service.estimateAverageHitDamage(viewerId, soul) : base.attackDamage();
        SoulStatSnapshot snap = service != null ? service.getSnapshot(soul) : SoulStatSnapshot.fromSoul(soul);
        double baseAtk = base.attackDamage();
        double ownerStrengthBonus = 0.0;
        try {
            var summonCfg = plugin != null ? plugin.getSummonConfig() : null;
            if (viewerId != null && summonCfg != null && summonCfg.isStrengthDamageEnabled()
                    && plugin != null && plugin.getSummonProgression() != null) {
                var st = plugin.getSummonProgression().resolveOwnerCombatStats(viewerId);
                if (st != null && st.available()) {
                    ownerStrengthBonus = Math.max(0.0, st.attackDamageTrait()) * summonCfg.getStrengthDamagePerAttackDamageTrait();
                    ownerStrengthBonus = Math.min(ownerStrengthBonus, Math.max(0.0, summonCfg.getStrengthDamageMaxBonus()));
                }
            }
        } catch (Throwable ignored) {}
        double baseWithOwner = baseAtk + ownerStrengthBonus;
        double dmgMult = Math.max(0.5, Math.min(3.0, snap.damageMultiplier()));
        String dmgLine;
        if (Math.abs(dmgMult - 1.0) >= 0.0005) {
            double brutal = soul != null ? soul.affixValue(SoulAffixType.BRUTAL) : 0.0;
            if (Math.abs(brutal) >= 0.00005) {
                dmgLine = "§c" + fmt(avgDmg, 2) + " §7ATK DMG §8(" + fmt(baseWithOwner, 2) + "+" + fmtPercent(brutal * 100.0) + ")";
            } else {
                double atkFromAffix = baseWithOwner * (dmgMult - 1.0);
                String sign = atkFromAffix >= 0 ? "+" : "";
                dmgLine = "§c" + fmt(avgDmg, 2) + " §7ATK DMG §8(" + fmt(baseWithOwner, 2) + sign + fmt(atkFromAffix, 2) + ")";
            }
        } else {
            dmgLine = "§c" + fmt(avgDmg, 2) + " §7ATK DMG";
        }

        // MV SPD: show base speed and SWIFT contribution (bps), final is spawned/clamped
        double moveBps = service != null ? service.computeSpawnedMovementBlocksPerSecond(soul) : (base.movementSpeed() * 43.17);
        double speedMult = Math.max(0.5, Math.min(1.6, snap.speedMultiplier()));
        double baseMoveBps;
        if (service != null) {
            baseMoveBps = service.computeSpawnedMovementSpeed(soul, 1.0) * 43.17;
        } else {
            baseMoveBps = base.movementSpeed() * 43.17;
        }
        String moveLine;
        if (Math.abs(speedMult - 1.0) >= 0.0005 && Math.abs(moveBps - baseMoveBps) >= 0.005) {
            double swift = soul != null ? soul.affixValue(SoulAffixType.SWIFT) : 0.0;
            if (Math.abs(swift) >= 0.00005) {
                moveLine = "§b" + fmt(moveBps, 2) + " bps §7MV SPD §8(" + fmt(baseMoveBps, 2) + "+" + fmtPercent(swift * 100.0) + ")";
            } else {
                double affixMoveBps = moveBps - baseMoveBps;
                String sign = affixMoveBps >= 0 ? "+" : "";
                moveLine = "§b" + fmt(moveBps, 2) + " bps §7MV SPD §8(" + fmt(baseMoveBps, 2) + sign + fmt(affixMoveBps, 2) + ")";
            }
        } else {
            moveLine = "§b" + fmt(moveBps, 2) + " bps §7MV SPD";
        }

        double atkSpdAps = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS,
                Math.min(SoulBaseStats.MAX_ATTACK_SPEED_APS, base.attackSpeed()));
        out.add(line(hpLine));
        out.add(line(dmgLine));
        out.add(line("§a" + fmt(atkSpdAps, 2) + " §7ATK SPD"));
        out.add(line(moveLine));

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

    /** Format a percentage value (e.g. 44.7 -> "44.7%") to match affix visuals. */
    private static String fmtPercent(double pct) {
        if (!Double.isFinite(pct)) return "0%";
        String num = Math.abs(pct - Math.rint(pct)) < 0.005
                ? String.format(Locale.ROOT, "%.0f", pct)
                : String.format(Locale.ROOT, "%.1f", pct);
        return num + "%";
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
