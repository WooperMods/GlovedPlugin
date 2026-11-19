package dev.gloved.glovedplugin.summon.runtime;

import com.destroystokyo.paper.entity.ai.MobGoals;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.data.SummonSharedData;
import dev.gloved.glovedplugin.summon.nms.api.SummonNMS;
import dev.gloved.glovedplugin.summon.soul.domain.SoulConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
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
    private final SummonCombatService combatService;
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    // Soul cast cooldowns (per-player), ms timestamps
    private final Map<UUID, Long> soulCastCooldowns = new ConcurrentHashMap<>();

    public SummonManager(GlovedPlugin plugin, SummonConfig config, SummonInstanceTracker tracker, SummonCombatService combatService) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
        this.combatService = combatService;
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
                plugin, config, this, tracker, combatService, player, profile, soul, target, spawnLoc, toSpawn,
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
     * Team/loadout overhaul: 1 summon per soul slot, full effective per-summon stats (no group division).
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
        final int groupSize = 1; // team/loadout: one summon per soul slot, full stats

        // Movement speed is tier-based (Normal/Fast/Very Fast/Insane): tier 1 = normal mob speed,
        // tiers 2-4 apply permanent Speed I/II/III potion effects. SWIFT/movement modifiers removed.
        int moveTier = base.movementSpeedTier();
        double baseMove;
        try {
            baseMove = SoulBaseStats.defaults(mob.getType()).movementSpeed();
            // defaults().movementSpeed is now tier-encoded in some cases after normalization helpers,
            // but raw attribute defaults are needed here; fall back to live attribute base if odd.
            if (!Double.isFinite(baseMove) || baseMove <= 0.0 || baseMove <= 4.5) {
                var mv = mob.getAttribute(Attribute.MOVEMENT_SPEED);
                baseMove = (mv != null && Double.isFinite(mv.getBaseValue()) && mv.getBaseValue() > 0.0)
                        ? mv.getBaseValue() : 0.23;
            }
        } catch (Throwable ignored) {
            baseMove = 0.23;
        }
        // Prefer mob type's vanilla/default movement attribute as "normal" base.
        try {
            var defAttr = mob.getType().getDefaultAttributes();
            if (defAttr != null) {
                var inst = defAttr.getAttribute(Attribute.MOVEMENT_SPEED);
                if (inst != null && Double.isFinite(inst.getBaseValue()) && inst.getBaseValue() > 0.0) {
                    baseMove = inst.getBaseValue();
                }
            }
        } catch (Throwable ignored) {}
        setBase(mob, Attribute.MOVEMENT_SPEED, baseMove);
        try {
            int amplifier = SoulBaseStats.speedPotionAmplifierForTier(moveTier);
            if (amplifier < 0) {
                mob.removePotionEffect(PotionEffectType.SPEED);
            } else {
                mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, amplifier, false, false, false));
            }
        } catch (Throwable ignored) {}
        try {
            double desiredBps = baseMove * 43.17 * (1.0 + Math.max(0, SoulBaseStats.speedPotionAmplifierForTier(moveTier) + 1) * 0.20 * (moveTier > 1 ? 1 : 0));
            if (moveTier <= 1) desiredBps = baseMove * 43.17;
            mob.getPersistentDataContainer().set(new NamespacedKey(plugin, "summon_move_bps"), PersistentDataType.DOUBLE, desiredBps);
        } catch (Throwable ignored) {}
        // Flying speed not a soul stat/tier; keep mob's own base.
        try {
            var fly = mob.getAttribute(Attribute.FLYING_SPEED);
            if (fly != null) {
                var defAttr = mob.getType().getDefaultAttributes();
                double flyBase = fly.getBaseValue();
                if (defAttr != null) {
                    var inst = defAttr.getAttribute(Attribute.FLYING_SPEED);
                    if (inst != null && Double.isFinite(inst.getBaseValue()) && inst.getBaseValue() > 0.0) flyBase = inst.getBaseValue();
                }
                fly.setBaseValue(flyBase);
            }
        } catch (Throwable ignored) {}
        // Offense / defense stats: absolute rolled values (affix multipliers where they exist), full per-summon.
        setBase(mob, Attribute.ATTACK_DAMAGE, Math.max(0.0, (base.attackDamage() * clamp(snap.damageMultiplier(), 0.5, 3.0)) / groupSize));
        setBase(mob, Attribute.ATTACK_SPEED, base.attackSpeed());
        // DEF: soul armor stat (base cap 13.75, ~55% base negation; enhancement can exceed caps). TENACIOUS scales DEF.
        double defMult = 1.0 + Math.max(0.0, snap.tenacityBonus());
        setBase(mob, Attribute.ARMOR, Math.max(SoulBaseStats.MIN_DEFENSE, base.defense() * defMult));

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
        // Soul's final (rolled+enhanced base * VIGOROUS + owner AuraSkills health bonus), full per-summon.
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
                double newMax = Math.max(1.0, (scaledBase + ownerHealthBonus) / groupSize);
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
            dev.gloved.glovedplugin.summon.vfx.SummonVfx.playSummonSpawn(player, at, count);
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
        try {
            if (plugin.getSummonRespawnService() != null) {
                plugin.getSummonRespawnService().cancelAll(owner.getUniqueId(), "recall");
            }
        } catch (Throwable ignored) {}
        tracker.removeAllForPlayer(owner.getUniqueId());
        return count;
    }

    public int recallActiveTeam(Player owner) {
        if (owner != null) {
            try {
                if (plugin.getSummonRespawnService() != null) {
                    plugin.getSummonRespawnService().cancelAll(owner.getUniqueId(), "recall-team");
                }
            } catch (Throwable ignored) {}
        }
        return recallAll(owner);
    }

    public int getActiveTeamIndex(UUID ownerId) {
        return tracker != null ? tracker.getActiveTeamIndex(ownerId) : -1;
    }

    public boolean hasActiveTeamSummons(UUID ownerId) {
        try {
            return tracker != null && !tracker.getActiveMinions(ownerId).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    public static final class TeamSpawnResult {
        public final boolean ok;
        public final int spawned;
        public final int failed;
        public final List<UUID> spawnedIds;
        public final String reason;

        public TeamSpawnResult(boolean ok, int spawned, int failed, List<UUID> spawnedIds, String reason) {
            this.ok = ok;
            this.spawned = spawned;
            this.failed = failed;
            this.spawnedIds = spawnedIds != null ? spawnedIds : List.of();
            this.reason = reason;
        }
    }

    /** Spawns 1 summon per resolved team slot (full per-summon stats, team-tagged). Preferred rollback on partial failure. */
    public TeamSpawnResult spawnResolvedTeam(Player player, int teamIndex, List<TeamSlotSpawn> slots, LivingEntity target) {
        if (player == null || slots == null || slots.isEmpty()) {
            return new TeamSpawnResult(false, 0, 0, List.of(), "empty");
        }
        List<UUID> spawnedIds = new ArrayList<>();
        int failed = 0;
        Location spawnLoc = resolveSpawnLocation(player, target);
        for (TeamSlotSpawn slot : slots) {
            if (slot == null || slot.soul == null) { failed++; continue; }
            try {
                SummonProfile profile = config != null ? config.getProfile(slot.soul.getEntityType()) : null;
                if (profile == null) profile = config != null ? config.getProfile(slot.soul.getFamilyKey()) : null;
                if (profile == null) {
                    GlovedPlugin.logDebug("[Summon] Team spawn: no summon profile for soul " + slot.soul.getFamilyKey());
                    failed++;
                    continue;
                }
                var result = SummonEntitySpawner.spawnConfiguredGroup(
                        plugin, config, this, tracker, combatService, player, profile, slot.soul, target, spawnLoc, 1,
                        null, teamIndex, slot.slotIndex);
                if (result != null && result.spawnedCount > 0 && result.spawnedIds != null) {
                    spawnedIds.addAll(result.spawnedIds);
                } else {
                    failed++;
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[Summon] Team spawn failed for slot " + slot.slotIndex + ": " + t);
                failed++;
            }
        }
        if (failed > 0) {
            // Preferred rollback: keep team state consistent
            for (UUID id : spawnedIds) {
                try { tracker.despawn(id); } catch (Throwable ignored) {}
            }
            return new TeamSpawnResult(false, 0, failed, List.of(), "partial-failure-rolled-back");
        }
        return new TeamSpawnResult(true, spawnedIds.size(), 0, spawnedIds, "ok");
    }

    public static final class TeamSlotSpawn {
        public final int slotIndex;
        public final SoulInstance soul;
        public TeamSlotSpawn(int slotIndex, SoulInstance soul) {
            this.slotIndex = slotIndex;
            this.soul = soul;
        }
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
                plugin, config, this, tracker, combatService, player, profile, soul, target, spawnLoc, toSpawn,
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
