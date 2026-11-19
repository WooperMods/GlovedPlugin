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
            if (trait == null) continue;
            out.add(line("§d" + trait.displayName()));
            List<String> desc = trait.guideDescription();
            if (desc == null || desc.isEmpty()) continue;
            for (String dl : desc) {
                if (dl == null) continue;
                String t = dl.trim();
                if (t.isEmpty()) continue;
                // Support inline separators in descriptions and render each as a lore line.
                String normalized = t.replace("\r\n", "\n").replace('\r', '\n');
                for (String part : normalized.split("[;\n]", -1)) {
                    if (part == null) continue;
                    String lineText = part.trim();
                    if (lineText.isEmpty()) continue;
                    out.add(line(lineText.startsWith("§") ? "§7 " + lineText : "§7 " + lineText));
                }
            }
        }
        out.add(separator());
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
        if (soul != null) {
            out.addAll(masterySection(plugin, soul));
        }
        out.add(header("Stats"));
        if (soul == null) {
            out.add(line("§7- None"));
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

        // HP breakdown: full effective per-summon stat (team/loadout: no group division).
        String hpLine;
        double totalHp = service != null ? service.computeSpawnedMaxHealth(viewerId, soul) : enhancedBase.health();
        {
            double preEnhHp = preEnhBase.health();
            double enhHpDelta = enhancedBase.health() - preEnhHp;
            double ownerHealthBonus = 0.0;
            double affixHealthBonus = 0.0;
            if (service != null) {
                double[] hb = service.computeSpawnedMaxHealthBreakdown(viewerId, soul);
                ownerHealthBonus = Math.max(0.0, hb[2]);
                affixHealthBonus = hb[1];
                totalHp = hb[3];
            }
            boolean showHpBreakdown = showEnh || Math.abs(ownerHealthBonus) >= 0.005 || Math.abs(affixHealthBonus) >= 0.005;
            if (showHpBreakdown) {
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(fmt(preEnhHp + ownerHealthBonus, 2));
                if (showEnh && Math.abs(enhHpDelta) >= 0.005) appendSignedDelta(parts, enhHpDelta, "§d");
                if (Math.abs(affixHealthBonus) >= 0.005) appendSignedDelta(parts, affixHealthBonus, affixHealthBonus >= 0 ? "§a" : "§c");
                hpLine = statLine("§e", totalHp, "§7HP", parts.toString());
            } else {
                hpLine = statLine("§e", totalHp, "§7HP", null);
            }
        }

        // DEF breakdown computed below in display order.

        // ATK DMG: full effective per-summon stat (team/loadout: no group division).
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
            boolean showEnhPart = showEnh && Math.abs(enhAtkDelta) >= 0.005;
            boolean showMultPart = Math.abs(dmgMult - 1.0) >= 0.0005;
            boolean hasDmgMod = showEnhPart || showMultPart;
            if (hasDmgMod) {
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(fmt(preEnhAtk + ownerStrengthBonus, 2));
                if (showEnhPart) appendSignedDelta(parts, enhAtkDelta, "§d");
                if (showMultPart) {
                    double atkFromAffix = baseWithOwner * (dmgMult - 1.0);
                    appendSignedDelta(parts, atkFromAffix, atkFromAffix >= 0 ? "§a" : "§c");
                }
                dmgLine = statLine("§c", avgDmg, "§7ATK DMG", parts.toString());
            } else {
                dmgLine = statLine("§c", avgDmg, "§7ATK DMG", null);
            }
        }

        // ATK SPD: pre-enh + enhancement delta
        double atkSpdAps = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS, enhancedBase.attackSpeed());
        double preAtkSpd = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS, preEnhBase.attackSpeed());
        double enhAtkSpdDelta = atkSpdAps - preAtkSpd;
        String atkSpdLine;
        if (showEnh && Math.abs(enhAtkSpdDelta) >= 0.005) {
            StringBuilder parts = new StringBuilder();
            parts.append("§7").append(fmt(preAtkSpd, 2));
            appendSignedDelta(parts, enhAtkSpdDelta, "§d");
            atkSpdLine = statLine("§a", atkSpdAps, "§7ATK SPD", parts.toString());
        } else {
            atkSpdLine = statLine("§a", atkSpdAps, "§7ATK SPD", null);
        }

        // MV SPD: tier-based (Normal/Fast/Very Fast/Insane)
        int moveTier = enhancedBase.movementSpeedTier();
        String moveLine = "§b" + SoulBaseStats.movementSpeedTierLabel(moveTier) + " §7MV SPD";

        out.add(line(hpLine));
        // DEF line (with enhancement and TENACIOUS affix breakdown when applicable)
        {
            double preDef = SoulBaseStats.clampDefense(preEnhBase.defense());
            double baseDef = Math.max(SoulBaseStats.MIN_DEFENSE, enhancedBase.defense());
            double defMult = 1.0 + Math.max(0.0, snap.tenacityBonus());
            double effDef = Math.max(SoulBaseStats.MIN_DEFENSE, baseDef * defMult);
            double enhDefDelta = baseDef - preDef;
            double affixDefDelta = effDef - baseDef;
            boolean showDefEnh = showEnh && Math.abs(enhDefDelta) >= 0.005;
            boolean showDefAffix = Math.abs(affixDefDelta) >= 0.005;
            if (showDefEnh || showDefAffix) {
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(fmt(preDef, 2));
                if (showDefEnh) appendSignedDelta(parts, enhDefDelta, "§d");
                if (showDefAffix) appendSignedDelta(parts, affixDefDelta, affixDefDelta >= 0 ? "§a" : "§c");
                out.add(line(statLine("§9", effDef, "§7DEF", parts.toString())));
            } else {
                out.add(line(statLine("§9", effDef, "§7DEF", null)));
            }
        }
        out.add(line(dmgLine));
        out.add(line(atkSpdLine));
        out.add(line(moveLine));
        // Respawn timer (effective, with enhancement/affix reductions)
        {
            double baseRespawn = preEnhBase.respawnSeconds(); // rolled base (pre-enhancement)
            double afterEnhRespawn = enhancedBase.respawnSeconds(); // after enhancement reduction, before affix/runtime clamping
            double effRespawn = afterEnhRespawn;
            double affixReduction = Math.max(0.0, Math.min(0.95, snap.respawnReduction()));
            try {
                if (plugin != null && plugin.getSummonRespawnService() != null) {
                    effRespawn = plugin.getSummonRespawnService().computeEffectiveRespawnSeconds(soul, service != null ? service.soulConfig() : null);
                } else {
                    effRespawn = Math.max(SoulBaseStats.MIN_RESPAWN_SECONDS, afterEnhRespawn * (1.0 - affixReduction));
                }
            } catch (Throwable ignored) {}
            if (Math.abs(effRespawn - baseRespawn) >= 0.05) {
                StringBuilder parts = new StringBuilder();
                parts.append("§7").append(fmt(baseRespawn, 1));
                double enhDelta = baseRespawn - afterEnhRespawn; // positive = time reduced
                if (showEnh && enhDelta >= 0.05) {
                    parts.append("§8-§d").append(fmt(enhDelta, 1));
                }
                double affixDelta = afterEnhRespawn - effRespawn;
                // If enhancement wasn't shown separately but effect exists (edge cases), fold into affix/total delta context by showing affix only when meaningful.
                if (!showEnh && enhDelta >= 0.05) {
                    affixDelta = baseRespawn - effRespawn; // show combined reduction after base
                    if (affixDelta >= 0.05) parts.append("§8-§a").append(fmt(affixDelta, 1));
                } else if (affixDelta >= 0.05) {
                    parts.append("§8-§a").append(fmt(affixDelta, 1));
                }
                // If floors/clamps made effective higher than components suggest, still show final in main value; calc shows component reductions.
                out.add(line("§d" + fmt(effRespawn, 1) + "s §7RESPAWN §8(" + parts + "§8)"));
            } else {
                out.add(line("§d" + fmt(effRespawn, 1) + "s §7RESPAWN"));
            }
        }

        boolean hasAffixLines = false;
        if (soul.getAffixes() == null || soul.getAffixes().isEmpty()) {
            out.add(line("§8-"));
            hasAffixLines = true;
        } else {
            for (SoulAffixRoll affix : soul.getAffixes()) {
                if (affix == null) continue;
                // Team/loadout overhaul: Summons count (CAPACITY) retired from gameplay/UI.
                if (affix.type() == SoulAffixType.CAPACITY) continue;
                String affixText = showAffixQuality
                        ? SoulAffixType.format(soul.getRarity(), affix)
                        : SoulAffixType.format(affix);
                out.add(line("§a" + affixText));
                hasAffixLines = true;
            }
            if (!hasAffixLines) {
                out.add(line("§8-"));
                hasAffixLines = true;
            }
        }

        int level = soul.getEnhanceLevel();
        if (level > 0) {
            var tableCfg = service != null ? service.tableConfig() : null;
            double perLevel = tableCfg != null ? tableCfg.getEnhancePerLevelBonus() : 0.025;
            double bonus = perLevel * 100.0 * level;
            out.add(line("§dEnhancement §fLv " + level + " §8(§a+" + fmt(bonus, 1) + "%§8)"));
        }

        return out;
    }

    /** Mastery section shown near the top of soul lore (below title, above stats). */
    public static List<Component> masterySection(GlovedPlugin plugin, SoulInstance soul) {
        List<Component> out = new ArrayList<>();
        if (plugin == null || soul == null) return out;
        try {
            var sc = plugin.getSummonConfig();
            var mcfg = sc != null ? sc.getSoulMasteryConfig() : null;
            if (mcfg == null || !mcfg.isEnabled()) return out;
            SoulService service = plugin.getSoulService();
            int ml = Math.max(0, soul.getMasteryLevel());
            int mx = Math.max(0, soul.getMasteryXp());
            int maxL = mcfg.getMaxLevel();
            String rank = mcfg.rankName(ml);
            out.add(line("§bMastery §fLv " + ml + " §7(" + rank + ")"));
            if (ml >= maxL) {
                out.add(line(masteryXpBar(1.0, "§aMAX", "")));
            } else {
                int need = Math.max(1, mcfg.xpToNextLevel(ml));
                double progress = Math.max(0.0, Math.min(1.0, mx / (double) need));
                out.add(line(masteryXpBar(progress, "§f" + mx + "§7/§f" + need + " XP", "")));
            }
            // show claimable milestone count (if any)
            if (service != null && service.getMasteryService() != null) {
                int claimable = service.getMasteryService().claimableMilestones(soul).size();
                if (claimable > 0) out.add(line("§e★ " + claimable + " mastery milestone" + (claimable == 1 ? "" : "s") + " claimable"));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Append a signed numeric delta to a stat breakdown (e.g. "§8+§d1.23" / "§8-§c1.23"). */
    private static void appendSignedDelta(StringBuilder parts, double delta, String deltaColor) {
        String sign = delta >= 0.0 ? "+" : "-";
        parts.append("§8").append(sign).append(deltaColor).append(fmt(Math.abs(delta), 2));
    }

    /** Build a stat line, with optional "(base ± deltas)" breakdown. */
    private static String statLine(String valueColor, double value, String label, String breakdown) {
        if (breakdown == null || breakdown.isBlank()) {
            return valueColor + fmt(value, 2) + " " + label;
        }
        return valueColor + fmt(value, 2) + " " + label + " §8(" + breakdown + "§8)";
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

    private static String masteryXpBar(double progress, String suffix, String prefix) {
        double p = Math.max(0.0, Math.min(1.0, progress));
        int segments = 20;
        int filled = (int) Math.round(p * segments);
        if (p > 0.0 && filled == 0) filled = 1;
        if (p >= 1.0) filled = segments;
        StringBuilder bar = new StringBuilder("§8[§a");
        for (int i = 0; i < segments; i++) {
            if (i == filled) bar.append("§8");
            bar.append("|");
        }
        bar.append("§8]");
        if (suffix != null && !suffix.isBlank()) bar.append(" ").append(suffix);
        return bar.toString();
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
