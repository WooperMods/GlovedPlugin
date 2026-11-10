package dev.gloved.glovedplugin.summon.soul.trap;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.SoulService;
import dev.gloved.glovedplugin.summon.soul.SoulGenerator;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.*;
import java.util.UUID;

public class SoulTrapListener implements Listener {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private final SoulService soulService;
    private static final NamespacedKey SOULLESS_KEY = new NamespacedKey("gloved", "summon_soulless");

    public SoulTrapListener(GlovedPlugin plugin, SummonConfig config, SoulService soulService) {
        this.plugin = plugin;
        this.config = config;
        this.soulService = soulService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!config.isSoulTrapsEnabled()) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getItemMeta() == null) return;

        SoulTrapTier tier = SoulTrapTier.fromItem(item);
        if (tier == null) return;

        if (!(event.getRightClicked() instanceof LivingEntity target)) return;

        event.setCancelled(true);

        if (target instanceof Player) {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cYou cannot trap players."));
            return;
        }

        // A mob whose soul has already been taken can never be trapped again.
        var targetPdc = target.getPersistentDataContainer();
        if (targetPdc.has(SOULLESS_KEY, PersistentDataType.BOOLEAN)) {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cThis mob's soul has already been taken."));
            return;
        }

        double hp = target.getHealth();
        double maxHp = target.getAttribute(Attribute.MAX_HEALTH) != null
                ? target.getAttribute(Attribute.MAX_HEALTH).getValue()
                : 20.0;
        if (hp <= config.getSoulTrapsMinHpToTrap()) {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cThis mob is too weak to trap."));
            return;
        }

        // Consume the trap — capture is guaranteed.
        item.setAmount(item.getAmount() - 1);

        extractSoul(player, target, tier);
    }

    private void extractSoul(Player player, LivingEntity target, SoulTrapTier tier) {
        target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                tier.mobEffectType, tier.mobEffectDurationTicks, tier.mobEffectAmplifier, false, false));

        // Soulless mobs are permanently slowed.
        target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 1, false, false));

        // Mark the soul as permanently taken: the mob can no longer target or
        // attack anything, and its soul can never be taken again.
        var soullessPdc = target.getPersistentDataContainer();
        soullessPdc.set(SOULLESS_KEY, PersistentDataType.BOOLEAN, true);
        if (target instanceof Mob mob) {
            mob.setTarget(null);
        }

        UUID ownerId = player.getUniqueId();
        SoulConfig cfg = config.getSoulConfig();
        int level = soulService.getSummoningLevel(ownerId);

        SoulRarity rolled = SoulGenerator.rollRarity(cfg);
        int rolledIdx = rolled.ordinal();
        int floorIdx = tier.rarityFloor.ordinal();
        int biasIdx = tier.rarityBias;
        int finalIdx = Math.max(floorIdx, Math.min(rolledIdx + biasIdx, SoulRarity.MYTHIC.ordinal()));
        SoulRarity finalRarity = SoulRarity.values()[finalIdx];

        SoulInstance soul = SoulGenerator.generate(ownerId, target.getType(), cfg, finalRarity, level, 0, 0);

        SoulService.CollectResult res = soulService.persistSoulToVaultPublic(player, soul, cfg, false, true);
        if (res.stored()) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "§aCaptured §f" + SoulService.describe(soul) + " §afrom " + target.getType().name() + "!"));
        } else if (res.convertedToEssence()) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "§aCaptured §f" + SoulService.describe(soul) + " §7(vault full → §b+" + res.essence() + " Essence§7)"));
        } else {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cFailed to store soul: " + res.reason()));
            soulService.dropSoulItemPublic(target.getLocation(), soul);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTargetLivingEntity(EntityTargetLivingEntityEvent event) {
        if (!config.isSoulTrapsEnabled()) return;

        Entity entity = event.getEntity();
        if (entity == null) return;

        var pdc = entity.getPersistentDataContainer();
        if (pdc.has(SOULLESS_KEY, PersistentDataType.BOOLEAN)) {
            event.setCancelled(true);
        }
    }
}
