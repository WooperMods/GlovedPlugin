package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalType;
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
            // Enable AI but strip vanilla behaviours
            try {
                mob.setAI(true);
                mob.setCanPickupItems(false);
                if (mob instanceof Mob m) {
                    MobGoals goals = plugin.getServer().getMobGoals();
                    var before = goals.getAllGoals(m);
                    plugin.getLogger().info("[Summon] Minion " + mob.getUniqueId() + " goals before: " + before.size() + " goals");
                    for (var g : before) {
                        try {
                            plugin.getLogger().info("[Summon]   Goal key=" + g.getKey() + " types=" + g.getTypes());
                        } catch (Exception ignored) {}
                    }
                    if (PhasingMob.usesVanillaAI(m)) {
                        // Vanilla-AI mobs (e.g. Vex, Phantom): keep vanilla goals so native AI/pathing can run.
                        plugin.getLogger().info("[Summon] Minion " + mob.getUniqueId() + " (vanilla AI) keeping all vanilla goals");
                    } else {
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
                        var after = goals.getAllGoals(m);
                        plugin.getLogger().info("[Summon] Minion " + mob.getUniqueId() + " goals after removal: " + after.size() + " goals");
                        for (var g : after) {
                            try {
                                plugin.getLogger().info("[Summon]   Goal key=" + g.getKey() + " types=" + g.getTypes());
                            } catch (Exception ignored) {}
                        }
                    }
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
            // Store mob type in PDC for DamageProxy
            var pdc = mob.getPersistentDataContainer();
            NamespacedKey mobTypeKey = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_mobtype");
            pdc.set(mobTypeKey, PersistentDataType.STRING, type.name());
            NamespacedKey profileKey = new NamespacedKey(GlovedPlugin.getPlugin(GlovedPlugin.class), "summon_profile");
            pdc.set(profileKey, PersistentDataType.STRING, profile.name);
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
