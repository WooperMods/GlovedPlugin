package dev.gloved.glovedplugin.summon.soul.domain;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;

public enum SoulRarity {
    COMMON(5000, 1, 1),
    UNCOMMON(2800, 2, 2),
    RARE(1400, 2, 3),
    EPIC(600, 3, 4),
    LEGENDARY(180, 3, 5),
    MYTHIC(20, 4, 6);

    public final int defaultWeight;
    public final int minAffixes;
    public final int maxAffixes;

    SoulRarity(int defaultWeight, int minAffixes, int maxAffixes) {
        this.defaultWeight = defaultWeight;
        this.minAffixes = minAffixes;
        this.maxAffixes = maxAffixes;
    }

    public static SoulRarity fromString(String raw, SoulRarity fallback) {
        if (raw == null) return fallback;
        try {
            return SoulRarity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** XP multiplier for soul capture progression (Common = 1x base). */
    public double getCaptureXpMultiplier() {
        return switch (this) {
            case COMMON -> 1.0;
            case UNCOMMON -> 2.0;
            case RARE -> 3.0;
            case EPIC -> 5.0;
            case LEGENDARY -> 8.0;
            case MYTHIC -> 13.0;
        };
    }

    /** Solid color for rarity UI. */
    public TextColor getColor() {
        return switch (this) {
            case COMMON -> TextColor.color(0xFFFFFF);
            case UNCOMMON -> TextColor.color(0x55FF55);
            case RARE -> TextColor.color(0x5555FF);
            case EPIC -> TextColor.color(0xAA00AA);
            case LEGENDARY -> TextColor.color(0xFFAA00);
            case MYTHIC -> TextColor.color(0xFF5555);
        };
    }

    /** Rarity name as a colored component (Mythic is bold red). */
    public Component coloredNameComponent() {
        String name = this.name();
        if (this == MYTHIC) {
            return Component.text(name, net.kyori.adventure.text.format.NamedTextColor.RED, net.kyori.adventure.text.format.TextDecoration.BOLD);
        }
        return Component.text(name, getColor());
    }

    /** Legacy-colored rarity name (Mythic is bold red). */
    public String coloredNameLegacy() {
        return switch (this) {
            case COMMON -> "§fCOMMON";
            case UNCOMMON -> "§aUNCOMMON";
            case RARE -> "§9RARE";
            case EPIC -> "§5EPIC";
            case LEGENDARY -> "§6LEGENDARY";
            case MYTHIC -> "§c§lMYTHIC";
        };
    }
}
