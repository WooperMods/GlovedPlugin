package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixRoll;
import dev.gloved.glovedplugin.summon.soul.domain.SoulGenerator;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulQuality;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.db.SoulDatabase;
import dev.gloved.glovedplugin.summon.soul.db.SoulRepository;
import dev.gloved.glovedplugin.summon.soul.table.item.CondensedSoulEssenceItem;
import dev.gloved.glovedplugin.summon.soul.table.item.SoulCatalystItem;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitDefinition;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRegistry;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraits;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
                            generated.getBaseStats(), generated.getEnhanceLevel(), generated.getGroupCapacity(),
                            0, 0, java.util.Set.of());
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
            try {
                var sc = plugin.getSummonConfig();
                var codexCfg = sc != null ? sc.getSoulCodexConfig() : null;
                if (codexCfg != null && codexCfg.isEnabled() && result.getEnhanceLevel() == codexCfg.getRefinedEnhanceLevel()) {
                    var codex = soulService.getCodexService();
                    var p = org.bukkit.Bukkit.getPlayer(owner);
                    if (codex != null && p != null) codex.onSoulRefined(p, result);
                }
            } catch (Throwable ignored) {}
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

                SoulBaseStats baseStats =
                        SoulGenerator.rollBaseStats(soul.getEntityType(), soul.getRarity(), null, config());
                String typeKey = soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey();
                int groupCapacity = SoulGenerator.rollBaseGroupCapacity(
                        soul.getRarity(), soul.getCategory(), typeKey, soulService.soulConfig());
                List<SoulAffixRoll> affixes = SoulGenerator.rollAffixesForRarity(
                        soul.getRarity(), soulService.soulConfig(), plugin.getSummonConfig(),
                        soul.getEntityType(), groupCapacity);
                double quality = SoulQuality.percent(soul.getRarity(), affixes);
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

    // ------------------------------------------------------------------ condense

    public TableResult condenseEssence(Player player, int units) {
        if (player == null) return TableResult.fail("Invalid player");
        UUID owner = player.getUniqueId();
        if (units <= 0) return TableResult.fail("Invalid amount");
        SoulTableConfig cfg = config();
        if (!cfg.isCondenseEnabled()) return TableResult.fail("Condensing is disabled");
        int rate = cfg.condenseRate();
        long costLong = (long) units * (long) rate;
        if (costLong > Integer.MAX_VALUE) return TableResult.fail("Amount too large");
        int cost = (int) costLong;
        if (!CondensedSoulEssenceItem.hasSpaceFor(player.getInventory(), CondensedSoulEssenceItem.create(plugin, 1, cfg), units)) {
            return TableResult.fail("No inventory space");
        }
        int cap = vaultCapacity(owner);
        try {
            boolean spent = database.transaction(conn -> repository.spendEssence(owner, cost, cap));
            if (!spent) return TableResult.fail(NOT_ENOUGH);
            try {
                CondensedSoulEssenceItem.giveToPlayer(plugin, player, units, cfg);
            } catch (Throwable t) {
                try {
                    database.transaction(conn -> { repository.addEssence(owner, cost, cap); return null; });
                } catch (Throwable refundErr) {
                    GlovedPlugin.logDebug("[SoulTable] condense refund failed: " + refundErr);
                }
                return TableResult.fail("Could not grant condensed essence");
            }
            return TableResult.ok("Condensed " + cost + " Essence into " + units + " item(s)", null);
        } catch (TableFailure failure) {
            return TableResult.fail(failure.getMessage());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] condense failed: " + e);
            return TableResult.fail("Database error");
        }
    }

    public TableResult decondenseEssence(Player player, int units) {
        if (player == null) return TableResult.fail("Invalid player");
        UUID owner = player.getUniqueId();
        if (units <= 0) return TableResult.fail("Invalid amount");
        SoulTableConfig cfg = config();
        if (!cfg.isCondenseEnabled()) return TableResult.fail("Condensing is disabled");
        if (!cfg.condenseReversible()) return TableResult.fail("Decondensing is disabled");
        int rate = cfg.condenseRate();
        long gainedLong = (long) units * (long) rate;
        if (gainedLong > Integer.MAX_VALUE) return TableResult.fail("Amount too large");
        int gained = (int) gainedLong;
        if (CondensedSoulEssenceItem.countInInventory(plugin, player) < units) {
            return TableResult.fail("Not enough Condensed Soul Essence");
        }
        if (!CondensedSoulEssenceItem.removeFromInventory(plugin, player, units)) {
            return TableResult.fail("Could not remove condensed essence");
        }
        int cap = vaultCapacity(owner);
        try {
            database.transaction(conn -> { repository.addEssence(owner, gained, cap); return null; });
            return TableResult.ok("Converted " + units + " item(s) into " + gained + " Essence", null);
        } catch (Throwable e) {
            // Best-effort restore items
            try { CondensedSoulEssenceItem.giveToPlayer(plugin, player, units, cfg); } catch (Throwable ignored) {}
            GlovedPlugin.logDebug("[SoulTable] decondense failed: " + e);
            return TableResult.fail("Database error");
        }
    }

    // ------------------------------------------------------------------ infusion

    public int infusionEssenceCostAdd(SoulRarity rarity) { return config().infusionEssenceCost("ADD", rarity); }
    public int infusionEssenceCostReplace(SoulRarity rarity) { return config().infusionEssenceCost("REPLACE", rarity); }
    public int infusionCatalystCostAdd() { return config().infusionCatalystCost("ADD"); }
    public int infusionCatalystCostReplace() { return config().infusionCatalystCost("REPLACE"); }

    public String infusionPrereqFailure(SoulInstance soul) {
        SoulTableConfig cfg = config();
        if (soul == null) return "Invalid soul";
        if (!cfg.isInfusionEnabled()) return "Infusion is disabled";
        if (!cfg.infusionRarityAllowed(soul.getRarity())) {
            return "Requires " + cfg.infusionMinRarity().name() + "+ rarity";
        }
        if (soul.getEnhanceLevel() < cfg.infusionMinEnhanceLevel()) {
            return "Requires enhancement level " + cfg.infusionMinEnhanceLevel() + "+";
        }
        return null;
    }

    public boolean isTraitOnSoul(SoulInstance soul, String traitId) {
        if (soul == null || traitId == null) return false;
        return soul.hasTraitId(traitId);
    }

    public List<SoulTraitDefinition> eligibleInfusionTraits(SoulInstance soul, String replacingTraitId) {
        if (soul == null) return List.of();
        SoulTraitRegistry reg = SoulTraitRegistry.getActive();
        if (reg == null) return List.of();
        String replacing = SoulTrait.canonicalize(replacingTraitId);
        List<SoulTraitDefinition> out = new ArrayList<>();
        for (SoulTraitDefinition def : reg.all()) {
            if (def == null || def.id() == null) continue;
            String id = SoulTrait.canonicalize(def.id());
            if (id == null) continue;
            boolean present = soul.hasTraitId(id);
            if (replacing != null && replacing.equals(id)) present = false;
            if (present) continue;
            out.add(def);
        }
        out.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return out;
    }

    public List<String> infusionWarnings(SoulInstance soul, SoulTraitDefinition trait, String replacingTraitId) {
        if (soul == null || trait == null) return List.of();
        List<String> warnings = new ArrayList<>();
        String id = SoulTrait.canonicalize(trait.id());
        // Build projected soul to evaluate situational usefulness
        SoulInstance projected = projectedSoulForTrait(soul, id, replacingTraitId, true);
        if (projected == null) projected = soul;
        if (SoulTraits.ID_PACKBOUND.equals(id)) {
            int cap = 1;
            try { cap = soulService.effectiveGroupCapacity(projected); } catch (Throwable ignored) { cap = 1; }
            if (cap <= 1) {
                warnings.add("§eSituational: Packbound is weak at current effective group capacity (" + cap + ").");
            } else {
                warnings.add("§aSynergy: Packbound benefits from multiple summons (cap " + cap + ").");
            }
        }
        // Generation-only roll conditions: warn if definition would not roll for projected context (informational)
        try {
            SoulTableConfig cfg = config();
            boolean strict = cfg.isInfusionStrictRollConditions();
            var ctx = buildRollContext(projected, replacingTraitId, id);
            if (ctx != null && !trait.canRoll(ctx)) {
                warnings.add("§eSituational: this trait's natural roll conditions are not met for this soul.");
                if (strict) warnings.add("§cStrict mode: this trait cannot be infused.");
            }
        } catch (Throwable ignored) {}
        return warnings;
    }

    private dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRollContext buildRollContext(SoulInstance soul, String replacingTraitId, String candidateId) {
        if (soul == null) return null;
        SoulInstance projected = projectedSoulForTrait(soul, candidateId, replacingTraitId, false);
        try {
            return new dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRollContext(
                    soul.getOwnerId(), soul.getEntityType(), soul.getFamilyKey(), soul.getCategory(),
                    soul.getRarity(), projected != null ? projected.getGroupCapacity() : soul.getGroupCapacity(),
                    projected != null ? projected.getAffixes() : soul.getAffixes(),
                    soulService.soulConfig(), plugin.getSummonConfig());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private SoulInstance projectedSoulForTrait(SoulInstance soul, String candidateId, String replacingTraitId, boolean assumeAddIfMissing) {
        if (soul == null) return null;
        String candidate = SoulTrait.canonicalize(candidateId);
        String replacing = SoulTrait.canonicalize(replacingTraitId);
        List<SoulTrait> traits = new ArrayList<>(soul.getTraits());
        if (replacing != null) {
            traits.removeIf(t -> t != null && replacing.equals(t.id()));
            if (candidate != null) traits.add(SoulTrait.of(candidate));
        } else if (candidate != null) {
            boolean has = false;
            for (SoulTrait t : traits) if (t != null && candidate.equals(t.id())) { has = true; break; }
            if (!has && assumeAddIfMissing) traits.add(SoulTrait.of(candidate));
        }
        return soul.withTraits(traits);
    }

    public boolean canInfuseAdd(SoulInstance soul, String traitId) {
        if (soul == null || traitId == null) return false;
        SoulTableConfig cfg = config();
        if (infusionPrereqFailure(soul) != null) return false;
        SoulTraitDefinition def = resolveTraitDef(traitId);
        if (def == null) return false;
        String id = SoulTrait.canonicalize(def.id());
        if (soul.hasTraitId(id)) return false;
        int cap = cfg.infusionTraitCap(soul.getRarity());
        if (soul.traitCount() >= cap) return false;
        if (cfg.isInfusionStrictRollConditions()) {
            var ctx = buildRollContext(soul, null, id);
            if (ctx != null && !def.canRoll(ctx)) return false;
        }
        int eCost = cfg.infusionEssenceCost("ADD", soul.getRarity());
        int cCost = cfg.infusionCatalystCost("ADD");
        return eCost >= 0 && cCost >= 0;
    }

    public boolean canInfuseReplace(SoulInstance soul, String replaceTraitId, String newTraitId) {
        if (soul == null || replaceTraitId == null || newTraitId == null) return false;
        SoulTableConfig cfg = config();
        if (infusionPrereqFailure(soul) != null) return false;
        String oldId = SoulTrait.canonicalize(replaceTraitId);
        SoulTraitDefinition def = resolveTraitDef(newTraitId);
        if (def == null || oldId == null) return false;
        String newId = SoulTrait.canonicalize(def.id());
        if (!soul.hasTraitId(oldId)) return false;
        if (oldId.equals(newId)) return false;
        if (soul.hasTraitId(newId)) return false;
        if (cfg.isInfusionStrictRollConditions()) {
            var ctx = buildRollContext(soul, oldId, newId);
            if (ctx != null && !def.canRoll(ctx)) return false;
        }
        return true;
    }

    private SoulTraitDefinition resolveTraitDef(String traitId) {
        SoulTraitRegistry reg = SoulTraitRegistry.getActive();
        if (reg == null || traitId == null) return null;
        return reg.byId(traitId).orElse(null);
    }

    public TableResult infuseAddTrait(UUID owner, Player player, UUID soulId, String traitId) {
        if (owner == null || player == null || soulId == null || traitId == null) return TableResult.fail("Invalid request");
        SoulTableConfig cfg = config();
        SoulTraitDefinition def = resolveTraitDef(traitId);
        if (def == null) return TableResult.fail("Unknown trait");
        String newId = SoulTrait.canonicalize(def.id());
        int catalystCost = cfg.infusionCatalystCost("ADD");
        if (SoulCatalystItem.countInInventory(plugin, player) < catalystCost) {
            return TableResult.fail("Not enough Soul Catalysts");
        }
        try {
            int cap = vaultCapacity(owner);
            SoulInstance result = database.transaction(conn -> {
                SoulInstance soul = requireOwned(owner, soulId);
                String prereq = infusionPrereqFailure(soul);
                if (prereq != null) throw new TableFailure(prereq);
                if (soul.hasTraitId(newId)) throw new TableFailure("Soul already has this trait");
                int traitCap = cfg.infusionTraitCap(soul.getRarity());
                if (soul.traitCount() >= traitCap) throw new TableFailure("Trait capacity reached");
                if (cfg.isInfusionStrictRollConditions()) {
                    var ctx = buildRollContext(soul, null, newId);
                    if (ctx != null && !def.canRoll(ctx)) throw new TableFailure("Trait is not allowed by strict conditions");
                }
                int essenceCost = cfg.infusionEssenceCost("ADD", soul.getRarity());
                if (essenceCost == Integer.MAX_VALUE) throw new TableFailure("Infusion not available");
                if (!repository.spendEssence(owner, essenceCost, cap)) throw new TableFailure(NOT_ENOUGH);
                List<SoulTrait> traits = new ArrayList<>(soul.getTraits());
                traits.add(SoulTrait.of(newId));
                repository.updateSoulTraits(soulId, traits);
                return soul.withTraits(traits);
            });

            boolean removed = SoulCatalystItem.removeFromInventory(plugin, player, catalystCost);
            if (!removed) {
                // compensate: revert trait + refund essence
                try {
                    database.transaction(conn -> {
                        SoulInstance current = repository.getSoul(soulId);
                        SoulInstance before = null;
                        try { before = soulService.getSoulCached(soulId); } catch (Throwable ignored) {}
                        // safest revert: reload and remove infused trait if present
                        SoulInstance s = repository.getSoul(soulId);
                        if (s != null) {
                            List<SoulTrait> reverted = new ArrayList<>();
                            boolean skipped = false;
                            for (SoulTrait t : s.getTraits()) {
                                if (!skipped && t != null && newId.equals(t.id())) { skipped = true; continue; }
                                reverted.add(t);
                            }
                            repository.updateSoulTraits(soulId, reverted);
                        }
                        repository.addEssence(owner, cfg.infusionEssenceCost("ADD", result.getRarity()), cap);
                        return null;
                    });
                } catch (Throwable compErr) {
                    plugin.getLogger().warning("[SoulTable] Infusion catalyst removal failed and compensation failed: " + compErr.getMessage());
                }
                soulService.invalidateSoul(soulId, owner);
                return TableResult.fail("Could not consume catalysts (infusion rolled back)");
            }

            soulService.invalidateSoul(soulId, owner);
            try {
                var sc = plugin.getSummonConfig();
                var codexCfg = sc != null ? sc.getSoulCodexConfig() : null;
                if (codexCfg != null && codexCfg.isEnabled()) {
                    var codex = soulService.getCodexService();
                    var p = org.bukkit.Bukkit.getPlayer(owner);
                    if (codex != null && p != null) codex.onSoulRefined(p, result);
                }
            } catch (Throwable ignored) {}
            return TableResult.ok("Infused trait " + def.displayName() + " (applies on next summon)", result);
        } catch (TableFailure failure) {
            return TableResult.fail(failure.getMessage());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] infusion add failed: " + e);
            return TableResult.fail("Database error");
        }
    }

    public TableResult infuseReplaceTrait(UUID owner, Player player, UUID soulId, String replaceTraitId, String newTraitId) {
        if (owner == null || player == null || soulId == null || replaceTraitId == null || newTraitId == null) {
            return TableResult.fail("Invalid request");
        }
        SoulTableConfig cfg = config();
        SoulTraitDefinition def = resolveTraitDef(newTraitId);
        if (def == null) return TableResult.fail("Unknown trait");
        String oldId = SoulTrait.canonicalize(replaceTraitId);
        String newId = SoulTrait.canonicalize(def.id());
        if (oldId == null) return TableResult.fail("Invalid trait");
        if (oldId.equals(newId)) return TableResult.fail("Choose a different trait");
        int catalystCost = cfg.infusionCatalystCost("REPLACE");
        if (SoulCatalystItem.countInInventory(plugin, player) < catalystCost) {
            return TableResult.fail("Not enough Soul Catalysts");
        }
        try {
            int cap = vaultCapacity(owner);
            SoulInstance result = database.transaction(conn -> {
                SoulInstance soul = requireOwned(owner, soulId);
                String prereq = infusionPrereqFailure(soul);
                if (prereq != null) throw new TableFailure(prereq);
                if (!soul.hasTraitId(oldId)) throw new TableFailure("Soul does not have that trait");
                if (soul.hasTraitId(newId)) throw new TableFailure("Soul already has this trait");
                if (cfg.isInfusionStrictRollConditions()) {
                    var ctx = buildRollContext(soul, oldId, newId);
                    if (ctx != null && !def.canRoll(ctx)) throw new TableFailure("Trait is not allowed by strict conditions");
                }
                int essenceCost = cfg.infusionEssenceCost("REPLACE", soul.getRarity());
                if (essenceCost == Integer.MAX_VALUE) throw new TableFailure("Infusion not available");
                if (!repository.spendEssence(owner, essenceCost, cap)) throw new TableFailure(NOT_ENOUGH);
                List<SoulTrait> traits = new ArrayList<>();
                boolean replaced = false;
                for (SoulTrait t : soul.getTraits()) {
                    if (t != null && oldId.equals(t.id())) {
                        traits.add(SoulTrait.of(newId));
                        replaced = true;
                    } else {
                        traits.add(t);
                    }
                }
                if (!replaced) throw new TableFailure("Soul does not have that trait");
                repository.updateSoulTraits(soulId, traits);
                return soul.withTraits(traits);
            });

            boolean removed = SoulCatalystItem.removeFromInventory(plugin, player, catalystCost);
            if (!removed) {
                try {
                    database.transaction(conn -> {
                        SoulInstance s = repository.getSoul(soulId);
                        if (s != null) {
                            List<SoulTrait> reverted = new ArrayList<>();
                            for (SoulTrait t : s.getTraits()) {
                                if (t != null && newId.equals(t.id())) reverted.add(SoulTrait.of(oldId));
                                else reverted.add(t);
                            }
                            repository.updateSoulTraits(soulId, reverted);
                        }
                        repository.addEssence(owner, cfg.infusionEssenceCost("REPLACE", result.getRarity()), cap);
                        return null;
                    });
                } catch (Throwable compErr) {
                    plugin.getLogger().warning("[SoulTable] Infusion catalyst removal failed and compensation failed: " + compErr.getMessage());
                }
                soulService.invalidateSoul(soulId, owner);
                return TableResult.fail("Could not consume catalysts (infusion rolled back)");
            }

            soulService.invalidateSoul(soulId, owner);
            try {
                var sc = plugin.getSummonConfig();
                var codexCfg = sc != null ? sc.getSoulCodexConfig() : null;
                if (codexCfg != null && codexCfg.isEnabled()) {
                    var codex = soulService.getCodexService();
                    var p = org.bukkit.Bukkit.getPlayer(owner);
                    if (codex != null && p != null) codex.onSoulRefined(p, result);
                }
            } catch (Throwable ignored) {}
            return TableResult.ok("Replaced trait with " + def.displayName() + " (applies on next summon)", result);
        } catch (TableFailure failure) {
            return TableResult.fail(failure.getMessage());
        } catch (SQLException e) {
            GlovedPlugin.logDebug("[SoulTable] infusion replace failed: " + e);
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
