package dev.gloved.glovedplugin;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * File logger for user-serviceable debugging. When enabled via
 * {@code /gloved debug}, every {@link GlovedPlugin#logInfo} and
 * {@link GlovedPlugin#logWarning} call is also appended to
 * {@code debug.log} next to the plugin data. The flag persists in
 * {@code debug.yml} so toggling never rewrites {@code config.yml}.
 *
 * <p>Files inherit the data folder's default ACL (gloves can read);
 * permissions are also set explicitly as a fallback.
 */
public final class DebugLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();

    private static volatile boolean enabled;
    private static File logFile;
    private static File flagFile;

    private DebugLog() {}

    public static void init(GlovedPlugin plugin) {
        File dir = plugin.getDataFolder();
        logFile = new File(dir, "debug.log");
        flagFile = new File(dir, "debug.yml");
        enabled = false;
        try {
            if (flagFile.isFile()) {
                enabled = YamlConfiguration.loadConfiguration(flagFile).getBoolean("debug", false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("GlovedPlugin could not read debug.yml: " + e);
        }
    }

    public static boolean isEnabled() {
        return enabled && logFile != null;
    }

    public static boolean setEnabled(GlovedPlugin plugin, boolean on) {
        init(plugin);
        enabled = on;
        try {
            YamlConfiguration cfg = flagFile.isFile()
                    ? YamlConfiguration.loadConfiguration(flagFile) : new YamlConfiguration();
            cfg.set("debug", on);
            cfg.save(flagFile);
            relaxPermissions(flagFile);
        } catch (IOException e) {
            plugin.getLogger().warning("GlovedPlugin could not write debug.yml: " + e);
            return false;
        }
        if (on) {
            synchronized (LOCK) {
                try {
                    Files.writeString(logFile.toPath(), "",
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    relaxPermissions(logFile);
                } catch (IOException e) {
                    plugin.getLogger().warning("GlovedPlugin could not reset debug.log: " + e);
                    return false;
                }
            }
            append("INFO", "Debug logging enabled");
        }
        return true;
    }

    public static void log(String level, String message) {
        File target = logFile;
        if (!enabled || target == null) return;
        append(level, message);
    }

    private static void append(String level, String message) {
        File target = logFile;
        if (target == null) return;
        String line = "[" + LocalDateTime.now().format(STAMP) + "] [" + level + "] " + message + "\n";
        synchronized (LOCK) {
            try {
                Files.writeString(target.toPath(), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                relaxPermissions(target);
            } catch (IOException ignored) {
                // Never break gameplay for a log write.
            }
        }
    }

    private static void relaxPermissions(File file) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"));
        } catch (Exception ignored) {
            // Non-POSIX filesystem or insufficient rights; the folder's
            // default ACL already covers the gloves user.
        }
    }
}
