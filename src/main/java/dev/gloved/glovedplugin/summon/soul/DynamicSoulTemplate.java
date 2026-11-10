package dev.gloved.glovedplugin.summon.soul;

import org.bukkit.ChatColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Runtime-resolved summon template for a dynamic soul family (entity type). Replaces static
 * SummonProfile in the fully-dynamic system while providing the fields needed by spawn/AI/tracker/UI.
 */
public final class DynamicSoulTemplate {
    public static final boolean INVULNERABLE = true;
    public static final int COOLDOWN_TICKS = 1; // base internal cooldown; cast cooldown handled by soul system
    public static final int LIFETIME_TICKS = 1200;
    public static final double FOLLOW_RANGE = 64.0;
    public static final String NAMETAG_FORMAT = "{player}'s {mob}";
    public static final boolean NAMETAG_VISIBLE = true;

    /** Family key used for caps/counting (typically entity type name). */
    public final String name;
    public final EntityType entityType;
    /** Single-element view for compatibility. */
    public final List<String> mobTypes;
    /** Legacy trigger chance (unused in cast-based dynamic system, kept for compatibility). */
    public final double chance;
    public final int minionsPerHit; // batch size per cast
    public final int maxActive;     // per-type/family cap (global cap enforced separately)
    public final ChatColor nametagColor;
    /** Not used in dynamic system (kept for compatibility). */
    public final String enchantName;
    public final EntityCategory category;

    public DynamicSoulTemplate(String familyKey, EntityType entityType, EntityCategory category,
                               int batchSize, int perTypeMaxActive, ChatColor nametagColor) {
        this.name = familyKey != null ? familyKey : (entityType != null ? entityType.name() : "UNKNOWN");
        this.entityType = entityType;
        this.category = category != null ? category : EntityCategory.SPECIAL;
        this.mobTypes = Collections.singletonList(this.entityType != null ? this.entityType.name() : "UNKNOWN");
        this.chance = 1.0;
        this.minionsPerHit = Math.max(1, batchSize);
        this.maxActive = Math.max(1, perTypeMaxActive);
        this.nametagColor = nametagColor != null ? nametagColor : defaultColor(this.category);
        this.enchantName = "";
    }

    /** Compatibility constructor matching old SummonProfile shape where needed. */
    public DynamicSoulTemplate(String name, String mobType, double chance, int minionsPerHit, int maxActive,
                               String nametagColor, String enchantName) {
        EntityType et = parseType(mobType);
        this(name != null ? name : mobType, et, EntityCategory.of(et), minionsPerHit, maxActive, parseChatColor(nametagColor));
    }

    public static EntityType parseType(String raw) {
        if (raw == null) return null;
        try { return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return null; }
    }

    public EntityType resolveEntityType() { return entityType; }

    public String getNametag(Player player, EntityType type) {
        return getNametag(player != null ? player.getName() : "Owner", type);
    }

    public String getNametag(String playerName, EntityType type) {
        String mobName = titleCaseMob(type != null ? type.name() : (entityType != null ? entityType.name() : name));
        String owner = (playerName != null && !playerName.isEmpty()) ? playerName : "Owner";
        String possessive = owner + (owner.toLowerCase(Locale.ROOT).endsWith("s") ? "'" : "'s");
        String format = NAMETAG_FORMAT.replace("{player}'s", possessive).replace("{player}", owner).replace("{mob}", mobName);
        return nametagColor + format;
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

    public static ChatColor parseChatColor(String color) {
        if (color == null) return null;
        try { return ChatColor.valueOf(color.toUpperCase(Locale.ROOT)); } catch (Exception e) {
            if (color.length() == 1) return ChatColor.getByChar(color.charAt(0));
        }
        return null;
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
