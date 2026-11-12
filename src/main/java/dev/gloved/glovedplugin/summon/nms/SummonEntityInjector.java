package dev.gloved.glovedplugin.summon.nms;

import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Registry of NMS summon entity constructors by Bukkit EntityType.
 */
public final class SummonEntityInjector {
    private static final Map<EntityType, BiFunction<ServerLevel, SummonSharedData, SummonEntity>> CREATORS = new ConcurrentHashMap<>();

    private SummonEntityInjector() {}

    public static void register(EntityType type, BiFunction<ServerLevel, SummonSharedData, SummonEntity> creator) {
        if (type == null || creator == null) return;
        CREATORS.put(type, creator);
    }

    public static boolean isSupported(EntityType type) {
        return type != null && CREATORS.containsKey(type);
    }

    public static Set<EntityType> getSupportedTypes() {
        return Collections.unmodifiableSet(CREATORS.keySet());
    }

    public static SummonEntity create(EntityType type, ServerLevel level, SummonSharedData sharedData) {
        BiFunction<ServerLevel, SummonSharedData, SummonEntity> creator = type == null ? null : CREATORS.get(type);
        if (creator == null) {
            throw new IllegalStateException("No SummonEntity creator registered for " + type);
        }
        return creator.apply(level, sharedData);
    }

    public static void clear() {
        CREATORS.clear();
    }
}
