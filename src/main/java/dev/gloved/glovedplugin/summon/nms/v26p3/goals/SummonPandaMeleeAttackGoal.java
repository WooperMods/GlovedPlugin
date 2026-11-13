package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.panda.Panda;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;

/**
 * CombatPets-style action-gated panda attack, routed through Gloved's SummonMeleeAttackGoal
 * so damage/cooldowns/credits stay in the SummonAI pipeline.
 */
public class SummonPandaMeleeAttackGoal extends SummonMeleeAttackGoal {
    private final Panda panda;

    public SummonPandaMeleeAttackGoal(Panda panda, GlovedPlugin plugin, double speedModifier) {
        super((Mob) panda, plugin, speedModifier);
        this.panda = panda;
    }

    @Override
    public boolean canUse() {
        try {
            if (!this.panda.canPerformAction()) return false;
        } catch (Throwable ignored) {
            // Mapping-safe fallback: if action gate is unavailable, behave as normal melee.
        }
        return super.canUse();
    }
}
