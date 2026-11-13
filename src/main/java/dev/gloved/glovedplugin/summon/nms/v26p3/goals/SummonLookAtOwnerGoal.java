package dev.gloved.glovedplugin.summon.nms.v26p3.goals;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.entity.SummonEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.UUID;

public class SummonLookAtOwnerGoal extends Goal {
    private final Mob mob;
    private final GlovedPlugin plugin;
    private Player owner;
    private int lookTime;

    public SummonLookAtOwnerGoal(Mob mob, GlovedPlugin plugin) {
        this.mob = mob;
        this.plugin = plugin;
        this.setFlags(EnumSet.of(Flag.LOOK));
    }

    private Player resolveOwner() {
        try {
            if (!(mob instanceof SummonEntity se)) return null;
            UUID id = se.getOwnerId();
            return id == null ? null : Bukkit.getPlayer(id);
        } catch (Throwable t) { return null; }
    }

    @Override
    public boolean canUse() {
        owner = resolveOwner();
        if (owner == null || !owner.isOnline()) return false;
        if (mob.getBukkitEntity().getWorld() != owner.getWorld()) return false;
        if (mob.getRandom().nextFloat() >= 0.02F) return false;
        lookTime = 40 + mob.getRandom().nextInt(40);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isOnline() && lookTime-- > 0;
    }

    @Override
    public void tick() {
        if (owner == null) return;
        try { mob.getLookControl().setLookAt(owner.getX(), owner.getEyeLocation().getY(), owner.getZ()); } catch (Throwable ignored) {}
    }
}
