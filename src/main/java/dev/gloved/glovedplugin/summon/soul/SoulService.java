package dev.gloved.glovedplugin.summon.soul;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.db.SoulDatabase;
import dev.gloved.glovedplugin.summon.soul.db.SoulRepository;
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

    public SoulConfig soulConfig() {
        try {
            var sc = plugin.getSummonConfig();
            return sc != null ? sc.getSoulConfig() : new SoulConfig();
        } catch (Throwable t) {
            return new SoulConfig();
        }
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
        SoulInstance soul = SoulGenerator.generate(killer.getUniqueId(), type, cfg, forced, level, pity.rare, pity.epic,
                capturedMaxHealth);

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
        SoulInstance soul = SoulGenerator.generate(owner.getUniqueId(), type, cfg, forcedRarity, level, 0, 0);
        return storeCapturedSoulDirect(owner, soul, cfg);
    }

    /** Simulate generation without persisting. */
    public SoulInstance simulate(EntityType type, SoulRarity forcedRarity) {
        SoulConfig cfg = soulConfig();
        UUID fake = new UUID(0, 0);
        return SoulGenerator.generate(fake, type, cfg, forcedRarity, 0, 0, 0);
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
                int essence = essenceValue(soul.getRarity());
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
                    false
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
                item.setCustomName(dev.gloved.glovedplugin.summon.soul.SoulDropItem.getDisplayName(soul));
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

    public static int essenceValue(SoulRarity rarity) {
        if (rarity == null) return 1;
        return switch (rarity) {
            case COMMON -> 1;
            case UNCOMMON -> 2;
            case RARE -> 5;
            case EPIC -> 12;
            case LEGENDARY -> 30;
            case MYTHIC -> 80;
        };
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
            final int durationTicks = 8;
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
        int essence = essenceValue(soul.getRarity());
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
        sb.append("§7Quality: §f").append(SoulQuality.format(a)).append(" §7vs §f").append(SoulQuality.format(b)).append("\n");
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
        sb.append("§7Rarity: §f").append(s.getRarity()).append(" §7Quality: §f").append(SoulQuality.format(s)).append("\n");
        sb.append("§7Trait: §f").append(s.getTrait() != null ? s.getTrait().name() : "-").append(" §7Flags: ").append(s.isFavorite() ? "§e[Fav]" : "§7-").append("\n");
        sb.append("§7Affixes: §f").append(affixSummary(s));
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

    public enum CastResult { OK, NO_SYSTEM, DISABLED, NO_PLAYER, NO_SELECTED_SOUL, NOT_ALLOWED, ON_COOLDOWN, NO_MANA, CAPPED, FAILED }

    public record CastAttempt(CastResult result, int spawned, String detail) {
        public static CastAttempt of(CastResult r) { return new CastAttempt(r, 0, null); }
        public static CastAttempt of(CastResult r, String d) { return new CastAttempt(r, 0, d); }
        public static CastAttempt ok(int spawned) { return new CastAttempt(CastResult.OK, spawned, null); }
    }

    /** Compute effective cast cooldown ticks for a soul (category/rarity/cost-efficiency aware). */
    public int computeCastCooldownTicks(SoulInstance soul, SoulConfig cfg) {
        if (cfg == null) return 240;
        double base = Math.max(20, cfg.getCastBaseCooldownTicks());
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
        // Enforce shared GCD floor
        computed = Math.max(computed, Math.max(0, cfg.getCastSharedGcdTicks()));
        return computed;
    }

    /** Compute effective cast mana cost for a soul. */
    public double computeCastManaCost(SoulInstance soul, SoulConfig cfg) {
        if (cfg == null) return 12.0;
        double base = Math.max(0.0, cfg.getCastBaseManaCost());
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
     * Compute the effective max health a minion would spawn with for this soul,
     * mirroring SummonManager.applySoulSnapshotToMinion:
     * base = saved mob max HP (or mob-type default if not saved)
     * scaledBase = base * soul health multiplier (VIGOROUS affix)
     * final = scaledBase + owner's AuraSkills health stat bonus
     */
    public double computeSpawnedMaxHealth(UUID ownerId, SoulInstance soul) {
        if (soul == null) return 0.0;
        double baseHealth;
        if (soul.getMaxHealth() > 0.0) {
            baseHealth = soul.getMaxHealth();
        } else {
            baseHealth = repository.resolveDefaultMaxHealth(
                    soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey());
        }
        SoulStatSnapshot snap = getSnapshot(soul);
        double healthMult = Math.max(0.5, Math.min(3.0, snap.healthMultiplier()));
        double scaledBase = Math.max(1.0, baseHealth * healthMult);
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
        return Math.max(1.0, scaledBase + ownerHealthBonus);
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
        if (!cfg.isAllowed(soul.getEntityType())) {
            player.sendMessage("§cThat soul type is not currently allowed.");
            return CastAttempt.of(CastResult.NOT_ALLOWED);
        }
        // cooldown
        long rem = summonMgr.getSoulCastCooldownRemainingMs(player.getUniqueId());
        if (rem > 0) {
            player.sendMessage("§cSoul cast on cooldown: §f" + String.format(java.util.Locale.ROOT, "%.1fs", rem / 1000.0));
            return CastAttempt.of(CastResult.ON_COOLDOWN, String.valueOf(rem));
        }
        // mana
        double manaCost = computeCastManaCost(soul, cfg);
        if (!summonMgr.tryConsumeSoulCastMana(player, manaCost)) {
            player.sendMessage("§cNot enough mana to cast this soul. §7(" + String.format(java.util.Locale.ROOT, "%.1f", manaCost) + ")");
            return CastAttempt.of(CastResult.NO_MANA);
        }
        // resolve template and spawn
        dev.gloved.glovedplugin.summon.SummonProfile profile = summonCfg.getProfile(soul.getEntityType());
        if (profile == null) {
            player.sendMessage("§cCould not resolve summon template for " + soul.getFamilyKey() + ".");
            return CastAttempt.of(CastResult.NOT_ALLOWED);
        }
        int spawned = summonMgr.spawnFromSoul(player, profile, soul, null, cfg.isReplaceIfCapped());
        if (spawned <= 0) {
            player.sendMessage("§eCannot summon now (active cap reached or spawn blocked).");
            return CastAttempt.of(CastResult.CAPPED);
        }
        int cdTicks = computeCastCooldownTicks(soul, cfg);
        summonMgr.setSoulCastCooldown(player.getUniqueId(), cdTicks * 50L);
        try {
            var prog = plugin.getSummonProgression();
            if (prog != null) prog.onSoulCast(player, soul.getFamilyKey());
        } catch (Throwable ignored) {}
        player.sendMessage("§dCast §f" + describe(soul) + " §7(+" + spawned + " summoned, -" + String.format(java.util.Locale.ROOT, "%.1f", manaCost) + " mana, " + String.format(java.util.Locale.ROOT, "%.1fs", cdTicks / 20.0) + " cd)");
        debug("Cast soul " + shortId(soul) + " by " + player.getName() + " family=" + soul.getFamilyKey() + " rarity=" + soul.getRarity() + " mana=" + manaCost + " cdTicks=" + cdTicks + " spawned=" + spawned);
        return CastAttempt.ok(spawned);
    }

    public static String describe(SoulInstance s) {
        if (s == null) return "?";
        return s.getRarity().name() + " " + prettyMobName(s.getEntityType() != null ? s.getEntityType().name() : s.getFamilyKey()) + " Soul (" + SoulQuality.format(s) + ")";
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
