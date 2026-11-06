# Modular Summoning Enchants – Implementation Plan

## Objective
Replace EliteMobs-dependent summoning with a self-contained, modular enchant system in GlovedPlugin. The enchant is configurable via YAML, spawns vanilla / custom mob types defined in config, tames them to the player, makes them friendly, and makes their attacks act as if the player performed the attack. Damage, enchant effects, and loot/XP credit go to the player.

## Goals
* Configurable enchant profiles per item NBT tag
* Mob type, AI, tamed/friendly, lifetime, cooldown, chance, count configurable
* On spawn: mob is tamed to player, owner set, target forced to player's current target, otherwise follow player
* Attacks by summoned mobs are proxied to the player: damage is dealt by the player, preserving weapon enchants, crits, combat mechanics
* No EliteMobs API required for spawning; optional EliteMobs integration for level scaling remains available

## Architecture Overview

### Package
dev.gloved.glovedplugin.summon
* SummonConfig.java – loads `summon.yml`
* SummonProfile.java – POJO per profile
* SummonEnchant.java – NBT tag detection
* SummonManager.java – cooldown, chance, spawn orchestration
* SummonListener.java – PrePlayerAttackEntityEvent integration
* MinionAI.java – taming, target forcing, follow behavior
* DamageProxy.java – attack redirection so mob hits count as player hits
* SummonInstanceTracker.java – lifetime despawn and cleanup

### Config schema summon.yml
```yaml
enabled: true
default-profile: sword_swarm
profiles:
  sword_swarm:
    mob-type: BEE
    ai: tame_follow
    tamed: true
    friendly: true
    invulnerable: true
    chance: 0.3
    cooldown_ticks: 5
    lifetime_ticks: 200
    minions: 2
    valid-slots: [MAIN_HAND]
    target-mode: CURRENT_TARGET
    damage-proxy: true
    follow-range: 16
    attack-range: 4
```

Backfill defaults on enable, never overwrite existing keys.

## Implementation Steps

### 1. Config & Model
* Create `SummonConfig` loading `summon.yml` from resources
* `SummonProfile` with fields: mobType, ai, tamed, friendly, invulnerable, chance, cooldown, lifetime, minions, validSlots, targetMode, damageProxy, followRange, attackRange
* Register reload in `GlovedPlugin.reload()`

### 2. Enchant detection
* `SummonEnchant` reads NBT `gloved:summon_profile` string
* Validate slot via `validSlots`

### 3. Listener integration
* `SummonListener` on `PrePlayerAttackEntityEvent`
* Skip if sneaking, config disabled, slot invalid, cooldown active
* Roll chance, get profile, resolve target = event.getAttacked()
* Call `SummonManager.spawn`

### 4. Spawn flow
* `SummonManager.spawn(player, profile, target)`
* Resolve spawn location: target location if target alive, else player location
* For each minion:
  * Spawn entity via `EntityType.valueOf(profile.mobType).spawn` at offset location
  * If mob implements `Tameable`, set owner = player, set tamed true, set angry false
  * Set custom name `summon:<profile>:<playerUUID>` for tracking
  * If invulnerable: set invulnerable via metadata or Attribute.GENERIC_MAX_HEALTH + DamageResistance
  * Make friendly to player: set target to null, add to `pets` set
  * Force initial target: if targetMode=CURRENT_TARGET and target exists, set mob target to target via `setTarget`
* Track instance in `SummonInstanceTracker` with despawn task after `lifetime_ticks`

### 5. AI behavior
* `MinionAI` handles follow when no target:
  * If mob target is null or dead, set target to nearest hostile mob within `followRange` or follow player
  * Use Bukkit scheduler repeating task every 20 ticks per active instance, not per mob teleport spam
  * Lazy reposition: only move if distance > threshold
* Respect `ai` mode: `tame_follow`, `passive`, `aggressive`

### 6. Damage proxy
* `DamageProxy` listens to `EntityDamageByEntityEvent`
* If damager is a summoned minion and `damage-proxy` true:
  * Replace damager with player for damage calculation
  * Copy player's weapon damage, enchantments, crits, and apply as player damage
  * Set event.getDamager() to player via reflection or cancel event and re-apply damage with player as source
  * Preserve knockback direction from minion
  * Ensure loot/XP credit goes to player via `Player` as source
* Alternative: make minion a `Projectile` launched by player? Simpler: cancel mob damage and apply `player.attackEntity(target)` with same location

### 7. Lifetime & cleanup
* Schedule `BukkitRunnable` to remove minions after `lifetime_ticks`
* On despawn: remove entity, clean tracker, decrement active count

### 8. Commands & debug
* `/gloved summon reload`
* Debug logging gated by `/gloved debug`

## Integration with Existing Systems
* Use `GlovedPlugin.logDebug` for diagnostics
* Reuse `CombatListener` pipeline for hit validation
* Cooldowns per player per profile
* Max active summons per player cap

## Testing Checklist
* Spawn on hit with correct chance/cooldown
* Mob is tamed, friendly to player, does not attack player
* Initial target is player's current target
* No target → mob follows player
* Damage is credited to player, enchant effects apply
* Lifetime despawn works
* Config reload hot-swaps values
* Performance with multiple players

## Risks
* Damage proxy requires careful event ordering to avoid double damage
* Tameable AI may conflict with vanilla targeting; use custom task for safety
* Entity type validation: invalid mob-type must be handled gracefully

## Next Actions
* Scaffold `summon` package with classes above
* Create `summon.yml` defaults
* Implement `DamageProxy` event handling
* Add tests for spawn, target, damage credit
