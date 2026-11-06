# EliteMobs Summoning Module – Implementation Plan

## Overview
Add a Java-based summoning system to GlovedPlugin that spawns EliteMobs bosses/minions on player actions, mirroring the Sword Swarm enchant behavior but implemented directly in Java with EliteMobs API integration.

## Goals
* Spawn EliteMobs bosses/minions on hit / right-click from a tagged item
* Target-aware spawn: attack player's current target if present, otherwise follow player until target found
* Lifetime, cooldown, chance, minion count configurable
* Damage scales with elite weapon level
* Minions are invulnerable to players, AI enabled with forced target, friendly to summoner
* Integrates with existing GlovedPlugin combat pipeline and config reload system

## Architecture

### Package Structure
```
dev.gloved.glovedplugin.elitesummon
  EliteSummonPlugin.java          # lifecycle hooks, config load
  SummonConfig.java               # loads summon.yml
  SummonEnchant.java              # NBT tag detection, item validation
  SummonManager.java              # cooldowns, chance rolls, spawn orchestration
  SummonListener.java             # CombatListener integration
  EliteMobsBridge.java            # direct EliteMobs API wrapper, replaces reflection
  MinionPowers.java               # Java-driven minion behavior loop
  model/SummonProfile.java        # per-enchant config
  model/SummonInstance.java       # active summon tracking
```

## Integration Points

### Existing Systems
* **CombatListener** – Use `PrePlayerAttackEntityEvent` already handled. Hook into same event to check for summon tag after hit validation.
* **WeaponRegistry** – Use elite weapon level from `WeaponRegistry` or `EliteMobsIntegration.getCombatLevel` for scaling.
* **DebugLog** – Gate logs with `/gloved debug`
* **Config reload** – Follow pattern from `MenuConfig` and `LootrConfig`: reload on `/gloved reload`

### EliteMobs Integration
Replace reflection with direct compile-time API where possible. `build.gradle` already has `compileOnly com.magmaguy:EliteMobs:10.9.10`
* `com.magmaguy.elitemobs.api.EliteMobsAPI` – spawn boss
* `com.magmaguy.elitemobs.mobconstructor.EliteEntity` – control entity
* `setOwner(UUID)`, `setInvulnerable(boolean)`, `forceTarget`, `addThreat`
* Use `EliteMobsIntegration` as base for reflection fallback

## Config – summon.yml

```yaml
enabled: true
default-profile: sword_swarm
profiles:
  sword_swarm:
    boss-file: swarm_minion.yml
    chance: 0.3
    cooldown_ticks: 5
    lifetime_ticks: 200
    minions: 2
    damage-base: 1.0
    damage-per-level: 0.3
    interval_ticks: 30
    valid-slots: [MAIN_HAND]
    target-mode: CURRENT_TARGET # or PLAYER
    invulnerable: true
    disable-ai: false
    force-target-on-spawn: true
```

Backfill defaults on enable, never overwrite existing keys.

## Implementation Steps

### 1. Config & Model
* Create `SummonConfig.java` loading `summon.yml` from resources
* `SummonProfile` POJO with validation
* Register config reload in `GlovedPlugin.reload()`

### 2. Enchant Detection
* `SummonEnchant` reads item NBT `gloved:summon_profile` string
* Validate slot via `valid-slots`

### 3. Listener
* `SummonListener implements Listener`
* Listen to `PrePlayerAttackEntityEvent` from CombatListener pipeline
* Check item, cooldown via `SummonManager.playerCooldowns`
* Roll chance, get weapon elite level from `WeaponRegistry` / `EliteMobsIntegration.getCombatLevel`
* Determine spawn location: target location if target alive else player location

### 4. Spawn Flow
* `SummonManager.spawn(profile, player, target, level)`
* For i=1..minions:
  * Offset spawn location randomly +-1 block
  * EliteMobs API spawn: `EliteMobsAPI.spawnBoss(profile.boss-file, location, level)`
  * Set owner, invulnerable
  * If `force-target-on-spawn` and target exists: `EliteEntity.forceTarget(targetLivingEntity)` or `addThreat` + set mob target
  * If no target: set mob target to player for follow, or leave AI to acquire nearest hostile mob automatically
  * Tag entity with summon instance metadata
  * Schedule despawn via Bukkit scheduler after `lifetime_ticks`
* Store `SummonInstance` with player UUID, boss UUIDs, target UUID

### 5. Minion Behavior
Use EliteMobs native AI instead of Java teleport loops.
* On spawn, set owner and invulnerable
* If target exists: force target via `EliteEntity.forceTarget` / `setTarget` and add threat so AI engages immediately
* If no target: leave AI enabled, minion will follow owner via vanilla tameable AI or `setOwner` makes it follow player. Once player acquires a target, the minion will acquire it naturally via EliteMobs threat system
* Lifetime despawn handles cleanup
* Optional Java watchdog to re-apply forced target if player switches targets, but avoid teleport spam

### 6. Cooldown & Limits
* Per-player cooldown map `Map<UUID, Long>`
* Max active summons per player cap to prevent lag

### 7. Commands
* `/gloved summon reload`
* Optional `/gloved summon debug` toggle

## Files to Create / Modify

* `src/main/java/dev/gloved/glovedplugin/elitesummon/...` package with classes above
* `src/main/resources/summon.yml`
* `GlovedPlugin.java` – init `SummonConfig` and `SummonManager`, register `SummonListener`
* `paper-plugin.yml` – add softdepend already present

## Testing Checklist
* Spawn on hit with correct chance/cooldown
* Minions attack current target via AI, not player
* No target → minions follow player via native tame AI
* Invulnerable to player damage
* Lifetime despawn works
* Damage scales with weapon elite level via EliteMobs level
* Config reload hot-swaps values
* Debug logging works

## Risks
* EliteMobs API version drift – keep reflection fallback for spawn/owner/invulnerable/forceTarget
* Performance: limit minions per player, rely on AI not Java teleport loops
* Target switching: ensure forced target updates if player retargets quickly

## Next Actions
* Scaffold package and config
* Implement EliteMobsBridge with direct API + reflection fallback
* Integrate listener with CombatListener pipeline
* Add summon.yml defaults and reload support
