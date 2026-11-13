package dev.gloved.glovedplugin.combat.targeting;

/**
 * One stage of a weapon combo. Mirrors Better Combat {@code attacks[]} entries
 * in a server-safe subset: no skeletal animation, just numbers + FX.
 */
public record AttackStage(
        double damageMultiplier,
        double rangeMultiplier,
        double angleDegrees,
        int upswingTicks,
        String swingSound,
        String particles) {

    public static AttackStage defaults() {
        return new AttackStage(1.0, 1.0, 90.0, 0, "ENTITY_PLAYER_ATTACK_SWEEP", "SWEEP_ATTACK");
    }
}
