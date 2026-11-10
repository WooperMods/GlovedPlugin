package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Resolves owner AuraSkills combat stats for minion hits (crit/strength). Cached with short TTL.
 * Uses reflection/fallbacks to stay compatible across AuraSkills 2.x API shapes.
 */
public final class SummonOwnerCombatStatsResolver {
    private static final long TTL_MS = 500L;
    private final Supplier<SummonConfig> config;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile boolean warnedUnavailable;

    private record CacheEntry(OwnerCombatStats stats, long expiresAtMs) {}

    public SummonOwnerCombatStatsResolver(Supplier<SummonConfig> config) {
        this.config = config;
    }

    public void clear() { cache.clear(); }
    public void onPlayerQuit(UUID id) { if (id != null) cache.remove(id); }

    public OwnerCombatStats resolve(Player owner) {
        if (owner == null) return OwnerCombatStats.unavailable();
        return resolve(owner.getUniqueId());
    }

    public OwnerCombatStats resolve(UUID ownerId) {
        if (ownerId == null) return OwnerCombatStats.unavailable();
        Player p = Bukkit.getPlayer(ownerId);
        if (p == null || !p.isOnline()) return OwnerCombatStats.unavailable();
        long now = System.currentTimeMillis();
        CacheEntry ce = cache.get(ownerId);
        if (ce != null && ce.expiresAtMs > now) return ce.stats;
        OwnerCombatStats stats = read(ownerId);
        cache.put(ownerId, new CacheEntry(stats, now + TTL_MS));
        return stats;
    }

    private OwnerCombatStats read(UUID ownerId) {
        try {
            if (Bukkit.getPluginManager().getPlugin("AuraSkills") == null) return OwnerCombatStats.unavailable();
            Class<?> apiClass = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object api = apiClass.getMethod("get").invoke(null);
            if (api == null) return OwnerCombatStats.unavailable();
            Object user = apiClass.getMethod("getUser", UUID.class).invoke(api, ownerId);
            if (user == null) return OwnerCombatStats.unavailable();

            double critChance = statLevel(user, "crit_chance", "CRIT_CHANCE");
            // Prefer effective crit_damage trait/bonus; fall back to stat level.
            double critDamage = effectiveTraitLevel(api, user, "crit_damage", "CRIT_DAMAGE");
            if (critDamage <= 0.0) critDamage = statLevel(user, "crit_damage", "CRIT_DAMAGE");
            double attackDamageTrait = effectiveTraitLevel(api, user, "attack_damage", "ATTACK_DAMAGE");
            double strength = statLevel(user, "strength", "STRENGTH");
            double health = statLevel(user, "health", "HEALTH");
            if (attackDamageTrait <= 0.0 && strength > 0.0) {
                // Fallback approximation: Strength stat contributes attack damage trait (default modifier 0.4 per point).
                attackDamageTrait = strength * 0.4;
            }
            return new OwnerCombatStats(true,
                    sanitize(critChance), sanitize(critDamage), sanitize(attackDamageTrait), sanitize(strength), sanitize(health));
        } catch (Throwable t) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                GlovedPlugin.logDebug("[SummonStats] AuraSkills stat resolution unavailable: " + t);
            }
            return OwnerCombatStats.unavailable();
        }
    }

    private static double sanitize(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.max(0.0, v);
    }

    private static double statLevel(Object user, String id, String enumName) {
        try {
            Class<?> statsClass = Class.forName("dev.aurelium.auraskills.api.stat.Stats");
            Object stat = statsClass.getField(enumName).get(null);
            Object val = user.getClass().getMethod("getStatLevel", Class.forName("dev.aurelium.auraskills.api.stat.Stat")).invoke(user, stat);
            if (val instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {}
        try {
            Object val = user.getClass().getMethod("getStatLevel", String.class).invoke(user, id);
            if (val instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {}
        // Try via NamespacedId/registry lookup if direct helpers unavailable
        try {
            Class<?> nid = Class.forName("dev.aurelium.auraskills.api.registry.NamespacedId");
            Object namespaced = nid.getMethod("fromDefault", String.class).invoke(null, id);
            Class<?> statClass = Class.forName("dev.aurelium.auraskills.api.stat.Stat");
            Object val = user.getClass().getMethod("getStatLevel", statClass).invoke(user, namespaced);
            if (val instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {}
        return 0.0;
    }

    private static double effectiveTraitLevel(Object api, Object user, String id, String enumName) {
        try {
            Class<?> traitsClass = Class.forName("dev.aurelium.auraskills.api.trait.Traits");
            Object trait = traitsClass.getField(enumName).get(null);
            Object val = user.getClass().getMethod("getEffectiveTraitLevel", Class.forName("dev.aurelium.auraskills.api.trait.Trait")).invoke(user, trait);
            if (val instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {}
        try {
            // Resolve trait instance from global registry, then query effective trait level
            Class<?> nid = Class.forName("dev.aurelium.auraskills.api.registry.NamespacedId");
            Object namespaced = nid.getMethod("fromDefault", String.class).invoke(null, id);
            Object global = api.getClass().getMethod("getGlobalRegistry").invoke(api);
            Object trait = global.getClass().getMethod("getTrait", nid).invoke(global, namespaced);
            if (trait != null) {
                Class<?> traitClass = Class.forName("dev.aurelium.auraskills.api.trait.Trait");
                Object val = user.getClass().getMethod("getEffectiveTraitLevel", traitClass).invoke(user, trait);
                if (val instanceof Number n) return n.doubleValue();
            }
        } catch (Throwable ignored) {}
        return 0.0;
    }
}
