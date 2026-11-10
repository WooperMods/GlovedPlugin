package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.summon.soul.EntityCategory;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runtime summon template. In the fully-dynamic soul system this is resolved from an EntityType
 * + category/global defaults (no static profile catalog required). Kept as {@code SummonProfile}
 * for compatibility with existing spawn/AI/tracker/nametag consumers.
 */
public class SummonProfile {
    public static final boolean INVULNERABLE = false;
    public static final int COOLDOWN_TICKS = 1;
    public static final int LIFETIME_TICKS = 1200;
    /** Unified radius: max distance minions may travel from owner / target scan range. */
    public static final double FOLLOW_RANGE = 16.0;
    public static final String NAMETAG_FORMAT = "{player}'s {mob}";
    public static final boolean NAMETAG_VISIBLE = true;

    /** Family key used for caps/counting (typically entity type name). */
    public final String name;
    /** Single entity type id for this template. */
    public final String mobType;
    public final List<String> mobTypes;
    /** Legacy trigger chance (not used for cast-based dynamic summoning). */
    public final double chance;
    /** How many minions spawned per cast/trigger batch. */
    public final int minionsPerHit;
    public final int maxActive;
    public final ChatColor nametagColor;
    /** Legacy enchant/display name (unused in dynamic system). */
    public final String enchantName;
    public final EntityCategory category;

    public SummonProfile(String name, String mobType, double chance, int minionsPerHit, int maxActive,
                         String nametagColor, String enchantName) {
        this(name, mobType, chance, minionsPerHit, maxActive, nametagColor, enchantName, null);
    }

    public SummonProfile(String name, String mobType, double chance, int minionsPerHit, int maxActive,
                         String nametagColor, String enchantName, EntityCategory category) {
        this.name = name != null ? name : (mobType != null ? mobType : "UNKNOWN");
        String mt = (mobType == null || mobType.isBlank()) ? this.name : mobType.trim().toUpperCase(Locale.ROOT);
        this.mobType = mt;
        this.mobTypes = Collections.singletonList(this.mobType);
        this.chance = chance;
        this.minionsPerHit = minionsPerHit > 0 ? minionsPerHit : 1;
        this.maxActive = maxActive > 0 ? maxActive : 10;
        EntityType et = resolveEntityTypeInternal(this.mobType);
        this.category = category != null ? category : EntityCategory.of(et);
        ChatColor parsedColor = parseChatColor(nametagColor);
        this.nametagColor = parsedColor != null ? parsedColor : defaultColor(this.category);
        this.enchantName = enchantName == null ? "" : enchantName.trim();
    }

    /** Dynamic factory from entity type and defaults. */
    public static SummonProfile dynamic(EntityType type, int batchSize, int perTypeMaxActive) {
        if (type == null) return null;
        EntityCategory cat = EntityCategory.of(type);
        return new SummonProfile(type.name(), type.name(), 1.0, Math.max(1, batchSize), Math.max(1, perTypeMaxActive),
                null, "", cat);
    }

    /** @deprecated kept for binary/source compatibility; only first type used. */
    @Deprecated(forRemoval = false)
    public SummonProfile(String name, List<String> mobTypes, double chance, int minionsPerHit, int maxActive,
                         String nametagColor, String enchantName) {
        this(name, (mobTypes == null || mobTypes.isEmpty()) ? "BEE" : mobTypes.get(0),
                chance, minionsPerHit, maxActive, nametagColor, enchantName);
    }

    private static ChatColor parseChatColor(String color) {
        if (color == null) return null;
        try { return ChatColor.valueOf(color.toUpperCase(Locale.ROOT)); } catch (Exception e) {
            if (color.length() == 1) return ChatColor.getByChar(color.charAt(0));
        }
        return null;
    }

    public static ChatColor defaultColor(EntityCategory cat) {
        if (cat == null) return ChatColor.WHITE;
        return switch (cat) {
            case HOSTILE -> ChatColor.RED;
            case PASSIVE -> ChatColor.GREEN;
            case NEUTRAL -> ChatColor.GOLD;
            case FLYING -> ChatColor.AQUA;
            case AQUATIC -> ChatColor.BLUE;
            case BOSS -> ChatColor.DARK_PURPLE;
            case AMBIENT -> ChatColor.GRAY;
            case SPECIAL -> ChatColor.LIGHT_PURPLE;
        };
    }

    private static EntityType resolveEntityTypeInternal(String raw) {
        if (raw == null) return null;
        try { return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return null; }
    }

    public EntityType resolveEntityType() { return resolveEntityTypeInternal(mobType); }

    public String getNametag(Player player, EntityType type) {
        return getNametag(resolvePlayerName(player), type);
    }

    private static String resolvePlayerName(Player player) {
        if (player == null) return "Owner";
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                Method setPlaceholders = papi.getMethod("setPlaceholders", Player.class, String.class);
                String resolved = String.valueOf(setPlaceholders.invoke(null, player, "%player_displayname%"));
                resolved = stripLegacy(resolved).trim();
                if (!resolved.isEmpty() && !resolved.equalsIgnoreCase("%player_displayname%")) {
                    return resolved;
                }
            }
        } catch (Throwable ignored) {}
        return player.getName();
    }

    private static String stripLegacy(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)[&§][0-9A-FK-ORX]", "")
                .replaceAll("(?i)[&§]#[0-9A-F]{6}", "")
                .replaceAll("(?i)<#[0-9A-F]{6}>", "");
    }

    public String getNametag(String playerName, EntityType type) {
        String raw = type != null ? type.name() : mobType;
        String mobName = titleCaseMob(raw);
        String owner = (playerName != null && !playerName.isEmpty()) ? playerName : "Owner";
        String possessive = owner + (owner.toLowerCase(Locale.ROOT).endsWith("s") ? "'" : "'s");
        String format = NAMETAG_FORMAT.replace("{player}'s", possessive).replace("{player}", owner)
                .replace("{mob}", mobName);
        return nametagColor + format;
    }

    public static String titleCaseMob(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        String[] parts = s.split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) b.append(p.substring(1));
        }
        return b.toString();
    }
}
