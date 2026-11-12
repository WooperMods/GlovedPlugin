package dev.gloved.glovedplugin.summon;

import dev.gloved.glovedplugin.GlovedPlugin;
import dev.gloved.glovedplugin.combat.CombatTargetRules;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Hardens summon projectile/special-effect attribution:
 * <ul>
 *   <li>Resolves the credited minion/owner from direct damager, projectile shooter, or PDC tags
 *       ({@code summon_owner}, {@code summon_minion}) for cases where shooter is missing/rewritten.</li>
 *   <li>Cancels vanilla summon-sourced damage and converts it through {@link SummonAI#applyMinionHit}
 *       so soul stats, cooldown cadence, progression/ability hooks, and protections apply consistently.</li>
 *   <li>Supports fire-time cooldown consumption via projectile PDC ({@code summon_cd_consumed}) to avoid
 *       double-consume/rate-limit drops while preventing duplicate conversion of the same projectile hit.</li>
 *   <li>Tags newly launched projectiles from tracked summons so delayed impacts stay attributable.</li>
 * </ul>
 */
public class DamageProxy implements Listener {
    private static final String KEY_OWNER = "summon_owner";
    private static final String KEY_MINION = "summon_minion";
    private static final String KEY_CD_CONSUMED = "summon_cd_consumed";
    private static final String KEY_HIT_CONVERTED = "summon_hit_converted";
    // Damage applied by Gloved summon pipeline (owner-credited computed damage). Must not be cancelled/converted again.
    private static final ThreadLocal<Boolean> PIPELINE_DAMAGE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final SummonInstanceTracker tracker;
    private final SummonAI minionAI;

    public DamageProxy(SummonInstanceTracker tracker, SummonAI minionAI) {
        this.tracker = tracker;
        this.minionAI = minionAI;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        try {
            Projectile proj = event.getEntity();
            if (proj == null) return;
            GlovedPlugin plugin = plugin();
            if (plugin == null || tracker == null) return;

            LivingEntity shooter = proj.getShooter() instanceof LivingEntity le ? le : null;
            UUID ownerId = null;
            LivingEntity minion = null;

            if (shooter != null) {
                ownerId = tracker.getOwner(shooter.getUniqueId());
                if (ownerId != null) {
                    minion = shooter;
                } else {
                    ownerId = parseUuid(pdcGet(shooter, plugin, KEY_OWNER));
                    if (ownerId != null && tracker.isSummoned(shooter.getUniqueId())) {
                        minion = shooter;
                    }
                }
            }

            // Evoker fangs / some spawned effects may not set shooter consistently; inherit from owner/minion tags if present later.
            if (ownerId == null) {
                ownerId = parseUuid(pdcGet(proj, plugin, KEY_OWNER));
                UUID minionId = parseUuid(pdcGet(proj, plugin, KEY_MINION));
                if (minionId != null) {
                    Entity e = Bukkit.getEntity(minionId);
                    if (e instanceof LivingEntity le && tracker.isSummoned(minionId)) {
                        minion = le;
                        if (ownerId == null) ownerId = tracker.getOwner(minionId);
                    }
                }
            }

            if (ownerId == null || minion == null) return;

            pdcSetString(proj, plugin, KEY_OWNER, ownerId.toString());
            pdcSetString(proj, plugin, KEY_MINION, minion.getUniqueId().toString());
            boolean pendingConsumed = false;
            try {
                if (minionAI != null) {
                    pendingConsumed = minionAI.pollPendingProjectileCooldownConsumed(minion.getUniqueId(), Bukkit.getCurrentTick());
                    if (pendingConsumed) {
                        markProjectileCooldownConsumed(proj, plugin);
                    }
                }
            } catch (Throwable ignored) {}

            // Ensure projectile shooter remains attributable: prefer the summon entity; if shooter is null, set to minion.
            try {
                if (proj.getShooter() == null) {
                    proj.setShooter(minion);
                }
            } catch (Throwable ignored) {}

            if (DebugLogSafe.isEnabled()) {
                GlovedPlugin.logDebug("[Summon] Tagged summon projectile launch type=" + proj.getType()
                        + " owner=" + ownerId + " minion=" + minion.getUniqueId() + " shooter=" + (shooter != null ? shooter.getType() : "null")
                        + " cdConsumedAtFire=" + pendingConsumed);
            }
        } catch (Throwable t) {
            GlovedPlugin plugin = plugin();
            if (plugin != null) plugin.getLogger().log(Level.WARNING, "[Summon] Projectile launch tagging failed (suppressed)", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        try {
            GlovedPlugin plugin = plugin();
            if (plugin == null || tracker == null) return;

            // Pipeline-applied owner-credited damage must always pass through, even if attribution
            // cannot be resolved from the event's damager/source in this context.
            try {
                if (Boolean.TRUE.equals(PIPELINE_DAMAGE.get())) {
                    if (DebugLogSafe.isEnabled()) {
                        GlovedPlugin.logDebug("[Summon] Pipeline-credited summon hit allowed through (marker) damager="
                                + safeType(event.getDamager()) + " target=" + safeType(event.getEntity())
                                + " damage=" + event.getDamage());
                    }
                    return;
                }
            } catch (Throwable ignored) {}

            Projectile projectile = event.getDamager() instanceof Projectile p ? p : null;
            Entity rawDamager = event.getDamager();

            ResolvedAttribution attr = resolveAttribution(rawDamager, projectile, plugin);
            if (attr == null || attr.ownerId == null) return;

            Player owner = Bukkit.getPlayer(attr.ownerId);
            LivingEntity target = event.getEntity() instanceof LivingEntity ? (LivingEntity) event.getEntity() : null;
            DamageSource source = event.getDamageSource();

            UUID causingId = null;
            try {
                Entity causingEnt = source != null ? source.getCausingEntity() : null;
                causingId = causingEnt != null ? causingEnt.getUniqueId() : null;
            } catch (Throwable ignored) {}
            // Backward-compat: allow through if an attributed summon hit is explicitly owner-caused.
            if (causingId != null && causingId.equals(attr.ownerId)) {
                if (DebugLogSafe.isEnabled() && owner != null) {
                    GlovedPlugin.logDebug("[Summon] Owner-caused summon-attributed hit allowed through for " + owner.getName()
                            + " damager=" + safeType(rawDamager) + " via=" + attr.via
                            + " target=" + safeType(event.getEntity()) + " damage=" + event.getDamage());
                }
                return;
            }

            // Direct non-projectile summon melee/contact: cancel vanilla and let SummonAI-driven systems apply.
            if (projectile == null && !(rawDamager instanceof EvokerFangs) && !(rawDamager instanceof AreaEffectCloud)) {
                event.setCancelled(true);
                if (DebugLogSafe.isEnabled()) {
                    GlovedPlugin.logDebug("[Summon] Cancelled vanilla summon damage for " + (owner != null ? owner.getName() : attr.ownerId)
                            + " damager=" + safeType(rawDamager) + " direct=" + safeType(source != null ? source.getDirectEntity() : null)
                            + " causing=" + (causingId != null ? causingId : "null")
                            + " via=" + attr.via + " target=" + safeType(event.getEntity()) + " damage=" + event.getDamage());
                }
                return;
            }

            // Projectile / attributable special entity hit: convert through summon pipeline.
            event.setCancelled(true);
            if (owner == null || !owner.isOnline() || minionAI == null || target == null || attr.minion == null) {
                if (DebugLogSafe.isEnabled() && owner != null) {
                    GlovedPlugin.logDebug("[Summon] Ignored summon-attributed hit (missing context) owner=" + owner.getName()
                            + " target=" + safeType(event.getEntity()) + " minion=" + (attr.minion != null) + " via=" + attr.via);
                }
                return;
            }
            if (CombatTargetRules.isProtected(plugin, owner, target)) {
                if (DebugLogSafe.isEnabled()) {
                    GlovedPlugin.logDebug("[Summon] Blocked summon-attributed hit by protection owner=" + owner.getName()
                            + " target=" + safeType(target) + " via=" + attr.via);
                }
                return;
            }

            long now = Bukkit.getCurrentTick();
            boolean fireConsumed = projectile != null && pdcHasByte(projectile, plugin, KEY_CD_CONSUMED);
            boolean convertedOnce = projectile != null && pdcHasByte(projectile, plugin, KEY_HIT_CONVERTED);

            // Prevent duplicate conversion for multi-event/explosion-like edge cases on the same projectile entity.
            if (convertedOnce) {
                if (DebugLogSafe.isEnabled()) {
                    GlovedPlugin.logDebug("[Summon] Skipping already-converted summon projectile hit owner=" + owner.getName()
                            + " projectile=" + projectile.getType() + " target=" + safeType(target));
                }
                return;
            }

            boolean canApply;
            if (fireConsumed) {
                canApply = true;
            } else {
                canApply = minionAI.tryConsumeAttackCooldown(attr.minion, owner, now);
            }
            if (!canApply) {
                if (DebugLogSafe.isEnabled()) {
                    GlovedPlugin.logDebug("[Summon] Rate-limited summon projectile/effect hit owner=" + owner.getName()
                            + " minion=" + attr.minion.getType() + " damager=" + safeType(rawDamager)
                            + " target=" + safeType(target) + " via=" + attr.via + " fireConsumed=" + fireConsumed);
                }
                return;
            }

            if (projectile != null) {
                try { pdcSetByte(projectile, plugin, KEY_HIT_CONVERTED, (byte) 1); } catch (Throwable ignored) {}
            }
            // Unified per-summon attack/damage helper: computes soul-stat damage, crits/abilities/procs,
            // progression hooks, and applies pipeline-marked summon-sourced damage.
            minionAI.performSummonAttack(attr.minion, owner, target, now,
                    SummonAI.AttackOptions.defaults()
                            .profileName(null)
                            .rawBaseDamage(0.0));
            if (DebugLogSafe.isEnabled()) {
                GlovedPlugin.logDebug("[Summon] Converted summon hit owner=" + owner.getName()
                        + " minion=" + attr.minion.getType() + " damager=" + safeType(rawDamager)
                        + " target=" + safeType(target) + " via=" + attr.via
                        + " fireConsumed=" + fireConsumed);
            }
        } catch (Throwable t) {
            GlovedPlugin plugin = plugin();
            if (plugin != null) plugin.getLogger().log(Level.WARNING, "[Summon] Error in DamageProxy (suppressed to avoid kick)", t);
        }
    }

    private ResolvedAttribution resolveAttribution(Entity damager, Projectile projectile, GlovedPlugin plugin) {
        LivingEntity shooter = null;
        if (damager instanceof LivingEntity le) {
            shooter = le;
        } else if (projectile != null && projectile.getShooter() instanceof LivingEntity le) {
            shooter = le;
        }

        // 1) Direct summon shooter/damager is tracked.
        if (shooter != null) {
            UUID ownerId = tracker.getOwner(shooter.getUniqueId());
            if (ownerId != null) return new ResolvedAttribution(ownerId, shooter, "shooter-tracked");
            UUID ownerTag = parseUuid(pdcGet(shooter, plugin, KEY_OWNER));
            if (ownerTag != null && tracker.isSummoned(shooter.getUniqueId())) {
                return new ResolvedAttribution(ownerTag, shooter, "shooter-owner-tag");
            }
        }

        // 2) Projectile/effect PDC tags (works when shooter is owner player, null, or remapped).
        if (projectile != null) {
            ResolvedAttribution fromProj = fromTaggedEntity(projectile, plugin, "projectile-tag");
            if (fromProj != null) return fromProj;
        } else if (damager != null) {
            ResolvedAttribution fromDamager = fromTaggedEntity(damager, plugin, "damager-tag");
            if (fromDamager != null) return fromDamager;
        }

        // 3) Area effect cloud source (lingering potions, dragon-breath-like effects) may expose source as projectile/owner.
        if (damager instanceof AreaEffectCloud cloud) {
            try {
                Object src = cloud.getSource();
                if (src instanceof java.util.UUID id) {
                    Entity e = Bukkit.getEntity(id);
                    if (e != null) {
                        if (e instanceof Projectile p) return resolveAttribution(p, p, plugin);
                        ResolvedAttribution a = resolveAttribution(e, e instanceof Projectile p2 ? p2 : null, plugin);
                        if (a != null) return a;
                    }
                } else if (src instanceof LivingEntity le) {
                    ResolvedAttribution a = resolveAttribution(le, null, plugin);
                    if (a != null) return a;
                } else if (src instanceof Projectile proj) {
                    ResolvedAttribution a = resolveAttribution(proj, proj, plugin);
                    if (a != null) return a;
                }
            } catch (Throwable ignored) {}
        }

        // 4) Evoker fangs: try owner (evoker) entity.
        if (damager instanceof EvokerFangs fangs) {
            try {
                LivingEntity ownerEnt = fangs.getOwner();
                if (ownerEnt != null) {
                    UUID oid = tracker.getOwner(ownerEnt.getUniqueId());
                    if (oid != null) return new ResolvedAttribution(oid, ownerEnt, "fangs-owner");
                    UUID ownerTag = parseUuid(pdcGet(ownerEnt, plugin, KEY_OWNER));
                    if (ownerTag != null && tracker.isSummoned(ownerEnt.getUniqueId())) {
                        return new ResolvedAttribution(ownerTag, ownerEnt, "fangs-owner-tag");
                    }
                }
            } catch (Throwable ignored) {}
            ResolvedAttribution tagged = fromTaggedEntity(fangs, plugin, "fangs-tag");
            if (tagged != null) return tagged;
        }

        // 5) Tameable/owned entities (vex-like summons spawned by spells in edge cases): attribute only if tracked.
        if (damager instanceof Tameable tame) {
            try {
                if (tame.getOwner() instanceof Player p) {
                    UUID tracked = tracker.getOwner(damager.getUniqueId());
                    if (tracked != null && tracked.equals(p.getUniqueId()) && damager instanceof LivingEntity le) {
                        return new ResolvedAttribution(tracked, le, "tameable-tracked");
                    }
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private ResolvedAttribution fromTaggedEntity(Entity entity, GlovedPlugin plugin, String via) {
        if (entity == null) return null;
        UUID ownerId = parseUuid(pdcGet(entity, plugin, KEY_OWNER));
        UUID minionId = parseUuid(pdcGet(entity, plugin, KEY_MINION));
        if (ownerId == null && minionId == null) return null;

        LivingEntity minion = null;
        if (minionId != null) {
            Entity e = Bukkit.getEntity(minionId);
            if (e instanceof LivingEntity le) minion = le;
            if (ownerId == null) ownerId = tracker.getOwner(minionId);
        }
        if (minion == null && projectileOwnerFallback(entity, plugin) instanceof LivingEntity le) {
            minion = le;
            if (ownerId == null) ownerId = tracker.getOwner(le.getUniqueId());
        }
        if (minion == null || ownerId == null) return null;
        if (!tracker.isSummoned(minion.getUniqueId())) return null;
        UUID trackedOwner = tracker.getOwner(minion.getUniqueId());
        if (trackedOwner == null || !trackedOwner.equals(ownerId)) return null;
        return new ResolvedAttribution(ownerId, minion, via);
    }

    private Entity projectileOwnerFallback(Entity entity, GlovedPlugin plugin) {
        if (entity instanceof Projectile proj && proj.getShooter() instanceof LivingEntity le) {
            return le;
        }
        return null;
    }

    private static String pdcGet(Entity entity, GlovedPlugin plugin, String key) {
        try {
            if (entity == null || plugin == null) return null;
            PersistentDataContainer pdc = entity.getPersistentDataContainer();
            return pdc.get(new NamespacedKey(plugin, key), PersistentDataType.STRING);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void pdcSetString(Entity entity, GlovedPlugin plugin, String key, String value) {
        try {
            if (entity == null || plugin == null || value == null) return;
            entity.getPersistentDataContainer().set(new NamespacedKey(plugin, key), PersistentDataType.STRING, value);
        } catch (Throwable ignored) {}
    }

    private static void pdcSetByte(Entity entity, GlovedPlugin plugin, String key, byte value) {
        try {
            if (entity == null || plugin == null) return;
            entity.getPersistentDataContainer().set(new NamespacedKey(plugin, key), PersistentDataType.BYTE, value);
        } catch (Throwable ignored) {}
    }

    private static boolean pdcHasByte(Entity entity, GlovedPlugin plugin, String key) {
        try {
            if (entity == null || plugin == null) return false;
            Byte b = entity.getPersistentDataContainer().get(new NamespacedKey(plugin, key), PersistentDataType.BYTE);
            return b != null && b != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markProjectileCooldownConsumed(org.bukkit.entity.Projectile projectile, GlovedPlugin plugin) {
        if (projectile == null || plugin == null) return;
        try { pdcSetByte(projectile, plugin, KEY_CD_CONSUMED, (byte) 1); } catch (Throwable ignored) {}
    }

    public static void tagSummonProjectile(org.bukkit.entity.Projectile projectile, GlovedPlugin plugin,
                                           UUID ownerId, UUID minionId) {
        if (projectile == null || plugin == null) return;
        try {
            if (ownerId != null) pdcSetString(projectile, plugin, KEY_OWNER, ownerId.toString());
            if (minionId != null) pdcSetString(projectile, plugin, KEY_MINION, minionId.toString());
        } catch (Throwable ignored) {}
    }

    /** Marks damage application on the current thread as Gloved summon pipeline damage (bypass cancel/convert guards). */
    public static void beginPipelineDamage() {
        try { PIPELINE_DAMAGE.set(Boolean.TRUE); } catch (Throwable ignored) {}
    }

    public static void endPipelineDamage() {
        try { PIPELINE_DAMAGE.remove(); } catch (Throwable ignored) {}
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static String safeType(Entity e) {
        try { return e == null ? "null" : e.getType().name(); } catch (Throwable t) { return "unknown"; }
    }

    private static GlovedPlugin plugin() {
        try { return GlovedPlugin.getPlugin(GlovedPlugin.class); } catch (Throwable t) { return null; }
    }

    /** Tiny shim so call sites can check debug state without hard dependency shape changes. */
    private static final class DebugLogSafe {
        static boolean isEnabled() {
            try { return dev.gloved.glovedplugin.DebugLog.isEnabled(); } catch (Throwable t) { return false; }
        }
    }

    private record ResolvedAttribution(UUID ownerId, LivingEntity minion, String via) {}
}

