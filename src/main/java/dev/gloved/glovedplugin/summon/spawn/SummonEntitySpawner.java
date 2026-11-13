package dev.gloved.glovedplugin.summon.spawn;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.ai.movement.SummonMoveGoal;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.nms.api.SummonNMS;
import dev.gloved.glovedplugin.summon.runtime.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.runtime.SummonManager;
import dev.gloved.glovedplugin.summon.runtime.SummonNametag;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;

/**
 * Shared per-minion spawn/configure/finalize pipeline extracted for D2.
 * Preserves existing behavior/order; callers remain responsible for policy/cap decisions and counts.
 */
public final class SummonEntitySpawner {

    private SummonEntitySpawner() {
    }

    public static final class SpawnResult {
        public final int spawnedCount;
        public final List<UUID> spawnedIds;
        public final Location spawnLocation;

        public SpawnResult(int spawnedCount, List<UUID> spawnedIds, Location spawnLocation) {
            this.spawnedCount = spawnedCount;
            this.spawnedIds = spawnedIds;
            this.spawnLocation = spawnLocation;
        }
    }

    /**
     * Spawns and configures {@code toSpawn} minions using the same logic as the existing spawn loops.
     * This method does not perform cap/replace policy decisions; it only executes the spawn pipeline.
     */
    public static SpawnResult spawnConfiguredGroup(
            GlovedPlugin plugin,
            SummonConfig config,
            SummonManager manager,
            SummonInstanceTracker tracker,
            SummonCombatService combatService,
            Player player,
            SummonProfile profile,
            SoulInstance soul,
            LivingEntity target,
            Location baseSpawnLoc,
            int toSpawn,
            String successDebugPrefix
    ) {
        List<UUID> spawned = new ArrayList<>();
        if (player == null || profile == null || toSpawn <= 0) {
            return new SpawnResult(0, spawned, baseSpawnLoc);
        }
        SummonNMS summonNms = plugin.getSummonNMS();
        for (int i = 0; i < toSpawn; i++) {
            EntityType type = profile.resolveEntityType();
            if (type == null || !type.isSpawnable() || !type.isAlive()) {
                GlovedPlugin.logDebug("[Summon] Invalid/non-spawnable entity type: " + profile.mobType);
                continue;
            }
            Location loc = baseSpawnLoc.clone().add((Math.random() - 0.5) * 2, 0, (Math.random() - 0.5) * 2);
            LivingEntity mob = null;
            boolean spawnedViaNms = false;
            if (summonNms != null && summonNms.canSpawn(type)) {
                try {
                    SummonSharedData sharedData = new SummonSharedData(player.getUniqueId());
                    sharedData.setProfileName(profile.name);
                    if (soul != null) sharedData.setSoulId(soul.getSoulId());
                    mob = summonNms.spawnSummon(profile, sharedData, loc);
                    spawnedViaNms = mob != null;
                } catch (Throwable t) {
                    GlovedPlugin.logDebug("[Summon] NMS spawn failed for " + type + ", falling back: " + t);
                    mob = null;
                }
            }
            if (mob == null) {
                try {
                    mob = (LivingEntity) player.getWorld().spawnEntity(loc, type);
                } catch (Throwable t) {
                    GlovedPlugin.logDebug("[Summon] Failed to spawn " + type + ": " + t);
                    continue;
                }
            }
            if (mob == null) continue;
            try {
                mob.setAI(true);
                mob.setCanPickupItems(false);
                if (!spawnedViaNms && mob instanceof Mob m) {
                    MobGoals goals = plugin.getServer().getMobGoals();
                    var all = goals.getAllGoals(m);
                    var toRemove = new java.util.ArrayList<com.destroystokyo.paper.entity.ai.Goal<?>>();
                    for (var g : all) {
                        boolean remove = SummonManager.shouldRemoveVanillaGoal(g);
                        if (remove) toRemove.add(g);
                    }
                    for (var g : toRemove) {
                        try { goals.removeGoal(m, (com.destroystokyo.paper.entity.ai.Goal) g); } catch (Exception ignored) {}
                    }
                    // Legacy fallback movement goal for non-NMS summons
                    goals.addGoal(m, 0, new SummonMoveGoal(plugin, tracker, m));
                    m.setTarget(null);
                } else if (mob instanceof Mob m) {
                    m.setTarget(null);
                }
            } catch (Exception ignored) {}
            if (mob instanceof Tameable t) {
                try { t.setTamed(true); t.setOwner(player); t.setAdult(); } catch (Throwable ignored) {}
            }
            String ownerName = player != null ? player.getName() : "Owner";
            var rarity = soul != null ? soul.getRarity() : null;
            boolean nametagEnabled = false;
            try { nametagEnabled = config != null && config.getSoulConfig() != null && config.getSoulConfig().isNametagEnabled(); } catch (Throwable ignored) {}
            if (nametagEnabled) {
                String displayName = SummonNametag.format(profile, player, ownerName, mob, rarity);
                try { mob.setCustomName(displayName); mob.setCustomNameVisible(SummonProfile.NAMETAG_VISIBLE); } catch (Throwable ignored) {}
            } else {
                try { mob.setCustomName(null); mob.setCustomNameVisible(false); } catch (Throwable ignored) {}
            }
            // Prevent sun-sensitive minions (e.g. zombies, skeletons) from burning in daylight.
            try {
                mob.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE,
                        org.bukkit.potion.PotionEffect.INFINITE_DURATION,
                        0, false, false, false));
            } catch (Throwable ignored) {
                try {
                    mob.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE,
                            Integer.MAX_VALUE, 0, false, false, false));
                } catch (Throwable ignored2) {}
            }

            var pdc = mob.getPersistentDataContainer();
            NamespacedKey mobTypeKey = new NamespacedKey(plugin, "summon_mobtype");
            pdc.set(mobTypeKey, PersistentDataType.STRING, type.name());
            NamespacedKey profileKey = new NamespacedKey(plugin, "summon_profile");
            pdc.set(profileKey, PersistentDataType.STRING, profile.name);
            NamespacedKey familyKey = new NamespacedKey(plugin, "summon_family");
            pdc.set(familyKey, PersistentDataType.STRING, profile.name);
            NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_owner");
            pdc.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            pdc.set(new NamespacedKey(plugin, "summon_minion"), PersistentDataType.BYTE, (byte) 1);
            if (soul != null) {
                NamespacedKey soulKey = new NamespacedKey(plugin, "summon_soul_id");
                pdc.set(soulKey, PersistentDataType.STRING, soul.getSoulId().toString());
            }

            if (soul != null) {
                try { manager.applySoulSnapshotToMinion(mob, soul); } catch (Throwable t) { GlovedPlugin.logDebug("[Summon] Failed to apply soul snapshot: " + t); }
                manager.scheduleSoulSnapshotReassert(mob, soul);
            } else {
                try {
                    var hp = mob.getAttribute(Attribute.MAX_HEALTH);
                    if (hp != null) {
                        mob.setHealth(Math.max(1.0, hp.getValue()));
                    }
                } catch (Throwable ignored) {}
            }

            if (target != null && !target.isDead() && mob instanceof Mob m2) {
                try { m2.setTarget(target); } catch (Throwable ignored) {}
            }
            int baseLifetimeTicks = SummonProfile.LIFETIME_TICKS;
            try {
                if (config != null && config.getSoulConfig() != null) {
                    baseLifetimeTicks = config.getSoulConfig().getLifetimeTicks();
                }
            } catch (Throwable ignored) {}
            int lifetimeTicks = baseLifetimeTicks;
            if (baseLifetimeTicks > 0 && soul != null) {
                try {
                    var svc = plugin.getSoulService();
                    var snap = svc != null ? svc.getSnapshot(soul)
                            : SoulStatSnapshot.fromSoul(soul);
                    double enduranceBonus = Math.max(0.0, Math.min(1.0, snap.enduranceBonus()));
                    lifetimeTicks = (int) Math.max(20L, Math.round(baseLifetimeTicks * (1.0 + enduranceBonus)));
                } catch (Throwable ignored) {}
            }
            tracker.track(player.getUniqueId(), mob, lifetimeTicks);
            spawned.add(mob.getUniqueId());
        }
        if (!spawned.isEmpty()) {
            manager.playSummonEffects(player, baseSpawnLoc, spawned.size());
            try {
                var soulService = plugin.getSoulService();
                if (soulService != null) {
                    Location from = player.getLocation().add(0, 1.0, 0);
                    for (UUID id : spawned) {
                        Entity ent = Bukkit.getEntity(id);
                        if (ent instanceof LivingEntity minion && !minion.isDead()) {
                            soulService.playSoulTrail(
                                    from,
                                    minion.getLocation().add(0, minion.getHeight() * 0.5, 0),
                                    null);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (successDebugPrefix != null) {
                GlovedPlugin.logDebug(successDebugPrefix);
            }
        }
        return new SpawnResult(spawned.size(), spawned, baseSpawnLoc);
    }
}
