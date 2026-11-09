package dev.gloved.glovedplugin.summon;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class SummonProfile {
    public static final boolean INVULNERABLE = true;
    public static final int COOLDOWN_TICKS = 5;
    public static final int LIFETIME_TICKS = 600;
    /**
     * Single unified radius: max distance minions may travel from the owner, the target scan
     * range around the owner, and the max allowed target distance from the owner.
     */
    public static final double FOLLOW_RANGE = 64.0;
    public static final String NAMETAG_FORMAT = "{player}'s {mob}";
    public static final boolean NAMETAG_VISIBLE = true;

    /** Config key of this profile, used to count active minions per profile. */
    public final String name;
    /** Single entity type id for this profile (e.g. {@code BEE}). {@code mob-types} list is invalid. */
    public final String mobType;
    /** Single-element view for compatibility with existing call sites. */
    public final List<String> mobTypes;
    public final double chance;
    /** How many minions are spawned each time this profile triggers. */
    public final int minionsPerHit;
    public final int maxActive;
    public final ChatColor nametagColor;
    /** Enchant/display name that must appear in an item's lore (formatting stripped) to trigger this profile. */
    public final String enchantName;

    public SummonProfile(String name, String mobType, double chance, int minionsPerHit, int maxActive,
                         String nametagColor, String enchantName) {
        this.name = name;
        this.mobType = (mobType == null || mobType.isBlank()) ? "BEE" : mobType.trim().toUpperCase();
        this.mobTypes = Collections.singletonList(this.mobType);
        this.chance = chance;
        this.minionsPerHit = minionsPerHit > 0 ? minionsPerHit : 1;
        this.maxActive = maxActive > 0 ? maxActive : 10;
        ChatColor parsedColor = parseChatColor(nametagColor);
        this.nametagColor = parsedColor != null ? parsedColor : ChatColor.YELLOW;
        this.enchantName = enchantName == null ? "" : enchantName.trim();
    }

    /** @deprecated use {@link #SummonProfile(String, String, double, int, int, String, String)}; only the first type is used. */
    @Deprecated(forRemoval = false)
    public SummonProfile(String name, List<String> mobTypes, double chance, int minionsPerHit, int maxActive,
                         String nametagColor, String enchantName) {
        this(name, (mobTypes == null || mobTypes.isEmpty()) ? "BEE" : mobTypes.get(0),
                chance, minionsPerHit, maxActive, nametagColor, enchantName);
    }

    private static ChatColor parseChatColor(String color) {
        if (color == null) return null;
        try {
            return ChatColor.valueOf(color.toUpperCase());
        } catch (Exception e) {
            // try single char code
            if (color.length() == 1) {
                return ChatColor.getByChar(color.charAt(0));
            }
        }
        return null;
    }

    public EntityType resolveEntityType() {
        try {
            return EntityType.valueOf(mobType);
        } catch (Exception e) {
            return null;
        }
    }

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

    private static String titleCaseMob(String raw) {
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
