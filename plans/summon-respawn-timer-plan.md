# Summon Respawn Timer + Kill-Charge — Full Implementation Plan

> **Status:** Planned (not implemented).  
> **Scope:** Add per-summon respawn timers for active Soul Group slots (auto-respawn into the same slot), introduce respawn as a rolled base soul stat, replace **COMMANDER / Cost Reduction** with a new respawn-time affix, and add kill-charge acceleration with subtle non-UI VFX/SFX feedback.  
> **Build/deploy:** `./build.sh` only (never run Gradle directly).  
> **Repo conventions:** Conventional Commits; config keys backfilled on enable/reload without overwriting existing keys (update bundled resource + runtime fallback + `GlovedPlugin` backfill + live server yml for immediate effect when implemented); additive DB migrations only; back up `souls.db` (+ `-wal`/`-shm`) before schema/backfill changes.

---

## 1. Goals and product decisions

### 1.1 Goals
- **Meaningful downtime:** when a summon in an active Soul Group dies, it does not instantly return.
- **Rarity-differentiated respawn stat:** respawn time is a rolled/stored/displayed soul stat with clear **COMMON → MYTHIC** separation and substantial downtime at all rarities (especially Mythic/high-stat souls).
- **Affix rework:** remove **COMMANDER / Cost Reduction** and replace it with a new respawn-time affix that reduces respawn downtime (bounded).
- **Kill-charge acceleration:** qualifying kills by the **owner** or their **summons** "charge" pending/dead souls in the active group, reducing remaining respawn time by a small configurable amount with distinct VFX/SFX.
- **Immersive non-UI feedback:** subtle VFX/SFX (no HUD/chat/action-bar/bossbar dependency) communicate summon death, pending-respawn state, and respawn completion.
- **Safety/compat:** no duplicate respawns, no stuck pending state, clean cancellation, additive migration/backfill for legacy DB rows and soul items, and no regressions to non-team summon flows.

### 1.2 Locked behavior decisions (from discussion)
1. **Auto-respawn in same slot:** dead summon in an active group enters pending state and auto-respawns into the **same `(teamIndex, slotIndex)`** after effective timer if group/slot remains valid and active.
2. **Affix replacement:** remove COMMANDER/Cost Reduction; add a new respawn-time affix.
3. **Respawn timer is a new base/stat field:** rolled per soul, stored/migrated, shown in lore/UI/stats, and used as base for effective respawn time.
4. **Kill-charge:** owner/summon qualifying kills reduce remaining respawn time for all currently pending/dead souls in that owner’s active group.
5. **Rarity bands overlap** and **Common max base respawn is ~1:30 (90s)**.
6. **Feedback:** subtle, immersive, non-UI VFX/SFX for death/pending/respawn-complete (plus kill-charge VFX).

### 1.3 Open decisions to finalize during implementation (defaults proposed)
| Decision | Proposed default | Notes |
|---|---|---|
| New affix enum id/name | `REAWAKENING` / "Respawn Reduction" | Must support migration from legacy `COMMANDER` string; finalize naming consistently in code/lore/config |
| Legacy COMMANDER conversion | Rename/map legacy `COMMANDER` roll -> new respawn affix with clamped value reinterpreted as respawn reduction | Preserves item/soul investment; alternative: reroll respawn affix on migration (not recommended) |
| Enhancement effect on respawn stat | Enhancement **reduces** respawn time modestly (same per-level bonus model, inverse application) but never below global floor | Must be explicit in `SoulBaseStats`/lore; configurable |
| Respawn persistence across logout/restart | **Ephemeral** (cancel/clear pending respawns on quit, recall, disable/reload unload paths) | Safer/simpler; persistence can be added later |
| Kill eligibility | PvE mob kills credited to owner or owner’s summoned minion; exclude players by default (configurable) | Hook into existing kill attribution; add anti-farm caps/cooldowns |
| Owner/summon offline/world/invalid at due time | Retry briefly (bounded) then cancel pending slot safely | No stuck state, no duplicates |

---

## 2. Current baseline and integration points

- **Soul Groups/Teams (1 summon/slot):** `summon/runtime/SummonManager` (`spawnResolvedTeam`, `TeamSlotSpawn`, team-tagged spawns), `summon/runtime/SummonInstanceTracker` (active entities, team index/slot tags `summon_team_index`, `summon_team_slot`, `summon_soul_id`, owner/profile/family tags), `summon/soul/service/SoulService` (team loadouts/selection/cast/activation), `summon/soul/ui/SoulTeamsInv`.
- **Death/cleanup:** `summon/runtime/SummonListener#onEntityDeath` -> `tracker.despawn(entityId)` (no respawn). Minion-attributed damage/kill systems and soul trait hooks live in `SummonListener`, `SummonCombatService`, `SummonAbilityService`, `SummonKillAttributionListener`, progression bridge.
- **Kill attribution/progression:** `SummonKillAttributionListener` -> `SummonProgressionBridge#onEntityDeath`; `BaseSummonProgressionBridge` resolves kill credit from `SummonXpState`, grants kill XP, and invokes soul mastery kill hook when `soulId` is credited. `SummonCombatService`/minion hit paths record kill credit and resolve `summon_soul_id`.
- **Soul domain/persistence:**
  - Domain: `summon/soul/domain/*` (`SoulInstance`, `SoulBaseStats`, `SoulAffixType`, `SoulAffixRoll`, `SoulStatSnapshot`, `SoulGenerator`, `SoulQuality`, `SoulRarity`, `SoulConfig`, table/stat configs).
  - DB: `summon/soul/db/SoulDatabase` (`souls.db`, additive `addColumnIfMissing` migrations, tables `souls`, `soul_loadouts`, `soul_player_state`, `soul_codex`, `soul_tables`, `soul_teams`), `summon/soul/db/SoulRepository`.
  - Item/PDC: `summon/soul/item/SoulDropItem` (serialized soul DTO in PDC), `SoulLoreBuilder`.
  - UI/flows: `SoulTeamsInv`, `SoulVaultInv`/handlers, `SoulTable*` services/UIs, guide/info commands.
- **Affix/COMMANDER today:** `SoulAffixType.COMMANDER` ("Cost Reduction") -> `SoulStatSnapshot.costEfficiency()` (1.0 - commander, clamped) -> used by `SoulService#computeCastCooldownTicks` and `#computeCastManaCost`. Generator/quality/guide/lore filter and value-cap COMMANDER.
- **VFX:** `summon/vfx/SummonVfx`, `VfxEffect`, `ParticleStep` (spawn/despawn/crit/proc presets) — extend for death/pending/respawn/kill-charge cues.
- **Config/backfill:** `src/main/resources/summon.yml` (bundled), runtime loading/fallbacks in summon/soul config classes, and backfill in `core/GlovedPlugin`.

---

## 3. Target architecture and responsibilities

| Area | Primary responsibility | New/changed classes (proposed) |
|---|---|---|
| Domain/stat model | Respawn base stat, affix model, snapshot math | `SoulBaseStats`, `SoulAffixType`, `SoulAffixRoll` (usage), `SoulStatSnapshot`, `SoulInstance` (if needed), `SoulGenerator`, `SoulQuality` |
| Persistence/migration | Additive schema, JSON/DTO fields, legacy COMMANDER + missing respawn stat migration | `SoulDatabase`, `SoulRepository`, `SoulDropItem`, `SoulLoreBuilder`, any soul cache/invalidation paths in `SoulService` |
| Config | Bands/caps/affix ranges/kill-charge/VFX toggles, backfill | `SoulConfig` / `SoulTableConfig.SoulStatConfig` (or dedicated respawn config), `SummonConfig`, `summon.yml`, `GlovedPlugin` backfill |
| Runtime respawn | Pending state, scheduling, death hook, respawn spawn, cancellation | **New:** `summon/runtime/SummonRespawnService` (+ inner state records), edits in `SummonManager`, `SummonListener`, `SummonInstanceTracker` integration points, `GlovedPlugin` lifecycle wiring |
| Kill-charge | Detect qualifying kills and reduce pending timers | `BaseSummonProgressionBridge`/kill-credit hook or dedicated listener/service callback, `SummonRespawnService` API |
| VFX/SFX | Death/pending/respawn-complete/kill-charge cues | `summon/vfx/SummonVfx` (+ builders/presets), optional small helper in respawn service |
| UI/lore/guide | Display respawn stat/affix and optional pending status | `SoulLoreBuilder`, `SoulTeamsInv`, soul guide/table text, inspect/command formatting |
| Validation | Build/tests | `./build.sh`, manual/integration checklist (section 13) |

---

## 4. Domain and stat/affix implementation details

### 4.1 New respawn affix (replace COMMANDER)

**`summon/soul/domain/SoulAffixType.java`**
- Replace `COMMANDER` with new enum constant (proposed: `REAWAKENING`) and update display/format metadata:
  - Friendly name: `"Respawn Reduction"` (or `"Soul Recovery"`), format as `+X% Respawn Reduction`.
  - Ensure `format(...)` and any UI strings reflect respawn-time reduction, not cost/mana/cooldown.
- **Compatibility:** enum rename breaks `valueOf` for persisted `"COMMANDER"`. Add a parsing helper (e.g. `parse(String)` / `fromStored(String)`) returning new type for `"COMMANDER"` and normal values for others; use it in **all** deserialization paths (`SoulDropItem`, `SoulRepository` affix JSON parsing, any config/data loaders). Do not rely on raw `valueOf` for stored data.
- Keep affix semantics bounded (fraction 0..cap) consistent with generator/quality/snapshot.

**`summon/soul/domain/SoulStatSnapshot.java`**
- Replace COMMANDER/cost-efficiency respawn-irrelevant behavior:
  - Option A (recommended): replace `costEfficiency` with `respawnReduction` (0..cap, where 0 = none) **and** keep a neutral cast-cost behavior (effective cost efficiency = 1.0) since COMMANDER is removed.
  - Option B: keep `costEfficiency` field for binary/source compatibility but always compute as `1.0` and add `respawnReduction` (or `respawnMultiplier`); update all call sites.
- Compute from new affix: e.g. `respawnReduction = clamp(newAffixValue, 0, cap)` or `respawnMultiplier = 1.0 - respawnReduction`.
- `identity()` and `fromSoul(null)` must return neutral respawn modifier (no reduction).

**`summon/soul/domain/SoulQuality.java`**
- Remove COMMANDER-specific caps/ranges and add caps/ranges for the new respawn affix in `maxFor(...)` / roll-percent logic so quality integrates correctly with rarity (similar bounded special handling as SWIFT/COMMANDER had, tuned for respawn reduction).

**`summon/soul/domain/SoulGenerator.java`**
- Replace all `COMMANDER` pool/filter/cap logic with new respawn affix:
  - `rollAffixes(...)` / pool construction: remove `allowCommander` semantics tied to cost reduction relevance; new affix should be generally rollable (or gated by config if desired) independent of cast mana/cooldown enablement.
  - Remove/repurpose `isCostReductionRelevant(...)` usage for affix inclusion unless still needed for unrelated reasons.
  - Apply rarity-appropriate value caps for new respawn affix.
- `rollBaseStats(...)` / generation must also roll and attach the new **respawn base stat** (see 4.2).
- Update `rollAffixesForRarity(...)` overloads and any compatibility signatures accordingly.

**Cast cost/cooldown call sites (`summon/soul/service/SoulService`)**
- `computeCastCooldownTicks(...)` and `computeCastManaCost(...)` currently multiply by `snap.costEfficiency()` (and apply dampening): change to neutral behavior (no COMMANDER reduction), e.g. multiplier = `1.0` or remove costEfficiency factor while preserving category/rarity multipliers as intended.
- Audit all `costEfficiency()` usages in `summon` module and remove/neutralize.

### 4.2 Respawn timer as a new base soul stat

**`summon/soul/domain/SoulBaseStats.java`**
- Add respawn stat field (recommended: `double respawnSeconds` stored as base seconds; alternatively ticks but seconds are clearer for lore/config).
- Define constants/keys:
  - `KEY_RESPAWN = "respawn"`, `MIN_RESPAWN_SECONDS` (safety), global bounds/caps from config.
- Update record components, `NEUTRAL`, constructors/factories, `fromEntity`/`defaults` (use config/default band midpoint or sensible fallback; entity attributes do not provide respawn), normalization, capping, band sampling in `roll(...)`, `sample(...)`, `enhanced(...)`, `clampFinal(...)`, and all stat-key iteration/switch paths.
- **Enhancement interaction (proposed):** respawn time improves (decreases) with enhancement, with diminishing/bounded effect and never below global floor:
  - e.g. `effectiveRespawn = max(floor, baseRespawn / enhancementFactorOrBoundedReduction)` or apply a bounded reduction factor similar in spirit to other enhanced stats but inverse for time.
  - Keep base rolled/capped by rarity band; enhancement may exceed "rarity speed" within global safety bounds only if explicitly allowed (recommended: allow improvement but clamp final effective >= global floor).
- **JSON:** extend `StatsDto` with respawn field and update `toJson()` / `fromJson()`:
  - Legacy payloads missing respawn must parse with `respawnSeconds <= 0` or sentinel so migration/backfill can detect and assign a rarity-band/default value (do not silently force Common value for all).

**Rarity bands and config (`SoulTableConfig.SoulStatConfig` / soul stat config model)**
- Add respawn stat band definitions per rarity using overlapping proposed defaults:
  - Common 75–90, Uncommon 64–80, Rare 52–68, Epic 42–56, Legendary 34–46, Mythic 28–38 (seconds), tunable.
- Add global min/max/floor/ceil keys for respawn (including effective floor, proposed 10s).

**`summon/soul/domain/SoulInstance.java`**
- No structural field required if respawn lives only in `SoulBaseStats`, but ensure copy/`with*`/serialization paths preserve `baseStats` correctly (already true). Add convenience accessor if useful (e.g. `getBaseRespawnSeconds()` delegating to base/effective stats).

**Lore/display (`summon/soul/item/SoulLoreBuilder`, inspect/formatters)**
- Add respawn timer line to stats section (e.g. `§d⏳ Respawn: §f34.5s` or similar existing style), showing effective/base+modifiers consistently with other stats.
- Update affix rendering to show new respawn affix and remove COMMANDER-specific presentation assumptions.
- `SoulDropItem#getDisplayName`/refresh paths unchanged except they rely on updated lore/stats.

### 4.3 Item/DB serialization and migration

**`summon/soul/item/SoulDropItem.java`**
- `serialize(...)`: no DTO shape break required beyond affix type names and base-stats JSON now containing respawn.
- `deserialize(...)`: use affix parse helper mapping `"COMMANDER"` -> new respawn affix; tolerate missing/legacy respawn in `baseStats` JSON by backfilling/falling back (rarity-aware if possible from parsed rarity, else defaults) so old dropped soul items remain loadable.
- On read/refresh of legacy items, consider opportunistic in-memory normalization (not required to rewrite item unless refreshed/saved by existing flows).

**`summon/soul/db/SoulDatabase.java`**
- Additive soul columns/migrations only if needed (respawn is primarily inside `base_stats_json`, so no new column is strictly required unless denormalized). Evaluate:
  - **Preferred:** keep respawn in `base_stats_json` (no schema column change) and perform data backfill in repository/service migration pass for rows where base stats lack respawn.
  - If denormalized for queries/UI later, add `respawn_seconds REAL` via `addColumnIfMissing` — but not necessary for MVP.
- Ensure migration helpers remain idempotent.

**`summon/soul/db/SoulRepository.java`**
- Update insert/update/select/mapping to preserve new base-stats JSON content (automatic if treating as opaque JSON string, but verify).
- Add/extend migration/backfill method(s) (idempotent, batch-safe) invoked on init/load paths (consistent with existing legacy stat backfill behavior):
  1. **Affix migration:** for souls whose `affixes_json` contains `"COMMANDER"`, parse and rewrite to new respawn affix (and recompute quality if stored/derived as needed) only when changed.
  2. **Respawn stat backfill:** for souls whose `base_stats_json` is null/missing respawn/sentinel, generate or assign a rarity-band respawn value (deterministic seed from `soul_id`+rarity recommended for stability, or random-once persisted) and write back.
  3. Invalidate relevant caches after bulk migration.
- Use `transaction(...)` for multi-step per-row/per-batch mutations where consistency matters.

**`summon/soul/service/SoulService` (caches/invalidation)**
- Ensure `invalidateSoulCache`, `getSoulCached`, snapshots, and selected/team caches are invalidated when migration mutates souls.
- If respawn affix affects snapshot-derived behavior, include respawn reduction in snapshot API consumed by runtime.

---

## 5. Config design and backfill

### 5.1 `summon.yml` (bundled resource) — proposed keys
Add/adjust under existing soul/stat sections (final structure aligned with current config style):

```yml
souls:
  respawn:
    enabled: true
    # effective respawn = clamp((baseRespawn * enhanceFactorOrRule) * (1 - respawnAffixReduction) - killChargeReductions, floor, ceil)
    global-floor-seconds: 10
    global-ceil-seconds: 120
    # enhancement interaction (example; finalize names/semantics)
    enhance-reduces-respawn: true
    enhance-per-level-reduction: 0.01   # 1% per level, bounded
    enhance-max-reduction: 0.20         # cap enhancement contribution

    kill-charge:
      enabled: true
      # qualifying kill reduces remaining respawn time for each pending dead soul in owner's active group
      flat-seconds: 3.0
      percent-of-remaining: 0.03        # 3%
      per-kill-apply-once: true
      # eligibility / anti-farm
      credit-owner-kills: true
      credit-summon-kills: true
      allow-player-victim: false
      internal-cooldown-ticks: 0        # optional global/owner throttle
      max-reduction-per-kill-seconds: 15 # optional cap
      min-remaining-after-charge-seconds: 10

    vfx-sfx:
      enabled: true
      # death (pending) cue
      death-cue-enabled: true
      death-particles: true
      death-sound: true
      # subtle ambient pending cue (non-UI)
      pending-cue-enabled: true
      pending-cue-interval-ticks: 80    # throttled periodic cue
      pending-cue-particles: true
      pending-cue-sound: false          # very subtle / often off by default
      # respawn complete cue
      respawn-cue-enabled: true
      respawn-particles: true
      respawn-sound: true
      # kill charge cue (distinct)
      kill-charge-cue-enabled: true
      kill-charge-particles: true
      kill-charge-sound: true
      audible-range: 24.0
      intensity: 1.0

  stats:
    # add respawn base-stat bands (keys aligned with SoulStatConfig loader)
    respawn:
      common:    { min: 75, max: 90 }
      uncommon:  { min: 64, max: 80 }
      rare:      { min: 52, max: 68 }
      epic:      { min: 42, max: 56 }
      legendary: { min: 34, max: 46 }
      mythic:    { min: 28, max: 38 }

  affixes:
    # replace COMMANDER config/ranges with new respawn affix ranges (names/structure per current config model)
    reawakening:
      common:    { min: 0.02, max: 0.05 }
      uncommon:  { min: 0.03, max: 0.07 }
      rare:      { min: 0.05, max: 0.10 }
      epic:      { min: 0.07, max: 0.13 }
      legendary: { min: 0.10, max: 0.17 }
      mythic:    { min: 0.13, max: 0.22 }
      hard-cap:  0.35
```

> **Note:** exact key names/structure must match existing `SoulConfig`/`SoulStatConfig`/loader conventions; remove or deprecate COMMANDER-specific keys and update guide/table text. Keep cast cooldown/mana config semantics intact except removal of affix-based cost reduction.

### 5.2 Code config loaders and backfill
- Update `summon/soul/domain/SoulConfig`, `summon/soul/table/SoulTableConfig.SoulStatConfig` (or respawn-specific config holder), and `summon/config/SummonConfig` to parse/load/fallback all new keys.
- `core/GlovedPlugin` backfill: add defaults for new keys only when missing (preserve existing values), including respawn bands, affix ranges, kill-charge, and VFX/SFX toggles.
- Update bundled `src/main/resources/summon.yml` and mirror required defaults in live server config for immediate effect on implementation deploy.

---

## 6. Respawn runtime design (detailed)

### 6.1 New service: `summon/runtime/SummonRespawnService` (proposed)

**State model (conceptual)**
- Key: `(ownerId, teamIndex, slotIndex)` (and/or stable slot key string) — one pending respawn per active group slot.
- Value: `PendingRespawn` record/object containing:
  - `ownerId`, `teamIndex`, `slotIndex`
  - `soulId` (UUID/String) and/or resolved soul snapshot context
  - `profileKey/familyKey/entityType` (for spawn/profile resolution and validation)
  - `dueAtMillis` (or `remainingMillis` + last tick time) — **millis-based or tick-based but consistent**; support remaining-time reduction from kill-charge
  - `createdAt`, `retryCount`, optional `deathLocation/world`
  - flags for cancellation/completion
- Owner index: `ownerId -> set/map of pending slots` for fast bulk operations (recall, quit, kill-charge, shutdown).

**Core API (conceptual)**
- `scheduleOrReplace(ownerId, teamIndex, slotIndex, soul, profile, deathLocation, effectiveRespawnSeconds)` — called from death hook when eligible.
- `cancelSlot(ownerId, teamIndex, slotIndex, reason)` / `cancelTeam(ownerId, teamIndex)` / `cancelAll(ownerId)` / `cancelAll()` — idempotent.
- `hasPending(ownerId, teamIndex, slotIndex)` / `getRemainingSeconds(...)` — for UI/ambient cues.
- `tick(now)` or scheduled Bukkit task to process due entries.
- `applyKillCharge(ownerId, reductionSecondsOrRule, sourceContext)` — reduces remaining time for all pending slots of owner (bounded, atomic per entry).
- `onPlayerQuit`, `onDisable`, `onReload` hooks.

**Lifecycle/wiring**
- Instantiate in `GlovedPlugin` summon setup, pass/store alongside `SummonManager`/`SummonInstanceTracker`/`SummonListener` (or accessible via plugin getter).
- Start periodic tick task (e.g. every 5–10 ticks) for due respawns and ambient pending cues (throttled); cancel on disable.
- Ensure thread-safety/Bukkit main-thread rules: entity spawn/remove and world access must run on main thread.

### 6.2 Death flow integration (`SummonListener#onEntityDeath` and/or tracker)

Current behavior: on summoned minion death -> `tracker.despawn(entityId)`.

**New behavior (team-slot summon, active group eligible):**
1. Confirm entity is tracked summoned minion (`tracker.isSummoned`/owner).
2. Read PDC tags: `summon_team_index`, `summon_team_slot`, `summon_soul_id`, profile/family/type, owner.
3. Determine eligibility for auto-respawn:
   - team index/slot present and valid
   - owner online and this team/slot is part of the **currently active** group/loadout context for that owner (and same soul still assigned, if required by design)
   - respawn system enabled by config
4. If **eligible**:
   - Compute effective respawn time from soul’s respawn base stat + enhancement rule + new respawn affix (via `SoulService` snapshot/base-stat helpers), clamped to global bounds.
   - Play **subtle death/pending cue** (non-UI VFX/SFX) at death location/context (§7).
   - Register pending respawn in `SummonRespawnService` for same `(owner, team, slot)`.
   - Remove/despawn the dead entity from active tracker **without creating conflicting state** (ensure it no longer counts as alive; avoid respawn loop). Implementation detail: may need a tracker path that cleans maps/task/entity without re-triggering unwanted effects, or schedule cleanup safely.
5. If **not eligible** (non-team, legacy, inactive group, invalid slot): preserve existing cleanup behavior.

**Important:** avoid double-handling (MONITOR death event + manual despawn). Ensure pending entry is created before/atomically with active-entity removal so slot is never “alive and pending”.

### 6.3 Due respawn execution

For each due pending slot (main thread):
1. Revalidate: respawn enabled; owner online; group still active; slot assignment unchanged and maps to same `soulId`; no live summoned entity already occupying that slot/team entry.
2. Resolve `SoulInstance` (cached/service lookup by id, owner-owned, valid state) and `SummonProfile`/spawn config.
3. Determine safe spawn location (near owner, respect existing summon spawn safety helpers like `resolveSpawnLocation`/`findSafeSpawn` logic in `SummonManager`; avoid unsafe blocks/world mismatch).
4. Spawn exactly **1** summon for that slot using existing spawn pipeline with team tags (`summon_team_index`, `summon_team_slot`) and soul snapshot/stat application (reuse `SummonEntitySpawner`/`SummonManager` logic; may require a small internal API for "spawn single slot respawn" without altering unrelated group semantics).
5. On success: clear pending entry, play **respawn-complete cue**, debug log.
6. On failure: increment retry count, reschedule short retry (bounded, e.g. 1–3 attempts over a few seconds) or cancel safely with debug warning; never leave duplicate pending+active or infinite retry loops.

### 6.4 Cancellation and consistency hooks

Cancel pending respawns (affected slots only unless noted) on:
- **Recall/deactivate/hotswap:** `SummonManager#recallAll/recallActiveTeam`, team deactivation, and any `SoulService`/manager path that replaces active group.
- **Slot changes:** team slot clear/replace/reassign, soul moved/extracted/deleted, soul no longer owned/valid, team selection/loadout mutations affecting active slots.
- **Player/session:** `PlayerQuitEvent` cleanup path (`SummonListener`/`SoulService#onPlayerQuit`), owner offline handling.
- **Lifecycle:** plugin disable (`SummonManager`/tracker shutdown), summon system reload where active state is reset (define explicitly), server shutdown. Call `SummonRespawnService#cancelAll()` and stop tasks.
- **Chunk/world/entity edge cases:** if respawn cannot be validated due to world unload or invalid context after retries, cancel safely.

**Invariant:** for any `(owner, team, slot)` at most one of {alive summoned entity, pending respawn} may exist; transitions are atomic/idempotent.

### 6.5 Ambient pending cue scheduling (non-UI)
- While slots are pending, emit very subtle periodic cues (owner-scoped/context-scoped) at configurable interval and intensity (§7). Implement throttling (per-owner/global cap) to avoid particle/sound spam when multiple souls are pending.
- Stop immediately when slot respawns or is cancelled.

---

## 7. Kill-charge acceleration (detailed)

### 7.1 Qualifying kill detection
- **Primary integration point:** `BaseSummonProgressionBridge#onEntityDeath(LivingEntity victim, long nowTick)` already consumes kill credit with `(ownerUuid, soulId, profileName)` from `SummonXpState`. This is the best existing hook for summon-attributed kills (and can be extended to owner-attributed kills if needed).
  - After resolving/validating kill credit (and independent of AuraSkills XP enablement where appropriate), call respawn kill-charge API for the credited owner: e.g. `plugin.getSummonRespawnService().onQualifyingKill(ownerId, victim, creditedMinionSoulId, source, nowTick)` subject to config/eligibility.
- **Owner-direct kills (if not already represented as summon kill credit):** add a lightweight listener/hook (or extend attribution) to credit owner kills to pending respawn charge when config allows and victim eligibility passes. Avoid double-charging for the same death event.
- **Eligibility filters (configurable):** victim must be valid mob target (default PvE/non-player), not blacklisted/exempt if configured; optional ignore armor stands/NPCs/system entities; optional per-victim-type/rarity handling later. Apply anti-farm: internal cooldown, per-kill cap, max reduction per application, and enforce global min remaining.

### 7.2 Charge application
- For a qualifying kill event, compute reduction once:
  - `reduction = min(maxReductionPerKill, flatSeconds + remaining * percentOfRemaining)` per pending slot (or global formula variant) then clamp so `newRemaining >= minRemainingAfterChargeSeconds` and `>= globalFloor`.
- Apply to **all pending dead souls in the owner’s active group** (all pending slots for active team) atomically/update due times.
- If no pending souls, no-op (and no VFX unless configured otherwise; default: only when >=1 soul charged).
- Play **kill-charge VFX/SFX** (distinct from death/respawn cues) when charge applied.

### 7.3 VFX/SFX implementation details (subtle, immersive, non-UI)

Implement in `summon/vfx/SummonVfx` (and/or small helper) using `VfxEffect`/`ParticleStep` builders consistent with existing presets:

| Cue | When | Suggested visual/audio (subtle, tunable) | Notes |
|---|---|---|---|
| Death/pending start | Eligible summoned minion dies and enters respawn queue | Small dissipating `SOUL`/`SOUL_FIRE_FLAME`/`ASH`-like puff at death location, very short; low-volume ethereal sound (e.g. soul escape/whisper equivalent available in version) | Non-UI, brief, owner-relevant context |
| Pending ambient (optional) | Periodically while slot pending | 1–few faint soul particles near owner or occasional soft cue; very low frequency/intensity | Throttled, configurable, can default sound off |
| Respawn complete | Summon successfully respawns into slot | Gentle inward/gathering soul particles at respawn point + soft chime/whoosh | Distinct from spawn/despawn/kill-charge presets |
| Kill-charge | Qualifying kill charges >=1 pending soul | Small "siphon/charge" arc or pulse (owner↔pending context abstraction) or brief soul spark near owner; distinct soft sound | Trigger once per qualifying kill event, throttled |

**Constraints:** configurable enable/intensity/frequency/range; owner/context-scoped visibility/audibility where feasible; no chat/action-bar/title/bossbar/scoreboard; cancel ambient cues on completion/cancel; respect performance (bounded particles, no per-tick heavy loops).

---

## 8. UI, lore, guide, and command updates

- **`SoulLoreBuilder` / soul stat rendering:** include respawn timer stat (base/effective as appropriate) and new respawn affix; remove/replace COMMANDER "Cost Reduction" display. Keep formatting consistent with existing stat lines.
- **`SoulTeamsInv` (optional but recommended):** visually indicate pending-respawn slots (e.g. slot item/lore/countdown text inside inventory item lore is UI, but allowed in inventory; the user requested non-UI *world feedback* — inventory state is fine). Prefer subtle indicator (timer remaining / "Respawning...") on refresh.
- **Soul Table guide/info/help:** update affix/stat descriptions to replace cost reduction with respawn reduction and document respawn stat behavior.
- **Commands/inspect (`/gloved soul ...` outputs):** include respawn stat/affix in detailed soul info where relevant.
- **Codex/mastery interactions:** no direct change required, but ensure any soul copy/display helpers include new stat/affix safely.

---

## 9. Edge cases and invariants

- **No duplicate respawns:** single pending entry per `(owner,team,slot)`; revalidate slot ownership/soulId and absence of live slot entity before spawn; idempotent cancellation.
- **Group/slot validity:** if team recalled, deactivated, hotswapped, slot cleared/replaced, soul removed/transferred/deleted, or active team differs, cancel pending respawn.
- **Owner/session:** on quit/disconnect cancel pending (ephemeral default). On join, no automatic respawn unless explicitly redesigned.
- **World/location safety:** if owner/world invalid or no safe spawn after bounded retries, cancel safely.
- **Reload/disable:** stop tasks and clear/cancel pending state; on reload, do not orphan entities or pending timers.
- **Non-team/legacy summons:** keep current behavior (no auto-respawn) unless explicitly extended.
- **COMMANDER legacy data:** DB rows and soul items containing `"COMMANDER"` must load/migrate without exceptions.
- **Performance:** bounded tick work, throttled ambient cues/VFX, no unbounded scans; reuse existing caches/services.
- **Exploit resistance:** kill-charge caps/cooldowns/eligibility filters; min respawn floor always enforced.

---

## 10. Implementation sequence (recommended)

1. **Preparation & config scaffolding**
   - Finalize new affix id/name, respawn stat key/unit, enhancement rule, and config key names.
   - Update `src/main/resources/summon.yml` defaults, config loaders/fallbacks, and `GlovedPlugin` backfill (preserve existing keys).
2. **Domain model changes**
   - `SoulAffixType` (+ parse compatibility for `COMMANDER`), `SoulStatSnapshot`, `SoulQuality`, `SoulGenerator`, `SoulBaseStats` (field, roll, normalize/cap/enhance, JSON with legacy handling), `SoulInstance`/helpers.
   - Update `SoulService` cast cost/cooldown to remove COMMANDER cost-efficiency effect.
3. **Persistence & migration**
   - `SoulDropItem` deserialization/migration compatibility, `SoulRepository` read/write + idempotent affix/respawn backfill, `SoulDatabase` only if additive column needed (likely not), cache invalidation.
4. **Lore/UI text updates**
   - `SoulLoreBuilder`, guide/table/command/inspect displays for respawn stat and new affix.
5. **Respawn runtime core**
   - Implement `SummonRespawnService` (state, schedule/cancel/query APIs, tick task), wire into plugin lifecycle.
   - Integrate death eligibility/scheduling and respawn execution (reuse spawn pipeline/team tags/snapshots).
   - Add cancellation hooks (recall, slot/loadout changes, quit, disable/reload).
6. **Kill-charge system**
   - Hook qualifying kills (progression/attribution path + owner-direct kill handling as needed), apply bounded remaining-time reductions, integrate caps/cooldowns/eligibility.
7. **VFX/SFX**
   - Add death/pending/respawn-complete/kill-charge presets/helpers in `SummonVfx` and invoke from respawn/kill-charge flows with config/throttling.
8. **Polish & hardening**
   - Debug logging, failure retries/cancellation safety, team UI pending indicators (optional), config/docs-in-yml comments if appropriate.
9. **Verification**
   - Run `./build.sh`, execute manual test matrix (section 13), fix issues, and ensure migrations are idempotent/safe.

---

## 11. File-by-file checklist (implementation aid)

- [ ] `plans/summon-respawn-timer-plan.md` (this plan, kept current if decisions change)
- [ ] `src/main/resources/summon.yml` — new respawn/stat/affix/kill-charge/VFX keys and defaults; remove/deprecate COMMANDER-specific entries
- [ ] `src/main/java/.../core/GlovedPlugin.java` — config backfill, respawn service lifecycle/wiring, shutdown cleanup
- [ ] `src/main/java/.../summon/soul/domain/SoulAffixType.java` — replace COMMANDER, parsing compat
- [ ] `src/main/java/.../summon/soul/domain/SoulStatSnapshot.java` — respawn reduction modifier, neutral cost efficiency
- [ ] `src/main/java/.../summon/soul/domain/SoulQuality.java` — new affix caps/ranges
- [ ] `src/main/java/.../summon/soul/domain/SoulGenerator.java` — roll new affix/stat, remove COMMANDER logic
- [ ] `src/main/java/.../summon/soul/domain/SoulBaseStats.java` — respawn field/roll/enhance/json/migration-safe parse
- [ ] `src/main/java/.../summon/soul/domain/SoulInstance.java` — accessors/copy safety if needed
- [ ] `src/main/java/.../summon/soul/domain/SoulConfig.java` (and/or stat/table config classes) — load respawn/affix/kill-charge/vfx settings
- [ ] `src/main/java/.../summon/soul/db/SoulDatabase.java` — additive migration only if required
- [ ] `src/main/java/.../summon/soul/db/SoulRepository.java` — mapping + idempotent legacy migration/backfill
- [ ] `src/main/java/.../summon/soul/item/SoulDropItem.java` — affix parse compat, legacy respawn handling
- [ ] `src/main/java/.../summon/soul/item/SoulLoreBuilder.java` — respawn stat and new affix display
- [ ] `src/main/java/.../summon/soul/service/SoulService.java` — remove COMMANDER cost effect, effective respawn helpers, cache invalidation/migration hooks
- [ ] `src/main/java/.../summon/runtime/SummonRespawnService.java` — **new** pending respawn manager/scheduler
- [ ] `src/main/java/.../summon/runtime/SummonManager.java` — respawn spawn integration/cancellation hooks, slot-spawn helper reuse
- [ ] `src/main/java/.../summon/runtime/SummonListener.java` — death->pending respawn flow, quit/cleanup hooks
- [ ] `src/main/java/.../summon/runtime/SummonInstanceTracker.java` — minimal support if needed for safe despawn/slot checks
- [ ] `src/main/java/.../summon/progression/BaseSummonProgressionBridge.java` (and/or kill listeners/state) — kill-charge trigger integration
- [ ] `src/main/java/.../summon/vfx/SummonVfx.java` — death/pending/respawn/kill-charge presets
- [ ] `src/main/java/.../summon/soul/ui/SoulTeamsInv.java` (and handlers) — optional pending status indicator
- [ ] Soul Table/guide/command classes — text/stat/affix updates as applicable
- [ ] `src/test/...` (if present/relevant) — unit-like tests for stat/affix/migration math where feasible

---

## 12. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| COMMANDER enum/string migration breaks legacy souls/items | Load errors, lost affixes | Central parse compatibility (`COMMANDER` -> new affix), idempotent DB/item-safe backfill, defensive deserialization |
| `SoulBaseStats`/JSON shape changes break legacy rows/items | Null/parse failures, wrong stats | Sentinel/missing-field detection + backfill, preserve old fields/aliases, additive migration |
| Duplicate respawns or stuck pending slots | Exploits or broken gameplay | Per-slot single state invariant, revalidation before spawn, bounded retries, idempotent cancel on all lifecycle paths |
| Kill-charge double-counting or farming | Balance abuse | One application per qualifying kill event, eligibility filters, cooldown/caps, global min floor |
| VFX/SFX spam/perf | Visual noise, lag | Throttled ambient cues, configurable intensity/frequency/range, lightweight particles |
| Removing COMMANDER cost reduction changes balance | Cast/cooldown/mana behavior shifts | Explicitly document/accept; neutralize `costEfficiency` effect; communicate in changelog/notes |
| Team/slot lifecycle edge cases (hotswap/quit/reload) | Orphan pending state or wrong soul respawn | Centralized cancellation hooks and soulId/slot validation on due respawn |

---

## 13. Verification and test matrix

**Build**
- [ ] `./build.sh` completes successfully and deploys updated jar.

**Migration/compat**
- [ ] Existing `souls.db` with legacy souls (no respawn stat, `COMMANDER` affixes) loads without errors; affixes migrated to new respawn affix; respawn stat backfilled within rarity bands.
- [ ] Legacy soul drop items in inventory/chests/world load/refresh correctly with migrated affix/stat.
- [ ] New souls generate with respawn base stat and can roll new respawn affix (not COMMANDER).

**Core respawn behavior**
- [ ] Summon death in active group schedules pending respawn for **same slot** and auto-respawns after expected effective time.
- [ ] Rarity spread is meaningful with overlap and Common max base ~90s; Mythic remains substantial (not too fast relative to power).
- [ ] Respawn affix reduces effective respawn time (bounded, respects global floor).
- [ ] Enhancement interaction behaves as specified (and cannot violate floor/ceil).

**Cancellation/safety**
- [ ] Recall/deactivate/hotswap, slot replace/clear, soul removal/extraction, owner quit, and plugin disable cancel pending respawns appropriately.
- [ ] No duplicate summons, no respawn when slot/group invalid, no stuck pending state after failures/retries.
- [ ] Non-team/legacy summon deaths retain existing behavior.

**Kill-charge**
- [ ] Owner kill and summon-attributed kill (as eligible) reduce remaining respawn time for all pending dead souls in active group by configured amount (flat+%/bounded).
- [ ] No reduction when no pending souls; caps/cooldowns/floor/eligibility enforced; no double-charge per event.

**VFX/SFX (non-UI)**
- [ ] Subtle death cue on pending start, optional throttled ambient pending cue, respawn-complete cue, and distinct kill-charge cue trigger correctly.
- [ ] Cues are configurable, owner/context-scoped, non-UI, and stop on cancel/complete; no spam/perf issues.

**Regression**
- [ ] Soul Groups activate/recall/hotswap, Soul Table operations, mastery/codex/progression hooks, and summon combat behavior continue to function.
- [ ] Cast cooldown/mana behavior remains correct with COMMANDER removed (neutral cost-efficiency).

**Operational**
- [ ] Backup reminder followed for DB migration; config backfill preserves existing keys; bundled/runtime/live config defaults aligned for deploy.
