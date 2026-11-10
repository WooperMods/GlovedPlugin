package dev.gloved.glovedplugin.summon.progression;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import dev.gloved.glovedplugin.summon.SummonConfig;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Crit + multi-level abilities (Debuff L2, Cleave L4, Life-Steal L6; archery/fighting-like pacing).
 * Minion crit is AuraSkills-authoritative by default (owner crit_chance/crit_damage stats), with safety caps.
 * GlovedPlugin executes abilities; AuraSkills provides progression/ability levels/UI parity.
 */
public class SummonAbilityService {
    private static final String ABILITY_DEBUFF = "gloved/summoning_debuff";
    private static final String ABILITY_CLEAVE = "gloved/summoning_cleave";
    private static final String ABILITY_LIFESTEAL = "gloved/summoning_lifesteal";

    private final GlovedPlugin plugin;
    private final SummonProgressionBridge progression;

    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>(); // ownerUUID:ability -> tick
    private final Map<UUID, Deque<BudgetSample>> lifestealBudget = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<BudgetSample>> cleaveDamageBudget = new ConcurrentHashMap<>();
    private volatile long debuffProcs;
    private volatile long cleaveProcs;
    private volatile long lifestealHits;

    private record BudgetSample(long tick, double amount) {}
    private record CritResolution(double damage, boolean crit, double multiplier) {}

    public static final class HitResult {
        public final double damage;
        public final boolean crit;
        public final boolean targetDebuffedBefore;
        public HitResult(double damage, boolean crit, boolean targetDebuffedBefore) {
            this.damage = damage; this.crit = crit; this.targetDebuffedBefore = targetDebuffedBefore;
        }
    }

    public static final class Diagnostics {
        public final long debuffProcs, cleaveProcs, lifestealHits;
        public Diagnostics(long debuffProcs, long cleaveProcs, long lifestealHits) {
            this.debuffProcs = debuffProcs; this.cleaveProcs = cleaveProcs; this.lifestealHits = lifestealHits;
        }
    }

    public SummonAbilityService(GlovedPlugin plugin, SummonProgressionBridge progression) {
        this.plugin = plugin;
        this.progression = progression;
    }

    public void clear() {
        cooldowns.clear();
        lifestealBudget.clear();
        cleaveDamageBudget.clear();
        debuffProcs = 0; cleaveProcs = 0; lifestealHits = 0;
    }

    public void onPlayerQuit(UUID ownerId) {
        if (ownerId == null) return;
        String prefix = ownerId + ":";
        cooldowns.keySet().removeIf(k -> k.startsWith(prefix));
        lifestealBudget.remove(ownerId);
        cleaveDamageBudget.remove(ownerId);
    }

    public Diagnostics diagnostics() {
        return new Diagnostics(debuffProcs, cleaveProcs, lifestealHits);
    }

    private int resolveAbilityLevel(Player owner, SummonConfig cfg, String abilityId, int fallbackUnlockLevel) {
        if (owner == null || cfg == null) return 0;
        int level = 0;
        if (progression != null && cfg.isAbilitiesUseAuraAbilityLevels()) {
            try { level = Math.max(0, progression.getAbilityLevel(owner.getUniqueId(), abilityId)); } catch (Throwable ignored) {}
        }
        if (level > 0) return level;
        // Fallback: Summoning skill level >= configured unlock => virtual ability level 1 (functional compatibility).
        if (progression != null) {
            try {
                int skill = progression.getSummoningLevel(owner.getUniqueId());
                if (skill >= Math.max(0, fallbackUnlockLevel)) return 1;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    private OwnerCombatStats stats(Player owner) {
        try {
            if (progression != null && owner != null) return progression.resolveOwnerCombatStats(owner.getUniqueId());
        } catch (Throwable ignored) {}
        return OwnerCombatStats.unavailable();
    }

    private void spawnCritVfx(LivingEntity minion, LivingEntity target, boolean strong) {
        try {
            if (target == null || target.getWorld() == null) return;
            var world = target.getWorld();
            var center = target.getLocation().add(0, 1.0, 0);
            int burst = strong ? 18 : 10;
            world.spawnParticle(Particle.CRIT, center, burst, 0.3, 0.45, 0.3, 0.08);
            world.spawnParticle(Particle.ENCHANTED_HIT, center, strong ? 14 : 8, 0.35, 0.45, 0.35, 0.12);
            world.spawnParticle(Particle.ELECTRIC_SPARK, center, strong ? 10 : 4, 0.25, 0.35, 0.25, 0.02);
            if (minion != null && minion.getWorld() == world) {
                world.spawnParticle(Particle.END_ROD, minion.getLocation().add(0, 0.9, 0), strong ? 8 : 4, 0.2, 0.25, 0.2, 0.01);
            }
            world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.55f, strong ? 1.55f : 1.35f);
            if (strong) {
                world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, 1.7f);
            }
        } catch (Throwable ignored) {}
    }

    private CritResolution resolveCrit(double baseDamage, Player owner, SummonConfig cfg) {
        if (cfg == null || !cfg.isCritEnabled()) return new CritResolution(baseDamage, false, 1.0);
        double chance = 0.0;
        double multiplier = 1.0;
        if (cfg.isCritSourceAuraSkills()) {
            OwnerCombatStats st = stats(owner);
            if (st.available()) {
                chance = Math.max(0.0, st.critChancePercentPoints() / 100.0);
                multiplier = 1.0 + (Math.max(0.0, st.critDamageTrait()) / 100.0);
            } else {
                // AuraSkills unavailable: no legacy double-dip by default; safe no-crit.
                chance = 0.0;
                multiplier = 1.0;
            }
        } else {
            // Legacy formula (explicit opt-in)
            int level = progression != null ? progression.getSummoningLevel(owner.getUniqueId()) : 0;
            chance = cfg.getCritLegacyBaseChance() + cfg.getCritLegacyPerLevel() * Math.max(0, level);
            multiplier = cfg.getCritLegacyDamageMultiplier();
        }
        chance = Math.max(0.0, Math.min(cfg.getCritMaxChance(), chance));
        multiplier = Math.max(cfg.getCritMinDamageMultiplier(), Math.min(cfg.getCritMaxDamageMultiplier(), multiplier));
        boolean crit = chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
        double damage = crit ? baseDamage * Math.max(1.0, multiplier) : baseDamage;
        return new CritResolution(damage, crit, multiplier);
    }

    public HitResult applyCritAndDamage(Player owner, LivingEntity minion, LivingEntity target, double baseDamage, SummonConfig cfg) {
        boolean debuffedBefore = isDebuffed(target, cfg);
        CritResolution cr = resolveCrit(baseDamage, owner, cfg);
        if (cr.crit) {
            spawnCritVfx(minion, target, cr.multiplier >= 1.75 || cr.damage >= baseDamage * 1.75);
            GlovedPlugin.logDebug("[SummonCrit] " + owner.getName() + " minion crit " + baseDamage + " -> " + cr.damage
                    + " (x" + cr.multiplier + ", source=" + (cfg != null ? cfg.getCritSource() : "?") + ")");
        }
        try {
            DamageSource src = DamageSource.builder(DamageType.GENERIC)
                    .withDirectEntity(minion)
                    .withCausingEntity(owner)
                    .build();
            target.damage(cr.damage, src);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonAbility] Primary damage failed: " + t);
        }
        return new HitResult(cr.damage, cr.crit, debuffedBefore);
    }

    public void onAfterPrimaryHit(Player owner, LivingEntity minion, LivingEntity primaryTarget, String profileName,
                                  double primaryFinalDamage, double primaryBaseDamage, boolean primaryCrit, boolean primaryDebuffedBefore, SummonConfig cfg) {
        try {
            if (owner == null || minion == null || primaryTarget == null || cfg == null || progression == null) return;
            if (!cfg.isProgressionEnabled() || !cfg.isAbilitiesEnabled()) return;
            long now = Bukkit.getCurrentTick();

            int debuffLvl = resolveAbilityLevel(owner, cfg, ABILITY_DEBUFF, cfg.getAbilityDebuffUnlockLevel());
            int cleaveLvl = resolveAbilityLevel(owner, cfg, ABILITY_CLEAVE, cfg.getAbilityCleaveUnlockLevel());
            int lifestealLvl = resolveAbilityLevel(owner, cfg, ABILITY_LIFESTEAL, cfg.getAbilityLifestealUnlockLevel());

            boolean debuffProcced = false;
            if (debuffLvl > 0 && cfg.isAbilityDebuffEnabled()
                    && proc(owner.getUniqueId(), "debuff", now, cfg.getAbilityDebuffCooldownTicks(), cfg.getAbilityDebuffChance(debuffLvl))) {
                debuffProcced = applyDebuff(primaryTarget, cfg, debuffLvl);
                if (debuffProcced) {
                    debuffProcs++;
                    GlovedPlugin.logDebug("[SummonAbility] Debuff (lvl " + debuffLvl + ") applied to " + primaryTarget.getName() + " for " + owner.getName());
                    spawnDebuffProcVfx(minion, primaryTarget, debuffLvl);
                }
            }

            List<LivingEntity> cleaveVictims = new ArrayList<>();
            boolean cleaveProcced = false;
            if (cleaveLvl > 0 && cfg.isAbilityCleaveEnabled()
                    && proc(owner.getUniqueId(), "cleave", now, cfg.getAbilityCleaveCooldownTicks(), cfg.getAbilityCleaveChance(cleaveLvl))) {
                cleaveProcced = true;
                cleaveProcs++;
                cleaveVictims = collectCleaveVictims(owner, minion, primaryTarget, cfg, cleaveLvl);
                GlovedPlugin.logDebug("[SummonAbility] Cleave (lvl " + cleaveLvl + ") proc for " + owner.getName() + " victims=" + cleaveVictims.size());
                for (LivingEntity victim : cleaveVictims) {
                    if (victim == null || victim.isDead()) continue;
                    boolean victimDebuffedBefore = isDebuffed(victim, cfg);
                    double ratioBase = cfg.isAbilityCleaveDamageRatioUsesBaseDamage() ? Math.max(0.0, primaryBaseDamage) : Math.max(0.0, primaryFinalDamage);
                    double cleaveBase = Math.min(cfg.getAbilityCleaveMaxDamage(),
                            Math.max(1.0, ratioBase * cfg.getAbilityCleaveDamageRatio(cleaveLvl)));
                    CritResolution ccr = resolveCrit(cleaveBase, owner, cfg);
                    double cleaveDamage = ccr.damage;
                    if (!consumeBudget(cleaveDamageBudget, owner.getUniqueId(), cleaveDamage, cfg.getAbilityCleaveMaxDamagePerMinute(), now)) {
                        GlovedPlugin.logDebug("[SummonAbility] Cleave damage budget capped for " + owner.getName());
                        continue;
                    }
                    try {
                        DamageSource src = DamageSource.builder(DamageType.GENERIC)
                                .withDirectEntity(minion)
                                .withCausingEntity(owner)
                                .build();
                        victim.damage(cleaveDamage, src);
                    } catch (Throwable t) {
                        GlovedPlugin.logDebug("[SummonAbility] Cleave damage failed: " + t);
                    }
                    try { progression.recordKillCredit(owner, minion, victim, profileName, now); } catch (Throwable ignored) {}
                    if (debuffProcced && cfg.isAbilityCleaveSpreadDebuff()) {
                        applyDebuff(victim, cfg, Math.max(1, debuffLvl));
                    }
                    boolean victimDebuffedForSynergy = victimDebuffedBefore || (debuffProcced && cfg.isAbilityCleaveSpreadDebuff()) || isDebuffed(victim, cfg);
                    if (lifestealLvl > 0 && cfg.isAbilityLifestealEnabled()) applyLifesteal(owner, victim, victimDebuffedForSynergy, cfg, now, lifestealLvl);
                }
                if (!cleaveVictims.isEmpty()) {
                    spawnCleaveProcVfx(minion, primaryTarget, cleaveVictims.size(), cleaveLvl);
                }
            }

            boolean primaryDebuffedForSynergy = primaryDebuffedBefore || debuffProcced || isDebuffed(primaryTarget, cfg);
            if (lifestealLvl > 0 && cfg.isAbilityLifestealEnabled()) applyLifesteal(owner, primaryTarget, primaryDebuffedForSynergy, cfg, now, lifestealLvl);
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonAbility] Ability resolution error: " + t);
        }
    }

    private boolean proc(UUID ownerId, String ability, long now, int cooldownTicks, double chance) {
        if (chance <= 0) return false;
        String key = ownerId + ":" + ability;
        long last = cooldowns.getOrDefault(key, Long.MIN_VALUE / 4);
        if (now - last < cooldownTicks) return false;
        if (ThreadLocalRandom.current().nextDouble() > chance) return false;
        cooldowns.put(key, now);
        return true;
    }

    private List<LivingEntity> collectCleaveVictims(Player owner, LivingEntity minion, LivingEntity primary, SummonConfig cfg, int cleaveLvl) {
        List<LivingEntity> out = new ArrayList<>();
        if (primary == null || primary.getWorld() == null) return out;
        double r = cfg.getAbilityCleaveRadius(cleaveLvl);
        int max = Math.max(1, cfg.getAbilityCleaveMaxTargets());
        for (Entity e : primary.getNearbyEntities(r, r, r)) {
            if (out.size() >= max) break;
            if (!(e instanceof LivingEntity le)) continue;
            if (le.getUniqueId().equals(primary.getUniqueId())) continue;
            if (le.getUniqueId().equals(owner.getUniqueId())) continue;
            if (le.getUniqueId().equals(minion.getUniqueId())) continue;
            if (le.isDead()) continue;
            try {
                var pdc = le.getPersistentDataContainer();
                if (pdc.has(new org.bukkit.NamespacedKey(plugin, "summon_mobtype"), org.bukkit.persistence.PersistentDataType.STRING)) continue;
            } catch (Throwable ignored) {}
            if (CombatTargetRules.isProtected(plugin, owner, le)) continue;
            out.add(le);
        }
        return out;
    }

    private boolean applyDebuff(LivingEntity target, SummonConfig cfg, int abilityLevel) {
        if (target == null || target.isDead()) return false;
        PotionEffectType type = PotionEffectResolver.resolve(cfg != null ? cfg.getAbilityDebuffEffect() : "SLOWNESS");
        if (type == null) type = PotionEffectType.SLOWNESS;
        try {
            int dur = cfg != null ? cfg.getAbilityDebuffDurationTicks(abilityLevel) : 60;
            int amp = cfg != null ? Math.max(0, cfg.getAbilityDebuffAmplifier()) : 0;
            target.addPotionEffect(new PotionEffect(type, Math.max(1, dur), amp, true, true, true));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isDebuffed(LivingEntity target, SummonConfig cfg) {
        if (target == null) return false;
        PotionEffectType configured = PotionEffectResolver.resolve(cfg != null ? cfg.getAbilityDebuffEffect() : "SLOWNESS");
        if (configured != null && target.hasPotionEffect(configured)) return true;
        if (cfg != null && !cfg.isAbilityDebuffSynergyAnyCommonDebuff()) return false;
        return target.hasPotionEffect(PotionEffectType.SLOWNESS)
                || target.hasPotionEffect(PotionEffectType.WEAKNESS)
                || target.hasPotionEffect(PotionEffectType.POISON)
                || target.hasPotionEffect(PotionEffectType.WITHER)
                || target.hasPotionEffect(PotionEffectType.GLOWING);
    }

    private boolean consumeBudget(Map<UUID, Deque<BudgetSample>> budgets, UUID ownerId, double amount, double maxPerMinute, long nowTick) {
        if (ownerId == null || amount <= 0) return false;
        if (maxPerMinute <= 0) return true; // 0 = unlimited
        Deque<BudgetSample> dq = budgets.computeIfAbsent(ownerId, k -> new ArrayDeque<>());
        synchronized (dq) {
            long cutoff = nowTick - 1200L;
            while (!dq.isEmpty() && dq.peekFirst().tick < cutoff) dq.removeFirst();
            double total = 0;
            for (BudgetSample s : dq) total += s.amount;
            if (total + amount > maxPerMinute + 1.0e-9) return false;
            dq.addLast(new BudgetSample(nowTick, amount));
            return true;
        }
    }

    private void applyLifesteal(Player owner, LivingEntity target, boolean targetDebuffed, SummonConfig cfg, long nowTick, int abilityLevel) {
        try {
            if (owner == null || !owner.isOnline() || owner.isDead()) return;
            double ownerMax = 20.0;
            try {
                var attr = owner.getAttribute(Attribute.MAX_HEALTH);
                if (attr != null) ownerMax = Math.max(1.0, attr.getValue());
            } catch (Throwable ignored) {}
            double heal = ownerMax * Math.max(0.0, cfg.getAbilityLifestealHealPercentMaxHp(abilityLevel));
            if (targetDebuffed) heal *= Math.max(1.0, cfg.getAbilityLifestealDebuffedBonusMultiplier());
            heal = Math.min(heal, Math.max(0.0, cfg.getAbilityLifestealPerHitHealCap()));
            if (heal <= 0) return;
            if (!consumeBudget(lifestealBudget, owner.getUniqueId(), heal, cfg.getAbilityLifestealMaxHealPerMinute(), nowTick)) return;
            double before = owner.getHealth();
            try {
                owner.heal(heal, EntityRegainHealthEvent.RegainReason.CUSTOM);
            } catch (Throwable t) {
                double next = Math.min(ownerMax, before + heal);
                if (next > before) owner.setHealth(next);
            }
            if (owner.getHealth() > before + 1.0e-9) {
                lifestealHits++;
                GlovedPlugin.logDebug("[SummonAbility] Lifesteal (lvl " + abilityLevel + ") " + heal + " -> " + owner.getName()
                        + (targetDebuffed ? " (debuffed bonus)" : "") + " target=" + (target != null ? target.getType() : "?"));
                spawnLifestealProcVfx(owner, target, heal, targetDebuffed, abilityLevel);
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonAbility] Lifesteal failed: " + t);
        }
    }

    private void spawnDebuffProcVfx(LivingEntity minion, LivingEntity target, int level) {
        try {
            if (target == null || target.getWorld() == null) return;
            var world = target.getWorld();
            var center = target.getLocation().add(0, 1.0, 0);
            world.spawnParticle(Particle.WITCH, center, 14 + Math.min(level * 3, 12), 0.35, 0.5, 0.35, 0.02);
            world.spawnParticle(Particle.SMOKE, center, 10 + Math.min(level * 2, 10), 0.3, 0.4, 0.3, 0.01);
            world.spawnParticle(Particle.SOUL, center.clone().add(0, 0.2, 0), 6 + Math.min(level, 6), 0.25, 0.35, 0.25, 0.02);
            if (level >= 2) {
                world.spawnParticle(Particle.PORTAL, center, 18, 0.4, 0.6, 0.4, 0.15);
            }
            if (minion != null && minion.getWorld() == world) {
                world.spawnParticle(Particle.ENCHANT, minion.getLocation().add(0, 0.9, 0), 12, 0.3, 0.4, 0.3, 0.4);
            }
            world.playSound(center, Sound.ENTITY_WITCH_DRINK, 0.3f, 1.25f);
            world.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.35f, 1.45f);
            if (level >= 2) world.playSound(center, Sound.ENTITY_ENDERMAN_AMBIENT, 0.18f, 1.8f);
        } catch (Throwable ignored) {}
    }

    private void spawnCleaveProcVfx(LivingEntity minion, LivingEntity primaryTarget, int victims, int level) {
        try {
            if (primaryTarget == null || primaryTarget.getWorld() == null) return;
            var world = primaryTarget.getWorld();
            var center = primaryTarget.getLocation().add(0, 1.0, 0);
            world.spawnParticle(Particle.SWEEP_ATTACK, center, 1 + Math.min(victims, 2), 0.2, 0.1, 0.2, 0.0);
            world.spawnParticle(Particle.CRIT, center, 10 + Math.min(victims * 4, 18) + level * 2, 0.6, 0.35, 0.6, 0.08);
            world.spawnParticle(Particle.CLOUD, center, 6 + Math.min(victims * 2, 10), 0.5, 0.15, 0.5, 0.02);
            world.spawnParticle(Particle.ELECTRIC_SPARK, center, 8 + Math.min(victims * 3, 12), 0.7, 0.3, 0.7, 0.03);
            if (minion != null && minion.getWorld() == world) {
                world.spawnParticle(Particle.SONIC_BOOM, minion.getLocation().add(0, 1.0, 0), 1, 0, 0, 0, 0);
            }
            world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 0.95f);
            world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.45f, 1.25f);
            if (victims >= 2) world.playSound(center, Sound.ITEM_TRIDENT_RETURN, 0.25f, 1.8f);
        } catch (Throwable ignored) {}
    }

    private void spawnLifestealProcVfx(Player owner, LivingEntity target, double heal, boolean targetDebuffed, int level) {
        try {
            if (owner == null || owner.getWorld() == null) return;
            var world = owner.getWorld();
            var ownerCenter = owner.getLocation().add(0, 1.2, 0);
            int hearts = Math.max(1, Math.min(4, (int) Math.ceil(heal / 0.5)));
            if (targetDebuffed) hearts = Math.min(5, hearts + 1);
            world.spawnParticle(Particle.HEART, owner.getLocation().add(0, 1.9, 0), hearts, 0.25, 0.18, 0.25, 0.0);
            world.spawnParticle(Particle.HAPPY_VILLAGER, ownerCenter, 6 + Math.min(level * 2, 8), 0.35, 0.45, 0.35, 0.02);
            if (target != null && target.getWorld() == world) {
                var targetCenter = target.getLocation().add(0, 1.0, 0);
                world.spawnParticle(Particle.DAMAGE_INDICATOR, targetCenter, 4 + Math.min(level, 4), 0.2, 0.3, 0.2, 0.02);
                world.spawnParticle(Particle.SOUL, targetCenter, 4 + Math.min(level, 4), 0.2, 0.3, 0.2, 0.01);
                // trail of soul energy from target to owner
                org.bukkit.util.Vector diff = ownerCenter.toVector().subtract(targetCenter.toVector());
                double len = diff.length();
                if (len > 0.2) {
                    org.bukkit.util.Vector step = diff.clone().normalize().multiply(0.5);
                    int steps = Math.min(10, (int) Math.ceil(len / 0.5));
                    var p = targetCenter.clone();
                    for (int i = 0; i < steps; i++) {
                        p.add(step);
                        world.spawnParticle(Particle.SCULK_SOUL, p, 1, 0.02, 0.02, 0.02, 0.0);
                    }
                }
            }
            world.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.18f, 1.9f);
            if (targetDebuffed) world.playSound(owner.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.12f, 2.0f);
        } catch (Throwable ignored) {}
    }
}
