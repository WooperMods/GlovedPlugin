package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.phasing.PhasingMob;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

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

    public boolean canSummon(Player player, SummonProfile profile, SummonTrigger trigger) {
        if (!config.isEnabled()) return false;
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.getOrDefault(player.getUniqueId(), Collections.emptyMap());
        String key = trigger.name() + ":" + profile.name;
        Long last = playerCooldowns.get(key);
        long required = SummonProfile.COOLDOWN_TICKS * 50L;
        if (last != null && now - last < required) return false;
        // max-active is a shared pool across triggers (counted per profile)
        int count = tracker.getActiveCount(player.getUniqueId(), profile.name);
        if (count >= profile.maxActive) return false;
        return true;
    }

    public void setCooldown(Player player, SummonProfile profile, SummonTrigger trigger) {
        String key = trigger.name() + ":" + profile.name;
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(key, System.currentTimeMillis());
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
            playSummonEffects(player, spawnLoc, spawned.size());
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
        return findFrontSpawn(player);
    }

    private Location findFrontSpawn(Player player) {
        Location base = player.getLocation();
        Vector dir = base.getDirection().clone().setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            dir = base.clone().getDirection().setY(0);
        }
        if (dir.lengthSquared() < 1.0E-4) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        // Try visible/open spots in front of the player first
        double[] distances = {2.6, 2.0, 3.2, 1.6, 3.8};
        for (double d : distances) {
            Location cand = base.clone().add(dir.clone().multiply(d));
            cand.setY(base.getY());
            Location safe = findSafeSpawn(cand);
            if (safe != null) return safe;
        }
        // Fallback: just in front of player, slightly raised
        Location fallback = base.clone().add(dir.multiply(1.8));
        fallback.setY(base.getY());
        return fallback.add(0, 0.1, 0);
    }

    private Location findSafeSpawn(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        int baseY = loc.getBlockY();
        // Try same level, then one up/down to handle slabs/slight uneven ground
        int[] yOffsets = {0, 1, -1};
        for (int yo : yOffsets) {
            Location feetLoc = loc.clone();
            feetLoc.setY(baseY + yo);
            Block feet = world.getBlockAt(feetLoc);
            Block head = feet.getRelative(0, 1, 0);
            Block below = feet.getRelative(0, -1, 0);
            if (isOpenForSpawn(feet) && isOpenForSpawn(head) && below.getType().isSolid()) {
                Location out = feet.getLocation().add(0.5, 0.0, 0.5);
                out.setYaw(loc.getYaw());
                out.setPitch(0f);
                return out;
            }
        }
        return null;
    }

    private boolean isOpenForSpawn(Block block) {
        try {
            return block != null && block.isPassable();
        } catch (Throwable t) {
            return block != null && !block.getType().isSolid();
        }
    }

    private void playSummonEffects(Player player, Location at, int count) {
        if (at == null || at.getWorld() == null) return;
        try {
            World world = at.getWorld();
            Location fx = at.clone().add(0, 0.8, 0);
            // Main burst - visible and "epic"
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, fx, 28 + Math.min(count * 4, 40), 0.9, 0.7, 0.9, 0.02);
            world.spawnParticle(Particle.REVERSE_PORTAL, fx, 24 + Math.min(count * 3, 30), 0.8, 0.6, 0.8, 0.03);
            world.spawnParticle(Particle.WITCH, fx, 16 + Math.min(count * 2, 24), 0.7, 0.5, 0.7, 0.01);
            world.spawnParticle(Particle.END_ROD, fx.clone().add(0, 0.6, 0), 10 + Math.min(count * 2, 18), 0.5, 0.8, 0.5, 0.01);
            world.spawnParticle(Particle.CLOUD, fx, 8, 0.5, 0.2, 0.5, 0.02);
            world.playSound(fx, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.15f);
            world.playSound(fx, Sound.BLOCK_BEACON_ACTIVATE, 0.35f, 1.6f);
            if (player != null) {
                player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 0.25f, 1.4f);
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Failed to play summon effects: " + t);
        }
    }

    public void reload(SummonConfig newConfig) {
        // No-op; config reference swapped externally
    }
}
