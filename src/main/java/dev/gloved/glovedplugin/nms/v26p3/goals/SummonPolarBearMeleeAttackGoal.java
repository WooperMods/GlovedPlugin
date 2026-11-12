package dev.gloved.glovedplugin.nms.v26p3.goals;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.SummonAI;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * CombatPets-style polar bear warning/stand attack, but damage/credit/cooldowns go through
 * Gloved's SummonAI pipeline (no direct NMS hurt calls).
 */
public class SummonPolarBearMeleeAttackGoal extends SummonMeleeAttackGoal {
    private final PolarBear bear;
    private final GlovedPlugin plugin;

    public SummonPolarBearMeleeAttackGoal(PolarBear bear, GlovedPlugin plugin) {
        super((Mob) bear, plugin, 1.25D);
        this.bear = bear;
        this.plugin = plugin;
    }

    private Player resolveOwner() {
        try {
            if (!(bear instanceof dev.gloved.glovedplugin.summon.entity.SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) { return null; }
    }

    private void playWarningSoundSafe() {
        try {
            Method m = PolarBear.class.getDeclaredMethod("playWarningSound");
            m.setAccessible(true);
            m.invoke(bear);
        } catch (Throwable ignored) {
            // Mapping/protection differences: warning sound is cosmetic.
        }
    }

    @Override
    public void tick() {
        LivingEntity target = this.bear.getTarget();
        if (target == null) return;
        Player owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return;

        try { bear.getLookControl().setLookAt(target, 30.0F, 30.0F); } catch (Throwable ignored) {}
        double distSq = this.bear.distanceToSqr(target);
        double reach = getAttackReachSqr(target);

        // Maintain chase/pathing toward target.
        try {
            if (distSq > reach) {
                bear.getNavigation().moveTo(target, 1.25D);
            }
        } catch (Throwable ignored) {}

        if (distSq <= reach) {
            performPipelineAttack(owner, target);
            try { bear.setStanding(false); } catch (Throwable ignored) {}
            return;
        }

        // Warning zone: stand and play warning sound while winding up, mirroring CombatPets.
        double warnRangeSq = (target.getBbWidth() + 3.0F) * (target.getBbWidth() + 3.0F);
        if (distSq < warnRangeSq) {
            long now = Bukkit.getCurrentTick();
            org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) bear.getBukkitEntity();
            SummonAI ai = plugin.getSummonAI();
            if (ai != null && ai.canAttackNow(bSummon, owner, now)) {
                try { bear.setStanding(true); } catch (Throwable ignored) {}
                playWarningSoundSafe();
            } else {
                try { bear.setStanding(false); } catch (Throwable ignored) {}
            }
            return;
        }

        try { bear.setStanding(false); } catch (Throwable ignored) {}
    }

    private void performPipelineAttack(Player owner, LivingEntity target) {
        long now = Bukkit.getCurrentTick();
        org.bukkit.entity.LivingEntity bSummon = (org.bukkit.entity.LivingEntity) bear.getBukkitEntity();
        org.bukkit.entity.LivingEntity bTarget = (org.bukkit.entity.LivingEntity) target.getBukkitEntity();
        SummonAI ai = plugin.getSummonAI();
        if (ai != null) {
            ai.performSummonAttack(bSummon, owner, bTarget, now, SummonAI.AttackOptions.meleePipeline());
        }
    }

    @Override
    public void stop() {
        try { bear.setStanding(false); } catch (Throwable ignored) {}
        super.stop();
    }
}
