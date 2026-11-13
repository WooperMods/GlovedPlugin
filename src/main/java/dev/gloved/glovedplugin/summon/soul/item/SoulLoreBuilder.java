package dev.gloved.glovedplugin.summon.soul.item;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixRoll;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixType;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;

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
        return statsSection(plugin, soul, null, false);
    }

    public static List<Component> statsSection(GlovedPlugin plugin, SoulInstance soul, UUID viewerId) {
        return statsSection(plugin, soul, viewerId, false);
    }

    /**
     * Unified soul stats/lore section used by vault, dropped soul items, Soul Table, previews, etc.
     * Add new soul stats here so all views stay consistent.
     *
     * @param viewerId        player context for owner bonuses (may be null)
     * @param showAffixQuality when true, append per-affix roll quality (e.g. Soul Table)
     */
    public static List<Component> statsSection(GlovedPlugin plugin, SoulInstance soul, UUID viewerId, boolean showAffixQuality) {
        List<Component> out = new ArrayList<>();
        out.add(header("Stats"));
        if (soul == null) {
            out.add(line("§7- None"));
            out.add(separator());
            return out;
        }

        SoulService service = plugin != null ? plugin.getSoulService() : null;
        SoulBaseStats enhancedBase = service != null
                ? service.effectiveBaseStats(soul)
                : (soul.getBaseStats() != null ? soul.getBaseStats() : SoulBaseStats.defaults(soul.getEntityType()));
        SoulBaseStats preEnhBase = service != null
                ? service.baseStatsOf(soul)
                : (soul.getBaseStats() != null ? soul.getBaseStats() : SoulBaseStats.defaults(soul.getEntityType()));
        int enhLevel = Math.max(0, soul.getEnhanceLevel());
        boolean showEnh = enhLevel > 0;

        SoulStatSnapshot snap = service != null ? service.getSnapshot(soul) : SoulStatSnapshot.fromSoul(soul);
        int groupSize = Math.max(1, service != null ? service.effectiveGroupCapacity(soul) : soul.getGroupCapacity());

        // HP breakdown: full soul stat (pre group-division). Runtime per-minion HP is divided on spawn.
        String hpLine;
        double totalHp = service != null ? service.computeSpawnedMaxHealth(viewerId, soul) : enhancedBase.health();
        {
            double vigorous = soul != null ? soul.affixValue(SoulAffixType.VIGOROUS) : 0.0;
            double preEnhHp = preEnhBase.health();
            double enhHpDelta = enhancedBase.health() - preEnhHp;
            double ownerHealthBonus = 0.0;
            if (service != null) {
                double[] hb = service.computeSpawnedMaxHealthBreakdown(viewerId, soul);
                ownerHealthBonus = Math.max(0.0, hb[2]);
                totalHp = hb[3];
            }
            boolean showHpBreakdown = showEnh || Math.abs(ownerHealthBonus) >= 0.005 || Math.abs(vigorous) >= 0.00005;
            if (showHpBreakdown) {
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(fmt(preEnhHp + ownerHealthBonus, 2));
                if (showEnh && Math.abs(enhHpDelta) >= 0.005) parts.append("§8+§d").append(fmt(enhHpDelta, 2));
                if (Math.abs(vigorous) >= 0.00005) parts.append("§8+§a").append(fmtPercent(vigorous * 100.0));
                hpLine = "§e" + fmt(totalHp, 2) + " §7HP §8(" + parts + "§8)";
            } else {
                hpLine = "§e" + fmt(totalHp, 2) + " §7HP";
            }
        }

        // DEF breakdown computed below in display order.

        // ATK DMG: full soul stat (pre group-division). Runtime per-minion damage is divided on spawn/hit.
        double avgDmg = service != null ? service.estimateAverageHitDamage(viewerId, soul) : enhancedBase.attackDamage();
        double baseAtk = enhancedBase.attackDamage();
        double preEnhAtk = preEnhBase.attackDamage();
        double enhAtkDelta = baseAtk - preEnhAtk;
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
        {
            boolean hasDmgMod = Math.abs(dmgMult - 1.0) >= 0.0005 || Math.abs(ownerStrengthBonus) >= 0.005 || (showEnh && Math.abs(enhAtkDelta) >= 0.005);
            if (hasDmgMod) {
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(fmt(preEnhAtk + ownerStrengthBonus, 2));
                if (showEnh && Math.abs(enhAtkDelta) >= 0.005) parts.append("§8+§d").append(fmt(enhAtkDelta, 2));
                if (Math.abs(dmgMult - 1.0) >= 0.0005) {
                    double brutal = soul != null ? soul.affixValue(SoulAffixType.BRUTAL) : 0.0;
                    if (Math.abs(brutal) >= 0.00005) {
                        parts.append("§8+§a").append(fmtPercent(brutal * 100.0));
                    } else {
                        double atkFromAffix = baseWithOwner * (dmgMult - 1.0);
                        String sign = atkFromAffix >= 0 ? "+" : "";
                        String deltaColor = atkFromAffix >= 0 ? "§a" : "§c";
                        parts.append("§8").append(sign).append(deltaColor).append(fmt(Math.abs(atkFromAffix), 2));
                    }
                }
                dmgLine = "§c" + fmt(avgDmg, 2) + " §7ATK DMG §8(" + parts + "§8)";
            } else {
                dmgLine = "§c" + fmt(avgDmg, 2) + " §7ATK DMG";
            }
        }

        // ATK SPD: pre-enh + enhancement delta
        double atkSpdAps = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS, enhancedBase.attackSpeed());
        double preAtkSpd = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS, preEnhBase.attackSpeed());
        double enhAtkSpdDelta = atkSpdAps - preAtkSpd;
        String atkSpdLine;
        if (showEnh && Math.abs(enhAtkSpdDelta) >= 0.005) {
            atkSpdLine = "§a" + fmt(atkSpdAps, 2) + " §7ATK SPD §8(§7" + fmt(preAtkSpd, 2) + "§8+§d" + fmt(enhAtkSpdDelta, 2) + "§8)";
        } else {
            atkSpdLine = "§a" + fmt(atkSpdAps, 2) + " §7ATK SPD";
        }

        // MV SPD: pre-enh + enhancement delta + affix contribution (bps)
        double moveBps = service != null ? service.computeSpawnedMovementBlocksPerSecond(soul) : (enhancedBase.movementSpeed() * 43.17);
        double speedMult = Math.max(0.5, Math.min(1.6, snap.speedMultiplier()));
        double preEnhMoveBps = Math.max(0.0, preEnhBase.movementSpeed() * 43.17);
        double enhMoveBps = Math.max(0.0, enhancedBase.movementSpeed() * 43.17);
        double enhMoveDeltaBps = enhMoveBps - preEnhMoveBps;
        String moveLine;
        {
            boolean hasSwift = Math.abs(speedMult - 1.0) >= 0.0005 && Math.abs(moveBps - enhMoveBps) >= 0.005;
            boolean showMvBreakdown = (showEnh && Math.abs(enhMoveDeltaBps) >= 0.005) || hasSwift;
            if (showMvBreakdown) {
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(fmt(preEnhMoveBps, 2));
                if (showEnh && Math.abs(enhMoveDeltaBps) >= 0.005) parts.append("§8+§d").append(fmt(enhMoveDeltaBps, 2));
                if (hasSwift) {
                    double swift = soul != null ? soul.affixValue(SoulAffixType.SWIFT) : 0.0;
                    if (Math.abs(swift) >= 0.00005) {
                        parts.append("§8+§a").append(fmtPercent(swift * 100.0));
                    } else {
                        double affixMoveBps = moveBps - enhMoveBps;
                        String sign = affixMoveBps >= 0 ? "+" : "";
                        String deltaColor = affixMoveBps >= 0 ? "§a" : "§c";
                        parts.append("§8").append(sign).append(deltaColor).append(fmt(Math.abs(affixMoveBps), 2));
                    }
                }
                moveLine = "§b" + fmt(moveBps, 2) + " bps §7MV SPD §8(" + parts + "§8)";
            } else {
                moveLine = "§b" + fmt(moveBps, 2) + " bps §7MV SPD";
            }
        }

        out.add(line(hpLine));
        // DEF line (with enhancement breakdown when applicable)
        {
            double preDef = SoulBaseStats.clampDefense(preEnhBase.defense());
            double effDef = Math.max(SoulBaseStats.MIN_DEFENSE, enhancedBase.defense());
            double defDelta = effDef - preDef;
            if (showEnh && Math.abs(defDelta) >= 0.005) {
                out.add(line("§9" + fmt(effDef, 2) + " §7DEF §8(§7" + fmt(preDef, 2) + "§8+§d" + fmt(defDelta, 2) + "§8)"));
            } else {
                out.add(line("§9" + fmt(effDef, 2) + " §7DEF"));
            }
        }
        out.add(line(dmgLine));
        out.add(line(atkSpdLine));
        out.add(line(moveLine));

        // Summons (authoritative soul stat): base + enhancement milestones + Summons affix %, capped.
        int effectiveCap = groupSize;
        String capLine = "§7Summons §3" + effectiveCap;
        if (service != null) {
            int[] cb = service.computeGroupCapacityBreakdown(soul); // base, enhanceBonus, affixBonusCount, effective
            double capPct = soul != null ? soul.affixValue(SoulAffixType.CAPACITY) : 0.0;
            boolean hasEnh = cb[1] > 0;
            boolean hasAffix = Math.abs(capPct) >= 0.00005;
            boolean differs = cb[3] != cb[0] || hasEnh || hasAffix;
            if (differs) {
                int beforeAffix = Math.max(1, cb[0] + cb[1]);
                int expected = (int) Math.floor(beforeAffix * (1.0 + Math.max(0.0, capPct)) + 1e-9);
                if (capPct > 0.0 && expected == beforeAffix) expected = beforeAffix + 1;
                boolean capped = cb[3] < expected;
                String effColor = capped ? "§c" : "§3";
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(cb[0]);
                if (hasEnh) parts.append("§8+§d").append(cb[1]);
                if (hasAffix) parts.append("§8+§a").append(fmtPercent(capPct * 100.0));
                capLine = "§7Summons " + effColor + cb[3] + " §8(" + parts + "§8)";
            }
        }
        out.add(line(capLine));

        int level = soul.getEnhanceLevel();
        if (level > 0) {
            var tableCfg = service != null ? service.tableConfig() : null;
            double perLevel = tableCfg != null ? tableCfg.getEnhancePerLevelBonus() : 0.025;
            double bonus = perLevel * 100.0 * level;
            out.add(line("§dEnhancement §fLv " + level + " §8(§a+" + fmt(bonus, 1) + "%§8)"));
        }

        if (soul.getAffixes() == null || soul.getAffixes().isEmpty()) {
            out.add(line("§8-"));
        } else {
            for (SoulAffixRoll affix : soul.getAffixes()) {
                if (affix == null) continue;
                String affixText = showAffixQuality
                        ? SoulAffixType.format(soul.getRarity(), affix)
                        : SoulAffixType.format(affix);
                out.add(line("§a" + affixText));
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
