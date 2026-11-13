package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixRoll;
import dev.gloved.glovedplugin.summon.soul.domain.SoulGenerator;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulQuality;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.db.SoulDatabase;
import dev.gloved.glovedplugin.summon.soul.db.SoulRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;

/**
 * Combine / enhance / reforge / salvage transactions for the Soul Table.
 *
 * <p>Every mutation re-validates ownership, rarity, level and Essence <b>inside</b> the database
 * transaction. The GUI is never trusted, and a failure rolls back both souls and Essence.</p>
 */
public final class SoulTableService {

    public record TableResult(boolean ok, String message, SoulInstance result) {
        public static TableResult ok(String message, SoulInstance result) {
            return new TableResult(true, message, result);
        }

        public static TableResult fail(String message) {
            return new TableResult(false, message, null);
        }
    }

    private static final String NOT_ENOUGH = "Not enough Essence";
    private static final String NO_LONGER_AVAILABLE = "Soul no longer available";
    private static final String MYTHIC_NO_COMBINE = "Mythic souls cannot be combined";
    private static final String MAX_LEVEL = "Maximum soul level reached";
    private static final String FAMILY_MISMATCH = "Souls must share the same family and rarity";
    private static final SoulTableConfig FALLBACK_CONFIG = new SoulTableConfig();

    private final GlovedPlugin plugin;
    private final SoulDatabase database;
    private final SoulRepository repository;
    private final SoulService soulService;

    public SoulTableService(GlovedPlugin plugin, SoulDatabase database, SoulService soulService) {
        this.plugin = plugin;
        this.database = database;
        this.soulService = soulService;
        this.repository = soulService.getRepository();
    }

    public SoulTableConfig config() {
        SoulTableConfig cfg = soulService.tableConfig();
        return cfg != null ? cfg : FALLBACK_CONFIG;
    }

    // ------------------------------------------------------------------ costs

    public int essenceCostCombine(SoulRarity target) {
        return config().combineCost(target);
    }

    public int essenceCostEnhance(int currentLevel) {
        return config().enhanceCost(currentLevel);
    }

    public int essenceCostReforge(SoulRarity rarity) {
        return config().reforgeCost(rarity);
    }

    // ------------------------------------------------------------------ actions

    /** Combines exactly three same-family, same-rarity souls into one of the next rarity. */
    public TableResult combine(UUID owner, List<UUID> soulIds, SoulRarity targetRarity) {
        if (owner == null) return TableResult.fail("Invalid player");
        List<UUID> ids = normalize(soulIds);
        if (ids.size() != 3) return TableResult.fail("Select exactly 3 souls");
        if (targetRarity == null) return TableResult.fail("Invalid target rarity");
        int cost = essenceCostCombine(targetRarity);
        if (cost == Integer.MAX_VALUE) return TableResult.fail(MYTHIC_NO_COMBINE);

        try {
            int cap = vaultCapacity(owner);
            SoulInstance result = database.transaction(conn -> {
                List<SoulInstance> souls = loadOwned(owner, ids);
                SoulInstance first = souls.get(0);
                if (first.getRarity() == SoulRarity.MYTHIC) throw new TableFailure(MYTHIC_NO_COMBINE);
                if (first.getRarity().ordinal() + 1 != targetRarity.ordinal()) {
                    throw new TableFailure("Invalid combine target");
                }
                for (SoulInstance soul : souls) {
                    if (soul.getRarity() != first.getRarity()
                            || !first.getFamilyKey().equals(soul.getFamilyKey())) {
                        throw new TableFailure(FAMILY_MISMATCH);
                    }
                }
                if (!repository.spendEssence(owner, cost, cap)) throw new TableFailure(NOT_ENOUGH);

                for (UUID id : ids) repository.deleteSoul(id);

                SoulInstance generated = SoulGenerator.generate(owner, first.getEntityType(),
                        soulService.soulConfig(), plugin.getSummonConfig(), targetRarity, 0, 0, 0, 0.0, null, config());
                if (!first.getFamilyKey().equals(generated.getFamilyKey())) {
                    // Keep the shared family key even if the generator derived it from the type name.
                    generated = new SoulInstance(generated.getSoulId(), generated.getOwnerId(),
                            generated.getEntityType(), first.getFamilyKey(), generated.getCategory(),
                            generated.getRarity(), generated.getQualityScore(), generated.getAffixes(),
                            generated.getTraits(), generated.getCreatedAt(), 0.0, false,
                            generated.getBaseStats(), generated.getEnhanceLevel(), generated.getGroupCapacity());
                }
                repository.insertSoul(generated);
                return generated;
            });

            for (UUID id : ids) soulService.invalidateSoul(id, owner);
            return TableResult.ok("Combined into " + result.getRarity().name() + " " + result.getFamilyKey() + " Soul", result);
        } catch (TableFailure failure) {
            return TableResult.fail(failure.getMessage());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] combine failed: " + e);
            return TableResult.fail("Database error");
        }
    }

    /** Raises one soul's enhancement level by one. */
    public TableResult enhance(UUID owner, UUID soulId) {
        if (owner == null || soulId == null) return TableResult.fail("Invalid soul");
        try {
            int cap = vaultCapacity(owner);
            SoulInstance result = database.transaction(conn -> {
                SoulInstance soul = requireOwned(owner, soulId);
                int level = soul.getEnhanceLevel();
                if (level >= config().getEnhanceMaxLevel()) throw new TableFailure(MAX_LEVEL);
                int cost = essenceCostEnhance(level);
                if (!repository.spendEssence(owner, cost, cap)) throw new TableFailure(NOT_ENOUGH);
                repository.updateSoulEnhanceLevel(soulId, level + 1);
                return soul.withEnhanceLevel(level + 1);
            });
            soulService.invalidateSoul(soulId, owner);
            return TableResult.ok("Enhanced to level " + result.getEnhanceLevel(), result);
        } catch (TableFailure failure) {
            return TableResult.fail(failure.getMessage());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] enhance failed: " + e);
            return TableResult.fail("Database error");
        }
    }

    /** Rerolls affixes, base stats, and base group capacity at the soul's current rarity, preserving everything else. */
    public TableResult reforge(UUID owner, UUID soulId) {
        if (owner == null || soulId == null) return TableResult.fail("Invalid soul");
        try {
            int cap = vaultCapacity(owner);
            SoulInstance result = database.transaction(conn -> {
                SoulInstance soul = requireOwned(owner, soulId);
                int soulCost = essenceCostReforge(soul.getRarity());
                if (soulCost == Integer.MAX_VALUE) throw new TableFailure("Reforge is not available for this soul");
                if (!repository.spendEssence(owner, soulCost, cap)) throw new TableFailure(NOT_ENOUGH);

                List<SoulAffixRoll> affixes = SoulGenerator.rollAffixesForRarity(
                        soul.getRarity(), soulService.soulConfig(), plugin.getSummonConfig(), soul.getEntityType());
                double quality = SoulQuality.percent(soul.getRarity(), affixes);
                SoulBaseStats baseStats =
                        SoulGenerator.rollBaseStats(soul.getEntityType(), soul.getRarity(), null, config());
                int groupCapacity = SoulGenerator.rollBaseGroupCapacity(
                        soul.getRarity(), soul.getCategory(),
                        soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey(),
                        soulService.soulConfig());
                repository.updateSoulAffixes(soulId, affixes, quality);
                repository.updateSoulBaseStats(soulId, baseStats);
                repository.updateSoulGroupCapacity(soulId, groupCapacity);
                return soul.withReforgedState(baseStats, affixes, quality, groupCapacity);
            });
            soulService.invalidateSoul(soulId, owner);
            return TableResult.ok("Reforged soul stats, affixes, and capacity", result);
        } catch (TableFailure failure) {
            return TableResult.fail(failure.getMessage());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] reforge failed: " + e);
            return TableResult.fail("Database error");
        }
    }

    /** Converts any number of selected souls into Essence (free to perform). */
    public TableResult salvage(UUID owner, List<UUID> soulIds) {
        if (owner == null) return TableResult.fail("Invalid player");
        List<UUID> ids = normalize(soulIds);
        if (ids.isEmpty()) return TableResult.fail("Select at least one soul");
        try {
            int cap = vaultCapacity(owner);
            int[] gained = {0};
            database.transaction(conn -> {
                List<SoulInstance> souls = loadOwned(owner, ids);
                int total = 0;
                for (SoulInstance soul : souls) total += soulService.essenceValueOf(soul.getRarity());
                for (UUID id : ids) repository.deleteSoul(id);
                if (total > 0) repository.addEssence(owner, total, cap);
                gained[0] = total;
                return null;
            });
            for (UUID id : ids) soulService.invalidateSoul(id, owner);
            return TableResult.ok("Salvaged " + ids.size() + " soul(s) for " + gained[0] + " Essence", null);
        } catch (TableFailure failure) {
            return TableResult.fail(failure.getMessage());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] salvage failed: " + e);
            return TableResult.fail("Database error");
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<SoulInstance> loadOwned(UUID owner, List<UUID> ids) throws SQLException {
        List<SoulInstance> souls = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            SoulInstance soul = repository.getSoul(id);
            if (soul == null || !owner.equals(soul.getOwnerId())) throw new TableFailure(NO_LONGER_AVAILABLE);
            souls.add(soul);
        }
        return souls;
    }

    private SoulInstance requireOwned(UUID owner, UUID soulId) throws SQLException {
        SoulInstance soul = repository.getSoul(soulId);
        if (soul == null || !owner.equals(soul.getOwnerId())) throw new TableFailure(NO_LONGER_AVAILABLE);
        return soul;
    }

    private int vaultCapacity(UUID owner) {
        try {
            var cfg = soulService.soulConfig();
            return cfg != null ? cfg.computeVaultCapacity(soulService.getSummoningLevel(owner)) : 30;
        } catch (Throwable ignored) {
            return 30;
        }
    }

    private static List<UUID> normalize(List<UUID> soulIds) {
        if (soulIds == null || soulIds.isEmpty()) return List.of();
        LinkedHashSet<UUID> unique = new LinkedHashSet<>();
        for (UUID id : soulIds) {
            if (id != null) unique.add(id);
        }
        return new ArrayList<>(unique);
    }

    /** Internal control-flow exception; the transaction helper rolls back on any RuntimeException. */
    private static final class TableFailure extends RuntimeException {
        TableFailure(String message) {
            super(message);
        }
    }
}
