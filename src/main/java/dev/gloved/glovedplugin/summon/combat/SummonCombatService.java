package dev.gloved.glovedplugin.summon.combat;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.core.logging.DebugLog;
import dev.gloved.glovedplugin.lootr.integration.EliteMobsIntegration;
import dev.gloved.glovedplugin.summon.ai.movement.ReachabilityService;
import dev.gloved.glovedplugin.summon.config.SummonConfig;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.progression.NoopSummonProgressionBridge;
import dev.gloved.glovedplugin.summon.progression.SummonAbilityService;
import dev.gloved.glovedplugin.summon.progression.SummonProgressionBridge;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;
import dev.gloved.glovedplugin.summon.runtime.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.runtime.SummonManager;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-legacy summon combat/support service (extracted from legacy summon AI control code).
 * Provides shared APIs used by NMS goals/brains/runtime: attack cooldowns, attack execution,
 * hit pipeline, recent-damager tracking, and unreachable caching. Does NOT control summons
 * (no monitor loop, no targeting/movement/follow control).
 */
public class SummonCombatService {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private final ReachabilityService reachabilityService;
    private final ConcurrentHashMap<UUID, Long> lastAttackTickByMinion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> pendingProjectileCooldownUntilTick = new ConcurrentHashMap<>();
    private SummonManager summonManager;
    private SummonConfig summonConfig;
    private SummonProgressionBridge progression = new NoopSummonProgressionBridge();
    private SummonAbilityService abilityService;

    // Recent-damager tracking (shared/static for cross-cutting use)
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> recentDamagers = new ConcurrentHashMap<>();
    private static final long DAMAGER_TTL_MS = 10_000L;
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> minionRecentDamagers = new ConcurrentHashMap<>();
    private static final long MINION_DAMAGER_TTL_MS = 10_000L;

    // Unreachable cache (shared/static)
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Long>> unreachableCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Location>> unreachableLocationCache = new ConcurrentHashMap<>();
    private static final long UNREACHABLE_TTL_MS = 10_000L;

    // Trait ICDs (bounded proc behavior)
    private static final ConcurrentHashMap<UUID, Long> unstableIcd = new ConcurrentHashMap<>();

    public SummonCombatService(GlovedPlugin plugin, SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.reachabilityService = new ReachabilityService(plugin);
    }

    public void setSummon(SummonConfig summonConfig, SummonManager summonManager) {
        this.summonConfig = summonConfig;
        this.summonManager = summonManager;
    }

    public void setProgression(SummonProgressionBridge progression) {
        this.progression = progression != null ? progression : new NoopSummonProgressionBridge();
    }

    public void setAbilityService(SummonAbilityService abilityService) {
        this.abilityService = abilityService;
    }

    public GlovedPlugin getPlugin() { return plugin; }
    public SummonInstanceTracker getTracker() { return tracker; }
    public ReachabilityService getReachabilityService() { return reachabilityService; }
    public SummonProgressionBridge getProgression() { return progression; }
    public SummonAbilityService getAbilityService() { return abilityService; }
    public SummonConfig getSummonConfig() { return summonConfig; }
    public SummonManager getSummonManager() { return summonManager; }

    // --- Attack cooldowns ---

    public boolean canAttackNow(LivingEntity minion, Player owner, long nowTick) {
        if (minion == null) return false;
        long last = lastAttackTickByMinion.getOrDefault(minion.getUniqueId(), Long.MIN_VALUE / 4);
        return (nowTick - last) >= requiredArcTicks(owner, minion);
    }

    public boolean tryConsumeAttackCooldown(LivingEntity minion, Player owner, long nowTick) {
        if (!canAttackNow(minion, owner, nowTick)) return false;
        recordAttack(minion, nowTick);
        return true;
    }

    public void recordAttack(LivingEntity minion, long nowTick) {
        if (minion == null) return;
        lastAttackTickByMinion.put(minion.getUniqueId(), nowTick);
    }

    public void resetAttackCooldown(LivingEntity minion) {
        if (minion == null) return;
        lastAttackTickByMinion.remove(minion.getUniqueId());
    }

    public void clearAttackCooldown(LivingEntity minion) {
        resetAttackCooldown(minion);
        try {
            if (minion != null) pendingProjectileCooldownUntilTick.remove(minion.getUniqueId());
        } catch (Throwable ignored) {}
    }

    public void markPendingProjectileCooldownConsumed(LivingEntity minion, long nowTick) {
        if (minion == null) return;
        pendingProjectileCooldownUntilTick.put(minion.getUniqueId(), nowTick + 10L);
    }

    public boolean pollPendingProjectileCooldownConsumed(UUID minionId, long nowTick) {
        if (minionId == null) return false;
        Long until = pendingProjectileCooldownUntilTick.get(minionId);
        if (until == null) return false;
        if (nowTick > until) {
            pendingProjectileCooldownUntilTick.remove(minionId, until);
            return false;
        }
        return pendingProjectileCooldownUntilTick.remove(minionId, until);
    }

    // --- Soul/minion helpers ---

    private SoulInstance resolveMinionSoul(LivingEntity minion) {
        try {
            if (minion == null || plugin.getSoulService() == null) return null;
            NamespacedKey k = new NamespacedKey(plugin, "summon_soul_id");
            String id = minion.getPersistentDataContainer().get(k, PersistentDataType.STRING);
            if (id == null || id.isBlank()) return null;
            return plugin.getSoulService().getSoulCached(java.util.UUID.fromString(id));
        } catch (Throwable ignored) { return null; }
    }

    private SoulStatSnapshot resolveMinionSnapshot(LivingEntity minion) {
        try {
            if (minion == null || plugin.getSoulService() == null) return SoulStatSnapshot.identity();
            NamespacedKey k = new NamespacedKey(plugin, "summon_soul_id");
            String id = minion.getPersistentDataContainer().get(k, PersistentDataType.STRING);
            if (id == null || id.isBlank()) return SoulStatSnapshot.identity();
            return plugin.getSoulService().getSnapshot(java.util.UUID.fromString(id));
        } catch (Throwable ignored) { return SoulStatSnapshot.identity(); }
    }

    private String resolveMinionProfileName(LivingEntity minion) {
        try {
            if (minion == null || plugin == null) return null;
            var pdc = minion.getPersistentDataContainer();
            String v = pdc.get(new NamespacedKey(plugin, "summon_profile"), PersistentDataType.STRING);
            if (v == null || v.isBlank()) v = pdc.get(new NamespacedKey(plugin, "summon_family"), PersistentDataType.STRING);
            return v;
        } catch (Throwable ignored) { return null; }
    }

    // --- Recent damagers (static/shared) ---

    public static void recordRecentDamager(Player player, LivingEntity damager) {
        if (player == null || damager == null) return;
        recentDamagers.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(damager.getUniqueId(), System.currentTimeMillis());
    }

    public static List<UUID> getRecentDamagers(Player player) {
        if (player == null) return Collections.emptyList();
        var map = recentDamagers.get(player.getUniqueId());
        if (map == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        List<UUID> result = new ArrayList<>();
        for (var e : map.entrySet()) {
            if (now - e.getValue() <= DAMAGER_TTL_MS) {
                result.add(e.getKey());
            } else {
                map.remove(e.getKey());
            }
        }
        if (map.isEmpty()) recentDamagers.remove(player.getUniqueId());
        return result;
    }

    public static void recordRecentDamager(LivingEntity minion, LivingEntity damager) {
        if (minion == null || damager == null) return;
        minionRecentDamagers.computeIfAbsent(minion.getUniqueId(), k -> new ConcurrentHashMap<>())
                .put(damager.getUniqueId(), System.currentTimeMillis());
    }

    public static List<UUID> getRecentDamagers(LivingEntity minion) {
        if (minion == null) return Collections.emptyList();
        var map = minionRecentDamagers.get(minion.getUniqueId());
        if (map == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        List<UUID> result = new ArrayList<>();
        for (var e : map.entrySet()) {
            if (now - e.getValue() <= MINION_DAMAGER_TTL_MS) {
                result.add(e.getKey());
            } else {
                map.remove(e.getKey());
            }
        }
        if (map.isEmpty()) minionRecentDamagers.remove(minion.getUniqueId());
        return result;
    }

    public void clearRecentDamagers(LivingEntity minion) {
        if (minion == null) return;
        minionRecentDamagers.remove(minion.getUniqueId());
    }

    // --- Unreachable cache (static/shared) ---

    public static boolean isCachedUnreachable(UUID minionId, UUID targetId, Location currentLoc) {
        var map = unreachableCache.get(minionId);
        if (map == null) return false;
        long now = System.currentTimeMillis();
        Long exp = map.get(targetId);
        if (exp == null) return false;
        if (now > exp) {
            map.remove(targetId);
            var locMap = unreachableLocationCache.get(minionId);
            if (locMap != null) {
                locMap.remove(targetId);
                if (locMap.isEmpty()) unreachableLocationCache.remove(minionId);
            }
            if (map.isEmpty()) unreachableCache.remove(minionId);
            return false;
        }
        var locMap = unreachableLocationCache.get(minionId);
        if (locMap != null) {
            Location cachedLoc = locMap.get(targetId);
            if (cachedLoc != null && currentLoc != null) {
                if (cachedLoc.getWorld() != currentLoc.getWorld() || cachedLoc.distanceSquared(currentLoc) > 0.01) {
                    map.remove(targetId);
                    locMap.remove(targetId);
                    if (map.isEmpty()) unreachableCache.remove(minionId);
                    if (locMap.isEmpty()) unreachableLocationCache.remove(minionId);
                    return false;
                }
            }
        }
        return true;
    }

    public static void cacheUnreachable(UUID minionId, UUID targetId, Location locAtCache) {
        unreachableCache.computeIfAbsent(minionId, k -> new ConcurrentHashMap<>())
                .put(targetId, System.currentTimeMillis() + UNREACHABLE_TTL_MS);
        if (locAtCache != null) {
            unreachableLocationCache.computeIfAbsent(minionId, k -> new ConcurrentHashMap<>())
                    .put(targetId, locAtCache.clone());
        }
    }

    // --- Attack options ---

    public static final class AttackOptions {
        public double rawBaseDamage = 0.0;
        public String profileName = null;
        public boolean requireAndConsumeCooldown = false;
        public boolean recordCooldownAfterHit = false;
        public boolean consumeCooldownOnProtected = false;
        public boolean swingAnimation = false;
        public boolean forceOwnerAttackStrength = false;

        public static AttackOptions defaults() { return new AttackOptions(); }
        public static AttackOptions meleePipeline() {
            AttackOptions o = new AttackOptions();
            o.requireAndConsumeCooldown = true;
            o.swingAnimation = true;
            o.forceOwnerAttackStrength = true;
            o.consumeCooldownOnProtected = true;
            return o;
        }
        public AttackOptions rawBaseDamage(double v) { this.rawBaseDamage = v; return this; }
        public AttackOptions profileName(String v) { this.profileName = v; return this; }
        public AttackOptions requireAndConsumeCooldown(boolean v) { this.requireAndConsumeCooldown = v; return this; }
        public AttackOptions recordCooldownAfterHit(boolean v) { this.recordCooldownAfterHit = v; return this; }
        public AttackOptions consumeCooldownOnProtected(boolean v) { this.consumeCooldownOnProtected = v; return this; }
        public AttackOptions swingAnimation(boolean v) { this.swingAnimation = v; return this; }
        public AttackOptions forceOwnerAttackStrength(boolean v) { this.forceOwnerAttackStrength = v; return this; }
    }

    // --- Attack execution ---

    public double performSummonAttack(LivingEntity minion, Player owner, LivingEntity target, long nowTick, AttackOptions options) {
        AttackOptions opt = options != null ? options : AttackOptions.defaults();
        if (minion == null || owner == null || target == null || minion.isDead() || target.isDead()) return 0.0;
        try {
            if (CombatTargetRules.isProtected(plugin, owner, target)) {
                if (opt.consumeCooldownOnProtected) recordAttack(minion, nowTick);
                GlovedPlugin.logDebug("[SummonAttack] Skipped protected target minion=" + minion.getUniqueId()
                        + " target=" + target.getType() + "/" + target.getName() + " owner=" + owner.getName());
                return 0.0;
            }
            if (opt.requireAndConsumeCooldown && !tryConsumeAttackCooldown(minion, owner, nowTick)) {
                return 0.0;
            }
            String profileName = opt.profileName;
            if (profileName == null || profileName.isBlank()) {
                profileName = resolveMinionProfileName(minion);
            }
            if (opt.swingAnimation) {
                try { minion.swingMainHand(); } catch (Throwable ignored) {}
            }
            if (opt.forceOwnerAttackStrength) {
                try { forceFullAttackStrength(owner); } catch (Throwable ignored) {}
            }
            double dealt = applyMinionHit(minion, owner, target, opt.rawBaseDamage, nowTick, profileName);
            if (!opt.requireAndConsumeCooldown && opt.recordCooldownAfterHit) {
                recordAttack(minion, nowTick);
            }
            if (DebugLog.isEnabled()) {
                int ndt = -1; boolean inv = false;
                try { ndt = target.getNoDamageTicks(); } catch (Throwable ignored) {}
                try { inv = target.isInvulnerable(); } catch (Throwable ignored) {}
                GlovedPlugin.logDebug("[SummonAttack] result minion=" + minion.getUniqueId() + " target=" + target.getType() + "/" + target.getName()
                        + " dealt=" + dealt + " hpAfter=" + (target.isDead() ? "dead" : target.getHealth())
                        + " ndt=" + ndt + " inv=" + inv + " profile=" + profileName);
            }
            return dealt;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonAttack] Error performing attack for " + minion.getUniqueId() + ": " + t);
            return 0.0;
        }
    }

    public double performSummonAttack(LivingEntity minion, Player owner, LivingEntity target, long nowTick) {
        return performSummonAttack(minion, owner, target, nowTick, AttackOptions.defaults());
    }

    public double applySummonHit(LivingEntity summon, Player owner, LivingEntity target,
                                 double rawBaseDamage, long nowTick, String profileName) {
        return applyMinionHit(summon, owner, target, rawBaseDamage, nowTick, profileName);
    }

    public double applyMinionHit(LivingEntity minion, Player owner, LivingEntity target,
                                 double rawBaseDamage, long nowTick, String profileName) {
        SoulInstance soul = resolveMinionSoul(minion);
        var soulService = plugin.getSoulService();
        SoulStatSnapshot soulSnap = (soul != null && soulService != null)
                ? soulService.getSnapshot(soul) : SoulStatSnapshot.identity();

        int ownerLevel = EliteMobsIntegration.getCombatLevel(owner);
        boolean isElite = isEliteMob(target);

        int groupSize = 1;
        try {
            if (soul != null && soulService != null) groupSize = Math.max(1, soulService.effectiveGroupCapacity(soul));
        } catch (Throwable ignored) {}
        double soulBaseAtk;
        if (soul != null && soulService != null) {
            soulBaseAtk = soulService.effectiveBaseStats(soul).attackDamage() / groupSize;
        } else if (minion != null && minion.getAttribute(Attribute.ATTACK_DAMAGE) != null) {
            soulBaseAtk = minion.getAttribute(Attribute.ATTACK_DAMAGE).getBaseValue();
        } else {
            soulBaseAtk = Math.max(0.5, rawBaseDamage) / groupSize;
        }
        double eliteBonus = isElite ? Math.max(0.0, ownerLevel * 0.4) : 0.0;

        GlovedPlugin.logDebug("[SummonHit] begin minion=" + (minion != null ? minion.getUniqueId() : "null")
                + " owner=" + (owner != null ? owner.getName() : "null")
                + " target=" + (target != null ? target.getType() + "/" + target.getName() : "null")
                + " soulBaseAtk=" + soulBaseAtk + " eliteBonus=" + eliteBonus + " rawBaseArg=" + rawBaseDamage
                + " profile=" + profileName);

        double baseDamage = Math.max(0.5, soulBaseAtk + eliteBonus);

        try {
            if (summonConfig != null && summonConfig.isStrengthDamageEnabled() && progression != null) {
                var st = progression.resolveOwnerCombatStats(owner.getUniqueId());
                if (st != null && st.available()) {
                    double bonus = Math.max(0.0, st.attackDamageTrait()) * summonConfig.getStrengthDamagePerAttackDamageTrait();
                    bonus = Math.min(bonus, Math.max(0.0, summonConfig.getStrengthDamageMaxBonus()));
                    baseDamage += bonus;
                }
            }
        } catch (Throwable ignored) {}

        double soulDamageMult = Math.max(0.25, Math.min(4.0, soulSnap.damageMultiplier()));
        if (soul != null && soul.hasTrait(SoulTrait.FRENZIED)) soulDamageMult *= 1.08;
        if (soul != null && soul.hasTrait(SoulTrait.PACKBOUND)) {
            soulDamageMult *= packboundMultiplier(minion, owner, soul);
        }
        baseDamage = Math.max(0.5, baseDamage * soulDamageMult);

        double finalDamage = baseDamage;
        boolean crit = false;
        boolean targetDebuffedBefore = false;
        try {
            if (abilityService != null && summonConfig != null) {
                var hr = abilityService.applyCritAndDamage(owner, minion, target, baseDamage, summonConfig, soul, soulSnap);
                finalDamage = hr.damage;
                crit = hr.crit;
                targetDebuffedBefore = hr.targetDebuffedBefore;
            } else {
                try { if (target.getNoDamageTicks() > 0) target.setNoDamageTicks(0); } catch (Throwable ignored) {}
                DamageSource src = DamageSource.builder(DamageType.GENERIC)
                        .withDirectEntity(minion)
                        .withCausingEntity(minion != null ? minion : owner)
                        .build();
                try {
                    DamageProxy.beginPipelineDamage();
                    target.damage(baseDamage, src);
                } finally {
                    DamageProxy.endPipelineDamage();
                }
            }
            applyKnockback(minion, owner, target);
            try {
                progression.onMinionHit(owner, minion, target, profileName, finalDamage);
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SummonXP] Hit hook dispatch error: " + t);
            }
            try {
                if (abilityService != null && summonConfig != null) {
                    abilityService.onAfterPrimaryHit(owner, minion, target, profileName, finalDamage, baseDamage, crit, targetDebuffedBefore, summonConfig, soul, soulSnap);
                }
            } catch (Throwable t) {
                GlovedPlugin.logDebug("[SummonAbility] Ability hook dispatch error: " + t);
            }
            try { applyUnstableTrait(owner, minion, target, soul, finalDamage, baseDamage, nowTick); } catch (Throwable ignored) {}
        } catch (Exception e) {
            GlovedPlugin.logDebug("[Summon] Minion " + minion.getUniqueId() + " damage exception: " + e);
        }
        return finalDamage;
    }

    // --- Helpers (extracted) ---

    public static boolean isEliteMob(LivingEntity entity) {
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("EliteMobs");
            if (p == null) return false;
            ClassLoader cl = p.getClass().getClassLoader();
            Class<?> eliteClass = Class.forName("com.magmaguy.elitemobs.mobconstructor.EliteEntity", true, cl);
            return eliteClass.isInstance(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void applyKnockback(LivingEntity minion, LivingEntity owner, LivingEntity target) {
        Vector direction = owner.getLocation().toVector().subtract(target.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() < 1.0E-4) {
            direction = minion.getLocation().toVector().subtract(target.getLocation().toVector());
            direction.setY(0);
            if (direction.lengthSquared() < 1.0E-4) return;
        }
        direction.normalize();
        double strength = 0.4;
        try {
            var knockback = minion.getAttribute(Attribute.ATTACK_KNOCKBACK);
            if (knockback != null) strength += Math.max(0, knockback.getValue());
        } catch (Exception ignored) {}
        target.knockback(strength, direction.getX(), direction.getZ());
    }

    public static void forceFullAttackStrength(Player player) {
        try {
            java.lang.reflect.Method m = player.getClass().getMethod("setCooledAttackStrength", float.class);
            m.invoke(player, 1.0f);
            return;
        } catch (Exception ignored) {}
        try {
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            java.lang.reflect.Method getHandle = craftPlayerClass.getMethod("getHandle");
            Object handle = getHandle.invoke(player);
            int cooldownPeriod = (int) player.getCooldownPeriod();
            Class<?> cls = handle.getClass();
            java.lang.reflect.Field f = null;
            while (cls != null) {
                try {
                    f = cls.getDeclaredField("attackStrengthTicker");
                    break;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            if (f != null) {
                f.setAccessible(true);
                f.setInt(handle, cooldownPeriod);
            }
        } catch (Exception ignored) {}
    }

    private static long requiredArcTicks(Player player) {
        return requiredArcTicks(player, null);
    }

    private static long requiredArcTicks(Player player, LivingEntity minion) {
        double aps = -1.0;
        if (minion != null) {
            try {
                var attr = minion.getAttribute(Attribute.ATTACK_SPEED);
                if (attr != null && attr.getValue() > 0.0) {
                    aps = attr.getValue();
                }
            } catch (Exception ignored) {}
            if (aps > 2.0) {
                aps = SoulBaseStats.normalizeAttackSpeedAps(aps);
            }
            if (aps <= 0.0) {
                aps = SoulBaseStats.AVG_ATTACK_SPEED_APS;
            }
        } else if (player != null) {
            try {
                double period = player.getCooldownPeriod();
                if (period > 0.0) aps = 20.0 / period;
            } catch (Exception ignored) {}
        }
        double periodTicks = -1.0;
        if (aps > 0.0) {
            if (aps > 2.0) aps = SoulBaseStats.normalizeAttackSpeedAps(aps);
            aps = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS,
                    Math.min(SoulBaseStats.MAX_ATTACK_SPEED_APS, aps));
            periodTicks = 20.0 / aps;
        }
        if (periodTicks <= 0.0) periodTicks = 20.0;
        if (minion != null) {
            return Math.max(1L, Math.round(periodTicks));
        }
        double minAttackStrength = 0.9;
        try {
            var plugin = GlovedPlugin.getPlugin(GlovedPlugin.class);
            if (plugin != null) {
                var registry = plugin.getWeaponRegistry();
                if (registry != null) {
                    minAttackStrength = registry.getMinAttackStrength();
                }
            }
        } catch (Exception ignored) {}
        double requiredTicks = minAttackStrength * periodTicks - 0.5;
        if (requiredTicks < 0) requiredTicks = 0;
        return Math.max(1L, Math.round(requiredTicks));
    }

    private double packboundMultiplier(LivingEntity minion, Player owner, SoulInstance soul) {
        try {
            if (minion == null || owner == null || soul == null || tracker == null) return 1.0;
            String fam = soul.getFamilyKey();
            int same = 0;
            for (LivingEntity le : tracker.getActiveMinions(owner.getUniqueId())) {
                if (le == null || le.isDead()) continue;
                if (le.getWorld() != minion.getWorld()) continue;
                if (le.getLocation().distanceSquared(minion.getLocation()) > 256.0) continue;
                try {
                    var pdc = le.getPersistentDataContainer();
                    String f = pdc.get(new NamespacedKey(plugin, "summon_family"), PersistentDataType.STRING);
                    if (f == null) f = pdc.get(new NamespacedKey(plugin, "summon_profile"), PersistentDataType.STRING);
                    if (fam.equalsIgnoreCase(f)) same++;
                } catch (Throwable ignored) {}
            }
            if (same >= 2) {
                return 1.0 + Math.min(0.12, (same - 1) * 0.03);
            }
        } catch (Throwable ignored) {}
        return 1.0;
    }

    private void applyUnstableTrait(Player owner, LivingEntity minion, LivingEntity primaryTarget, SoulInstance soul, double finalDamage, double baseDamage, long nowTick) {
        if (owner == null || minion == null || primaryTarget == null || soul == null) return;
        if (!soul.hasTrait(SoulTrait.UNSTABLE)) return;
        Long last = unstableIcd.get(minion.getUniqueId());
        if (last != null && nowTick - last < 80) return;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > 0.18) return;
        unstableIcd.put(minion.getUniqueId(), nowTick);
        double radius = 2.75;
        double aoe = Math.max(0.75, Math.min(6.0, Math.max(finalDamage, baseDamage) * 0.35));
        int hit = 0;
        try {
            if (primaryTarget.getWorld() != null) {
                dev.gloved.glovedplugin.summon.vfx.SummonVfx.playUnstableBurst(primaryTarget.getLocation());
            }
        } catch (Throwable ignored) {}
        for (Entity e : primaryTarget.getNearbyEntities(radius, radius, radius)) {
            if (hit >= 3) break;
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            if (le.getUniqueId().equals(primaryTarget.getUniqueId())) continue;
            if (le.getUniqueId().equals(owner.getUniqueId()) || le.getUniqueId().equals(minion.getUniqueId())) continue;
            try {
                var pdc = le.getPersistentDataContainer();
                if (pdc.has(new NamespacedKey(plugin, "summon_mobtype"), PersistentDataType.STRING)) continue;
            } catch (Throwable ignored) {}
            if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
            try {
                try { if (le.getNoDamageTicks() > 0) le.setNoDamageTicks(0); } catch (Throwable ignored) {}
                DamageSource src = DamageSource.builder(DamageType.GENERIC)
                        .withDirectEntity(minion)
                        .withCausingEntity(minion != null ? minion : owner)
                        .build();
                try {
                    DamageProxy.beginPipelineDamage();
                    le.damage(aoe, src);
                } finally {
                    DamageProxy.endPipelineDamage();
                }
                try { progression.recordKillCredit(owner, minion, le, null, nowTick); } catch (Throwable ignored) {}
                hit++;
            } catch (Throwable ignored) {}
        }
        GlovedPlugin.logDebug("[SummonTrait] UNSTABLE proc by minion " + minion.getUniqueId() + " aoe=" + aoe + " extraTargets=" + hit);
    }
}
