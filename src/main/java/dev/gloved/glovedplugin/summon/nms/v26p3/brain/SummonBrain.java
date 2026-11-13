package dev.gloved.glovedplugin.summon.nms.v26p3.brain;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonCoreBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonFightBehaviors;
import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.SummonIdleBehaviors;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;

/**
 * Gloved-adapted brain wiring for summon entities (modeled closely on CombatPets PetBrain).
 * Uses Gloved owner resolution, SoulService combat mode, CombatTargetRules/32-block targeting,
 * and SummonAI cooldown/damage pipeline (soul stats & calculations) through behavior helpers.
 *
 * <p>Wiring prefers direct 26.3 API calls (like CombatPets) and falls back reflectively for
 * mapping variance. Failures are logged when debug is enabled.</p>
 */
public final class SummonBrain {
    private SummonBrain() {}

    protected static final ImmutableList<MemoryModuleType<?>> MEMORY_TYPES = ImmutableList.of(
            MemoryModuleType.IS_IN_WATER,
            MemoryModuleType.LOOK_TARGET,
            MemoryModuleType.LIKED_PLAYER,
            MemoryModuleType.NEAREST_LIVING_ENTITIES,
            MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
            MemoryModuleType.NEAREST_VISIBLE_PLAYER,
            MemoryModuleType.NEAREST_VISIBLE_ATTACKABLE_PLAYER,
            MemoryModuleType.NEAREST_VISIBLE_WANTED_ITEM,
            MemoryModuleType.NEAREST_VISIBLE_ADULT_HOGLINS,
            MemoryModuleType.HURT_BY,
            MemoryModuleType.HURT_BY_ENTITY,
            MemoryModuleType.WALK_TARGET,
            MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
            MemoryModuleType.ATTACK_TARGET,
            MemoryModuleType.ATTACK_COOLING_DOWN,
            MemoryModuleType.INTERACTION_TARGET,
            MemoryModuleType.PATH,
            MemoryModuleType.ANGRY_AT,
            MemoryModuleType.UNIVERSAL_ANGER,
            MemoryModuleType.AVOID_TARGET,
            MemoryModuleType.RIDE_TARGET,
            MemoryModuleType.ATE_RECENTLY,
            MemoryModuleType.RAM_COOLDOWN_TICKS,
            MemoryModuleType.RAM_TARGET,
            MemoryModuleType.LONG_JUMP_COOLDOWN_TICKS,
            MemoryModuleType.NEAREST_REPELLENT
    );

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ImmutableList<SensorType<?>> getSensorTypes() {
        try {
            return ImmutableList.of(
                    SensorType.NEAREST_LIVING_ENTITIES,
                    SensorType.NEAREST_PLAYERS,
                    SensorType.NEAREST_ITEMS,
                    SensorType.HURT_BY,
                    SensorType.PIGLIN_SPECIFIC_SENSOR
            );
        } catch (Throwable t) {
            // Fallback if a sensor constant is unavailable in this mapping
            return ImmutableList.of(
                    SensorType.NEAREST_LIVING_ENTITIES,
                    SensorType.NEAREST_PLAYERS,
                    SensorType.HURT_BY
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <E extends Mob> Brain.Provider<E> brainProvider(@NotNull E entity) {
        try {
            return brainProvider(entity, null);
        } catch (Throwable t) {
            debug("brainProvider(entity) failed: " + t);
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <E extends Mob> Brain.Provider<E> brainProvider(@NotNull E entity, List<MemoryModuleType<?>> extraMemory) {
        List mem = new ArrayList(MEMORY_TYPES);
        if (extraMemory != null && !extraMemory.isEmpty()) mem.addAll(extraMemory);
        List sensors = new ArrayList(getSensorTypes());
        // Use reflective provider creation (26.3 mapping overloads vary).
        try {
            for (Method m : Brain.class.getMethods()) {
                if (!m.getName().equals("provider") || m.getParameterCount() != 2) continue;
                try {
                    Object res = m.invoke(null, mem, sensors);
                    if (res instanceof Brain.Provider) return (Brain.Provider<E>) res;
                } catch (Throwable ignored) {}
            }
            // Some mappings may expect (sensors, memories) order or different shapes; try reversed if available.
            for (Method m : Brain.class.getMethods()) {
                if (!m.getName().equals("provider") || m.getParameterCount() != 2) continue;
                try {
                    Object res = m.invoke(null, sensors, mem);
                    if (res instanceof Brain.Provider) return (Brain.Provider<E>) res;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            debug("brainProvider reflective failed: " + t);
        }
        debug("brainProvider returned null (mem=" + mem.size() + " sensors=" + sensors.size() + ")");
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @NotNull
    public static <E extends Mob> Brain<E> refreshBrain(@NotNull GlovedPlugin plugin, @NotNull E summon, @NotNull Brain<E> brain) {
        try {
            resetBrainState(brain);
            wireActivities(plugin, summon, (Brain) brain);
            debug("refreshBrain wired summon=" + safeSummonName(summon)
                    + " brain=" + brain.getClass().getName());
        } catch (Throwable t) {
            debug("refreshBrain wireActivities failed summon=" + safeSummonName(summon) + ": " + t);
        }
        return brain;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void resetBrainState(Brain brain) {
        if (brain == null) return;
        try { brain.stopAll(null, null); } catch (Throwable ignored) {}
        try {
            for (Method m : brain.getClass().getMethods()) {
                if (m.getParameterCount() == 2 && "stopAll".equals(m.getName())) {
                    // 26.3: stopAll(ServerLevel, LivingEntity) - called from tick context later; best-effort nulls handled above.
                    continue;
                }
            }
        } catch (Throwable ignored) {}
        try {
            for (Method m : brain.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if ("removeAllBehaviors".equals(n) || "clearMemories".equals(n) || "removeAllBehaviours".equals(n)) {
                    try { m.invoke(brain); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try {
            // Best-effort: erase common target/path memories before rewiring.
            for (MemoryModuleType<?> mem : new MemoryModuleType<?>[]{
                    MemoryModuleType.ATTACK_TARGET, MemoryModuleType.WALK_TARGET, MemoryModuleType.LOOK_TARGET,
                    MemoryModuleType.ANGRY_AT, MemoryModuleType.AVOID_TARGET, MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
                    MemoryModuleType.PATH, MemoryModuleType.INTERACTION_TARGET
            }) {
                try { brain.eraseMemory((MemoryModuleType) mem); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void wireActivities(GlovedPlugin plugin, Mob summon, Brain brain) {
        if (brain == null) return;

        // Core: movement/look/swim/anger cleanup. Target-invalid checks are also safe here.
        ImmutableList core = ImmutableList.of(
                SummonCoreBehaviors.lookAtTarget(),
                SummonCoreBehaviors.moveToTarget(),
                SummonCoreBehaviors.swim(),
                SummonFightBehaviors.stopAngryIfTargetDead(),
                SummonFightBehaviors.stopAttackIfTargetInvalid(plugin)
        );
        if (!tryAddActivityDirect(brain, Activity.CORE, 0, core)) {
            tryAddActivityPairs(brain, Activity.CORE, ImmutableList.of(
                    Pair.of(0, SummonCoreBehaviors.lookAtTarget()),
                    Pair.of(0, SummonCoreBehaviors.moveToTarget()),
                    Pair.of(0, SummonCoreBehaviors.swim()),
                    Pair.of(0, SummonFightBehaviors.stopAngryIfTargetDead()),
                    Pair.of(0, SummonFightBehaviors.stopAttackIfTargetInvalid(plugin))
            ));
        }

        // Idle: follow owner and acquire targets per combat mode/rules (32-block leash preserved in helpers).
        Object lookAtOwner = SummonIdleBehaviors.lookAtOwner(plugin);
        Object idleRunOne;
        try {
            idleRunOne = new RunOne(ImmutableList.of(Pair.of(lookAtOwner, 1)));
        } catch (Throwable t) {
            idleRunOne = lookAtOwner;
        }
        ImmutableList idle = ImmutableList.of(
                idleRunOne,
                SummonIdleBehaviors.followOwner(plugin),
                SummonFightBehaviors.autoTargetAndAttack(plugin)
        );
        if (!tryAddActivityDirect(brain, Activity.IDLE, 10, idle)) {
            tryAddActivityPairs(brain, Activity.IDLE, ImmutableList.of(
                    Pair.of(10, idleRunOne),
                    Pair.of(10, SummonIdleBehaviors.followOwner(plugin)),
                    Pair.of(10, SummonFightBehaviors.autoTargetAndAttack(plugin))
            ));
        }

        // Fight: validate, chase, and melee via SummonAI cooldown/damage pipeline (soul stats/calcs).
        ImmutableList fight = ImmutableList.of(
                SummonFightBehaviors.stopAttackIfTargetInvalid(plugin),
                SummonFightBehaviors.reachTargetWhenOutOfRange(),
                SummonFightBehaviors.meleeAttack(plugin)
        );
        if (!tryAddActivityDirect(brain, Activity.FIGHT, 10, fight)) {
            tryAddActivityPairs(brain, Activity.FIGHT, ImmutableList.of(
                    Pair.of(10, SummonFightBehaviors.stopAttackIfTargetInvalid(plugin)),
                    Pair.of(10, SummonFightBehaviors.reachTargetWhenOutOfRange()),
                    Pair.of(10, SummonFightBehaviors.meleeAttack(plugin))
            ));
        }

        if (!tryInvoke(brain, "setCoreActivities", new Class[]{java.util.Set.class}, new Object[]{ImmutableSet.of(Activity.CORE)})) {
            debug("setCoreActivities failed for " + safeSummonName(summon));
        }
        if (!tryInvoke(brain, "setDefaultActivity", new Class[]{Activity.class}, new Object[]{Activity.IDLE})) {
            debug("setDefaultActivity failed for " + safeSummonName(summon));
        }
        if (!tryInvoke(brain, "useDefaultActivity", new Class[0], new Object[0])) {
            debug("useDefaultActivity failed for " + safeSummonName(summon));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean tryAddActivityDirect(Brain brain, Activity activity, int priority, ImmutableList behaviors) {
        try {
            java.util.List pairs = new java.util.ArrayList();
            for (Object b : behaviors) pairs.add(Pair.of(priority, b));
            SummonBrainReflect.addActivityWithConditions(brain, activity, ImmutableList.copyOf(pairs), ImmutableSet.of());
            return true;
        } catch (Throwable t) {
            debug("tryAddActivityDirect failed activity=" + activity + " err=" + t);
            return false;
        }
    }

    private static void debug(String msg) {
        try {
            if (DebugLog.isEnabled()) {
                GlovedPlugin.logDebug("[SummonBrain] " + msg);
            }
        } catch (Throwable ignored) {}
    }

    private static String safeSummonName(Mob summon) {
        try { return summon.getType() + "/" + summon.getStringUUID(); } catch (Throwable t) { return String.valueOf(summon); }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void tryAddActivityPairs(Brain brain, Activity activity, ImmutableList pairs) {
        try {
            SummonBrainReflect.addActivityWithConditions(brain, activity, pairs, ImmutableSet.of());
        } catch (Throwable t) {
            debug("tryAddActivityPairs failed activity=" + activity + " err=" + t);
        }
    }

    private static boolean tryInvoke(Object target, String name, Class<?>[] types, Object[] args) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            try {
                for (Method m : target.getClass().getMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                    try { m.invoke(target, args); return true; } catch (Throwable ignored2) {}
                }
            } catch (Throwable ignored3) {}
        }
        return false;
    }
}
