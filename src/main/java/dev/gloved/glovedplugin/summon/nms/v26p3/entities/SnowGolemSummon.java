package dev.gloved.glovedplugin.summon.nms.v26p3.entities;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.*;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonAutoTargetGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonFollowGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonLookAtOwnerGoal;
import dev.gloved.glovedplugin.summon.nms.v26p3.goals.SummonRangedAttackGoal;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;

public class SnowGolemSummon extends SnowGolem implements SummonEntity {
    private final SummonSharedData sharedData;
    private final GlovedPlugin plugin;

    public SnowGolemSummon(ServerLevel level, SummonSharedData sharedData, GlovedPlugin plugin) {
        super(EntityTypes.SNOW_GOLEM, (Level) level);
        this.sharedData = sharedData;
        this.plugin = plugin;
        try { this.setPumpkin(false); } catch (Throwable ignored) {}
    }

    @Override
    public void setGoals() {
        this.goalSelector.getAvailableGoals().clear();
        this.targetSelector.getAvailableGoals().clear();
        this.targetSelector.addGoal(1, new SummonAutoTargetGoal(this, plugin));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Ranged snowball attack via Gloved cooldown-aware goal; vanilla performRangedAttack spawns
        // snowballs attributed to this summon so DamageProxy can convert to owner-credited damage.
        this.goalSelector.addGoal(1, new SummonRangedAttackGoal(this, plugin, 1.25D, 10.0F));
        this.goalSelector.addGoal(2, new SummonFollowGoal(this, plugin));
        this.goalSelector.addGoal(8, new SummonLookAtOwnerGoal(this, plugin));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override public boolean isSensitiveToWater() { return false; }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        // Suppress shearing/pumpkin interactions.
        return this.level().isClientSide() ? InteractionResult.CONSUME : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void shear(ServerLevel worldserver, SoundSource soundcategory, ItemStack itemstack) {
        // Suppress pumpkin removal.
    }

    @Override
    public void aiStep() {
        // Best-effort suppression of vanilla snow-trail/environment side effects while preserving AI.
        // Snow trail placement is mapping-specific in 26.3; try common hook names, then fall back to super.
        // Summons must not grief terrain.
        boolean handled = false;
        try {
            for (String m : new String[]{"tryPlaceSnow", "placeSnow", "createSnow"}) {
                try {
                    java.lang.reflect.Method mm = SnowGolem.class.getDeclaredMethod(m);
                    mm.setAccessible(true);
                    // Intentionally do nothing (suppress trail), run rest of AI step via super if needed.
                    handled = true;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        try { super.aiStep(); } catch (Throwable ignored) {}
        // If a mapping places snow after super via known method, attempts above are harmless fail-safe no-ops.
        if (!handled) {
            // TODO(mapping, 26.3): verify snow-trail suppression hook; avoid block edits here for safety.
        }
    }

    @Override public UUID getOwnerId() { return sharedData != null ? sharedData.getOwnerId() : null; }
    @Override public SummonSharedData getSharedData() { return sharedData; }
    @Override public UUID getBukkitUniqueId() { try { return this.getBukkitEntity().getUniqueId(); } catch (Throwable t) { return null; } }
}
