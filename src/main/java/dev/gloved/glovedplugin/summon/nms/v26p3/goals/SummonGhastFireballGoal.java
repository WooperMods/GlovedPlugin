package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.UUID;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

/**
 * CombatPets-parity ghast fireball attack, integrated with Gloved summon cooldown/target rules.
 * Spawns a non-griefing, summon-owned LargeFireball; damage crediting is handled by the summon pipeline.
 */
public class SummonGhastFireballGoal extends Goal {
    private final Ghast ghast;
    private final GlovedPlugin plugin;
    private int chargeTime;

    public SummonGhastFireballGoal(Ghast ghast, GlovedPlugin plugin) {
        this.ghast = ghast;
        this.plugin = plugin;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    private Player resolveOwner() {
        try {
            if (!(ghast instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean canUse() {
        LivingEntity target = ghast.getTarget();
        return target != null && target.isAlive();
    }

    @Override
    public void start() {
        this.chargeTime = 0;
    }

    @Override
    public void stop() {
        this.chargeTime = 0;
        try { ghast.setCharging(false); } catch (Throwable ignored) {}
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = ghast.getTarget();
        if (target == null) return;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return;
        try {
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) {
                ghast.setTarget(null);
                return;
            }
        } catch (Throwable ignored) {}

        double distSq = target.distanceToSqr(ghast);
        boolean inRange = distSq < 4096.0D;
        boolean hasLos = false;
        try { hasLos = ghast.hasLineOfSight(target); } catch (Throwable ignored) { hasLos = inRange; }

        if (inRange && hasLos) {
            Level world = ghast.level();
            ++this.chargeTime;

            // Charge telegraph (CombatPets parity)
            if (this.chargeTime == 10 && !ghast.isSilent()) {
                try { world.levelEvent(null, 1015, ghast.blockPosition(), 0); } catch (Throwable ignored) {}
            }

            if (this.chargeTime == 20) {
                // Gate and consume at fire time for reliable cooldown/APS behavior.
                // Impact conversion is marked consumed so DamageProxy applies computed damage without re-consuming.
                SummonAI ai = plugin != null ? plugin.getSummonAI() : null;
                org.bukkit.entity.LivingEntity bSummon = null;
                try { bSummon = (org.bukkit.entity.LivingEntity) ghast.getBukkitEntity(); } catch (Throwable ignored) {}
                long now = Bukkit.getCurrentTick();
                boolean ready = ai == null || bSummon == null || ai.tryConsumeAttackCooldown(bSummon, owner, now);
                if (ready) {
                    fireAt(target, world, owner, bSummon, now);
                    GlovedPlugin.logDebug("[SummonGhast] Fireball launched by " + ghast.getBukkitEntity().getUniqueId()
                            + " owner=" + owner.getName() + " target=" + target.getBukkitEntity().getUniqueId() + " cdConsumedAtFire=true");
                    this.chargeTime = -20;
                } else {
                    // Hold near fire-ready until cooldown permits, while keeping charging telegraph bounded.
                    this.chargeTime = 19;
                }
            }
        } else if (this.chargeTime > 0) {
            --this.chargeTime;
        }

        try { ghast.setCharging(this.chargeTime > 10); } catch (Throwable ignored) {}
    }

    private void fireAt(LivingEntity target, Level world, Player owner, org.bukkit.entity.LivingEntity bSummon, long now) {
        try {
            double dirMod = 4.0D;
            Vec3 view = ghast.getViewVector(1.0F);
            double dx = target.getX() - (ghast.getX() + view.x * dirMod);
            double dy = target.getY(0.5D) - (0.5D + ghast.getY(0.5D));
            double dz = target.getZ() - (ghast.getZ() + view.z * dirMod);
            if (!ghast.isSilent()) {
                try { world.levelEvent(null, 1016, ghast.blockPosition(), 0); } catch (Throwable ignored) {}
            }

            Vec3 shoot = new Vec3(dx, dy, dz).normalize();
            // Summon-owned, non-griefing fireball: zero explosion power/yield.
            LargeFireball fireball = new LargeFireball(world, ghast, shoot, 0);
            try { fireball.explosionPower = 0; } catch (Throwable ignored) {}
            try { fireball.bukkitYield = 0.0F; } catch (Throwable ignored) {}
            try {
                Method setIncendiary = LargeFireball.class.getMethod("setIncendiary", boolean.class);
                setIncendiary.invoke(fireball, false);
            } catch (Throwable ignored) {}
            fireball.setPos(ghast.getX() + view.x * dirMod, ghast.getY(0.5D) + 0.5D, ghast.getZ() + view.z * dirMod);
            // Attribution hardening: tag projectile and mark cooldown consumed at fire for APS/converted damage.
            try {
                org.bukkit.entity.Entity bProj = fireball.getBukkitEntity();
                if (bProj instanceof org.bukkit.entity.Projectile proj) {
                    if (bSummon != null) {
                        try { proj.setShooter(bSummon); } catch (Throwable ignored) {}
                    } else {
                        try { proj.setShooter((org.bukkit.entity.LivingEntity) ghast.getBukkitEntity()); } catch (Throwable ignored) {}
                    }
                    java.util.UUID ownerId = owner != null ? owner.getUniqueId() : null;
                    java.util.UUID minionId = bSummon != null ? bSummon.getUniqueId() : null;
                    if (ownerId == null || minionId == null) {
                        if (ghast instanceof SummonEntity se) {
                            if (ownerId == null) ownerId = se.getOwnerId();
                            if (minionId == null) minionId = se.getBukkitUniqueId();
                        }
                    }
                    DamageProxy.tagSummonProjectile(proj, plugin, ownerId, minionId);
                    DamageProxy.markProjectileCooldownConsumed(proj, plugin);
                }
            } catch (Throwable ignored) {}
            world.addFreshEntity(fireball);
            if (DebugLog.isEnabled()) {
                try {
                    GlovedPlugin.logDebug("[Summon] Ghast fireball spawned minion=" + (bSummon != null ? bSummon.getUniqueId() : ghast.getBukkitEntity().getUniqueId())
                            + " owner=" + (owner != null ? owner.getUniqueId() : "null") + " cdConsumedAtFire=true tick=" + now);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            // Fail-safe: do nothing if projectile construction/spawn fails on this mapping.
        }
    }
}
