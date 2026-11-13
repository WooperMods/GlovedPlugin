package dev.gloved.glovedplugin.summon.nms.v26p3.brain;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Brain/support helpers for brained NMS summons (Paper 26.3).
 * Compatible with both the WIP brain foundation and direct summon entity usage.
 * All methods are fail-safe on mapping/runtime issues.
 */
public final class SummonBrainUtil {
    private SummonBrainUtil() {}

    // --- Owner resolution ---
    public static Player resolveOwner(Object mobOrSummon) {
        try {
            if (mobOrSummon instanceof Mob mob) {
                return SummonBrainAI.resolveOwnerBukkit(mob);
            }
            if (mobOrSummon instanceof SummonEntity se) {
                UUID id = se.getOwnerId();
                return id == null ? null : Bukkit.getPlayer(id);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Player resolveOwner(SummonEntity summon) {
        try {
            UUID id = summon == null ? null : summon.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- Target validation/selection (used by WIP brain behaviors) ---
    public static boolean isValidAttackTarget(GlovedPlugin plugin, Player owner, LivingEntity bTarget) {
        try {
            if (plugin == null || owner == null || bTarget == null) return false;
            if (!bTarget.isValid() || bTarget.isDead()) return false;
            return !CombatTargetRules.isProtected(plugin, owner, bTarget);
        } catch (Throwable t) {
            return false;
        }
    }

    public static LivingEntity selectBestTarget(GlovedPlugin plugin, Player owner, Mob mob) {
        try {
            if (plugin == null || owner == null || mob == null) return null;
            net.minecraft.world.entity.LivingEntity nms = SummonBrainAI.findTarget(plugin, mob);
            if (nms == null) return null;
            return (LivingEntity) nms.getBukkitEntity();
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clearCombatMemories(Mob mob) {
        if (mob == null) return;
        eraseMemorySafe(mob, MemoryModuleType.ATTACK_TARGET);
        eraseMemorySafe(mob, MemoryModuleType.WALK_TARGET);
        eraseMemorySafe(mob, MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        eraseMemorySafe(mob, MemoryModuleType.ANGRY_AT);
        eraseMemorySafe(mob, MemoryModuleType.UNIVERSAL_ANGER);
        eraseMemorySafe(mob, MemoryModuleType.LOOK_TARGET);
        try { mob.setTarget(null); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void eraseMemorySafe(Mob mob, MemoryModuleType type) {
        try { mob.getBrain().eraseMemory(type); } catch (Throwable ignored) {}
    }

    // --- Activity / damage helpers (used by direct summon implementations) ---
    public static void updateActivity(Mob mob) {
        try {
            SummonBrainAI.updateActivity(mob);
        } catch (Throwable ignored) {
            try {
                if (mob == null) return;
                var brain = mob.getBrain();
                boolean hasAttack = mob.getTarget() != null;
                try { hasAttack = hasAttack || brain.getMemory(MemoryModuleType.ATTACK_TARGET).isPresent(); } catch (Throwable ignored2) {}
                if (hasAttack) brain.setActiveActivityIfPossible(Activity.FIGHT);
                else brain.setActiveActivityIfPossible(Activity.IDLE);
                try { mob.setAggressive(hasAttack); } catch (Throwable ignored3) {}
            } catch (Throwable ignored2) {}
        }
    }

    public static boolean shouldIgnoreDamageFromOwner(Mob mob, DamageSource source) {
        try {
            if (!(mob instanceof SummonEntity se) || source == null) return false;
            UUID ownerId = se.getOwnerId();
            if (ownerId == null) return false;
            Entity attacker = source.getEntity();
            if (attacker != null && ownerId.equals(attacker.getUUID())) return true;
            Entity direct = null;
            try { direct = source.getDirectEntity(); } catch (Throwable ignored) {}
            if (direct != null && ownerId.equals(direct.getUUID())) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static void debugFailSafe(GlovedPlugin plugin, String context, Throwable t) {
        try {
            if (plugin == null) return;
            GlovedPlugin.logDebug("[SummonBrain] " + context + " fail-safe: " + t);
        } catch (Throwable ignored) {}
    }
}