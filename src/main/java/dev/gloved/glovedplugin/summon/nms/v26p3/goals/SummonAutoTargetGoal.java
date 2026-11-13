package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.ai.movement.SummonAIUtils;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * NMS target selector goal using SummonAIUtils HP-aware scoring.
 */
public class SummonAutoTargetGoal extends TargetGoal {
    private final GlovedPlugin plugin;
    private LivingEntity nmsTarget;

    public SummonAutoTargetGoal(Mob mob, GlovedPlugin plugin) {
        super(mob, false);
        this.plugin = plugin;
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    private Player resolveOwner() {
        try {
            if (!(this.mob instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) { return null; }
    }

    @Override
    public boolean canUse() {
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return false;
        org.bukkit.entity.LivingEntity bukkitSummon = (org.bukkit.entity.LivingEntity) this.mob.getBukkitEntity();
        String mode = "AGGRESSIVE";
        try {
            if (plugin.getSoulService() != null) mode = plugin.getSoulService().getSummonCombatMode(owner.getUniqueId());
        } catch (Throwable ignored) {}
        boolean defensive = "DEFENSIVE".equalsIgnoreCase(mode);

        // Build candidates similar to legacy SummonAI scan
        Set<UUID> recentDamagerIds = new HashSet<>(SummonCombatService.getRecentDamagers(owner));
        recentDamagerIds.addAll(SummonCombatService.getRecentDamagers(bukkitSummon));
        List<org.bukkit.entity.LivingEntity> candidates = new ArrayList<>();
        double scanDist = SummonProfile.FOLLOW_RANGE;
        for (org.bukkit.entity.Entity e : owner.getNearbyEntities(scanDist, scanDist, scanDist)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le)) continue;
            if (le == owner || le.isDead() || !le.isValid()) continue;
            if (plugin.getSummonTracker() != null && plugin.getSummonTracker().isSummoned(le.getUniqueId())) continue;
            if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
            if (isRelevant(le, owner, bukkitSummon, defensive, recentDamagerIds)) candidates.add(le);
        }
        for (org.bukkit.entity.Entity e : bukkitSummon.getNearbyEntities(scanDist, scanDist, scanDist)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le)) continue;
            if (le == owner || le.isDead() || !le.isValid()) continue;
            if (plugin.getSummonTracker() != null && plugin.getSummonTracker().isSummoned(le.getUniqueId())) continue;
            if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
            if (isRelevant(le, owner, bukkitSummon, defensive, recentDamagerIds) && !candidates.contains(le)) candidates.add(le);
        }
        for (UUID damagerId : SummonCombatService.getRecentDamagers(owner)) {
            org.bukkit.entity.Entity e = owner.getWorld().getEntity(damagerId);
            if (e instanceof org.bukkit.entity.LivingEntity le && le != owner && !le.isDead() && !CombatTargetRules.isProtected(plugin, owner, le)) {
                if (!candidates.contains(le)) candidates.add(le);
            }
        }
        for (UUID damagerId : SummonCombatService.getRecentDamagers(bukkitSummon)) {
            org.bukkit.entity.Entity e = bukkitSummon.getWorld().getEntity(damagerId);
            if (e instanceof org.bukkit.entity.LivingEntity le && le != owner && !le.isDead() && !CombatTargetRules.isProtected(plugin, owner, le)) {
                if (!candidates.contains(le)) candidates.add(le);
            }
        }

        // Do not target enemies past 32 blocks from the owner.
        candidates.removeIf(le -> {
            try {
                return le.getWorld() != owner.getWorld() || le.getLocation().distanceSquared(owner.getLocation()) > 1024.0D;
            } catch (Throwable t) { return true; }
        });

        org.bukkit.entity.LivingEntity lastBukkit = this.mob.getTarget() != null ? (org.bukkit.entity.LivingEntity) this.mob.getTarget().getBukkitEntity() : null;
        if (lastBukkit != null) {
            try {
                if (lastBukkit.getWorld() != owner.getWorld() || lastBukkit.getLocation().distanceSquared(owner.getLocation()) > 1024.0D) {
                    lastBukkit = null;
                }
            } catch (Throwable ignored) { lastBukkit = null; }
        }
        org.bukkit.entity.LivingEntity best = SummonAIUtils.selectBestTarget(owner, bukkitSummon, candidates, recentDamagerIds, lastBukkit);
        if (best == null) return false;
        try {
            this.nmsTarget = ((org.bukkit.craftbukkit.entity.CraftLivingEntity) best).getHandle();
        } catch (Throwable t) {
            return false;
        }
        this.targetMob = this.nmsTarget;
        return this.targetMob != null;
    }

    private boolean isRelevant(org.bukkit.entity.LivingEntity le, Player owner, org.bukkit.entity.LivingEntity summon,
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

    @Override
    public void start() {
        if (this.nmsTarget != null) {
            try {
                org.bukkit.entity.Entity bSummon = this.mob.getBukkitEntity();
                org.bukkit.entity.Entity bTarget = this.nmsTarget.getBukkitEntity();
                if (bSummon instanceof org.bukkit.entity.Mob bMob && bTarget instanceof org.bukkit.entity.LivingEntity bLiving) {
                    bMob.setTarget(bLiving);
                } else {
                    this.mob.setTarget(this.nmsTarget);
                }
            } catch (Throwable ignored) {
                try { this.mob.setTarget(this.nmsTarget); } catch (Throwable ignored2) {}
            }
        }
        super.start();
    }
}
