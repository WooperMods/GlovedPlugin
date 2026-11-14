package dev.gloved.glovedplugin.summon.vfx;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.annotation.Nullable;

/**
 * Summon-module VFX presets and builders. All effects are composed via builders for modularity.
 */
public final class SummonVfx {
    private SummonVfx() {}

    // --- Lifecycle presets ---

    public static VfxEffect.Builder summonSpawnBuilder(int count) {
        int c = Math.max(0, count);
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.SOUL_FIRE_FLAME).count(14 + Math.min(c * 2, 20)).offset(0.9, 0.7, 0.9).extra(0.02).build())
                .step(ParticleStep.builder(Particle.REVERSE_PORTAL).count(12 + Math.min(c * 2, 15)).offset(0.8, 0.6, 0.8).extra(0.03).build())
                .step(ParticleStep.builder(Particle.WITCH).count(8 + Math.min(c * 1, 12)).offset(0.7, 0.5, 0.7).extra(0.01).build())
                .step(ParticleStep.builder(Particle.END_ROD).count(5 + Math.min(c * 1, 9)).offset(0.5, 0.8, 0.5).extra(0.01).atOffset(0, 0.6, 0).build())
                .step(ParticleStep.builder(Particle.CLOUD).count(4).offset(0.5, 0.2, 0.5).extra(0.02).build())
                .sound(Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8f, 1.15f)
                .sound(Sound.BLOCK_BEACON_ACTIVATE, 0.35f, 1.6f)
                .playerSound(Sound.ITEM_TRIDENT_RETURN, 0.25f, 1.4f);
    }

    /** Plays summon spawn VFX at `at + (0,0.8,0)` (matches SummonManager.playSummonEffects). */
    public static void playSummonSpawn(@Nullable Player player, @Nullable Location at, int count) {
        if (at == null || at.getWorld() == null) return;
        try {
            Location fx = at.clone().add(0, 0.8, 0);
            summonSpawnBuilder(count).build().play(at.getWorld(), fx, player);
        } catch (Throwable ignored) {}
    }

    public static VfxEffect.Builder summonDespawnBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.SOUL_FIRE_FLAME).count(12).offset(0.5, 0.7, 0.5).extra(0.04).build())
                .step(ParticleStep.builder(Particle.REVERSE_PORTAL).count(10).offset(0.6, 0.6, 0.6).extra(0.06).build())
                .step(ParticleStep.builder(Particle.END_ROD).count(7).offset(0.35, 0.5, 0.35).extra(0.03).atOffset(0, 0.5, 0).build())
                .step(ParticleStep.builder(Particle.SOUL).count(15).offset(0.4, 0.5, 0.4).extra(0.02).build())
                .sound(Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.3f)
                .sound(Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.6f);
    }

    /** Plays despawn VFX at entity location + (0,0.6,0). */
    public static void playSummonDespawn(@Nullable LivingEntity le) {
        if (le == null || le.getWorld() == null) return;
        try {
            Location at = le.getLocation().add(0, 0.6, 0);
            summonDespawnBuilder().build().play(le.getWorld(), at);
        } catch (Throwable ignored) {}
    }

    // --- Ability presets ---

    public static VfxEffect.Builder critBuilder(boolean strong) {
        int burst = strong ? 18 : 10;
        VfxEffect.Builder b = VfxEffect.builder()
                .step(ParticleStep.builder(Particle.CRIT).count(burst).offset(0.3, 0.45, 0.3).extra(0.08).build())
                .step(ParticleStep.builder(Particle.ENCHANTED_HIT).count(strong ? 14 : 8).offset(0.35, 0.45, 0.35).extra(0.12).build())
                .step(ParticleStep.builder(Particle.ELECTRIC_SPARK).count(strong ? 10 : 4).offset(0.25, 0.35, 0.25).extra(0.02).build())
                .sound(Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.55f, strong ? 1.55f : 1.35f);
        if (strong) b.sound(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, 1.7f);
        return b;
    }

    /** Matches SummonAbilityService.spawnCritVfx (target center + optional minion END_ROD). */
    public static void playCrit(@Nullable LivingEntity minion, @Nullable LivingEntity target, boolean strong) {
        try {
            if (target == null || target.getWorld() == null) return;
            World world = target.getWorld();
            Location center = target.getLocation().add(0, 1.0, 0);
            critBuilder(strong).build().play(world, center);
            if (minion != null && minion.getWorld() == world) {
                VfxEffect.builder()
                        .step(ParticleStep.builder(Particle.END_ROD).count(strong ? 8 : 4).offset(0.2, 0.25, 0.2).extra(0.01).build())
                        .build().play(world, minion.getLocation().add(0, 0.9, 0));
            }
        } catch (Throwable ignored) {}
    }

    public static VfxEffect.Builder debuffProcBuilder(int level) {
        VfxEffect.Builder b = VfxEffect.builder()
                .step(ParticleStep.builder(Particle.WITCH).count(14 + Math.min(level * 3, 12)).offset(0.35, 0.5, 0.35).extra(0.02).build())
                .step(ParticleStep.builder(Particle.SMOKE).count(10 + Math.min(level * 2, 10)).offset(0.3, 0.4, 0.3).extra(0.01).build())
                .step(ParticleStep.builder(Particle.SOUL).count(6 + Math.min(level, 6)).offset(0.25, 0.35, 0.25).extra(0.02).atOffset(0, 0.2, 0).build())
                .sound(Sound.ENTITY_WITCH_DRINK, 0.3f, 1.25f)
                .sound(Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.35f, 1.45f);
        if (level >= 2) {
            b.step(ParticleStep.builder(Particle.PORTAL).count(18).offset(0.4, 0.6, 0.4).extra(0.15).build());
            b.sound(Sound.ENTITY_ENDERMAN_AMBIENT, 0.18f, 1.8f);
        }
        return b;
    }

    /** Matches SummonAbilityService.spawnDebuffProcVfx. */
    public static void playDebuffProc(@Nullable LivingEntity minion, @Nullable LivingEntity target, int level) {
        try {
            if (target == null || target.getWorld() == null) return;
            World world = target.getWorld();
            Location center = target.getLocation().add(0, 1.0, 0);
            debuffProcBuilder(level).build().play(world, center);
            if (minion != null && minion.getWorld() == world) {
                VfxEffect.builder()
                        .step(ParticleStep.builder(Particle.ENCHANT).count(12).offset(0.3, 0.4, 0.3).extra(0.4).build())
                        .build().play(world, minion.getLocation().add(0, 0.9, 0));
            }
        } catch (Throwable ignored) {}
    }

    public static VfxEffect.Builder cleaveProcBuilder(int level, int victims) {
        VfxEffect.Builder b = VfxEffect.builder()
                .step(ParticleStep.builder(Particle.SWEEP_ATTACK).count(1 + Math.min(victims, 2)).offset(0.2, 0.1, 0.2).extra(0.0).build())
                .step(ParticleStep.builder(Particle.CRIT).count(10 + Math.min(victims * 4, 18) + level * 2).offset(0.6, 0.35, 0.6).extra(0.08).build())
                .step(ParticleStep.builder(Particle.CLOUD).count(6 + Math.min(victims * 2, 10)).offset(0.5, 0.15, 0.5).extra(0.02).build())
                .step(ParticleStep.builder(Particle.ELECTRIC_SPARK).count(8 + Math.min(victims * 3, 12)).offset(0.7, 0.3, 0.7).extra(0.03).build())
                .sound(Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.7f, 0.95f)
                .sound(Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.45f, 1.25f);
        if (victims >= 2) b.sound(Sound.ITEM_TRIDENT_RETURN, 0.25f, 1.8f);
        return b;
    }

    /** Matches SummonAbilityService.spawnCleaveProcVfx. */
    public static void playCleaveProc(@Nullable LivingEntity minion, @Nullable LivingEntity primaryTarget, int victims, int level) {
        try {
            if (primaryTarget == null || primaryTarget.getWorld() == null) return;
            World world = primaryTarget.getWorld();
            Location center = primaryTarget.getLocation().add(0, 1.0, 0);
            cleaveProcBuilder(level, victims).build().play(world, center);
            if (minion != null && minion.getWorld() == world) {
                VfxEffect.builder()
                        .step(ParticleStep.builder(Particle.SONIC_BOOM).count(1).offset(0, 0, 0).extra(0.0).build())
                        .build().play(world, minion.getLocation().add(0, 1.0, 0));
            }
        } catch (Throwable ignored) {}
    }

    public static VfxEffect.Builder lifestealOwnerBuilder(int level, double heal, boolean targetDebuffed) {
        int hearts = Math.max(1, Math.min(4, (int) Math.ceil(heal / 0.5)));
        if (targetDebuffed) hearts = Math.min(5, hearts + 1);
        VfxEffect.Builder b = VfxEffect.builder()
                .step(ParticleStep.builder(Particle.HEART).count(hearts).offset(0.25, 0.18, 0.25).extra(0.0).atOffset(0, 0.7, 0).build()) // owner.getLocation().add(0,1.9,0) relative to ownerCenter+? center is +1.2 so +0.7
                .step(ParticleStep.builder(Particle.HAPPY_VILLAGER).count(6 + Math.min(level * 2, 8)).offset(0.35, 0.45, 0.35).extra(0.02).build())
                .sound(Sound.ENTITY_PLAYER_LEVELUP, 0.18f, 1.9f);
        if (targetDebuffed) b.sound(Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.12f, 2.0f);
        return b;
    }

    /**
     * Matches SummonAbilityService.spawnLifestealProcVfx (owner/target bursts, optional trail target->owner, sounds at owner).
     */
    public static void playLifestealProc(@Nullable LivingEntity owner, @Nullable LivingEntity target, double heal, boolean targetDebuffed, int level) {
        try {
            if (owner == null || owner.getWorld() == null) return;
            World world = owner.getWorld();
            Location ownerCenter = owner.getLocation().add(0, 1.2, 0);
            lifestealOwnerBuilder(level, heal, targetDebuffed).build().play(world, ownerCenter);
            if (target != null && target.getWorld() == world) {
                Location targetCenter = target.getLocation().add(0, 1.0, 0);
                VfxEffect.builder()
                        .step(ParticleStep.builder(Particle.DAMAGE_INDICATOR).count(4 + Math.min(level, 4)).offset(0.2, 0.3, 0.2).extra(0.02).build())
                        .step(ParticleStep.builder(Particle.SOUL).count(4 + Math.min(level, 4)).offset(0.2, 0.3, 0.2).extra(0.01).build())
                        .build().play(world, targetCenter);
                // trail of soul energy from target to owner (immediate line, as before)
                org.bukkit.util.Vector diff = ownerCenter.toVector().subtract(targetCenter.toVector());
                double len = diff.length();
                if (len > 0.2) {
                    org.bukkit.util.Vector step = diff.clone().normalize().multiply(0.5);
                    int steps = Math.min(10, (int) Math.ceil(len / 0.5));
                    Location p = targetCenter.clone();
                    ParticleStep trailStep = ParticleStep.builder(Particle.SCULK_SOUL).count(1).offset(0.02, 0.02, 0.02).extra(0.0).build();
                    for (int i = 0; i < steps; i++) {
                        p.add(step);
                        trailStep.play(world, p.clone());
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    // --- Trait / misc presets ---

    public static VfxEffect.Builder unstableBurstBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.EXPLOSION).count(1).offset(0, 0, 0).extra(0.0).build())
                .step(ParticleStep.builder(Particle.FLAME).count(18).offset(0.6, 0.4, 0.6).extra(0.02).build())
                .sound(Sound.ENTITY_GENERIC_EXPLODE, 0.35f, 1.35f);
    }

    /** UNSTABLE proc VFX at primary target + (0,0.8,0). */
    public static void playUnstableBurst(@Nullable Location at) {
        if (at == null || at.getWorld() == null) return;
        try {
            unstableBurstBuilder().build().play(at.getWorld(), at.clone().add(0, 0.8, 0));
        } catch (Throwable ignored) {}
    }

    /** BULWARK trait: subtle defensive shimmer when a summoned minion mitigates damage. */
    public static VfxEffect.Builder bulwarkProcBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.END_ROD).count(3).offset(0.25, 0.35, 0.25).extra(0.01).build())
                .step(ParticleStep.builder(Particle.WAX_OFF).count(4).offset(0.3, 0.4, 0.3).extra(0.0).build())
                .step(ParticleStep.builder(Particle.CRIT).count(2).offset(0.2, 0.25, 0.2).extra(0.02).build());
    }

    public static void playBulwarkProc(@Nullable LivingEntity minion) {
        if (minion == null || minion.getWorld() == null) return;
        try {
            bulwarkProcBuilder().build().play(minion.getWorld(), minion.getLocation().add(0, minion.getHeight() * 0.55, 0));
        } catch (Throwable ignored) {}
    }

    /** VAMPIRIC trait: subtle crimson/soul accent when lifesteal procs (in addition to lifesteal VFX). */
    public static VfxEffect.Builder vampiricAccentBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.DAMAGE_INDICATOR).count(2).offset(0.12, 0.18, 0.12).extra(0.02).build())
                .step(ParticleStep.builder(Particle.CRIMSON_SPORE).count(4).offset(0.18, 0.2, 0.18).extra(0.0).build());
    }

    public static void playVampiricAccent(@Nullable LivingEntity owner, @Nullable LivingEntity minion) {
        try {
            if (owner != null && owner.getWorld() != null) {
                vampiricAccentBuilder().build().play(owner.getWorld(), owner.getLocation().add(0, 1.0, 0));
            }
            if (minion != null && minion.getWorld() != null) {
                VfxEffect.builder()
                        .step(ParticleStep.builder(Particle.DAMAGE_INDICATOR).count(1).offset(0.1, 0.12, 0.1).extra(0.02).build())
                        .build().play(minion.getWorld(), minion.getLocation().add(0, 0.9, 0));
            }
        } catch (Throwable ignored) {}
    }

    /** HEXING trait: subtle hex wisps accent when a debuff proc occurs. */
    public static VfxEffect.Builder hexingAccentBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.WITCH).count(4).offset(0.18, 0.22, 0.18).extra(0.01).build())
                .step(ParticleStep.builder(Particle.ENCHANT).count(6).offset(0.22, 0.28, 0.22).extra(0.3).build());
    }

    public static void playHexingAccent(@Nullable LivingEntity target) {
        if (target == null || target.getWorld() == null) return;
        try {
            hexingAccentBuilder().build().play(target.getWorld(), target.getLocation().add(0, 1.05, 0));
        } catch (Throwable ignored) {}
    }

    /** SPLINTERING trait: subtle sharp spark accent when a cleave proc occurs. */
    public static VfxEffect.Builder splinteringAccentBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.CRIT).count(4).offset(0.25, 0.2, 0.25).extra(0.05).build())
                .step(ParticleStep.builder(Particle.SWEEP_ATTACK).count(1).offset(0.05, 0.05, 0.05).extra(0.0).build());
    }

    public static void playSplinteringAccent(@Nullable LivingEntity target) {
        if (target == null || target.getWorld() == null) return;
        try {
            splinteringAccentBuilder().build().play(target.getWorld(), target.getLocation().add(0, 1.0, 0));
        } catch (Throwable ignored) {}
    }

    /** FRENZIED trait: subtle ember/rage sparks on hit. */
    public static VfxEffect.Builder frenziedHitBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.FLAME).count(2).offset(0.18, 0.22, 0.18).extra(0.01).build())
                .step(ParticleStep.builder(Particle.SMOKE).count(1).offset(0.12, 0.15, 0.12).extra(0.005).build());
    }

    public static void playFrenziedHit(@Nullable LivingEntity minion, @Nullable LivingEntity target) {
        try {
            if (minion != null && minion.getWorld() != null) {
                frenziedHitBuilder().build().play(minion.getWorld(), minion.getLocation().add(0, Math.max(0.6, minion.getHeight() * 0.6), 0));
            }
            if (target != null && target.getWorld() != null) {
                VfxEffect.builder()
                        .step(ParticleStep.builder(Particle.ASH).count(2).offset(0.15, 0.2, 0.15).extra(0.0).build())
                        .build().play(target.getWorld(), target.getLocation().add(0, 1.0, 0));
            }
        } catch (Throwable ignored) {}
    }

    /** PACKBOUND trait: subtle ally-link glint when pack bonus is active on hit. */
    public static VfxEffect.Builder packboundHitBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.HAPPY_VILLAGER).count(2).offset(0.22, 0.25, 0.22).extra(0.0).build())
                .step(ParticleStep.builder(Particle.END_ROD).count(1).offset(0.12, 0.2, 0.12).extra(0.005).build());
    }

    public static void playPackboundHit(@Nullable LivingEntity minion) {
        if (minion == null || minion.getWorld() == null) return;
        try {
            packboundHitBuilder().build().play(minion.getWorld(), minion.getLocation().add(0, Math.max(0.7, minion.getHeight() * 0.65), 0));
        } catch (Throwable ignored) {}
    }

    public static VfxEffect.Builder creeperExplosionBuilder() {
        return VfxEffect.builder()
                .step(ParticleStep.builder(Particle.EXPLOSION).count(1).offset(0.0, 0.0, 0.0).extra(0.0).build());
    }

    // --- Animated trail builder (builder-driven sequence) ---

    /**
     * Plays a soul trail from start to end over time (matches SoulService.playSoulTrail).
     * Built via {@link VfxSequence.Builder}.
     */
    public static void playSoulTrail(Plugin plugin, @Nullable Location start, @Nullable Location end) {
        try {
            if (plugin == null || start == null || end == null || start.getWorld() == null) return;
            if (!start.getWorld().equals(end.getWorld())) return;
            final Location trailStart = start.clone();
            final Location trailEnd = end.clone();
            final World world = trailStart.getWorld();
            if (world == null) return;
            final int durationTicks = 6;
            final ParticleStep soulStep = ParticleStep.builder(Particle.SOUL).count(2).offset(0.02, 0.02, 0.02).extra(0.0).build();
            final ParticleStep sculkStep = ParticleStep.builder(Particle.SCULK_SOUL).count(2).offset(0.01, 0.01, 0.01).extra(0.0).build();
            final ParticleStep endRodStep = ParticleStep.builder(Particle.END_ROD).count(6).offset(0.15, 0.2, 0.15).extra(0.01).build();

            VfxSequence.builder(plugin)
                    .delayTicks(0L)
                    .periodTicks(1L)
                    .maxTicks(durationTicks)
                    .onTick((tick, handle) -> {
                        if (trailEnd.getWorld() == null || !trailEnd.getWorld().equals(trailStart.getWorld())) {
                            handle.cancel();
                            return;
                        }
                        Location target = trailEnd.clone();
                        double progress = Math.min(1.0, tick / (double) durationTicks);
                        double lead = Math.min(1.0, progress * 1.15);
                        double trailLen = 0.35;
                        double tail = Math.max(0.0, lead - trailLen);
                        int steps = 20;
                        for (int i = 0; i <= steps; i++) {
                            double t = tail + (lead - tail) * (i / (double) steps);
                            Location p = trailStart.clone().add(target.clone().subtract(trailStart).multiply(t));
                            soulStep.play(world, p);
                            if (i % 2 == 0) {
                                sculkStep.play(world, p);
                            }
                        }
                        if (progress >= 0.95) {
                            endRodStep.play(world, target);
                        }
                    })
                    .start();
        } catch (Throwable ignored) {}
    }
}
