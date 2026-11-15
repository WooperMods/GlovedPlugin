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
        BufferedImage condensedTexture = buildCondensedTexture();
        BufferedImage catalystTexture = buildCatalystTexture();

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

            // Soul Table block/item
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

            // Condensed Soul Essence item
            put(zos, "assets/gloved/items/condensed_soul_essence.json", ("{\n" +
                    "  \"model\": {\n" +
                    "    \"type\": \"minecraft:model\",\n" +
                    "    \"model\": \"gloved:item/condensed_soul_essence\"\n" +
                    "  }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "assets/gloved/models/item/condensed_soul_essence.json", ("{\n" +
                    "  \"parent\": \"minecraft:item/generated\",\n" +
                    "  \"textures\": { \"layer0\": \"gloved:item/condensed_soul_essence\" }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "assets/gloved/textures/item/condensed_soul_essence.png", png(condensedTexture));

            // Soul Catalyst item
            put(zos, "assets/gloved/items/soul_catalyst.json", ("{\n" +
                    "  \"model\": {\n" +
                    "    \"type\": \"minecraft:model\",\n" +
                    "    \"model\": \"gloved:item/soul_catalyst\"\n" +
                    "  }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "assets/gloved/models/item/soul_catalyst.json", ("{\n" +
                    "  \"parent\": \"minecraft:item/generated\",\n" +
                    "  \"textures\": { \"layer0\": \"gloved:item/soul_catalyst\" }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "assets/gloved/textures/item/soul_catalyst.png", png(catalystTexture));
        }
        return baos.toByteArray();
    }

    /** 16x16 condensed amethyst/soul crystal. */
    private static BufferedImage buildCondensedTexture() {
        BufferedImage image = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(TEXTURE_SEED ^ 0x434F4E44455345L);
        int outer = 0x3A5FA8;
        int mid = 0x57C7FF;
        int inner = 0xD9FBFF;
        int edge = 0x1E2E52;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                double dx = Math.abs(x - 7.5) / 7.5;
                double dy = Math.abs(y - 7.5) / 7.5;
                double d = dx + dy; // diamond
                if (d > 1.05) continue;
                int color;
                if (d > 0.85) color = edge;
                else if (d > 0.55) color = jitter(outer, rng.nextInt(11) - 5);
                else if (d > 0.25) color = jitter(mid, rng.nextInt(11) - 5);
                else color = jitter(inner, rng.nextInt(9) - 4);
                // subtle cross highlight
                if ((x == 7 || x == 8) && y >= 4 && y <= 11 && d <= 0.85) color = blend(color, 0xFFFFFF, 0.18);
                if ((y == 7 || y == 8) && x >= 4 && x <= 11 && d <= 0.85) color = blend(color, 0xFFFFFF, 0.12);
                image.setRGB(x, y, 0xFF000000 | color);
            }
        }
        return image;
    }

    /** 16x16 soul catalyst core. */
    private static BufferedImage buildCatalystTexture() {
        BufferedImage image = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(TEXTURE_SEED ^ 0x434154414C595354L);
        int ring = 0xA24BFF;
        int ringHi = 0xE0A8FF;
        int core = 0x66FFF2;
        int coreHi = 0xF4FFFF;
        int dark = 0x2A1038;
        for (int y = 0; y < TEXTURE_SIZE; y++) {
            for (int x = 0; x < TEXTURE_SIZE; x++) {
                double dx = x - 7.5;
                double dy = y - 7.5;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > 7.2) continue;
                int color = 0;
                if (dist >= 5.2 && dist <= 6.8) {
                    color = ((x + y) & 1) == 0 ? ringHi : ring;
                } else if (dist <= 2.6) {
                    color = dist <= 1.2 ? coreHi : core;
                } else if (dist > 3.0 && dist < 4.6 && ((x + y) % 3 == 0)) {
                    color = jitter(dark, rng.nextInt(13) - 6);
                } else if (dist < 5.2) {
                    // connectors/spokes
                    if (Math.abs(dx) <= 0.8 || Math.abs(dy) <= 0.8) color = jitter(ring, rng.nextInt(11) - 5);
                }
                if (color == 0) continue;
                image.setRGB(x, y, 0xFF000000 | color);
            }
        }
        return image;
    }

    private static int blend(int base, int top, double amount) {
        amount = Math.max(0.0, Math.min(1.0, amount));
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int tr = (top >> 16) & 0xFF, tg = (top >> 8) & 0xFF, tb = top & 0xFF;
        int r = clamp((int) Math.round(br + (tr - br) * amount));
        int g = clamp((int) Math.round(bg + (tg - bg) * amount));
        int b = clamp((int) Math.round(bb + (tb - bb) * amount));
        return (r << 16) | (g << 8) | b;
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
