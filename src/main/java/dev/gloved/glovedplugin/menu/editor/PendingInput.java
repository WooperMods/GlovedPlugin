package dev.gloved.glovedplugin.menu.editor;

import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A single outstanding chat-text request from the menu editor. Consumed by the
 * chat listener (which cancels the broadcast); anything unanswered in 30
 * seconds expires.
 */
public class PendingInput {

    public enum Kind {
        ITEM_NAME, ITEM_LORE, ITEM_LORE_LINE, ITEM_COMMAND, ITEM_MATERIAL, ADD_BUTTON_MATERIAL,
        MENU_TITLE, MENU_SOUND, FILLER_MATERIAL,
        OPENER_NAME, OPENER_LORE, OPENER_LORE_LINE, OPENER_MATERIAL
    }

    private static final Map<UUID, PendingInput> PENDING = new HashMap<>();

    public final Kind kind;
    /** Item index for ITEM_* kinds, -1 otherwise. */
    public final int itemIndex;
    /** Pre-edit lore copy, so "cancel" restores it (lore kinds only). */
    public final List<String> originalLore;
    public final MenuEditorInv.Screen returnTo;
    /** Lore line index for *-LORE_LINE kinds, -1 otherwise. */
    public final int lineIndex;
    public BukkitTask timeoutTask;

    public PendingInput(Kind kind, int itemIndex, List<String> originalLore, MenuEditorInv.Screen returnTo) {
        this(kind, itemIndex, originalLore, returnTo, -1);
    }

    public PendingInput(Kind kind, int itemIndex, List<String> originalLore, MenuEditorInv.Screen returnTo, int lineIndex) {
        this.kind = kind;
        this.itemIndex = itemIndex;
        this.originalLore = originalLore;
        this.returnTo = returnTo;
        this.lineIndex = lineIndex;
    }

    public static PendingInput get(UUID player) {
        return PENDING.get(player);
    }

    public static void put(UUID player, PendingInput pending) {
        cancel(player);
        PENDING.put(player, pending);
    }

    /** Removes and returns the pending input, cancelling its timeout. */
    public static PendingInput take(UUID player) {
        PendingInput pending = PENDING.remove(player);
        if (pending != null && pending.timeoutTask != null) {
            pending.timeoutTask.cancel();
            pending.timeoutTask = null;
        }
        return pending;
    }

    public static void cancel(UUID player) {
        take(player);
    }

    /** Removes only if it is still this exact instance (timeout race guard). */
    public static boolean removeIfSame(UUID player, PendingInput pending) {
        if (PENDING.get(player) == pending) {
            PENDING.remove(player);
            return true;
        }
        return false;
    }
}
