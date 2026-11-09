package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.phasing.PhasingMob;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SummonManager {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private final SummonInstanceTracker tracker;
    private final MinionAI minionAI;
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public SummonManager(GlovedPlugin plugin, SummonConfig config, SummonInstanceTracker tracker, MinionAI minionAI) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.minionAI = minionAI;
    }

    public boolean canSummon(Player player, SummonProfile profile) {
        if (!config.isEnabled()) return false;
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.getOrDefault(player.getUniqueId(), Collections.emptyMap());
        Long last = playerCooldowns.get(profile.mobType);
        long required = SummonProfile.COOLDOWN_TICKS * 50L;
        if (last != null && now - last < required) return false;
        int count = tracker.getActiveCount(player.getUniqueId(), profile.name);
        if (count >= profile.maxActive) return false;
        return true;
    }

    public void setCooldown(Player player, SummonProfile profile) {
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(profile.mobType, System.currentTimeMillis());
    }

    public void spawn(Player player, SummonProfile profile, LivingEntity target) {
        Location spawnLoc = resolveSpawnLocation(player, target);
        // Never exceed the per-profile cap, even when a batch is larger than the remaining slots.
        int active = tracker.getActiveCount(player.getUniqueId(), profile.name);
        int toSpawn = Math.min(profile.minionsPerHit, Math.max(0, profile.maxActive - active));
        GlovedPlugin.logDebug("[Summon] Spawning " + toSpawn + "/" + profile.minionsPerHit + " x " + profile.mobType
                + " for " + player.getName() + " at " + spawnLoc + " (active " + active + "/" + profile.maxActive + ")");
        List<UUID> spawned = new ArrayList<>();
        for (int i = 0; i < toSpawn; i++) {
            EntityType type = profile.resolveEntityType();
            if (type == null || !type.isSpawnable()) {
                GlovedPlugin.logDebug("[Summon] Invalid entity type: " + profile.mobType);
                continue;
            }
            Location loc = spawnLoc.clone().add((Math.random() - 0.5) * 2, 0, (Math.random() - 0.5) * 2);
            LivingEntity mob = (LivingEntity) player.getWorld().spawnEntity(loc, type);
            if (mob == null) continue;
            // Enable AI but selectively strip quirk/target-selector vanilla goals. Keep core
            // movement/navigation goals so vanilla pathfinding can run; use pathfinder.moveTo
            // (navigation intent) for follow, never direct velocity steering.
            try {
                mob.setAI(true);
                mob.setCanPickupItems(false);
                if (mob instanceof Mob m) {
                    MobGoals goals = plugin.getServer().getMobGoals();
                    var all = goals.getAllGoals(m);
                    var toRemove = new java.util.ArrayList<com.destroystokyo.paper.entity.ai.Goal<?>>();
                    for (var g : all) {
                        boolean remove = shouldRemoveVanillaGoal(g);
                        GlovedPlugin.logDebug("[Summon] Goal " + mob.getType() + " " + describeGoal(g) + " -> " + (remove ? "remove" : "keep"));
                        if (remove) toRemove.add(g);
                    }
                    for (var g : toRemove) {
                        try {
                            goals.removeGoal(m, (com.destroystokyo.paper.entity.ai.Goal) g);
                        } catch (Exception ignored) {}
                    }
                    goals.addGoal(m, 0, new SummonMoveGoal(plugin, tracker, m));
                    m.setTarget(null);
                    GlovedPlugin.logDebug("[Summon] Minion " + mob.getUniqueId() + " goals kept=" + goals.getAllGoals(m).size() + " removed=" + toRemove.size());
                }
            } catch (Exception ignored) {}
            // Any tameable minion is always tamed and owned by the summoner
            if (mob instanceof Tameable t) {
                t.setTamed(true);
                t.setOwner(player);
                t.setAdult();
            }
            PhasingMob.applySpawnSettings(mob);
            // Use config nametag
            String displayName = profile.getNametag(player, type);
            mob.setCustomName(displayName);
            mob.setCustomNameVisible(SummonProfile.NAMETAG_VISIBLE);
            // Store mob type/profile/owner in PDC (owner enables orphan cleanup after restarts)
            var pdc = mob.getPersistentDataContainer();
            NamespacedKey mobTypeKey = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_mobtype");
            pdc.set(mobTypeKey, PersistentDataType.STRING, type.name());
            NamespacedKey profileKey = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_profile");
            pdc.set(profileKey, PersistentDataType.STRING, profile.name);
            NamespacedKey ownerKey = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_owner");
            pdc.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            // Summoned minions are always invulnerable
            if (SummonProfile.INVULNERABLE) {
                mob.setInvulnerable(true);
                if (mob.getAttribute(Attribute.MAX_HEALTH) != null) {
                    mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(1e9);
                }
            }
            // Force initial target
            if (target != null && !target.isDead() && mob instanceof org.bukkit.entity.Mob m2) {
                m2.setTarget(target);
            }
            // Register tracker
            tracker.track(player.getUniqueId(), mob, SummonProfile.LIFETIME_TICKS);
            spawned.add(mob.getUniqueId());
            // Start AI monitoring
            minionAI.monitorMinion(mob, player, profile);
        }
        if (!spawned.isEmpty()) {
            GlovedPlugin.logDebug("[Summon] Spawned " + spawned.size() + " minions for " + player.getName());
        }
    }

    private static boolean shouldRemoveVanillaGoal(com.destroystokyo.paper.entity.ai.Goal<?> goal) {
        if (goal == null) return false;
        try {
            var types = goal.getTypes();
            if (types != null && types.contains(com.destroystokyo.paper.entity.ai.GoalType.TARGET)) {
                return true; // plugin owns targeting
            }
        } catch (Exception ignored) {}
        try {
            var key = goal.getKey();
            if (key != null && key.getNamespacedKey() != null) {
                String k = key.getNamespacedKey().getKey().toLowerCase(java.util.Locale.ROOT);
                // Quirks/behaviors we never want on minions
                if (k.contains("pollinate") || k.contains("flower") || k.contains("hive")
                        || k.contains("breed") || k.contains("mate") || k.contains("love")
                        || k.contains("tempt") || k.contains("panic") || k.contains("flee_sun")
                        || k.contains("random_stroll") || k.contains("water_avoiding_random_stroll")
                        || k.contains("random_fly") || k.contains("wander") || k.contains("stroll")
                        || k.contains("eat_block") || k.contains("eat_grass") || k.contains("eat_tile")
                        || k.contains("sleep") || k.contains("rest") || k.contains("roost") || k.contains("hang")
                        || k.contains("raid") || k.contains("celebrate")
                        || k.contains("beg") || k.contains("offer") || k.contains("trade")
                        || k.contains("pickup") || k.contains("take_flower") || k.contains("find_pollination")
                        || k.contains("go_to_known") || k.contains("locate_hive") || k.contains("go_to_hive")
                        || k.contains("enter_hive") || k.contains("pathfind_to_pollination")
                        || k.contains("vex_random_move")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String describeGoal(com.destroystokyo.paper.entity.ai.Goal<?> goal) {
        if (goal == null) return "null";
        String key = "?";
        String types = "?";
        try {
            if (goal.getKey() != null && goal.getKey().getNamespacedKey() != null) {
                key = goal.getKey().getNamespacedKey().toString();
            }
        } catch (Exception ignored) {}
        try {
            types = String.valueOf(goal.getTypes());
        } catch (Exception ignored) {}
        return "key=" + key + " types=" + types;
    }

    private Location resolveSpawnLocation(Player player, LivingEntity target) {
        if (target != null && !target.isDead()) {
            return target.getLocation().add(0, 1, 0);
        }
        return player.getLocation().add(0, 1, 0);
    }

    public void reload(SummonConfig newConfig) {
        // No-op; config reference swapped externally
    }
}
