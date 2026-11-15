package dev.gloved.glovedplugin.ui;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import org.bukkit.Bukkit;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates and deploys the GlovedPlugin UI icon resource pack (GlovedUI.zip).
 *
 * <p>Provides custom item models/textures for GUI navigation and menu-entry buttons via
 * {@code gloved:ui/*} item model ids. Icons are loaded from bundled resources
 * ({@code /ui/icons/*.png}) and deployed to ResourcePackManager mixer like other packs.</p>
 */
public final class UiPackService {

    public record Result(boolean ok, String message) {}

    private static final String ZIP_NAME = "GlovedUI.zip";

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

    private record IconDef(String path, String resourceName) {
        String modelPath() { return path; }
    }

    private byte[] buildPack() throws IOException {
        System.setProperty("java.awt.headless", "true");
        List<IconDef> icons = List.of(
                new IconDef(UiIcons.ARROW_LEFT, "arrow_left.png"),
                new IconDef(UiIcons.ARROW_RIGHT, "arrow_right.png"),
                new IconDef(UiIcons.ARROW_LEFT_DISABLED, "arrow_left_disabled.png"),
                new IconDef(UiIcons.ARROW_RIGHT_DISABLED, "arrow_right_disabled.png"),
                new IconDef(UiIcons.BACK, "back.png"),
                new IconDef(UiIcons.CLOSE, "close.png"),
                new IconDef(UiIcons.HOME, "home.png"),
                new IconDef(UiIcons.GUIDE, "guide.png"),
                new IconDef(UiIcons.TEAMS, "teams.png"),
                new IconDef(UiIcons.VAULT, "vault.png"),
                new IconDef(UiIcons.COMBINE, "combine.png"),
                new IconDef(UiIcons.ENHANCE, "enhance.png"),
                new IconDef(UiIcons.REFORGE, "reforge.png"),
                new IconDef(UiIcons.SALVAGE, "salvage.png"),
                new IconDef(UiIcons.CONDENSE, "condense.png"),
                new IconDef(UiIcons.INFUSION, "infusion.png")
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            put(zos, "pack.mcmeta", ("{\n" +
                    "  \"pack\": {\n" +
                    "    \"description\": \"GlovedPlugin UI Icons\",\n" +
                    "    \"min_format\": [97,1],\n" +
                    "    \"max_format\": 97\n" +
                    "  }\n" +
                    "}\n").getBytes(StandardCharsets.UTF_8));
            put(zos, "CREDITS.txt", creditsText().getBytes(StandardCharsets.UTF_8));

            // preview icon
            put(zos, "pack.png", png(scaleNearest(readResourceImage("/ui/icons/arrow_right.png"), 64)));

            for (IconDef icon : icons) {
                String p = icon.modelPath(); // e.g. ui/arrow_left
                byte[] texture = readResourceBytes("/ui/icons/" + icon.resourceName());
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
                put(zos, "assets/gloved/textures/item/" + p + ".png", texture);
            }
        }
        return baos.toByteArray();
    }

    private String creditsText() {
        return "GlovedPlugin UI Icons\n\n" +
                "Some icons extracted from game-icons.net (https://game-icons.net/)\n" +
                "SVG source archive: https://game-icons.net/archives/ffffff/transparent/game-icons.net.svg.zip\n" +
                "License: CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/)\n" +
                "Changes: selected, resized to 16x16, and recolored/rotated as needed for UI use.\n\n" +
                "Icons used:\n" +
                "- arrow_left/arrow_right (+ disabled variants): delapouite/plain-arrow (rotated, disabled dimmed)\n" +
                "- back: lorc/return-arrow\n" +
                "- close: sbed/cancel\n" +
                "- home: delapouite/house\n" +
                "- guide: lorc/open-book\n" +
                "- teams: delapouite/three-friends\n" +
                "- vault: delapouite/chest\n" +
                "- combine: lorc/anvil-impact\n" +
                "- enhance: delapouite/upgrade\n" +
                "- reforge: lorc/flat-hammer\n" +
                "- salvage: lorc/recycle\n" +
                "- condense: lorc/crystal-growth\n" +
                "- infusion: lorc/magic-swirl\n";
    }

    private static byte[] readResourceBytes(String path) throws IOException {
        try (InputStream in = UiPackService.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing UI icon resource: " + path);
            return in.readAllBytes();
        }
    }

    private static BufferedImage readResourceImage(String path) throws IOException {
        try (InputStream in = UiPackService.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing UI icon resource: " + path);
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("Unreadable UI icon resource: " + path);
            return img;
        }
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
