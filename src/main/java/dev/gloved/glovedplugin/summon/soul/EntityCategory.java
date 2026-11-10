package dev.gloved.glovedplugin.summon.soul;

import org.bukkit.entity.*;

import java.util.Locale;

/** Coarse category derived from EntityType/class for default balancing and UI. */
public enum EntityCategory {
    PASSIVE,
    NEUTRAL,
    HOSTILE,
    FLYING,
    AQUATIC,
    AMBIENT,
    BOSS,
    SPECIAL;

    public static EntityCategory of(EntityType type) {
        if (type == null) return SPECIAL;
        // Boss/high-risk by type name (class checks vary by API; keep simple/minimal)
        String n = type.name().toUpperCase(Locale.ROOT);
        if (n.equals("ENDER_DRAGON") || n.equals("WITHER") || n.equals("WARDEN") || n.equals("ELDER_GUARDIAN")) {
            return BOSS;
        }
        Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null) return SPECIAL;
        // Order matters: more specific first
        if (WaterMob.class.isAssignableFrom(cls) || Fish.class.isAssignableFrom(cls) || Squid.class.isAssignableFrom(cls)
                || n.contains("DOLPHIN") || n.contains("TURTLE") || n.contains("AXOLOTL") || n.contains("GUARDIAN")) {
            return AQUATIC;
        }
        if (Flying.class.isAssignableFrom(cls) || n.equals("BEE") || n.equals("PARROT") || n.equals("BAT")
                || n.equals("PHANTOM") || n.equals("ALLAY") || n.equals("VEX") || n.equals("GHAST") || n.equals("BLAZE")) {
            return FLYING;
        }
        if (Monster.class.isAssignableFrom(cls) || Slime.class.isAssignableFrom(cls) || n.contains("SLIME") || n.contains("MAGMA")) {
            return HOSTILE;
        }
        if (Animals.class.isAssignableFrom(cls) || n.equals("VILLAGER") || n.equals("WANDERING_TRADER")) {
            // Some neutral-ish animals exist, but minimal config: treat Animals as PASSIVE baseline
            return PASSIVE;
        }
        if (Ambient.class.isAssignableFrom(cls)) {
            return AMBIENT;
        }
        // Golems/neutral mobs
        if (n.contains("GOLEM") || n.equals("WOLF") || n.equals("LLAMA") || n.equals("PANDA") || n.equals("BEE")) {
            return NEUTRAL;
        }
        return SPECIAL;
    }
}
