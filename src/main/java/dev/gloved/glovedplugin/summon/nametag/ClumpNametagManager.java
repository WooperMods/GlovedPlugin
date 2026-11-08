package dev.gloved.glovedplugin.summon.nametag;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.SummonConfig;
import dev.gloved.glovedplugin.summon.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.SummonProfile;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * When enough of an owner's minions of the same type/profile cluster together, hides their individual
 * nametags and shows a single proxy {@link TextDisplay} at the clump center matching that minion's
 * nametag (e.g. "Player's Bees x5").
 */
public class ClumpNametagManager implements Listener {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private BukkitTask task;

    // ownerId -> (clumpKey -> proxy entity UUID)
    private final Map<UUID, Map<String, UUID>> proxiesByOwner = new HashMap<>();
    // minionId -> currently hidden by clump logic
    private final Set<UUID> hiddenMinions = new HashSet<>();

    public ClumpNametagManager(GlovedPlugin plugin, SummonInstanceTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    public void start() {
        stop();
        SummonConfig cfg = plugin.getSummonConfig();
        long period = cfg != null ? Math.max(1L, cfg.getClumpedNametagsUpdateTicks()) : 10L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        removeAllProxies();
        restoreAllHidden();
    }

    /** Call after config reload to apply new update-ticks/enabled state. */
    public void restart() {
        removeAllProxies();
        restoreAllHidden();
        start();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (event.getPlayer() != null) {
            removeProxiesForOwner(event.getPlayer().getUniqueId());
        }
    }

    private void tick() {
        try {
            SummonConfig cfg = plugin.getSummonConfig();
            if (cfg == null || !cfg.isEnabled() || !cfg.isClumpedNametagsEnabled()) {
                if (!proxiesByOwner.isEmpty() || !hiddenMinions.isEmpty()) {
                    removeAllProxies();
                    restoreAllHidden();
                }
                return;
            }
            int minCount = cfg.getClumpedNametagsMinCount();
            double radius = cfg.getClumpedNametagsRadius();
            String format = cfg.getClumpedNametagsFormat();
            double radiusSq = radius * radius;

            Set<UUID> owners = collectOwners();
            // Clean up owners with no minions
            for (UUID ownerId : new ArrayList<>(proxiesByOwner.keySet())) {
                if (!owners.contains(ownerId)) {
                    removeProxiesForOwner(ownerId);
                }
            }

            Set<UUID> nowHidden = new HashSet<>();
            for (UUID ownerId : owners) {
                processOwner(ownerId, minCount, radiusSq, format, nowHidden);
            }

            // Restore any minions that are no longer in a clump
            for (UUID minionId : new ArrayList<>(hiddenMinions)) {
                if (!nowHidden.contains(minionId)) {
                    restoreNametag(minionId);
                    hiddenMinions.remove(minionId);
                }
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] ClumpNametagManager tick error: " + t);
        }
    }

    private Set<UUID> collectOwners() {
        Set<UUID> owners = new HashSet<>();
        NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_owner");
        for (LivingEntity le : tracker.getActiveMinions()) {
            if (le == null || le.isDead()) continue;
            // Tracker exposes by player in other paths; derive owner from PDC/tracker cache
            String ownerStr = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerStr != null) {
                try { owners.add(UUID.fromString(ownerStr)); } catch (Exception ignored) {}
            }
        }
        // Also include owners known to tracker via activeByPlayer? Not directly exposed; PDC is sufficient.
        return owners;
    }

    private void processOwner(UUID ownerId, int minCount, double radiusSq, String format, Set<UUID> nowHidden) {
        Player owner = Bukkit.getPlayer(ownerId);
        List<LivingEntity> minions = tracker.getActiveMinions(ownerId);
        if (minions.isEmpty()) {
            removeProxiesForOwner(ownerId);
            return;
        }
        minions.removeIf(le -> le == null || le.isDead());

        SummonConfig cfg = plugin.getSummonConfig();
        NamespacedKey profileKeyPdc = new NamespacedKey(plugin, "summon_profile");
        // Only clump minions of the same type/profile, with a nametag matching that profile.
        Map<String, List<LivingEntity>> byProfile = new HashMap<>();
        for (LivingEntity le : minions) {
            String pName = le.getPersistentDataContainer().get(profileKeyPdc, PersistentDataType.STRING);
            String groupKey = (pName != null && !pName.isBlank()) ? pName : ("type:" + le.getType().name());
            byProfile.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(le);
        }

        Map<String, UUID> ownerProxies = proxiesByOwner.computeIfAbsent(ownerId, k -> new HashMap<>());
        Set<String> activeKeys = new HashSet<>();

        String playerName = owner != null ? owner.getName() : "Owner";
        for (Map.Entry<String, List<LivingEntity>> entry : byProfile.entrySet()) {
            String profileGroup = entry.getKey();
            List<List<LivingEntity>> clumps = findClumps(entry.getValue(), radiusSq, minCount);
            for (List<LivingEntity> clump : clumps) {
                String key = profileGroup + "|" + clumpKey(clump);
                activeKeys.add(key);
                Location center = clumpCenter(clump);
                if (center == null) continue;
                String text = formatNametag(format, playerName, profileGroup, clump, owner, cfg);
                UUID proxyId = ownerProxies.get(key);
                TextDisplay display = resolveDisplay(proxyId);
                if (display == null || display.isDead()) {
                    display = spawnProxy(center, text, ownerId);
                    if (display != null) ownerProxies.put(key, display.getUniqueId());
                } else {
                    updateProxy(display, center, text);
                }
                for (LivingEntity member : clump) {
                    if (member == null || member.isDead()) continue;
                    try {
                        if (member.isCustomNameVisible()) member.setCustomNameVisible(false);
                        nowHidden.add(member.getUniqueId());
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Remove proxies for clumps that no longer exist
        for (String key : new ArrayList<>(ownerProxies.keySet())) {
            if (!activeKeys.contains(key)) {
                removeProxyEntity(ownerProxies.remove(key));
            }
        }
    }

    private List<List<LivingEntity>> findClumps(List<LivingEntity> minions, double radiusSq, int minCount) {
        int n = minions.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            LivingEntity a = minions.get(i);
            if (a == null || a.isDead()) continue;
            Location la = a.getLocation();
            for (int j = i + 1; j < n; j++) {
                LivingEntity b = minions.get(j);
                if (b == null || b.isDead()) continue;
                Location lb = b.getLocation();
                if (la.getWorld() == null || !la.getWorld().equals(lb.getWorld())) continue;
                if (la.distanceSquared(lb) <= radiusSq) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<LivingEntity>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            LivingEntity le = minions.get(i);
            if (le == null || le.isDead()) continue;
            int root = find(parent, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(le);
        }

        List<List<LivingEntity>> clumps = new ArrayList<>();
        for (List<LivingEntity> g : groups.values()) {
            if (g.size() >= minCount) clumps.add(g);
        }
        return clumps;
    }

    private static int find(int[] parent, int i) {
        if (parent[i] != i) parent[i] = find(parent, parent[i]);
        return parent[i];
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[rb] = ra;
    }

    private String clumpKey(List<LivingEntity> clump) {
        List<String> ids = new ArrayList<>();
        for (LivingEntity le : clump) ids.add(le.getUniqueId().toString());
        Collections.sort(ids);
        // Stable-ish key from member composition (bounded length)
        int hash = ids.hashCode();
        return Integer.toHexString(hash) + ":" + ids.size();
    }

    private Location clumpCenter(List<LivingEntity> clump) {
        double x = 0, y = 0, z = 0;
        World w = null;
        int c = 0;
        for (LivingEntity le : clump) {
            if (le == null || le.isDead()) continue;
            Location l = le.getLocation();
            if (w == null) w = l.getWorld();
            if (w != null && !w.equals(l.getWorld())) continue;
            x += l.getX();
            y += Math.max(y, l.getY() + le.getHeight()); // track max top; we'll average base too
            z += l.getZ();
            c++;
        }
        if (c == 0 || w == null) return null;
        // Use average X/Z and a Y above the highest member for visibility
        double ax = 0, az = 0, maxTop = Double.NEGATIVE_INFINITY;
        for (LivingEntity le : clump) {
            if (le == null || le.isDead()) continue;
            Location l = le.getLocation();
            if (!w.equals(l.getWorld())) continue;
            ax += l.getX(); az += l.getZ();
            maxTop = Math.max(maxTop, l.getY() + le.getHeight());
        }
        return new Location(w, ax / c, maxTop + 0.4, az / c);
    }

    private String formatNametag(String format, String playerName, String profileGroup,
                                  List<LivingEntity> clump, Player owner, SummonConfig cfg) {
        int count = clump.size();
        LivingEntity sample = clump.isEmpty() ? null : clump.get(0);
        String baseNametag = resolveBaseNametag(profileGroup, sample, owner, playerName, cfg);
        String s = (format == null || format.isBlank()) ? "{nametag} x{count}" : format;
        s = s.replace("{nametag}", baseNametag)
             .replace("{player}", playerName == null ? "Owner" : playerName)
             .replace("{count}", String.valueOf(count))
             .replace("{mob}", sample != null ? sample.getType().name().toLowerCase().replace("_", " ") : "minion");
        return s;
    }

    private String resolveBaseNametag(String profileGroup, LivingEntity sample, Player owner,
                                      String playerName, SummonConfig cfg) {
        if (cfg != null && profileGroup != null && !profileGroup.startsWith("type:")) {
            SummonProfile profile = cfg.getProfile(profileGroup);
            if (profile != null && sample != null) {
                if (owner != null) return profile.getNametag(owner, sample.getType());
                return profile.getNametag(playerName, sample.getType());
            }
        }
        if (sample != null && sample.getCustomName() != null) return sample.getCustomName();
        return (sample != null ? sample.getType().name().toLowerCase().replace("_", " ") : "Minions");
    }

    private TextDisplay spawnProxy(Location center, String legacyText, UUID ownerId) {
        try {
            return center.getWorld().spawn(center, TextDisplay.class, td -> {
                td.text(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', legacyText)));
                td.setBillboard(Display.Billboard.CENTER);
                try { td.setPersistent(false); } catch (Throwable ignored) {}
                try { td.setShadowed(true); } catch (Throwable ignored) {}
                NamespacedKey proxyKey = new NamespacedKey(plugin, "summon_nametag_proxy");
                td.getPersistentDataContainer().set(proxyKey, PersistentDataType.BYTE, (byte) 1);
                NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_nametag_owner");
                td.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, ownerId.toString());
            });
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] Failed to spawn clump nametag proxy: " + t);
            return null;
        }
    }

    private void updateProxy(TextDisplay display, Location center, String legacyText) {
        try {
            if (display.getWorld() == null || !display.getWorld().equals(center.getWorld())
                    || display.getLocation().distanceSquared(center) > 0.01) {
                display.teleport(center);
            }
            display.text(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', legacyText)));
        } catch (Throwable ignored) {}
    }

    private TextDisplay resolveDisplay(UUID id) {
        if (id == null) return null;
        Entity e = Bukkit.getEntity(id);
        return e instanceof TextDisplay td ? td : null;
    }

    private void restoreNametag(UUID minionId) {
        Entity e = Bukkit.getEntity(minionId);
        if (!(e instanceof LivingEntity le) || le.isDead()) return;
        try {
            SummonConfig cfg = plugin.getSummonConfig();
            NamespacedKey profileKey = new NamespacedKey(plugin, "summon_profile");
            NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_owner");
            String profileName = le.getPersistentDataContainer().get(profileKey, PersistentDataType.STRING);
            String ownerStr = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (cfg != null && profileName != null) {
                SummonProfile profile = cfg.getProfile(profileName);
                if (profile != null) {
                    Player owner = null;
                    if (ownerStr != null) {
                        try { owner = Bukkit.getPlayer(UUID.fromString(ownerStr)); } catch (Exception ignored) {}
                    }
                    String displayName;
                    if (owner != null) {
                        displayName = profile.getNametag(owner, le.getType());
                    } else {
                        // Fallback when owner is offline
                        displayName = profile.getNametag("Owner", le.getType());
                    }
                    le.setCustomName(displayName);
                }
            }
            le.setCustomNameVisible(SummonProfile.NAMETAG_VISIBLE);
        } catch (Throwable ignored) {
            try { le.setCustomNameVisible(true); } catch (Throwable ignored2) {}
        }
    }

    private void restoreAllHidden() {
        for (UUID id : new ArrayList<>(hiddenMinions)) {
            restoreNametag(id);
        }
        hiddenMinions.clear();
    }

    private void removeProxyEntity(UUID id) {
        if (id == null) return;
        Entity e = Bukkit.getEntity(id);
        if (e != null && !e.isDead()) {
            try { e.remove(); } catch (Throwable ignored) {}
        }
    }

    public void removeProxiesForOwner(UUID ownerId) {
        if (ownerId == null) return;
        Map<String, UUID> map = proxiesByOwner.remove(ownerId);
        if (map != null) {
            for (UUID id : map.values()) removeProxyEntity(id);
        }
        // Also restore nametags for that owner's minions (they may no longer be in clumps / owner quit)
        NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_owner");
        String ownerStr = ownerId.toString();
        for (UUID minionId : new ArrayList<>(hiddenMinions)) {
            Entity e = Bukkit.getEntity(minionId);
            if (e instanceof LivingEntity le && !le.isDead()) {
                String os = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                if (ownerStr.equals(os)) {
                    restoreNametag(minionId);
                    hiddenMinions.remove(minionId);
                }
            } else {
                hiddenMinions.remove(minionId);
            }
        }
    }

    public void removeAllProxies() {
        for (UUID ownerId : new ArrayList<>(proxiesByOwner.keySet())) {
            Map<String, UUID> map = proxiesByOwner.remove(ownerId);
            if (map != null) for (UUID id : map.values()) removeProxyEntity(id);
        }
        proxiesByOwner.clear();
    }

    /** Removes orphaned nametag proxy display entities (no valid owner context). */
    public void cleanupOrphanProxies() {
        NamespacedKey proxyKey = new NamespacedKey(plugin, "summon_nametag_proxy");
        NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_nametag_owner");
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (!(e instanceof TextDisplay)) continue;
                var pdc = e.getPersistentDataContainer();
                if (!pdc.has(proxyKey, PersistentDataType.BYTE)) continue;
                String ownerStr = pdc.get(ownerKey, PersistentDataType.STRING);
                boolean orphan = true;
                if (ownerStr != null) {
                    try {
                        UUID ownerId = UUID.fromString(ownerStr);
                        if (Bukkit.getPlayer(ownerId) != null && proxiesByOwner.containsKey(ownerId)) {
                            // check if this proxy is known
                            for (UUID known : proxiesByOwner.get(ownerId).values()) {
                                if (known.equals(e.getUniqueId())) { orphan = false; break; }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (orphan) {
                    e.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            GlovedPlugin.logDebug("[Summon] Cleaned up " + removed + " orphaned clump nametag proxy(s)");
        }
    }
}
