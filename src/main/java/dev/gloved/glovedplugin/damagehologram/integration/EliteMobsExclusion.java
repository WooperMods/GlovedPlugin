package dev.gloved.glovedplugin.damagehologram.integration;

import com.magmaguy.elitemobs.entitytracker.EntityTracker;
import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.entity.Entity;

public final class EliteMobsExclusion {

    private static boolean warnedMissing;

    private EliteMobsExclusion() {}

    public static boolean isEliteMob(Entity entity) {
        if (entity == null) return false;
        try {
            return EntityTracker.isEliteMob(entity);
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            warnOnce("EliteMobs API not available for damage hologram exclusion: " + e);
            return false;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[DamageHologram] EliteMobs exclusion check failed: " + t);
            return false;
        }
    }

    private static void warnOnce(String msg) {
        if (warnedMissing) return;
        warnedMissing = true;
        GlovedPlugin.logWarning(msg);
    }
}
