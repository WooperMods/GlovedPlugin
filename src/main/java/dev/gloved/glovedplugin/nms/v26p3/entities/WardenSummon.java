package dev.gloved.glovedplugin.nms.v26p3.entities;

import com.mojang.serialization.Dynamic;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrain;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainAI;
import dev.gloved.glovedplugin.nms.v26p3.brain.SummonBrainUtil;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenAi;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Warden summon using Gloved's custom SummonBrain (CombatPets-style brained setup).
 * No vanilla Warden AI/sonic: customServerAiStep only ticks the brain and updates activity.
 * Follow/targeting and melee combat (including our attack speed/cooldown and damage pipeline)
 * are driven by SummonBrain behaviors (SummonAI/SummonBrainAI/CombatTargetRules).
 */
public class WardenSummon extends Warden implements SummonEntity {

    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private boolean brainInitialized;

    public WardenSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.WARDEN, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        try { this.setPersistenceRequired(); } catch (Throwable ignored) {}
        ensureBrain();
    }

    @Override
    public void setGoals() {
        try { this.goalSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        try { this.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}
        ensureBrain();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain.Provider<Warden> brainProvider() {
        try {
            return SummonBrain.brainProvider(this);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Brain<?> makeBrain(Object dynamic) {
        try {
            var provider = this.brainProvider();
            if (provider != null) {
                for (java.lang.reflect.Method m : provider.getClass().getMethods()) {
                    if (!"makeBrain".equals(m.getName())) continue;
                    try {
                        Object brain;
                        if (m.getParameterCount() == 1) {
                            brain = m.invoke(provider, dynamic);
                        } else if (m.getParameterCount() == 2) {
                            brain = m.invoke(provider, this, dynamic);
                        } else {
                            continue;
                        }
                        return this.refreshBrain((Brain) brain);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try { return this.getBrain(); } catch (Throwable t) { return null; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Brain<Warden> refreshBrain(Brain<Warden> brain) {
        try {
            if (brain != null && plugin != null) {
                SummonBrain.refreshBrain(plugin, this, (Brain) brain);
                brainInitialized = true;
            }
        } catch (Throwable ignored) {}
        return brain;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void ensureBrain() {
        try {
            if (plugin == null) return;
            Brain brain = this.getBrain();
            if (brain != null) {
                SummonBrain.refreshBrain(plugin, this, brain);
                try { SummonBrainAI.updateActivity(this); } catch (Throwable ignored) {}
                brainInitialized = true;
            }
        } catch (Throwable t) {
            SummonBrainUtil.debugFailSafe(plugin, "WardenSummon.ensureBrain", t);
        }
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void customServerAiStep(ServerLevel level) {
        // CombatPets-style: tick only our custom brain + activity; never vanilla Warden AI (no sonic/dig/sniff/roar).
        try {
            if (!brainInitialized) ensureBrain();
            suppressWardenMemories();
            try { ((Brain) this.getBrain()).tick(level, this); } catch (Throwable t) { dbg("brain.tick err=" + t.getClass().getSimpleName()); }
            suppressWardenMemories();
            try { SummonBrainAI.updateActivity(this); } catch (Throwable t) {
                dbg("updateActivity err=" + t.getClass().getSimpleName());
                try { SummonBrainUtil.updateActivity(this); } catch (Throwable ignored2) {}
            }
            suppressWardenMemories();
            dbg("tick end active=" + safeActive() + " target=" + (getTarget()!=null)
                    + " atkMem=" + getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                    + " walk=" + getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    + " look=" + getBrain().hasMemoryValue(MemoryModuleType.LOOK_TARGET));
        } catch (Throwable t) {
            try { SummonBrainUtil.debugFailSafe(plugin, "WardenSummon.customServerAiStep", t); } catch (Throwable ignored) {}
        }
    }

    private String safeActive() {
        try { return String.valueOf(getBrain().getActiveNonCoreActivity()); } catch (Throwable ignored) {}
        try { return String.valueOf(getBrain().getActiveActivities()); } catch (Throwable ignored2) {}
        return "?";
    }
    private void dbg(String msg) {
        try {
            if (!dev.gloved.glovedplugin.DebugLog.isEnabled()) return;
            if ((tickCount % 40L) != 0L) return;
            String id = "?"; try { id = getBukkitEntity().getUniqueId().toString().substring(0,8); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[WardenSummonAI] " + id + " " + msg);
        } catch (Throwable ignored) {}
    }

    /** Defensive suppression of warden-specific behaviors/memories (we do not run vanilla Warden AI). */
    private void suppressWardenMemories() {
        eraseMemoryByName("DIG_COOLDOWN");
        eraseMemoryByName("IS_DIGGING");
        eraseMemoryByName("DIGGING");
        eraseMemoryByName("IS_EMERGING");
        eraseMemoryByName("IS_SNIFFING");
        eraseMemoryByName("SNIFF_COOLDOWN");
        eraseMemoryByName("RECENT_PROJECTILE");
        eraseMemoryByName("ROAR_TARGET");
        eraseMemoryByName("ROAR_SOUND_COOLDOWN");
        eraseMemoryByName("ROAR_SOUND_DELAY");
        eraseMemoryByName("DISTURBANCE_LOCATION");
        eraseMemoryByName("VIBRATION_COOLDOWN");
        eraseMemoryByName("SONIC_BOOM_COOLDOWN");
        eraseMemoryByName("SONIC_BOOM_SOUND_COOLDOWN");
        eraseMemoryByName("SONIC_BOOM_SOUND_DELAY");
        eraseMemoryByName("SONIC_BOOM");
        eraseMemoryByName("IS_SONIC_BOOM");
        eraseMemoryByName("SONIC_BOOM_TARGET");
        try { this.setPersistenceRequired(); } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void eraseMemoryByName(String fieldName) {
        try {
            java.lang.reflect.Field f = MemoryModuleType.class.getField(fieldName);
            Object v = f.get(null);
            if (v instanceof MemoryModuleType type) {
                this.getBrain().eraseMemory(type);
            }
        } catch (Throwable ignored) {}
    }

    private boolean isOwnerOrAlliedEntity(Entity entity) {
        try {
            UUID ownerId = getOwnerId();
            if (ownerId == null || entity == null) return false;
            UUID eid = entity.getUUID();
            if (ownerId.equals(eid)) return true;
            try {
                var tracker = plugin != null ? plugin.getSummonTracker() : null;
                if (tracker != null) {
                    UUID otherOwner = tracker.getOwner(eid);
                    if (otherOwner != null && ownerId.equals(otherOwner)) return true;
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    // --- Anger/disturbance/push/targeting safety ---
    @Override
    public void increaseAngerAt(@Nullable Entity entity) {
        try {
            if (entity == null || isOwnerOrAlliedEntity(entity)) return;
            super.increaseAngerAt(entity);
        } catch (Throwable t) {
            SummonBrainUtil.debugFailSafe(plugin, "WardenSummon.increaseAngerAt(entity)", t);
        }
    }

    @Override
    public void increaseAngerAt(@Nullable Entity entity, int anger, boolean playListeningSound) {
        try {
            if (entity == null || isOwnerOrAlliedEntity(entity)) return;
            super.increaseAngerAt(entity, anger, playListeningSound);
        } catch (Throwable t) {
            try { increaseAngerAt(entity); } catch (Throwable ignored) {}
        }
    }

    @Override
    public boolean canTargetEntity(@Nullable Entity entity) {
        try {
            if (entity == null) return false;
            if (isOwnerOrAlliedEntity(entity)) return false;
            UUID ownerId = getOwnerId();
            if (ownerId != null) {
                org.bukkit.entity.Player owner = org.bukkit.Bukkit.getPlayer(ownerId);
                if (owner != null) {
                    try {
                        org.bukkit.entity.Entity b = entity.getBukkitEntity();
                        if (b instanceof org.bukkit.entity.LivingEntity le && CombatTargetRules.isProtected(plugin, owner, le)) return false;
                    } catch (Throwable ignored) {}
                }
            }
            return super.canTargetEntity(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void doPush(Entity entity) {
        try {
            if (entity == null) return;
            if (!isOwnerOrAlliedEntity(entity)) {
                try {
                    if (!this.isNoAi() && !this.getBrain().hasMemoryValue(MemoryModuleType.TOUCH_COOLDOWN)) {
                        this.getBrain().setMemoryWithExpiry(MemoryModuleType.TOUCH_COOLDOWN, Unit.INSTANCE, 20L);
                        this.increaseAngerAt(entity);
                        try { WardenAi.setDisturbanceLocation(this, entity.blockPosition()); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            SummonBrainUtil.debugFailSafe(plugin, "WardenSummon.doPush", t);
        }
        try { entity.push(this); } catch (Throwable ignored) {}
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource damageSource, float amount) {
        try {
            if (SummonBrainUtil.shouldIgnoreDamageFromOwner(this, damageSource)) return false;
            return super.hurtServer(level, damageSource, amount);
        } catch (Throwable t) {
            try { return super.hurtServer(level, damageSource, amount); } catch (Throwable t2) { return false; }
        }
    }

    // --- Persistence/control suppressions ---
    @Override
    public void checkDespawn() {
        // Summon lifecycle is managed by GlovedPlugin; never vanilla-despawn.
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isPersistenceRequired() {
        return true;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        try {
            if (SummonBrainUtil.shouldIgnoreDamageFromOwner(this, source)) return true;
        } catch (Throwable ignored) {}
        try { return super.isInvulnerableTo(level, source); } catch (Throwable t) { return false; }
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
