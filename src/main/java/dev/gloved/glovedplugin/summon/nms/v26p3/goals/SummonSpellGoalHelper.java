package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-safe, cached discovery/instantiation of inner spell goals for summon spellcasters.
 * Fail-open: failures are swallowed so missing/mapped-differently goals do not break goal wiring.
 */
public final class SummonSpellGoalHelper {
    private static final ConcurrentHashMap<String, Object> CTOR_CACHE = new ConcurrentHashMap<>();
    private static final Object MISS = new Object();

    private SummonSpellGoalHelper() {}

    /** Find and instantiate inner Goal classes of {@code hostClass} whose simple name contains any keyword. */
    public static Map<Goal, Integer> instantiateInnerGoals(Mob host, Class<?> hostClass, String[] includeAny, String[] excludeAny, int priority) {
        Map<Goal, Integer> out = new LinkedHashMap<>();
        try {
            for (Class<?> inner : hostClass.getDeclaredClasses()) {
                if (!Goal.class.isAssignableFrom(inner) || inner.isInterface() || Modifier.isAbstract(inner.getModifiers())) continue;
                String simple = inner.getSimpleName().toLowerCase(Locale.ROOT);
                if (!matchesAny(simple, includeAny)) continue;
                if (matchesAny(simple, excludeAny)) continue;
                Goal g = instantiate(inner, hostClass, host);
                if (g != null) out.put(g, priority);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Instantiate a specific inner Goal by exact simple name (cached). */
    public static Goal instantiateBySimpleName(Mob host, Class<?> hostClass, String simpleName) {
        try {
            String key = hostClass.getName() + "#" + simpleName + "(" + hostClass.getName() + ")";
            Object cached = CTOR_CACHE.computeIfAbsent(key, k -> {
                try {
                    Class<?> inner = findDeclaredInnerBySimpleName(hostClass, simpleName);
                    if (inner == null || !Goal.class.isAssignableFrom(inner)) return MISS;
                    Constructor<?> c = inner.getDeclaredConstructor(hostClass);
                    c.setAccessible(true);
                    return c;
                } catch (Throwable t) { return MISS; }
            });
            if (cached == MISS) return null;
            Constructor<?> ctor = (Constructor<?>) cached;
            Object o = ctor.newInstance(host);
            return (o instanceof Goal g) ? g : null;
        } catch (Throwable ignored) {}
        return null;
    }

    /** Instantiate any inner Goal of {@code ownerClass} (or its superclasses) by simple name, passing {@code host} if ctor matches. */
    public static Goal instantiateFromHierarchy(Mob host, Class<?> ownerClass, Class<?> ctorParamClass, String simpleName) {
        try {
            Class<?> scan = ownerClass;
            while (scan != null && scan != Object.class) {
                Goal g = instantiateFromOwner(host, scan, ctorParamClass, simpleName);
                if (g != null) return g;
                scan = scan.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Goal instantiateFromOwner(Mob host, Class<?> ownerClass, Class<?> ctorParamClass, String simpleName) {
        try {
            String key = ownerClass.getName() + "#" + simpleName + "(" + ctorParamClass.getName() + ")";
            Object cached = CTOR_CACHE.computeIfAbsent(key, k -> {
                try {
                    Class<?> inner = findDeclaredInnerBySimpleName(ownerClass, simpleName);
                    if (inner == null || !Goal.class.isAssignableFrom(inner)) return MISS;
                    Constructor<?> c = inner.getDeclaredConstructor(ctorParamClass);
                    c.setAccessible(true);
                    return c;
                } catch (Throwable t) { return MISS; }
            });
            if (cached == MISS) return null;
            Constructor<?> ctor = (Constructor<?>) cached;
            Object o = ctor.newInstance(host);
            return (o instanceof Goal g) ? g : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> findDeclaredInnerBySimpleName(Class<?> owner, String simpleName) {
        try {
            for (Class<?> inner : owner.getDeclaredClasses()) {
                if (inner.getSimpleName().equals(simpleName)) return inner;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Goal instantiate(Class<?> inner, Class<?> hostClass, Mob host) {
        try {
            String key = inner.getName() + "(" + hostClass.getName() + ")";
            Object cached = CTOR_CACHE.computeIfAbsent(key, k -> {
                try {
                    Constructor<?> c = inner.getDeclaredConstructor(hostClass);
                    c.setAccessible(true);
                    return c;
                } catch (Throwable t) { return MISS; }
            });
            if (cached == MISS) return null;
            Constructor<?> ctor = (Constructor<?>) cached;
            Object o = ctor.newInstance(host);
            return (o instanceof Goal g) ? g : null;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean matchesAny(String value, String[] needles) {
        if (needles == null || needles.length == 0) return false;
        for (String n : needles) {
            if (n != null && value.contains(n.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
