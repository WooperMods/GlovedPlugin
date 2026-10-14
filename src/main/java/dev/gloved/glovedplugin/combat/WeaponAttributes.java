package dev.gloved.glovedplugin.combat;

import java.util.List;

/**
 * Per-weapon tuning. Server-side subset of Better Combat
 * {@code WeaponAttributes}: range/angle/hitbox/combo/two-handed.
 */
public record WeaponAttributes(
        double rangeBonus,
        double baseAngleDegrees,
        HitboxShape hitbox,
        boolean twoHanded,
        List<AttackStage> attacks) {

    public enum HitboxShape {
        FORWARD_BOX,
        HORIZONTAL_PLANE,
        VERTICAL_PLANE;

        public static HitboxShape parse(String raw, HitboxShape fallback) {
            if (raw == null) return fallback;
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public AttackStage stage(int comboIndex) {
        if (attacks == null || attacks.isEmpty()) return AttackStage.defaults();
        return attacks.get(Math.floorMod(comboIndex, attacks.size()));
    }

    public int comboSize() {
        return attacks == null || attacks.isEmpty() ? 1 : attacks.size();
    }
}
