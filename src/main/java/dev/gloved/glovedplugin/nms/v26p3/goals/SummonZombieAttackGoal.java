package dev.gloved.glovedplugin.nms.v26p3.goals;

import dev.gloved.glovedplugin.GlovedPlugin;
import net.minecraft.world.entity.Mob;

/**
 * Zombie-style melee attack goal: same as SummonMeleeAttackGoal but raises arms while aggressive,
 * matching CombatPets PetZombieAttackGoal behavior for zombie-family summons.
 */
public class SummonZombieAttackGoal extends SummonMeleeAttackGoal {
    private int raiseArmTicks;

    public SummonZombieAttackGoal(Mob mob, GlovedPlugin plugin) {
        super(mob, plugin, 1.0D);
    }

    public SummonZombieAttackGoal(Mob mob, GlovedPlugin plugin, double speedModifier) {
        super(mob, plugin, speedModifier);
    }

    @Override
    public void start() {
        super.start();
        this.raiseArmTicks = 0;
    }

    @Override
    public void stop() {
        super.stop();
        try { this.mob.setAggressive(false); } catch (Throwable ignored) {}
    }

    @Override
    public void tick() {
        super.tick();
        ++this.raiseArmTicks;
        try {
            // Aggressive/arm-raising window similar to vanilla zombie attack pacing.
            this.mob.setAggressive(this.raiseArmTicks >= 5);
        } catch (Throwable ignored) {}
    }
}
