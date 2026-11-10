package dev.gloved.glovedplugin.summon.soul.trap;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.summon.MinionAI;
import dev.gloved.glovedplugin.summon.SummonConfig;
import dev.gloved.glovedplugin.summon.soul.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.SoulConfig;
import dev.gloved.glovedplugin.summon.soul.SoulService;
import dev.gloved.glovedplugin.summon.soul.SoulRarity;
import dev.gloved.glovedplugin.summon.soul.SoulGenerator;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Listens for right-clicking a living mob with a soul trap item.
 * On success: extracts the mob's soul into a SoulInstance (vault or dropped item),
 * applies the tier's debuff, sets mob HP to 1, and marks the mob as exhausted.
 * On failure: consumes the trap and applies Resistance to the mob.
 */
public class SoulTrapListener implements Listener {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private final SoulService soulService;
    private final Map<UUID, Long> exhaustedMobs = new HashMap<>(); // mob UUID -> exhaustion expiry tick

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

        // Only right-click on a living entity
        if (!(event.getRightClicked() instanceof LivingEntity target)) return;

        event.setCancelled(true);

        if (target instanceof Player) {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cYou cannot trap players."));
            return;
        }

        // Check mob exhaustion (recently trapped)
        UUID mobId = target.getUniqueId();
        long now = System.currentTimeMillis();
        Long exhaustUntil = exhaustedMobs.get(mobId);
        if (exhaustUntil != null && now < exhaustUntil) {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cThis mob's soul is exhausted from a recent capture."));
            return;
        }

        // Check min HP
        double hp = target.getHealth();
        double maxHp = target.getAttribute(Attribute.MAX_HEALTH) != null
                ? target.getAttribute(Attribute.MAX_HEALTH).getValue()
                : 20.0;
        if (hp <= config.getSoulTrapsMinHpToTrap()) {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cThis mob is too weak to trap."));
            return;
        }

        // Check elite/boss resistance
        boolean isElite = MinionAI.isEliteMob(target);
        double effectiveChance = tier.successChance;
        if (isElite) {
            effectiveChance *= (1.0 - config.getSoulTrapsEliteResistance());
        }
        // Boss detection: check if it's a boss-type entity
        boolean isBoss = target instanceof org.bukkit.entity.Boss
                || target.getType() == EntityType.WITHER
                || target.getType() == EntityType.ENDER_DRAGON;
        if (isBoss) {
            effectiveChance *= (1.0 - config.getSoulTrapsBossResistance());
        }

        // Roll success
        boolean success = java.util.concurrent.ThreadLocalRandom.current().nextDouble() < effectiveChance;

        // Consume the trap
        item.setAmount(item.getAmount() - 1);

        if (success) {
            // Extract the soul
            extractSoul(player, target, tier);
        } else {
            // Failure: mob gets Resistance and targets player
            target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, 200, 0, false, false));
            if (target instanceof Mob mob) {
                mob.setTarget(player);
            }
            player.sendMessage(net.kyori.adventure.text.Component.text("§cThe trap failed! The mob resisted."));
        }
    }

    private void extractSoul(Player player, LivingEntity target, SoulTrapTier tier) {
        // Apply debuff to mob
        target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                tier.mobEffectType, tier.mobEffectDurationTicks, tier.mobEffectAmplifier, false, false));

        // Set mob HP to 1 (survive but wounded)
        target.setHealth(1.0);

        // Mark mob as exhausted
        long exhaustionEnd = System.currentTimeMillis() + config.getSoulTrapsTrapExhaustionTicks();
        exhaustedMobs.put(target.getUniqueId(), exhaustionEnd);

        // Generate the soul
        UUID ownerId = player.getUniqueId();
        SoulConfig cfg = config.getSoulConfig();
        int level = soulService.getSummoningLevel(ownerId);

        // Determine rarity based on tier bias
        SoulRarity rolled = SoulGenerator.rollRarity(cfg);
        int rolledIdx = rolled.ordinal();
        int floorIdx = tier.rarityFloor.ordinal();
        int biasIdx = tier.rarityBias;
        int finalIdx = Math.max(floorIdx, Math.min(rolledIdx + biasIdx, SoulRarity.MYTHIC.ordinal()));
        SoulRarity finalRarity = SoulRarity.values()[finalIdx];

        SoulInstance soul = SoulGenerator.generate(ownerId, target.getType(), cfg, finalRarity, level, 0, 0);

        // Persist to vault or drop as item
        SoulService.CollectResult res = soulService.persistSoulToVaultPublic(player, soul, cfg, false, true);
        if (res.stored()) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "§aCaptured §f" + SoulService.describe(soul) + " §afrom " + target.getType().name() + "!"));
        } else if (res.convertedToEssence()) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "§aCaptured §f" + SoulService.describe(soul) + " §7(vault full → §b+" + res.essence() + " Essence§7)"));
        } else {
            player.sendMessage(net.kyori.adventure.text.Component.text("§cFailed to store soul: " + res.reason()));
            // Drop the physical item anyway
            soulService.dropSoulItemPublic(target.getLocation(), soul);
        }
    }
}
