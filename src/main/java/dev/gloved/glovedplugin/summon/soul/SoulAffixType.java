package dev.gloved.glovedplugin.summon.soul;

import java.util.Locale;

public enum SoulAffixType {
    BRUTAL,      // +minion damage %
    VIGOROUS,    // +max HP %
    SWIFT,       // +movement/speed %
    PRECISE,     // +crit chance
    DEADLY,      // +crit damage %
    TENACIOUS,   // +damage reduction / toughness %
    ENDURING,    // +duration/uptime %
    RESONANT,    // +ability potency %
    COMMANDER;   // -mana/cooldown cost %

    public String friendlyName() {
        return switch (this) {
            case BRUTAL -> "Damage";
            case VIGOROUS -> "Health";
            case SWIFT -> "Speed";
            case PRECISE -> "Crit Chance";
            case DEADLY -> "Crit Damage";
            case TENACIOUS -> "Defense";
            case ENDURING -> "Duration";
            case RESONANT -> "Ability Power";
            case COMMANDER -> "Cost Reduction";
        };
    }

    /** Player-friendly display for an affix roll (e.g. "+12% Damage", "+8% Cost Reduction"). */
    public static String format(SoulAffixRoll roll) {
        if (roll == null || roll.type() == null) return "-";
        double pct = roll.value() * 100.0;
        // round nicely (avoid ugly floating noise)
        String num = Math.abs(pct - Math.rint(pct)) < 0.005
                ? String.format(Locale.ROOT, "%.0f", pct)
                : String.format(Locale.ROOT, "%.1f", pct);
        return "+" + num + "% " + roll.type().friendlyName();
    }
}
