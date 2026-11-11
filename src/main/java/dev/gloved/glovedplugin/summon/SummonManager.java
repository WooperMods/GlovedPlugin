package dev.gloved.glovedplugin.summon;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.SoulConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
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
    // Soul cast cooldowns (per-player), ms timestamps
    private final Map<UUID, Long> soulCastCooldowns = new ConcurrentHashMap<>();

    public SummonManager(GlovedPlugin plugin, SummonConfig config, SummonInstanceTracker tracker, MinionAI minionAI) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.minionAI = minionAI;
    }

    public long getSoulCastCooldownRemainingMs(UUID playerId) {
        if (playerId == null) return 0;
        Long until = soulCastCooldowns.get(playerId);
        if (until == null) return 0;
        long rem = until - System.currentTimeMillis();
        return Math.max(0, rem);
    }

    public void setSoulCastCooldown(UUID playerId, long cooldownMs) {
        if (playerId == null) return;
        soulCastCooldowns.put(playerId, System.currentTimeMillis() + Math.max(0, cooldownMs));
    }

    public boolean canSummon(Player player, SummonProfile profile, SummonTrigger trigger) {
        if (!config.isEnabled() || player == null || profile == null) return false;
        long now = System.currentTimeMillis();
        Map<String, Long> playerCooldowns = cooldowns.getOrDefault(player.getUniqueId(), Collections.emptyMap());
        String key = trigger.name() + ":" + profile.name;
        Long last = playerCooldowns.get(key);
        long required = SummonProfile.COOLDOWN_TICKS * 50L;
        if (last != null && now - last < required) return false;
        EffectiveSummonStats stats = EffectiveSummonStats.resolve(player, profile, config, plugin.getSummonProgression());
        int typeCount = tracker.getActiveCount(player.getUniqueId(), profile.name);
        if (typeCount >= stats.maxActive) return false;
        // Global cap (dynamic soul system)
        SoulConfig sc = config.getSoulConfig();
        if (sc != null) {
            int level = 0;
            try { if (plugin.getSummonProgression() != null) level = Math.max(0, plugin.getSummonProgression().getSummoningLevel(player.getUniqueId())); } catch (Throwable ignored) {}
            int globalMax = sc.computeGlobalMaxActive(level);
            int globalCount = tracker.getActiveMinions(player.getUniqueId()).size();
            if (globalCount >= globalMax) return false;
        }
        return true;
    }

    public void setCooldown(Player player, SummonProfile profile, SummonTrigger trigger) {
        if (player == null || profile == null) return;
        String key = trigger.name() + ":" + profile.name;
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(key, System.currentTimeMillis());
    }

    /** Spawn from dynamic soul/profile template; tags summon_soul_id/summon_family when soul provided. */
    public int spawnFromSoul(Player player, SummonProfile profile, dev.gloved.glovedplugin.summon.soul.SoulInstance soul, LivingEntity target) {
        return spawnFromSoul(player, profile, soul, target, false);
    }

    public int spawnFromSoul(Player player, SummonProfile profile, dev.gloved.glovedplugin.summon.soul.SoulInstance soul, LivingEntity target, boolean allowReplace) {
        if (player == null || profile == null) return 0;
        Location spawnLoc = resolveSpawnLocation(player, target);
        EffectiveSummonStats stats = EffectiveSummonStats.resolve(player, profile, config, plugin.getSummonProgression());
        int activeType = tracker.getActiveCount(player.getUniqueId(), profile.name);
        int toSpawn = Math.min(stats.minionsPerHit, Math.max(0, stats.maxActive - activeType));
        // Apply global cap
        SoulConfig sc = config.getSoulConfig();
        int level = 0;
        int globalMax = Integer.MAX_VALUE;
        int globalCount = 0;
        if (sc != null) {
            try { if (plugin.getSummonProgression() != null) level = Math.max(0, plugin.getSummonProgression().getSummoningLevel(player.getUniqueId())); } catch (Throwable ignored) {}
            globalMax = sc.computeGlobalMaxActive(level);
            globalCount = tracker.getActiveMinions(player.getUniqueId()).size();
            toSpawn = Math.min(toSpawn, Math.max(0, globalMax - globalCount));
        }
        if (toSpawn <= 0 && allowReplace) {
            boolean freed = false;
            if (sc != null && globalCount >= globalMax) {
                freed = tryFreeOneForReplace(player, profile.name, true);
            } else if (activeType >= stats.maxActive) {
                freed = tryFreeOneForReplace(player, profile.name, false);
            }
            if (freed) {
                activeType = tracker.getActiveCount(player.getUniqueId(), profile.name);
                toSpawn = Math.min(stats.minionsPerHit, Math.max(0, stats.maxActive - activeType));
                if (sc != null) {
                    globalCount = tracker.getActiveMinions(player.getUniqueId()).size();
                    toSpawn = Math.min(toSpawn, Math.max(0, globalMax - globalCount));
                }
            }
        }
        if (toSpawn <= 0) return 0;
        GlovedPlugin.logDebug("[Summon] Spawning " + toSpawn + "/" + stats.minionsPerHit + " x " + profile.mobType
                + " for " + player.getName() + " at " + spawnLoc + " (typeActive " + activeType + "/" + stats.maxActive + ", lvl " + stats.summoningLevel + ", soul=" + (soul != null ? soul.getSoulId() : "none") + ")");
        List<UUID> spawned = new ArrayList<>();
        for (int i = 0; i < toSpawn; i++) {
            EntityType type = profile.resolveEntityType();
            if (type == null || !type.isSpawnable() || !type.isAlive()) {
                GlovedPlugin.logDebug("[Summon] Invalid/non-spawnable entity type: " + profile.mobType);
                continue;
            }
            Location loc = spawnLoc.clone().add((Math.random() - 0.5) * 2, 0, (Math.random() - 0.5) * 2);
            LivingEntity mob;
            try {
                mob = (LivingEntity) player.getWorld().spawnEntity(loc, type);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[Summon] Failed to spawn " + type + ": " + t);
                continue;
            }
            if (mob == null) continue;
            try {
                mob.setAI(true);
                mob.setCanPickupItems(false);
                if (mob instanceof Mob m) {
                    MobGoals goals = plugin.getServer().getMobGoals();
                    var all = goals.getAllGoals(m);
                    var toRemove = new java.util.ArrayList<com.destroystokyo.paper.entity.ai.Goal<?>>();
                    for (var g : all) {
                        boolean remove = shouldRemoveVanillaGoal(g);
                        if (remove) toRemove.add(g);
                    }
                    for (var g : toRemove) {
                        try { goals.removeGoal(m, (com.destroystokyo.paper.entity.ai.Goal) g); } catch (Exception ignored) {}
                    }
                    goals.addGoal(m, 0, new SummonMoveGoal(plugin, tracker, m));
                    m.setTarget(null);
                }
            } catch (Exception ignored) {}
            if (mob instanceof Tameable t) {
                try { t.setTamed(true); t.setOwner(player); t.setAdult(); } catch (Throwable ignored) {}
            }
            String displayName = profile.getNametag(player, type);
            try { mob.setCustomName(displayName); mob.setCustomNameVisible(SummonProfile.NAMETAG_VISIBLE); } catch (Throwable ignored) {}

            var pdc = mob.getPersistentDataContainer();
            NamespacedKey mobTypeKey = new NamespacedKey(plugin, "summon_mobtype");
            pdc.set(mobTypeKey, PersistentDataType.STRING, type.name());
            NamespacedKey profileKey = new NamespacedKey(plugin, "summon_profile");
            pdc.set(profileKey, PersistentDataType.STRING, profile.name);
            NamespacedKey familyKey = new NamespacedKey(plugin, "summon_family");
            pdc.set(familyKey, PersistentDataType.STRING, profile.name);
            NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_owner");
            pdc.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            if (soul != null) {
                NamespacedKey soulKey = new NamespacedKey(plugin, "summon_soul_id");
                pdc.set(soulKey, PersistentDataType.STRING, soul.getSoulId().toString());
            }

            // Minions are damageable; apply soul stat snapshot (spawn-time attributes)
            if (soul != null) {
                try { applySoulSnapshotToMinion(mob, soul); } catch (Throwable t) { GlovedPlugin.logDebug("[Summon] Failed to apply soul snapshot: " + t); }
            } else {
                try {
                    var hp = mob.getAttribute(Attribute.MAX_HEALTH);
                    if (hp != null) {
                        mob.setHealth(Math.max(1.0, hp.getValue()));
                    }
                } catch (Throwable ignored) {}
            }

            if (target != null && !target.isDead() && mob instanceof org.bukkit.entity.Mob m2) {
                try { m2.setTarget(target); } catch (Throwable ignored) {}
            }
            tracker.track(player.getUniqueId(), mob, SummonProfile.LIFETIME_TICKS);
            spawned.add(mob.getUniqueId());
            minionAI.monitorMinion(mob, player, profile);
        }
        if (!spawned.isEmpty()) {
            playSummonEffects(player, spawnLoc, spawned.size());
            // Trail from player head to each spawned minion
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
            GlovedPlugin.logDebug("[Summon] Spawned " + spawned.size() + " minions for " + player.getName() + " (soul-cast=" + (soul != null) + ")");
        }
        return spawned.size();
    }

    /**
     * Try to free one active minion slot for replace-policy casts.
     * @param preferGlobal if true, prefer freeing any minion (global cap pressure), else prefer same family/profile.
     */
    private boolean tryFreeOneForReplace(Player player, String familyKey, boolean preferGlobal) {
        try {
            if (player == null || tracker == null) return false;
            java.util.List<LivingEntity> mins = tracker.getActiveMinions(player.getUniqueId());
            if (mins == null || mins.isEmpty()) return false;
            LivingEntity victim = null;
            NamespacedKey profileKey = new NamespacedKey(plugin, "summon_profile");
            NamespacedKey familyPdc = new NamespacedKey(plugin, "summon_family");
            // Prefer same family/profile if per-type pressure or as first pass
            if (familyKey != null) {
                for (LivingEntity le : mins) {
                    if (le == null || le.isDead()) continue;
                    String fam = le.getPersistentDataContainer().get(familyPdc, PersistentDataType.STRING);
                    if (fam == null) fam = le.getPersistentDataContainer().get(profileKey, PersistentDataType.STRING);
                    if (familyKey.equalsIgnoreCase(fam)) { victim = le; break; }
                }
            }
            // If global pressure and no same-family found (or none preferred), take any
            if (victim == null && preferGlobal) {
                for (LivingEntity le : mins) {
                    if (le != null && !le.isDead()) { victim = le; break; }
                }
            }
            if (victim == null) return false;
            tracker.despawn(victim.getUniqueId());
            return true;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Replace-policy despawn failed: " + t);
            return false;
        }
    }

    /** Mana consumption for explicit soul cast (uses soul cast base cost). */
    public boolean tryConsumeSoulCastMana(Player player) {
        double cost = 0.0;
        if (config != null && config.getSoulConfig() != null) cost = Math.max(0.0, config.getSoulConfig().getCastBaseManaCost());
        return tryConsumeSoulCastMana(player, cost);
    }

    /** Mana consumption for explicit soul cast with computed (soul-scaled) cost. */
    public boolean tryConsumeSoulCastMana(Player player, double cost) {
        try {
            if (config == null) return true;
            cost = Math.max(0.0, cost);
            if (cost <= 0.0) return true;
            if (!config.isManaEnabled()) return true;
            if (player == null || !player.isOnline()) return false;
            if (plugin.getServer().getPluginManager().getPlugin("AuraSkills") == null) {
                return config.isManaAllowWhenAuraSkillsMissing();
            }
            Class<?> apiClass = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object api = apiClass.getMethod("get").invoke(null);
            if (api == null) return config.isManaAllowWhenAuraSkillsMissing();
            Object user = apiClass.getMethod("getUser", UUID.class).invoke(api, player.getUniqueId());
            if (user == null) return config.isManaAllowWhenAuraSkillsMissing();
            try {
                Object loaded = user.getClass().getMethod("isLoaded").invoke(user);
                if (loaded instanceof Boolean b && !b) return config.isManaAllowWhenAuraSkillsMissing();
            } catch (Throwable ignored) {}
            Object ok = user.getClass().getMethod("consumeMana", double.class).invoke(user, cost);
            if (ok instanceof Boolean b) return b;
            double mana = ((Number) user.getClass().getMethod("getMana").invoke(user)).doubleValue();
            if (mana + 1.0e-9 < cost) return false;
            user.getClass().getMethod("setMana", double.class).invoke(user, Math.max(0.0, mana - cost));
            return true;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Soul cast mana consume failed: " + t);
            return config == null || config.isManaAllowWhenAuraSkillsMissing();
        }
    }

    /**
     * Applies rolled soul base stats (enhanced) plus affix multipliers to a freshly spawned minion.
     *
     * <p>Never touches {@code Attribute.FOLLOW_RANGE}: follow/target range is a fixed
     * {@link SummonProfile#FOLLOW_RANGE} AI limit and is intentionally not a soul stat.</p>
     */
    private void applySoulSnapshotToMinion(LivingEntity mob, dev.gloved.glovedplugin.summon.soul.SoulInstance soul) {
        if (mob == null || soul == null) return;
        var svc = plugin.getSoulService();
        var snap = svc != null ? svc.getSnapshot(soul) : dev.gloved.glovedplugin.summon.soul.SoulStatSnapshot.fromSoul(soul);
        dev.gloved.glovedplugin.summon.soul.SoulBaseStats base = svc != null
                ? svc.effectiveBaseStats(soul)
                : (soul.getBaseStats() != null
                        ? soul.getBaseStats()
                        : dev.gloved.glovedplugin.summon.soul.SoulBaseStats.defaults(soul.getEntityType()));

        double speedMult = clamp(snap.speedMultiplier(), 0.5, 1.6);
        // Movement: rolled absolute speed scaled by the SWIFT affix.
        setBase(mob, Attribute.MOVEMENT_SPEED, clamp(base.movementSpeed() * speedMult, 0.01, 1.0));
        // Flying speed is not a captured soul stat: keep the mob's own base, scaled by the same multiplier.
        try {
            var fly = mob.getAttribute(Attribute.FLYING_SPEED);
            if (fly != null) fly.setBaseValue(clamp(fly.getBaseValue() * speedMult, 0.01, 1.0));
        } catch (Throwable ignored) {}
        // Offense / defense stats: absolute rolled values (affix multipliers where they exist).
        setBase(mob, Attribute.ATTACK_DAMAGE, Math.max(0.0, base.attackDamage() * clamp(snap.damageMultiplier(), 0.5, 3.0)));
        setBase(mob, Attribute.ARMOR, base.armor());
        setBase(mob, Attribute.ARMOR_TOUGHNESS, base.armorToughness());
        setBase(mob, Attribute.KNOCKBACK_RESISTANCE, Math.min(1.0, base.knockbackResistance()));
        setBase(mob, Attribute.ATTACK_KNOCKBACK, base.attackKnockback());
        setBase(mob, Attribute.ATTACK_SPEED, base.attackSpeed());

        // Apply bounded health scaling for damageable minions.
        // Health = rolled (enhanced) base * VIGOROUS affix + owner's AuraSkills health stat (flat bonus).
        try {
            var hp = mob.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) {
                double healthMult = Math.max(0.5, Math.min(3.0, snap.healthMultiplier()));
                double scaledBase = Math.max(1.0, base.health() * healthMult);
                // Owner's AuraSkills health stat: flat max-health bonus for the minion.
                double ownerHealthBonus = 0.0;
                try {
                    UUID ownerId = tracker != null ? tracker.getOwner(mob.getUniqueId()) : null;
                    if (ownerId != null) {
                        var prog = plugin.getSummonProgression();
                        if (prog != null) {
                            Player owner = Bukkit.getPlayer(ownerId);
                            if (owner != null && owner.isOnline()) {
                                var stats = prog.resolveOwnerCombatStats(owner.getUniqueId());
                                if (stats.available()) {
                                    ownerHealthBonus = Math.max(0.0, stats.healthStatLevel());
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                double newMax = Math.max(1.0, scaledBase + ownerHealthBonus);
                hp.setBaseValue(newMax);
                mob.setHealth(newMax);
            }
        } catch (Throwable ignored) {}
        // Slime/Magma size normalization (safety/generic rule)
        try {
            if (mob instanceof org.bukkit.entity.Slime slime) {
                int size = slime.getSize();
                if (size > 3) slime.setSize(2);
                else if (size < 1) slime.setSize(1);
            }
        } catch (Throwable ignored) {}
    }

    private static void setBase(LivingEntity mob, Attribute attribute, double value) {
        try {
            var instance = mob.getAttribute(attribute);
            if (instance != null && Double.isFinite(value)) instance.setBaseValue(Math.max(0.0, value));
        } catch (Throwable ignored) {}
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static boolean shouldRemoveVanillaGoal(com.destroystokyo.paper.entity.ai.Goal<?> goal) {
        if (goal == null) return false;
        try {
            var key = goal.getKey();
            if (key != null && key.getNamespacedKey() != null) {
                String k = key.getNamespacedKey().getKey().toLowerCase(java.util.Locale.ROOT);
                // Keep ranged-attack goals so ranged minions (skeleton, stray, bogged, pillager,
                // drowned w/ trident, blaze, breeze, witch, etc.) can actually shoot/projectile-attack.
                if (k.contains("ranged") || k.contains("bow") || k.contains("crossbow")
                        || k.contains("trident") || k.contains("breeze") || k.contains("shoot")
                        || k.contains("throw")) {
                    return false;
                }
            }
        } catch (Exception ignored) {}
        // SummonMoveGoal handles all movement (chase target + follow owner), so remove
        // any remaining vanilla MOVE-type goals (e.g. MoveTowardsTargetGoal, random stroll)
        // that would conflict with it by stopping/overriding the path.
        try {
            var types = goal.getTypes();
            if (types != null && types.contains(com.destroystokyo.paper.entity.ai.GoalType.MOVE)) {
                return true;
            }
        } catch (Exception ignored) {}
        try {
            var types = goal.getTypes();
            if (types != null && types.contains(com.destroystokyo.paper.entity.ai.GoalType.TARGET)) {
                return true;
            }
        } catch (Exception ignored) {}
        try {
            var key = goal.getKey();
            if (key != null && key.getNamespacedKey() != null) {
                String k = key.getNamespacedKey().getKey().toLowerCase(java.util.Locale.ROOT);
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
        double[] distances = {2.6, 2.0, 3.2, 1.6, 3.8};
        for (double d : distances) {
            Location cand = base.clone().add(dir.clone().multiply(d));
            cand.setY(base.getY());
            Location safe = findSafeSpawn(cand);
            if (safe != null) return safe;
        }
        Location fallback = base.clone().add(dir.multiply(1.8));
        fallback.setY(base.getY());
        return fallback.add(0, 0.1, 0);
    }

    private Location findSafeSpawn(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        int baseY = loc.getBlockY();
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
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, fx, 14 + Math.min(count * 2, 20), 0.9, 0.7, 0.9, 0.02);
            world.spawnParticle(Particle.REVERSE_PORTAL, fx, 12 + Math.min(count * 2, 15), 0.8, 0.6, 0.8, 0.03);
            world.spawnParticle(Particle.WITCH, fx, 8 + Math.min(count * 1, 12), 0.7, 0.5, 0.7, 0.01);
            world.spawnParticle(Particle.END_ROD, fx.clone().add(0, 0.6, 0), 5 + Math.min(count * 1, 9), 0.5, 0.8, 0.5, 0.01);
            world.spawnParticle(Particle.CLOUD, fx, 4, 0.5, 0.2, 0.5, 0.02);
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
        // config reference swapped externally
    }
}
