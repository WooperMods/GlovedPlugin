package dev.gloved.glovedplugin.summon.soul.trait;

import dev.gloved.glovedplugin.combat.targeting.CombatTargetRules;
import dev.gloved.glovedplugin.core.GlovedPlugin;
import dev.gloved.glovedplugin.summon.runtime.DamageProxy;
import dev.gloved.glovedplugin.summon.progression.PotionEffectResolver;
import dev.gloved.glovedplugin.summon.soul.domain.SoulInstance;
import dev.gloved.glovedplugin.summon.soul.trait.ctx.*;
import dev.gloved.glovedplugin.summon.vfx.SummonVfx;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Central definitions and behavior hooks for all built-in soul traits. */
public final class SoulTraits {
    public static final String ID_VAMPIRIC = "VAMPIRIC";
    public static final String ID_FRENZIED = "FRENZIED";
    public static final String ID_BULWARK = "BULWARK";
    public static final String ID_HEXING = "HEXING";
    public static final String ID_SPLINTERING = "SPLINTERING";
    public static final String ID_UNSTABLE = "UNSTABLE";
    public static final String ID_PACKBOUND = "PACKBOUND";
    public static final String ID_MULTI_HIT = "MULTI_HIT";
    public static final String ID_ECHO_STEP = "ECHO_STEP";
    public static final String ID_EXECUTIONER = "EXECUTIONER";
    public static final String ID_POISON_TOUCH = "POISON_TOUCH";
    public static final String ID_REFLECTION = "REFLECTION";

    private static final TraitProcCooldowns PROC_COOLDOWNS = new TraitProcCooldowns();

    private SoulTraits() {}

    public static TraitProcCooldowns procCooldowns() {
        return PROC_COOLDOWNS;
    }

    public static void registerAll(SoulTraitRegistry registry) {
        if (registry == null) return;

        registry.register(SoulTraitBuilder.trait(ID_VAMPIRIC)
                .displayName("Vampiric")
                .description("Your summons heal a bit when dealing damage.")
                .order(0)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyAbility(AbilityContext ctx) {
                        if (ctx == null) return;
                        ctx.multiplyLifestealHeal(1.20);
                        ctx.multiplyLifestealPerHitCap(1.10);
                        ctx.markVampiricLifesteal();
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_FRENZIED)
                .displayName("Frenzied")
                .description("Hits harder and lands critical strikes more often.")
                .order(1)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyOutgoingDamage(OutgoingDamageContext ctx) {
                        if (ctx == null) return;
                        ctx.multiplyDamage(1.08);
                        ctx.markFrenziedHitAccent();
                    }

                    @Override
                    public void modifyCrit(CritContext ctx) {
                        if (ctx == null) return;
                        ctx.addCritChanceBonus(0.03);
                        ctx.addCritDamageBonus(0.08);
                    }

                    @Override
                    public void modifyAbility(AbilityContext ctx) {
                        if (ctx == null) return;
                        ctx.multiplyAbilityPotency(1.05);
                    }

                    @Override
                    public void contributePreview(PreviewContext ctx) {
                        if (ctx == null) return;
                        ctx.multiplyOutgoingDamage(1.08);
                        ctx.addCritChanceBonus(0.03);
                        ctx.addCritDamageBonus(0.08);
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_BULWARK)
                .displayName("Bulwark")
                .description("Tougher summons that take less damage in fights.")
                .order(2)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyIncomingDamage(IncomingDamageContext ctx) {
                        if (ctx == null) return;
                        ctx.addReduction(0.10);
                        try { SummonVfx.playBulwarkProc(ctx.victimMinion()); } catch (Throwable ignored) {}
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_HEXING)
                .displayName("Hexing")
                .description("More likely to weaken enemies when your summons hit.")
                .order(3)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyAbility(AbilityContext ctx) {
                        if (ctx == null) return;
                        ctx.multiplyDebuffChance(1.25);
                        ctx.markHexingDebuffProc();
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_SPLINTERING)
                .displayName("Splintering")
                .description("Better at hurting groups of enemies at once.")
                .order(4)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyAbility(AbilityContext ctx) {
                        if (ctx == null) return;
                        ctx.multiplyCleaveChance(1.20);
                        ctx.multiplyCleaveRatio(1.15);
                        ctx.markSplinteringCleaveProc();
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_UNSTABLE)
                .displayName("Unstable")
                .description("Attacks can burst and damage nearby enemies.")
                .order(5)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void afterPrimaryHit(HitContext ctx) {
                        if (ctx == null) return;
                        applyUnstable(ctx);
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_PACKBOUND)
                .displayName("Packbound")
                .description("Fights stronger when other summons are nearby.")
                .order(6)
                .rollIf(ctx -> ctx != null && ctx.effectiveGroupCapacity() > 1)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyOutgoingDamage(OutgoingDamageContext ctx) {
                        if (ctx == null) return;
                        double pm = computePackboundMultiplier(ctx);
                        if (pm > 1.0001) {
                            ctx.multiplyDamage(pm);
                            ctx.markPackboundActive();
                        }
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_MULTI_HIT)
                .displayName("Multi-Hit")
                .description("Attacks can quickly strike the same enemy twice.")
                .order(7)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void afterPrimaryHit(HitContext ctx) {
                        if (ctx == null) return;
                        applyMultiHit(ctx);
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_ECHO_STEP)
                .displayName("Echo Step")
                .description("Quickly repositions on hit and briefly stuns the target.\nBriefly grants the summon high resistance.")
                .order(8)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void afterPrimaryHit(HitContext ctx) {
                        if (ctx == null) return;
                        applyEchoStep(ctx);
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_EXECUTIONER)
                .displayName("Executioner")
                .description("Deals increased damage to low-health targets or targets affected by Debuff.\nDebuffed targets are more susceptible and take even more damage when both apply.")
                .order(9)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyOutgoingDamage(OutgoingDamageContext ctx) {
                        if (ctx == null) return;
                        applyExecutioner(ctx);
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_POISON_TOUCH)
                .displayName("Poison Touch")
                .description("Attacks can poison enemies.\nWithers targets afflicted by Debuff instead.")
                .order(10)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void afterPrimaryHit(HitContext ctx) {
                        if (ctx == null) return;
                        applyPoisonTouch(ctx);
                    }
                })
                .build());

        registry.register(SoulTraitBuilder.trait(ID_REFLECTION)
                .displayName("Reflection")
                .description("Can reflect incoming attacks back at attackers.\nDebuffed attackers take the full force.")
                .order(11)
                .hooks(new SoulTraitHooks() {
                    @Override
                    public void modifyIncomingDamage(IncomingDamageContext ctx) {
                        if (ctx == null) return;
                        applyReflection(ctx);
                    }
                })
                .build());
    }

    private static double computePackboundMultiplier(OutgoingDamageContext ctx) {
        try {
            LivingEntity minion = ctx.minion();
            Player owner = ctx.owner();
            SoulInstance soul = ctx.soul();
            var tracker = ctx.plugin() != null ? ctx.plugin().getSummonTracker() : null;
            if (minion == null || owner == null || soul == null || tracker == null) return 1.0;
            String fam = soul.getFamilyKey();
            int same = 0;
            for (LivingEntity le : tracker.getActiveMinions(owner.getUniqueId())) {
                if (le == null || le.isDead()) continue;
                if (le.getWorld() != minion.getWorld()) continue;
                if (le.getLocation().distanceSquared(minion.getLocation()) > 256.0) continue;
                try {
                    var pdc = le.getPersistentDataContainer();
                    String f = pdc.get(new NamespacedKey(ctx.plugin(), "summon_family"), PersistentDataType.STRING);
                    if (f == null) f = pdc.get(new NamespacedKey(ctx.plugin(), "summon_profile"), PersistentDataType.STRING);
                    if (fam != null && fam.equalsIgnoreCase(f)) same++;
                } catch (Throwable ignored) {}
            }
            if (same >= 2) {
                return 1.0 + Math.min(0.12, (same - 1) * 0.03);
            }
        } catch (Throwable ignored) {}
        return 1.0;
    }

    private static void applyUnstable(HitContext ctx) {
        Player owner = ctx.owner();
        LivingEntity minion = ctx.minion();
        LivingEntity primaryTarget = ctx.target();
        SoulInstance soul = ctx.soul();
        if (owner == null || minion == null || primaryTarget == null || soul == null) return;
        if (!PROC_COOLDOWNS.tryConsume(minion.getUniqueId(), ID_UNSTABLE, ctx.nowTick(), 80)) return;
        if (ThreadLocalRandom.current().nextDouble() > 0.18) return;

        double radius = 2.75;
        double aoe = Math.max(0.75, Math.min(6.0, Math.max(ctx.finalDamage(), ctx.baseDamage()) * 0.35));
        int hit = 0;
        try {
            if (primaryTarget.getWorld() != null) {
                SummonVfx.playUnstableBurst(primaryTarget.getLocation());
            }
        } catch (Throwable ignored) {}

        for (Entity e : primaryTarget.getNearbyEntities(radius, radius, radius)) {
            if (hit >= 3) break;
            if (!(e instanceof LivingEntity le) || le.isDead()) continue;
            if (le.getUniqueId().equals(primaryTarget.getUniqueId())) continue;
            if (le.getUniqueId().equals(owner.getUniqueId()) || le.getUniqueId().equals(minion.getUniqueId())) continue;
            try {
                var pdc = le.getPersistentDataContainer();
                if (pdc.has(new NamespacedKey(ctx.plugin(), "summon_mobtype"), PersistentDataType.STRING)) continue;
            } catch (Throwable ignored) {}
            if (CombatTargetRules.isProtected(ctx.plugin(), owner, le)) continue;
            try {
                try { if (le.getNoDamageTicks() > 0) le.setNoDamageTicks(0); } catch (Throwable ignored) {}
                DamageSource src = DamageSource.builder(DamageType.GENERIC)
                        .withDirectEntity(minion)
                        .withCausingEntity(minion != null ? minion : owner)
                        .build();
                try {
                    DamageProxy.beginPipelineDamage();
                    le.damage(aoe, src);
                } finally {
                    DamageProxy.endPipelineDamage();
                }
                try {
                    if (ctx.progression() != null) {
                        ctx.progression().recordKillCredit(owner, minion, le, null, ctx.nowTick());
                    }
                } catch (Throwable ignored) {}
                hit++;
            } catch (Throwable ignored) {}
        }
        GlovedPlugin.logDebug("[SummonTrait] UNSTABLE proc by minion " + minion.getUniqueId() + " aoe=" + aoe + " extraTargets=" + hit);
    }

    private static void applyMultiHit(HitContext ctx) {
        GlovedPlugin plugin = ctx.plugin();
        Player owner = ctx.owner();
        LivingEntity minion = ctx.minion();
        LivingEntity target = ctx.target();
        var combat = ctx.combatService();
        if (plugin == null || owner == null || minion == null || target == null || combat == null) return;
        if (minion.isDead() || target.isDead() || !owner.isOnline()) return;
        if (minion.getWorld() == null || target.getWorld() == null || minion.getWorld() != target.getWorld()) return;

        // Deterministic anti-chain guard: consume ICD before scheduling (covers the 4-tick follow-up).
        if (!PROC_COOLDOWNS.tryConsume(minion.getUniqueId(), ID_MULTI_HIT, ctx.nowTick(), 30)) return;
        if (ThreadLocalRandom.current().nextDouble() > 0.15) return;

        String profileName = ctx.profileName();
        try { SummonVfx.playMultiHitProc(minion, target); } catch (Throwable ignored) {}

        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (!plugin.isEnabled()) return;
                    if (owner == null || !owner.isOnline() || owner.isDead()) return;
                    if (minion == null || minion.isDead() || !minion.isValid()) return;
                    if (target == null || target.isDead() || !target.isValid()) return;
                    if (minion.getWorld() == null || target.getWorld() == null || minion.getWorld() != target.getWorld()) return;

                    var options = dev.gloved.glovedplugin.summon.combat.SummonCombatService.AttackOptions.defaults()
                            .rawBaseDamage(0.0)
                            .profileName(profileName)
                            .swingAnimation(true);
                    combat.performSummonAttack(minion, owner, target, Bukkit.getCurrentTick(), options);
                } catch (Throwable ignored) {}
            }, 4L);
        } catch (Throwable ignored) {}
    }

    private static PotionEffectType resolveConfiguredDebuffType(dev.gloved.glovedplugin.summon.config.SummonConfig cfg) {
        try {
            String raw = cfg != null ? cfg.getAbilityDebuffEffect() : "SLOWNESS";
            PotionEffectType type = PotionEffectResolver.resolve(raw);
            return type != null ? type : PotionEffectType.SLOWNESS;
        } catch (Throwable ignored) {
            return PotionEffectType.SLOWNESS;
        }
    }

    private static boolean hasConfiguredDebuffAbilityEffect(LivingEntity target, dev.gloved.glovedplugin.summon.config.SummonConfig cfg) {
        if (target == null || target.isDead()) return false;
        try {
            PotionEffectType type = resolveConfiguredDebuffType(cfg);
            return type != null && target.hasPotionEffect(type);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTargetDebuffedForSynergy(LivingEntity target, boolean targetDebuffedBefore,
                                                      dev.gloved.glovedplugin.summon.config.SummonConfig cfg) {
        if (target == null || target.isDead()) return false;
        if (targetDebuffedBefore) return true;
        try {
            PotionEffectType configured = resolveConfiguredDebuffType(cfg);
            if (configured != null && target.hasPotionEffect(configured)) return true;
            if (cfg != null && !cfg.isAbilityDebuffSynergyAnyCommonDebuff()) return false;
            return target.hasPotionEffect(PotionEffectType.SLOWNESS)
                    || target.hasPotionEffect(PotionEffectType.WEAKNESS)
                    || target.hasPotionEffect(PotionEffectType.POISON)
                    || target.hasPotionEffect(PotionEffectType.WITHER)
                    || target.hasPotionEffect(PotionEffectType.GLOWING);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void applyExecutioner(OutgoingDamageContext ctx) {
        LivingEntity target = ctx.target();
        if (target == null || target.isDead()) return;
        try {
            double maxHp = 20.0;
            try {
                var attr = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (attr != null) maxHp = Math.max(1.0, attr.getValue());
            } catch (Throwable ignored) {
                maxHp = Math.max(1.0, target.getHealth());
            }
            double hp = Math.max(0.0, target.getHealth());
            boolean debuffed = isTargetDebuffedForSynergy(target, false, ctx.summonConfig());
            double lowHealthThreshold = debuffed ? 0.50 : 0.30;
            boolean lowHealth = hp / Math.max(1.0, maxHp) <= lowHealthThreshold;

            if (!lowHealth && !debuffed) return;

            double mult = (lowHealth && debuffed) ? 1.18 : 1.12;
            ctx.multiplyDamage(mult);
        } catch (Throwable ignored) {}
    }

    private static void applyEchoStep(HitContext ctx) {
        Player owner = ctx.owner();
        LivingEntity minion = ctx.minion();
        LivingEntity target = ctx.target();
        if (owner == null || minion == null || target == null) return;
        if (!owner.isOnline() || owner.isDead() || minion.isDead() || target.isDead()) return;
        if (minion.getWorld() == null || target.getWorld() == null || minion.getWorld() != target.getWorld()) return;
        if (CombatTargetRules.isProtected(ctx.plugin(), owner, target)) return;

        boolean targetDebuffed = isTargetDebuffedForSynergy(target, ctx.targetDebuffedBefore(), ctx.summonConfig());
        double chance = 0.15 + (targetDebuffed ? 0.05 : 0.0); // synergy: easier to proc on debuffed targets
        if (!PROC_COOLDOWNS.tryConsume(minion.getUniqueId(), ID_ECHO_STEP, ctx.nowTick(), 100)) return;
        if (ThreadLocalRandom.current().nextDouble() > Math.min(0.95, chance)) return;

        // Brief high resistance on the summon (requested), plus a short speed burst for the step.
        try {
            minion.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 30, 3, true, true, true)); // Resistance IV, 1.5s
            minion.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20, 0, true, true, true));
        } catch (Throwable ignored) {}

        // Reposition minion to a safe flank near the target.
        try {
            Location dest = findEchoStepLocation(minion, owner, target);
            if (dest != null) {
                minion.teleport(dest);
                try { minion.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Simulated stun on target: heavy slow; longer when debuffed (synergy).
        try {
            int stunTicks = targetDebuffed ? 30 : 20;
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stunTicks, 5, true, true, true)); // Slowness VI
        } catch (Throwable ignored) {}

        try { SummonVfx.playEchoStepProc(minion, target, targetDebuffed); } catch (Throwable ignored) {}
        GlovedPlugin.logDebug("[SummonTrait] ECHO_STEP proc by minion " + minion.getUniqueId() + " debuffedSynergy=" + targetDebuffed);
    }

    private static Location findEchoStepLocation(LivingEntity minion, Player owner, LivingEntity target) {
        try {
            Location tLoc = target.getLocation();
            World world = tLoc.getWorld();
            if (world == null) return null;

            Vector awayFromOwner = tLoc.toVector().subtract(owner.getLocation().toVector());
            if (awayFromOwner.lengthSquared() < 1.0e-4) {
                Vector dir = tLoc.getDirection();
                awayFromOwner = (dir != null ? dir.clone() : new Vector(0, 0, 1)).multiply(-1);
            }
            awayFromOwner.setY(0);
            if (awayFromOwner.lengthSquared() < 1.0e-4) awayFromOwner = new Vector(1, 0, 0);
            awayFromOwner.normalize();

            Vector side = new Vector(-awayFromOwner.getZ(), 0, awayFromOwner.getX()).normalize();
            Vector[] dirs = new Vector[] {
                    awayFromOwner.clone(),
                    awayFromOwner.clone().add(side.clone().multiply(0.7)).normalize(),
                    awayFromOwner.clone().subtract(side.clone().multiply(0.7)).normalize(),
                    side.clone(),
                    side.clone().multiply(-1)
            };
            double[] distances = new double[] {1.8, 2.2, 1.5};

            for (double dist : distances) {
                for (Vector dir : dirs) {
                    Location base = tLoc.clone().add(dir.clone().multiply(dist));
                    Location candidate = findSafeTeleportSpot(world, base, minion);
                    if (candidate == null) continue;
                    // Avoid stepping too close to the owner or onto the exact target position in awkward cases.
                    try {
                        if (owner.getWorld() == world && candidate.distanceSquared(owner.getLocation()) < 1.0) continue;
                    } catch (Throwable ignored) {}
                    candidate.setYaw(minion.getLocation().getYaw());
                    candidate.setPitch(minion.getLocation().getPitch());
                    return candidate;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Location findSafeTeleportSpot(World world, Location base, LivingEntity minion) {
        if (world == null || base == null) return null;
        int baseX = base.getBlockX();
        int baseZ = base.getBlockZ();
        int startY = base.getBlockY();
        for (int dy = 0; dy >= -2; dy--) {
            int y = startY + dy;
            Location feetLoc = new Location(world, baseX + 0.5, y, baseZ + 0.5);
            Block feet = world.getBlockAt(baseX, y, baseZ);
            Block head = world.getBlockAt(baseX, y + 1, baseZ);
            Block below = world.getBlockAt(baseX, y - 1, baseZ);
            if (!below.isPassable() && feet.isPassable() && head.isPassable()) {
                // Avoid hazardous floors where obvious.
                try {
                    var belowType = below.getType();
                    if (belowType == org.bukkit.Material.LAVA || belowType == org.bukkit.Material.FIRE
                            || belowType == org.bukkit.Material.CAMPFIRE || belowType == org.bukkit.Material.SOUL_CAMPFIRE
                            || belowType == org.bukkit.Material.MAGMA_BLOCK || belowType == org.bukkit.Material.CACTUS) {
                        continue;
                    }
                } catch (Throwable ignored) {}
                return feetLoc;
            }
        }
        return null;
    }

    private static void applyPoisonTouch(HitContext ctx) {
        Player owner = ctx.owner();
        LivingEntity minion = ctx.minion();
        LivingEntity target = ctx.target();
        if (owner == null || minion == null || target == null) return;
        if (!owner.isOnline() || owner.isDead() || minion.isDead() || target.isDead()) return;
        if (CombatTargetRules.isProtected(ctx.plugin(), owner, target)) return;

        if (!PROC_COOLDOWNS.tryConsume(minion.getUniqueId(), ID_POISON_TOUCH, ctx.nowTick(), 80)) return;
        if (ThreadLocalRandom.current().nextDouble() > 0.20) return;

        // Dynamic synergy: wither instead of poison if affected by Debuff ability (configured debuff effect present).
        boolean affectedByDebuffAbility = hasConfiguredDebuffAbilityEffect(target, ctx.summonConfig())
                || (ctx.targetDebuffedBefore() && hasConfiguredDebuffAbilityEffect(target, ctx.summonConfig()));

        double potency = 1.0;
        try {
            if (ctx.soulSnapshot() != null) {
                potency = Math.max(0.85, Math.min(1.25, ctx.soulSnapshot().abilityPotencyMultiplier()));
            }
        } catch (Throwable ignored) {}
        int duration = Math.max(40, Math.min(120, (int) Math.round(60 * potency)));

        try {
            if (affectedByDebuffAbility) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, duration, 0, true, true, true));
            } else {
                target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, duration, 0, true, true, true));
            }
        } catch (Throwable ignored) {}

        try { SummonVfx.playPoisonTouchProc(minion, target, affectedByDebuffAbility); } catch (Throwable ignored) {}
        GlovedPlugin.logDebug("[SummonTrait] POISON_TOUCH proc by minion " + minion.getUniqueId()
                + " wither=" + affectedByDebuffAbility + " dur=" + duration);
    }

    private static void applyReflection(IncomingDamageContext ctx) {
        GlovedPlugin plugin = ctx.plugin();
        LivingEntity victim = ctx.victimMinion();
        Entity attackerEntity = ctx.attacker();
        if (plugin == null || victim == null || victim.isDead() || attackerEntity == null) return;
        if (!(attackerEntity instanceof LivingEntity attacker) || attacker.isDead()) return;
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (ctx.ownerId() != null && attacker.getUniqueId().equals(ctx.ownerId())) return;

        double incoming = Math.max(0.0, ctx.incomingDamage());
        if (incoming <= 0.0) return;

        long nowTick = Bukkit.getCurrentTick();
        if (!PROC_COOLDOWNS.tryConsume(victim.getUniqueId(), ID_REFLECTION, nowTick, 80)) return;
        if (ThreadLocalRandom.current().nextDouble() > 0.20) return;

        var summonConfig = plugin.getSummonConfig();
        boolean attackerDebuffed = isTargetDebuffedForSynergy(attacker, false, summonConfig);
        double reflected = incoming * (attackerDebuffed ? 1.0 : 0.35);
        reflected = Math.max(0.0, Math.min(reflected, Math.max(incoming, 1.0) * 2.0));
        if (reflected <= 0.0) return;

        // Reflecting this attack prevents damage to the summon.
        ctx.cancelDamage();

        try {
            if (attacker.getNoDamageTicks() > 0) attacker.setNoDamageTicks(0);
        } catch (Throwable ignored) {}

        try {
            Entity causing = victim;
            try {
                if (ctx.ownerId() != null) {
                    Player owner = Bukkit.getPlayer(ctx.ownerId());
                    if (owner != null) causing = owner; // credit owner as causing entity where possible
                }
            } catch (Throwable ignored) {}

            DamageSource src = DamageSource.builder(DamageType.THORNS)
                    .withDirectEntity(victim)
                    .withCausingEntity(causing)
                    .build();
            try {
                DamageProxy.beginPipelineDamage();
                attacker.damage(reflected, src);
            } finally {
                DamageProxy.endPipelineDamage();
            }
        } catch (Throwable t) {
            GlovedPlugin.logDebug("[SummonTrait] REFLECTION reflect damage failed: " + t);
        }

        try { SummonVfx.playReflectionProc(victim, attacker, attackerDebuffed); } catch (Throwable ignored) {}
        GlovedPlugin.logDebug("[SummonTrait] REFLECTION proc by minion " + victim.getUniqueId()
                + " reflected=" + reflected + " full=" + attackerDebuffed + " incoming=" + incoming);
    }

    /** Pretty display name fallback for unknown ids (matches prior formatting behavior style). */
    public static String prettyName(String key) {
        if (key == null || key.isBlank()) return "Unknown";
        String[] parts = key.toLowerCase(Locale.ROOT).split("[_\\s]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }
}
