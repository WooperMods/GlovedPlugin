package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.summon.soul.SoulRarity;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;

/**
 * Builds LevelledMobs-style nametags for summoned minions (LM nametag disabled on minions via rules).
 *
 * <p>Format mirrors the server's {@code nametag-using-numbers} preset:
 * {@code  &fꓡꓦꓡ{rarity} &8&l༺&r {displayname} &8&l༻ {hpColor}{hp}&fꓧꓑ }
 * with health color tiered by HP ratio (LM {@code health-indicator.colored-tiers}).</p>
 */
public final class SummonNametag {
    private SummonNametag() {}

    // LM nametag-using-numbers frame (server rules.yml), without the level marker.
    private static final String PREFIX = " §f";
    private static final String SEP_L = "§8§l༺§r ";

    private static final String HP_TIER_1 = ChatColor.of("#22E76B").toString(); // >= 80%
    private static final String HP_TIER_2 = ChatColor.of("#528CFF").toString(); // >= 60%
    private static final String HP_TIER_3 = ChatColor.of("#FFCD56").toString(); // >= 40%
    private static final String HP_TIER_4 = ChatColor.of("#FE803C").toString(); // >= 20%
    private static final String HP_TIER_5 = ChatColor.of("#F2003D").toString(); // > 0%
    private static final String HP_TIER_6 = ChatColor.of("#B447FF").toString(); // <= 0%

    /** Base display name without rarity/HP (e.g. "Gloves's Zombie") using the profile's nametag color. */
    public static String baseName(SummonProfile profile, String ownerName, EntityType type, org.bukkit.entity.Player ownerPlayer) {
        if (profile == null) {
            String owner = ownerName;
            if ((owner == null || owner.isBlank()) && ownerPlayer != null) owner = ownerPlayer.getName();
            if (owner == null || owner.isBlank()) owner = "Owner";
            String mob = type != null ? SummonProfile.titleCaseMob(type.name()) : "Minion";
            String poss = owner + (owner.toLowerCase(Locale.ROOT).endsWith("s") ? "'" : "'s");
            return "§f" + poss + " " + mob;
        }
        if (ownerPlayer != null) return profile.getNametag(ownerPlayer, type);
        return profile.getNametag(ownerName != null ? ownerName : "Owner", type);
    }

    /** Full LM-style nametag with optional rarity badge (no health indicator). */
    public static String format(SummonProfile profile, org.bukkit.entity.Player ownerPlayer, String ownerName,
                                LivingEntity minion, SoulRarity rarity) {
        if (minion == null) return "";
        EntityType type = minion.getType();
        String base = baseName(profile, ownerName, type, ownerPlayer);

        StringBuilder sb = new StringBuilder(64);
        sb.append(PREFIX);
        if (rarity != null) {
            sb.append(rarityBadge(rarity)).append(' ');
        }
        sb.append(SEP_L).append(base);
        return sb.toString().trim();
    }

    /** Compact colored rarity badge (replaces LM tiered level). */
    public static String rarityBadge(SoulRarity rarity) {
        if (rarity == null) return "";
        return rarity.coloredNameLegacy();
    }

    public static String roundedHealth(LivingEntity entity) {
        double hp = 0.0;
        try { hp = Math.max(0.0, entity.getHealth()); } catch (Throwable ignored) {}
        if (Double.isNaN(hp) || Double.isInfinite(hp)) hp = 0.0;
        // Match LM's rounded display feel: integer-like for whole numbers, one decimal otherwise.
        if (Math.abs(hp - Math.rint(hp)) < 0.05) {
            return String.valueOf((long) Math.rint(hp));
        }
        return String.format(Locale.US, "%.1f", hp);
    }

    public static String healthColor(LivingEntity entity) {
        double hp = 0.0;
        double max = 1.0;
        try { hp = Math.max(0.0, entity.getHealth()); } catch (Throwable ignored) {}
        try {
            var attr = entity.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) max = Math.max(1.0, attr.getValue());
            else max = Math.max(1.0, hp);
        } catch (Throwable ignored) {
            max = Math.max(1.0, hp);
        }
        double ratio = max <= 0.0 ? 0.0 : hp / max;
        if (ratio >= 0.80) return HP_TIER_1;
        if (ratio >= 0.60) return HP_TIER_2;
        if (ratio >= 0.40) return HP_TIER_3;
        if (ratio >= 0.20) return HP_TIER_4;
        if (ratio > 0.0) return HP_TIER_5;
        return HP_TIER_6;
    }
}
