package dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.*;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Function;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;

/**
 * Fight behaviors for brained summons. Targeting respects CombatTargetRules and SoulService combat mode;
 * melee damage flows through SummonAI cooldown + applySummonHit pipeline.
 */
public final class SummonFightBehaviors {
    private SummonFightBehaviors() {}

    @NotNull
    public static BehaviorControl<Mob> autoTargetAndAttack(@NotNull GlovedPlugin plugin) {
        return BehaviorBuilder.create(builder -> builder.group(
                builder.absent(MemoryModuleType.ATTACK_TARGET),
                builder.registered(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
        ).apply(builder, (memAttackTarget, memCantReach) -> (level, mob, time) -> {
            Optional<LivingEntity> current = SummonBrainAI.getAttackTarget(mob);
            if (current.isPresent()) return false;
            LivingEntity target = SummonBrainAI.findTarget(plugin, mob);
            if (target == null) return false;
            boolean canAttack = mob.canAttack(target);
            if (!canAttack) {
                dbg(mob, "autoTarget found=" + safeType(target) + " but canAttack=false");
                return false;
            }
            try {
                Player owner = SummonBrainAI.resolveOwnerBukkit(mob);
                org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
                if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                    dbg(mob, "autoTarget rejected protected target=" + bTarget.getType());
                    return false;
                }
            } catch (Throwable ignored) {}
            boolean set = SummonBrainAI.setAngerTarget(mob, target, false);
            dbg(mob, "autoTarget set=" + set + " target=" + safeType(target));
            return set;
        }));
    }

    private static void dbg(Mob mob, String msg) {
        try {
            if (!DebugLog.isEnabled()) return;
            if ((mob.tickCount % 40L) != 0L) return;
            String id = "?"; try { id = mob.getBukkitEntity().getUniqueId().toString().substring(0,8); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[SummonBrainFight] " + mob.getType() + "/" + id + " " + msg);
        } catch (Throwable ignored) {}
    }
    private static String safeType(LivingEntity le) {
        try { return String.valueOf(le.getType()); } catch (Throwable t) { return "?"; }
    }

    @NotNull
    public static BehaviorControl<Mob> stopAttackIfTargetInvalid(@NotNull GlovedPlugin plugin) {
        return BehaviorBuilder.create(builder -> builder.group(
                builder.present(MemoryModuleType.ATTACK_TARGET)
        ).apply(builder, memAttackTarget -> (level, mob, time) -> {
            LivingEntity target = builder.get(memAttackTarget);
            if (target == null || !target.isAlive()) {
                SummonBrainAI.clearAttackTarget(mob);
                return true;
            }
            try {
                if (level instanceof ServerLevel sl && !Sensor.isEntityAttackableIgnoringLineOfSight(sl, mob, target)) {
                    SummonBrainAI.clearAttackTarget(mob);
                    return true;
                }
            } catch (Throwable ignored) {}
            try {
                Player owner = SummonBrainAI.resolveOwnerBukkit(mob);
                org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
                if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                    SummonBrainAI.clearAttackTarget(mob);
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        }));
    }

    @NotNull
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static BehaviorControl stopAngryIfTargetDead() {
        try {
            return StopBeingAngryIfTargetDead.create();
        } catch (Throwable t) {
            // Fallback: clear anger/attack memories when current attack target is gone.
            return BehaviorBuilder.create(builder -> builder.group(
                    builder.registered(MemoryModuleType.ATTACK_TARGET)
            ).apply(builder, memAttack -> (level, entity, time) -> {
                try {
                    java.util.Optional opt = entity.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
                    if (opt != null && opt.isPresent()) {
                        Object o = opt.get();
                        if (o instanceof LivingEntity le && le.isAlive()) return false;
                    }
                    try { entity.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET); } catch (Throwable ignored) {}
                    try { entity.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT); } catch (Throwable ignored) {}
                    try { entity.getBrain().eraseMemory(MemoryModuleType.UNIVERSAL_ANGER); } catch (Throwable ignored) {}
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }));
        }
    }

    @NotNull
    public static BehaviorControl<Mob> reachTargetWhenOutOfRange() {
        Function<LivingEntity, Float> speedFunc = mob -> 1.0F;
        return BehaviorBuilder.create(builder -> builder.group(
                builder.registered(MemoryModuleType.WALK_TARGET),
                builder.registered(MemoryModuleType.LOOK_TARGET),
                builder.present(MemoryModuleType.ATTACK_TARGET)
        ).apply(builder, (walkTarget, lookTarget, attackTarget) -> (level, mob, time) -> {
            LivingEntity target = builder.get(attackTarget);
            if (target == null) return false;
            try {
                if (BehaviorUtils.isWithinAttackRange(mob, target, 1)) {
                    walkTarget.erase();
                } else {
                    lookTarget.set(new EntityTracker(target, true));
                    walkTarget.set(new WalkTarget(new EntityTracker(target, false), speedFunc.apply(mob), 2));
                }
            } catch (Throwable t) {
                double distSq = mob.distanceToSqr(target);
                double reachSq = meleeReachSq(mob, target);
                if (distSq <= reachSq) {
                    walkTarget.erase();
                } else {
                    try { lookTarget.set(new EntityTracker(target, true)); } catch (Throwable ignored) {}
                    try { walkTarget.set(new WalkTarget(new EntityTracker(target, false), 1.0F, 2)); } catch (Throwable ignored) {}
                }
            }
            return true;
        }));
    }

    @NotNull
    public static OneShot<Mob> meleeAttack(@NotNull GlovedPlugin plugin) {
        return BehaviorBuilder.create(builder -> builder.group(
                builder.registered(MemoryModuleType.LOOK_TARGET),
                builder.present(MemoryModuleType.ATTACK_TARGET)
        ).apply(builder, (lookTarget, attackTarget) -> (level, mob, time) -> {
            LivingEntity target = builder.get(attackTarget);
            if (target == null || !target.isAlive()) {
                dbgAtk(mob, "skip dead/null target");
                return false;
            }
            if (isHoldingUsableProjectileWeapon(mob)) {
                dbgAtk(mob, "skip projectile weapon");
                return false;
            }

            double distSq = mob.distanceToSqr(target);
            double reachSq = meleeReachSq(mob, target);
            if (distSq > reachSq) {
                dbgAtk(mob, "outOfRange dist=" + fmt(Math.sqrt(distSq)) + " reach=" + fmt(Math.sqrt(reachSq)) + " tgt=" + safeType(target));
                return false;
            }

            Player owner = SummonBrainAI.resolveOwnerBukkit(mob);
            if (owner == null || !owner.isOnline()) {
                dbgAtk(mob, "skip no owner");
                return false;
            }
            org.bukkit.entity.LivingEntity bSummon;
            org.bukkit.entity.LivingEntity bTarget;
            try {
                bSummon = (org.bukkit.entity.LivingEntity) mob.getBukkitEntity();
                bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            } catch (Throwable t) {
                return false;
            }
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                dbgAtk(mob, "protected target=" + bTarget.getType());
                SummonBrainAI.clearAttackTarget(mob);
                return true;
            }

            SummonAI ai = plugin.getSummonAI();
            if (ai == null) return false;
            long now = Bukkit.getCurrentTick();
            if (!ai.canAttackNow(bSummon, owner, now)) {
                dbgAtk(mob, "cooldown tgt=" + safeType(target));
                return false;
            }

            try { lookTarget.set(new EntityTracker(target, true)); } catch (Throwable ignored) {}
            try { mob.lookAt(target, 30.0F, 30.0F); } catch (Throwable ignored) {}
            // Unified per-summon attack helper: cooldown, soul stats/calc, procs/abilities, pipeline damage.
            double dealt = ai.performSummonAttack(bSummon, owner, bTarget, now, SummonAI.AttackOptions.meleePipeline());
            dbgAtk(mob, "attack dealt=" + fmt(dealt) + " tgt=" + safeType(target) + " canNowAfter=" + ai.canAttackNow(bSummon, owner, Bukkit.getCurrentTick()));
            return dealt > 0.0 || !ai.canAttackNow(bSummon, owner, Bukkit.getCurrentTick());
        }));
    }

    private static void dbgAtk(Mob mob, String msg) {
        try {
            if (!DebugLog.isEnabled()) return;
            // Throttle but allow frequent attack diagnostics (every 20 ticks per mob)
            if ((mob.tickCount % 20L) != 0L) return;
            String id = "?"; try { id = mob.getBukkitEntity().getUniqueId().toString().substring(0,8); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[SummonBrainAtk] " + mob.getType() + "/" + id + " " + msg);
        } catch (Throwable ignored) {}
    }
    private static String fmt(double d) { return String.format(java.util.Locale.US, "%.2f", d); }

    private static boolean isHoldingUsableProjectileWeapon(Mob mob) {
        try {
            return mob.isHolding(itemStack -> {
                try {
                    Item item = itemStack.getItem();
                    if (!(item instanceof ProjectileWeaponItem)) return false;
                    // 26.3 mapping variance: try common method names reflectively.
                    try {
                        java.lang.reflect.Method m = mob.getClass().getMethod("canFireProjectileWeapon", ProjectileWeaponItem.class);
                        Object r = m.invoke(mob, (ProjectileWeaponItem) item);
                        return r instanceof Boolean b && b;
                    } catch (NoSuchMethodException ignored) {
                        try {
                            java.lang.reflect.Method m = mob.getClass().getMethod("canFireProjectileWeapon", net.minecraft.world.item.ItemStack.class);
                            Object r = m.invoke(mob, itemStack);
                            return r instanceof Boolean b && b;
                        } catch (Throwable ignored2) {}
                    }
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            });
        } catch (Throwable t) {
            return false;
        }
    }

    private static double meleeReachSq(Mob mob, LivingEntity target) {
        try {
            double w = mob.getBbWidth();
            double tw = target.getBbWidth();
            double reach = Math.sqrt((w * 2.0) * (w * 2.0) + tw) + 2.0;
            return reach * reach;
        } catch (Throwable t) {
            return 9.0;
        }
    }
}
