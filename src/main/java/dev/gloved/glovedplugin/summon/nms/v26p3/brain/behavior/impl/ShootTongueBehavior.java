package dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl;

import com.google.common.collect.ImmutableMap;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.SummonBrainUtil;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.frog.Frog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * CombatPets ShootTongue parity adapted for Gloved summon pipeline:
 * - Uses SummonAI attack cooldown (APS/soul stats) to gate tongue shots.
 * - Applies damage via SummonAI.applySummonHit for owner credit/progression/hooks.
 * - No vanilla entity removal side effects on kill.
 */
public class ShootTongueBehavior extends Behavior<Frog> {
    public static final int TIME_OUT_DURATION = 100;
    public static final int CATCH_ANIMATION_DURATION = 6;
    public static final int TONGUE_ANIMATION_DURATION = 10;
    private static final float EATING_DISTANCE = 2.5F;
    private static final float EATING_MOVEMENT_FACTOR = 0.75F;

    private final GlovedPlugin plugin;
    private int eatAnimationTimer;
    private int calculatePathCounter;
    private State state = State.MOVE_TO_TARGET;
    private boolean consumedCooldownForCurrentShot;

    public ShootTongueBehavior(GlovedPlugin plugin) {
        super(ImmutableMap.of(
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED), TIME_OUT_DURATION);
        this.plugin = plugin;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Frog frog) {
        LivingEntity target = frog.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        return target != null && target.isAlive();
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Frog frog, long gameTime) {
        LivingEntity target = frog.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        return target != null && target.isAlive();
    }

    @Override
    protected void start(ServerLevel level, Frog frog, long gameTime) {
        LivingEntity target = frog.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target != null) {
            try { BehaviorUtils.lookAtEntity(frog, target); } catch (Throwable ignored) {}
            try { frog.setTongueTarget(target); } catch (Throwable ignored) {}
            frog.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target.position(), 2.0F, 0));
        }
        this.calculatePathCounter = 10;
        this.state = State.MOVE_TO_TARGET;
        this.consumedCooldownForCurrentShot = false;
    }

    @Override
    protected void stop(ServerLevel level, Frog frog, long gameTime) {
        try { frog.eraseTongueTarget(); } catch (Throwable ignored) {}
        try { frog.setPose(Pose.STANDING); } catch (Throwable ignored) {}
        frog.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        this.consumedCooldownForCurrentShot = false;
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }

    private void eatEntity(ServerLevel level, Frog frog) {
        try { level.playSound(null, frog, SoundEvents.FROG_EAT, SoundSource.NEUTRAL, 2.0F, 1.0F); } catch (Throwable ignored) {}
        Entity tongueTarget = null;
        try {
            Optional<Entity> opt = frog.getTongueTarget();
            tongueTarget = opt.orElse(null);
        } catch (Throwable ignored) {}
        if (!(tongueTarget instanceof LivingEntity target) || !target.isAlive()) return;

        Player owner = SummonBrainUtil.resolveOwner(frog);
        if (owner == null || plugin == null) return;
        SummonCombatService ai = plugin.getSummonCombatService();
        if (ai == null) return;
        try {
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) frog.getBukkitEntity();
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) return;
            // Cooldown was consumed when the tongue shot started; apply Gloved damage/credit on catch.
            long now = Bukkit.getCurrentTick();
            ai.applySummonHit(bSummon, owner, bTarget, 0.0, now, null);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void tick(ServerLevel level, Frog frog, long gameTime) {
        LivingEntity target = frog.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
        if (target == null || !target.isAlive()) return;

        try { frog.setTongueTarget(target); } catch (Throwable ignored) {}
        try { BehaviorUtils.lookAtEntity(frog, target); } catch (Throwable ignored) {}

        switch (this.state) {
            case MOVE_TO_TARGET -> {
                if (target.distanceTo(frog) <= EATING_DISTANCE) {
                    try { frog.getNavigation().stop(); } catch (Throwable ignored) {}
                    frog.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);

                    Player owner = SummonBrainUtil.resolveOwner(frog);
                    SummonCombatService ai = plugin != null ? plugin.getSummonCombatService() : null;
                    org.bukkit.entity.LivingEntity bSummon = null;
                    try { bSummon = (org.bukkit.entity.LivingEntity) frog.getBukkitEntity(); } catch (Throwable ignored) {}
                    boolean canShoot = owner != null && ai != null && bSummon != null
                            && ai.tryConsumeAttackCooldown(bSummon, owner, Bukkit.getCurrentTick());
                    if (canShoot) {
                        consumedCooldownForCurrentShot = true;
                        try { level.playSound(null, frog, SoundEvents.FROG_TONGUE, SoundSource.NEUTRAL, 2.0F, 1.0F); } catch (Throwable ignored) {}
                        try { frog.setPose(Pose.USING_TONGUE); } catch (Throwable ignored) {}
                        try { target.setDeltaMovement(target.position().vectorTo(frog.position()).normalize().scale(EATING_MOVEMENT_FACTOR)); } catch (Throwable ignored) {}
                        this.eatAnimationTimer = 0;
                        this.state = State.CATCH_ANIMATION;
                    }
                } else if (--this.calculatePathCounter <= 0) {
                    frog.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target.position(), 2.0F, 0));
                    this.calculatePathCounter = 10;
                }
            }
            case CATCH_ANIMATION -> {
                if (++this.eatAnimationTimer >= CATCH_ANIMATION_DURATION) {
                    this.state = State.EAT_ANIMATION;
                    this.eatEntity(level, frog);
                }
            }
            case EAT_ANIMATION -> {
                if (this.eatAnimationTimer++ >= TONGUE_ANIMATION_DURATION) {
                    this.state = State.MOVE_TO_TARGET;
                    consumedCooldownForCurrentShot = false;
                    try { frog.setPose(Pose.STANDING); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private enum State {
        MOVE_TO_TARGET,
        CATCH_ANIMATION,
        EAT_ANIMATION
    }
}
