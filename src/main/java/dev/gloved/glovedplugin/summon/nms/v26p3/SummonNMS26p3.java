package dev.gloved.glovedplugin.summon.nms.v26p3;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import dev.gloved.glovedplugin.summon.entity.SummonEntityBridge;
import dev.gloved.glovedplugin.summon.nms.api.SummonEntityInjector;
import dev.gloved.glovedplugin.summon.nms.api.SummonNMS;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.Set;

public class SummonNMS26p3 implements SummonNMS {
    private final GlovedPlugin plugin;

    public SummonNMS26p3(GlovedPlugin plugin) {
        this.plugin = plugin;
        SummonEntityRegistry26p3.registerAll(plugin);
    }

    @Override
    public Set<EntityType> getSupportedEntities() {
        return SummonEntityInjector.getSupportedTypes();
    }

    @Override
    public LivingEntity spawnSummon(SummonProfile profile, SummonSharedData sharedData, Location location) {
        if (profile == null || sharedData == null || location == null || location.getWorld() == null) return null;
        World bukkitWorld = location.getWorld();
        EntityType type = profile.resolveEntityType();
        if (type == null || !SummonEntityInjector.isSupported(type)) return null;

        ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
        SummonEntity summonEntity = SummonEntityInjector.create(type, level, sharedData);
        if (!(summonEntity instanceof Mob mob)) return null;

        // goalSelector is protected; each entity clears/configures selectors in setGoals().
        try { mob.targetSelector.getAvailableGoals().clear(); } catch (Throwable ignored) {}

        LivingEntity bukkitEntity = (LivingEntity) mob.getBukkitEntity();
        SummonEntityBridge.addHolder(summonEntity, bukkitEntity);

        try { summonEntity.setGoals(); } catch (Throwable t) {
            plugin.getLogger().warning("[SummonNMS] Failed to set goals for " + type + ": " + t);
        }

        level.addFreshEntity(mob);
        mob.snapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        return bukkitEntity;
    }

    @Override
    public void setSaddle(LivingEntity entity) {
        // not needed for current summons
    }

    @Override
    public void sneak(LivingEntity entity, boolean value) {
        try {
            CraftLivingEntity craft = (CraftLivingEntity) entity;
            craft.getHandle().setShiftKeyDown(value);
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean hasNavigationPath(LivingEntity entity) {
        try {
            CraftLivingEntity craft = (CraftLivingEntity) entity;
            if (craft.getHandle() instanceof Mob mob) {
                return mob.getNavigation().isInProgress();
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
