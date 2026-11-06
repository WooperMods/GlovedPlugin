package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class SummonListener implements Listener {
    private final GlovedPlugin plugin;
    private final SummonConfig config;
    private final SummonManager manager;
    private final Random rand = new Random();

    public SummonListener(GlovedPlugin plugin, SummonConfig config, SummonManager manager) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!config.isEnabled()) {
            GlovedPlugin.logDebug("[Summon] Config disabled");
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            GlovedPlugin.logDebug("[Summon] Player sneaking, abort");
            return;
        }

        var item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            GlovedPlugin.logDebug("[Summon] No item in main hand");
            return;
        }
        String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : "unnamed";
        GlovedPlugin.logDebug("[Summon] Player " + player.getName() + " attacking with " + item.getType() + " '" + itemName + "'");

        String profileName = SummonEnchant.getProfile(item);
        GlovedPlugin.logDebug("[Summon] getProfile result: " + profileName + " for item " + item.getType());
        if (profileName == null) {
            GlovedPlugin.logDebug("[Summon] No profile detected");
            return;
        }
        SummonProfile profile = config.getProfile(profileName);
        GlovedPlugin.logDebug("[Summon] Config lookup for profile '" + profileName + "' -> " + (profile != null ? "found" : "missing"));
        if (profile == null) {
            GlovedPlugin.logDebug("[Summon] Profile not found in config: " + profileName + " – clearing cache");
            SummonEnchant.clearProfile(item);
            return;
        }

        GlovedPlugin.logDebug("[Summon] Profile validSlots: " + profile.validSlots + ", checking HAND");
        if (!profile.validSlots.contains(EquipmentSlot.HAND)) {
            GlovedPlugin.logDebug("[Summon] Slot mismatch");
            return;
        }

        if (!manager.canSummon(player, profile)) {
            GlovedPlugin.logDebug("[Summon] CanSummon false for " + player.getName());
            return;
        }

        double roll = rand.nextDouble();
        GlovedPlugin.logDebug("[Summon] Chance roll " + roll + " vs " + profile.chance);
        if (roll > profile.chance) {
            GlovedPlugin.logDebug("[Summon] Chance failed");
            return;
        }

        var attacked = event.getAttacked();
        LivingEntity target = attacked instanceof LivingEntity le ? le : null;
        GlovedPlugin.logDebug("[Summon] Pre-attack target: " + (target != null ? target.getType() : "null"));
        manager.setCooldown(player, profile);
        manager.spawn(player, profile, target);
        GlovedPlugin.logDebug("[Summon] Summon triggered for " + player.getName() + " profile " + profileName);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageByEntityEvent event) {
        if (!config.isEnabled()) {
            GlovedPlugin.logDebug("[Summon Armor] Config disabled");
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!(event.getDamager() instanceof LivingEntity damager)) {
            GlovedPlugin.logDebug("[Summon Armor] Damager not living");
            return;
        }
        if (player.isSneaking()) {
            GlovedPlugin.logDebug("[Summon Armor] Player sneaking, abort");
            return;
        }
        // Avoid triggering on own minions
        NamespacedKey mobTypeKey = new NamespacedKey(plugin, "summon_mobtype");
        if (damager.getPersistentDataContainer().has(mobTypeKey, PersistentDataType.STRING)) {
            GlovedPlugin.logDebug("[Summon Armor] Damager is a summoned minion, abort");
            return;
        }

        var inv = player.getInventory();
        ItemStack[] armor = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        Set<String> processed = new HashSet<>();

        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item == null || item.getType().isAir()) continue;
            String profileName = SummonEnchant.getProfile(item);
            if (profileName == null) continue;
            if (processed.contains(profileName)) continue;
            processed.add(profileName);

            SummonProfile profile = config.getProfile(profileName);
            if (profile == null) {
                SummonEnchant.clearProfile(item);
                continue;
            }
            if (!profile.validSlots.contains(slots[i])) {
                GlovedPlugin.logDebug("[Summon Armor] Profile " + profileName + " slot mismatch for " + slots[i]);
                continue;
            }
            if (!manager.canSummon(player, profile)) {
                GlovedPlugin.logDebug("[Summon Armor] CanSummon false for " + player.getName() + " profile " + profileName);
                continue;
            }
            double roll = rand.nextDouble();
            GlovedPlugin.logDebug("[Summon Armor] Chance roll " + roll + " vs " + profile.chance + " for profile " + profileName);
            if (roll > profile.chance) {
                GlovedPlugin.logDebug("[Summon Armor] Chance failed for profile " + profileName);
                continue;
            }
            manager.setCooldown(player, profile);
            manager.spawn(player, profile, damager);
            GlovedPlugin.logDebug("[Summon Armor] Summon triggered for " + player.getName() + " profile " + profileName + " from armor slot " + slots[i]);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player && event.getDamager() instanceof LivingEntity damager) {
            MinionAI.recordRecentDamager(player, damager);
        }
    }
}
