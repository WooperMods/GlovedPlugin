package dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl;

import com.google.common.collect.ImmutableMap;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainUtil;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

public class BreezeShootBehavior extends Behavior<Breeze> {
    private static final int ATTACK_RANGE_MAX_SQRT = 256;
    private static final int UNCERTAINTY_BASE = 5;
    private static final int UNCERTAINTY_MULTIPLIER = 4;
    private static final float PROJECTILE_MOVEMENT_SCALE = 0.7F;
    private static final int SHOOT_INITIAL_DELAY_TICKS = 15;
    private static final int SHOOT_RECOVER_DELAY_TICKS = 4;
    private static final int SHOOT_COOLDOWN_TICKS = 10; // memory cooldown between volleys; gated by SummonAI APS

    private final GlovedPlugin plugin;

    public BreezeShootBehavior(GlovedPlugin plugin) {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.BREEZE_SHOOT_COOLDOWN, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.BREEZE_SHOOT_CHARGING, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.BREEZE_SHOOT_RECOVERING, MemoryStatus.VALUE_ABSENT,
                MemoryModuleType.BREEZE_SHOOT, MemoryStatus.VALUE_PRESENT
        ), SHOOT_INITIAL_DELAY_TICKS + 1 + SHOOT_RECOVER_DELAY_TICKS);
        this.plugin = plugin;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Breeze breeze) {
        try { if (breeze.getPose() != Pose.STANDING) return false; } catch (Throwable ignored) {}
        LivingEntity target = breeze.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target == null) return false;
        double distance = breeze.position().distanceToSqr(target.position());
        if (distance >= ATTACK_RANGE_MAX_SQRT) {
            breeze.getBrain().eraseMemory(MemoryModuleType.BREEZE_SHOOT);
            return false;
        }
        // Gate by SummonAI cooldown/APS so wind shots integrate with soul stats/APS.
        Player owner = SummonBrainUtil.resolveOwner(breeze);
        SummonCombatService ai = plugin != null ? plugin.getSummonCombatService() : null;
        if (owner == null || ai == null) return false;
        try {
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) breeze.getBukkitEntity();
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) return false;
            if (!ai.canAttackNow(bSummon, owner, Bukkit.getCurrentTick())) return false;
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Breeze breeze, long gameTime) {
        return breeze.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && breeze.getBrain().hasMemoryValue(MemoryModuleType.BREEZE_SHOOT);
    }

    @Override
    protected void start(ServerLevel level, Breeze breeze, long gameTime) {
        try { breeze.setPose(Pose.SHOOTING); } catch (Throwable ignored) {}
        breeze.getBrain().setMemoryWithExpiry(MemoryModuleType.BREEZE_SHOOT_CHARGING, Unit.INSTANCE, SHOOT_INITIAL_DELAY_TICKS);
        try { breeze.playSound(SoundEvents.BREEZE_INHALE, 1.0F, 1.0F); } catch (Throwable ignored) {}
    }

    @Override
    protected void stop(ServerLevel level, Breeze breeze, long gameTime) {
        try {
            if (breeze.getPose() == Pose.SHOOTING) breeze.setPose(Pose.STANDING);
        } catch (Throwable ignored) {}
        breeze.getBrain().setMemoryWithExpiry(MemoryModuleType.BREEZE_SHOOT_COOLDOWN, Unit.INSTANCE, SHOOT_COOLDOWN_TICKS);
        breeze.getBrain().eraseMemory(MemoryModuleType.BREEZE_SHOOT);
    }

    @Override
    protected void tick(ServerLevel level, Breeze breeze, long gameTime) {
        Brain<Breeze> brain = breeze.getBrain();
        LivingEntity target = brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target == null) return;
        try { breeze.lookAt(EntityAnchorArgument.Anchor.EYES, target.position()); } catch (Throwable ignored) {}

        boolean charging = brain.getMemory(MemoryModuleType.BREEZE_SHOOT_CHARGING).isPresent();
        boolean recovering = brain.getMemory(MemoryModuleType.BREEZE_SHOOT_RECOVERING).isPresent();
        if (charging || recovering) return;

        // Consume SummonAI cooldown at actual fire time for APS/cooldown integration.
        Player owner = SummonBrainUtil.resolveOwner(breeze);
        SummonCombatService ai = plugin != null ? plugin.getSummonCombatService() : null;
        if (owner == null || ai == null) return;
        org.bukkit.entity.LivingEntity bSummon;
        org.bukkit.entity.LivingEntity bTarget;
        try {
            bSummon = (org.bukkit.entity.LivingEntity) breeze.getBukkitEntity();
            bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
        } catch (Throwable t) { return; }
        if (CombatTargetRules.isProtected(plugin, owner, bTarget)) return;
        long now = Bukkit.getCurrentTick();
        if (!ai.tryConsumeAttackCooldown(bSummon, owner, now)) return;

        brain.setMemoryWithExpiry(MemoryModuleType.BREEZE_SHOOT_RECOVERING, Unit.INSTANCE, SHOOT_RECOVER_DELAY_TICKS);

        try {
            Vec3 viewVector = breeze.getViewVector(1.0F);
            Vec3 targetDir = target.position().subtract(breeze.position()).normalize();
            if (viewVector.dot(targetDir) <= 0.5) return;

            double x = target.getX() - breeze.getX();
            double y = target.getY(target.isPassenger() ? 0.8 : 0.3) - breeze.getY(0.5);
            double z = target.getZ() - breeze.getZ();
            float inaccuracy = (float) (UNCERTAINTY_BASE - level.getDifficulty().getId() * UNCERTAINTY_MULTIPLIER);

            Entity projectile = createBreezeWindCharge(breeze, level);
            if (projectile == null) return;
            try { breeze.playSound(SoundEvents.BREEZE_SHOOT, 1.5F, 1.0F); } catch (Throwable ignored) {}

            // shoot(x,y,z,power,inaccuracy) is standard for Projectile in Mojmap.
            try {
                Method shoot = projectile.getClass().getMethod("shoot", double.class, double.class, double.class, float.class, float.class);
                shoot.invoke(projectile, x, y, z, PROJECTILE_MOVEMENT_SCALE, inaccuracy);
            } catch (Throwable t) {
                // fallback: set delta movement directly
                try {
                    Vec3 dir = new Vec3(x, y, z).normalize().scale(PROJECTILE_MOVEMENT_SCALE);
                    projectile.setDeltaMovement(dir);
                } catch (Throwable ignored) {}
            }

            // Attribution hardening: keep shooter as the summon (or tag explicitly) so projectile impact
            // is converted/credited by DamageProxy. Cooldown was consumed at fire time for APS parity.
            try {
                org.bukkit.entity.Entity bProj = projectile.getBukkitEntity();
                if (bProj instanceof org.bukkit.entity.Projectile proj) {
                    try { proj.setShooter(bSummon); } catch (Throwable ignored) {}
                    try {
                        java.util.UUID ownerId = (breeze instanceof SummonEntity se) ? se.getOwnerId() : owner.getUniqueId();
                        DamageProxy.tagSummonProjectile(proj, plugin, ownerId, bSummon.getUniqueId());
                        DamageProxy.markProjectileCooldownConsumed(proj, plugin);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            level.addFreshEntity(projectile);
            if (DebugLog.isEnabled()) {
                GlovedPlugin.logDebug("[Summon] Breeze wind charge fired minion=" + bSummon.getUniqueId()
                        + " owner=" + owner.getUniqueId() + " target=" + bTarget.getUniqueId() + " cdConsumedAtFire=true");
            }
        } catch (Throwable ignored) {}
    }

    private Entity createBreezeWindCharge(Breeze breeze, ServerLevel level) {
        String[] candidates = {
                "net.minecraft.world.entity.projectile.windcharge.BreezeWindCharge",
                "net.minecraft.world.entity.projectile.BreezeWindCharge",
                "net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge"
        };
        for (String cn : candidates) {
            try {
                Class<?> cls = Class.forName(cn);
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 2 && p[0].isAssignableFrom(breeze.getClass()) && p[1].isAssignableFrom(level.getClass())) {
                        Object o = c.newInstance(breeze, level);
                        if (o instanceof Entity e) return e;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
