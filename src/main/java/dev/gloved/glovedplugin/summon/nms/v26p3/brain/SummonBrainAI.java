package dev.gloved.glovedplugin.summon.nms.v26p3.brain;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.ai.movement.SummonAIUtils;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.schedule.Activity;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;

/**
 * Brain activity/target helper for brained summons (Gloved-adapted from CombatPets PetAI).
 */
public final class SummonBrainAI {
    private SummonBrainAI() {}

    public static void updateActivity(@NotNull Mob entity) {
        Brain<?> brain = entity.getBrain();
        boolean hasTarget = getAttackTarget(entity).isPresent();
        String chosen = "IDLE";
        try {
            if (hasTarget) {
                chosen = "FIGHT";
                try { SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.FIGHT); }
                catch (Throwable ignored) { try { brain.setActiveActivityIfPossible(Activity.FIGHT); } catch (Throwable ignored2) {} }
            } else if (isInWaterSafe(entity)) {
                chosen = "SWIM/IDLE";
                try { SummonBrainReflect.setActiveActivityToFirstValid(brain, java.util.List.of(Activity.SWIM, Activity.IDLE)); }
                catch (Throwable ignored) {
                    try { SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.SWIM); } catch (Throwable ignored2) {
                        try { SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.IDLE); } catch (Throwable ignored3) {}
                    }
                }
            } else {
                chosen = "IDLE";
                try { SummonBrainReflect.setActiveActivityIfPossible(brain, Activity.IDLE); }
                catch (Throwable ignored) { try { brain.setActiveActivityIfPossible(Activity.IDLE); } catch (Throwable ignored2) {} }
            }
            try { entity.setAggressive(brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)); } catch (Throwable ignored) {}
        } finally {
            debugTick(entity, "updateActivity hasTarget=" + hasTarget + " chosen=" + chosen
                    + " active=" + safeActiveActivity(brain)
                    + " walk=" + brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)
                    + " look=" + brain.hasMemoryValue(MemoryModuleType.LOOK_TARGET));
        }
    }

    private static String safeActiveActivity(Brain<?> brain) {
        try { return String.valueOf(brain.getActiveNonCoreActivity()); } catch (Throwable ignored) {}
        try { return String.valueOf(brain.getActiveActivities()); } catch (Throwable ignored2) {}
        return "?";
    }

    private static void debugTick(Mob entity, String msg) {
        try {
            if (!DebugLog.isEnabled()) return;
            long t = entity.tickCount;
            if ((t % 40L) != 0L) return; // throttle
            String id = "?";
            try { id = entity.getBukkitEntity().getUniqueId().toString().substring(0, 8); } catch (Throwable ignored) {}
            String type = String.valueOf(entity.getType());
            Player owner = resolveOwnerBukkit(entity);
            String ownerName = owner != null ? owner.getName() : "null";
            GlovedPlugin.logDebug("[SummonBrainAI] " + type + "/" + id + " owner=" + ownerName + " " + msg);
        } catch (Throwable ignored) {}
    }

    private static boolean isInWaterSafe(@NotNull Mob entity) {
        try { return entity.isInWater(); } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = entity.getClass().getMethod("isInWaterOrBubble");
            Object r = m.invoke(entity);
            return r instanceof Boolean b && b;
        } catch (Throwable ignored) {}
        return false;
    }

    @NotNull
    public static Optional<LivingEntity> getAttackTarget(@NotNull Mob mob) {
        try {
            return mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Nullable
    public static Player resolveOwnerBukkit(@NotNull Mob mob) {
        try {
            if (!(mob instanceof SummonEntity se)) return null;
            UUID ownerId = se.getOwnerId();
            return ownerId == null ? null : Bukkit.getPlayer(ownerId);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    public static ServerPlayer resolveOwnerNms(@NotNull Mob mob) {
        Player owner = resolveOwnerBukkit(mob);
        if (owner == null) return null;
        try {
            return ((CraftPlayer) owner).getHandle();
        } catch (Throwable t) {
            return null;
        }
    }

    public static String resolveCombatMode(@NotNull GlovedPlugin plugin, @NotNull Mob mob) {
        try {
            Player owner = resolveOwnerBukkit(mob);
            if (owner == null) return "AGGRESSIVE";
            if (plugin.getSoulService() != null) {
                return plugin.getSoulService().getSummonCombatMode(owner.getUniqueId());
            }
        } catch (Throwable ignored) {}
        return "AGGRESSIVE";
    }

    public static boolean isProtectedTarget(@NotNull GlovedPlugin plugin, @Nullable Player owner, @Nullable org.bukkit.entity.LivingEntity bTarget) {
        if (owner == null || bTarget == null) return true;
        return CombatTargetRules.isProtected(plugin, owner, bTarget);
    }

    public static boolean setAngerTarget(@NotNull Mob mob, @NotNull LivingEntity target, boolean force) {
        try {
            if (!force && mob.level() instanceof ServerLevel level) {
                if (!Sensor.isEntityAttackableIgnoringLineOfSight(level, mob, target)) return false;
            }
        } catch (Throwable ignored) {}
        try { mob.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE); } catch (Throwable ignored) {}
        try { mob.getBrain().setMemoryWithExpiry(MemoryModuleType.ANGRY_AT, target.getUUID(), 20L * 60L); } catch (Throwable ignored) {}
        try { mob.getBrain().setMemoryWithExpiry(MemoryModuleType.UNIVERSAL_ANGER, true, 20L * 60L); } catch (Throwable ignored) {}
        try { mob.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, target); } catch (Throwable ignored) {}
        try {
            org.bukkit.entity.Entity bSummon = mob.getBukkitEntity();
            org.bukkit.entity.Entity bTarget = target.getBukkitEntity();
            if (bSummon instanceof org.bukkit.entity.Mob bMob && bTarget instanceof org.bukkit.entity.LivingEntity bLiving) {
                bMob.setTarget(bLiving);
            } else {
                mob.setTarget(target);
            }
        } catch (Throwable ignored) {}
        return true;
    }

    public static void clearAttackTarget(@NotNull Mob mob) {
        try { mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET); } catch (Throwable ignored) {}
        try { mob.getBrain().eraseMemory(MemoryModuleType.ANGRY_AT); } catch (Throwable ignored) {}
        try { mob.getBrain().eraseMemory(MemoryModuleType.UNIVERSAL_ANGER); } catch (Throwable ignored) {}
        try { mob.setTarget(null); } catch (Throwable ignored) {}
    }

    /**
     * Gloved-adapted target finding: scans owner/minion nearby entities and recent damagers,
     * filters by CombatTargetRules + SoulService combat mode, and scores with SummonAIUtils.
     */
    @Nullable
    public static LivingEntity findTarget(@NotNull GlovedPlugin plugin, @NotNull Mob mob) {
        Player owner = resolveOwnerBukkit(mob);
        if (owner == null || !owner.isOnline()) {
            debugEvent(mob, "findTarget skip ownerMissing");
            return null;
        }
        org.bukkit.entity.LivingEntity bSummon;
        try {
            bSummon = (org.bukkit.entity.LivingEntity) mob.getBukkitEntity();
        } catch (Throwable t) {
            debugEvent(mob, "findTarget skip bukkitCastFail");
            return null;
        }
        String mode = resolveCombatMode(plugin, mob);
        boolean defensive = "DEFENSIVE".equalsIgnoreCase(mode);

        Set<UUID> recentDamagerIds = new HashSet<>(SummonCombatService.getRecentDamagers(owner));
        try { recentDamagerIds.addAll(SummonCombatService.getRecentDamagers(bSummon)); } catch (Throwable ignored) {}
        List<org.bukkit.entity.LivingEntity> candidates = new ArrayList<>();
        double scanDist = SummonProfile.FOLLOW_RANGE;

        collectCandidates(plugin, owner, bSummon, owner.getNearbyEntities(scanDist, scanDist, scanDist), candidates, defensive, recentDamagerIds);
        collectCandidates(plugin, owner, bSummon, bSummon.getNearbyEntities(scanDist, scanDist, scanDist), candidates, defensive, recentDamagerIds);
        collectRecentDamagers(plugin, owner, owner.getWorld(), SummonCombatService.getRecentDamagers(owner), candidates);
        collectRecentDamagers(plugin, owner, bSummon.getWorld(), SummonCombatService.getRecentDamagers(bSummon), candidates);

        int beforeLeash = candidates.size();
        // Do not target enemies past 32 blocks from the owner.
        candidates.removeIf(le -> {
            try {
                return le.getWorld() != owner.getWorld() || le.getLocation().distanceSquared(owner.getLocation()) > 1024.0D;
            } catch (Throwable t) { return true; }
        });

        org.bukkit.entity.LivingEntity lastBukkit = null;
        try {
            if (mob.getTarget() != null) lastBukkit = (org.bukkit.entity.LivingEntity) mob.getTarget().getBukkitEntity();
        } catch (Throwable ignored) {}
        if (lastBukkit != null) {
            try {
                if (lastBukkit.getWorld() != owner.getWorld() || lastBukkit.getLocation().distanceSquared(owner.getLocation()) > 1024.0D) {
                    lastBukkit = null;
                }
            } catch (Throwable ignored) { lastBukkit = null; }
        }
        org.bukkit.entity.LivingEntity best = SummonAIUtils.selectBestTarget(owner, bSummon, candidates, recentDamagerIds, lastBukkit);
        debugEvent(mob, "findTarget mode=" + mode + " defensive=" + defensive
                + " recent=" + recentDamagerIds.size() + " candPreLeash=" + beforeLeash
                + " cand=" + candidates.size() + " best=" + (best != null ? best.getType() : "null"));
        if (best == null) return null;
        try {
            return ((CraftLivingEntity) best).getHandle();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void debugEvent(Mob entity, String msg) {
        try {
            if (!DebugLog.isEnabled()) return;
            long t = entity.tickCount;
            if ((t % 40L) != 0L) return; // throttle event logs
            String id = "?";
            try { id = entity.getBukkitEntity().getUniqueId().toString().substring(0, 8); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[SummonBrainAI] " + entity.getType() + "/" + id + " " + msg);
        } catch (Throwable ignored) {}
    }

    private static void collectCandidates(GlovedPlugin plugin, Player owner, org.bukkit.entity.LivingEntity summon,
                                          Collection<org.bukkit.entity.Entity> nearby,
                                          List<org.bukkit.entity.LivingEntity> out,
                                          boolean defensive, Set<UUID> recentDamagerIds) {
        for (org.bukkit.entity.Entity e : nearby) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le)) continue;
            if (le == owner || le.isDead() || !le.isValid()) continue;
            if (plugin.getSummonTracker() != null && plugin.getSummonTracker().isSummoned(le.getUniqueId())) continue;
            if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
            if (isRelevant(le, owner, summon, defensive, recentDamagerIds) && !out.contains(le)) out.add(le);
        }
    }

    private static void collectRecentDamagers(GlovedPlugin plugin, Player owner, org.bukkit.World world,
                                              Collection<UUID> ids, List<org.bukkit.entity.LivingEntity> out) {
        if (world == null || ids == null) return;
        for (UUID id : ids) {
            try {
                org.bukkit.entity.Entity e = world.getEntity(id);
                if (e instanceof org.bukkit.entity.LivingEntity le && le != owner && !le.isDead() && le.isValid()
                        && !CombatTargetRules.isProtected(plugin, owner, le) && !out.contains(le)) {
                    out.add(le);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static boolean isRelevant(org.bukkit.entity.LivingEntity le, Player owner, org.bukkit.entity.LivingEntity summon,
                                     boolean defensive, Set<UUID> recentDamagerIds) {
        boolean isHostile = le instanceof org.bukkit.entity.Monster
                || le instanceof org.bukkit.entity.Slime
                || le instanceof org.bukkit.entity.Ghast;
        boolean targetsOwner = false;
        boolean targetsSummon = false;
        if (le instanceof org.bukkit.entity.Mob mobEnt && mobEnt.getTarget() != null) {
            targetsOwner = mobEnt.getTarget().getUniqueId().equals(owner.getUniqueId());
            targetsSummon = mobEnt.getTarget().getUniqueId().equals(summon.getUniqueId());
        }
        boolean recent = recentDamagerIds != null && recentDamagerIds.contains(le.getUniqueId());
        if (defensive) {
            return targetsOwner || targetsSummon || recent;
        }
        return isHostile || targetsOwner || targetsSummon || recent;
    }
}
