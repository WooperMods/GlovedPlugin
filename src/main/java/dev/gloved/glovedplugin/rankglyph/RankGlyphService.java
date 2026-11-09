package dev.gloved.glovedplugin.rankglyph;

import dev.gloved.glovedplugin.GlovedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates textured rank-prefix glyphs from LuckPerms groups and wires them into
 * ResourcePackManager + LuckPerms. Robust to prefixes already being glyph chars by
 * falling back to a persisted mapping (plugins/GlovedPlugin/rankglyphs/mapping.json
 * or server tools/rank-glyphs/mapping.json).
 */
public final class RankGlyphService {
    private static final Pattern BRACE_HEX = Pattern.compile("\\{#([0-9A-Fa-f]{6})\\}");
    private static final Pattern AMP_HEX = Pattern.compile("[&\u00A7]#([0-9A-Fa-f]{6})");
    private static final Pattern SECTION_HEX = Pattern.compile("[&\u00A7]x(?:[&\u00A7][0-9A-Fa-f]){6}");
    private static final Map<Character, int[]> LEGACY_RGB = Map.ofEntries(
            Map.entry('0', new int[]{0, 0, 0}), Map.entry('1', new int[]{0, 0, 170}),
            Map.entry('2', new int[]{0, 170, 0}), Map.entry('3', new int[]{0, 170, 170}),
            Map.entry('4', new int[]{170, 0, 0}), Map.entry('5', new int[]{170, 0, 170}),
            Map.entry('6', new int[]{255, 170, 0}), Map.entry('7', new int[]{170, 170, 170}),
            Map.entry('8', new int[]{85, 85, 85}), Map.entry('9', new int[]{85, 85, 255}),
            Map.entry('a', new int[]{85, 255, 85}), Map.entry('b', new int[]{85, 255, 255}),
            Map.entry('c', new int[]{255, 85, 85}), Map.entry('d', new int[]{255, 85, 255}),
            Map.entry('e', new int[]{255, 255, 85}), Map.entry('f', new int[]{255, 255, 255})
    );
    private static final String ZIP_NAME = "LuckPermsRankGlyphs.zip";
    private static final int PUA_START = 0xE300;

    private final GlovedPlugin plugin;
    public RankGlyphService(GlovedPlugin plugin) { this.plugin = plugin; }

    public record Result(boolean ok, String message, List<GroupGlyph> glyphs) {}
    public record GroupGlyph(String group, int weight, String label, String sourcePrefix, int[] bgRgb, int codepoint, String command) {}

    public void runAll(CommandSender sender) {
        sender.sendMessage(Component.text("[RankGlyphs] Generating pack + applying LuckPerms prefixes...", NamedTextColor.GRAY));
        generate().thenAccept(gen -> {
            if (!gen.ok() || gen.glyphs().isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("[RankGlyphs] " + gen.message(), NamedTextColor.RED)));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> { sender.sendMessage(Component.text("[RankGlyphs] " + gen.message(), NamedTextColor.GREEN)); applyAndReload(sender, gen.glyphs()); });
        }).exceptionally(ex -> { Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("[RankGlyphs] Failed: " + ex.getMessage(), NamedTextColor.RED))); return null; });
    }

    public void runGenerate(CommandSender sender) {
        sender.sendMessage(Component.text("[RankGlyphs] Generating resource pack...", NamedTextColor.GRAY));
        generate().thenAccept(gen -> Bukkit.getScheduler().runTask(plugin, () -> {
            sender.sendMessage(Component.text("[RankGlyphs] " + gen.message(), gen.ok() ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (gen.ok()) sender.sendMessage(Component.text("[RankGlyphs] Next: /gloved rankglyphs apply (or 'all').", NamedTextColor.YELLOW));
        }));
    }

    public void runApply(CommandSender sender) {
        // Always prefer mapping for apply (stable, and works when LP already has glyphs)
        List<GroupGlyph> mapped = loadAnyMapping();
        if (!mapped.isEmpty()) { applyAndReload(sender, mapped); return; }
        generate().thenAccept(gen -> {
            if (!gen.ok()) Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text("[RankGlyphs] " + gen.message(), NamedTextColor.RED)));
            else Bukkit.getScheduler().runTask(plugin, () -> applyAndReload(sender, gen.glyphs()));
        });
    }

    public void runStatus(CommandSender sender) {
        sender.sendMessage(Component.text("[RankGlyphs] mixer=" + mixerDir().resolve(ZIP_NAME) + " exists=" + Files.exists(mixerDir().resolve(ZIP_NAME)), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("[RankGlyphs] mapping=" + mappingPath() + " exists=" + Files.exists(mappingPath()), NamedTextColor.GRAY));
        List<GroupGlyph> gs = loadAnyMapping();
        if (gs.isEmpty()) sender.sendMessage(Component.text("[RankGlyphs] No mapping found yet.", NamedTextColor.YELLOW));
        for (GroupGlyph g : gs) sender.sendMessage(Component.text("  " + g.group() + " w=" + g.weight() + " U+" + String.format("%04X", g.codepoint()) + " bg=" + Arrays.toString(g.bgRgb()) + " " + g.label(), NamedTextColor.AQUA));
    }

    private CompletableFuture<Result> generate() {
        LuckPerms lp;
        try { lp = LuckPermsProvider.get(); } catch (Throwable t) { return CompletableFuture.completedFuture(new Result(false, "LuckPerms API unavailable: " + t.getMessage(), List.of())); }
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.setProperty("java.awt.headless", "true");
                Collection<Group> groups = lp.getGroupManager().getLoadedGroups();
                if (groups.isEmpty()) lp.getGroupManager().loadAllGroups().join();
                groups = lp.getGroupManager().getLoadedGroups();
                Map<String, GroupGlyph> prior = loadAnyMapping().stream().collect(Collectors.toMap(g -> g.group().toLowerCase(Locale.ROOT), g -> g, (a,b)->a));
                List<GroupGlyph> glyphs = buildGlyphs(new ArrayList<>(groups), prior);
                if (glyphs.isEmpty()) return new Result(false, "No LuckPerms groups/prefix mappings found. Create group prefixes or provide a mapping.", List.of());
                byte[] zip = buildPack(glyphs);
                Path outDir = plugin.getDataFolder().toPath().resolve("rankglyphs");
                try { Files.createDirectories(outDir); } catch (Exception ignored) {}
                try { Files.write(outDir.resolve(ZIP_NAME), zip); } catch (Exception e) { plugin.getLogger().warning("[RankGlyphs] Could not write pack to " + outDir + ": " + e.getMessage()); }
                try { writeMapping(outDir.resolve("mapping.json"), glyphs); } catch (Exception e) { plugin.getLogger().warning("[RankGlyphs] Could not write mapping: " + e.getMessage()); }
                try { writeCommands(outDir.resolve("apply-luckperms-prefixes.txt"), glyphs); } catch (Exception ignored) {}
                String mixerNote;
                try {
                    Files.createDirectories(mixerDir());
                    Path target = mixerDir().resolve(ZIP_NAME);
                    Files.write(target, zip);
                    try { target.toFile().setReadable(true, false); target.toFile().setWritable(true, false); } catch (Exception ignored) {}
                    mixerNote = "deployed to RPM mixer: " + target;
                } catch (Exception e) {
                    mixerNote = "could not write RPM mixer (" + mixerDir().resolve(ZIP_NAME) + "): " + e.getMessage() + ". Pack saved in " + outDir + " — copy to mixer manually, then /rspm reload.";
                    plugin.getLogger().warning("[RankGlyphs] " + mixerNote);
                }
                return new Result(true, "Generated " + glyphs.size() + " glyphs (" + mixerNote + ")", glyphs);
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "RankGlyphs generate error", e);
                return new Result(false, "Generate failed: " + e.getMessage(), List.of());
            }
        });
    }

    private void applyAndReload(CommandSender sender, List<GroupGlyph> glyphs) {
        LuckPerms lp;
        try { lp = LuckPermsProvider.get(); } catch (Throwable t) { sender.sendMessage(Component.text("[RankGlyphs] LuckPerms API unavailable: " + t.getMessage(), NamedTextColor.RED)); return; }
        int changed = 0; List<String> errors = new ArrayList<>();
        for (GroupGlyph g : glyphs) {
            try {
                Group group = lp.getGroupManager().getGroup(g.group());
                if (group == null) { errors.add(g.group() + ": missing"); continue; }
                String glyphChar = new String(Character.toChars(g.codepoint()));
                group.data().clear(node -> node.getType() == NodeType.PREFIX);
                group.data().add(PrefixNode.builder(glyphChar + " ", g.weight()).build());
                lp.getGroupManager().saveGroup(group).join();
                changed++;
            } catch (Exception e) { errors.add(g.group() + ": " + e.getMessage()); }
        }
        sender.sendMessage(Component.text("[RankGlyphs] Set prefixes for " + changed + "/" + glyphs.size() + " groups.", errors.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        for (String err : errors) sender.sendMessage(Component.text("  - " + err, NamedTextColor.RED));
        if (Bukkit.getPluginManager().isPluginEnabled("ResourcePackManager")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rspm reload");
        if (Bukkit.getPluginManager().isPluginEnabled("TAB")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab reload");
        if (Bukkit.getPluginManager().isPluginEnabled("CMI")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cmi tablistupdate -all");
        sender.sendMessage(Component.text("[RankGlyphs] Reloaded rspm/tab/cmi (if present). Clients must download/apply the pack (rejoin/clear server-resource-packs cache if squares persist).", NamedTextColor.GRAY));
    }

    private List<GroupGlyph> buildGlyphs(List<Group> groups, Map<String, GroupGlyph> prior) {
        List<GroupGlyph> out = new ArrayList<>();
        for (Group g : groups) {
            PrefixNode best = null;
            for (PrefixNode pn : g.getNodes(NodeType.PREFIX)) if (best == null || pn.getPriority() > best.getPriority()) best = pn;
            GroupGlyph old = prior.get(g.getName().toLowerCase(Locale.ROOT));
            String prefix = best != null && best.getMetaValue() != null ? best.getMetaValue() : (old != null ? old.sourcePrefix() : "");
            int weight = best != null ? best.getPriority() : (old != null ? old.weight() : 0);
            String label = stripCodes(prefix);
            int[] bg = firstColor(prefix);
            if ((label.isBlank() || isGlyphOnly(label)) && old != null) { if (label.isBlank() || isGlyphOnly(label)) label = old.label(); if (bg == null) bg = old.bgRgb(); if (best == null) weight = old.weight(); if (prefix.isBlank() || isGlyphOnly(stripCodes(prefix))) prefix = old.sourcePrefix(); }
            if (label.isBlank() || isGlyphOnly(label)) label = prettify(g.getName());
            if (bg == null) bg = new int[]{120, 120, 120};
            // include if it has a live prefix OR exists in prior mapping
            if (best != null || old != null) out.add(new GroupGlyph(g.getName(), weight, label, prefix, bg, old != null ? old.codepoint() : -1, ""));
        }
        // also include mapping-only groups not loaded? keep LP authoritative; mapping supplies design.
        out.sort(Comparator.comparingInt(GroupGlyph::weight).reversed().thenComparing(GroupGlyph::group));
        // assign stable codepoints: keep prior if present, else next free from PUA_START
        Set<Integer> used = new HashSet<>();
        for (GroupGlyph g : out) if (g.codepoint() >= 0) used.add(g.codepoint());
        int next = PUA_START;
        List<GroupGlyph> assigned = new ArrayList<>();
        for (GroupGlyph g : out) {
            int cp = g.codepoint();
            if (cp < 0) { while (used.contains(next)) next++; cp = next++; used.add(cp); }
            String cmd = "/lp group " + g.group() + " meta setprefix " + g.weight() + " \"" + new String(Character.toChars(cp)) + " \"";
            assigned.add(new GroupGlyph(g.group(), g.weight(), g.label(), g.sourcePrefix(), g.bgRgb(), cp, cmd));
        }
        return assigned;
    }

    private boolean isGlyphOnly(String s) {
        if (s == null || s.isBlank()) return true;
        for (int i = 0; i < s.length(); ) { int cp = s.codePointAt(i); if (cp < 0xE000 || cp > 0xF8FF) return false; i += Character.charCount(cp); }
        return true;
    }

    private byte[] buildPack(List<GroupGlyph> glyphs) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            put(zos, "pack.mcmeta", "{\n  \"pack\": {\n    \"description\": \"LuckPerms rank glyphs (GlovedPlugin)\",\n    \"min_format\": [97,1],\n    \"max_format\": 97\n  }\n}\n".getBytes(StandardCharsets.UTF_8));
            StringBuilder providers = new StringBuilder("{\n  \"providers\": [\n");
            for (int i = 0; i < glyphs.size(); i++) {
                GroupGlyph g = glyphs.get(i);
                ByteArrayOutputStream ib = new ByteArrayOutputStream();
                ImageIO.write(renderBadge(g.label(), g.bgRgb()), "png", ib);
                put(zos, "assets/minecraft/textures/font/ranks/" + g.group() + ".png", ib.toByteArray());
                if (i > 0) providers.append(",\n");
                providers.append("    {\"type\":\"bitmap\",\"file\":\"minecraft:font/ranks/").append(g.group())
                        .append(".png\",\"ascent\":7,\"height\":8,\"chars\":[\"").append(escapeJson(new String(Character.toChars(g.codepoint())))).append("\"]}");
            }
            providers.append("\n  ]\n}\n");
            put(zos, "assets/minecraft/font/default.json", providers.toString().getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    private BufferedImage renderBadge(String label, int[] bg) {
        int fontSize = 18; Font font = resolveFont(Font.BOLD, fontSize);
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g0 = tmp.createGraphics(); g0.setFont(font); FontMetrics fm = g0.getFontMetrics(); int tw = fm.stringWidth(label); g0.dispose();
        int padX = 4, h = Math.max(fm.getAscent() + 4, 18), w = tw + padX * 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(bg[0], bg[1], bg[2])); g.fillRect(0, 0, w, h);
        g.setColor(luminance(bg) < 0.45 ? Color.WHITE : new Color(30, 30, 30));
        g.setFont(font); g.drawString(label, padX, (h - fm.getHeight()) / 2 + fm.getAscent()); g.dispose();
        return img;
    }

    private Font resolveFont(int style, int size) {
        for (String n : new String[]{"Liberation Sans Bold", "DejaVu Sans Bold", "Arial Bold", Font.SANS_SERIF}) { Font f = new Font(n, style, size); if (f.getFamily() != null) return f; }
        return new Font(Font.SANS_SERIF, style, size);
    }
    private static double luminance(int[] rgb) { double r = srgb(rgb[0]), g = srgb(rgb[1]), b = srgb(rgb[2]); return 0.2126 * r + 0.7152 * g + 0.0722 * b; }
    private static double srgb(int c) { double v = c / 255.0; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException { zos.putNextEntry(new ZipEntry(name)); zos.write(data); zos.closeEntry(); }

    private Path mixerDir() { return plugin.getDataFolder().toPath().getParent().resolve("ResourcePackManager").resolve("mixer"); }
    private Path mappingPath() { return plugin.getDataFolder().toPath().resolve("rankglyphs").resolve("mapping.json"); }
    private List<Path> mappingCandidates() {
        List<Path> p = new ArrayList<>();
        p.add(mappingPath());
        Path plugins = plugin.getDataFolder().toPath().getParent();
        if (plugins != null) {
            Path serverRoot = plugins.getParent();
            if (serverRoot != null) p.add(serverRoot.resolve("tools").resolve("rank-glyphs").resolve("mapping.json"));
            p.add(plugins.resolve("LuckPerms").resolve("rank-glyphs").resolve("mapping.json"));
        }
        return p;
    }
    private List<GroupGlyph> loadAnyMapping() { for (Path p : mappingCandidates()) if (Files.isRegularFile(p)) { try { return readMapping(p); } catch (Exception ignored) {} } return List.of(); }

    private void writeMapping(Path path, List<GroupGlyph> glyphs) throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < glyphs.size(); i++) {
            GroupGlyph g = glyphs.get(i); if (i > 0) sb.append(",\n");
            sb.append("  {\"group\":").append(j(g.group())).append(",\"weight\":").append(g.weight())
                    .append(",\"label\":").append(j(g.label())).append(",\"source_prefix\":").append(j(g.sourcePrefix()))
                    .append(",\"bg_rgb\":[").append(g.bgRgb()[0]).append(",").append(g.bgRgb()[1]).append(",").append(g.bgRgb()[2]).append("]")
                    .append(",\"codepoint\":\"U+").append(String.format("%04X", g.codepoint())).append("\"")
                    .append(",\"command\":").append(j(g.command())).append("}");
        }
        sb.append("\n]\n"); Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private List<GroupGlyph> readMapping(Path path) throws IOException {
        String s = Files.readString(path, StandardCharsets.UTF_8); List<GroupGlyph> out = new ArrayList<>(); int i = 0;
        while ((i = s.indexOf("\"group\"", i)) >= 0) {
            String group = extract(s, i, "group"); if (group.isEmpty()) { i++; continue; }
            int w = parseIntSafe(extractRaw(s, i, "weight"), 0);
            String label = extract(s, i, "label");
            int cp = parseIntSafe(extract(s, i, "codepoint").replace("U+", "").replace("u+", ""), 16, -1);
            if (cp < 0) { String ch = extract(s, i, "char"); if (!ch.isEmpty()) cp = ch.codePointAt(0); }
            String cmd = extract(s, i, "command"); String src = extract(s, i, "source_prefix");
            int[] bg = extractRgb(s, i);
            if (cp >= 0) out.add(new GroupGlyph(group, w, label, src, bg, cp, cmd.isEmpty() ? ("/lp group " + group + " meta setprefix " + w + " \"" + new String(Character.toChars(cp)) + " \"") : cmd));
            i++;
        }
        return out;
    }
    private static int[] extractRgb(String s, int from) {
        int k = s.indexOf("\"bg_rgb\"", from); if (k < 0) return new int[]{120,120,120};
        int b = s.indexOf('[', k), e = s.indexOf(']', b); if (b < 0 || e < 0) return new int[]{120,120,120};
        String[] p = s.substring(b + 1, e).split(","); if (p.length < 3) return new int[]{120,120,120};
        try { return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())}; } catch (Exception ex) { return new int[]{120,120,120}; }
    }
    private static int parseIntSafe(String s, int def) { return parseIntSafe(s, 10, def); }
    private static int parseIntSafe(String s, int radix, int def) { try { return Integer.parseInt(s.trim(), radix); } catch (Exception e) { return def; } }
    private static String extract(String s, int from, String key) {
        int k = s.indexOf("\"" + key + "\"", from); if (k < 0) return "";
        int c = s.indexOf(':', k); int q1 = s.indexOf('"', c + 1); if (q1 < 0) return "";
        StringBuilder b = new StringBuilder();
        for (int i = q1 + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') b.append('\n');
                else if (n == 'r') b.append('\r');
                else if (n == 't') b.append('\t');
                else if (n == '"') b.append('"');
                else if (n == '\\') b.append('\\');
                else if (n == 'u' && i + 4 < s.length()) { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                else b.append(n);
            } else if (ch == '"') break;
            else b.append(ch);
        }
        return b.toString();
    }
    private static String extractRaw(String s, int from, String key) {
        int k = s.indexOf("\"" + key + "\"", from); if (k < 0) return "0"; int c = s.indexOf(':', k); int e = c + 1;
        while (e < s.length() && Character.isWhitespace(s.charAt(e))) e++;
        int end = e; while (end < s.length() && "-0123456789".indexOf(s.charAt(end)) >= 0) end++;
        return s.substring(e, Math.max(end, e+1));
    }
    private void writeCommands(Path path, List<GroupGlyph> glyphs) throws IOException {
        Files.writeString(path, glyphs.stream().map(GroupGlyph::command).collect(Collectors.joining("\n")) + "\n", StandardCharsets.UTF_8);
    }
    private static String j(String s) { return "\"" + escapeJson(s) + "\""; }
    private static String escapeJson(String s) {
        if (s == null) return ""; StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i);
            if (c == '"') b.append("\\\""); else if (c == '\\') b.append("\\\\"); else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r"); else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
        } return b.toString();
    }
    private static String prettify(String name) { return name == null || name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1); }
    private static int[] firstColor(String s) {
        if (s == null) return null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{' && i + 8 <= s.length() && s.charAt(i + 1) == '#') { String hx = s.substring(i + 2, i + 8); if (hx.matches("[0-9A-Fa-f]{6}")) return hex(hx); }
            if ((ch == '&' || ch == '\u00A7') && i + 8 <= s.length() && s.charAt(i + 1) == '#') { String hx = s.substring(i + 2, i + 8); if (hx.matches("[0-9A-Fa-f]{6}")) return hex(hx); }
            if ((ch == '&' || ch == '\u00A7') && i + 1 < s.length() && Character.toLowerCase(s.charAt(i + 1)) == 'x') {
                Matcher m = Pattern.compile("[&\u00A7]([0-9A-Fa-f])").matcher(s.substring(i, Math.min(i + 14, s.length())));
                List<String> parts = new ArrayList<>(); while (m.find()) parts.add(m.group(1));
                if (parts.size() == 7 && parts.get(0).equalsIgnoreCase("x")) return hex(String.join("", parts.subList(1, 7)));
            }
            if ((ch == '&' || ch == '\u00A7') && i + 1 < s.length()) { int[] c = LEGACY_RGB.get(Character.toLowerCase(s.charAt(i + 1))); if (c != null) return c; }
        }
        return null;
    }
    private static int[] hex(String h) { return new int[]{Integer.parseInt(h.substring(0,2),16), Integer.parseInt(h.substring(2,4),16), Integer.parseInt(h.substring(4,6),16)}; }
    private static String stripCodes(String s) {
        if (s == null) return ""; String r = BRACE_HEX.matcher(s).replaceAll(""); r = AMP_HEX.matcher(r).replaceAll("");
        r = SECTION_HEX.matcher(r).replaceAll(""); r = r.replaceAll("[&\u00A7][0-9A-Fa-fK-Ok-oRrXx]", "");
        return r.replace("\u00A7", "").replace("&", "").trim();
    }
}
