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

    private Connection conn() { return database.getConnection(); }

    public synchronized void insertSoul(SoulInstance soul) throws SQLException {
        String sql = "INSERT INTO souls (soul_id, owner_uuid, entity_type, family_key, category, rarity, quality, " +
                "affixes_json, trait, favorite, state, origin_context, created_at, max_health) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
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
                    return new PlayerSoulState(owner,
                            rs.getInt("essence"),
                            rs.getInt("vault_capacity"),
                            rs.getInt("selected_slot"),
                            rs.getString("pity_json"));
                }
            }
        }
        long now = System.currentTimeMillis();
        String ins = "INSERT INTO soul_player_state (owner_uuid, essence, vault_capacity, pity_json, selected_slot, doctrine, updated_at) VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(ins)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, 0);
            ps.setInt(3, Math.max(1, defaultCapacity));
            ps.setString(4, "{}");
            ps.setInt(5, 0);
            ps.setString(6, null);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
        return new PlayerSoulState(owner, 0, Math.max(1, defaultCapacity), 0, "{}");
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

    public double resolveDefaultMaxHealth(String typeName) {
        EntityType type;
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
        return new SoulInstance(soulId, owner, et, family, cat, rarity, quality, affixes, traits, createdAt, maxHealth, favorite);
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

    public record PlayerSoulState(UUID owner, int essence, int vaultCapacity, int selectedSlot, String pityJson) {}
    public record CodexEntry(String familyKey, String highestRarity, int capturedCount) {}
}
