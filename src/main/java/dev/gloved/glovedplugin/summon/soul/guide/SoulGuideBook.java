package dev.gloved.glovedplugin.summon.soul.guide;

import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.soul.domain.SoulAffixType;
import dev.gloved.glovedplugin.summon.soul.service.SoulService;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitDefinition;
import dev.gloved.glovedplugin.summon.soul.trait.SoulTraitRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the Soul Guide as a read-only written book opened via
 * {@link org.bukkit.entity.Player#openBook(ItemStack)}.
 * <p>
 * Aggressively condensed: visible lines are short labels (~20 chars max to fit
 * the 114px book line width), every detail lives in a hover tooltip, and pages
 * split at the 14-line client cap. Traits fit on one page (12 traits + header
 * = 13 lines). Dynamic sources are read at open time.
 */
public final class SoulGuideBook {

    /** Hard client cap: a written-book page shows at most 14 lines. */
    private static final int MAX_VISUAL_LINES_PER_PAGE = 14;
    /** Pre-wrap limit so each line fits within the 114px book line width. */
    private static final int MAX_CHARS_PER_LINE = 20;

    private final GlovedPlugin plugin;

    public SoulGuideBook(GlovedPlugin plugin) {
        this.plugin = plugin;
    }

    /** Builds and opens the Soul Guide book for the given player. */
    public void open(org.bukkit.entity.Player player) {
        if (player == null || plugin == null) return;
        try {
            ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
            BookMeta meta = (BookMeta) book.getItemMeta();
            if (meta == null) return;
            meta.title(Component.text("Soul Guide", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
            meta.author(Component.text("GlovedPlugin", NamedTextColor.GRAY));
            meta.pages(buildPages());
            book.setItemMeta(meta);
            player.openBook(book);
        } catch (Throwable t) {
            plugin.getLogger().warning("[SoulGuide] Failed to open Soul Guide book: " + t.getMessage());
        }
    }

    /* ---------------------------------------------------------------- pages */

    private List<Component> buildPages() {
        SoulService svc = plugin.getSoulService();
        boolean durationEnabled = isDurationEnabled(svc);

        PageSink sink = new PageSink();

        // Page 1 — Title / TOC
        sink.section("Soul Guide");
        sink.line("Capture mobs.");
        sink.line("Summon them.");
        sink.line("Upgrade souls.");
        sink.toc("Overview");
        sink.toc("Affixes");
        sink.toc("Traits");
        sink.toc("Soul Table");
        sink.toc("Condense & Infusion");
        sink.flush();

        // Page 2 — Overview / stats
        sink.section("Overview");
        sink.subHeading("Stats");
        sink.stat("Health", NamedTextColor.RED, "Summon toughness.");
        sink.stat("Defense", NamedTextColor.DARK_AQUA, "Less damage taken.");
        sink.stat("Damage", NamedTextColor.RED, "Hit strength.");
        sink.stat("Move Speed", NamedTextColor.AQUA, "Movement speed.");
        sink.stat("Attack Speed", NamedTextColor.GREEN, "Attack rate.");
        sink.stat("Respawn Time", NamedTextColor.LIGHT_PURPLE, "Group respawn delay.");
        sink.flush();

        // Page 3 — Affixes
        sink.section("Affixes");
        sink.line("Rolled bonus stats.");
        for (SoulAffixType type : SoulAffixType.values()) {
            if (type == SoulAffixType.CAPACITY) continue; // retired from gameplay
            if (!durationEnabled && type == SoulAffixType.ENDURING) continue;
            sink.affix(type);
        }
        sink.flush();

        // Page 4 — Traits (all registered traits on one page)
        SoulTraitRegistry registry = SoulTraitRegistry.getActive();
        List<SoulTraitDefinition> defs = registry != null ? registry.all() : List.of();
        if (defs.isEmpty()) {
            sink.section("Traits");
            sink.line("No traits registered.");
            sink.flush();
        } else {
            sink.section("Traits");
            int count = 0;
            for (SoulTraitDefinition def : defs) {
                if (count >= 13) break;
                String name = def != null && def.displayName() != null ? def.displayName() : "?";
                List<String> desc = def != null ? def.guideDescription() : List.of();
                if (desc == null) desc = List.of();
                String tooltip = joinNonNull(desc, " ");
                if (tooltip.isEmpty()) tooltip = "No description.";
                sink.trait(name, tooltip);
                count++;
            }
            sink.flush();
        }

        // Page 5 — Soul Table + Condense / Infusion
        sink.section("Soul Table");
        sink.action("Combine", "3 same type + rarity → higher.");
        sink.action("Enhance", "Level up a soul. Applies next summon.");
        sink.action("Reforge", "Reroll stats + bonuses.");
        sink.action("Salvage", "Break down for Essence.");
        sink.section("Condense & Infusion");
        sink.item("Condense", "Essence ⇄ Condensed Soul Essence.");
        sink.item("Batch", "x1 / x16 / x64 / Max.");
        sink.item("Infusion", "Add / replace traits.");
        sink.item("Cost", "Essence + Soul Catalysts.");
        sink.item("Timing", "Next summon.");
        sink.flush();

        return sink.build();
    }

    /* ---------------------------------------------------------------- page sink */

    /**
     * Accumulates visual lines and starts a new book page at the 14-line cap.
     * Wrapped paragraphs get fresh plain components per line; hover items keep
     * their component (and tooltip) as a single line.
     */
    private static final class PageSink {
        private final List<Component> pages = new ArrayList<>();
        private final List<Component> current = new ArrayList<>();
        private int visualLines = 0;

        void section(String text) {
            addItem(Component.text(text, NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
        }

        void subHeading(String text) {
            addItem(Component.text(text, NamedTextColor.AQUA));
        }

        void line(String text) {
            addWrapped(text, NamedTextColor.WHITE);
        }

        void toc(String text) {
            addItem(Component.text("• " + text, NamedTextColor.GRAY)
                    .hoverEvent(HoverEvent.showText(Component.text("Open " + text + " section.", NamedTextColor.GRAY))));
        }

        void stat(String name, NamedTextColor color, String detail) {
            addItem(Component.text("• " + name, color, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text(detail, NamedTextColor.GRAY))));
        }

        void affix(SoulAffixType type) {
            addItem(Component.text("• " + type.friendlyName(), NamedTextColor.GREEN)
                    .hoverEvent(HoverEvent.showText(Component.text(affixDescription(type), NamedTextColor.GRAY))));
        }

        void trait(String name, String tooltip) {
            addItem(Component.text("• " + name, NamedTextColor.DARK_AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text(tooltip, NamedTextColor.GRAY))));
        }

        void action(String name, String detail) {
            addItem(Component.text("• " + name, NamedTextColor.YELLOW)
                    .hoverEvent(HoverEvent.showText(Component.text(detail, NamedTextColor.GRAY))));
        }

        void item(String name, String detail) {
            addItem(Component.text("• " + name, NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text(detail, NamedTextColor.GRAY))));
        }

        void flush() {
            if (current.isEmpty()) return;
            pages.add(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), current));
            current.clear();
            visualLines = 0;
        }

        List<Component> build() {
            flush();
            return pages;
        }

        private void addItem(Component c) {
            if (visualLines >= MAX_VISUAL_LINES_PER_PAGE) flush();
            current.add(c);
            visualLines++;
        }

        private void addWrapped(String text, NamedTextColor color) {
            for (String line : wrap(text)) {
                if (visualLines >= MAX_VISUAL_LINES_PER_PAGE) flush();
                current.add(Component.text(line, color));
                visualLines++;
            }
        }

        private List<String> wrap(String text) {
            if (text == null || text.isBlank()) return List.of("");
            String s = stripLegacyFormatting(text);
            if (s.isEmpty()) return List.of("");
            if (s.length() <= MAX_CHARS_PER_LINE) return List.of(s);
            List<String> out = new ArrayList<>();
            String[] words = s.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                int add = line.length() > 0 ? word.length() + 1 : word.length();
                if (line.length() + add > MAX_CHARS_PER_LINE) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    if (line.length() > 0) line.append(' ');
                    line.append(word);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            return out;
        }

        private static String stripLegacyFormatting(String text) {
            if (text == null) return "";
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '§' && i + 1 < text.length()) {
                    i++;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private static String joinNonNull(List<String> parts, String sep) {
            StringBuilder out = new StringBuilder();
            for (String p : parts) {
                if (p == null) continue;
                if (out.length() > 0) out.append(sep);
                out.append(stripLegacyFormatting(p));
            }
            return out.toString();
        }

        private static String affixDescription(SoulAffixType type) {
            return switch (type) {
                case BRUTAL -> "Hit harder.";
                case VIGOROUS -> "More health.";
                case PRECISE -> "More crits.";
                case DEADLY -> "Crits hit harder.";
                case TENACIOUS -> "More defense.";
                case ENDURING -> "Stay longer.";
                case RESONANT -> "Stronger effects.";
                case REAWAKENING -> "Faster respawn.";
                case CAPACITY -> "More summons.";
            };
        }
    }

    private static String stripLegacyFormatting(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String joinNonNull(List<String> parts, String sep) {
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p == null) continue;
            if (out.length() > 0) out.append(sep);
            out.append(stripLegacyFormatting(p));
        }
        return out.toString();
    }

    private boolean isDurationEnabled(SoulService svc) {
        try {
            if (svc == null || svc.soulConfig() == null) return false;
            return svc.soulConfig().getLifetimeTicks() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
