package dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import org.jetbrains.annotations.NotNull;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.ai.movement.SummonTargetingUtils;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;

/**
 * Idle behaviors for brained summons: look at owner, follow owner (Gloved 32-block teleport semantics),
 * and auto-target via SummonBrainAI/SummonTargetingUtils + SoulService combat mode + CombatTargetRules.
 */
public final class SummonIdleBehaviors {
    private static final int FOLLOW_MIN_DIST = 2;
    private static final double TELEPORT_DIST = SummonProfile.FOLLOW_RANGE; // 32.0

    private SummonIdleBehaviors() {}

    @NotNull
    public static BehaviorControl<Mob> lookAtOwner(@NotNull GlovedPlugin plugin) {
        return BehaviorBuilder.create(builder -> builder.group(
                builder.absent(MemoryModuleType.LOOK_TARGET)
        ).apply(builder, (memLookTarget) -> (level, mob, time) -> {
            ServerPlayer owner = SummonBrainAI.resolveOwnerNms(mob);
            if (owner == null) return false;
            try { memLookTarget.set(new EntityTracker(owner, true)); } catch (Throwable ignored) { return false; }
            return true;
        }));
    }

    @NotNull
    public static BehaviorControl<Mob> followOwner(@NotNull GlovedPlugin plugin) {
        return BehaviorBuilder.create(builder -> builder.group(
                builder.registered(MemoryModuleType.LOOK_TARGET),
                builder.absent(MemoryModuleType.WALK_TARGET)
        ).apply(builder, (memLookTarget, memWalkTarget) -> (level, mob, time) -> {
            // Preserve Gloved leash/range semantics (32 blocks) while behaving like CombatPets' follow.
            try {
                if (mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) return false;
            } catch (Throwable ignored) {}
            ServerPlayer owner = SummonBrainAI.resolveOwnerNms(mob);
            if (owner == null || !owner.isAlive()) {
                dbgIdle(mob, "followOwner no-owner/alive");
                return false;
            }

            // Different world/level or too far -> teleport to owner (Gloved: 32 block leash).
            try {
                if (mob.level() != owner.level() || mob.distanceTo(owner) > TELEPORT_DIST) {
                    dbgIdle(mob, "followOwner recallTeleport dist=" + String.format(java.util.Locale.US, "%.1f", mob.distanceTo(owner)));
                    teleportToOwner(mob, owner);
                    return false;
                }
            } catch (Throwable ignored) {
                try {
                    if (mob.getBukkitEntity().getWorld() != owner.getBukkitEntity().getWorld()
                            || mob.getBukkitEntity().getLocation().distanceSquared(owner.getBukkitEntity().getLocation()) > TELEPORT_DIST * TELEPORT_DIST) {
                        dbgIdle(mob, "followOwner recallTeleport(bukkit)");
                        teleportToOwner(mob, owner);
                        return false;
                    }
                } catch (Throwable ignored2) {}
            }

            try {
                if (!mob.closerThan(owner, FOLLOW_MIN_DIST)) {
                    memLookTarget.set(new EntityTracker(owner, true));
                    memWalkTarget.set(new WalkTarget(new EntityTracker(owner, false), 1.0F, FOLLOW_MIN_DIST - 1));
                    dbgIdle(mob, "followOwner setWalkTarget dist=" + String.format(java.util.Locale.US, "%.1f", mob.distanceTo(owner)));
                    return true;
                }
            } catch (Throwable t) {
                dbgIdle(mob, "followOwner error=" + t.getClass().getSimpleName());
            }
            return false;
        }));
    }

    private static void dbgIdle(Mob mob, String msg) {
        try {
            if (!DebugLog.isEnabled()) return;
            if ((mob.tickCount % 40L) != 0L) return;
            String id = "?"; try { id = mob.getBukkitEntity().getUniqueId().toString().substring(0,8); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[SummonBrainIdle] " + mob.getType() + "/" + id + " " + msg);
        } catch (Throwable ignored) {}
    }

    private static void teleportToOwner(Mob mob, ServerPlayer owner) {
        // CombatPets-style cleanup on far recall, but keep Gloved 32-block threshold/teleport behavior.
        try { SummonBrainAI.clearAttackTarget(mob); } catch (Throwable ignored) {}
        try { mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT); } catch (Throwable ignored) {}
        try { mob.getNavigation().stop(); } catch (Throwable ignored) {}
        try {
            mob.getBukkitEntity().teleport(owner.getBukkitEntity().getLocation());
        } catch (Throwable ignored) {}
    }
}
