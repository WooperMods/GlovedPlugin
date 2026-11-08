package dev.gloved.glovedplugin.summon;

import org.bukkit.ChatColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

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
        String mobName = type != null ? type.name().toLowerCase().replace("_", " ") : mobType.toLowerCase().replace("_", " ");
        String format = NAMETAG_FORMAT.replace("{player}", player.getName())
                .replace("{mob}", mobName);
        return nametagColor + format;
    }
}
