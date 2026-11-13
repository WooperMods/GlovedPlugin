package dev.gloved.glovedplugin.summon.soul.service;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulDatabase;
import dev.gloved.glovedplugin.summon.soul.db.SoulRepository;
import dev.gloved.glovedplugin.summon.nms.api.SummonEntityInjector;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import dev.gloved.glovedplugin.summon.ai.legacy.SummonAI;
import dev.gloved.glovedplugin.summon.config.SummonProfile;
import dev.gloved.glovedplugin.summon.runtime.SummonManager;
import dev.gloved.glovedplugin.summon.soul.domain.EntityCategory;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixType;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulGenerator;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.domain.SoulStatSnapshot;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import dev.gloved.glovedplugin.summon.soul.item.SoulDropItem;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;

/**
 * High-level soul operations: capture, generation, persistence, vault/loadout/cast, progression hooks.
 */
public class SoulService {
    public record CaptureResult(boolean captured, SoulInstance soul, String reason, boolean convertedToEssence) {}
    public record CollectResult(boolean stored, boolean convertedToEssence, int essence, String reason) {
        public static CollectResult okStored() { return new CollectResult(true, false, 0, "stored"); }
        public static CollectResult okEssence(int amt) { return new CollectResult(false, true, amt, "vault-full-converted"); }
        public static CollectResult fail(String r) { return new CollectResult(false, false, 0, r); }
    }

    private static final Gson GSON = new Gson();
    private static final Type PITY_TYPE = new TypeToken<Map<String, Number>>() {}.getType();

    private final GlovedPlugin plugin;
    private final SoulDatabase database;
    private final SoulRepository repository;

    // Ephemeral DR timestamps (per-player per-family) + short global DR
    private final Map<UUID, Map<String, Long>> lastCaptureAt = new ConcurrentHashMap<>();
    // Hot-path cache: selected soul instance per player (invalidated on relevant changes)
    private final Map<UUID, SoulInstance> selectedSoulCache = new ConcurrentHashMap<>();
    // Hot-path caches for spawned minion combat/stat lookups
    private final Map<UUID, SoulInstance> soulByIdCache = new ConcurrentHashMap<>();
    private final Map<UUID, SoulStatSnapshot> snapshotBySoulIdCache = new ConcurrentHashMap<>();

    public SoulService(GlovedPlugin plugin, SoulDatabase database) {
        this.plugin = plugin;
        this.database = database;
        this.repository = new SoulRepository(plugin, database);
    }

    public void invalidateSelectedCache(UUID owner) {
        if (owner != null) selectedSoulCache.remove(owner);
    }

    private void invalidateSoulCache(UUID soulId) {
        if (soulId == null) return;
        soulByIdCache.remove(soulId);
        snapshotBySoulIdCache.remove(soulId);
    }

    public SoulInstance getSoulCached(UUID soulId) {
        if (soulId == null) return null;
        SoulInstance cached = soulByIdCache.get(soulId);
        if (cached != null) return cached;
        SoulInstance s = getSoul(soulId);
        if (s != null) soulByIdCache.put(soulId, s);
        return s;
    }

    public SoulStatSnapshot getSnapshot(UUID soulId) {
        if (soulId == null) return SoulStatSnapshot.identity();
        SoulStatSnapshot cached = snapshotBySoulIdCache.get(soulId);
        if (cached != null) return cached;
        SoulInstance soul = getSoulCached(soulId);
        SoulStatSnapshot snap = soul != null ? SoulStatSnapshot.fromSoul(soul) : SoulStatSnapshot.identity();
        snapshotBySoulIdCache.put(soulId, snap);
        return snap;
    }

    public SoulStatSnapshot getSnapshot(SoulInstance soul) {
        if (soul == null) return SoulStatSnapshot.identity();
        SoulStatSnapshot snap = SoulStatSnapshot.fromSoul(soul);
        snapshotBySoulIdCache.put(soul.getSoulId(), snap);
        soulByIdCache.put(soul.getSoulId(), soul);
        return snap;
    }

    public SoulRepository getRepository() { return repository; }

    /** Essence balance for an owner (0 when the database is unavailable). */
    public int getEssence(UUID owner) {
        if (owner == null) return 0;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            return repository.getEssence(owner, cap);
        } catch (SQLException e) {
            return 0;
        }
    }

    /** Atomically spends essence. Returns false when the balance is too low. */
    public boolean spendEssence(UUID owner, int amount) {
        if (owner == null) return false;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            return repository.spendEssence(owner, amount, cap);
        } catch (SQLException e) {
            return false;
        }
    }

    /** Adds essence to an owner (admin/command path). */
    public void addEssence(UUID owner, int amount) {
        if (owner == null || amount <= 0) return;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            repository.addEssence(owner, amount, cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to add essence for " + owner + ": " + e);
        }
    }

    /** Sets an owner's essence to an absolute value (admin/command path). */
    public void setEssence(UUID owner, int amount) {
        if (owner == null) return;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            repository.setEssence(owner, Math.max(0, amount), cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to set essence for " + owner + ": " + e);
        }
    }

    /** Removes essence from an owner, clamped at zero (admin/command path). Returns the resulting balance. */
    public int removeEssence(UUID owner, int amount) {
        if (owner == null) return 0;
        if (amount <= 0) return getEssence(owner);
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            return repository.removeEssence(owner, amount, cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to remove essence for " + owner + ": " + e);
            return getEssence(owner);
        }
    }

    /** Public cache invalidation used by Soul Table operations that mutate rows directly. */
    public void invalidateSoul(UUID soulId, UUID ownerId) {
        invalidateSoulCache(soulId);
        invalidateSelectedCache(ownerId);
    }

    public SoulConfig soulConfig() {
        try {
            var sc = plugin.getSummonConfig();
            return sc != null ? sc.getSoulConfig() : new SoulConfig();
        } catch (Throwable t) {
            return new SoulConfig();
        }
    }

    /** Soul Table / stat config (may be null before the summon module initialises). */
    public SoulTableConfig tableConfig() {
        try {
            var sc = plugin.getSummonConfig();
            return sc != null ? sc.getSoulTableConfig() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Rolled base stats for a soul, falling back to the entity type defaults for legacy souls. */
    public SoulBaseStats baseStatsOf(SoulInstance soul) {
        if (soul == null) return SoulBaseStats.NEUTRAL;
        SoulBaseStats base = soul.getBaseStats();
        return base != null ? base : SoulBaseStats.defaults(soul.getEntityType());
    }

    /** Base stats with the Soul Table enhancement applied. Affix multipliers are applied separately. */
    public SoulBaseStats effectiveBaseStats(SoulInstance soul) {
        if (soul == null) return SoulBaseStats.NEUTRAL;
        var cfg = tableConfig();
        double perLevel = cfg != null ? cfg.getEnhancePerLevelBonus() : 0.025;
        var statCfg = cfg != null ? cfg.getStatConfig() : new SoulTableConfig.SoulStatConfig();
        return baseStatsOf(soul).enhanced(soul.getEnhanceLevel(), perLevel, soul.getRarity(), statCfg);
    }

    /** Base group capacity rolled/generated on the soul (before affix/enhancement modifiers). */
    public int baseGroupCapacity(SoulInstance soul) {
        if (soul == null) return 1;
        return Math.max(1, soul.getGroupCapacity());
    }

    /**
     * Effective/authoritative group capacity for a soul: base + enhancement milestones + capacity affix %,
     * clamped by hard per-type overrides and absolute ceilings. Mob types with hard overrides can still
     * roll the capacity affix on the soul; the override remains a final ceiling.
     */
    public int effectiveGroupCapacity(SoulInstance soul) {
        if (soul == null) return 1;
        int base = Math.max(1, soul.getGroupCapacity());

        // Enhancement path: milestone increases (balanced, integer), capped later.
        int enhanceBonus = 0;
        int lvl = Math.max(0, soul.getEnhanceLevel());
        if (lvl >= 5) enhanceBonus += 1;
        if (lvl >= 10) enhanceBonus += 1;

        // Reforge/enhancement can increase capacity via base rerolls and these milestones.
        int baseWithEnhance = Math.max(1, base + enhanceBonus);

        double pct = 0.0;
        try { pct = Math.max(0.0, Math.min(1.0, getSnapshot(soul).capacityBonus())); } catch (Throwable ignored) {}
        int computed = (int) Math.floor(baseWithEnhance * (1.0 + pct) + 1e-9);
        if (pct > 0.0 && computed == baseWithEnhance) {
            // Ensure a meaningful mild % affix can contribute at low capacities.
            computed = baseWithEnhance + 1;
        }
        if (computed < 1) computed = 1;

        // Absolute safety ceiling for generated/modified soul capacity.
        int ceiling = 10;

        // Category/type guidance ceiling (keeps rarity scaling from becoming extreme for some archetypes).
        try {
            SoulConfig cfg = soulConfig();
            if (cfg != null) {
                var cat = soul.getCategory();
                if (cat == null) cat = EntityCategory.of(soul.getEntityType());
                var cd = cfg.getCategoryDefaults(cat);
                if (cd != null) {
                    ceiling = Math.min(ceiling, Math.max(1, cd.maxActivePerType() + 2));
                }
            }
        } catch (Throwable ignored) {}

        // Hard per-type override is an absolute ceiling (final authority cap).
        try {
            SoulConfig cfg = soulConfig();
            if (cfg != null) {
                String family = soul.getFamilyKey();
                if (family == null || family.isBlank()) {
                    family = soul.getEntityType() != null ? soul.getEntityType().name() : null;
                }
                Integer hard = cfg.getTypeMaxActiveOverride(family);
                if (hard == null && soul.getEntityType() != null) {
                    hard = cfg.getTypeMaxActiveOverride(soul.getEntityType().name());
                }
                if (hard != null) ceiling = Math.min(ceiling, Math.max(1, hard));
            }
        } catch (Throwable ignored) {}

        return Math.max(1, Math.min(computed, ceiling));
    }

    /** Breakdown for UI: [base, enhanceBonus, affixBonusCount, effectiveFinal]. */
    public int[] computeGroupCapacityBreakdown(SoulInstance soul) {
        if (soul == null) return new int[]{1, 0, 0, 1};
        int base = Math.max(1, soul.getGroupCapacity());
        int lvl = Math.max(0, soul.getEnhanceLevel());
        int enhanceBonus = (lvl >= 5 ? 1 : 0) + (lvl >= 10 ? 1 : 0);
        int beforeAffix = Math.max(1, base + enhanceBonus);
        double pct = 0.0;
        try { pct = Math.max(0.0, Math.min(1.0, getSnapshot(soul).capacityBonus())); } catch (Throwable ignored) {}
        int computed = (int) Math.floor(beforeAffix * (1.0 + pct) + 1e-9);
        if (pct > 0.0 && computed == beforeAffix) computed = beforeAffix + 1;
        int effective = effectiveGroupCapacity(soul);
        int affixBonus = Math.max(0, effective - Math.min(effective, beforeAffix));
        // if capped down by ceilings, affixBonus shown as 0 or reduced; keep simple
        if (computed > effective) affixBonus = Math.max(0, effective - beforeAffix);
        return new int[]{base, enhanceBonus, affixBonus, effective};
    }

    public int getSummoningLevel(UUID owner) {
        try {
            if (owner != null && plugin.getSummonProgression() != null) {
                return Math.max(0, plugin.getSummonProgression().getSummoningLevel(owner));
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private void debug(String msg) {
        try {
            SoulConfig cfg = soulConfig();
            if (cfg != null && !cfg.isDebugTelemetry()) return;
        } catch (Throwable ignored) {}
        GlovedPlugin.logDebug("[Souls] " + msg);
    }

    /** Attempt to capture a soul from a killed mob attributed to a player. Drops a soul item on success. */
    public CaptureResult tryCapture(Player killer, LivingEntity victim) {
        return tryCapture(killer, victim, false);
    }

    public CaptureResult tryCapture(Player killer, LivingEntity victim, boolean fromMinionKill) {
        if (killer == null || victim == null) return new CaptureResult(false, null, "invalid", false);
        SoulConfig cfg = soulConfig();
        if (cfg == null || !cfg.isEnabled()) return new CaptureResult(false, null, "disabled", false);

        EntityType type = victim.getType();
        if (!cfg.isAllowed(type)) return new CaptureResult(false, null, "not-allowed", false);
        // Only allow souls for mob types with registered custom summon/NMS AI support.
        if (!SummonEntityInjector.isSupported(type)) {
            debug("Capture blocked for " + killer.getName() + " type=" + type + ": no summon AI/NMS support");
            return new CaptureResult(false, null, "no-summon-ai", false);
        }

        int level = getSummoningLevel(killer.getUniqueId());
        double chance = Math.max(0.0, Math.min(1.0, cfg.getCaptureBaseChance()
                + Math.min(cfg.getCaptureLuckMaxBonus(), cfg.getCaptureLuckPerLevel() * level)));
        EntityCategory cat = EntityCategory.of(type);
        chance *= Math.max(0.0, cfg.getCaptureCategoryMultiplier(cat));
        if (fromMinionKill) chance *= Math.max(0.0, Math.min(1.0, cfg.getCaptureMinionKillMultiplier()));
        if (chance <= 0.0) return new CaptureResult(false, null, "chance-zero", false);

        // Simple DR: same family recently dampens chance
        if (cfg.isCaptureDrEnabled()) {
            long now = System.currentTimeMillis();
            Map<String, Long> m = lastCaptureAt.computeIfAbsent(killer.getUniqueId(), k -> new ConcurrentHashMap<>());
            Long last = m.get(type.name());
            long windowMs = Math.max(10_000L, cfg.getCaptureDrWindowSeconds() * 1000L / 10L); // soft per-family window
            if (last != null && now - last < windowMs) {
                chance *= 0.35;
            }
        }

        if (ThreadLocalRandom.current().nextDouble() > Math.max(0.0, Math.min(1.0, chance))) {
            debug("Capture roll failed for " + killer.getName() + " type=" + type + " chance=" + chance + " minionKill=" + fromMinionKill);
            // progress pity slightly on misses
            if (cfg.isPityEnabled()) updatePityOnResult(killer.getUniqueId(), cfg, null, false);
            return new CaptureResult(false, null, "roll-failed", false);
        }

        PityState pity = cfg.isPityEnabled() ? readPity(killer.getUniqueId(), cfg) : PityState.empty();
        SoulRarity forced = forcedRarityFromPity(cfg, pity);
        double capturedMaxHealth = victim.getAttribute(Attribute.MAX_HEALTH) != null
                ? victim.getAttribute(Attribute.MAX_HEALTH).getBaseValue() : 0.0;
        SoulBaseStats capturedStats = SoulBaseStats.fromEntity(victim);
        SoulInstance soul = SoulGenerator.generate(killer.getUniqueId(), type, cfg, forced, level, pity.rare, pity.epic,
                capturedMaxHealth, capturedStats, tableConfig());

        // Capture-time side effects
        updatePityOnResult(killer.getUniqueId(), cfg, soul.getRarity(), true);
        try { repository.upsertCodex(killer.getUniqueId(), soul.getFamilyKey(), soul.getRarity()); } catch (SQLException ignored) {}
        markCaptureDr(killer.getUniqueId(), soul.getFamilyKey());
        try {
            var prog = plugin.getSummonProgression();
            if (prog != null) prog.onSoulCaptured(killer, victim, soul.getFamilyKey(), soul.getRarity());
        } catch (Throwable ignored) {}

        Location from = victim.getLocation().add(0, victim.getHeight() * 0.5, 0);
        CollectResult res = persistSoulToVault(killer, soul, cfg, false, true);
        if (res.stored()) {
            playCaptureFeedback(killer, soul, false, 0, fromMinionKill);
            playSoulTrail(from, killer.getLocation().add(0, 1.0, 0), soul);
            debug("Captured soul " + soul.getRarity() + " " + soul.getFamilyKey() + " for " + killer.getName() + " (lvl=" + level + ", minionKill=" + fromMinionKill + ")");
            return new CaptureResult(true, soul, "stored", false);
        } else if (res.convertedToEssence()) {
            playCaptureFeedback(killer, soul, true, res.essence(), fromMinionKill);
            playSoulTrail(from, killer.getLocation().add(0, 1.0, 0), soul);
            debug("Captured soul (essence) " + soul.getRarity() + " " + soul.getFamilyKey() + " for " + killer.getName());
            return new CaptureResult(true, soul, "vault-full-converted", true);
        }
        debug("Failed to store captured soul for " + killer.getName() + " " + soul.getFamilyKey() + ": " + res.reason());
        return new CaptureResult(false, soul, res.reason(), false);
    }

    /** Admin/give: create and store a soul directly in vault (optionally forced rarity). */
    public CaptureResult giveSoul(Player owner, EntityType type, SoulRarity forcedRarity) {
        if (owner == null || type == null) return new CaptureResult(false, null, "invalid", false);
        SoulConfig cfg = soulConfig();
        int level = getSummoningLevel(owner.getUniqueId());
        SoulInstance soul = SoulGenerator.generate(owner.getUniqueId(), type, cfg, forcedRarity, level, 0, 0,
                0.0, SoulBaseStats.defaults(type), tableConfig());
        return storeCapturedSoulDirect(owner, soul, cfg);
    }

    /** Simulate generation without persisting. */
    public SoulInstance simulate(EntityType type, SoulRarity forcedRarity) {
        SoulConfig cfg = soulConfig();
        UUID fake = new UUID(0, 0);
        return SoulGenerator.generate(fake, type, cfg, forcedRarity, 0, 0, 0, 0.0,
                SoulBaseStats.defaults(type), tableConfig());
    }

    /** Direct store to vault (admin/give path). Handles capacity, codex, DR, and feedback. */
    private CaptureResult storeCapturedSoulDirect(Player player, SoulInstance soul, SoulConfig cfg) {
        CollectResult res = persistSoulToVault(player, soul, cfg, true, true);
        if (res.stored()) {
            playCaptureFeedback(player, soul, false, 0);
            markCaptureDr(player.getUniqueId(), soul.getFamilyKey());
            return new CaptureResult(true, soul, "stored", false);
        } else if (res.convertedToEssence()) {
            playCaptureFeedback(player, soul, true, res.essence());
            markCaptureDr(player.getUniqueId(), soul.getFamilyKey());
            return new CaptureResult(true, soul, "vault-full-converted", true);
        }
        return new CaptureResult(false, soul, res.reason(), false);
    }

    /**
     * Persist a soul into vault DB (insert or convert to essence if full).
     * @param updateCodex whether to upsert codex (capture-time already does this for drops)
     * @param invalidateCaches whether to invalidate selection/caches for owner
     */
    private CollectResult persistSoulToVault(Player player, SoulInstance soul, SoulConfig cfg, boolean updateCodex, boolean invalidateCaches) {
        if (player == null || soul == null) return CollectResult.fail("invalid");
        try {
            if (cfg == null) cfg = soulConfig();
            int level = getSummoningLevel(player.getUniqueId());
            int scaledCap = cfg.computeVaultCapacity(level);
            SoulRepository.PlayerSoulState state = repository.getOrCreatePlayerState(player.getUniqueId(), scaledCap);
            if (state.vaultCapacity() != scaledCap) {
                repository.updateVaultCapacity(player.getUniqueId(), scaledCap, scaledCap);
                state = repository.getOrCreatePlayerState(player.getUniqueId(), scaledCap);
            }
            // Idempotency: if this soul record already exists (e.g. duplicate/stale physical item), treat as collected.
            try {
                SoulInstance existing = repository.getSoul(soul.getSoulId());
                if (existing != null) {
                    if (invalidateCaches) invalidateSelectedCache(player.getUniqueId());
                    return CollectResult.okStored();
                }
            } catch (SQLException ignored) {}

            int count = repository.countSouls(player.getUniqueId());
            boolean toEssence = count >= Math.max(1, state.vaultCapacity());
            if (toEssence) {
                int essence = overflowEssenceValue(soul.getRarity());
                repository.addEssence(player.getUniqueId(), essence, scaledCap);
                if (updateCodex) repository.upsertCodex(player.getUniqueId(), soul.getFamilyKey(), soul.getRarity());
                return CollectResult.okEssence(essence);
            } else {
                repository.insertSoul(soul);
                if (updateCodex) repository.upsertCodex(player.getUniqueId(), soul.getFamilyKey(), soul.getRarity());
                if (invalidateCaches) invalidateSelectedCache(player.getUniqueId());
                return CollectResult.okStored();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[Souls] Failed to persist soul to vault: " + e);
            GlovedPlugin.logDebug("[Souls] persistSoulToVault error: " + e);
            return CollectResult.fail("db-error");
        }
    }

    /** Called when a player collects/claims a physical soul item (pickup, right-click, etc.). Transfers ownership if needed. */
    public CollectResult collectDroppedSoul(Player player, SoulInstance soul) {
        if (player == null || soul == null) return CollectResult.fail("invalid");
        SoulInstance toStore = soul;
        boolean transferred = !player.getUniqueId().equals(soul.getOwnerId());
        if (transferred) {
            // Claiming a traded soul: transfer ownership to the collector
            toStore = new SoulInstance(
                    java.util.UUID.randomUUID(),
                    player.getUniqueId(),
                    soul.getEntityType(),
                    soul.getFamilyKey(),
                    soul.getCategory(),
                    soul.getRarity(),
                    soul.getQualityScore(),
                    new java.util.ArrayList<>(soul.getAffixes()),
                    new java.util.ArrayList<>(soul.getTraits()),
                    System.currentTimeMillis(),
                    soul.getMaxHealth(),
                    false,
                    soul.getBaseStats(),
                    soul.getEnhanceLevel(),
                    soul.getGroupCapacity()
            );
        }
        SoulConfig cfg = soulConfig();
        // For normal owner collection of dropped/captured items, capture-time side effects were already applied.
        // For transferred/traded claims, treat as a new acquisition for the collector (codex update).
        return persistSoulToVault(player, toStore, cfg, transferred, true);
    }

    private boolean dropSoulItem(Location loc, SoulInstance soul) {
        try {
            if (loc == null || loc.getWorld() == null || soul == null) return false;
            ItemStack stack = SoulDropItem.create(plugin, soul);
            Item item = loc.getWorld().dropItem(loc, stack);
            try {
                // tradeable physical soul item: allow any player to pick it up
                item.setWillAge(true);
                item.setPickupDelay(10);
            } catch (Throwable ignored) {}
            item.setGlowing(true);
            try {
                item.setCustomName(SoulDropItem.getDisplayName(soul));
                item.setCustomNameVisible(true);
            } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Souls] dropSoulItem failed: " + t);
            return false;
        }
    }

    private void playDropFeedback(Player player, SoulInstance soul, boolean fromMinionKill) {
        try {
            if (player == null || soul == null) return;
            player.sendMessage("§d[Soul] §aA " + describe(soul) + " §7dropped!" + (fromMinionKill ? " §8(minion kill)" : ""));
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.4f);
        } catch (Throwable ignored) {}
    }

    private void markCaptureDr(UUID owner, String familyKey) {
        if (owner == null || familyKey == null) return;
        lastCaptureAt.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(familyKey, System.currentTimeMillis());
    }

    /** Capture side effects for non-kill captures (e.g. soul traps): codex, DR, pity, and progression capture XP. */
    public void applyTrapCaptureSideEffects(Player player, LivingEntity victim, SoulInstance soul) {
        if (player == null || soul == null) return;
        SoulConfig cfg = soulConfig();
        try {
            if (cfg != null && cfg.isPityEnabled()) updatePityOnResult(player.getUniqueId(), cfg, soul.getRarity(), true);
        } catch (Throwable ignored) {}
        try { repository.upsertCodex(player.getUniqueId(), soul.getFamilyKey(), soul.getRarity()); } catch (SQLException ignored) {}
        try { markCaptureDr(player.getUniqueId(), soul.getFamilyKey()); } catch (Throwable ignored) {}
        try {
            var prog = plugin.getSummonProgression();
            if (prog != null) prog.onSoulCaptured(player, victim, soul.getFamilyKey(), soul.getRarity());
        } catch (Throwable ignored) {}
    }

    /** Essence value using new defaults (config-less fallback). Prefer {@link #essenceValueOf(SoulRarity)}. */
    public static int essenceValue(SoulRarity rarity) {
        if (rarity == null) return 1;
        return switch (rarity) {
            case COMMON -> 1;
            case UNCOMMON -> 3;
            case RARE -> 8;
            case EPIC -> 18;
            case LEGENDARY -> 42;
            case MYTHIC -> 95;
        };
    }

    /** Config-driven essence value for salvage/capture conversion. */
    public int essenceValueOf(SoulRarity rarity) {
        SoulConfig cfg = soulConfig();
        return cfg != null ? cfg.getEssenceValue(rarity) : essenceValue(rarity);
    }

    /** Config-driven essence value for vault overflow conversion (applies overflow multiplier). */
    public int overflowEssenceValue(SoulRarity rarity) {
        int base = essenceValueOf(rarity);
        SoulConfig cfg = soulConfig();
        double mult = cfg != null ? Math.max(0.0, cfg.getEssenceOverflowMultiplier()) : 1.0;
        return Math.max(0, (int) Math.round(base * mult));
    }

    private void playCaptureFeedback(Player player, SoulInstance soul, boolean essence, int essenceAmt) {
        playCaptureFeedback(player, soul, essence, essenceAmt, false);
    }

    private void playCaptureFeedback(Player player, SoulInstance soul, boolean essence, int essenceAmt, boolean fromMinionKill) {
        try {
            if (player == null || soul == null) return;
            String suffix = fromMinionKill ? " §8(minion kill)" : "";
            if (essence) {
                player.sendMessage("§d[Soul] §7Captured " + describe(soul) + " but vault is full → §b+" + essenceAmt + " Essence" + suffix);
            } else {
                player.sendMessage("§d[Soul] §aCaptured " + describe(soul) + "!" + suffix);
            }
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.4f);
        } catch (Throwable ignored) {}
    }

    /** Visual trail from start location to end location (e.g. capture→player, player→minion on summon, minion→player on despawn). */
    public void playSoulTrail(Location start, Location end, SoulInstance soul) {
        try {
            if (start == null || end == null || start.getWorld() == null) return;
            if (!start.getWorld().equals(end.getWorld())) return;
            final Location trailStart = start.clone();
            final Location trailEnd = end.clone();
            final int durationTicks = 6;
            final org.bukkit.scheduler.BukkitRunnable task = new org.bukkit.scheduler.BukkitRunnable() {
                int tick = 0;

                @Override
                public void run() {
                    try {
                        if (trailEnd.getWorld() == null || !trailEnd.getWorld().equals(trailStart.getWorld())) {
                            cancel();
                            return;
                        }
                        Location target = trailEnd.clone();
                        double progress = Math.min(1.0, tick / (double) durationTicks);
                        // leading point moves toward target over time
                        double lead = Math.min(1.0, progress * 1.15);
                        double trailLen = 0.35;
                        double tail = Math.max(0.0, lead - trailLen);
                        int steps = 20;
                        for (int i = 0; i <= steps; i++) {
                            double t = tail + (lead - tail) * (i / (double) steps);
                            Location p = trailStart.clone().add(target.clone().subtract(trailStart).multiply(t));
                            trailStart.getWorld().spawnParticle(org.bukkit.Particle.SOUL, p, 2, 0.02, 0.02, 0.02, 0.0);
                            if (i % 2 == 0) {
                                trailStart.getWorld().spawnParticle(org.bukkit.Particle.SCULK_SOUL, p, 2, 0.01, 0.01, 0.01, 0.0);
                            }
                        }
                        if (progress >= 0.95) {
                            trailStart.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, target, 6, 0.15, 0.2, 0.15, 0.01);
                        }
                        tick++;
                        if (tick > durationTicks) cancel();
                    } catch (Throwable t) {
                        cancel();
                    }
                }
            };
            task.runTaskTimer(plugin, 0L, 1L);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Souls] playSoulTrail failed: " + t);
        }
    }

    public List<SoulInstance> listSouls(UUID owner, int page, int pageSize) {
        try {
            int p = Math.max(0, page);
            int ps = Math.max(1, Math.min(50, pageSize));
            return repository.listSouls(owner, ps, p * ps);
        } catch (SQLException e) {
            plugin.getLogger().warning("[Souls] listSouls failed: " + e);
            return List.of();
        }
    }

    public int countSouls(UUID owner) {
        try { return repository.countSouls(owner); } catch (SQLException e) { return 0; }
    }

    public SoulRepository.PlayerSoulState getState(UUID owner) {
        try {
            SoulConfig cfg = soulConfig();
            int level = getSummoningLevel(owner);
            int cap = cfg != null ? cfg.computeVaultCapacity(level) : 30;
            SoulRepository.PlayerSoulState st = repository.getOrCreatePlayerState(owner, cap);
            if (st != null && st.vaultCapacity() != cap) {
                repository.updateVaultCapacity(owner, cap, cap);
                st = repository.getOrCreatePlayerState(owner, cap);
            }
            return st;
        }
        catch (SQLException e) { return null; }
    }

    public String getSummonCombatMode(UUID owner) {
        SoulRepository.PlayerSoulState st = getState(owner);
        if (st == null || st.combatMode() == null) return "AGGRESSIVE";
        return normalizeCombatMode(st.combatMode());
    }

    public String setSummonCombatMode(UUID owner, String mode) {
        if (owner == null) return "AGGRESSIVE";
        String normalized = normalizeCombatMode(mode);
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            repository.setCombatMode(owner, normalized, cap);
        } catch (SQLException e) {
            plugin.getLogger().warning("[Souls] Failed to set combat mode for " + owner + ": " + e);
        }
        return normalized;
    }

    public String toggleSummonCombatMode(UUID owner) {
        String current = getSummonCombatMode(owner);
        String next = "AGGRESSIVE".equalsIgnoreCase(current) ? "DEFENSIVE" : "AGGRESSIVE";
        return setSummonCombatMode(owner, next);
    }

    public String getSoulTableTab(UUID owner) {
        if (owner == null) return "COMBINE";
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            String tab = repository.getSoulTableTab(owner, cap);
            return tab == null || tab.isBlank() ? "COMBINE" : tab;
        } catch (SQLException e) {
            return "COMBINE";
        }
    }

    public void setSoulTableTab(UUID owner, String tab) {
        if (owner == null) return;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            repository.setSoulTableTab(owner, tab, cap);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to set soul table tab for " + owner + ": " + e);
        }
    }

    public static String normalizeCombatMode(String mode) {
        if (mode == null) return "AGGRESSIVE";
        return switch (mode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DEFENSIVE", "DEFENSE", "DEFEND" -> "DEFENSIVE";
            default -> "AGGRESSIVE";
        };
    }

    public SoulInstance getSoul(UUID soulId) {
        try { return repository.getSoul(soulId); } catch (SQLException e) { return null; }
    }

    public SoulInstance findOwnedSoul(UUID owner, String idPrefixOrIndexHint) {
        if (owner == null || idPrefixOrIndexHint == null) return null;
        try { return repository.findSoulByPrefix(owner, idPrefixOrIndexHint); } catch (SQLException e) { return null; }
    }

    public List<SoulInstance> listSoulsFiltered(UUID owner, String familyOrType, SoulRarity rarity, int page, int pageSize) {
        try {
            return repository.listSoulsFiltered(owner, familyOrType, rarity, Math.max(1, Math.min(50, pageSize)), Math.max(0, page) * Math.max(1, pageSize));
        } catch (SQLException e) { return List.of(); }
    }

    public List<SoulRepository.CodexEntry> listCodex(UUID owner, int limit) {
        try { return repository.listCodex(owner, Math.max(1, Math.min(100, limit))); } catch (SQLException e) { return List.of(); }
    }

    public int countCodex(UUID owner) {
        try { return repository.countCodex(owner); } catch (SQLException e) { return 0; }
    }

    public int loadoutSlotsFor(UUID owner) {
        int level = getSummoningLevel(owner);
        SoulConfig cfg = soulConfig();
        int base = cfg != null ? Math.max(1, cfg.getLoadoutBaseSlots()) : 1;
        int per = cfg != null ? Math.max(1, cfg.getLoadoutLevelsPerSlot()) : 10;
        int max = cfg != null ? Math.max(base, cfg.getLoadoutMaxSlots()) : 5;
        return Math.max(1, Math.min(max, base + (level / per)));
    }

    public Map<Integer, UUID> getLoadout(UUID owner) {
        try { return repository.listLoadout(owner); } catch (SQLException e) { return Map.of(); }
    }

    public boolean selectSlot(UUID owner, int slot) {
        int max = loadoutSlotsFor(owner);
        if (slot < 0 || slot >= max) return false;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            repository.setSelectedSlot(owner, slot, cap);
            invalidateSelectedCache(owner);
            return true;
        } catch (SQLException e) { return false; }
    }

    public boolean equipSoul(UUID owner, int slot, SoulInstance soul) {
        if (owner == null || soul == null) return false;
        if (!owner.equals(soul.getOwnerId())) return false;
        int max = loadoutSlotsFor(owner);
        if (slot < 0 || slot >= max) return false;
        try {
            repository.setLoadoutSoul(owner, slot, soul.getSoulId(), false);
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            SoulRepository.PlayerSoulState st = repository.getOrCreatePlayerState(owner, cap);
            if (st.selectedSlot() == slot) invalidateSelectedCache(owner);
            return true;
        } catch (SQLException e) { return false; }
    }

    public boolean unequipSlot(UUID owner, int slot) {
        if (owner == null) return false;
        try {
            repository.setLoadoutSoul(owner, slot, null, false);
            invalidateSelectedCache(owner);
            return true;
        } catch (SQLException e) { return false; }
    }

    public SoulInstance getSelectedSoul(UUID owner) {
        if (owner == null) return null;
        SoulInstance cached = selectedSoulCache.get(owner);
        if (cached != null) return cached;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            SoulRepository.PlayerSoulState st = repository.getOrCreatePlayerState(owner, cap);
            int slot = st.selectedSlot();
            UUID soulId = repository.getLoadoutSoulId(owner, slot);
            if (soulId == null) return null;
            SoulInstance soul = repository.getSoul(soulId);
            if (soul != null) selectedSoulCache.put(owner, soul);
            return soul;
        } catch (SQLException e) { return null; }
    }

    public boolean toggleFavorite(UUID owner, SoulInstance soul) {
        if (owner == null || soul == null || !owner.equals(soul.getOwnerId())) return false;
        try {
            soul.setFavorite(!soul.isFavorite());
            repository.updateSoulFavorite(soul.getSoulId(), soul.isFavorite());
            invalidateSelectedCache(owner);
            invalidateSoulCache(soul.getSoulId());
            return true;
        } catch (SQLException e) { return false; }
    }

    public record SalvageResult(boolean ok, int essenceGained, String reason) {}

    public SalvageResult salvage(UUID owner, SoulInstance soul) {
        if (owner == null || soul == null) return new SalvageResult(false, 0, "invalid");
        if (!owner.equals(soul.getOwnerId())) return new SalvageResult(false, 0, "not-owner");
        try {
            SoulInstance existing = repository.getSoul(soul.getSoulId());
            if (existing == null || !owner.equals(existing.getOwnerId())) return new SalvageResult(false, 0, "not-found");
            soul = existing; // use current DB state
        } catch (SQLException e) {
            return new SalvageResult(false, 0, "db-error");
        }
        if (soul.isFavorite()) return new SalvageResult(false, 0, "this soul is favorited");
        try {
            Map<Integer, UUID> loadout = repository.listLoadout(owner);
            if (loadout.containsValue(soul.getSoulId())) return new SalvageResult(false, 0, "equipped");
        } catch (SQLException ignored) {}
        int essence = essenceValueOf(soul.getRarity());
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            // re-check existence right before delete to reduce race window
            if (repository.getSoul(soul.getSoulId()) == null) return new SalvageResult(false, 0, "not-found");
            repository.deleteSoul(soul.getSoulId());
            repository.addEssence(owner, essence, cap);
            invalidateSelectedCache(owner);
            invalidateSoulCache(soul.getSoulId());
            return new SalvageResult(true, essence, "ok");
        } catch (SQLException e) {
            return new SalvageResult(false, 0, "db-error");
        }
    }

    public record ExtractResult(boolean ok, String reason) {}

    /** Remove a soul from the vault so it can be turned into a physical/tradeable item. */
    public ExtractResult extractForItem(UUID owner, SoulInstance soul) {
        if (owner == null || soul == null) return new ExtractResult(false, "invalid");
        if (!owner.equals(soul.getOwnerId())) return new ExtractResult(false, "not-owner");
        try {
            SoulInstance existing = repository.getSoul(soul.getSoulId());
            if (existing == null || !owner.equals(existing.getOwnerId())) return new ExtractResult(false, "not-found");
            soul = existing; // use current DB state
        } catch (SQLException e) {
            return new ExtractResult(false, "db-error");
        }
        if (soul.isFavorite()) return new ExtractResult(false, "this soul is favorited");
        try {
            Map<Integer, UUID> loadout = repository.listLoadout(owner);
            if (loadout.containsValue(soul.getSoulId())) return new ExtractResult(false, "equipped");
        } catch (SQLException ignored) {}
        try {
            // re-check existence right before delete to reduce race window
            if (repository.getSoul(soul.getSoulId()) == null) return new ExtractResult(false, "not-found");
            repository.deleteSoul(soul.getSoulId());
            invalidateSelectedCache(owner);
            invalidateSoulCache(soul.getSoulId());
            return new ExtractResult(true, "ok");
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] extractForItem failed: " + e);
            return new ExtractResult(false, "db-error");
        }
    }

    public String compare(SoulInstance a, SoulInstance b) {
        if (a == null || b == null) return "Cannot compare: missing soul.";
        StringBuilder sb = new StringBuilder();
        sb.append("§dCompare§7: ").append(shortId(a)).append(" vs ").append(shortId(b)).append("\n");
        sb.append("§7Type: §f").append(a.getFamilyKey()).append(" §7vs §f").append(b.getFamilyKey()).append("\n");
        sb.append("§7Rarity: §f").append(a.getRarity()).append(" §7vs §f").append(b.getRarity()).append("\n");
        sb.append("§7Trait: §f").append(a.getTrait() != null ? a.getTrait() : "-").append(" §7vs §f").append(b.getTrait() != null ? b.getTrait() : "-").append("\n");
        sb.append("§7Affixes A: §f").append(affixSummary(a)).append("\n");
        sb.append("§7Affixes B: §f").append(affixSummary(b));
        return sb.toString();
    }

    public String inspect(SoulInstance s) {
        if (s == null) return "Soul not found.";
        StringBuilder sb = new StringBuilder();
        sb.append("§dSoul ").append(shortId(s)).append(" §7(").append(s.getSoulId()).append(")\n");
        sb.append("§7Family: §f").append(s.getFamilyKey()).append(" §7Category: §f").append(s.getCategory()).append("\n");
        sb.append("§7Rarity: §f").append(s.getRarity()).append("\n");
        sb.append("§7Summons: §f").append(effectiveGroupCapacity(s)).append(" §7(base ").append(Math.max(1, s.getGroupCapacity())).append(")\n");
        sb.append("§7Trait: §f").append(s.getTrait() != null ? s.getTrait().name() : "-").append(" §7Flags: ").append(s.isFavorite() ? "§e[Fav]" : "§7-").append("\n");
        if (s.getAffixes().isEmpty()) {
            sb.append("§7Affixes: §f-");
        } else {
            sb.append("§7Affixes:\n");
            for (var a : s.getAffixes()) {
                sb.append("  §f- ").append(SoulAffixType.format(s.getRarity(), a)).append("\n");
            }
            if (sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    public static String shortId(SoulInstance s) {
        if (s == null) return "????";
        String id = s.getSoulId().toString();
        return id.substring(0, 8);
    }

    public static String affixSummary(SoulInstance s) {
        if (s == null || s.getAffixes().isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.getAffixes().size(); i++) {
            var a = s.getAffixes().get(i);
            if (i > 0) sb.append(", ");
            sb.append(SoulAffixType.format(a));
        }
        return sb.toString();
    }

    public void onPlayerQuit(UUID owner) {
        invalidateSelectedCache(owner);
        if (owner != null) lastCaptureAt.remove(owner);
    }

    public enum CastResult { OK, RECALLED, NO_SYSTEM, DISABLED, NO_PLAYER, NO_SELECTED_SOUL, NOT_ALLOWED, ON_COOLDOWN, NO_MANA, CAPPED, FAILED }

    public record CastAttempt(CastResult result, int spawned, String detail) {
        public static CastAttempt of(CastResult r) { return new CastAttempt(r, 0, null); }
        public static CastAttempt of(CastResult r, String d) { return new CastAttempt(r, 0, d); }
        public static CastAttempt ok(int spawned) { return new CastAttempt(CastResult.OK, spawned, null); }
    }

    /** Compute effective cast cooldown ticks for a soul (category/rarity/cost-efficiency aware). Returns 0 when disabled. */
    public int computeCastCooldownTicks(SoulInstance soul, SoulConfig cfg) {
        if (cfg == null) return 0;
        double rawBase = cfg.getCastBaseCooldownTicks();
        int rawGcd = Math.max(0, cfg.getCastSharedGcdTicks());
        if (rawBase <= 0.0 && rawGcd <= 0) return 0;
        double base = Math.max(20, rawBase);
        double mult = 1.0;
        if (soul != null) {
            SoulConfig.CategoryDefaults cd = cfg.getCategoryDefaults(soul.getCategory());
            if (cd != null) mult *= Math.max(0.5, Math.min(3.0, cd.costMult()));
            SoulStatSnapshot snap = getSnapshot(soul);
            // Dampen cost-efficiency impact so it can't swing cooldown as wildly.
            // 1.5x -> 1.25x, 0.5x -> 0.75x; centered on 1.0.
            double eff = snap.costEfficiency();
            mult *= 1.0 + (eff - 1.0) * 0.5;
        }
        int computed = (int) Math.max(20, Math.round(base * mult));
        // Enforce shared GCD floor when configured
        if (rawGcd > 0) computed = Math.max(computed, rawGcd);
        return computed;
    }

    /** Compute effective cast mana cost for a soul. Returns 0 when soul cast mana is disabled. */
    public double computeCastManaCost(SoulInstance soul, SoulConfig cfg) {
        if (cfg == null) return 0.0;
        if (!cfg.isCastManaEnabled()) return 0.0;
        double base = Math.max(0.0, cfg.getCastBaseManaCost());
        if (base <= 0.0) return 0.0;
        if (soul == null) return base;
        double mult = 1.0;
        SoulConfig.CategoryDefaults cd = cfg.getCategoryDefaults(soul.getCategory());
        if (cd != null) mult *= Math.max(0.5, Math.min(3.0, cd.costMult()));
        mult *= 1.0 + (soul.getRarity().ordinal() * 0.05);
        SoulStatSnapshot snap = getSnapshot(soul);
        mult *= Math.max(0.5, Math.min(1.5, snap.costEfficiency()));
        return Math.max(0.0, base * mult);
    }

    /**
     * Compute the movement speed attribute value a minion would spawn with for this soul,
     * mirroring SummonManager.applySoulSnapshotToMinion (capped relative to mob default).
     */
    public double computeSpawnedMovementSpeed(SoulInstance soul) {
        if (soul == null) return 0.0;
        SoulStatSnapshot snap = getSnapshot(soul);
        double speedMult = Math.max(0.5, Math.min(1.6, snap.speedMultiplier()));
        return computeSpawnedMovementSpeed(soul, speedMult);
    }

    /** Compute spawned movement speed using an explicit speed multiplier (for breakdowns). */
    public double computeSpawnedMovementSpeed(SoulInstance soul, double speedMult) {
        if (soul == null) return 0.0;
        SoulBaseStats base = effectiveBaseStats(soul);
        if (!Double.isFinite(speedMult)) speedMult = 1.0;
        speedMult = Math.max(0.5, Math.min(1.6, speedMult));
        double maxMove = 14.0 / 43.17; // ~14 bps global cap
        double moveFloor = 1.0 / 43.17; // ~1 bps floor
        double baseMove = base.movementSpeed();
        if (!Double.isFinite(baseMove)) baseMove = 2.5 / 43.17;
        // Compatibility/parity: older souls may have normalized-low movement bases (~2.5 bps).
        // Never estimate/apply below ~90% of the mob type's vanilla/default movement speed.
        try {
            org.bukkit.entity.EntityType type = null;
            try { type = org.bukkit.entity.EntityType.valueOf(String.valueOf(soul.getEntityType())); } catch (Throwable ignored) {}
            if (type == null) {
                try { type = org.bukkit.entity.EntityType.valueOf(String.valueOf(soul.getFamilyKey())); } catch (Throwable ignored) {}
            }
            double defMove = type != null ? SoulBaseStats.defaults(type).movementSpeed() : 0.0;
            if (Double.isFinite(defMove) && defMove > 0.0) {
                baseMove = Math.max(baseMove, defMove * 0.90);
            }
        } catch (Throwable ignored) {}
        if (baseMove < moveFloor) baseMove = moveFloor;
        if (baseMove > maxMove) baseMove = maxMove;
        // Diminishing returns: higher base speed => affixes affect it less.
        double applied;
        if (speedMult >= 1.0) {
            double bonus = speedMult - 1.0;
            double headroom = maxMove - baseMove;
            double effectiveness = headroom / maxMove;
            applied = baseMove + baseMove * bonus * effectiveness;
        } else {
            // Slowdown effects scale down less harshly near floor in reverse manner.
            double penalty = 1.0 - speedMult;
            double room = baseMove - moveFloor;
            double effectiveness = room / maxMove;
            applied = baseMove - baseMove * penalty * effectiveness;
        }
        if (applied < moveFloor) applied = moveFloor;
        if (applied > maxMove) applied = maxMove;
        return applied;
    }

    /** Effective spawned movement speed in blocks per second (matches actual pathfinder movement at base intent). */
    public double computeSpawnedMovementBlocksPerSecond(SoulInstance soul) {
        return computeSpawnedMovementSpeed(soul) * 43.17;
    }

    /**
     * Estimate the average melee hit damage this soul's minion would deal (non-elite baseline),
     * mirroring SummonAI.applyMinionHit/SummonAbilityService crit resolution as closely as
     * possible without target-specific context. Packbound is omitted (ally-dependent).
     */
    public double estimateAverageHitDamage(UUID ownerId, SoulInstance soul) {
        if (soul == null) return 0.0;
        var summonCfg = plugin.getSummonConfig();

        // Core base: soul's effective (enhanced) attack stat (raw soul attribute foundation).
        double baseDamage = Math.max(0.5, effectiveBaseStats(soul).attackDamage());

        // Owner strength bonus (AuraSkills attack damage trait)
        try {
            if (ownerId != null && summonCfg != null && summonCfg.isStrengthDamageEnabled() && plugin.getSummonProgression() != null) {
                var st = plugin.getSummonProgression().resolveOwnerCombatStats(ownerId);
                if (st != null && st.available()) {
                    double bonus = Math.max(0.0, st.attackDamageTrait()) * summonCfg.getStrengthDamagePerAttackDamageTrait();
                    bonus = Math.min(bonus, Math.max(0.0, summonCfg.getStrengthDamageMaxBonus()));
                    baseDamage += bonus;
                }
            }
        } catch (Throwable ignored) {}

        // Soul damage multipliers/traits (packbound is situational, omitted)
        SoulStatSnapshot snap = getSnapshot(soul);
        double soulDamageMult = Math.max(0.25, Math.min(4.0, snap.damageMultiplier()));
        if (soul.hasTrait(SoulTrait.FRENZIED)) soulDamageMult *= 1.08;
        baseDamage = Math.max(0.5, baseDamage * soulDamageMult);

        // Expected crit multiplier (average per hit)
        try {
            if (summonCfg != null && summonCfg.isCritEnabled()) {
                double chance = 0.0;
                double multiplier = 1.0;
                if (summonCfg.isCritSourceAuraSkills()) {
                    if (ownerId != null && plugin.getSummonProgression() != null) {
                        var st = plugin.getSummonProgression().resolveOwnerCombatStats(ownerId);
                        if (st != null && st.available()) {
                            chance = Math.max(0.0, st.critChancePercentPoints() / 100.0);
                            multiplier = 1.0 + (Math.max(0.0, st.critDamageTrait()) / 100.0);
                        }
                    }
                } else {
                    int level = ownerId != null ? getSummoningLevel(ownerId) : 0;
                    chance = summonCfg.getCritLegacyBaseChance() + summonCfg.getCritLegacyPerLevel() * Math.max(0, level);
                    multiplier = summonCfg.getCritLegacyDamageMultiplier();
                }
                chance += Math.max(0.0, snap.critChanceBonus());
                multiplier += Math.max(0.0, snap.critDamageBonus());
                if (soul.hasTrait(SoulTrait.FRENZIED)) { chance += 0.03; multiplier += 0.08; }
                chance = Math.max(0.0, Math.min(summonCfg.getCritMaxChance(), chance));
                multiplier = Math.max(summonCfg.getCritMinDamageMultiplier(), Math.min(summonCfg.getCritMaxDamageMultiplier(), multiplier));
                baseDamage *= (1.0 + chance * (Math.max(1.0, multiplier) - 1.0));
            }
        } catch (Throwable ignored) {}

        return Math.max(0.0, baseDamage);
    }

    /** Breakdown of spawned max health: [baseIncludingOwner, affixBonus(VIGOROUS), ownerBonus, total]. */
    public double[] computeSpawnedMaxHealthBreakdown(UUID ownerId, SoulInstance soul) {
        if (soul == null) return new double[]{0, 0, 0, 0};
        SoulBaseStats base = effectiveBaseStats(soul);
        double baseHealth = base.health();
        if (baseHealth <= 0.0) {
            baseHealth = soul.getMaxHealth() > 0.0
                    ? soul.getMaxHealth()
                    : repository.resolveDefaultMaxHealth(
                            soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey());
        }
        SoulStatSnapshot snap = getSnapshot(soul);
        double healthMult = Math.max(0.5, Math.min(3.0, snap.healthMultiplier()));
        double affixBonus = baseHealth * (healthMult - 1.0);
        double scaledBase = Math.max(1.0, baseHealth + affixBonus);
        // if clamped up by min 1.0, attribute difference to base+affix proportionally; keep simple
        if (scaledBase > baseHealth + affixBonus) affixBonus = scaledBase - baseHealth;
        double ownerHealthBonus = 0.0;
        if (ownerId != null) {
            try {
                var prog = plugin.getSummonProgression();
                if (prog != null) {
                    var stats = prog.resolveOwnerCombatStats(ownerId);
                    if (stats.available()) {
                        ownerHealthBonus = Math.max(0.0, stats.healthStatLevel());
                    }
                }
            } catch (Throwable ignored) {}
        }
        double baseIncludingOwner = Math.max(0.0, baseHealth + ownerHealthBonus);
        double total = Math.max(1.0, scaledBase + ownerHealthBonus);
        return new double[]{baseIncludingOwner, affixBonus, ownerHealthBonus, total};
    }

    /**
     * Compute the effective max health a minion would spawn with for this soul,
     * mirroring SummonManager.applySoulSnapshotToMinion:
     * base = rolled capture stats (enhanced) or saved/type default max HP for legacy souls
     * scaledBase = base * soul health multiplier (VIGOROUS affix) * enhancement
     * final = scaledBase + owner's AuraSkills health stat bonus
     */
    public double computeSpawnedMaxHealth(UUID ownerId, SoulInstance soul) {
        double[] b = computeSpawnedMaxHealthBreakdown(ownerId, soul);
        return b[3];
    }

    public CastAttempt castSelected(Player player) {
        if (player == null) return CastAttempt.of(CastResult.NO_PLAYER);
        SoulConfig cfg = soulConfig();
        if (cfg == null || !cfg.isEnabled()) return CastAttempt.of(CastResult.DISABLED);
        var summonCfg = plugin.getSummonConfig();
        var summonMgr = plugin.getSummonManager();
        if (summonCfg == null || summonMgr == null || !summonCfg.isEnabled()) return CastAttempt.of(CastResult.NO_SYSTEM);

        SoulInstance soul = getSelectedSoul(player.getUniqueId());
        if (soul == null) {
            player.sendMessage("§cNo soul selected. Use /gloved soul menu or /gloved soul select/equip.");
            return CastAttempt.of(CastResult.NO_SELECTED_SOUL);
        }

        // SoulId-exclusive toggle/hotswap: one active soul instance group per owner.
        var tracker = plugin.getSummonTracker();
        java.util.UUID selectedId = soul.getSoulId();
        java.util.UUID activeSoulId = summonMgr.getActiveSoulId(player.getUniqueId());
        boolean hasActive = false;
        try { hasActive = tracker != null && !tracker.getActiveMinions(player.getUniqueId()).isEmpty(); } catch (Throwable ignored) {}

        if (selectedId != null && selectedId.equals(activeSoulId)) {
            // Same selected soul active -> recall/toggle off (no mana, no cooldown)
            int recalled = summonMgr.recallAll(player);
            debug("Recalled soul " + shortId(soul) + " by " + player.getName() + " recalled=" + recalled);
            return new CastAttempt(CastResult.RECALLED, 0, selectedId.toString());
        }

        // Validate selected soul before hotswap recall so we don't recall if cast cannot proceed.
        if (!cfg.isAllowed(soul.getEntityType())) {
            player.sendMessage("§cThat soul type is not currently allowed.");
            return CastAttempt.of(CastResult.NOT_ALLOWED);
        }
        SummonProfile profile = summonCfg.getProfile(soul.getEntityType());
        if (profile == null) {
            player.sendMessage("§cCould not resolve summon template for " + soul.getFamilyKey() + ".");
            return CastAttempt.of(CastResult.NOT_ALLOWED);
        }

        long rem = summonMgr.getSoulCastCooldownRemainingMs(player.getUniqueId());
        int cdTicks = computeCastCooldownTicks(soul, cfg);
        if (cdTicks > 0 && rem > 0) {
            player.sendMessage("§cSoul cast on cooldown: §f" + String.format(java.util.Locale.ROOT, "%.1fs", rem / 1000.0));
            return CastAttempt.of(CastResult.ON_COOLDOWN, String.valueOf(rem));
        }

        double manaCost = computeCastManaCost(soul, cfg);
        boolean manaActive = cfg.isCastManaEnabled() && manaCost > 0.0
                && summonCfg != null && summonCfg.isManaEnabled();
        if (manaActive && !summonMgr.tryConsumeSoulCastMana(player, manaCost)) {
            player.sendMessage("§cNot enough mana to cast this soul. §7(" + String.format(java.util.Locale.ROOT, "%.1f", manaCost) + ")");
            return CastAttempt.of(CastResult.NO_MANA);
        }

        // Different active soul (or mixed/legacy active state) -> hotswap recall, then cast selected soul.
        if (hasActive) {
            int recalled = summonMgr.recallAll(player);
            debug("Hotswap recall for " + player.getName() + " recalled=" + recalled + " before casting " + shortId(soul));
        }

        int spawned = summonMgr.spawnSoulGroupToCap(player, profile, soul, null);
        if (spawned <= 0) {
            player.sendMessage("§eCannot summon now (spawn blocked).");
            return CastAttempt.of(CastResult.CAPPED);
        }

        if (cdTicks > 0) {
            summonMgr.setSoulCastCooldown(player.getUniqueId(), cdTicks * 50L);
        }
        try {
            var prog = plugin.getSummonProgression();
            if (prog != null) prog.onSoulCast(player, soul.getFamilyKey());
        } catch (Throwable ignored) {}

        debug("Cast soul " + shortId(soul) + " by " + player.getName() + " family=" + soul.getFamilyKey() + " rarity=" + soul.getRarity()
                + " mana=" + manaCost + " manaActive=" + manaActive + " cdTicks=" + cdTicks + " spawned=" + spawned);
        return CastAttempt.ok(spawned);
    }

    public static String describe(SoulInstance s) {
        if (s == null) return "?";
        return s.getRarity().name() + " " + prettyMobName(s.getEntityType() != null ? s.getEntityType().name() : s.getFamilyKey()) + " Soul";
    }

    private static String prettyMobName(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(java.util.Locale.ROOT).split("[_\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }

    // ---------- pity persistence ----------
    private record PityState(int rare, int epic, int legendary) {
        static PityState empty() { return new PityState(0,0,0); }
    }

    private PityState readPity(UUID owner, SoulConfig cfg) {
        try {
            SoulRepository.PlayerSoulState st = repository.getOrCreatePlayerState(owner, cfg.computeVaultCapacity(getSummoningLevel(owner)));
            if (st == null || st.pityJson() == null || st.pityJson().isBlank()) return PityState.empty();
            Map<String, Number> m = GSON.fromJson(st.pityJson(), PITY_TYPE);
            if (m == null) return PityState.empty();
            int rare = m.containsKey("rare") ? Math.max(0, m.get("rare").intValue()) : 0;
            int epic = m.containsKey("epic") ? Math.max(0, m.get("epic").intValue()) : 0;
            int legendary = m.containsKey("legendary") ? Math.max(0, m.get("legendary").intValue()) : 0;
            return new PityState(rare, epic, legendary);
        } catch (Throwable t) {
            return PityState.empty();
        }
    }

    private void updatePityOnResult(UUID owner, SoulConfig cfg, SoulRarity rolled, boolean captured) {
        if (owner == null || cfg == null || !cfg.isPityEnabled()) return;
        try {
            PityState cur = readPity(owner, cfg);
            int rare = cur.rare, epic = cur.epic, legendary = cur.legendary;
            if (!captured) {
                rare++; epic++; legendary++;
            } else if (rolled != null) {
                if (rolled.ordinal() >= SoulRarity.RARE.ordinal()) rare = 0; else rare++;
                if (rolled.ordinal() >= SoulRarity.EPIC.ordinal()) epic = 0; else epic++;
                if (rolled.ordinal() >= SoulRarity.LEGENDARY.ordinal()) legendary = 0; else legendary++;
            }
            Map<String, Integer> out = new LinkedHashMap<>();
            out.put("rare", Math.max(0, rare));
            out.put("epic", Math.max(0, epic));
            out.put("legendary", Math.max(0, legendary));
            int cap = cfg.computeVaultCapacity(getSummoningLevel(owner));
            repository.updatePityJson(owner, GSON.toJson(out), cap);
        } catch (Throwable ignored) {}
    }

    private SoulRarity forcedRarityFromPity(SoulConfig cfg, PityState pity) {
        if (cfg == null || pity == null) return null;
        if (pity.legendary >= cfg.getPityLegendaryThreshold()) return SoulRarity.LEGENDARY;
        if (pity.epic >= cfg.getPityEpicThreshold()) return SoulRarity.EPIC;
        if (pity.rare >= cfg.getPityRareThreshold()) return SoulRarity.RARE;
        return null;
    }

    /** Public wrapper for persistSoulToVault, used by SoulTrapListener. */
    public CollectResult persistSoulToVaultPublic(Player player, SoulInstance soul, SoulConfig cfg, boolean updateCodex, boolean invalidateCaches) {
        return persistSoulToVault(player, soul, cfg, updateCodex, invalidateCaches);
    }

    /** Public wrapper for dropSoulItem, used by SoulTrapListener. */
    public boolean dropSoulItemPublic(Location loc, SoulInstance soul) {
        return dropSoulItem(loc, soul);
    }
}
