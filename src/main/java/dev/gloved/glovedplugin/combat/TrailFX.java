package dev.gloved.glovedplugin.combat;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Particle + sound feedback per combo stage. This is the vanilla-client
 * stand-in for Better Combat swing animations and trails. An optional
 * {@code ItemDisplay} slash and the FMM bridge build on top of this.
 */
public final class TrailFX {

    private TrailFX() {}

    public static void play(Player player, AttackStage stage) {
        String particleRaw = stage.particles();
        if (particleRaw != null && particleRaw.trim().equalsIgnoreCase("NONE")) {
            playSoundOnly(player, stage);
            return;
        }
        Particle particle = parseParticle(particleRaw, Particle.SWEEP_ATTACK);
        try {
            var eye = player.getEyeLocation();
            var dir = eye.getDirection();
            // Slash marker slightly in front of the eyes so first-person sees it.
            var at = eye.add(dir.multiply(1.2));
            player.getWorld().spawnParticle(particle, at, 2, 0.3, 0.2, 0.3, 0.01);
        } catch (Exception ignored) {
            // Old clients / odd particles must never break damage handling.
        }
        playSoundOnly(player, stage);
    }

    /**
     * Mace smash cone: expanding rings of particles along the look vector
     * instead of the shared horizontal slash. {@code scale} grows the cone
     * for falling smashes.
     */
    public static void playCone(Player player, AttackStage stage, double scale) {
        String particleRaw = stage.particles();
        if (particleRaw != null && particleRaw.trim().equalsIgnoreCase("NONE")) {
            playSoundOnly(player, stage);
            return;
        }
        Particle particle = parseParticle(particleRaw, Particle.GUST);
        try {
            var eye = player.getEyeLocation();
            var dir = eye.getDirection().normalize();
            double s = Math.max(0.5, Math.min(2.5, scale));
            for (int ring = 0; ring < 4; ring++) {
                double dist = 1.0 + ring * 0.7 * s;
                var at = eye.clone().add(dir.clone().multiply(dist));
                double spread = (0.25 + ring * 0.35) * s;
                player.getWorld().spawnParticle(particle, at, 5, spread, spread * 0.6, spread, 0.02);
            }
        } catch (Exception ignored) {
            // Old clients / odd particles must never break damage handling.
        }
        playSoundOnly(player, stage);
    }

    private static void playSoundOnly(Player player, AttackStage stage) {
        String soundRaw = stage.swingSound();
        if (soundRaw != null && soundRaw.trim().equalsIgnoreCase("NONE")) return;
        Sound sound = parseSound(soundRaw, Sound.ENTITY_PLAYER_ATTACK_SWEEP);
        try {
            player.getWorld().playSound(player.getLocation(), sound, 0.8f, 1.0f);
        } catch (Exception ignored) {
        }
    }

    static Particle parseParticle(String raw, Particle fallback) {
        if (raw == null) return fallback;
        try {
            return Particle.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    static Sound parseSound(String raw, Sound fallback) {
        if (raw == null) return fallback;
        // Accept both UPPER_SNAKE (ENTITY_PLAYER_ATTACK_SWEEP) and
        // namespaced (minecraft:entity.player.attack.sweep) forms.
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace('.', '_').replace(':', '_');
        for (var field : Sound.class.getFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && Sound.class.isAssignableFrom(field.getType())
                    && field.getName().equalsIgnoreCase(norm)) {
                try {
                    return (Sound) field.get(null);
                } catch (IllegalAccessException e) {
                    return fallback;
                }
            }
        }
        // minecraft:entity.player.attack.sweep -> ENTITY_PLAYER_ATTACK_SWEEP
        String snake = raw.trim().toLowerCase(Locale.ROOT);
        if (snake.contains(":")) snake = snake.substring(snake.indexOf(':') + 1);
        snake = snake.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return (Sound) Sound.class.getField(snake).get(null);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Weapons that can be dual-wielded: one-handed melee, no shield/bow. */
    public static boolean isOneHandedWeapon(Material mat) {
        if (mat == null || mat.isAir()) return false;
        String n = mat.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_HOE")
                || n.equals("MACE") || n.equals("TRIDENT")
                || n.equals("STICK") || n.equals("BLAZE_ROD") || n.equals("BREEZE_ROD")) {
            return true;
        }
        return false;
    }

    public static boolean blocksOffhandUse(Material offhand) {
        if (offhand == null || offhand.isAir()) return false;
        String n = offhand.name();
        return n.equals("SHIELD") || n.equals("BOW") || n.equals("CROSSBOW")
                || n.equals("FISHING_ROD") || n.endsWith("BOAT") || n.equals("LEAD")
                || offhand.isEdible();
    }
}
