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
import dev.gloved.glovedplugin.summon.combat.SummonCombatService;
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
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitHooks;
import dev.gloved.glovedplugin.summon.soul.trait.ctx.PreviewContext;
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
    // Guard against duplicate interact events causing activate -> instant recall toggle.
    private final Map<UUID, Long> lastTeamSpawnAt = new ConcurrentHashMap<>();

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

    private int teamCount(SoulConfig cfg) {
        return cfg != null ? Math.max(1, cfg.getTeamCount()) : 4;
    }

    private int teamSlots(SoulConfig cfg) {
        return cfg != null ? Math.max(1, cfg.getTeamSlotsPerTeam()) : 4;
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

    /** @deprecated Summons count/group capacity retired by team/loadout overhaul; kept for compatibility. */
    @Deprecated
    public int baseGroupCapacity(SoulInstance soul) {
        return 1;
    }

    /** @deprecated Summons count/group capacity retired by team/loadout overhaul; kept for compatibility. */
    @Deprecated
    public int effectiveGroupCapacity(SoulInstance soul) {
        return 1;
    }

    /** @deprecated Summons count/group capacity retired by team/loadout overhaul; kept for compatibility. */
    @Deprecated
    public int[] computeGroupCapacityBreakdown(SoulInstance soul) {
        return new int[]{1, 0, 0, 1};
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
        SoulInstance soul = SoulGenerator.generate(killer.getUniqueId(), type, cfg, plugin.getSummonConfig(), forced, level, pity.rare, pity.epic,
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
        SoulInstance soul = SoulGenerator.generate(owner.getUniqueId(), type, cfg, plugin.getSummonConfig(), forcedRarity, level, 0, 0,
                0.0, SoulBaseStats.defaults(type), tableConfig());
        return storeCapturedSoulDirect(owner, soul, cfg);
    }

    /** Simulate generation without persisting. */
    public SoulInstance simulate(EntityType type, SoulRarity forcedRarity) {
        SoulConfig cfg = soulConfig();
        UUID fake = new UUID(0, 0);
        return SoulGenerator.generate(fake, type, cfg, plugin.getSummonConfig(), forcedRarity, 0, 0, 0, 0.0,
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
            dev.gloved.glovedplugin.summon.vfx.SummonVfx.playSoulTrail(plugin, start, end);
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
            ensureTeamsSeeded(owner, cap);
            return st;
        }
        catch (SQLException e) { return null; }
    }

    private void ensureTeamsSeeded(UUID owner, int defaultCapacity) {
        if (owner == null) return;
        try {
            repository.seedDefaultTeamsFromLegacy(owner, defaultCapacity);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to seed team data for " + owner + ": " + e);
        }
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

    /** @deprecated Legacy selected-soul model replaced by team/loadout. Returns first valid soul in selected team (compat). */
    @Deprecated
    public SoulInstance getSelectedSoul(UUID owner) {
        if (owner == null) return null;
        try {
            SoulConfig cfg = soulConfig();
            int team = getSelectedTeam(owner);
            var slots = getTeam(owner, team);
            for (int i = 0; i < teamSlots(cfg); i++) {
                UUID sid = slots.get(i);
                if (sid == null) continue;
                SoulInstance s = getSoul(sid);
                if (s != null && owner.equals(s.getOwnerId())) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ---------- Team/loadout APIs ----------

    public int getSelectedTeam(UUID owner) {
        if (owner == null) return 1;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            ensureTeamsSeeded(owner, cap);
            return repository.getSelectedTeam(owner, cap);
        } catch (SQLException e) {
            return 1;
        }
    }

    public void setSelectedTeam(UUID owner, int teamIndex) {
        if (owner == null) return;
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            ensureTeamsSeeded(owner, cap);
            repository.setSelectedTeam(owner, Math.max(1, Math.min(teamCount(cfg), teamIndex)), cap);
            invalidateSelectedCache(owner);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to set selected team for " + owner + ": " + e);
        }
    }

    public Map<Integer, UUID> getTeam(UUID owner, int teamIndex) {
        if (owner == null) return Map.of();
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            ensureTeamsSeeded(owner, cap);
            teamIndex = Math.max(1, Math.min(teamCount(cfg), teamIndex));
            return repository.getTeamSlots(owner, teamIndex);
        } catch (SQLException e) {
            return Map.of();
        }
    }

    public Map<Integer, Map<Integer, UUID>> listTeams(UUID owner) {
        if (owner == null) return Map.of();
        try {
            SoulConfig cfg = soulConfig();
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            ensureTeamsSeeded(owner, cap);
            return repository.listTeams(owner);
        } catch (SQLException e) {
            return Map.of();
        }
    }

    public boolean setTeamSlot(UUID owner, int teamIndex, int slotIndex, UUID soulId) {
        if (owner == null) return false;
        SoulConfig cfg = soulConfig();
        teamIndex = Math.max(1, Math.min(teamCount(cfg), teamIndex));
        slotIndex = Math.max(0, Math.min(teamSlots(cfg) - 1, slotIndex));
        try {
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            ensureTeamsSeeded(owner, cap);
            if (soulId != null) {
                SoulInstance soul = repository.getSoul(soulId);
                if (soul == null || !owner.equals(soul.getOwnerId())) return false;
                boolean allowDupSame = cfg != null && cfg.isTeamsAllowDuplicateSoulInSameTeam();
                if (!allowDupSame && repository.isSoulAssignedToTeam(owner, teamIndex, soulId, slotIndex)) {
                    return false;
                }
            }
            repository.setTeamSlot(owner, teamIndex, slotIndex, soulId);
            invalidateSelectedCache(owner);
            return true;
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to set team slot for " + owner + ": " + e);
            return false;
        }
    }

    public void clearTeamSlot(UUID owner, int teamIndex, int slotIndex) {
        if (owner == null) return;
        SoulConfig cfg = soulConfig();
        teamIndex = Math.max(1, Math.min(teamCount(cfg), teamIndex));
        slotIndex = Math.max(0, Math.min(teamSlots(cfg) - 1, slotIndex));
        try {
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            ensureTeamsSeeded(owner, cap);
            repository.clearTeamSlot(owner, teamIndex, slotIndex);
            invalidateSelectedCache(owner);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to clear team slot for " + owner + ": " + e);
        }
    }

    public void clearTeam(UUID owner, int teamIndex) {
        if (owner == null) return;
        SoulConfig cfg = soulConfig();
        teamIndex = Math.max(1, Math.min(teamCount(cfg), teamIndex));
        try {
            int cap = cfg != null ? cfg.computeVaultCapacity(getSummoningLevel(owner)) : 30;
            ensureTeamsSeeded(owner, cap);
            repository.clearTeam(owner, teamIndex);
            invalidateSelectedCache(owner);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to clear team for " + owner + ": " + e);
        }
    }

    public void clearSoulFromAllTeams(UUID owner, UUID soulId) {
        if (owner == null || soulId == null) return;
        try {
            repository.clearSoulFromAllTeams(owner, soulId);
            invalidateSelectedCache(owner);
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[Souls] Failed to clear soul from teams for " + owner + ": " + e);
        }
    }

    public record TeamActivationResult(CastResult result, int spawned, int skippedInvalid, int teamIndex, String detail) {
        public static TeamActivationResult fail(CastResult r, int teamIndex, String detail) { return new TeamActivationResult(r, 0, 0, teamIndex, detail); }
        public static TeamActivationResult recalled(int teamIndex) { return new TeamActivationResult(CastResult.RECALLED, 0, 0, teamIndex, null); }
        public static TeamActivationResult ok(CastResult r, int teamIndex, int spawned, int skipped) { return new TeamActivationResult(r, spawned, skipped, teamIndex, null); }
    }

    public boolean recallActiveTeam(Player player) {
        if (player == null) return false;
        var mgr = plugin.getSummonManager();
        if (mgr == null) return false;
        int recalled = mgr.recallAll(player);
        if (recalled > 0) {
            player.sendMessage("§d[Souls] §eRecalled §f" + recalled + "§e summon" + (recalled == 1 ? "" : "s") + "§7.");
        }
        return recalled > 0;
    }

    public TeamActivationResult activateTeam(Player player, int teamIndex) {
        if (player == null) return TeamActivationResult.fail(CastResult.NO_PLAYER, teamIndex, "no-player");
        SoulConfig cfg = soulConfig();
        if (cfg == null || !cfg.isEnabled()) return TeamActivationResult.fail(CastResult.DISABLED, teamIndex, "disabled");
        var summonCfg = plugin.getSummonConfig();
        var summonMgr = plugin.getSummonManager();
        var tracker = plugin.getSummonTracker();
        if (summonCfg == null || summonMgr == null || tracker == null || !summonCfg.isEnabled()) {
            return TeamActivationResult.fail(CastResult.NO_SYSTEM, teamIndex, "no-system");
        }
        teamIndex = Math.max(1, Math.min(teamCount(cfg), teamIndex));
        setSelectedTeam(player.getUniqueId(), teamIndex);

        // Resolve and validate team slots (skip/clean invalid)
        Map<Integer, UUID> slots = getTeam(player.getUniqueId(), teamIndex);
        int slotsPerTeam = teamSlots(cfg);
        List<SummonManager.TeamSlotSpawn> toSpawn = new ArrayList<>(slotsPerTeam);
        int skippedInvalid = 0;
        for (int slot = 0; slot < slotsPerTeam; slot++) {
            UUID soulId = slots.get(slot);
            if (soulId == null) continue;
            SoulInstance soul = repositoryGetSoulSafe(soulId);
            boolean valid = soul != null && player.getUniqueId().equals(soul.getOwnerId());
            if (!valid) {
                clearTeamSlot(player.getUniqueId(), teamIndex, slot);
                skippedInvalid++;
                continue;
            }
            // basic allow check for activation context
            if (!cfg.isAllowed(soul.getEntityType())) {
                clearTeamSlot(player.getUniqueId(), teamIndex, slot);
                skippedInvalid++;
                continue;
            }
            toSpawn.add(new SummonManager.TeamSlotSpawn(slot, soul));
        }
        if (toSpawn.isEmpty()) {
            if (skippedInvalid > 0) {
                player.sendMessage("§c[Souls] Group " + teamIndex + " has no valid summons §7(cleaned " + skippedInvalid + " invalid slot" + (skippedInvalid == 1 ? "" : "s") + ").");
            } else {
                player.sendMessage("§c[Souls] Group " + teamIndex + " is empty.");
            }
            return TeamActivationResult.fail(CastResult.NO_SELECTED_SOUL, teamIndex, "empty-or-invalid-team");
        }

        boolean hasActive = summonMgr.hasActiveTeamSummons(player.getUniqueId());
        int activeTeam = summonMgr.getActiveTeamIndex(player.getUniqueId());
        boolean recallOnly = hasActive && activeTeam == teamIndex;
        if (recallOnly) {
            long nowMs = System.currentTimeMillis();
            long lastSpawn = lastTeamSpawnAt.getOrDefault(player.getUniqueId(), 0L);
            if (nowMs - lastSpawn < 500L) {
                return TeamActivationResult.ok(CastResult.OK, teamIndex, 0, 0);
            }
            int recalled = summonMgr.recallAll(player);
            player.sendMessage("§d[Souls] §eRecalled Group " + teamIndex + " §7(" + recalled + " summon" + (recalled == 1 ? "" : "s") + ")");
            return TeamActivationResult.recalled(teamIndex);
        }

        // Pre-spawn checks before mana spend / irreversible transitions
        if (!player.isOnline() || player.isDead() || player.getWorld() == null) {
            return TeamActivationResult.fail(CastResult.NO_PLAYER, teamIndex, "invalid-player-state");
        }

        double manaCost = Math.max(0.0, cfg.getCastActivateManaCost());
        boolean manaEnabled = cfg.isCastManaEnabled() && manaCost > 0.0 && summonCfg != null && summonCfg.isManaEnabled();
        if (manaEnabled && !hasEnoughAuraMana(player, manaCost)) {
            player.sendMessage("§c[Souls] Not enough mana to activate Group " + teamIndex + " §7(" + String.format(Locale.ROOT, "%.1f", manaCost) + ").");
            return TeamActivationResult.fail(CastResult.NO_MANA, teamIndex, "no-mana");
        }

        boolean isHotswap = hasActive && activeTeam != -1 && activeTeam != teamIndex;
        if (hasActive && activeTeam == -1) {
            // Mixed/legacy active state: normalize by recall before team activation
            isHotswap = true;
        }

        if (isHotswap) {
            summonMgr.recallAll(player);
        }

        if (manaEnabled && !summonMgr.tryConsumeSoulCastMana(player, manaCost)) {
            player.sendMessage("§c[Souls] Could not spend mana to activate Group " + teamIndex + ".");
            return TeamActivationResult.fail(CastResult.NO_MANA, teamIndex, "mana-spend-failed");
        }

        SummonManager.TeamSpawnResult spawnRes = summonMgr.spawnResolvedTeam(player, teamIndex, toSpawn, null);
        if (spawnRes == null || !spawnRes.ok || spawnRes.spawned <= 0) {
            player.sendMessage("§c[Souls] Failed to activate Group " + teamIndex + " §7(spawn failed).");
            return TeamActivationResult.fail(CastResult.FAILED, teamIndex, spawnRes != null ? spawnRes.reason : "spawn-failed");
        }
        lastTeamSpawnAt.put(player.getUniqueId(), System.currentTimeMillis());

        try {
            var prog = plugin.getSummonProgression();
            if (prog != null) {
                for (SummonManager.TeamSlotSpawn tss : toSpawn) {
                    try { prog.onSoulCast(player, tss.soul != null ? tss.soul.getFamilyKey() : null); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        StringBuilder msg = new StringBuilder("§d[Souls] ");
        if (isHotswap) msg.append("§bHotswapped to Group ").append(teamIndex);
        else msg.append("§aActivated Group ").append(teamIndex);
        msg.append(" §7(").append(spawnRes.spawned).append(" summon").append(spawnRes.spawned == 1 ? "" : "s");
        if (skippedInvalid > 0) msg.append(", skipped ").append(skippedInvalid).append(" invalid");
        msg.append(")");
        player.sendMessage(msg.toString());
        return TeamActivationResult.ok(CastResult.OK, teamIndex, spawnRes.spawned, skippedInvalid);
    }

    private SoulInstance repositoryGetSoulSafe(UUID soulId) {
        try { return repository.getSoul(soulId); } catch (SQLException e) { return null; }
    }

    private boolean hasEnoughAuraMana(Player player, double cost) {
        if (cost <= 0.0) return true;
        try {
            if (!soulConfig().isCastManaEnabled()) return true;
            if (plugin.getServer().getPluginManager().getPlugin("AuraSkills") == null) {
                return plugin.getSummonConfig() == null || plugin.getSummonConfig().isManaAllowWhenAuraSkillsMissing();
            }
            Class<?> apiClass = Class.forName("dev.aurelium.auraskills.api.AuraSkillsApi");
            Object api = apiClass.getMethod("get").invoke(null);
            if (api == null) return plugin.getSummonConfig() == null || plugin.getSummonConfig().isManaAllowWhenAuraSkillsMissing();
            Object user = apiClass.getMethod("getUser", UUID.class).invoke(api, player.getUniqueId());
            if (user == null) return plugin.getSummonConfig() == null || plugin.getSummonConfig().isManaAllowWhenAuraSkillsMissing();
            try {
                Object loaded = user.getClass().getMethod("isLoaded").invoke(user);
                if (loaded instanceof Boolean b && !b) return plugin.getSummonConfig() == null || plugin.getSummonConfig().isManaAllowWhenAuraSkillsMissing();
            } catch (Throwable ignored) {}
            double mana = ((Number) user.getClass().getMethod("getMana").invoke(user)).doubleValue();
            return mana + 1.0e-9 >= cost;
        } catch (Throwable t) {
            return plugin.getSummonConfig() == null || plugin.getSummonConfig().isManaAllowWhenAuraSkillsMissing();
        }
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
        sb.append("§7Trait: §f").append(a.getTrait() != null ? a.getTrait().displayName() : "-").append(" §7vs §f").append(b.getTrait() != null ? b.getTrait().displayName() : "-").append("\n");
        sb.append("§7Affixes A: §f").append(affixSummary(a)).append("\n");
        sb.append("§7Affixes B: §f").append(affixSummary(b));
        return sb.toString();
    }

    public String inspect(SoulInstance s) {
        return inspect(null, s);
    }

    public String inspect(UUID viewerId, SoulInstance s) {
        if (s == null) return "Soul not found.";
        StringBuilder sb = new StringBuilder();
        sb.append("§dSoul ").append(shortId(s)).append(" §7(").append(s.getSoulId()).append(")\n");
        sb.append("§7Family: §f").append(s.getFamilyKey()).append(" §7Category: §f").append(s.getCategory()).append("\n");
        sb.append("§7Rarity: §f").append(s.getRarity()).append(" §7Quality: §f")
                .append(String.format(java.util.Locale.ROOT, "%.2f%%", s.getQualityScore())).append("\n");
        SoulBaseStats base = effectiveBaseStats(s);
        double hp = computeSpawnedMaxHealth(viewerId != null ? viewerId : s.getOwnerId(), s);
        double atk = estimateAverageHitDamage(viewerId != null ? viewerId : s.getOwnerId(), s);
        double mv = computeSpawnedMovementBlocksPerSecond(s);
        double aps = Math.max(SoulBaseStats.MIN_ATTACK_SPEED_APS, Math.min(SoulBaseStats.MAX_ATTACK_SPEED_APS, base.attackSpeed()));
        double def = SoulBaseStats.clampDefense(base.defense());
        sb.append("§7Stats: §e").append(String.format(java.util.Locale.ROOT, "%.2f", hp)).append(" HP")
                .append(" §9").append(String.format(java.util.Locale.ROOT, "%.2f", def)).append(" DEF")
                .append(" §c").append(String.format(java.util.Locale.ROOT, "%.2f", atk)).append(" ATK")
                .append(" §b").append(String.format(java.util.Locale.ROOT, "%.2f", mv)).append(" bps")
                .append(" §a").append(String.format(java.util.Locale.ROOT, "%.2f", aps)).append(" APS\n");
        sb.append("§7Enhancement: §fLv ").append(Math.max(0, s.getEnhanceLevel())).append("\n");
        sb.append("§7Trait: §f").append(s.getTrait() != null ? s.getTrait().displayName() : "-")
                .append(" §7Flags: ").append(s.isFavorite() ? "§e[Fav]" : "§7-").append("\n");
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
     * Estimate the average melee hit damage this soul would deal (non-elite baseline),
     * mirroring SummonCombatService.applyMinionHit/SummonAbilityService crit resolution as closely as
     * possible without target-specific context. Packbound is omitted (ally-dependent).
     * Returns the soul's full (pre group-division) damage stat for display/tooltips.
     */
    public double estimateAverageHitDamage(UUID ownerId, SoulInstance soul) {
        if (soul == null) return 0.0;
        var summonCfg = plugin.getSummonConfig();

        // Core base: soul's effective (enhanced) attack stat (full soul stat, pre-division).
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
        PreviewContext preview = new PreviewContext(plugin, summonCfg, ownerId, soul, snap);
        try {
            for (var trait : soul.getTraits()) {
                if (trait == null) continue;
                SoulTraitHooks hooks = trait.hooks();
                if (hooks != null) hooks.contributePreview(preview);
            }
        } catch (Throwable ignored) {}
        soulDamageMult *= preview.outgoingDamageMult();
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
                chance += preview.critChanceBonus();
                multiplier += preview.critDamageBonus();
                chance = Math.max(0.0, Math.min(summonCfg.getCritMaxChance(), chance));
                multiplier = Math.max(summonCfg.getCritMinDamageMultiplier(), Math.min(summonCfg.getCritMaxDamageMultiplier(), multiplier));
                baseDamage *= (1.0 + chance * (Math.max(1.0, multiplier) - 1.0));
            }
        } catch (Throwable ignored) {}

        return Math.max(0.0, baseDamage);
    }

    /**
     * Breakdown of spawned max health (full effective per-summon): [baseIncludingOwner, affixBonus(VIGOROUS), ownerBonus, total].
     * Team/loadout: no group division at spawn.
     */
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

    /**
     * Quick team activation (Soul Focus): activate/recall the selected team.
     * Team/loadout overhaul replaces legacy selected-soul casting.
     */
    public CastAttempt castSelected(Player player) {
        if (player == null) return CastAttempt.of(CastResult.NO_PLAYER);
        int team = getSelectedTeam(player.getUniqueId());
        TeamActivationResult res = activateTeam(player, team);
        if (res == null) return CastAttempt.of(CastResult.FAILED);
        return new CastAttempt(res.result(), res.spawned(), res.detail());
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
