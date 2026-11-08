package dev.gloved.glovedplugin.summon.nametag;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.SummonConfig;
import dev.gloved.glovedplugin.summon.SummonInstanceTracker;
import dev.gloved.glovedplugin.summon.SummonProfile;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
 * When enough of an owner's minions of the same type/profile cluster together, picks a leader
 * (middle-most minion) to show an aggregated nametag (profile nametag + count) and hides nametags
 * on the other clumped minions. No proxy entities are created (performance-friendly).
 */
public class ClumpNametagManager implements Listener {
    private final GlovedPlugin plugin;
    private final SummonInstanceTracker tracker;
    private BukkitTask task;

    // minionId -> currently hidden/overridden by clump logic (needs restore)
    private final Set<UUID> managedMinions = new HashSet<>();
    // minionId -> original/custom name should be restored? We restore from profile PDC.

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
        restoreAllManaged();
        cleanupOrphanProxies(); // remove any leftover proxies from older implementation
    }

    /** Call after config reload to apply new update-ticks/enabled state. */
    public void restart() {
        restoreAllManaged();
        cleanupOrphanProxies();
        start();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Minions are removed by summon cleanup; restore/forget managed ids for this owner defensively.
        if (event.getPlayer() != null) {
            restoreManagedForOwner(event.getPlayer().getUniqueId());
        }
    }

    private void tick() {
        try {
            SummonConfig cfg = plugin.getSummonConfig();
            if (cfg == null || !cfg.isEnabled() || !cfg.isClumpedNametagsEnabled()) {
                if (!managedMinions.isEmpty()) restoreAllManaged();
                cleanupOrphanProxies();
                return;
            }
            int minCount = cfg.getClumpedNametagsMinCount();
            double radius = cfg.getClumpedNametagsRadius();
            String format = cfg.getClumpedNametagsFormat();
            double radiusSq = radius * radius;

            Set<UUID> owners = collectOwners();
            Set<UUID> nowManaged = new HashSet<>();
            for (UUID ownerId : owners) {
                processOwner(ownerId, minCount, radiusSq, format, nowManaged);
            }

            // Restore any minions no longer managed by a clump
            for (UUID minionId : new ArrayList<>(managedMinions)) {
                if (!nowManaged.contains(minionId)) {
                    restoreNametag(minionId);
                    managedMinions.remove(minionId);
                }
            }
            managedMinions.addAll(nowManaged);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[Summon] ClumpNametagManager tick error: " + t);
        }
    }

    private Set<UUID> collectOwners() {
        Set<UUID> owners = new HashSet<>();
        NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_owner");
        for (LivingEntity le : tracker.getActiveMinions()) {
            if (le == null || le.isDead()) continue;
            String ownerStr = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            if (ownerStr != null) {
                try { owners.add(UUID.fromString(ownerStr)); } catch (Exception ignored) {}
            }
        }
        return owners;
    }

    private void processOwner(UUID ownerId, int minCount, double radiusSq, String format, Set<UUID> nowManaged) {
        Player owner = Bukkit.getPlayer(ownerId);
        List<LivingEntity> minions = tracker.getActiveMinions(ownerId);
        if (minions.isEmpty()) return;
        minions.removeIf(le -> le == null || le.isDead());
        if (minions.isEmpty()) return;

        SummonConfig cfg = plugin.getSummonConfig();
        NamespacedKey profileKeyPdc = new NamespacedKey(plugin, "summon_profile");
        Map<String, List<LivingEntity>> byProfile = new HashMap<>();
        for (LivingEntity le : minions) {
            String pName = le.getPersistentDataContainer().get(profileKeyPdc, PersistentDataType.STRING);
            String groupKey = (pName != null && !pName.isBlank()) ? pName : ("type:" + le.getType().name());
            byProfile.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(le);
        }

        String playerName = owner != null ? owner.getName() : "Owner";
        Set<UUID> clumpedIds = new HashSet<>();
        for (Map.Entry<String, List<LivingEntity>> entry : byProfile.entrySet()) {
            String profileGroup = entry.getKey();
            List<List<LivingEntity>> clumps = findClumps(entry.getValue(), radiusSq, minCount);
            for (List<LivingEntity> clump : clumps) {
                LivingEntity leader = pickLeader(clump);
                if (leader == null || leader.isDead()) continue;
                String leaderName = formatNametag(format, playerName, profileGroup, clump, owner, cfg);
                leaderName = ChatColor.translateAlternateColorCodes('&', leaderName);
                for (LivingEntity member : clump) {
                    if (member == null || member.isDead()) continue;
                    try {
                        if (member.getUniqueId().equals(leader.getUniqueId())) {
                            member.setCustomName(leaderName);
                            member.setCustomNameVisible(true);
                        } else {
                            member.setCustomNameVisible(false);
                        }
                        nowManaged.add(member.getUniqueId());
                        clumpedIds.add(member.getUniqueId());
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private LivingEntity pickLeader(List<LivingEntity> clump) {
        if (clump == null || clump.isEmpty()) return null;
        double cx = 0, cy = 0, cz = 0;
        World w = null;
        int c = 0;
        for (LivingEntity le : clump) {
            if (le == null || le.isDead()) continue;
            Location l = le.getLocation();
            if (w == null) w = l.getWorld();
            if (w == null || !w.equals(l.getWorld())) continue;
            cx += l.getX(); cy += l.getY(); cz += l.getZ(); c++;
        }
        if (c == 0) return clump.get(0);
        cx /= c; cy /= c; cz /= c;
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (LivingEntity le : clump) {
            if (le == null || le.isDead() || le.getWorld() == null || !le.getWorld().equals(w)) continue;
            Location l = le.getLocation();
            double dx = l.getX() - cx, dy = l.getY() - cy, dz = l.getZ() - cz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = le; }
        }
        return best != null ? best : clump.get(0);
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
        if (sample != null) {
            // try to restore from profile PDC if groupKey was type-based
            NamespacedKey profileKeyPdc = new NamespacedKey(plugin, "summon_profile");
            String pName = sample.getPersistentDataContainer().get(profileKeyPdc, PersistentDataType.STRING);
            if (cfg != null && pName != null) {
                SummonProfile profile = cfg.getProfile(pName);
                if (profile != null) {
                    return owner != null ? profile.getNametag(owner, sample.getType()) : profile.getNametag(playerName, sample.getType());
                }
            }
        }
        String mob = sample != null ? sample.getType().name().toLowerCase().replace("_", " ") : "minion";
        return (playerName == null ? "Owner" : playerName) + "'s " + mob;
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
                    String displayName = owner != null
                            ? profile.getNametag(owner, le.getType())
                            : profile.getNametag("Owner", le.getType());
                    le.setCustomName(ChatColor.translateAlternateColorCodes('&', displayName));
                }
            }
            le.setCustomNameVisible(SummonProfile.NAMETAG_VISIBLE);
        } catch (Throwable ignored) {
            try { le.setCustomNameVisible(true); } catch (Throwable ignored2) {}
        }
    }

    private void restoreAllManaged() {
        for (UUID id : new ArrayList<>(managedMinions)) {
            restoreNametag(id);
        }
        managedMinions.clear();
    }

    private void restoreManagedForOwner(UUID ownerId) {
        if (ownerId == null) return;
        NamespacedKey ownerKey = new NamespacedKey(plugin, "summon_owner");
        String ownerStr = ownerId.toString();
        for (UUID minionId : new ArrayList<>(managedMinions)) {
            Entity e = Bukkit.getEntity(minionId);
            if (e instanceof LivingEntity le && !le.isDead()) {
                String os = le.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                if (ownerStr.equals(os)) {
                    restoreNametag(minionId);
                    managedMinions.remove(minionId);
                }
            } else {
                managedMinions.remove(minionId);
            }
        }
    }

    /** Removes orphaned nametag proxy display entities left over from older proxy-based implementation. */
    public void cleanupOrphanProxies() {
        NamespacedKey proxyKey = new NamespacedKey(plugin, "summon_nametag_proxy");
        int removed = 0;
        try {
            for (World world : Bukkit.getWorlds()) {
                for (Entity e : world.getEntities()) {
                    if (!(e instanceof TextDisplay)) continue;
                    if (e.getPersistentDataContainer().has(proxyKey, PersistentDataType.BYTE)) {
                        e.remove();
                        removed++;
                    }
                }
            }
        } catch (Throwable ignored) {}
        if (removed > 0) {
            GlovedPlugin.logDebug("[Summon] Cleaned up " + removed + " legacy clump nametag proxy(s)");
        }
    }

    // Back-compat no-op for any callers; proxies are no longer used.
    public void removeAllProxies() {
        cleanupOrphanProxies();
    }

    public void removeProxiesForOwner(UUID ownerId) {
        restoreManagedForOwner(ownerId);
        cleanupOrphanProxies();
    }
}
