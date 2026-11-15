# Soul Mastery / Attunement + Account/Family Codex — Sub-Implementation Plan

> **Scope:** Implement systems 1 and 2 from `plans/soul-endgame-systems.md` (Soul Mastery / Attunement, Account/Family Codex) on top of the current Soul Groups + Soul Table + Infusion/Condense baseline.
> **Constraints:** Use `./build.sh` only. Keep changes modular/organized for easy extension. Additive schema/config/backfill (preserve existing keys). Follow repo conventions and Soul Groups behavior (4×4, 1 summon/slot, per-summon full stats, `groupCapacity` retired from gameplay).

## 1. Goals and non-goals (v1)

### Goals
- **Modular packages** so future milestones/objectives/rewards/hooks/UI content can be added with minimal core edits.
- **Per-soul Mastery**: persisted XP/level/rank and milestone state on each `SoulInstance`, with config-driven progression and claimable milestone rewards.
- **Account/Family Codex**: expanded discovery/progress tracking and a browsable UI with claimable rewards, built as an extensible objective/reward framework.
- **Integration points**: capture, salvage/extract/delete/combine/reforge/infusion, summon/combat/kill progression hooks, Soul Table/Soul UI navigation, commands, lore/tooltips.
- **Safe defaults**: systems enabled/configurable, additive DB migrations/backfills, transactional/atomic claim+reward flows where practical, anti-abuse/rate limits for XP.

### Non-goals (defer unless clarified)
- Full visual cosmetic runtime for every planned effect (advanced summon particle trails/auras/nameplate accents) beyond hooks/placeholders or simple implemented effects.
- Catalyst tiers/variants, fragments, cores, affinity systems (only extension points/compatibility).
- Reworking unrelated combat/balance systems.

## 2. Proposed modular architecture

### 2.1 Package layout

```
summon/soul/mastery/
  domain/      MasteryProgress, MasteryRank, MilestoneId, MilestoneReward, MasteryConfig
  db/          MasteryRepository (or repository extensions) + codec/serialization helpers
  service/     SoulMasteryService (XP grant, rank-up, milestone claims, queries)
  event/       MasteryXpSource, MasteryEventListener(s) / hook adapters
  reward/      MasteryRewardHandler registry (ESSENCE, COMMAND/item hook, TITLE_SOUND, COSMETIC_TAG, TOKEN placeholder)
  ui/          SoulMasteryView helpers / optional Soul Table mastery screen

summon/soul/codex/
  domain/      CodexEntryProgress, CodexTier, CodexObjective, CodexReward, CodexCategory/Set
  db/          CodexRepository (expanded progress/claims storage)
  service/     SoulCodexService (discovery/progress updates, objective evaluation, claims)
  event/       CodexProgressListener(s) / hook adapters
  reward/      CodexRewardHandler registry
  ui/          SoulCodexInv (paginated/browsable), detail/claim rendering helpers
```

Shared integration stays in existing services/listeners with thin delegation:
- `SoulService`/`SoulRepository` for soul persistence and capture/salvage/infusion lifecycle hooks.
- `SummonProgressionBridge` / combat listeners / summon tracker hooks for XP and codex progress events.
- `SoulTableNav`/Hub/Guide, `SoulVaultInv`/`SoulTeamsInv` or commands for UI entry points.

### 2.2 Registries and extension points
- **Mastery milestones/rewards**: config/data-driven definitions resolved into typed reward handlers (easy to add new reward types/content).
- **Codex objectives/sets/rewards**: registry of objective evaluators keyed by type (FAMILY_CAPTURED, FAMILY_HIGHEST_RARITY, FAMILY_CAPTURE_COUNT, FAMILY_MASTERED_COUNT, FAMILY_INFUSED_COUNT placeholder, CATEGORY_SET, etc.) and reward handlers.
- **Event hooks**: single service methods (`onSoulCaptured`, `onSoulSalvaged`, `onSoulInfused`, `onSummonCombatTick`, `onSummonDamage`, `onSummonKill`, `onSoulMasteryChanged`) so new content/systems can subscribe with minimal wiring.

## 3. Data and persistence (additive)

### 3.1 Per-soul mastery (souls table / SoulInstance)
Add columns (idempotent migrations) to `souls`:
- `mastery_xp INTEGER NOT NULL DEFAULT 0`
- `mastery_level INTEGER NOT NULL DEFAULT 0` (or rank index)
- `mastery_data_json TEXT` (nullable/default empty) for selected attunements, claimed milestones, timestamps/counters needed for anti-abuse or future expansion

`SoulInstance` additions:
- immutable fields/getters for mastery state
- copy helpers: `withMastery(...)` / `withMasteryProgress(...)`
- serialization/deserialization compatibility for existing rows/items (default to empty progress)

`SoulRepository` additions:
- map/load/save mastery fields in `insertSoul`, `mapSoul`, and targeted update methods (e.g. `updateSoulMastery`)
- ensure delete/transform flows preserve/reset per config where applicable

### 3.2 Expanded codex (account-wide)
Current `soul_codex(owner_uuid,family_key,highest_rarity,captured_count)` remains for compatibility and is extended/mirrored into richer tracking.

Proposed additive tables:
- `soul_codex_progress` (per owner+family): highest rarity, captured count, refined/enhanced count, infused count, mastered count, first seen timestamps, flags/JSON for extensibility
- `soul_codex_claims` (per owner+claim_id): claim state/timestamp/reward payload hash/version
- optional `soul_codex_meta` (per owner): tracked objective, UI prefs, totals cache

Repository/service must provide efficient reads for UI (paged family lists, summary %, claimable counts) and atomic claim transactions (`SoulDatabase.transaction`).

### 3.3 Backfill/migration
- `souls` mastery columns backfilled as defaults on init (no rewrite of unrelated data).
- Existing `soul_codex` rows migrated into expanded progress (idempotent) preserving highest rarity/captured count.
- Config backfill for new `souls.mastery.*` and `souls.codex.*` keys.

## 4. Soul Mastery / Attunement design (v1 implementation target)

### 4.1 Progression model
- Config-driven level/rank curve (XP thresholds) and milestone definitions.
- XP sources (rate-limited, anti-farm):
  - summon active/combat participation ticks (periodic, only while soul’s summons are active and in valid combat context)
  - damage dealt participation (from summon combat pipeline/owner-attributed minion hits)
  - kill participation (credited kills)
  - optional capture/use bonuses if appropriate
  - elite/boss/challenge bonus multipliers when detectable, with caps
- Diminishing returns / cooldown windows per source, per soul, and/or per minute caps.

### 4.2 Milestones and rewards
- Milestone definitions: level/rank requirement, reward type + params, one-time claim rules.
- v1 reward handlers (proposed): `essence`, `title_and_sound` feedback, `cosmetic_tag`/attunement selection stored on soul (data-only hook for future visuals), and extensible command/item hooks if safe/configured.
- Utility perks/cosmetics beyond data + feedback: implement only where existing hooks make it clean; otherwise expose extension points and record unlocked attunement state for future systems.

### 4.3 Lifecycle rules
- Mastery bound to `SoulInstance`; persists in vault and across normal table actions.
- **Preserve by default** on reforge/enhance/infusion; configurable reset/penalty hooks where explicitly supported.
- **Combine/Salvage/Extract/Delete**: define and implement handling (e.g. salvage grants small mastery-derived bonus/essence or logs; delete/extract remove soul-bound progress with it; combine target behavior explicit). Must be transactional/consistent.

### 4.4 UI/feedback (v1)
- Lore/inspect: mastery rank/level, XP progress, next milestone/claimable indicator.
- Soul Table: add a Mastery/Attunement screen (or panel flow) reachable from hub/nav for a selected soul to view progress and claim milestones.
- In-game feedback on gain/rank-up/claim (action bar/message/title/sound, throttled).

## 5. Account/Family Codex design (v1 implementation target)

### 5.1 Progress model
- Family/entity entries keyed by `familyKey`/entity type: discovered, captured count, highest rarity, and (as available) refined/infused/mastered counters.
- Tier progression (configurable thresholds): e.g. Discovered → Collected → Refined → Mastered, with one-time rewards per tier/entry as configured.
- Category/set objectives: extensible evaluator framework (start with a small curated/configurable set: family completion basics, rarity milestones; leave advanced trait/affinity/core objectives as future evaluators).

### 5.2 Rewards and claims
- Reward handlers similar to mastery (cosmetic/QoL tags, essence/items/hooks) with account-level claim tracking.
- Discovery/claim toasts/messages and summary progress.

### 5.3 UI/commands (v1)
- Browsable Codex UI: paginated family list with completion/tier state, details for selected family, claim buttons, and basic filters/search if feasible.
- Entry points (proposed): Soul Table hub button + `/gloved soul codex` command; optional shortcut from Soul Vault/Groups as space permits.
- Integrate with existing inventory-holder routing and UI icon/pack conventions.

## 6. Integration points (code areas)

- **DB/schema**: `SoulDatabase` migrations, `SoulRepository` mappings/updates, new codex/mastery repository classes.
- **Domain**: `SoulInstance` (+ DTO/item serialization `SoulDropItem` if needed), new mastery/codex domain records/config classes.
- **Services/events**: `SoulService` capture/collect/salvage/extract/table-action hooks; `SummonProgressionBridge` and combat/listener hooks for XP/kill/damage/capture/cast events; summon runtime/tracker for active-summon combat participation ticks.
- **UI**: `SoulTableUiKeys`, `SoulTableNav`, `SoulTableHubInv`, new screen(s) (e.g. `SoulTableMasteryInv`, `SoulCodexInv`), `SoulLoreBuilder`/inspect/status text, vault/group UI badges where useful.
- **Commands/config/wiring**: soul command handlers/help/completions, `SoulConfig`/table config or new config sections, `summon.yml` defaults/fallback/backfill, `GlovedPlugin` init/reload/registration and lifecycle.

## 7. Phased implementation plan

1. **Scaffold & persistence foundation**: packages, config models/defaults/backfill, DB migrations, domain/storage serialization, repository/service skeletons, plugin wiring.
2. **Mastery core**: XP/rank engine, milestone evaluation/claim service, lifecycle hooks (preserve/reset rules), lore/inspect integration, basic UI screen/entry point.
3. **Mastery event wiring**: connect capture/summon/combat/kill hooks with rate limiting/anti-abuse; add feedback.
4. **Codex core**: expanded progress tracking, tier/objective evaluation, claim persistence/rewards, migration from existing codex.
5. **Codex UI & commands**: browsable/claim UI, discovery/claim feedback, command/help/completions and entry points.
6. **Polish, validation & docs**: config tuning defaults, edge cases/transactions/cache invalidation, `./build.sh`, update plan notes/changelog if appropriate.

## 8. Open questions / decisions needed before implementation

1. **v1 Mastery rewards scope**: implement which reward types now? (a) data/claims + essence/title-sound feedback only, (b) include simple cosmetic tags/selection hooks with no new VFX runtime, (c) attempt concrete summon visual cosmetics/perks now. Also include "attunement tokens" in v1 or defer?
2. **Mastery XP sources & combat-valid definition (v1)**: which existing hooks should count initially (e.g. summon active time, minion damage dealt, credited kills, capture/cast)? How strict should anti-AFK/valid-combat checks be in v1?
3. **Codex tiers/rewards (v1)**: ship a minimal but real reward set (e.g. small essence/title/cosmetic tags per tier) or focus on tracking/UI and make rewards mostly config placeholders? Should refined/mastered/infused counters be fully wired now?
4. **UI entry points**: preferred placement for Codex and Mastery views (Soul Table hub buttons/screens, Soul Vault/Groups buttons, `/gloved soul ...` commands, or a mix)? Any layout/icon preferences?
5. **Lifecycle on consume actions**: on **combine/salvage/extract/delete**, should mastery/codex progress be preserved/converted/granted in any specific way (beyond obvious "captured/discovered" history), or follow simple defaults (salvage may grant small bonus; consumed/deleted souls lose soul-bound mastery; combine target preserves only if explicitly specified)?
6. **Storage preference**: OK to use additive `souls` columns for mastery core fields plus a `mastery_data_json` blob, and new `soul_codex_*` tables for expanded account progress/claims (as proposed), instead of forcing everything into existing tables/JSON only?

## 9. Risks and mitigations
- **Scope creep**: lock v1 to modular foundation + core tracking/claims/UI; stub advanced rewards/evaluators.
- **Performance/abuse**: batch/rate-limit XP updates, avoid heavy per-tick DB writes (in-memory accumulation + periodic flush/dirty-save), cap rewards.
- **Data integrity**: additive migrations, defensive deserialization defaults, transactional claims/currency changes.
- **Compatibility**: preserve Soul Groups semantics, legacy soul rows/items, and existing codex behavior while extending.

*This sub-plan will be refined based on answers to section 8, then implemented in modular phases with `./build.sh` verification.*