package dev.gloved.glovedplugin.summon.runtime;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.nms.api.SummonNMS;
import dev.gloved.glovedplugin.summon.soul.domain.SoulConfig;
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
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
import dev.gloved.glovedplugin.summon.ai.movement.SummonMoveGoal;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import dev.gloved.glovedplugin.summon.spawn.SummonEntitySpawner;

public class SummonManager {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private final SummonInstanceTracker tracker;
    private final SummonAI summonAI;
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    // Soul cast cooldowns (per-player), ms timestamps
    private final Map<UUID, Long> soulCastCooldowns = new ConcurrentHashMap<>();

    public SummonManager(GlovedPlugin plugin, SummonConfig config, SummonInstanceTracker tracker, SummonAI summonAI) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.summonAI = summonAI;
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
    public int spawnFromSoul(Player player, SummonProfile profile, SoulInstance soul, LivingEntity target) {
        return spawnFromSoul(player, profile, soul, target, false);
    }

    public int spawnFromSoul(Player player, SummonProfile profile, SoulInstance soul, LivingEntity target, boolean allowReplace) {
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
        var result = SummonEntitySpawner.spawnConfiguredGroup(
                plugin, config, this, tracker, summonAI, player, profile, soul, target, spawnLoc, toSpawn,
                null);
        if (result.spawnedCount > 0) {
            GlovedPlugin.logDebug("[Summon] Spawned " + result.spawnedCount + " minions for " + player.getName() + " (soul-cast=" + (soul != null) + ")");
        }
        return result.spawnedCount;
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
        if (config != null && config.getSoulConfig() != null) {
            var sc = config.getSoulConfig();
            if (!sc.isCastManaEnabled()) return true;
            cost = Math.max(0.0, sc.getCastBaseManaCost());
        }
        return tryConsumeSoulCastMana(player, cost);
    }

    /** Mana consumption for explicit soul cast with computed (soul-scaled) cost. */
    public boolean tryConsumeSoulCastMana(Player player, double cost) {
        try {
            if (config == null) return true;
            if (config.getSoulConfig() != null && !config.getSoulConfig().isCastManaEnabled()) return true;
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
     * Re-applies soul stats shortly after spawn so external spawn-time modifiers (e.g. LevelledMobs,
     * if misconfigured) cannot leave a minion with the wrong health/attributes.
     */
    public void scheduleSoulSnapshotReassert(LivingEntity mob, SoulInstance soul) {
        if (mob == null || soul == null) return;
        final UUID mobId = mob.getUniqueId();
        final SoulInstance soulRef = soul;
        for (long delay : new long[]{1L, 10L, 40L, 100L, 200L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Entity ent = Bukkit.getEntity(mobId);
                if (!(ent instanceof LivingEntity living) || living.isDead() || !living.isValid()) return;
                try {
                    applySoulSnapshotToMinion(living, soulRef);
                } catch (Throwable t) {
                    GlovedPlugin.logDebug("[Summon] Failed to reassert soul snapshot: " + t);
                }
            }, delay);
        }
    }

    /**
     * Applies rolled soul base stats (enhanced) plus affix multipliers to a freshly spawned minion.
     *
     * <p>Never touches {@code Attribute.FOLLOW_RANGE}: follow/target range is a fixed
     * {@link SummonProfile#FOLLOW_RANGE} AI limit and is intentionally not a soul stat.</p>
     */
    public void applySoulSnapshotToMinion(LivingEntity mob, SoulInstance soul) {
        if (mob == null || soul == null) return;
        var svc = plugin.getSoulService();
        var snap = svc != null ? svc.getSnapshot(soul) : SoulStatSnapshot.fromSoul(soul);
        SoulBaseStats base = svc != null
                ? svc.effectiveBaseStats(soul)
                : (soul.getBaseStats() != null
                        ? soul.getBaseStats()
                        : SoulBaseStats.defaults(soul.getEntityType()));

        double speedMult = clamp(snap.speedMultiplier(), 0.5, 1.6);
        // Movement: base scaled by SWIFT with diminishing returns near the cap (higher speed => less affix impact).
        double maxMove = 14.0 / 43.17; // ~14 bps global cap
        double moveFloor = 1.0 / 43.17; // ~1 bps floor
        double baseMove = base.movementSpeed();
        if (!Double.isFinite(baseMove)) baseMove = 2.5 / 43.17;
        // Compatibility/parity: older souls may have normalized-low movement bases (~2.5 bps).
        // Never force a summoned mob below ~90% of its vanilla/default movement speed.
        try {
            double defMove = SoulBaseStats.defaults(mob.getType()).movementSpeed();
            if (Double.isFinite(defMove) && defMove > 0.0) {
                baseMove = Math.max(baseMove, defMove * 0.90);
            }
        } catch (Throwable ignored) {}
        baseMove = clamp(baseMove, moveFloor, maxMove);
        double appliedMove;
        if (speedMult >= 1.0) {
            double bonus = speedMult - 1.0;
            double effectiveness = (maxMove - baseMove) / maxMove;
            appliedMove = baseMove + baseMove * bonus * effectiveness;
        } else {
            double penalty = 1.0 - speedMult;
            double effectiveness = (baseMove - moveFloor) / maxMove;
            appliedMove = baseMove - baseMove * penalty * effectiveness;
        }
        appliedMove = clamp(appliedMove, moveFloor, maxMove);
        double desiredBps = appliedMove * 43.17;
        try {
            mob.getPersistentDataContainer().set(new NamespacedKey(plugin, "summon_move_bps"), PersistentDataType.DOUBLE, desiredBps);
        } catch (Throwable ignored) {}
        // Set attribute to desired speed; pathfinder runs at ~1.0x (intent-adjusted) in SummonMoveGoal.
        setBase(mob, Attribute.MOVEMENT_SPEED, appliedMove);
        // Flying speed is not a captured soul stat: keep the mob's own base, scaled by the same multiplier.
        try {
            var fly = mob.getAttribute(Attribute.FLYING_SPEED);
            if (fly != null) {
                double flyBase = fly.getBaseValue();
                double flyCap = Math.min(1.0, Math.max(flyBase * 1.6, 0.4));
                fly.setBaseValue(clamp(flyBase * speedMult, 0.05, flyCap));
            }
        } catch (Throwable ignored) {}
        // Offense / defense stats: absolute rolled values (affix multipliers where they exist).
        setBase(mob, Attribute.ATTACK_DAMAGE, Math.max(0.0, base.attackDamage() * clamp(snap.damageMultiplier(), 0.5, 3.0)));
        setBase(mob, Attribute.ATTACK_SPEED, base.attackSpeed());

        // Slime/Magma size normalization (safety/generic rule) must happen before final health is set,
        // because changing size resets a slime's max health to its size-based vanilla value.
        try {
            if (mob instanceof org.bukkit.entity.Slime slime) {
                int size = slime.getSize();
                if (size > 3) slime.setSize(2);
                else if (size < 1) slime.setSize(1);
            }
        } catch (Throwable ignored) {}

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

    public static boolean shouldRemoveVanillaGoal(com.destroystokyo.paper.entity.ai.Goal<?> goal) {
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

    public void playSummonEffects(Player player, Location at, int count) {
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

    /** Returns the active summoned soulId for the owner when all active minions share the same soul, else null. */
    public UUID getActiveSoulId(UUID ownerId) {
        if (ownerId == null || tracker == null) return null;
        List<LivingEntity> mins = tracker.getActiveMinions(ownerId);
        if (mins == null || mins.isEmpty()) return null;
        NamespacedKey soulKey = new NamespacedKey(plugin, "summon_soul_id");
        UUID found = null;
        for (LivingEntity le : mins) {
            if (le == null || le.isDead()) continue;
            String raw = le.getPersistentDataContainer().get(soulKey, PersistentDataType.STRING);
            if (raw == null || raw.isBlank()) return null; // unknown/legacy -> treat as non-matching group
            try {
                UUID id = UUID.fromString(raw);
                if (found == null) found = id;
                else if (!found.equals(id)) return null; // mixed souls active
            } catch (Exception ignored) {
                return null;
            }
        }
        return found;
    }

    /** Recalls/despawns all active minions for the owner. */
    public int recallAll(Player owner) {
        if (owner == null || tracker == null) return 0;
        int count = 0;
        try {
            List<LivingEntity> mins = tracker.getActiveMinions(owner.getUniqueId());
            count = mins != null ? mins.size() : 0;
        } catch (Throwable ignored) {}
        tracker.removeAllForPlayer(owner.getUniqueId());
        return count;
    }

    /** Spawns a full soul group for the given soul up to that soul's authoritative effective capacity (exclusive single-group model). */
    public int spawnSoulGroupToCap(Player player, SummonProfile profile, SoulInstance soul, LivingEntity target) {
        if (player == null || profile == null) return 0;
        Location spawnLoc = resolveSpawnLocation(player, target);
        EffectiveSummonStats stats = EffectiveSummonStats.resolve(player, profile, config, plugin.getSummonProgression());
        // Exclusive model: one active soul group at a time. Soul capacity is authoritative for group size.
        int targetCount = 1;
        try {
            var soulService = plugin.getSoulService();
            if (soul != null && soulService != null) {
                targetCount = Math.max(1, soulService.effectiveGroupCapacity(soul));
            } else {
                targetCount = Math.max(1, stats.maxActive);
            }
        } catch (Throwable t) {
            targetCount = Math.max(1, stats.maxActive);
        }
        int activeType = 0;
        try { activeType = tracker.getActiveCount(player.getUniqueId(), profile.name); } catch (Throwable ignored) {}
        int toSpawn = Math.max(0, targetCount - activeType);
        if (toSpawn <= 0) return 0;

        GlovedPlugin.logDebug("[Summon] Spawning full soul group " + toSpawn + " x " + profile.mobType
                + " for " + player.getName() + " at " + spawnLoc + " (target " + targetCount + ", typeActive " + activeType
                + ", lvl " + stats.summoningLevel + ", soul=" + (soul != null ? soul.getSoulId() : "none") + ")");
        var result = SummonEntitySpawner.spawnConfiguredGroup(
                plugin, config, this, tracker, summonAI, player, profile, soul, target, spawnLoc, toSpawn,
                null);
        if (result.spawnedCount > 0) {
            GlovedPlugin.logDebug("[Summon] Spawned soul group " + result.spawnedCount + " for " + player.getName() + " (soul=" + (soul != null ? soul.getSoulId() : "none") + ")");
        }
        return result.spawnedCount;
    }

    public void reload(SummonConfig newConfig) {
        // config reference swapped externally
    }
}
