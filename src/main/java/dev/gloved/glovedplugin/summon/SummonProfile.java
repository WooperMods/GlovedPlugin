package dev.gloved.glovedplugin.summon;

import org.bukkit.ChatColor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SummonProfile {
    public final List<String> mobTypes;
    public final String mobType;
    public final String ai;
    public final boolean tamed;
    public final boolean friendly;
    public final boolean invulnerable;
    public final double chance;
    public final int cooldownTicks;
    public final int lifetimeTicks;
    public final int minions;
    public final int maxActive;
    public final Set<EquipmentSlot> validSlots;
    public final String targetMode;
    public final boolean damageProxy;
    public final double followRange;
    public final double attackRange;
    public final String nametagFormat;
    public final ChatColor nametagColor;
    public final boolean nametagVisible;

    public SummonProfile(List<String> mobTypes, String ai, boolean tamed, boolean friendly, boolean invulnerable,
                         double chance, int cooldownTicks, int lifetimeTicks, int minions, int maxActive,
                         List<String> validSlots, String targetMode, boolean damageProxy,
                         double followRange, double attackRange,
                         String nametagFormat, String nametagColor, boolean nametagVisible) {
        if (mobTypes == null || mobTypes.isEmpty()) {
            this.mobTypes = new ArrayList<>();
        } else {
            this.mobTypes = new ArrayList<>(mobTypes);
        }
        this.mobType = this.mobTypes.isEmpty() ? "BEE" : this.mobTypes.get(0);
        this.ai = ai;
        this.tamed = tamed;
        this.friendly = friendly;
        this.invulnerable = invulnerable;
        this.chance = chance;
        this.cooldownTicks = cooldownTicks;
        this.lifetimeTicks = lifetimeTicks;
        this.minions = minions;
        this.maxActive = maxActive > 0 ? maxActive : 10;
        Set<EquipmentSlot> slots = validSlots == null ? Set.of() : validSlots.stream()
                .map(s -> {
                    String normalized = s.toUpperCase().replace("MAIN_HAND", "HAND").replace("OFF_HAND", "OFF_HAND");
                    try { return EquipmentSlot.valueOf(normalized); } catch (Exception e) { return null; }
                })
                .filter(s -> s != null)
                .collect(Collectors.toSet());
        this.validSlots = slots.isEmpty() ? Set.of(EquipmentSlot.HAND) : slots;
        this.targetMode = targetMode;
        this.damageProxy = damageProxy;
        this.followRange = followRange;
        this.attackRange = attackRange;
        this.nametagFormat = nametagFormat != null ? nametagFormat : "{player}'s {mob}";
        // Actually parse color name
        ChatColor parsedColor = parseChatColor(nametagColor);
        this.nametagColor = parsedColor != null ? parsedColor : ChatColor.YELLOW;
        this.nametagVisible = nametagVisible;
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
        if (mobTypes.isEmpty()) return null;
        String typeName = mobTypes.get((int)(Math.random() * mobTypes.size()));
        try {
            return EntityType.valueOf(typeName.toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }

    public String getNametag(Player player, EntityType type) {
        String mobName = type != null ? type.name().toLowerCase().replace("_", " ") : mobType.toLowerCase().replace("_", " ");
        String format = nametagFormat.replace("{player}", player.getName())
                .replace("{mob}", mobName);
        return nametagColor + format;
    }
}
