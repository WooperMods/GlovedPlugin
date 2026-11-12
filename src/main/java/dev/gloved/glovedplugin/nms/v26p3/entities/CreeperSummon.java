package dev.gloved.glovedplugin.nms.v26p3.entities;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonChaseTargetGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.SummonAI;
import dev.gloved.glovedplugin.summon.SummonProfile;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CreeperSummon extends Creeper implements SummonEntity {
    private static final int EXPLODE_COOLDOWN_TICKS = 60;
    private static final int SWELL_START_RANGE_SQ = 9; // vanilla swell activation ~3 blocks
    private static final double BASE_EXPLOSION_RADIUS = 3.0D;
    private static final double POWERED_RADIUS_MULTIPLIER = 2.0D;

    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private int explodeCooldown = 0;

    public CreeperSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.CREEPER, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        try { this.maxSwell = 60; } catch (Throwable ignored) {}
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(4, new SummonChaseTargetGoal(this, plugin, 1.0D, 2.0D));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        try {
            if (this.isAlive()) {
                LivingEntity target = this.getTarget();
                if (target == null || !target.isAlive()) {
                    resetSwellState(false);
                }

                if (this.explodeCooldown > 0) {
                    this.explodeCooldown--;
                } else {
                    syncOldSwell();
                    updateSwellDirection(target);

                    int swellDir = this.getSwellDir();
                    if (swellDir > 0 && this.swell == 0) {
                        try { this.playSound(SoundEvents.CREEPER_PRIMED, 1.0f, 0.5f); } catch (Throwable ignored) {}
                        try { this.gameEvent(GameEvent.PRIME_FUSE); } catch (Throwable ignored) {}
                    }

                    this.swell += swellDir;
                    if (this.swell < 0) this.swell = 0;

                    int max = getMaxSwellSafe();
                    if (this.swell >= max) {
                        explodeCreeperSummon();
                        this.explodeCooldown = EXPLODE_COOLDOWN_TICKS;
                        resetSwellState(true);
                        GlovedPlugin.logDebug("[SummonCreeper] Exploded and entered cooldown for " + getBukkitUniqueId()
                                + " owner=" + (getOwnerId() != null ? getOwnerId() : "none"));
                    }
                }
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonCreeper] Tick error for " + getBukkitUniqueId() + ": " + t);
        }
        super.tick();
    }

    private void updateSwellDirection(LivingEntity target) {
        try {
            if (this.isIgnited()) {
                this.setSwellDir(1);
                return;
            }
            if (target == null || !target.isAlive()) {
                this.setSwellDir(-1);
                return;
            }
            Player owner = resolveOwner();
            if (owner != null) {
                try {
                    org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
                    if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                        this.setSwellDir(-1);
                        return;
                    }
                } catch (Throwable ignored) {}
            }
            double distSq = this.distanceToSqr(target);
            if (distSq <= SWELL_START_RANGE_SQ) {
                this.setSwellDir(1);
            } else {
                // Let swell decay when out of range (CombatPets-style controlled state)
                this.setSwellDir(-1);
            }
        } catch (Throwable t) {
            try { this.setSwellDir(-1); } catch (Throwable ignored) {}
        }
    }

    private void resetSwellState(boolean clearTarget) {
        try { this.setSwellDir(-1); } catch (Throwable ignored) {}
        try { this.swell = 0; } catch (Throwable ignored) {}
        try { syncOldSwell(); } catch (Throwable ignored) {}
        if (clearTarget) {
            try { this.setTarget(null); } catch (Throwable ignored) {}
        }
    }

    private int getMaxSwellSafe() {
        try { return Math.max(1, this.maxSwell); } catch (Throwable ignored) {}
        return 30;
    }

    private void syncOldSwell() {
        // Best-effort: Mojmap field name is typically oldSwell in recent versions.
        try {
            Field f = Creeper.class.getDeclaredField("oldSwell");
            f.setAccessible(true);
            f.setInt(this, this.swell);
        } catch (Throwable ignored) {
            // Non-fatal: visual interpolation only.
        }
    }

    private Player resolveOwner() {
        try {
            UUID id = getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private void explodeCreeperSummon() {
        try {
            if (this.level().isClientSide()) return;
        } catch (Throwable ignored) {}

        Player owner = resolveOwner();
        SummonAI ai = plugin.getSummonAI();
        org.bukkit.entity.LivingEntity bukkitSummon = (org.bukkit.entity.LivingEntity) this.getBukkitEntity();
        Location center = bukkitSummon.getLocation();
        if (center.getWorld() == null) return;

        float radius = (float) (BASE_EXPLOSION_RADIUS * (isPoweredSafe() ? POWERED_RADIUS_MULTIPLIER : 1.0D));
        long now = Bukkit.getCurrentTick();

        GlovedPlugin.logDebug("[SummonCreeper] Triggering explosion at " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ()
                + " radius=" + radius + " powered=" + isPoweredSafe() + " owner=" + (owner != null ? owner.getName() : "null")
                + " ownerId=" + getOwnerId() + " ai=" + (ai != null));

        // Non-griefing visual/sound effect (no block damage, no vanilla entity damage routing).
        try {
            center.getWorld().spawnParticle(Particle.EXPLOSION, center, 1, 0.0, 0.0, 0.0, 0.0);
            center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f);
        } catch (Throwable ignored) {}

        // Owner-credited, rule-filtered damage (integrates with Gloved progression/ability pipeline when owner/AI available).
        double radiusSq = (double) radius * (double) radius;
        List<org.bukkit.entity.LivingEntity> victims = new ArrayList<>();
        try {
            for (org.bukkit.entity.Entity e : bukkitSummon.getNearbyEntities(radius + 1.0, radius + 1.0, radius + 1.0)) {
                if (!(e instanceof org.bukkit.entity.LivingEntity le)) continue;
                if (le.isDead() || !le.isValid()) continue;
                if (le.getUniqueId().equals(bukkitSummon.getUniqueId())) continue;
                if (le.getWorld() != center.getWorld()) continue;
                double dsq = le.getLocation().distanceSquared(center);
                if (dsq > radiusSq) continue;
                if (owner != null) {
                    if (le.getUniqueId().equals(owner.getUniqueId())) continue;
                    try {
                        if (plugin.getSummonTracker() != null) {
                            UUID otherOwner = plugin.getSummonTracker().getOwner(le.getUniqueId());
                            if (owner.getUniqueId().equals(otherOwner)) continue; // allied summon
                        }
                    } catch (Throwable ignored) {}
                    boolean protectedTarget = false;
                    try { protectedTarget = CombatTargetRules.isProtected(plugin, owner, le); } catch (Throwable ignored) {}
                    if (protectedTarget) {
                        GlovedPlugin.logDebug("[SummonCreeper] Explosion skipped protected target=" + le.getUniqueId() + " type=" + le.getType());
                        continue;
                    }
                } else {
                    // No owner online/resolved: fail safe, do not damage protected/unknown targets.
                    GlovedPlugin.logDebug("[SummonCreeper] Explosion skipped target (owner unresolved)=" + le.getUniqueId() + " type=" + le.getType());
                    continue;
                }
                victims.add(le);
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonCreeper] Victim scan failed: " + t);
        }
        GlovedPlugin.logDebug("[SummonCreeper] Explosion victim count=" + victims.size() + " ownerOnline=" + (owner != null && owner.isOnline()));

        String profileName = sharedData != null ? sharedData.getProfileName() : null;
        for (org.bukkit.entity.LivingEntity victim : victims) {
            try {
                double before = 0.0;
                try { before = victim.isDead() ? 0.0 : victim.getHealth(); } catch (Throwable ignored) {}
                boolean damaged = false;
                if (owner != null && ai != null) {
                    double dealt = ai.applySummonHit(bukkitSummon, owner, victim, 0.0, now, profileName);
                    damaged = dealt > 0.0 || victim.isDead();
                    GlovedPlugin.logDebug("[SummonCreeper] Explosion hit victim=" + victim.getUniqueId() + " type=" + victim.getType()
                            + " owner=" + owner.getName() + " dealt=" + dealt + " hpBefore=" + before
                            + " hpAfter=" + (victim.isDead() ? 0.0 : safeHealth(victim)));
                } else if (owner != null) {
                    // Fallback (should be rare): summon-sourced generic damage without progression hooks.
                    org.bukkit.damage.DamageSource src = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.EXPLOSION)
                            .withDirectEntity(bukkitSummon)
                            .withCausingEntity(bukkitSummon)
                            .build();
                    try { if (victim.getNoDamageTicks() > 0) victim.setNoDamageTicks(0); } catch (Throwable ignored) {}
                    try {
                        dev.gloved.glovedplugin.summon.DamageProxy.beginPipelineDamage();
                        victim.damage(4.0, src);
                    } finally {
                        dev.gloved.glovedplugin.summon.DamageProxy.endPipelineDamage();
                    }
                    damaged = victim.isDead() || safeHealth(victim) < before - 1.0e-6;
                    GlovedPlugin.logDebug("[SummonCreeper] Explosion fallback damage applied victim=" + victim.getUniqueId() + " type=" + victim.getType()
                            + " owner=" + owner.getName() + " hpBefore=" + before
                            + " hpAfter=" + (victim.isDead() ? 0.0 : safeHealth(victim)) + " damaged=" + damaged);
                } else {
                    GlovedPlugin.logDebug("[SummonCreeper] Explosion could not apply damage (owner/ai unavailable) victim=" + victim.getUniqueId());
                }
                applyExplosionKnockback(victim, center, radius);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SummonCreeper] Failed to damage victim " + victim.getUniqueId() + ": " + t);
            }
        }

        // Charged creeper lingering cloud parity (best-effort, summon-safe context).
        try {
            if (isPoweredSafe()) {
                invokeSpawnLingeringCloud();
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonCreeper] Lingering cloud spawn failed: " + t);
        }

        // Post-explosion knockback/state reset so summon remains usable in Gloved lifecycle.
        try {
            Vector dir = bukkitSummon.getLocation().getDirection();
            if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
            dir.normalize().multiply(-0.4).setY(0.25);
            bukkitSummon.setVelocity(dir);
        } catch (Throwable ignored) {}
    }

    private void applyExplosionKnockback(org.bukkit.entity.LivingEntity victim, Location center, float radius) {
        try {
            Location vLoc = victim.getLocation();
            Vector delta = vLoc.toVector().subtract(center.toVector());
            double dist = Math.max(0.001, delta.length());
            double strength = Math.max(0.0, 1.0 - (dist / Math.max(0.001, radius)));
            // target.knockback(strength, x, z) expects vector pointing victim -> source and subtracts it,
            // so invert to push away from explosion center.
            Vector away = delta.multiply(1.0 / dist);
            double kb = 0.35 + (0.65 * strength);
            victim.knockback(kb, -away.getX(), -away.getZ());
        } catch (Throwable ignored) {}
    }

    @Override
    public void explodeCreeper() {
        // Controlled summon-safe explosion path; never perform vanilla creeper explosion.
        explodeCreeperSummon();
    }

    private boolean isPoweredSafe() {
        try { return this.isPowered(); } catch (Throwable t) { return false; }
    }

    private static double safeHealth(org.bukkit.entity.LivingEntity le) {
        try { return le.isDead() ? 0.0 : le.getHealth(); } catch (Throwable t) { return -1.0; }
    }

    private void invokeSpawnLingeringCloud() {
        // Mojmap name in modern versions; keep reflective fallback for mapping differences.
        Throwable last = null;
        for (String name : new String[]{"spawnLingeringCloud", "gK"}) {
            try {
                Method m = Creeper.class.getDeclaredMethod(name);
                m.setAccessible(true);
                m.invoke(this);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) throw new RuntimeException(last);
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
