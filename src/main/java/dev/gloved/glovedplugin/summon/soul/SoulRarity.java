package dev.gloved.glovedplugin.summon.soul;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;

public enum SoulRarity {
    COMMON(5000, 1, 1),
    UNCOMMON(2800, 2, 2),
    RARE(1400, 2, 3),
    EPIC(600, 3, 3),
    LEGENDARY(180, 3, 4),
    MYTHIC(20, 4, 4);

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

    /** Solid color for rarity UI (Mythic uses hot pink end as representative). */
    public TextColor getColor() {
        return switch (this) {
            case COMMON -> TextColor.color(0xFFFFFF);
            case UNCOMMON -> TextColor.color(0x55FF55);
            case RARE -> TextColor.color(0x5555FF);
            case EPIC -> TextColor.color(0xAA00AA);
            case LEGENDARY -> TextColor.color(0xFFAA00);
            case MYTHIC -> TextColor.color(0xFF69B4);
        };
    }

    /** Rarity name as a colored component (Mythic is a dark-purple→hot-pink gradient). */
    public Component coloredNameComponent() {
        String name = this.name();
        if (this == MYTHIC) {
            return gradient(name, TextColor.color(0xAA00AA), TextColor.color(0xFF69B4));
        }
        return Component.text(name, getColor());
    }

    /** Legacy-colored rarity name (Mythic is a per-character dark-purple→hot-pink hex gradient). */
    public String coloredNameLegacy() {
        if (this == MYTHIC) return gradientLegacy(this.name(), 0xAA00AA, 0xFF69B4);
        return switch (this) {
            case COMMON -> "§fCOMMON";
            case UNCOMMON -> "§aUNCOMMON";
            case RARE -> "§9RARE";
            case EPIC -> "§5EPIC";
            case LEGENDARY -> "§6LEGENDARY";
            case MYTHIC -> gradientLegacy(this.name(), 0xAA00AA, 0xFF69B4);
        };
    }

    private static Component gradient(String text, TextColor from, TextColor to) {
        if (text == null || text.isEmpty()) return Component.empty();
        int len = text.length();
        if (len == 1) return Component.text(text, from);
        Component out = Component.empty();
        for (int i = 0; i < len; i++) {
            float t = (float) i / (float) (len - 1);
            TextColor c = TextColor.lerp(t, from, to);
            out = out.append(Component.text(String.valueOf(text.charAt(i)), c));
        }
        return out;
    }

    private static String gradientLegacy(String text, int fromRgb, int toRgb) {
        if (text == null || text.isEmpty()) return "";
        int len = text.length();
        int fr = (fromRgb >> 16) & 0xFF, fg = (fromRgb >> 8) & 0xFF, fb = fromRgb & 0xFF;
        int tr = (toRgb >> 16) & 0xFF, tg = (toRgb >> 8) & 0xFF, tb = toRgb & 0xFF;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            float t = len == 1 ? 0f : (float) i / (float) (len - 1);
            int r = Math.round(fr + (tr - fr) * t);
            int g = Math.round(fg + (tg - fg) * t);
            int b = Math.round(fb + (tb - fb) * t);
            sb.append("§x");
            String hex = String.format("%02X%02X%02X", r, g, b);
            for (char c : hex.toCharArray()) sb.append('§').append(c);
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }
}
