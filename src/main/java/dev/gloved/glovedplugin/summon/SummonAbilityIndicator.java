package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Shows an intuitive action-bar hint when a player holds a main-hand item that
 * grants a summon right-click ability (detected via {@link SummonEnchant#getProfile}).
 * Event-driven (held/swap/join) to avoid spam.
 */
public class SummonAbilityIndicator implements Listener {
    private final GlovedPlugin plugin;
    private final SummonConfig config;

    public SummonAbilityIndicator(GlovedPlugin plugin, SummonConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        refresh(player, next);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> refresh(player, player.getInventory().getItemInMainHand()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> refresh(player, player.getInventory().getItemInMainHand()));
    }

    private void refresh(Player player, ItemStack item) {
        try {
            if (player == null || !player.isOnline()) return;
            if (config == null || !config.isEnabled()) return;
            if (item == null || item.getType().isAir()) return;

            String profileName = SummonEnchant.getProfile(item);
            if (profileName == null) return;
            SummonProfile profile = config.getProfile(profileName);
            String label = (profile != null && profile.enchantName != null && !profile.enchantName.isBlank())
                    ? profile.enchantName
                    : profileName;

            TextColor abilityColor = toTextColor(profile != null ? profile.nametagColor : null);
            Component msg = Component.text()
                    .append(Component.text("Right-Click", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                    .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(label, abilityColor).decorate(TextDecoration.BOLD))
                    .build();
            player.sendActionBar(msg);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonUI] Failed to refresh ability indicator: " + t);
        }
    }

    private static TextColor toTextColor(ChatColor chatColor) {
        if (chatColor == null) return NamedTextColor.YELLOW;
        try {
            String name = chatColor.name().toLowerCase(Locale.ROOT);
            NamedTextColor named = NamedTextColor.NAMES.value(name);
            if (named != null) return named;
        } catch (Throwable ignored) {}
        return NamedTextColor.YELLOW;
    }
}
