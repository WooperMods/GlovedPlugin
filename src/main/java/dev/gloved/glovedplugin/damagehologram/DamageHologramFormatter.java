package dev.gloved.glovedplugin.damagehologram;

import org.bukkit.ChatColor;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;

/**
 * Formats damage hologram text to match AuraSkills HologramsHook behavior.
 */
public final class DamageHologramFormatter {

    private DamageHologramFormatter() {}

    public static NumberFormat createNumberFormat(int decimalMaxAmount) {
        int n = Math.max(0, decimalMaxAmount);
        StringBuilder pattern = new StringBuilder("#.");
        for (int i = 0; i < n; i++) pattern.append('#');
        // DecimalFormat with pattern "#." (no hashes) still fine; but match AuraSkills: "#." + repeat('#', max)
        return new DecimalFormat(pattern.toString());
    }

    public static String format(double finalDamage, boolean critical, boolean scaling, double hpActionBarScaling,
                                double decimalDisplayWhenLessThan, NumberFormat numberFormat,
                                ChatColor defaultColor, List<ChatColor> criticalColors) {
        double damage = Math.max(0.0, finalDamage);
        String damageText;
        if (scaling) {
            double damageScaling = damage * hpActionBarScaling;
            if (damageScaling < decimalDisplayWhenLessThan) {
                damageText = numberFormat.format(damageScaling);
            } else {
                damageText = String.valueOf(Math.round(damageScaling));
            }
        } else {
            if (damage < decimalDisplayWhenLessThan) {
                damageText = numberFormat.format(damage);
            } else {
                damageText = String.valueOf(Math.round(damage));
            }
        }

        if (critical) {
            return getCriticalText(damageText, defaultColor, criticalColors);
        }
        return defaultColor.toString() + damageText;
    }

    /**
     * Mirrors AuraSkills: colors digits by reverse index from right to left using criticalColors list;
     * if index exceeds list, use last color.
     */
    public static String getCriticalText(String damageText, ChatColor defaultColor, List<ChatColor> criticalColors) {
        StringBuilder text = new StringBuilder(defaultColor.toString());
        if (criticalColors == null || criticalColors.isEmpty()) {
            text.append(damageText);
            return text.toString();
        }
        for (int i = 0; i < damageText.length(); i++) {
            int j = Math.abs(i - (damageText.length() - 1));
            for (int k = 0; k < criticalColors.size(); k++) {
                if (k == j) {
                    text.append(criticalColors.get(k)).append(damageText.charAt(i));
                    break;
                } else if (k == criticalColors.size() - 1) {
                    text.append(criticalColors.get(k)).append(damageText.charAt(i));
                }
            }
        }
        return text.toString();
    }
}
