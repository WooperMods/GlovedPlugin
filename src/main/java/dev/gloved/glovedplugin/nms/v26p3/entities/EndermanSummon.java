package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Enderman summon with controlled teleport policy (CombatPets parity):
 * - Blocks uncontrolled/random teleports that can lose owner control by only permitting a single
 *   teleport in response to being hurt (including projectile hits), then consuming the allowance.
 * - Stabilizes target changes by pinning {@code targetChangeTime} forward so stare/anger-driven
 *   target churn does not trigger unwanted teleports or loss of control.
 * - Disables water sensitivity; melee/combat remains routed through GlovedPlugin summon goals/AI
 *   so cooldowns, soul stats, owner credit, and CombatTargetRules are preserved.
 */
public class EndermanSummon extends Enderman implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private boolean teleportAllowed;
    private static final Field TARGET_CHANGE_TIME_FIELD = resolveTargetChangeTimeField();

    public EndermanSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.ENDERMAN, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    private static Field resolveTargetChangeTimeField() {
        // Reflection-safe for mapping differences (Paper 26.3 mapped / obf variants).
        String[] candidates = {"targetChangeTime", "cx", "cA", "bW"};
        for (String name : candidates) {
            try {
                Field f = Enderman.class.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == int.class || f.getType() == Integer.class) return f;
            } catch (Throwable ignored) {
            }
        }
        for (Field f : Enderman.class.getDeclaredFields()) {
            try {
                if (f.getType() == int.class && f.getName().toLowerCase(java.util.Locale.ROOT).contains("target")) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        // Melee attack is cooldown/credit-aware via SummonMeleeAttackGoal -> SummonAI.
        this.goalSelector.addGoal(2, new SummonMeleeAttackGoal(this, plugin));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Keep target-change timer far in the future to prevent stare-induced target instability/teleports.
        pinTargetChangeTime();
        super.customServerAiStep(level);
    }

    @Override
    public void tick() {
        // Defensive pin in case mapped AI paths skip customServerAiStep ordering in some states.
        pinTargetChangeTime();
        super.tick();
    }

    private void pinTargetChangeTime() {
        if (TARGET_CHANGE_TIME_FIELD == null) return;
        try {
            TARGET_CHANGE_TIME_FIELD.setInt(this, this.tickCount + 600);
        } catch (Throwable ignored) {
            // fail-safe: leave vanilla behavior rather than risking hard failure
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        // Allow exactly one defensive teleport for this hurt (water/projectile/etc. sensitivities handled by
        // vanilla hurt logic but gated by our teleport() override). Prevents uncontrolled repeated teleports.
        this.teleportAllowed = true;
        try {
            return super.hurtServer(level, source, amount);
        } finally {
            // If super did not consume the allowance (no teleport occurred), drop it so random teleports
            // cannot be triggered later from stale state.
            this.teleportAllowed = false;
        }
    }

    @Override
    public boolean isSensitiveToWater() {
        return false;
    }

    @Override
    public boolean isSunBurnTick() {
        return false;
    }

    @Override
    public boolean teleport() {
        if (this.teleportAllowed) {
            this.teleportAllowed = false;
            return super.teleport();
        }
        // Block uncontrolled/random teleports (including stare/random AI teleports) to keep summon controllable.
        return false;
    }

    @Override
    public UUID getOwnerId() {
        return sharedData != null ? sharedData.getOwnerId() : null;
    }

    @Override
    public SummonSharedData getSharedData() {
        return sharedData;
    }

    @Override
    public UUID getBukkitUniqueId() {
        try {
            return this.getBukkitEntity().getUniqueId();
        } catch (Throwable t) {
            return null;
        }
    }
}
