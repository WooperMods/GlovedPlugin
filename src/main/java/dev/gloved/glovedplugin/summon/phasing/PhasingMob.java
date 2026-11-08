package dev.gloved.glovedplugin.summon.phasing;

import dev.gloved.glovedplugin.summon.ReachabilityService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Vex;

/**
 * Centralizes vanilla-AI / phasing-minion behavior.
 *
 * <ul>
 *   <li><b>Vanilla AI</b> (e.g. Vex, Phantom): keep vanilla goals/movement and only assign targets
 *       (plus owner-credited damage/extended reach). Phantoms fly with native pathfinding.</li>
 *   <li><b>Phasing through blocks</b> (Vex): can move through solid blocks, so block-collision
 *       pathfinding/reachability is treated leniently and spawn settings (no gravity) differ.</li>
 * </ul>
 *
 * <p>Keep changes targeted to these mob types to avoid affecting other minions.</p>
 */
public final class PhasingMob {
    private PhasingMob() {}

    /** Whether the mob phases through solid blocks (Vex). Not all vanilla-AI mobs phase through blocks. */
    public static boolean isPhasing(LivingEntity mob) {
        return mob instanceof Vex;
    }

    /** Whether this minion should use vanilla AI control (goals/movement/attacks) with target assignment only. */
    public static boolean usesVanillaAI(LivingEntity mob) {
        return mob instanceof Vex || mob instanceof Phantom;
    }

    /** Back-compat alias while call sites migrate. */
    public static boolean phasesThroughBlocks(LivingEntity mob) {
        return isPhasing(mob);
    }

    /** Apply spawn-time settings specific to true phasing minions (e.g. Vex no gravity to prevent sinking). */
    public static void applySpawnSettings(LivingEntity mob) {
        if (!isPhasing(mob) || mob == null) return;
        try { mob.setGravity(false); } catch (Throwable ignored) {}
    }
}
