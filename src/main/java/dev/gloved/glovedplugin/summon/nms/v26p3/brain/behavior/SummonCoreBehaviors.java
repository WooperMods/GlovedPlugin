package dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior;

import dev.gloved.glovedplugin.summon.nms.v26p3.brain.behavior.impl.SummonMoveToTarget;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.LookAtTargetSink;
import net.minecraft.world.entity.ai.behavior.Swim;
import org.jetbrains.annotations.NotNull;

public final class SummonCoreBehaviors {
    private SummonCoreBehaviors() {}

    @NotNull
    public static BehaviorControl<Mob> lookAtTarget() {
        return new LookAtTargetSink(45, 90);
    }

    @NotNull
    public static BehaviorControl<Mob> moveToTarget() {
        return new SummonMoveToTarget();
    }

    @NotNull
    public static BehaviorControl<Mob> swim() {
        return new Swim<>(0.8F);
    }
}
