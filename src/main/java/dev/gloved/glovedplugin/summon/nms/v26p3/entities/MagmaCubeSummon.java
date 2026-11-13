package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonSlimeAttackGoal;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;

import java.util.UUID;

public class MagmaCubeSummon extends MagmaCube implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;
    private SummonSlimeAttackGoal slimeAttackGoal;

    public MagmaCubeSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.MAGMA_CUBE, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        // CombatPets intent: auto-target, float, follow owner, slime follow-target/contact attack goal
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.slimeAttackGoal = new SummonSlimeAttackGoal(this, plugin);
        this.goalSelector.addGoal(3, this.slimeAttackGoal);
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    private org.bukkit.entity.Player resolveOwnerBukkit() {
        try {
            UUID id = getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private void tryContactDamage(LivingEntity target) {
        if (target == null || !target.isAlive()) return;
        org.bukkit.entity.Player owner = resolveOwnerBukkit();
        if (owner == null || !owner.isOnline()) return;
        try {
            LivingEntity currentTarget = this.getTarget();
            if (currentTarget == null || currentTarget != target) return;
            org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
            if (CombatTargetRules.isProtected(plugin, owner, bTarget)) return;
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) this.getBukkitEntity();
            long now = Bukkit.getCurrentTick();
            SummonAI ai = plugin != null ? plugin.getSummonAI() : null;
            if (ai == null || !ai.tryConsumeAttackCooldown(bSummon, owner, now)) return;
            ai.applySummonHit(bSummon, owner, bTarget, 0.0, now, null);
        } catch (Throwable ignored) {}
    }

    // Mapping-safe hook points (signatures vary across 26.3 mappings). Contact combat is
    // handled reliably by SummonSlimeAttackGoal's cooldown-gated proximity check to avoid
    // duplicate damage; these hooks are best-effort parity and never apply direct damage.
    public void push(Entity entity) {
        try { super.push(entity); } catch (Throwable ignored) {}
    }

    protected void doPush(Entity entity) {
        try { push(entity); } catch (Throwable ignored) {}
    }

    public void playerTouch(Player player) {
        try { super.playerTouch(player); } catch (Throwable ignored) {}
    }

    protected boolean isDealsDamage() {
        return true;
    }

    protected boolean isDealsDamageSafe() {
        return isDealsDamage();
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
