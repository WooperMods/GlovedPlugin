package dev.gloved.glovedplugin.nms.v26p3.brain;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.schedule.Activity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Reflection helpers for 26.3 brain API variance (addActivity signatures differ across mappings).
 */
public final class SummonBrainReflect {
    private SummonBrainReflect() {}

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void addActivity(Brain<?> brain, Activity activity, int priority, List<?> behaviors) {
        if (brain == null || activity == null || behaviors == null) return;
        addActivityWithConditions(brain, activity, toPairs(priority, behaviors), ImmutableSet.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Pair<Integer, ?>> toPairs(int priority, List<?> behaviors) {
        ImmutableList.Builder<Pair<Integer, ?>> b = ImmutableList.builder();
        for (Object o : behaviors) {
            if (o instanceof Pair) b.add((Pair) o);
            else b.add(Pair.of(priority, o));
        }
        return b.build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void addActivityPairs(Brain<?> brain, Activity activity, List<Pair<Integer, ?>> behaviors) {
        if (brain == null || activity == null || behaviors == null) return;
        addActivityWithConditions(brain, activity, behaviors, ImmutableSet.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void addActivityWithConditions(Brain<?> brain, Activity activity,
                                                 List<Pair<Integer, ?>> behaviors,
                                                 Set<Pair<MemoryModuleType<?>, MemoryStatus>> conditions) {
        if (brain == null || activity == null || behaviors == null) return;
        List<Pair<Integer, ?>> pairList = ImmutableList.copyOf(behaviors);
        Set cond = conditions == null ? ImmutableSet.of() : ImmutableSet.copyOf(conditions);

        // 26.3 Mojang/Paper: addActivity(Activity, ImmutableList<Pair<Integer, BehaviorControl>>, Set<Pair<Mem,Status>>, Set<MemoryModuleType>)
        try {
            for (Method m : brain.getClass().getMethods()) {
                if (!m.getName().equals("addActivity")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4
                        && p[0].isAssignableFrom(Activity.class)
                        && List.class.isAssignableFrom(p[1])
                        && Set.class.isAssignableFrom(p[2])
                        && Set.class.isAssignableFrom(p[3])) {
                    m.invoke(brain, activity, pairList, cond, ImmutableSet.of());
                    dbg("addActivity(4arg) activity=" + activity + " behaviors=" + pairList.size() + " ok=true");
                    return;
                }
            }
        } catch (Throwable t) {
            dbg("addActivity 4arg failed activity=" + activity + " err=" + t);
        }

        // Legacy/alternate: addActivityWithConditions(Activity, List, Set)
        try {
            for (Method m : brain.getClass().getMethods()) {
                if (!m.getName().equals("addActivityWithConditions")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0].isAssignableFrom(Activity.class)
                        && List.class.isAssignableFrom(p[1]) && Set.class.isAssignableFrom(p[2])) {
                    m.invoke(brain, activity, pairList, cond);
                    dbg("addActivityWithConditions activity=" + activity + " ok=true behaviors=" + pairList.size());
                    return;
                }
            }
        } catch (Throwable t) {
            dbg("addActivityWithConditions 3arg failed activity=" + activity + " err=" + t);
        }

        // Legacy 3-arg: addActivity(Activity, int, List<Behavior>) (uniform priority)
        try {
            boolean uniform = true; Integer pr = null; ImmutableList.Builder flat = ImmutableList.builder();
            for (Pair<Integer, ?> pair : pairList) {
                if (pair == null) continue;
                Integer pval = pair.getFirst();
                if (pr == null) pr = pval; else if (pval == null || !pr.equals(pval)) uniform = false;
                flat.add(pair.getSecond());
            }
            if (uniform && pr != null) {
                for (Method m : brain.getClass().getMethods()) {
                    if (!m.getName().equals("addActivity")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0].isAssignableFrom(Activity.class)
                            && (p[1] == int.class || p[1] == Integer.class)
                            && List.class.isAssignableFrom(p[2])) {
                        m.invoke(brain, activity, pr, flat.build());
                        dbg("addActivity(3arg) activity=" + activity + " priority=" + pr + " behaviors=" + pairList.size() + " ok=true");
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            dbg("addActivity 3arg-legacy failed activity=" + activity + " err=" + t);
        }

        // Legacy 2-arg: addActivity(Activity, List<Pair<Integer, Behavior>>)
        try {
            for (Method m : brain.getClass().getMethods()) {
                if (!m.getName().equals("addActivity")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0].isAssignableFrom(Activity.class) && List.class.isAssignableFrom(p[1])) {
                    m.invoke(brain, activity, pairList);
                    dbg("addActivity(2arg) activity=" + activity + " behaviors=" + pairList.size() + " ok=true");
                    return;
                }
            }
        } catch (Throwable t) {
            dbg("addActivity 2arg failed activity=" + activity + " err=" + t);
        }

        dbg("addActivity FAILED activity=" + activity + " behaviors=" + pairList.size() + " ok=false mode=none");
    }

    private static void dbg(String msg) {
        try {
            if (dev.gloved.glovedplugin.DebugLog.isEnabled()) {
                dev.gloved.glovedplugin.GlovedPlugin.logDebug("[SummonBrainReflect] " + msg);
            }
        } catch (Throwable ignored) {}
    }

    public static void setCoreActivities(Brain<?> brain, Set<Activity> activities) {
        try { brain.getClass().getMethod("setCoreActivities", Set.class).invoke(brain, activities); } catch (Throwable ignored) {}
    }
    public static void setDefaultActivity(Brain<?> brain, Activity activity) {
        try { brain.getClass().getMethod("setDefaultActivity", Activity.class).invoke(brain, activity); } catch (Throwable ignored) {}
    }
    public static void useDefaultActivity(Brain<?> brain) {
        try { brain.getClass().getMethod("useDefaultActivity").invoke(brain); } catch (Throwable ignored) {}
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void setActiveActivityIfPossible(Brain brain, Activity activity) {
        try { brain.setActiveActivityIfPossible(activity); } catch (Throwable ignored) {}
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void setActiveActivityToFirstValid(Brain brain, List<Activity> activities) {
        try {
            for (Method m : brain.getClass().getMethods()) {
                if (!m.getName().equals("setActiveActivityToFirstValid")) continue;
                if (m.getParameterCount() == 1 && List.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    m.invoke(brain, activities);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }
}
