package dev.gloved.glovedplugin.nms.v26p3.goals;

import dev.gloved.glovedplugin.GlovedPlugin;
import net.minecraft.world.entity.animal.fox.Fox;

/**
 * Fox attack goal gated by fox states (no attack while sitting/sleeping/crouching/faceplanted),
 * mirroring CombatPets FoxPet.FoxAttackGoal while using Gloved's SummonAI damage pipeline.
 */
public class SummonFoxMeleeAttackGoal extends SummonMeleeAttackGoal {
    private final Fox fox;

    public SummonFoxMeleeAttackGoal(Fox fox, GlovedPlugin plugin, double speedModifier) {
        super(fox, plugin, speedModifier);
        this.fox = fox;
    }

    @Override
    public void start() {
        try { fox.setIsInterested(false); } catch (Throwable ignored) {}
        super.start();
    }

    @Override
    public boolean canUse() {
        try {
            if (fox.isSitting() || fox.isSleeping() || fox.isCrouching() || fox.isFaceplanted()) {
                return false;
            }
        } catch (Throwable ignored) {}
        return super.canUse();
    }
}
