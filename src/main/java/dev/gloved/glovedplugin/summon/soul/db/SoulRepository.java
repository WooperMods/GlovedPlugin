package dev.gloved.glovedplugin.summon.soul.db;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.*;
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
                "affixes_json, trait, favorite, state, origin_context, created_at, max_health, base_stats_json, enhance_level, group_capacity) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
        // Get existing
        String sel = "SELECT highest_rarity, captured_count FROM soul_codex WHERE owner_uuid = ? AND family_key = ?";
        String highest = rarity != null ? rarity.name() : null;
        int count = 0;
        boolean exists = false;
        try (PreparedStatement ps = conn().prepareStatement(sel)) {
            ps.setString(1, owner.toString());
            ps.setString(2, familyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    String prev = rs.getString("highest_rarity");
                    highest = maxRarity(prev, highest);
                    count = rs.getInt("captured_count");
                }
            }
        }
        if (exists) {
            String upd = "UPDATE soul_codex SET highest_rarity = ?, captured_count = ? WHERE owner_uuid = ? AND family_key = ?";
            try (PreparedStatement ps = conn().prepareStatement(upd)) {
                ps.setString(1, highest);
                ps.setInt(2, count + 1);
                ps.setString(3, owner.toString());
                ps.setString(4, familyKey);
                ps.executeUpdate();
            }
        } else {
            String ins = "INSERT INTO soul_codex (owner_uuid, family_key, highest_rarity, captured_count) VALUES (?,?,?,?)";
            try (PreparedStatement ps = conn().prepareStatement(ins)) {
                ps.setString(1, owner.toString());
                ps.setString(2, familyKey);
                ps.setString(3, highest);
                ps.setInt(4, 1);
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
                    return new PlayerSoulState(owner,
                            rs.getInt("essence"),
                            rs.getInt("vault_capacity"),
                            rs.getInt("selected_slot"),
                            rs.getString("pity_json"),
                            mode,
                            tableTab);
                }
            }
        }
        long now = System.currentTimeMillis();
        String ins = "INSERT INTO soul_player_state (owner_uuid, essence, vault_capacity, pity_json, selected_slot, doctrine, updated_at, combat_mode, soul_table_tab) VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(ins)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, 0);
            ps.setInt(3, Math.max(1, defaultCapacity));
            ps.setString(4, "{}");
            ps.setInt(5, 0);
            ps.setString(6, null);
            ps.setLong(7, now);
            ps.setString(8, "AGGRESSIVE");
            ps.setString(9, "COMBINE");
            ps.executeUpdate();
        }
        return new PlayerSoulState(owner, 0, Math.max(1, defaultCapacity), 0, "{}", "AGGRESSIVE", "COMBINE");
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
        String sql = "SELECT family_key, highest_rarity, captured_count FROM soul_codex WHERE owner_uuid = ? ORDER BY captured_count DESC, family_key ASC LIMIT ?";
        List<CodexEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new CodexEntry(rs.getString("family_key"), rs.getString("highest_rarity"), rs.getInt("captured_count")));
                }
            }
        }
        return out;
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
        // remove from loadouts first
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM soul_loadouts WHERE soul_id = ?")) {
            ps.setString(1, soulId.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM souls WHERE soul_id = ?")) {
            ps.setString(1, soulId.toString());
            ps.executeUpdate();
        }
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
     * Backfills {@code base_stats_json} for souls captured before the stat system existed.
     *
     * <p>Idempotent: only rows with a null/blank payload are touched. Stored {@code max_health} is
     * the health source (elite mobs already inflate it), so health gets variance only — no rarity
     * multiplier — while every other stat uses the entity type default with rarity + variance.
     * All values are capped.</p>
     *
     * @return number of backfilled rows
     */
    public synchronized int backfillLegacyBaseStats(dev.gloved.glovedplugin.summon.soul.table.SoulTableConfig.SoulStatConfig statCfg)
            throws SQLException {
        String sel = "SELECT soul_id, entity_type, rarity, max_health FROM souls " +
                "WHERE base_stats_json IS NULL OR base_stats_json = ''";
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sel);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{
                        rs.getString("soul_id"),
                        rs.getString("entity_type"),
                        rs.getString("rarity"),
                        String.valueOf(rs.getDouble("max_health"))
                });
            }
        }
        if (rows.isEmpty()) return 0;

        int updated = 0;
        String upd = "UPDATE souls SET base_stats_json = ? WHERE soul_id = ? AND " +
                "(base_stats_json IS NULL OR base_stats_json = '')";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            for (String[] row : rows) {
                UUID soulId;
                try { soulId = UUID.fromString(row[0]); } catch (Exception ignored) { continue; }
                SoulRarity rarity = SoulRarity.fromString(row[2], SoulRarity.COMMON);
                EntityType type = null;
                try { type = EntityType.valueOf(row[1]); } catch (Exception ignored) {}
                SoulBaseStats def = type != null ? SoulBaseStats.defaults(type) : SoulBaseStats.NEUTRAL;
                double storedHealth = 0.0;
                try { storedHealth = Double.parseDouble(row[3]); } catch (Exception ignored) {}
                double healthSource = storedHealth > 0.0 ? storedHealth : def.health();

                // Legacy backfill: generate stats in the new rarity bands (plan: effects appear on backfills).
                // Deterministic per-soul seed for idempotent-ish reruns on the same row set.
                SoulBaseStats rolled = SoulBaseStats.roll(rarity, statCfg, new Random(soulId.hashCode() * 31L));

                ps.setString(1, rolled.toJson());
                ps.setString(2, soulId.toString());
                updated += ps.executeUpdate();
            }
        }
        if (updated > 0) {
            GlovedPlugin.logDebug("[Souls] Backfilled base_stats_json for " + updated + " legacy souls");
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
            GlovedPlugin.logDebug("[Souls] Backfilled group_capacity for " + updated + " legacy soul(s)");
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
     * Rewrites {@code base_stats_json} rows that still carry stat fields retired from the soul
     * system (armor, armor toughness, knockback resistance, attack knockback). Idempotent: only
     * rows whose JSON still mentions a removed key are touched.
     */
    public synchronized int stripRemovedBaseStats() throws SQLException {
        String sel = "SELECT soul_id, base_stats_json FROM souls WHERE " +
                "base_stats_json LIKE '%\"armor\"%' OR " +
                "base_stats_json LIKE '%\"armorToughness\"%' OR " +
                "base_stats_json LIKE '%\"knockbackResistance\"%' OR " +
                "base_stats_json LIKE '%\"attackKnockback\"%'";
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sel);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString("soul_id"), rs.getString("base_stats_json")});
            }
        }
        if (rows.isEmpty()) return 0;

        int updated = 0;
        String upd = "UPDATE souls SET base_stats_json = ? WHERE soul_id = ?";
        try (PreparedStatement ps = conn().prepareStatement(upd)) {
            for (String[] row : rows) {
                SoulBaseStats stats = SoulBaseStats.fromJson(row[1]);
                if (stats == null) continue;
                ps.setString(1, stats.toJson());
                ps.setString(2, row[0]);
                updated += ps.executeUpdate();
            }
        }
        if (updated > 0) {
            GlovedPlugin.logDebug("[Souls] Stripped retired stat fields from " + updated + " soul(s)");
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
            // Legacy row read before the backfill pass ran: use the type's defaults (no variance).
            baseStats = SoulBaseStats.defaults(et);
        }
        int enhanceLevel = 0;
        try { enhanceLevel = Math.max(0, rs.getInt("enhance_level")); } catch (SQLException ignored) {}
        int groupCapacity = 1;
        try { groupCapacity = Math.max(1, rs.getInt("group_capacity")); } catch (SQLException ignored) {}
        if (groupCapacity <= 0) groupCapacity = 1;
        return new SoulInstance(soulId, owner, et, family, cat, rarity, quality, affixes, traits, createdAt,
                maxHealth, favorite, baseStats, enhanceLevel, groupCapacity);
    }

    private static String serializeTraits(List<SoulTrait> traits) {
        if (traits == null || traits.isEmpty()) return "[]";
        List<String> names = new ArrayList<>();
        for (SoulTrait t : traits) if (t != null) names.add(t.name());
        return GSON.toJson(names);
    }

    private static List<SoulTrait> deserializeTraits(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        // Migration: existing rows may store a single trait name (not JSON array).
        try {
            List<String> names = GSON.fromJson(json, new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
            if (names != null) {
                List<SoulTrait> out = new ArrayList<>();
                for (String n : names) {
                    if (n == null || n.isBlank()) continue;
                    try { out.add(SoulTrait.valueOf(n)); } catch (Exception ignored) {}
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Exception ignored) {}
        // Fallback: treat whole string as a single trait name.
        try { return List.of(SoulTrait.valueOf(json.trim())); } catch (Exception ignored) { return List.of(); }
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
                try { out.add(new SoulAffixRoll(SoulAffixType.valueOf(d.type), d.value)); } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception e) {
            return List.of();
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

    public record PlayerSoulState(UUID owner, int essence, int vaultCapacity, int selectedSlot, String pityJson, String combatMode, String soulTableTab) {}
    public record CodexEntry(String familyKey, String highestRarity, int capturedCount) {}
}
