package dev.gloved.glovedplugin.summon.soul;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.gloved.glovedplugin.GlovedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Physical dropped soul item (mob head) that routes to Soul Vault on pickup. */
public final class SoulDropItem {
    private static final Gson GSON = new Gson();
    private static final Type AFFIX_LIST_TYPE = new TypeToken<List<AffixDto>>() {}.getType();

    private SoulDropItem() {}

    public static NamespacedKey soulDataKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_drop_data");
    }

    public static NamespacedKey soulOwnerKey(GlovedPlugin plugin) {
        return new NamespacedKey(plugin, "soul_drop_owner");
    }

    /** Formatted display name for use as a soul item's nametag (no quality suffix). */
    public static String getDisplayName(SoulInstance soul) {
        if (soul == null) return "Soul";
        String mobName = prettyName(soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey());
        String sep = soul.getRarity() == SoulRarity.MYTHIC ? " §r§c" : " ";
        return soul.getRarity().coloredNameLegacy() + sep + mobName + " Soul";
    }

    public static ItemStack create(GlovedPlugin plugin, SoulInstance soul) {
        Material mat = materialFor(soul != null ? soul.getEntityType() : null);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (soul != null) {
                meta.getPersistentDataContainer().set(soulDataKey(plugin), PersistentDataType.STRING, serialize(soul));
                meta.getPersistentDataContainer().set(soulOwnerKey(plugin), PersistentDataType.STRING, soul.getOwnerId().toString());
            }
            item.setItemMeta(meta);
            refresh(plugin, item, soul);
        }
        return item;
    }

    /** Refresh display name/lore for an existing soul drop item (keeps PDC data). */
    public static boolean refresh(GlovedPlugin plugin, ItemStack item) {
        if (!isSoulDrop(plugin, item)) return false;
        SoulInstance soul = readSoul(plugin, item);
        return refresh(plugin, item, soul);
    }

    private static boolean refresh(GlovedPlugin plugin, ItemStack item, SoulInstance soul) {
        if (plugin == null || item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String mobName = prettyName(soul != null ? (soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey()) : "Unknown");
        SoulRarity rarity = soul != null ? soul.getRarity() : SoulRarity.COMMON;
        String sep = rarity == SoulRarity.MYTHIC ? " §r§c" : " ";
        String display = rarity.coloredNameLegacy() + sep + mobName + " Soul";
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(display).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.addAll(SoulLoreBuilder.traitsSection(soul));
        lore.addAll(SoulLoreBuilder.statsSection(plugin, soul));
        if (soul == null) {
            // statsSection already renders a safe empty stats block for null souls.
        }
        lore.add(Component.empty());
        lore.add(LegacyComponentSerializer.legacySection().deserialize("§aRight-click§7: add to Soul Vault").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        item.setItemMeta(meta);
        return true;
    }

    public static boolean isSoulDrop(GlovedPlugin plugin, ItemStack item) {
        if (plugin == null || item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(soulDataKey(plugin), PersistentDataType.STRING);
    }

    public static SoulInstance readSoul(GlovedPlugin plugin, ItemStack item) {
        if (!isSoulDrop(plugin, item)) return null;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;
            String json = meta.getPersistentDataContainer().get(soulDataKey(plugin), PersistentDataType.STRING);
            if (json == null || json.isBlank()) return null;
            return deserialize(json);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Souls] Failed to read soul drop item: " + t);
            return null;
        }
    }

    public static UUID readOwner(GlovedPlugin plugin, ItemStack item) {
        if (!isSoulDrop(plugin, item)) return null;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return null;
            String s = meta.getPersistentDataContainer().get(soulOwnerKey(plugin), PersistentDataType.STRING);
            return s != null ? UUID.fromString(s) : null;
        } catch (Throwable ignored) { return null; }
    }

    public static Material materialFor(EntityType type) {
        if (type == null) return Material.EGG;
        Material head = headMaterialFor(type);
        if (head != null) return head;
        // Fallback for mobs without a vanilla head item: spawn egg
        try {
            String name = type.name() + "_SPAWN_EGG";
            Material egg = Material.matchMaterial(name);
            if (egg != null) return egg;
            egg = Material.valueOf(name);
            if (egg != null) return egg;
        } catch (Throwable ignored) {}
        return Material.EGG;
    }

    private static Material headMaterialFor(EntityType type) {
        if (type == null) return null;
        return switch (type) {
            case ZOMBIE -> Material.ZOMBIE_HEAD;
            case SKELETON -> Material.SKELETON_SKULL;
            case CREEPER -> Material.CREEPER_HEAD;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case PIGLIN, PIGLIN_BRUTE -> Material.PIGLIN_HEAD;
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            case PLAYER -> Material.PLAYER_HEAD;
            default -> null;
        };
    }

    private static String prettyName(String key) {
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

    private static String rarityColor(SoulRarity r) {
        if (r == null) return "§f";
        return switch (r) {
            case COMMON -> "§f";
            case UNCOMMON -> "§a";
            case RARE -> "§9";
            case EPIC -> "§5";
            case LEGENDARY -> "§6";
            case MYTHIC -> "§x§F§F§6§9§B§4";
        };
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "-";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String serialize(SoulInstance soul) {
        SoulDto dto = new SoulDto();
        dto.soulId = soul.getSoulId().toString();
        dto.ownerId = soul.getOwnerId().toString();
        dto.entityType = soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey();
        dto.familyKey = soul.getFamilyKey();
        dto.category = soul.getCategory().name();
        dto.rarity = soul.getRarity().name();
        dto.quality = soul.getQualityScore();
        dto.trait = soul.getTrait() != null ? soul.getTrait().name() : null;
        dto.traits = new ArrayList<>();
        for (SoulTrait t : soul.getTraits()) {
            if (t != null) dto.traits.add(t.name());
        }
        dto.createdAt = soul.getCreatedAt();
        dto.favorite = soul.isFavorite();
        dto.maxHealth = soul.getMaxHealth();
        dto.enhanceLevel = soul.getEnhanceLevel();
        dto.groupCapacity = soul.getGroupCapacity();
        dto.baseStats = soul.getBaseStats() != null ? soul.getBaseStats().toJson() : null;
        dto.affixes = new ArrayList<>();
        for (SoulAffixRoll r : soul.getAffixes()) {
            if (r != null) dto.affixes.add(new AffixDto(r.type().name(), r.value()));
        }
        return GSON.toJson(dto);
    }

    private static SoulInstance deserialize(String json) {
        SoulDto dto = GSON.fromJson(json, SoulDto.class);
        if (dto == null) return null;
        UUID soulId = UUID.fromString(dto.soulId);
        UUID ownerId = UUID.fromString(dto.ownerId);
        EntityType et = null;
        try { et = EntityType.valueOf(dto.entityType); } catch (Exception ignored) {}
        EntityCategory cat = EntityCategory.valueOf(dto.category);
        SoulRarity rarity = SoulRarity.valueOf(dto.rarity);
        List<SoulTrait> traits = new ArrayList<>();
        if (dto.traits != null) {
            for (String n : dto.traits) {
                if (n == null || n.isBlank()) continue;
                try { traits.add(SoulTrait.valueOf(n)); } catch (Exception ignored) {}
            }
        }
        // Migration: older items stored a single trait in dto.trait.
        if (traits.isEmpty() && dto.trait != null && !dto.trait.isBlank()) {
            try { traits.add(SoulTrait.valueOf(dto.trait)); } catch (Exception ignored) {}
        }
        List<SoulAffixRoll> affixes = new ArrayList<>();
        if (dto.affixes != null) {
            for (AffixDto a : dto.affixes) {
                if (a == null || a.type == null) continue;
                try { affixes.add(new SoulAffixRoll(SoulAffixType.valueOf(a.type), a.value)); } catch (Exception ignored) {}
            }
        }
        int groupCapacity = dto.groupCapacity > 0 ? dto.groupCapacity : 1;
        return new SoulInstance(soulId, ownerId, et, dto.familyKey, cat, rarity, dto.quality, affixes, traits,
                dto.createdAt, dto.maxHealth, dto.favorite,
                baseStatsOrFallback(dto.baseStats, et, dto.familyKey), dto.enhanceLevel, groupCapacity);
    }

    private static SoulBaseStats baseStatsOrFallback(String json, EntityType type, String familyKey) {
        SoulBaseStats parsed = SoulBaseStats.fromJson(json);
        if (parsed != null) return parsed;
        if (type != null) return SoulBaseStats.defaults(type);
        // Legacy item with an unresolvable entity type: neutral defaults.
        return SoulBaseStats.defaults(null);
    }

    private static class SoulDto {
        String soulId, ownerId, entityType, familyKey, category, rarity, trait, baseStats;
        List<String> traits;
        double quality; long createdAt; double maxHealth; boolean favorite;
        int enhanceLevel;
        int groupCapacity;
        List<AffixDto> affixes;
    }
    private static class AffixDto {
        String type; double value;
        AffixDto() {}
        AffixDto(String type, double value) { this.type = type; this.value = value; }
    }
}
