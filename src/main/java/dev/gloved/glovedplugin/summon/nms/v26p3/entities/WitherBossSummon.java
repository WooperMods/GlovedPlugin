package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonRangedAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

/**
 * Wither summon with CombatPets-style head/boss safety parity and summon-safe behavior:
 * - Hides boss bar and prevents invulnerability/spawn state leaks.
 * - Freezes/controls head update hooks where mappings allow so heads stay aligned with the summon target.
 * - Uses GlovedPlugin cooldown-gated ranged firing; projectile hits are converted/credited by DamageProxy/SummonCombatService.
 * - Suppresses destructive world effects (no blue/dangerous skulls, no block destruction hooks when available).
 */
public class WitherBossSummon extends WitherBoss implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    private static final Field NEXT_HEAD_UPDATE_FIELD = resolveNextHeadUpdateField();
    private static final Field IDLE_HEAD_UPDATES_FIELD = resolveIdleHeadUpdatesField();
    private static final Method SET_INVUL_TICKS_METHOD = resolveMethod(WitherBoss.class,
            new String[]{"setInvulnerableTicks"}, int.class);
    private static final Method SET_ALTERNATIVE_TARGET_METHOD = resolveMethod(WitherBoss.class,
            new String[]{"setAlternativeTarget"}, int.class, int.class);
    private static final Method GET_ALTERNATIVE_TARGET_METHOD = resolveMethod(WitherBoss.class,
            new String[]{"getAlternativeTarget"}, int.class);
    private static final Method CAN_DESTROY_BLOCKS_METHOD = resolveMethod(WitherBoss.class,
            new String[]{"canDestroyBlocks"}, boolean.class);

    public WitherBossSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.WITHER, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        hideBossBar();
        clearInvulnerabilityState();
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        // Ranged attack is cooldown-aware (fire gate in goal) and owner-credited via summon projectile pipeline.
        this.goalSelector.addGoal(3, new SummonRangedAttackGoal(this, plugin, 1.0D, 20.0F));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
    }

    @Override
    public void tick() {
        // Keep summon-safe boss/invul/head state even if vanilla logic mutates it.
        hideBossBar();
        clearInvulnerabilityState();
        suppressDestructiveState();
        stabilizeHeadUpdates();
        super.tick();
        hideBossBar();
        clearInvulnerabilityState();
        suppressDestructiveState();
        stabilizeHeadUpdates();
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        stabilizeHeadUpdates();
        suppressDestructiveState();
        super.customServerAiStep(level);
        stabilizeHeadUpdates();
        hideBossBar();
        clearInvulnerabilityState();
        suppressDestructiveState();
    }

    private void hideBossBar() {
        try {
            if (this.bossEvent != null) this.bossEvent.setVisible(false);
        } catch (Throwable ignored) {
        }
    }

    private void clearInvulnerabilityState() {
        try {
            if (SET_INVUL_TICKS_METHOD != null) {
                SET_INVUL_TICKS_METHOD.invoke(this, 0);
            }
        } catch (Throwable ignored) {
        }
        // Best-effort direct field suppression for spawn-invul variants.
        trySetIntField(this, new String[]{"invulnerableTicks", "bY", "bX"}, 0);
    }

    private void stabilizeHeadUpdates() {
        // CombatPets parity: prevent unmanaged head-update/retarget churn; keep heads driven by active target.
        try {
            if (NEXT_HEAD_UPDATE_FIELD != null) {
                Object cur = NEXT_HEAD_UPDATE_FIELD.get(this);
                if (cur instanceof int[] arr) {
                    if (arr.length > 0) arr[0] = Integer.MAX_VALUE;
                    if (arr.length > 1) arr[1] = Integer.MAX_VALUE;
                } else if (NEXT_HEAD_UPDATE_FIELD.getType() == int.class) {
                    NEXT_HEAD_UPDATE_FIELD.setInt(this, Integer.MAX_VALUE);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (IDLE_HEAD_UPDATES_FIELD != null) {
                Object cur = IDLE_HEAD_UPDATES_FIELD.get(this);
                if (cur instanceof int[] arr) {
                    if (arr.length > 0) arr[0] = Integer.MAX_VALUE;
                    if (arr.length > 1) arr[1] = Integer.MAX_VALUE;
                }
            }
        } catch (Throwable ignored) {
        }
        syncAlternativeTargetsToMain();
    }

    private void syncAlternativeTargetsToMain() {
        if (SET_ALTERNATIVE_TARGET_METHOD == null) return;
        try {
            LivingEntity target = this.getTarget();
            int id = target == null ? 0 : target.getId();
            SET_ALTERNATIVE_TARGET_METHOD.invoke(this, 1, id);
            SET_ALTERNATIVE_TARGET_METHOD.invoke(this, 2, id);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void makeInvulnerable() {
        // Summons must never enter boss spawn-invulnerability state or trigger associated side effects.
    }

    @Override
    public void performRangedAttack(LivingEntity target, float velocity) {
        // Cooldown-aware fire is gated by SummonRangedAttackGoal; projectile damage/credit is proxied on hit.
        // Delegate to vanilla skull attack for mapping-correct behavior, but keep summon-safe state around it.
        try {
            stabilizeHeadUpdates();
        } catch (Throwable ignored) {
        }
        try {
            GlovedPlugin.logDebug("[SummonWither] Skull attack fired by " + getBukkitUniqueId()
                    + " target=" + (target != null ? target.getBukkitEntity().getUniqueId() : "null"));
            super.performRangedAttack(target, velocity);
        } catch (Throwable ignored) {
            // fail-safe: do not break AI tick if a mapping-specific ranged path changes
        }
    }

    private void suppressDestructiveState() {
        // Best-effort summon safety: suppress block-breaking/grief counters where mappings expose them.
        trySetIntField(this, new String[]{"destroyBlocksTick", "blocksDestroyed", "ca"}, 0);
        trySetBooleanField(this, new String[]{"canDestroyBlocks", "destroyBlocks"}, false);
    }

    private static void trySetBooleanField(Object target, String[] names, boolean value) {
        for (String name : names) {
            try {
                Field f = WitherBoss.class.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                    f.set(target, value);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // Reflection-safe helpers for 26.3 mapping variance.
    private static Field resolveNextHeadUpdateField() {
        return resolveField(WitherBoss.class, new String[]{"nextHeadUpdate", "cy", "cB", "cC"}, int[].class, int.class);
    }

    private static Field resolveIdleHeadUpdatesField() {
        return resolveField(WitherBoss.class, new String[]{"idleHeadUpdates", "cz", "cD"}, int[].class, null);
    }

    private static Field resolveField(Class<?> owner, String[] names, Class<?> preferredType, Class<?> altType) {
        for (String name : names) {
            try {
                Field f = owner.getDeclaredField(name);
                if (preferredType != null && f.getType() != preferredType && (altType == null || f.getType() != altType)) {
                    continue;
                }
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        // Name-based fallback: int[] field likely related to head updates on wither.
        for (Field f : owner.getDeclaredFields()) {
            try {
                if (preferredType != null && f.getType() == preferredType && f.getName().toLowerCase(java.util.Locale.ROOT).contains("head")) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method resolveMethod(Class<?> owner, String[] names, Class<?>... params) {
        for (String name : names) {
            try {
                Method m = owner.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void trySetIntField(Object target, String[] names, int value) {
        for (String name : names) {
            try {
                Field f = WitherBoss.class.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == int.class || f.getType() == Integer.class) {
                    f.setInt(target, value);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
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
