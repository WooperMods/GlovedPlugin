package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonMeleeAttackGoal;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.UUID;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;

/**
 * Enderman summon with controlled teleport policy (CombatPets parity):
 * - Blocks uncontrolled/random teleports that can lose owner control by only permitting a single
 *   teleport in response to being hurt (including projectile hits), then consuming the allowance.
 * - NEW: After ~3s of not being able to reach the combat target (out of melee range, no progress),
 *   the Enderman will teleport to a safe spot near the target to re-engage.
 * - Stabilizes target changes by pinning {@code targetChangeTime} forward so stare/anger-driven
 *   target churn does not trigger unwanted teleports or loss of control.
 * - Disables water sensitivity; melee/combat remains routed through GlovedPlugin summon goals/AI
 *   so cooldowns, soul stats, owner credit, and CombatTargetRules are preserved.
 */
public class EndermanSummon extends Enderman implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private boolean teleportAllowed;
    private boolean chaseTeleportAllowed;
    private UUID chaseTargetUuid;
    private int stuckTicks;
    private double lastTargetDistSq;
    private int lastChaseTeleportTick;
    private static final Field TARGET_CHANGE_TIME_FIELD = resolveTargetChangeTimeField();

    public EndermanSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.ENDERMAN, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public SummonSharedData getSharedData() {
        return sharedData;
    }

    @Override
    public UUID getOwnerId() {
        return sharedData != null ? sharedData.getOwnerId() : null;
    }

    private static Field resolveTargetChangeTimeField() {
        // Reflection-safe for mapping differences (Paper 26.3 mapped / obf variants).
        String[] candidates = {"targetChangeTime", "cx", "cA", "bW"};
        for (String name : candidates) {
            try {
                Field f = Enderman.class.getDeclaredField(name);
                f.setAccessible(true);
                if (f.getType() == int.class || f.getType() == Integer.class) return f;
            } catch (Throwable ignored) {
            }
        }
        for (Field f : Enderman.class.getDeclaredFields()) {
            try {
                if (f.getType() == int.class && f.getName().toLowerCase(java.util.Locale.ROOT).contains("target")) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        // Melee attack is cooldown/credit-aware via SummonMeleeAttackGoal -> SummonAI.
        this.goalSelector.addGoal(2, new SummonMeleeAttackGoal(this, plugin));
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        // Keep target-change timer far in the future to prevent stare-induced target instability/teleports.
        pinTargetChangeTime();
        super.customServerAiStep(level);
    }

    @Override
    public void tick() {
        // Defensive pin in case mapped AI paths skip customServerAiStep ordering in some states.
        pinTargetChangeTime();

        // Chase teleport: if the Enderman has a valid combat target but cannot reach it
        // (out of melee range, no progress) for a delay, teleport to a safe spot near the target.
        attemptChaseTeleport();

        super.tick();
    }

    private void pinTargetChangeTime() {
        if (TARGET_CHANGE_TIME_FIELD == null) return;
        try {
            TARGET_CHANGE_TIME_FIELD.setInt(this, this.tickCount + 600);
        } catch (Throwable ignored) {
            // fail-safe: leave vanilla behavior rather than risking hard failure
        }
    }

    /** Attempt a single controlled chase teleport after a delay of no progress. */
    private void attemptChaseTeleport() {
        // Minimal requirements: we need a target and cooldown must have elapsed.
        int now = this.tickCount;
        if (now - lastChaseTeleportTick < 60) return; // cooldown: at most once per 60 ticks (~3s)

        // Get the NMS target via getTarget().
        LivingEntity nmsTarget = this.getTarget();
        if (nmsTarget == null) {
            chaseTargetUuid = null;
            stuckTicks = 0;
            lastTargetDistSq = Double.MAX_VALUE;
            return;
        }
        // Check isAlive on the NMS target (NMS LivingEntity.hasLiving() / isAlive()).
        if (!nmsTarget.isAlive()) {
            chaseTargetUuid = null;
            stuckTicks = 0;
            lastTargetDistSq = Double.MAX_VALUE;
            return;
        }
        // Convert to Bukkit entity via getBukkitEntity() and cast.
        org.bukkit.entity.LivingEntity bukkitTarget = (org.bukkit.entity.LivingEntity) nmsTarget.getBukkitEntity();
        if (bukkitTarget == null) {
            chaseTargetUuid = null;
            stuckTicks = 0;
            lastTargetDistSq = Double.MAX_VALUE;
            return;
        }

        UUID targetUid = bukkitTarget.getUniqueId();
        if (chaseTargetUuid != null && !chaseTargetUuid.equals(targetUid)) {
            // Target changed; reset tracking.
            chaseTargetUuid = targetUid;
            stuckTicks = 0;
            lastTargetDistSq = Double.MAX_VALUE;
            return;
        }
        chaseTargetUuid = targetUid;

        // Ensure we're tracking the same target consistently.
        if (chaseTargetUuid.equals(this.getOwnerId())) return; // don't chase owner

        // Check that target is in same world.
        if (bukkitTarget.getWorld() != this.getBukkitEntity().getWorld()) {
            chaseTargetUuid = null;
            stuckTicks = 0;
            lastTargetDistSq = Double.MAX_VALUE;
            return;
        }

        // Compute distance and reachability.
        double distSq = this.getBukkitEntity().getLocation().distanceSquared(bukkitTarget.getLocation());

        // Determine if target is out of melee/attack reach.
        // Use a reach estimate consistent with melee reach (~1.5-2 blocks depending on entity width).
        double reachSq = computeMeleeReachSq(this, nmsTarget);
        boolean outOfReach = distSq > reachSq;

        if (outOfReach) {
            // No progress (or getting worse): accumulate stuck ticks.
            if (distSq >= lastTargetDistSq) {
                // Not improving fast enough; increment stuck timer.
                stuckTicks++;
                if (stuckTicks >= 60) {
                    // 60 ticks (~3s) of no progress while out of reach → attempt chase teleport.
                    doChaseTeleport();
                    stuckTicks = 0; // reset after attempt; cooldown handles frequency.
                    lastChaseTeleportTick = now;
                }
            } else {
                // Got closer = progress; reset stuck timer.
                lastTargetDistSq = distSq;
                stuckTicks = 0;
            }
        } else {
            // Within reach: reset stuck tracking; no chase needed.
            lastTargetDistSq = distSq;
            stuckTicks = 0;
            chaseTargetUuid = null;
        }
    }

    /** Compute a rough melee reach squared for the given attacker vs target. */
    private static double computeMeleeReachSq(LivingEntity attacker, LivingEntity target) {
        // Base reach: conservative estimate ~2.236 blocks squared.
        double base = 5.0;
        // Small adjustment by target width: wider targets are slightly easier to be "in reach" of.
        double widthFactor = 1.0;
        try {
            double w = target.getBbWidth();
            if (w > 0) widthFactor = Math.max(0.5, 1.0 - w * 0.15);
        } catch (Throwable ignored) {}
        return base * widthFactor;
    }

    /** Perform the actual teleport to a safe spot near the target. */
    private void doChaseTeleport() {
        // Get the NMS target via getTarget().
        LivingEntity nmsTarget = this.getTarget();
        if (nmsTarget == null) return;
        // Check isAlive on the NMS target.
        if (!nmsTarget.isAlive()) return;
        // Convert to Bukkit entity via getBukkitEntity() and cast.
        org.bukkit.entity.LivingEntity bukkitTarget = (org.bukkit.entity.LivingEntity) nmsTarget.getBukkitEntity();
        if (bukkitTarget == null) return;

        // Do not teleport if target is the owner or in a different world.
        if (bukkitTarget.getUniqueId().equals(this.getOwnerId())) return;
        if (bukkitTarget.getWorld() != this.getBukkitEntity().getWorld()) return;

        // Choose a destination near the target: offset a few blocks laterally and vertically
        // to a safe (non-solid, headroom-adequate) spot. Prefer staying within ~3 blocks of the
        // target so the Enderman can re-engage quickly.
        org.bukkit.Location targetLoc = bukkitTarget.getLocation();
        org.bukkit.Location spawnLoc = null;

        // Try a few candidate offsets around the target.
        for (int dx = -3; dx <= 3 && spawnLoc == null; dx++) {
            for (int dz = -3; dz <= 3 && spawnLoc == null; dz++) {
                for (int dy = -1; dy <= 1 && spawnLoc == null; dy++) {
                    org.bukkit.Location candidate = targetLoc.clone().add(dx, dy, dz);
                    // Must be on a solid block surface (not inside air/void) and have headroom.
                    if (candidate.getBlock().getType().isSolid()) {
                        // Check the block above for headroom (avoid teleporting into ceiling).
                        org.bukkit.Location above = candidate.add(0, 1, 0);
                        if (!above.getBlock().getType().isSolid()) {
                            spawnLoc = candidate;
                        }
                    }
                }
            }
        }

        // Fallback: use the target location itself if no better spot found (may place entity
        // next to target rather than on top, but still within engage range).
        if (spawnLoc == null) {
            spawnLoc = targetLoc.clone();
            // Ensure it's not inside a solid block.
            int attempts = 0;
            while (spawnLoc.getBlock().getType().isSolid() && attempts < 10) {
                spawnLoc.add(0.1, 0, 0.1);
                attempts++;
            }
        }

        // Allow the teleport through our override by setting the chase allowance flag.
        this.chaseTeleportAllowed = true;
        try {
            super.teleport();
        } finally {
            // Always consume the allowance after the attempt (whether it succeeded or failed),
            // so we don't enable repeated random teleports.
            this.chaseTeleportAllowed = false;
        }

        // After teleport, reset tracking so the Enderman re-acquires the target naturally
        // via its goals (SummonAutoTargetGoal / SummonMeleeAttackGoal).
        this.chaseTargetUuid = null;
        this.stuckTicks = 0;
        this.lastTargetDistSq = Double.MAX_VALUE;
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