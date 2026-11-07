package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalType;
import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.GlovedPlugin;
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
    private final Map<UUID, Integer> activeCount = new ConcurrentHashMap<>();

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
        long required = profile.cooldownTicks * 50L;
        if (last != null && now - last < required) return false;
        int count = tracker.getActiveCount(player.getUniqueId());
        if (count >= profile.maxActive) return false;
        return true;
    }

    public void setCooldown(Player player, SummonProfile profile) {
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(profile.mobType, System.currentTimeMillis());
    }

    public void spawn(Player player, SummonProfile profile, LivingEntity target) {
        Location spawnLoc = resolveSpawnLocation(player, target);
        GlovedPlugin.logDebug("[Summon] Spawning " + profile.minions + " x " + profile.mobTypes + " for " + player.getName() + " at " + spawnLoc);
        List<UUID> spawned = new ArrayList<>();
        for (int i = 0; i < profile.minions; i++) {
            EntityType type = profile.resolveEntityType();
            if (type == null || !type.isSpawnable()) {
                GlovedPlugin.logDebug("[Summon] Invalid entity type: " + profile.mobTypes);
                continue;
            }
            Location loc = spawnLoc.clone().add((Math.random() - 0.5) * 2, 0, (Math.random() - 0.5) * 2);
            LivingEntity mob = (LivingEntity) player.getWorld().spawnEntity(loc, type);
            if (mob == null) continue;
            // Enable AI but strip vanilla behaviours
            try {
                mob.setAI(true);
                mob.setCanPickupItems(false);
                if (mob instanceof Mob m) {
                    MobGoals goals = plugin.getServer().getMobGoals();
                    // Log goals before removal
                    var before = goals.getAllGoals(m);
                    plugin.getLogger().info("[Summon] Minion " + mob.getUniqueId() + " goals before removal: " + before.size() + " goals");
                    for (var g : before) {
                        try {
                            plugin.getLogger().info("[Summon]   Goal key=" + g.getKey() + " types=" + g.getTypes());
                        } catch (Exception ignored) {}
                    }
                    // Remove all vanilla goals except jump-related ones; keep vanilla jumping/gravity
                    var toRemove = new java.util.ArrayList<Goal>();
                    for (var g : goals.getAllGoals(m)) {
                        var types = g.getTypes();
                        boolean keep = false;
                        for (var t : types) {
                            if (t == GoalType.JUMP) {
                                keep = true;
                                break;
                            }
                        }
                        if (!keep) {
                            toRemove.add(g);
                        }
                    }
                    for (var g : toRemove) {
                        try { goals.removeGoal(m, g); } catch (Exception ignored) {}
                    }
                    // Clear any existing target to avoid vanilla targeting
                    m.setTarget(null);
                    // Log goals after removal
                    var after = goals.getAllGoals(m);
                    plugin.getLogger().info("[Summon] Minion " + mob.getUniqueId() + " goals after removal: " + after.size() + " goals");
                    for (var g : after) {
                        try {
                            plugin.getLogger().info("[Summon]   Goal key=" + g.getKey() + " types=" + g.getTypes());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            // Tame if applicable
            if (mob instanceof Tameable t && profile.tamed) {
                t.setTamed(true);
                t.setOwner(player);
                t.setAdult();
            }
            // Set custom name for tracking
            String trackingName = "summon:" + profile.mobTypes + ":" + player.getUniqueId();
            // Use config nametag
            String displayName = profile.getNametag(player, type);
            mob.setCustomName(displayName);
            mob.setCustomNameVisible(profile.nametagVisible);
            // Store mob type in PDC for DamageProxy
            var pdc = mob.getPersistentDataContainer();
            NamespacedKey mobTypeKey = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_mobtype");
            pdc.set(mobTypeKey, PersistentDataType.STRING, type.name());
            // Invulnerable
            if (profile.invulnerable) {
                mob.setInvulnerable(true);
                if (mob.getAttribute(Attribute.MAX_HEALTH) != null) {
                    mob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(1e9);
                }
            }
            // Friendly
            if (profile.friendly && mob instanceof org.bukkit.entity.Mob m) {
                m.setTarget(null);
            }
            // Force initial target
            if ("CURRENT_TARGET".equalsIgnoreCase(profile.targetMode) && target != null && !target.isDead() && mob instanceof org.bukkit.entity.Mob m2) {
                m2.setTarget(target);
            }
            // Register tracker
            tracker.track(player.getUniqueId(), mob, profile.lifetimeTicks);
            activeCount.merge(player.getUniqueId(), 1, Integer::sum);
            spawned.add(mob.getUniqueId());
            // Start AI monitoring
            minionAI.monitorMinion(mob, player, profile);
        }
        if (!spawned.isEmpty()) {
            GlovedPlugin.logDebug("[Summon] Spawned " + spawned.size() + " minions for " + player.getName());
        }
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
