package dev.gloved.glovedplugin.combat;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom-payload bridge to the GlovedMod companion client
 * ({@code gloved:combat}, mirrored in the mod's {@code GlovedProtocol}).
 *
 * <p>Handshake is server-initiated so modded clients stay silent on foreign
 * servers (no kick risk from unexpected payloads): the server beacons
 * {@code [HELLO][varint protocol]} on join, the client replies
 * {@code [HELLO][varint protocol]}, and only then does either side speak.
 * Unknown tags are ignored so version skew fails silently.
 */
public final class GlovedBridge implements Listener, PluginMessageListener {

    public static final String CHANNEL = "gloved:combat";
    public static final int PROTOCOL_VERSION = 1;

    private static final byte HELLO = 0x01;
    private static final byte SWING = 0x02;
    private static final byte ARC = 0x10;

    private final GlovedPlugin plugin;
    private final CombatListener combat;
    private final Set<UUID> modded = ConcurrentHashMap.newKeySet();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> beaconTasks = new ConcurrentHashMap<>();
    private static final int BEACON_TRIES = 10;

    public GlovedBridge(GlovedPlugin plugin, CombatListener combat) {
        this.plugin = plugin;
        this.combat = combat;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        GlovedPlugin.logInfo("[BetterCombat] Bridge active on " + CHANNEL);
    }

    public void shutdown() {
        try {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
            Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        } catch (Exception ignored) {}
        modded.clear();
    }

    private boolean bridgeOn() {
        try {
            return plugin.getWeaponRegistry().isEnabled() && plugin.getWeaponRegistry().isModBridge();
        } catch (Exception e) {
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!bridgeOn()) return;
        UUID id;
        try {
            id = event.getPlayer().getUniqueId();
        } catch (Exception e) {
            return;
        }
        cancelBeacon(id);
        // Paper silently drops outgoing plugin messages until the client's
        // channel registration arrives, so retry until the channel is listed
        // (or the client handshakes, or we give up on vanilla clients).
        final int[] attempts = {0};
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player player;
            try {
                player = Bukkit.getPlayer(id);
            } catch (Exception e) {
                cancelBeacon(id);
                return;
            }
            if (player == null || !player.isOnline() || !bridgeOn()) {
                cancelBeacon(id);
                return;
            }
            if (modded.contains(id)) {
                cancelBeacon(id);
                return;
            }
            boolean listed = false;
            try {
                Set<String> listening = player.getListeningPluginChannels();
                listed = listening != null && listening.contains(CHANNEL);
            } catch (Exception ignored) {}
            if (listed) {
                sendBeacon(player);
            }
            if (++attempts[0] >= BEACON_TRIES) {
                cancelBeacon(id);
            }
        }, 20L, 20L);
        beaconTasks.put(id, task);
    }

    private void cancelBeacon(UUID id) {
        org.bukkit.scheduler.BukkitTask task = beaconTasks.remove(id);
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception ignored) {}
        }
    }

    public void clear(Player player) {
        try {
            modded.remove(player.getUniqueId());
            cancelBeacon(player.getUniqueId());
        } catch (Exception ignored) {}
    }

    /** Beacon: lets the mod know the bridge exists (vanilla clients ignore it). */
    public void sendBeacon(Player player) {
        if (!bridgeOn()) return;
        try {
            player.sendPluginMessage(plugin, CHANNEL, concat(new byte[]{HELLO}, varInt(PROTOCOL_VERSION)));
        } catch (Exception ignored) {}
    }

    /** Arc timing for the modded HUD. Only sent to handshaked clients. */
    public void sendArc(Player player, long gapMillis) {
        if (!bridgeOn()) return;
        try {
            if (!modded.contains(player.getUniqueId())) return;
            int gap = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, gapMillis));
            player.sendPluginMessage(plugin, CHANNEL, concat(new byte[]{ARC}, varInt(gap)));
        } catch (Exception ignored) {}
    }

    public boolean isModded(Player player) {
        try {
            return modded.contains(player.getUniqueId());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || message == null || message.length < 1) return;
        if (!bridgeOn()) return;
        try {
            switch (message[0]) {
                case HELLO -> {
                    int[] pos = {1};
                    int version = readVarInt(message, pos);
                    if (version != PROTOCOL_VERSION) {
                        GlovedPlugin.logWarning("[BetterCombat] Mod handshake version mismatch from "
                                + player.getName() + ": " + version + ", ignoring");
                        return;
                    }
                    modded.add(player.getUniqueId());
                    GlovedPlugin.logInfo("[BetterCombat] Companion mod handshaked for " + player.getName());
                }
                case SWING -> combat.onModdedSwing(player);
                default -> { /* ignore unknown tags */ }
            }
        } catch (Exception ignored) {}
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    private static byte[] varInt(int value) {
        byte[] tmp = new byte[5];
        int i = 0;
        int v = value;
        do {
            byte b = (byte) (v & 0x7F);
            v >>>= 7;
            if (v != 0) b |= 0x80;
            tmp[i++] = b;
        } while (v != 0);
        byte[] out = new byte[i];
        System.arraycopy(tmp, 0, out, 0, i);
        return out;
    }

    private static int readVarInt(byte[] buf, int[] pos) {
        int value = 0;
        int shift = 0;
        while (pos[0] < buf.length && shift < 35) {
            byte b = buf[pos[0]++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return value;
    }
}
