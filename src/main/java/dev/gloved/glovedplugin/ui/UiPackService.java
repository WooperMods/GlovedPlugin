package dev.gloved.glovedplugin.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.Bukkit;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates and deploys the GlovedPlugin UI icon resource pack (GlovedUI.zip).
 *
 * <p>Provides custom item models/textures for GUI navigation and menu-entry buttons via
 * {@code gloved:ui/*} item model ids. Deployed to ResourcePackManager mixer like other packs.</p>
 */
public final class UiPackService {

    public record Result(boolean ok, String message) {}

    private static final String ZIP_NAME = "GlovedUI.zip";
    private static final int SIZE = 16;

    private final GlovedPlugin plugin;

    public UiPackService(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    public Result generateAndDeploy() {
        try {
            byte[] zip = buildPack();
            Path outDir = plugin.getDataFolder().toPath().resolve("ui");
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
                note = "ResourcePackManager folder not found; UI icons kept at " + local;
                plugin.getLogger().warning("[UI] " + note);
            }
            return new Result(true, note);
        } catch (Exception e) {
            plugin.getLogger().warning("[UI] Could not generate UI pack: " + e.getMessage());
            return new Result(false, "UI pack generation failed: " + e.getMessage());
        }
    }

    private record IconDef(String path, BufferedImage texture) {}

    private byte[] buildPack() throws IOException {
        System.setProperty("java.awt.headless", "true");
        List<IconDef> icons = new ArrayList<>();
        icons.add(new IconDef(UiIcons.ARROW_LEFT, drawArrowLeft()));
        icons.add(new IconDef(UiIcons.ARROW_RIGHT, drawArrowRight()));
        icons.add(new IconDef(UiIcons.BACK, drawBack()));
        icons.add(new IconDef(UiIcons.CLOSE, drawClose()));
        icons.add(new IconDef(UiIcons.HOME, drawHome()));
        icons.add(new IconDef(UiIcons.GUIDE, drawGuide()));
        icons.add(new IconDef(UiIcons.TEAMS, drawTeams()));
        icons.add(new IconDef(UiIcons.VAULT, drawVault()));
        icons.add(new IconDef(UiIcons.COMBINE, drawCombine()));
        icons.add(new IconDef(UiIcons.ENHANCE, drawEnhance()));
        icons.add(new IconDef(UiIcons.REFORGE, drawReforge()));
        icons.add(new IconDef(UiIcons.SALVAGE, drawSalvage()));
        icons.add(new IconDef(UiIcons.CONDENSE, drawCondense()));
        icons.add(new IconDef(UiIcons.INFUSION, drawInfusion()));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            put(zos, "pack.mcmeta", ("{\n" +
                    "  \"pack\": {\n" +
                    "    \"description\": \"GlovedPlugin UI Icons\",\n" +
                    "    \"min_format\": [97,1],\n" +
                    "    \"max_format\": 97\n" +
                    "  }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));

            // preview icon
            put(zos, "pack.png", png(scaleNearest(drawArrowRight(), 64)));

            for (IconDef icon : icons) {
                String p = icon.path(); // e.g. ui/arrow_left
                // item definition
                put(zos, "assets/gloved/items/" + p + ".json", ("{\n" +
                        "  \"model\": {\n" +
                        "    \"type\": \"minecraft:model\",\n" +
                        "    \"model\": \"gloved:item/" + p + "\"\n" +
                        "  }\n" +
                        "}\n").getBytes(StandardCharsets.UTF_8));
                // item model
                put(zos, "assets/gloved/models/item/" + p + ".json", ("{\n" +
                        "  \"parent\": \"minecraft:item/generated\",\n" +
                        "  \"textures\": {\n" +
                        "    \"layer0\": \"gloved:item/" + p + "\"\n" +
                        "  }\n" +
                        "}\n").getBytes(StandardCharsets.UTF_8));
                // texture
                put(zos, "assets/gloved/textures/item/" + p + ".png", png(icon.texture()));
            }
        }
        return baos.toByteArray();
    }

    private static BufferedImage blank() {
        return new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
    }

    private static void px(BufferedImage img, int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return;
        img.setRGB(x, y, argb);
    }

    private static void fillRect(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int yy = y; yy < y + h; yy++) for (int xx = x; xx < x + w; xx++) px(img, xx, yy, argb);
    }

    private static void strokeRect(BufferedImage img, int x, int y, int w, int h, int argb) {
        for (int xx = x; xx < x + w; xx++) { px(img, xx, y, argb); px(img, xx, y + h - 1, argb); }
        for (int yy = y; yy < y + h; yy++) { px(img, x, yy, argb); px(img, x + w - 1, yy, argb); }
    }

    private static BufferedImage drawArrowLeft() {
        BufferedImage img = blank();
        int c = 0xFFEAF6FF, s = 0xFF1B3B5A;
        // shaft
        fillRect(img, 5, 7, 8, 2, c); fillRect(img, 5, 8, 8, 1, s);
        // head
        for (int i = 0; i < 4; i++) {
            fillRect(img, 3 + i, 4 + i, 1, 8 - i * 2, c);
            px(img, 3 + i, 3 + i, s); px(img, 3 + i, 12 - i, s);
        }
        return img;
    }

    private static BufferedImage drawArrowRight() {
        BufferedImage img = blank();
        int c = 0xFFEAF6FF, s = 0xFF1B3B5A;
        fillRect(img, 3, 7, 8, 2, c); fillRect(img, 3, 8, 8, 1, s);
        for (int i = 0; i < 4; i++) {
            fillRect(img, 12 - i, 4 + i, 1, 8 - i * 2, c);
            px(img, 12 - i, 3 + i, s); px(img, 12 - i, 12 - i, s);
        }
        return img;
    }

    private static BufferedImage drawBack() {
        BufferedImage img = blank();
        int c = 0xFFFFE8B0, s = 0xFF5A3A12;
        // arrow head left
        for (int i = 0; i < 3; i++) {
            fillRect(img, 3 + i, 5 + i, 1, 6 - i * 2, c);
            px(img, 3 + i, 4 + i, s); px(img, 3 + i, 11 - i, s);
        }
        // curved-ish shaft
        fillRect(img, 6, 7, 5, 2, c);
        px(img, 10, 6, c); px(img, 11, 5, c); px(img, 11, 10, c); px(img, 10, 11, c);
        strokeRect(img, 10, 4, 3, 8, s);
        fillRect(img, 11, 5, 1, 6, c);
        return img;
    }

    private static BufferedImage drawClose() {
        BufferedImage img = blank();
        int c = 0xFFFFB3B3, s = 0xFF6A1E1E;
        for (int i = 0; i < 10; i++) {
            px(img, 3 + i, 3 + i, c); px(img, 12 - i, 3 + i, c);
            px(img, 3 + i, 4 + i, s); px(img, 12 - i, 4 + i, s);
        }
        return img;
    }

    private static BufferedImage drawHome() {
        BufferedImage img = blank();
        int roof = 0xFFFFD38A, wall = 0xFFF0E2C6, edge = 0xFF5A4424;
        // roof
        for (int i = 0; i < 6; i++) fillRect(img, 2 + i, 3 + i, 12 - i * 2, 1, roof);
        // walls
        fillRect(img, 4, 8, 8, 5, wall);
        strokeRect(img, 4, 8, 8, 5, edge);
        // door
        fillRect(img, 7, 10, 2, 3, 0xFF8B5A2B);
        // roof outline accents
        px(img, 7, 2, edge); px(img, 8, 2, edge);
        return img;
    }

    private static BufferedImage drawGuide() {
        BufferedImage img = blank();
        int cover = 0xFF5CA8FF, page = 0xFFF6F0D8, edge = 0xFF1E3558;
        fillRect(img, 4, 3, 8, 10, cover);
        strokeRect(img, 4, 3, 8, 10, edge);
        fillRect(img, 6, 4, 5, 8, page);
        px(img, 5, 4, edge); px(img, 5, 11, edge);
        fillRect(img, 7, 6, 3, 1, 0xFF7AA7D6);
        fillRect(img, 7, 8, 3, 1, 0xFF7AA7D6);
        return img;
    }

    private static BufferedImage drawTeams() {
        BufferedImage img = blank();
        int a = 0xFF8FE388, b = 0xFFFFE08A, c = 0xFF9AD0FF, edge = 0xFF2E3A2E;
        fillRect(img, 2, 4, 4, 8, a); strokeRect(img, 2, 4, 4, 8, edge);
        fillRect(img, 6, 3, 4, 10, b); strokeRect(img, 6, 3, 4, 10, edge);
        fillRect(img, 10, 5, 4, 7, c); strokeRect(img, 10, 5, 4, 7, edge);
        return img;
    }

    private static BufferedImage drawVault() {
        BufferedImage img = blank();
        int box = 0xFFB8A7D9, edge = 0xFF3A2E4A, gem = 0xFF7FE7FF;
        fillRect(img, 3, 5, 10, 8, box); strokeRect(img, 3, 5, 10, 8, edge);
        fillRect(img, 4, 4, 8, 2, 0xFF9A86C4); strokeRect(img, 4, 4, 8, 2, edge);
        fillRect(img, 7, 8, 2, 2, gem); px(img, 7, 8, 0xFFEFFFFF);
        return img;
    }

    private static BufferedImage drawCombine() {
        BufferedImage img = blank();
        int metal = 0xFFC8CDD6, edge = 0xFF3C434E, spark = 0xFFE9B0FF;
        fillRect(img, 3, 9, 10, 4, metal); strokeRect(img, 3, 9, 10, 4, edge);
        fillRect(img, 5, 6, 2, 3, metal); fillRect(img, 9, 6, 2, 3, metal);
        px(img, 8, 4, spark); px(img, 7, 5, spark); px(img, 9, 5, spark); px(img, 8, 6, spark);
        return img;
    }

    private static BufferedImage drawEnhance() {
        BufferedImage img = blank();
        int star = 0xFFFFE08A, edge = 0xFF6A4A12;
        int[] ys = {2,3,3,4,4,4,5,5,5,5,5,4,4,4,3,3};
        // simple star
        px(img,8,2,star); fillRect(img,7,3,3,1,star); fillRect(img,6,4,5,1,star);
        fillRect(img,3,5,11,2,star); fillRect(img,5,7,7,1,star); fillRect(img,4,8,3,1,star); fillRect(img,10,8,3,1,star);
        fillRect(img,4,9,2,1,star); fillRect(img,11,9,2,1,star);
        // center highlight
        px(img,8,5,0xFFFFF6CF);
        // outline hints
        px(img,8,1,edge); px(img,2,5,edge); px(img,13,5,edge); px(img,4,10,edge); px(img,11,10,edge);
        return img;
    }

    private static BufferedImage drawReforge() {
        BufferedImage img = blank();
        int metal = 0xFFB0B8C4, edge = 0xFF404850, hot = 0xFFFF9B5E;
        // hammer head
        fillRect(img, 7, 3, 6, 4, metal); strokeRect(img, 7, 3, 6, 4, edge);
        // handle
        for (int i = 0; i < 6; i++) { px(img, 6 - i, 7 + i, 0xFF8B5A2B); px(img, 7 - i, 7 + i, 0xFF6A3E1A); }
        // sparks
        px(img, 5, 4, hot); px(img, 4, 5, hot); px(img, 5, 6, hot);
        return img;
    }

    private static BufferedImage drawSalvage() {
        BufferedImage img = blank();
        int bin = 0xFF8FA3B8, edge = 0xFF33404D, shard = 0xFF8FE7FF;
        fillRect(img, 4, 6, 8, 7, bin); strokeRect(img, 4, 6, 8, 7, edge);
        fillRect(img, 3, 5, 10, 2, 0xFF74879A); strokeRect(img, 3, 5, 10, 2, edge);
        fillRect(img, 6, 3, 4, 2, edge);
        px(img, 8, 8, shard); px(img, 7, 9, shard); px(img, 9, 9, shard); px(img, 8, 10, 0xFFEFFFFF);
        return img;
    }

    private static BufferedImage drawCondense() {
        BufferedImage img = blank();
        int gem = 0xFF8AD8FF, edge = 0xFF23465A, core = 0xFFEFFFFF;
        // diamond
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            double d = Math.abs(x - 7.5) / 5.5 + Math.abs(y - 7.5) / 5.5;
            if (d <= 1.0) px(img, x, y, gem);
            if (Math.abs(d - 1.0) < 0.18) px(img, x, y, edge);
        }
        px(img, 8, 7, core); px(img, 7, 8, core); px(img, 8, 8, core);
        return img;
    }

    private static BufferedImage drawInfusion() {
        BufferedImage img = blank();
        int a = 0xFFCFA8FF, b = 0xFF8AF0E5, edge = 0xFF3A2E4A;
        // star/core
        fillRect(img, 7, 3, 2, 10, a); fillRect(img, 3, 7, 10, 2, b);
        fillRect(img, 7, 7, 2, 2, 0xFFF8F2FF);
        px(img, 4, 4, a); px(img, 11, 4, b); px(img, 4, 11, b); px(img, 11, 11, a);
        strokeRect(img, 6, 6, 4, 4, edge);
        return img;
    }

    private static BufferedImage scaleNearest(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(src, 0, 0, size, size, null);
        } finally { g.dispose(); }
        return out;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
}
