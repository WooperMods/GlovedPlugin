package dev.gloved.glovedplugin.summon.soul.table;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.Bukkit;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates the GlovedPlugin Soul Table resource pack.
 *
 * <p>Art is produced procedurally (deterministic seed) and deployed the same way as
 * {@code RankGlyphService}: written to {@code plugins/GlovedPlugin/soultable/SoulTable.zip}, copied
 * into {@code plugins/ResourcePackManager/mixer/} when present, then {@code rspm reload}.</p>
 */
public final class SoulTablePackService {

    public record Result(boolean ok, String message) {}

    private static final String ZIP_NAME = "SoulTable.zip";
    private static final int TEXTURE_SIZE = 16;
    private static final long TEXTURE_SEED = 0x534F554C54414245L; // "SOULTABE"

    private final GlovedPlugin plugin;

    public SoulTablePackService(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public Result generateAndDeploy() {
        try {
            byte[] zip = buildPack();
            Path outDir = plugin.getDataFolder().toPath().resolve("soultable");
            Files.createDirectories(outDir);
            Path local = outDir.resolve(ZIP_NAME);
            Files.write(local, zip);

            String note = "saved " + local;
            Path pluginsDir = plugin.getDataFolder().toPath().getParent();
            Path rspmDir = pluginsDir != null ? pluginsDir.resolve("ResourcePackManager") : null;
            if (rspmDir != null && Files.isDirectory(rspmDir)) {
                Path mixer = rspmDir.resolve("mixer");
                Files.createDirectories(mixer);
                Path target = mixer.resolve(ZIP_NAME);
                Files.write(target, zip);
                note = "deployed " + target;
                if (Bukkit.getPluginManager().isPluginEnabled("ResourcePackManager")) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rspm reload");
                    note += " + rspm reload";
                }
            } else {
                String warning = "ResourcePackManager folder not found; players will see a plain "
                        + "Smithing Table (the Soul Table still works). Pack kept at " + local;
                plugin.getLogger().warning("[SoulTable] " + warning);
                note = warning;
            }
            return new Result(true, note);
        } catch (Exception e) {
            plugin.getLogger().warning("[SoulTable] Could not generate resource pack: " + e.getMessage());
            return new Result(false, "Pack generation failed: " + e.getMessage());
        }
    }

    private byte[] buildPack() throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage texture = buildTexture();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            put(zos, "pack.mcmeta", ("{\n" +
                    "  \"pack\": {\n" +
                    "    \"description\": \"GlovedPlugin Soul Table\",\n" +
                    "    \"min_format\": [97,1],\n" +
                    "    \"max_format\": 97\n" +
                    "  }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "pack.png", png(scale(texture, 64)));

            put(zos, "assets/gloved/items/soul_table.json", ("{\n" +
                    "  \"model\": {\n" +
                    "    \"type\": \"minecraft:model\",\n" +
                    "    \"model\": \"gloved:item/soul_table\"\n" +
                    "  }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "assets/gloved/models/item/soul_table.json", ("{\n" +
                    "  \"parent\": \"minecraft:block/cube_all\",\n" +
                    "  \"textures\": { \"all\": \"gloved:block/soul_table\" }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "assets/gloved/textures/block/soul_table.png", png(texture));
        }
        return baos.toByteArray();
    }

    /** 16x16 soul-sand block with an amethyst rune ring and a cyan soul glyph. */
    private static BufferedImage buildTexture() {
        BufferedImage image = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(TEXTURE_SEED);

        int baseDark = 0x3A2A22;
        int baseLight = 0x4A352A;
        int border = 0x241812;
        int ring = 0x8A5BD6;
        int ringHighlight = 0xB98CFF;
        int glyph = 0x7FE7E0;
        int glyphHighlight = 0xE8FFFD;

        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                int color;
                if (x == 0 || y == 0 || x == TEXTURE_SIZE - 1 || y == TEXTURE_SIZE - 1) {
                    color = border;
                } else {
                    color = ((x + y) & 1) == 0 ? baseDark : baseLight;
                    color = jitter(color, rng.nextInt(13) - 6);
                }

                double dx = x - 7.5;
                double dy = y - 7.5;
                double distance = Math.sqrt(dx * dx + dy * dy);

                // Inset rune ring.
                if (distance >= 4.6 && distance <= 6.0
                        && x > 0 && y > 0 && x < TEXTURE_SIZE - 1 && y < TEXTURE_SIZE - 1) {
                    color = distance >= 5.3 && ((x + y) & 1) == 0 ? ringHighlight : ring;
                }

                // Central soul glyph (diamond) with a bright core.
                double diamond = Math.abs(dx) + Math.abs(dy);
                if (diamond <= 2.2) {
                    color = diamond <= 1.0 ? glyphHighlight : glyph;
                }

                image.setRGB(x, y, 0xFF000000 | color);
            }
        }
        return image;
    }

    private static int jitter(int rgb, int delta) {
        int r = clamp(((rgb >> 16) & 0xFF) + delta);
        int g = clamp(((rgb >> 8) & 0xFF) + delta);
        int b = clamp((rgb & 0xFF) + delta);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
}
