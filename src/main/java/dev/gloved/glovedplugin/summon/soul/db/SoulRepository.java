package dev.gloved.glovedplugin.summon.soul.db;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.*;
import dev.gloved.glovedplugin.summon.soul.service.*;
import dev.gloved.glovedplugin.summon.soul.item.*;
import dev.gloved.glovedplugin.summon.soul.db.*;
import dev.gloved.glovedplugin.summon.soul.ui.*;
import dev.gloved.glovedplugin.summon.soul.table.*;
import dev.gloved.glovedplugin.summon.soul.table.ui.*;
import dev.gloved.glovedplugin.summon.soul.trap.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.Locale;
import dev.gloved.glovedplugin.summon.soul.domain.EntityCategory;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixRoll;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixType;
import dev.gloved.glovedplugin.summon.soul.domain.SoulBaseStats;
import dev.gloved.glovedplugin.summon.soul.domain.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.domain.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.domain.SoulTrait;
import dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig;

/**
 * JDBC repository for souls.db. Synchronous DB access; callers should avoid main-thread heavy use when possible.
 */
public class SoulRepository {
    private static final Gson GSON = new Gson();
    private static final Type AFFIX_LIST_TYPE = new TypeToken<List<AffixDto>>() {}.getType();

    private final GlovedPlugin plugin;
    private final SoulDatabase database;

    public SoulRepository(GlovedPlugin plugin, SoulDatabase database) {
        this.plugin = plugin;
        this.database = database;
    }

    private Connection conn() { return database.connection(); }

    public synchronized void insertSoul(SoulInstance soul) throws SQLException {
        String sql = "INSERT INTO souls (soul_id, owner_uuid, entity_type, family_key, category, rarity, quality, " +
                "affixes_json, trait, favorite, state, origin_context, created_at, max_health, base_stats_json, enhance_level, group_capacity, mastery_xp, mastery_level, mastery_claimed) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, soul.getSoulId().toString());
            ps.setString(2, soul.getOwnerId().toString());
            ps.setString(3, soul.getEntityType() != null ? soul.getEntityType().name() : soul.getFamilyKey());
            ps.setString(4, soul.getFamilyKey());
            ps.setString(5, soul.getCategory().name());
            ps.setString(6, soul.getRarity().name());
            ps.setDouble(7, soul.getQualityScore());
            ps.setString(8, serializeAffixes(soul.getAffixes()));
            ps.setString(9, serializeTraits(soul.getTraits()));
            ps.setInt(10, soul.isFavorite() ? 1 : 0);
            ps.setString(11, "VAULT");
            ps.setString(12, null);
            ps.setLong(13, soul.getCreatedAt());
            ps.setDouble(14, soul.getMaxHealth());
            ps.setString(15, soul.getBaseStats() != null ? soul.getBaseStats().toJson() : null);
            ps.setInt(16, Math.max(0, soul.getEnhanceLevel()));
            ps.setInt(17, Math.max(1, soul.getGroupCapacity()));
            ps.setInt(18, Math.max(0, soul.getMasteryXp()));
            ps.setInt(19, Math.max(0, soul.getMasteryLevel()));
            ps.setString(20, serializeMasteryClaimed(soul.getMasteryClaimedMilestones()));
            ps.executeUpdate();
        }
    }

    public synchronized List<SoulInstance> listSouls(UUID owner, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM souls WHERE owner_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<SoulInstance> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, Math.max(1, limit));
            ps.setInt(3, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapSoul(rs));
            }
        }
        return out;
    }

    public synchronized int countSouls(UUID owner) throws SQLException {
        String sql = "SELECT COUNT(*) FROM souls WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public synchronized SoulInstance getSoul(UUID soulId) throws SQLException {
        String sql = "SELECT * FROM souls WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, soulId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapSoul(rs) : null;
            }
        }
    }

    public synchronized void upsertCodex(UUID owner, String familyKey, SoulRarity rarity) throws SQLException {
        if (owner == null || familyKey == null || familyKey.isBlank()) return;
        String sel = "SELECT highest_rarity, captured_count, first_captured_at FROM soul_codex WHERE owner_uuid = ? AND family_key = ?";
        String highest = rarity != null ? rarity.name() : null;
        int count = 0;
        long firstAt = 0L;
        boolean exists = false;
        try (PreparedStatement ps = conn().prepareStatement(sel)) {
            ps.setString(1, owner.toString());
            ps.setString(2, familyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    highest = maxRarity(rs.getString("highest_rarity"), highest);
                    count = rs.getInt("captured_count");
                    try { firstAt = rs.getLong("first_captured_at"); } catch (SQLException ignored) {}
                }
            }
        }
        long now = System.currentTimeMillis();
        if (firstAt <= 0L) firstAt = now;
        if (exists) {
            String upd = "UPDATE soul_codex SET highest_rarity = ?, captured_count = ?, first_captured_at = ? WHERE owner_uuid = ? AND family_key = ?";
            try (PreparedStatement ps = conn().prepareStatement(upd)) {
                ps.setString(1, highest);
                ps.setInt(2, count + 1);
                ps.setLong(3, firstAt);
                ps.setString(4, owner.toString());
                ps.setString(5, familyKey);
                ps.executeUpdate();
            }
        } else {
            String ins = "INSERT INTO soul_codex (owner_uuid, family_key, highest_rarity, captured_count, first_captured_at) VALUES (?,?,?,?,?)";
            try (PreparedStatement ps = conn().prepareStatement(ins)) {
                ps.setString(1, owner.toString());
                ps.setString(2, familyKey);
                ps.setString(3, highest);
                ps.setInt(4, 1);
                ps.setLong(5, firstAt);
                ps.executeUpdate();
            }
        }
    }

    public synchronized PlayerSoulState getOrCreatePlayerState(UUID owner, int defaultCapacity) throws SQLException {
        String sel = "SELECT * FROM soul_player_state WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(sel)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String mode = "AGGRESSIVE";
                    try { mode = rs.getString("combat_mode"); } catch (SQLException ignored) {}
                    if (mode == null || mode.isBlank()) mode = "AGGRESSIVE";
                    String tableTab = "COMBINE";
                    try { tableTab = rs.getString("soul_table_tab"); } catch (SQLException ignored) {}
                    if (tableTab == null || tableTab.isBlank()) tableTab = "COMBINE";
                    int selectedTeam = 1;
                    try { selectedTeam = rs.getInt("selected_team"); } catch (SQLException ignored) {}
                    if (selectedTeam < 1 || selectedTeam > 4) selectedTeam = 1;
                    int soulsAcquiredTotal = 0;
                    int groupActivationsTotal = 0;
                    int soulTableActionsTotal = 0;
                    int soulRespawnsTotal = 0;
                    int soulTableCombineTotal = 0;
                    int soulTableEnhanceTotal = 0;
                    int soulTableReforgeTotal = 0;
                    int soulTableSalvageTotal = 0;
                    try { soulsAcquiredTotal = Math.max(0, rs.getInt("souls_acquired_total")); } catch (SQLException ignored) {}
                    try { groupActivationsTotal = Math.max(0, rs.getInt("group_activations_total")); } catch (SQLException ignored) {}
                    try { soulTableActionsTotal = Math.max(0, rs.getInt("soul_table_actions_total")); } catch (SQLException ignored) {}
                    try { soulRespawnsTotal = Math.max(0, rs.getInt("soul_respawns_total")); } catch (SQLException ignored) {}
                    try { soulTableCombineTotal = Math.max(0, rs.getInt("soul_table_combine_total")); } catch (SQLException ignored) {}
                    try { soulTableEnhanceTotal = Math.max(0, rs.getInt("soul_table_enhance_total")); } catch (SQLException ignored) {}
                    try { soulTableReforgeTotal = Math.max(0, rs.getInt("soul_table_reforge_total")); } catch (SQLException ignored) {}
                    try { soulTableSalvageTotal = Math.max(0, rs.getInt("soul_table_salvage_total")); } catch (SQLException ignored) {}
                    return new PlayerSoulState(owner,
                            rs.getInt("essence"),
                            rs.getInt("vault_capacity"),
                            rs.getInt("selected_slot"),
                            selectedTeam,
                            rs.getString("pity_json"),
                            mode,
                            tableTab,
                            soulsAcquiredTotal, groupActivationsTotal, soulTableActionsTotal,
                            soulRespawnsTotal, soulTableCombineTotal, soulTableEnhanceTotal, soulTableReforgeTotal, soulTableSalvageTotal);
                }
            }
        }
        long now = System.currentTimeMillis();
        String ins = "INSERT INTO soul_player_state (owner_uuid, essence, vault_capacity, pity_json, selected_slot, selected_team, doctrine, updated_at, combat_mode, soul_table_tab) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(ins)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, 0);
            ps.setInt(3, Math.max(1, defaultCapacity));
            ps.setString(4, "{}");
            ps.setInt(5, 0);
            ps.setInt(6, 1);
            ps.setString(7, null);
            ps.setLong(8, now);
            ps.setString(9, "AGGRESSIVE");
            ps.setString(10, "COMBINE");
            ps.executeUpdate();
        }
        return new PlayerSoulState(owner, 0, Math.max(1, defaultCapacity), 0, 1, "{}", "AGGRESSIVE", "COMBINE", 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public synchronized void setCombatMode(UUID owner, String combatMode, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET combat_mode = ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setString(1, combatMode == null ? "AGGRESSIVE" : combatMode);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void addEssence(UUID owner, int amount, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET essence = essence + ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setInt(1, Math.max(0, amount));
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void setEssence(UUID owner, int amount, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET essence = ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setInt(1, Math.max(0, amount));
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized int removeEssence(UUID owner, int amount, int defaultCapacity) throws SQLException {
        int toRemove = Math.max(0, amount);
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET essence = MAX(0, essence - ?), updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setInt(1, toRemove);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
        return getEssence(owner, defaultCapacity);
    }

    public synchronized int getEssence(UUID owner, int defaultCapacity) throws SQLException {
        return getOrCreatePlayerState(owner, defaultCapacity).essence();
    }

    /**
     * Atomically deducts essence. Returns false (and changes nothing) when the balance is too low.
     */
    public synchronized boolean spendEssence(UUID owner, int amount, int defaultCapacity) throws SQLException {
        int cost = Math.max(0, amount);
        if (cost == 0) return true;
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET essence = essence - ?, updated_at = ? " +
                "WHERE owner_uuid = ? AND essence >= ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setInt(1, cost);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.setInt(4, cost);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized void updateSoulEnhanceLevel(UUID soulId, int enhanceLevel) throws SQLException {
        String sql = "UPDATE souls SET enhance_level = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, enhanceLevel));
            ps.setString(2, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateSoulAffixes(UUID soulId, List<SoulAffixRoll> affixes, double quality) throws SQLException {
        String sql = "UPDATE souls SET affixes_json = ?, quality = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, serializeAffixes(affixes));
            ps.setDouble(2, quality);
            ps.setString(3, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateSoulBaseStats(UUID soulId, SoulBaseStats stats) throws SQLException {
        String sql = "UPDATE souls SET base_stats_json = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, stats != null ? stats.toJson() : null);
            ps.setString(2, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateSoulGroupCapacity(UUID soulId, int groupCapacity) throws SQLException {
        String sql = "UPDATE souls SET group_capacity = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, groupCapacity));
            ps.setString(2, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateSoulTraits(UUID soulId, List<SoulTrait> traits) throws SQLException {
        String sql = "UPDATE souls SET trait = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, serializeTraits(traits));
            ps.setString(2, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateSoulMastery(UUID soulId, int masteryXp, int masteryLevel, java.util.Set<Integer> claimed) throws SQLException {
        String sql = "UPDATE souls SET mastery_xp = ?, mastery_level = ?, mastery_claimed = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, masteryXp));
            ps.setInt(2, Math.max(0, masteryLevel));
            ps.setString(3, serializeMasteryClaimed(claimed));
            ps.setString(4, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void setSelectedSlot(UUID owner, int slot, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET selected_slot = ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setInt(1, Math.max(0, slot));
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updateVaultCapacity(UUID owner, int capacity, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET vault_capacity = ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setInt(1, Math.max(1, capacity));
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized String getSoulTableTab(UUID owner, int defaultCapacity) throws SQLException {
        return getOrCreatePlayerState(owner, defaultCapacity).soulTableTab();
    }

    public synchronized void setSoulTableTab(UUID owner, String tab, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET soul_table_tab = ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setString(1, tab == null || tab.isBlank() ? "COMBINE" : tab);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void updatePityJson(UUID owner, String pityJson, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET pity_json = ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setString(1, pityJson != null ? pityJson : "{}");
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized List<CodexEntry> listCodex(UUID owner, int limit) throws SQLException {
        String sql = "SELECT family_key, highest_rarity, captured_count, first_captured_at, mastered_count, refined_count, " +
                "discovered_claimed, collected_claimed, refined_claimed, mastered_claimed " +
                "FROM soul_codex WHERE owner_uuid = ? ORDER BY captured_count DESC, family_key ASC LIMIT ?";
        List<CodexEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapCodex(rs));
            }
        }
        return out;
    }

    public synchronized CodexEntry getCodexEntry(UUID owner, String familyKey) throws SQLException {
        if (owner == null || familyKey == null) return null;
        String sql = "SELECT family_key, highest_rarity, captured_count, first_captured_at, mastered_count, refined_count, " +
                "discovered_claimed, collected_claimed, refined_claimed, mastered_claimed " +
                "FROM soul_codex WHERE owner_uuid = ? AND family_key = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setString(2, familyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapCodex(rs) : null;
            }
        }
    }

    private CodexEntry mapCodex(ResultSet rs) throws SQLException {
        long firstAt = 0L; int mastered = 0, refined = 0;
        boolean d = false, c = false, r = false, m = false;
        try { firstAt = rs.getLong("first_captured_at"); } catch (SQLException ignored) {}
        try { mastered = rs.getInt("mastered_count"); } catch (SQLException ignored) {}
        try { refined = rs.getInt("refined_count"); } catch (SQLException ignored) {}
        try { d = rs.getInt("discovered_claimed") != 0; } catch (SQLException ignored) {}
        try { c = rs.getInt("collected_claimed") != 0; } catch (SQLException ignored) {}
        try { r = rs.getInt("refined_claimed") != 0; } catch (SQLException ignored) {}
        try { m = rs.getInt("mastered_claimed") != 0; } catch (SQLException ignored) {}
        return new CodexEntry(rs.getString("family_key"), rs.getString("highest_rarity"), rs.getInt("captured_count"),
                firstAt, mastered, refined, d, c, r, m);
    }

    public synchronized void incrementCodexMastered(UUID owner, String familyKey) throws SQLException {
        if (owner == null || familyKey == null) return;
        ensureCodexRow(owner, familyKey);
        String sql = "UPDATE soul_codex SET mastered_count = mastered_count + 1 WHERE owner_uuid = ? AND family_key = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setString(2, familyKey);
            ps.executeUpdate();
        }
    }

    public synchronized void incrementCodexRefined(UUID owner, String familyKey) throws SQLException {
        if (owner == null || familyKey == null) return;
        ensureCodexRow(owner, familyKey);
        String sql = "UPDATE soul_codex SET refined_count = refined_count + 1 WHERE owner_uuid = ? AND family_key = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setString(2, familyKey);
            ps.executeUpdate();
        }
    }

    public synchronized boolean setCodexClaimed(UUID owner, String familyKey, String tier, boolean claimed) throws SQLException {
        if (owner == null || familyKey == null || tier == null) return false;
        ensureCodexRow(owner, familyKey);
        String col = switch (tier.toUpperCase(Locale.ROOT)) {
            case "DISCOVERED" -> "discovered_claimed";
            case "COLLECTED" -> "collected_claimed";
            case "REFINED" -> "refined_claimed";
            case "MASTERED" -> "mastered_claimed";
            default -> null;
        };
        if (col == null) return false;
        String sql = "UPDATE soul_codex SET " + col + " = ? WHERE owner_uuid = ? AND family_key = ? AND " + col + " = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, claimed ? 1 : 0);
            ps.setString(2, owner.toString());
            ps.setString(3, familyKey);
            ps.setInt(4, claimed ? 0 : 1);
            return ps.executeUpdate() > 0;
        }
    }

    private void ensureCodexRow(UUID owner, String familyKey) throws SQLException {
        if (getCodexEntry(owner, familyKey) != null) return;
        String ins = "INSERT OR IGNORE INTO soul_codex (owner_uuid, family_key, highest_rarity, captured_count, first_captured_at) VALUES (?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(ins)) {
            ps.setString(1, owner.toString());
            ps.setString(2, familyKey);
            ps.setString(3, null);
            ps.setInt(4, 0);
            ps.setLong(5, 0L);
            ps.executeUpdate();
        }
    }

    public synchronized int countCodex(UUID owner) throws SQLException {
        String sql = "SELECT COUNT(*) FROM soul_codex WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public synchronized void setLoadoutSoul(UUID owner, int slot, UUID soulId, boolean selected) throws SQLException {
        String del = "DELETE FROM soul_loadouts WHERE owner_uuid = ? AND slot = ?";
        try (PreparedStatement ps = conn().prepareStatement(del)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, Math.max(0, slot));
            ps.executeUpdate();
        }
        if (soulId == null) return;
        String ins = "INSERT INTO soul_loadouts (owner_uuid, slot, soul_id, selected) VALUES (?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(ins)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, Math.max(0, slot));
            ps.setString(3, soulId.toString());
            ps.setInt(4, selected ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public synchronized UUID getLoadoutSoulId(UUID owner, int slot) throws SQLException {
        String sql = "SELECT soul_id FROM soul_loadouts WHERE owner_uuid = ? AND slot = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, Math.max(0, slot));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString("soul_id");
                    if (s == null) return null;
                    try { return UUID.fromString(s); } catch (Exception ignored) { return null; }
                }
            }
        }
        return null;
    }

    public synchronized java.util.Map<Integer, UUID> listLoadout(UUID owner) throws SQLException {
        String sql = "SELECT slot, soul_id FROM soul_loadouts WHERE owner_uuid = ? ORDER BY slot ASC";
        java.util.LinkedHashMap<Integer, UUID> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot");
                    String sid = rs.getString("soul_id");
                    if (sid == null) continue;
                    try { out.put(slot, UUID.fromString(sid)); } catch (Exception ignored) {}
                }
            }
        }
        return out;
    }

    public synchronized void updateSoulFavorite(UUID soulId, boolean favorite) throws SQLException {
        String sql = "UPDATE souls SET favorite = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setInt(1, favorite ? 1 : 0);
            ps.setString(2, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized void deleteSoul(UUID soulId) throws SQLException {
        // remove from loadouts and team slots first
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM soul_loadouts WHERE soul_id = ?")) {
            ps.setString(1, soulId.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn().prepareStatement("UPDATE soul_teams SET soul_id = NULL, updated_at = ? WHERE soul_id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, soulId.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM souls WHERE soul_id = ?")) {
            ps.setString(1, soulId.toString());
            ps.executeUpdate();
        }
    }


    public synchronized int incrementSoulsAcquiredTotal(UUID owner, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET souls_acquired_total = souls_acquired_total + 1, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, owner.toString());
            ps.executeUpdate();
        }
        return getOrCreatePlayerState(owner, defaultCapacity).soulsAcquiredTotal();
    }

    public synchronized int incrementGroupActivationsTotal(UUID owner, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET group_activations_total = group_activations_total + 1, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, owner.toString());
            ps.executeUpdate();
        }
        return getOrCreatePlayerState(owner, defaultCapacity).groupActivationsTotal();
    }

    public synchronized int incrementSoulTableActionsTotal(UUID owner, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET soul_table_actions_total = soul_table_actions_total + 1, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, owner.toString());
            ps.executeUpdate();
        }
        return getOrCreatePlayerState(owner, defaultCapacity).soulTableActionsTotal();
    }

    public synchronized int incrementSoulRespawnsTotal(UUID owner, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String upd = "UPDATE soul_player_state SET soul_respawns_total = soul_respawns_total + 1, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, owner.toString());
            ps.executeUpdate();
        }
        return getOrCreatePlayerState(owner, defaultCapacity).soulRespawnsTotal();
    }

    /** Increments overall soul table action total and the specific action total. Returns the player state after update. */
    public synchronized PlayerSoulState incrementSoulTableActionTotals(UUID owner, int defaultCapacity, String actionColumnSuffix) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        String actionCol = switch (actionColumnSuffix == null ? "" : actionColumnSuffix.toLowerCase(Locale.ROOT)) {
            case "combine" -> "soul_table_combine_total";
            case "enhance" -> "soul_table_enhance_total";
            case "reforge" -> "soul_table_reforge_total";
            case "salvage" -> "soul_table_salvage_total";
            default -> null;
        };
        long now = System.currentTimeMillis();
        if (actionCol == null) {
            String upd = "UPDATE soul_player_state SET soul_table_actions_total = soul_table_actions_total + 1, updated_at = ? WHERE owner_uuid = ?";
            try (PreparedStatement ps = conn().prepareStatement(upd)) {
                ps.setLong(1, now);
                ps.setString(2, owner.toString());
                ps.executeUpdate();
            }
        } else {
            String upd = "UPDATE soul_player_state SET soul_table_actions_total = soul_table_actions_total + 1, "
                    + actionCol + " = " + actionCol + " + 1, updated_at = ? WHERE owner_uuid = ?";
            try (PreparedStatement ps = conn().prepareStatement(upd)) {
                ps.setLong(1, now);
                ps.setString(2, owner.toString());
                ps.executeUpdate();
            }
        }
        return getOrCreatePlayerState(owner, defaultCapacity);
    }

    // ---------- Team / loadout presets (soul team overhaul) ----------

    public synchronized int getSelectedTeam(UUID owner, int defaultCapacity) throws SQLException {
        PlayerSoulState st = getOrCreatePlayerState(owner, defaultCapacity);
        int t = st.selectedTeam();
        return (t >= 1 && t <= 4) ? t : 1;
    }

    public synchronized void setSelectedTeam(UUID owner, int selectedTeam, int defaultCapacity) throws SQLException {
        getOrCreatePlayerState(owner, defaultCapacity);
        int t = Math.max(1, Math.min(4, selectedTeam));
        String upd = "UPDATE soul_player_state SET selected_team = ?, updated_at = ? WHERE owner_uuid = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            ps.setInt(1, t);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, owner.toString());
            ps.executeUpdate();
        }
    }

    public synchronized boolean hasAnyTeamData(UUID owner) throws SQLException {
        String sql = "SELECT 1 FROM soul_teams WHERE owner_uuid = ? LIMIT 1";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public synchronized UUID getTeamSlotSoul(UUID owner, int teamIndex, int slotIndex) throws SQLException {
        String sql = "SELECT soul_id FROM soul_teams WHERE owner_uuid = ? AND team_index = ? AND slot_index = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, teamIndex);
            ps.setInt(3, slotIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String s = rs.getString("soul_id");
                if (s == null || s.isBlank()) return null;
                try { return UUID.fromString(s); } catch (Exception ignored) { return null; }
            }
        }
    }

    public synchronized java.util.Map<Integer, UUID> getTeamSlots(UUID owner, int teamIndex) throws SQLException {
        String sql = "SELECT slot_index, soul_id FROM soul_teams WHERE owner_uuid = ? AND team_index = ? ORDER BY slot_index ASC";
        java.util.LinkedHashMap<Integer, UUID> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, teamIndex);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot_index");
                    String sid = rs.getString("soul_id");
                    if (sid == null || sid.isBlank()) continue;
                    try { out.put(slot, UUID.fromString(sid)); } catch (Exception ignored) {}
                }
            }
        }
        return out;
    }

    public synchronized java.util.Map<Integer, java.util.Map<Integer, UUID>> listTeams(UUID owner) throws SQLException {
        String sql = "SELECT team_index, slot_index, soul_id FROM soul_teams WHERE owner_uuid = ? ORDER BY team_index ASC, slot_index ASC";
        java.util.LinkedHashMap<Integer, java.util.Map<Integer, UUID>> teams = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int team = rs.getInt("team_index");
                    int slot = rs.getInt("slot_index");
                    String sid = rs.getString("soul_id");
                    java.util.Map<Integer, UUID> slots = teams.computeIfAbsent(team, k -> new java.util.LinkedHashMap<>());
                    if (sid == null || sid.isBlank()) continue;
                    try { slots.put(slot, UUID.fromString(sid)); } catch (Exception ignored) {}
                }
            }
        }
        return teams;
    }

    public synchronized void setTeamSlot(UUID owner, int teamIndex, int slotIndex, UUID soulId) throws SQLException {
        String sql = "INSERT INTO soul_teams (owner_uuid, team_index, slot_index, soul_id, updated_at) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(owner_uuid, team_index, slot_index) DO UPDATE SET soul_id=excluded.soul_id, updated_at=excluded.updated_at";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, teamIndex);
            ps.setInt(3, slotIndex);
            if (soulId == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, soulId.toString());
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public synchronized void clearTeamSlot(UUID owner, int teamIndex, int slotIndex) throws SQLException {
        setTeamSlot(owner, teamIndex, slotIndex, null);
    }

    public synchronized void clearTeam(UUID owner, int teamIndex) throws SQLException {
        for (int slot = 0; slot < 4; slot++) {
            setTeamSlot(owner, teamIndex, slot, null);
        }
    }

    public synchronized void clearSoulFromAllTeams(UUID owner, UUID soulId) throws SQLException {
        if (owner == null || soulId == null) return;
        String sql = "UPDATE soul_teams SET soul_id = NULL, updated_at = ? WHERE owner_uuid = ? AND soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, owner.toString());
            ps.setString(3, soulId.toString());
            ps.executeUpdate();
        }
    }

    public synchronized boolean isSoulAssignedToTeam(UUID owner, int teamIndex, UUID soulId, Integer excludingSlot) throws SQLException {
        if (owner == null || soulId == null) return false;
        String sql = "SELECT slot_index FROM soul_teams WHERE owner_uuid = ? AND team_index = ? AND soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, teamIndex);
            ps.setString(3, soulId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot_index");
                    if (excludingSlot != null && slot == excludingSlot) continue;
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized void ensureDefaultTeams(UUID owner) throws SQLException {
        if (owner == null) return;
        for (int team = 1; team <= 4; team++) {
            for (int slot = 0; slot < 4; slot++) {
                String sql = "INSERT OR IGNORE INTO soul_teams (owner_uuid, team_index, slot_index, soul_id, updated_at) VALUES (?,?,?,?,?)";
                try (PreparedStatement ps = conn().prepareStatement(sql)) {
                    ps.setString(1, owner.toString());
                    ps.setInt(2, team);
                    ps.setInt(3, slot);
                    ps.setNull(4, Types.VARCHAR);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Idempotent migration: seed Team 1 from legacy loadouts/selected slot when no team data exists. */
    public synchronized void seedDefaultTeamsFromLegacy(UUID owner, int defaultCapacity) throws SQLException {
        if (owner == null) return;
        getOrCreatePlayerState(owner, defaultCapacity);
        if (hasAnyTeamData(owner)) return;
        ensureDefaultTeams(owner);

        PlayerSoulState st = getOrCreatePlayerState(owner, defaultCapacity);
        int selectedSlot = Math.max(0, st.selectedSlot());
        java.util.LinkedHashSet<UUID> ordered = new java.util.LinkedHashSet<>();
        UUID selected = getLoadoutSoulId(owner, selectedSlot);
        if (selected != null) ordered.add(selected);
        for (java.util.Map.Entry<Integer, UUID> e : listLoadout(owner).entrySet()) {
            if (e.getValue() != null) ordered.add(e.getValue());
        }

        int slot = 0;
        for (UUID soulId : ordered) {
            if (slot >= 4) break;
            if (soulId == null) continue;
            SoulInstance s = getSoul(soulId);
            if (s == null || !owner.equals(s.getOwnerId())) continue;
            setTeamSlot(owner, 1, slot++, soulId);
        }
        setSelectedTeam(owner, 1, defaultCapacity);
    }

    /**
     * Backfill max_health for existing souls that were captured before this field existed
     * (max_health = 0). Uses each mob type's default max health as the value.
     */
    public synchronized void backfillMissingMaxHealth() throws SQLException {
        String sel = "SELECT DISTINCT entity_type FROM souls WHERE max_health IS NULL OR max_health = 0";
        List<String> types = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sel);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String t = rs.getString("entity_type");
                if (t != null && !t.isBlank()) types.add(t);
            }
        }

        for (String typeName : types) {
            double defaultMax = resolveDefaultMaxHealth(typeName);
            if (defaultMax > 0.0) {
                String upd = "UPDATE souls SET max_health = ? WHERE (max_health IS NULL OR max_health = 0) AND entity_type = ?";
                try (PreparedStatement ps = conn().prepareStatement(upd)) {
                    ps.setDouble(1, defaultMax);
                    ps.setString(2, typeName);
                    ps.executeUpdate();
                }
                GlovedPlugin.logDebug("[Souls] Backfilled max_health=" + defaultMax + " for entity_type=" + typeName);
            }
        }
    }

    /**
     * Re-rolls the respawnSeconds base stat for all existing souls using current rarity bands/config.
     * Keeps all other stored base stats/fields unchanged.
     */
    public synchronized int rerollRespawnStatsForAll(SoulTableConfig.SoulStatConfig statCfg) throws SQLException {
        if (statCfg == null) statCfg = new SoulTableConfig.SoulStatConfig();
        String sel = "SELECT soul_id, rarity, base_stats_json, entity_type FROM souls";
        String upd = "UPDATE souls SET base_stats_json = ? WHERE soul_id = ?";
        int updated = 0;
        java.util.Random rng = java.util.concurrent.ThreadLocalRandom.current();
        try (PreparedStatement psSel = conn().prepareStatement(sel);
             ResultSet rs = psSel.executeQuery();
             PreparedStatement psUpd = conn().prepareStatement(upd)) {
            while (rs.next()) {
                String soulId = rs.getString("soul_id");
                String rarityRaw = rs.getString("rarity");
                String json = rs.getString("base_stats_json");
                String entityType = rs.getString("entity_type");
                SoulRarity rarity = SoulRarity.fromString(rarityRaw, SoulRarity.COMMON);
                SoulBaseStats base = SoulBaseStats.fromJson(json);
                if (base == null) {
                    org.bukkit.entity.EntityType type = null;
                    try { if (entityType != null) type = org.bukkit.entity.EntityType.valueOf(entityType); } catch (Throwable ignored) {}
                    base = SoulBaseStats.defaults(type);
                }
                // If parsed with legacy sentinel or invalid respawn, normalize first to keep other stats sane
                if (base.respawnSeconds() <= 0.0) {
                    base = new SoulBaseStats(base.health(), base.attackDamage(), base.movementSpeed(), base.attackSpeed(), base.defense(),
                            SoulBaseStats.defaultRespawnSeconds(rarity, statCfg));
                }
                double newRespawn = SoulBaseStats.defaultRespawnSeconds(rarity, statCfg);
                // sample a rolled value in the band for variety (not just midpoint)
                try {
                    SoulBaseStats rolled = SoulBaseStats.roll(rarity, statCfg, rng);
                    newRespawn = rolled.respawnSeconds();
                } catch (Throwable ignored) {}
                SoulBaseStats next = new SoulBaseStats(
                        base.health(), base.attackDamage(), base.movementSpeed(), base.attackSpeed(), base.defense(), newRespawn
                ).normalized().clampFinal(rarity, statCfg);
                String nextJson = next.toJson();
                if (nextJson != null && !nextJson.equals(json)) {
                    psUpd.setString(1, nextJson);
                    psUpd.setString(2, soulId);
                    psUpd.addBatch();
                    updated++;
                }
            }
            if (updated > 0) psUpd.executeBatch();
        }
        return updated;
    }

    /**
     * Backfills {@code base_stats_json} for souls captured before the stat system existed.
     *
     * <p>Idempotent: only rows with a null/blank payload are touched. Stored {@code max_health} is
     * the health source (elite mobs already inflate it), so health gets variance only — no rarity
     * multiplier — while every other stat uses the entity type default with rarity + variance.
     * All values are capped.</p>
     *
     * @return number of backfilled rows
     */
    public synchronized int backfillLegacyBaseStats(SoulTableConfig.SoulStatConfig statCfg)
            throws SQLException {
        String sel = "SELECT soul_id, entity_type, rarity, max_health, base_stats_json FROM souls " +
                "WHERE base_stats_json IS NULL OR base_stats_json = '' OR base_stats_json NOT LIKE '%\"respawnSeconds\"%'";
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sel);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{
                        rs.getString("soul_id"),
                        rs.getString("entity_type"),
                        rs.getString("rarity"),
                        String.valueOf(rs.getDouble("max_health")),
                        rs.getString("base_stats_json")
                });
            }
        }
        if (rows.isEmpty()) return 0;

        int updated = 0;
        String upd = "UPDATE souls SET base_stats_json = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            for (String[] row : rows) {
                UUID soulId;
                try { soulId = UUID.fromString(row[0]); } catch (Exception ignored) { continue; }
                SoulRarity rarity = SoulRarity.fromString(row[2], SoulRarity.COMMON);
                EntityType type = null;
                try { type = EntityType.valueOf(row[1]); } catch (Exception ignored) {}
                String existingJson = row.length > 4 ? row[4] : null;
                SoulBaseStats parsed = SoulBaseStats.fromJson(existingJson);
                SoulBaseStats result;
                if (parsed == null) {
                    // Legacy row before stat system: roll full stats in rarity bands (deterministic per soul id).
                    result = SoulBaseStats.roll(rarity, statCfg, new Random(soulId.hashCode() * 31L));
                } else if (parsed.respawnSeconds() <= 0.0) {
                    // Existing stats payload missing respawn: preserve existing stats and backfill respawn deterministically.
                    double respawn = SoulBaseStats.defaultRespawnSeconds(rarity, statCfg);
                    result = new SoulBaseStats(parsed.health(), parsed.attackDamage(), parsed.movementSpeed(),
                            parsed.attackSpeed(), parsed.defense(), respawn);
                    // If legacy respawn key present under old name, fromJson would have read it; only missing triggers this.
                    result = result.capped(statCfg);
                } else {
                    continue;
                }
                String newJson = result.toJson();
                if (existingJson != null && existingJson.equals(newJson)) continue;
                ps.setString(1, newJson);
                ps.setString(2, soulId.toString());
                updated += ps.executeUpdate();
            }
        }
        if (updated > 0) {
            GlovedPlugin.logDebug("[Souls] Backfilled/migrated base_stats_json (respawn) for " + updated + " legacy " + (updated == 1 ? "soul" : "souls"));
        }
        return updated;
    }

    /**
     * Backfills {@code group_capacity} for souls created before soul-capacity existed.
     * Best-effort: recomputes a deterministic rarity/type/category-based capacity for rows still at
     * the legacy default (1) where a higher capacity is appropriate. Leaves valid low-capacity souls
     * (e.g. COMMON/boss-overridden types resolved later by ceiling) reasonable.
     */
    public synchronized int backfillLegacyGroupCapacity() throws SQLException {
        String sel = "SELECT soul_id, entity_type, family_key, category, rarity, group_capacity FROM souls";
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sel);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{
                        rs.getString("soul_id"),
                        rs.getString("entity_type"),
                        rs.getString("family_key"),
                        rs.getString("category"),
                        rs.getString("rarity"),
                        String.valueOf(rs.getInt("group_capacity"))
                });
            }
        }
        if (rows.isEmpty()) return 0;

        int updated = 0;
        String upd = "UPDATE souls SET group_capacity = ? WHERE soul_id = ? AND group_capacity <> ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            for (String[] row : rows) {
                UUID soulId;
                try { soulId = UUID.fromString(row[0]); } catch (Exception ignored) { continue; }
                SoulRarity rarity = SoulRarity.fromString(row[4], SoulRarity.COMMON);
                EntityCategory cat = EntityCategory.SPECIAL;
                try { cat = EntityCategory.valueOf(row[3]); } catch (Exception ignored) {}
                String typeKey = row[1] != null ? row[1] : row[2];
                int current;
                try { current = Integer.parseInt(row[5]); } catch (Exception ignored) { current = 1; }
                int cap = computeLegacyBackfillCapacity(soulId, rarity, cat, typeKey);

                // Normalize invalid/high values and enforce fixed-max-count overrides on existing rows.
                boolean fixedMax = false;
                try {
                    var sc = plugin.getSummonConfig();
                    SoulConfig soulCfg = sc != null ? sc.getSoulConfig() : null;
                    if (soulCfg != null && typeKey != null && !typeKey.isBlank() && soulCfg.getTypeMaxActiveOverride(typeKey) != null) {
                        fixedMax = true;
                    }
                } catch (Throwable ignored) {}
                if (!fixedMax) {
                    // Only raise legacy default rows for normal types; don't rewrite valid custom values upward/downward here.
                    if (current > 1) continue;
                    if (cap <= 1) continue;
                } else {
                    cap = Math.max(1, cap);
                    if (cap == current) continue;
                }

                ps.setInt(1, cap);
                ps.setString(2, soulId.toString());
                ps.setInt(3, current);
                updated += ps.executeUpdate();
            }
        }
        if (updated > 0) {
            GlovedPlugin.logDebug("[Souls] Backfilled group_capacity for " + updated + " legacy " + (updated == 1 ? "soul" : "souls"));
        }
        return updated;
    }

    private int computeLegacyBackfillCapacity(UUID soulId, SoulRarity rarity, EntityCategory category, String typeKey) {
        SoulRarity r = rarity != null ? rarity : SoulRarity.COMMON;
        EntityCategory cat = category != null ? category : EntityCategory.SPECIAL;

        // Respect hard per-type overrides for legacy souls with fixed max Count.
        try {
            var sc = plugin.getSummonConfig();
            SoulConfig soulCfg = sc != null ? sc.getSoulConfig() : null;
            if (soulCfg != null && typeKey != null && !typeKey.isBlank()) {
                Integer hard = soulCfg.getTypeMaxActiveOverride(typeKey);
                if (hard != null) return Math.max(1, hard);
            }
        } catch (Throwable ignored) {}

        int min; int max;
        switch (r) {
            case COMMON -> { min = 1; max = 1; }
            case UNCOMMON -> { min = 1; max = 2; }
            case RARE -> { min = 1; max = 2; }
            case EPIC -> { min = 2; max = 3; }
            case LEGENDARY -> { min = 2; max = 3; }
            case MYTHIC -> { min = 2; max = 4; }
            default -> { min = 1; max = 1; }
        }
        // deterministic pick
        int pick = min;
        if (max > min) {
            int span = max - min + 1;
            int h = Math.abs(soulId != null ? soulId.hashCode() : 0);
            pick = min + (h % span);
        }
        // category tweak (subtle)
        if (cat == EntityCategory.BOSS || cat == EntityCategory.SPECIAL) pick = Math.max(1, Math.min(pick, 2));
        return Math.max(1, Math.min(8, pick));
    }

    /**
     * Normalizes {@code base_stats_json}: migrates missing/legacy DEF (armor) into the soul DEF stat,
     * and strips truly retired fields (armor toughness, knockback resistance, attack knockback).
     * Idempotent: only rows that need normalization/migration are touched.
     */
    public synchronized int stripRemovedBaseStats() throws SQLException {
        String sel = "SELECT soul_id, rarity, base_stats_json FROM souls WHERE " +
                "base_stats_json IS NULL OR base_stats_json = '' OR " +
                "base_stats_json NOT LIKE '%\"defense\"%' OR " +
                "base_stats_json NOT LIKE '%\"respawnSeconds\"%' OR " +
                "base_stats_json LIKE '%\"armorToughness\"%' OR " +
                "base_stats_json LIKE '%\"knockbackResistance\"%' OR " +
                "base_stats_json LIKE '%\"attackKnockback\"%'";
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sel);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString("soul_id"), rs.getString("rarity"), rs.getString("base_stats_json")});
            }
        }
        if (rows.isEmpty()) return 0;

        SoulTableConfig.SoulStatConfig statCfg = plugin.getSummonConfig() != null && plugin.getSummonConfig().getSoulTableConfig() != null
                ? plugin.getSummonConfig().getSoulTableConfig().getStatConfig() : new SoulTableConfig.SoulStatConfig();
        int updated = 0;
        String upd = "UPDATE souls SET base_stats_json = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            for (String[] row : rows) {
                String oldJson = row[2];
                SoulBaseStats stats = SoulBaseStats.fromJson(oldJson);
                if (stats == null) continue;
                if (stats.respawnSeconds() <= 0.0) {
                    SoulRarity rarity = SoulRarity.fromString(row[1], SoulRarity.COMMON);
                    double respawn = SoulBaseStats.defaultRespawnSeconds(rarity, statCfg);
                    stats = new SoulBaseStats(stats.health(), stats.attackDamage(), stats.movementSpeed(),
                            stats.attackSpeed(), stats.defense(), respawn);
                }
                String newJson = stats.toJson();
                if (oldJson != null && oldJson.equals(newJson)) continue;
                ps.setString(1, newJson);
                ps.setString(2, row[0]);
                updated += ps.executeUpdate();
            }
        }
        if (updated > 0) {
            GlovedPlugin.logDebug("[Souls] Normalized/migrated base_stats_json for " + updated + " " + (updated == 1 ? "soul" : "souls"));
        }
        return updated;
    }

    /** Idempotent migration: legacy COMMANDER affixes -> REAWAKENING in stored affixes_json. */
    public synchronized int migrateLegacyCommanderAffixes() throws SQLException {
        String sel = "SELECT soul_id, affixes_json, quality, rarity FROM souls WHERE affixes_json LIKE '%COMMANDER%'";
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sel);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString("soul_id"), rs.getString("affixes_json"), rs.getString("quality"), rs.getString("rarity")});
            }
        }
        if (rows.isEmpty()) return 0;
        int updated = 0;
        String upd = "UPDATE souls SET affixes_json = ?, quality = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            for (String[] row : rows) {
                String oldJson = row[1];
                List<SoulAffixRoll> affixes = deserializeAffixes(oldJson); // parse maps COMMANDER -> REAWAKENING
                String newJson = serializeAffixes(affixes);
                if (oldJson != null && oldJson.equals(newJson)) continue;
                SoulRarity rarity = SoulRarity.fromString(row[3], SoulRarity.COMMON);
                double quality;
                try { quality = Double.parseDouble(row[2]); } catch (Exception e) { quality = SoulQuality.percent(rarity, affixes); }
                // keep stored quality stable but recompute if parsing changed meaningfully; recompute for consistency
                quality = SoulQuality.percent(rarity, affixes);
                ps.setString(1, newJson);
                ps.setDouble(2, quality);
                ps.setString(3, row[0]);
                updated += ps.executeUpdate();
            }
        }
        if (updated > 0) {
            GlovedPlugin.logDebug("[Souls] Migrated legacy COMMANDER affixes to REAWAKENING for " + updated + " " + (updated == 1 ? "soul" : "souls"));
        }
        return updated;
    }

    public double resolveDefaultMaxHealth(String typeName) {        EntityType type;
        try {
            type = EntityType.valueOf(typeName);
        } catch (Exception ignored) {
            return 0.0;
        }
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            if (world == null) continue;
            try {
                org.bukkit.entity.Entity e = world.spawnEntity(world.getSpawnLocation().add(0, 64, 0), type);
                if (e instanceof LivingEntity le) {
                    double max = le.getAttribute(Attribute.MAX_HEALTH) != null
                            ? le.getAttribute(Attribute.MAX_HEALTH).getBaseValue() : 0.0;
                    e.remove();
                    if (max > 0.0) return max;
                }
                e.remove();
            } catch (Throwable ignored) {}
        }
        return 0.0;
    }

    public synchronized SoulInstance findSoulByPrefix(UUID owner, String prefix) throws SQLException {
        if (prefix == null || prefix.isBlank()) return null;
        String p = prefix.toLowerCase(Locale.ROOT);
        List<SoulInstance> all = listSouls(owner, 200, 0);
        SoulInstance match = null;
        for (SoulInstance s : all) {
            if (s.getSoulId().toString().toLowerCase(Locale.ROOT).startsWith(p)) {
                if (match != null) return null; // ambiguous
                match = s;
            }
        }
        return match;
    }

    public synchronized List<SoulInstance> listSoulsFiltered(UUID owner, String familyOrType, SoulRarity rarity, int limit, int offset) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM souls WHERE owner_uuid = ?");
        List<Object> params = new ArrayList<>();
        params.add(owner.toString());
        if (familyOrType != null && !familyOrType.isBlank()) {
            sql.append(" AND (family_key = ? OR entity_type = ?)");
            String v = familyOrType.trim().toUpperCase(Locale.ROOT);
            params.add(v); params.add(v);
        }
        if (rarity != null) {
            sql.append(" AND rarity = ?");
            params.add(rarity.name());
        }
        sql.append(" ORDER BY favorite DESC, quality DESC, created_at DESC LIMIT ? OFFSET ?");
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));
        List<SoulInstance> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object o = params.get(i);
                int idx = i + 1;
                if (o instanceof String s) ps.setString(idx, s);
                else if (o instanceof Integer n) ps.setInt(idx, n);
                else ps.setObject(idx, o);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapSoul(rs));
            }
        }
        return out;
    }

    private SoulInstance mapSoul(ResultSet rs) throws SQLException {
        UUID soulId = UUID.fromString(rs.getString("soul_id"));
        UUID owner = UUID.fromString(rs.getString("owner_uuid"));
        String etName = rs.getString("entity_type");
        EntityType et = null;
        try { et = EntityType.valueOf(etName); } catch (Exception ignored) {}
        String family = rs.getString("family_key");
        EntityCategory cat = EntityCategory.valueOf(rs.getString("category"));
        SoulRarity rarity = SoulRarity.valueOf(rs.getString("rarity"));
        double quality = rs.getDouble("quality");
        List<SoulAffixRoll> affixes = deserializeAffixes(rs.getString("affixes_json"));
        List<SoulTrait> traits = deserializeTraits(rs.getString("trait"));
        long createdAt = rs.getLong("created_at");
        double maxHealth = rs.getDouble("max_health");
        boolean favorite = rs.getInt("favorite") != 0;
        SoulBaseStats baseStats = SoulBaseStats.fromJson(rs.getString("base_stats_json"));
        if (baseStats == null) {
            // Legacy row read before the backfill pass ran: use defaults and a deterministic rarity respawn fallback.
            baseStats = SoulBaseStats.defaults(et);
        }
        if (baseStats != null && baseStats.respawnSeconds() <= 0.0) {
            SoulBaseStats fallback = SoulBaseStats.defaults(et);
            double respawn = SoulBaseStats.defaultRespawnSeconds(rarity, plugin.getSummonConfig() != null && plugin.getSummonConfig().getSoulTableConfig() != null
                    ? plugin.getSummonConfig().getSoulTableConfig().getStatConfig() : new SoulTableConfig.SoulStatConfig());
            baseStats = new SoulBaseStats(baseStats.health(), baseStats.attackDamage(), baseStats.movementSpeed(),
                    baseStats.attackSpeed(), baseStats.defense(), respawn > 0 ? respawn : fallback.respawnSeconds());
        }
        int enhanceLevel = 0;
        try { enhanceLevel = Math.max(0, rs.getInt("enhance_level")); } catch (SQLException ignored) {}
        int groupCapacity = 1;
        try { groupCapacity = Math.max(1, rs.getInt("group_capacity")); } catch (SQLException ignored) {}
        if (groupCapacity <= 0) groupCapacity = 1;
        int masteryXp = 0;
        int masteryLevel = 0;
        java.util.Set<Integer> masteryClaimed = java.util.Set.of();
        try { masteryXp = Math.max(0, rs.getInt("mastery_xp")); } catch (SQLException ignored) {}
        try { masteryLevel = Math.max(0, rs.getInt("mastery_level")); } catch (SQLException ignored) {}
        try { masteryClaimed = deserializeMasteryClaimed(rs.getString("mastery_claimed")); } catch (SQLException ignored) {}
        return new SoulInstance(soulId, owner, et, family, cat, rarity, quality, affixes, traits, createdAt,
                maxHealth, favorite, baseStats, enhanceLevel, groupCapacity, masteryXp, masteryLevel, masteryClaimed);
    }

    private static String serializeTraits(List<SoulTrait> traits) {
        if (traits == null || traits.isEmpty()) return "[]";
        List<String> names = new ArrayList<>();
        for (SoulTrait t : traits) {
            if (t == null) continue;
            String id = t.id();
            if (id != null && !id.isBlank()) names.add(id);
        }
        return GSON.toJson(names);
    }

    private static List<SoulTrait> deserializeTraits(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        List<String> names = new ArrayList<>();
        boolean parsedArray = false;
        // Migration/current format: JSON array of trait ID strings.
        try {
            List<String> arr = GSON.fromJson(json, new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
            if (arr != null) {
                parsedArray = true;
                for (String n : arr) {
                    if (n == null || n.isBlank()) continue;
                    names.add(n);
                }
            }
        } catch (Exception ignored) {}
        // Legacy fallback: treat whole string as a single trait name/ID.
        if (!parsedArray && names.isEmpty()) {
            String single = json.trim();
            if (!single.isBlank()) names.add(single);
        }
        if (names.isEmpty()) return List.of();

        List<SoulTrait> out = new ArrayList<>(names.size());
        for (String n : names) {
            SoulTrait t = SoulTrait.fromId(n);
            if (t == null) continue;
            // Resolve via registry (logs unknown once). Keep resolved trait identity in original order.
            try { t.definitionOptional(); } catch (Throwable ignored) {}
            out.add(t);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static String serializeAffixes(List<SoulAffixRoll> affixes) {
        if (affixes == null || affixes.isEmpty()) return "[]";
        List<AffixDto> dtos = new ArrayList<>();
        for (SoulAffixRoll r : affixes) if (r != null) dtos.add(new AffixDto(r.type().name(), r.value()));
        return GSON.toJson(dtos);
    }

    private static List<SoulAffixRoll> deserializeAffixes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<AffixDto> dtos = GSON.fromJson(json, AFFIX_LIST_TYPE);
            if (dtos == null) return List.of();
            List<SoulAffixRoll> out = new ArrayList<>();
            for (AffixDto d : dtos) {
                if (d == null || d.type == null) continue;
                try {
                    SoulAffixType type = SoulAffixType.parse(d.type);
                    if (type != null) out.add(new SoulAffixRoll(type, d.value));
                } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String serializeMasteryClaimed(java.util.Set<Integer> claimed) {
        if (claimed == null || claimed.isEmpty()) return "[]";
        List<Integer> vals = new ArrayList<>();
        for (Integer v : claimed) if (v != null && v > 0) vals.add(v);
        Collections.sort(vals);
        return GSON.toJson(vals);
    }

    private static java.util.Set<Integer> deserializeMasteryClaimed(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return java.util.Set.of();
        try {
            List<Integer> vals = GSON.fromJson(json, new TypeToken<List<Integer>>() {}.getType());
            if (vals == null || vals.isEmpty()) return java.util.Set.of();
            java.util.LinkedHashSet<Integer> out = new java.util.LinkedHashSet<>();
            for (Integer v : vals) if (v != null && v > 0) out.add(v);
            return out.isEmpty() ? java.util.Set.of() : java.util.Collections.unmodifiableSet(out);
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    private static String maxRarity(String a, String b) {
        SoulRarity ra = parseRarity(a), rb = parseRarity(b);
        if (ra == null) return rb != null ? rb.name() : a;
        if (rb == null) return ra.name();
        return ra.ordinal() >= rb.ordinal() ? ra.name() : rb.name();
    }
    private static SoulRarity parseRarity(String s) {
        if (s == null) return null;
        try { return SoulRarity.valueOf(s); } catch (Exception e) { return null; }
    }

    private record AffixDto(String type, double value) {}

    public record PlayerSoulState(UUID owner, int essence, int vaultCapacity, int selectedSlot, int selectedTeam, String pityJson, String combatMode, String soulTableTab,
                                   int soulsAcquiredTotal, int groupActivationsTotal, int soulTableActionsTotal,
                                   int soulRespawnsTotal, int soulTableCombineTotal, int soulTableEnhanceTotal, int soulTableReforgeTotal, int soulTableSalvageTotal) {}
    public record CodexEntry(String familyKey, String highestRarity, int capturedCount,
                             long firstCapturedAt, int masteredCount, int refinedCount,
                             boolean discoveredClaimed, boolean collectedClaimed, boolean refinedClaimed, boolean masteredClaimed) {
        public CodexEntry(String familyKey, String highestRarity, int capturedCount) {
            this(familyKey, highestRarity, capturedCount, 0L, 0, 0, false, false, false, false);
        }
    }
}
