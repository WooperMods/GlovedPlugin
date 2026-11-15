# Soul Team / Loadout Overhaul — Full Implementation & Design Plan

> **Status:** Implementation-ready design/plan.  
> **Build workflow:** use `./build.sh` only (never run Gradle directly).  
> **Terminology:** spawned entities are called **summons** (never **minions**) in all new/updated player-facing text, docs, comments, and new identifiers where practical.

## 0. Purpose

This document specifies the exact overhaul required to replace the current **single selected soul + per-soul summon group** system with a **team/loadout-based summoning system** (gacha-style team setup). It is intended to be detailed enough for a separate AI agent to implement with minimal ambiguity.

**Decisions locked for this overhaul**
- **Team composition:** 3 saved teams per player (`Team 1`, `Team 2`, `Team 3`), **4 fixed slots per team**, **1 summon per equipped soul slot** (mixed soul types allowed).
- **Reuse rules:** a soul may be assigned to multiple saved teams; a soul may **not** appear twice in the same team.
- **Activation:** one active team at a time. Activating the already-active team **recalls/toggles off**. Activating a different team performs **hotswap** (recall current, then spawn target).
- **Activation target/selection:** activating a specific team targets it and sets it as the selected/active team context.
- **Invalid/empty slots on activation:** skip empty/invalid/missing slots, clean stale references, spawn remaining valid summons. If no valid summons remain, activation fails with clear feedback.
- **Cooldown/mana (default):** **no cast GCD** for activate/hotswap, **mana on activate/hotswap** (configurable), **recall/toggle-off instant and free**. Design prioritizes fast recovery when summons get stuck.
- **Summons count retirement:** remove/ignore `groupCapacity` (“Summons count”) in gameplay/UI/generation/enhancement/previews/lore. Keep legacy stored data/fields harmless for compatibility.
- **Per-summon stats:** with 1 summon per soul slot, **do not divide soul HP/DMG across a group**. Each spawned summon uses the soul’s full effective HP/DMG.
- **Soul Focus revamp:** Right-click Soul Focus -> Teams view (filled/empty slots) -> click slot -> Soul Vault picker for that slot -> pick/clear soul -> return to Teams view.
- **Migration:** additive/idempotent migration from legacy selected/loadout state into Team 1 defaults; keep legacy data readable but unused for gameplay.

---

## 1. Current Implementation Baseline (what exists today)

### 1.1 Core concepts
- **Selected soul model:** player state stores `selected_slot`; `soul_loadouts` stores per-slot soul assignments (and selected-related usage). `SoulService` resolves selected soul and casts/recalls it.
- **SoulId-exclusive active group:** casting the selected soul spawns a group for that soul; same soul active => recall/toggle; different soul active => hotswap recall then cast selected soul.
- **Group capacity / Summons count:** `SoulInstance.groupCapacity` is rolled/stored/serialized and used (with effective modifiers) to determine how many summons spawn for one soul. Runtime stat application divides HP/DMG by group size in spawn paths (e.g. `SummonManager.applySoulSnapshotToMinion`).
- **Runtime tracking:** summons are tagged/tracked by owner/profile/soul identifiers and managed by summon runtime/tracker/listeners for recall, cleanup, AI, combat attribution, and progression hooks.
- **UI/interaction:** Soul Focus (`summon/soul/item/SoulItem`) currently supports cast/recall and vault access patterns; `SoulVaultInv` manages paginated vault browsing/selection/equip interactions around legacy loadout semantics.

### 1.2 Important existing files/classes (primary impact)
- **DB:** `summon/soul/db/SoulDatabase.java`, `summon/soul/db/SoulRepository.java`
- **Domain/data:** `summon/soul/domain/SoulInstance.java`, `SoulGenerator.java`, `SoulConfig.java` (and related soul config/stat classes), `SoulDropItem`/DTO serialization, lore/stat builders
- **Service:** `summon/soul/service/SoulService.java` (+ listeners/helpers), capture/collect/extract/salvage flows
- **Runtime:** `summon/runtime/SummonManager.java`, `SummonInstanceTracker.java`, spawn/listeners/combat/AI/progression integration, `DamageProxy` and related attribution
- **UI/items:** `summon/soul/ui/SoulVaultInv.java`, `summon/soul/item/SoulItem.java` (Soul Focus), soul item/listener/command handlers
- **Commands:** `/gloved soul ...` handlers (exact classes under command packages)
- **Config/wiring:** `src/main/resources/summon.yml`, summon config loaders/backfill, `core/GlovedPlugin.java` registration/reload paths

> Implementer note: inspect current method names/usages before refactoring; preserve behavior not explicitly changed by this plan.

---

## 2. Target Behavior Specification

### 2.1 Team data model (runtime/domain)

Introduce explicit team concepts (new classes/records/value objects as appropriate in `summon/soul/domain` or `summon/soul/team` package):
- `SoulTeamSlot` / slot value: `int teamIndex (1..3)`, `int slotIndex (0..3)`, `UUID soulId` or `null`.
- `SoulTeam`: `owner UUID`, `teamIndex`, ordered 4 slots.
- `SoulTeamLoadoutState`: `selectedTeam` (1..3), teams map/list.
- `ResolvedTeamSummon` (activation input): ordered list of valid `(slotIndex, SoulInstance)` entries after validation/cleanup.

**Validation rules**
- Team index must be in `1..3` (or configured bounds if generalized, but defaults/behavior fixed to 3 teams for this overhaul).
- Slot index must be in `0..3` (4 slots).
- A non-null `soulId` must exist, belong to owner, and be summon-eligible for activation.
- Same `soulId` cannot appear in more than one slot of the **same** team.
- Same `soulId` may appear in different teams.

### 2.2 Activation semantics (authoritative)

#### Definitions
- **Selected team:** team targeted for editing/UI and default activation context.
- **Active team:** team currently represented by tracked active summons for the owner.
- **Team activation target:** the specific team index requested by UI/command/item action.

#### Activation outcomes
- `ACTIVATED`: target team spawned successfully (>=1 summon).
- `RECALLED`: target team was already active and was recalled/toggled off.
- `HOTSWAPPED`: a different team was active; it was recalled and target team spawned.
- `FAILED_*`: no spawn/recall change except safe cleanup; return explicit reason for UX.

#### Algorithm (must follow)
1. **Resolve request**: determine `owner`, `targetTeamIndex`, and interaction source (UI/command/item). Set/record selected team to `targetTeamIndex` for this activation context.
2. **Load team state**: fetch target team slots in deterministic slot order (`0..3`).
3. **Validate & clean slots**:
   - For each slot, if empty -> skip.
   - If `soulId` missing/deleted/not owned/invalid -> clear slot reference (persist), mark skipped with reason.
   - If valid -> include in summonable list (preserve slot order).
4. **No valid summons check**: if summonable list empty -> fail with `FAILED_EMPTY_OR_INVALID_TEAM` (after cleanup persisted).
5. **Active-team check**:
   - If active team == target team and has tracked active summons -> **recall now** (instant/free), return `RECALLED`.
   - If a different team is active -> **hotswap path** (step 6 then 8).
6. **Pre-spawn checks** (before spending mana / before irreversible transitions where possible):
   - permissions/world/feature checks currently required for summoning
   - mana availability for activate/hotswap (if configured > 0)
   - spawn feasibility guards (owner online/valid location/world)
   - anti-race/duplicate activation guard for owner
7. **Hotswap recall (if needed)**: recall all currently active team summons. Ensure tracker/runtime state reflects no active team before spawning target.
8. **Spend cost**: consume mana for activate/hotswap only (no GCD by default, recall always free). If mana spend fails unexpectedly, abort safely with failure (do not partially charge).
9. **Spawn summons**: spawn exactly one summon per valid slot in slot order using each soul’s own snapshot/stats/traits/affixes. Apply team-context tracking metadata.
10. **Finalize state/UI**:
   - mark target team active (runtime/tracker-derived), selected team = target.
   - report spawned/skipped counts and reasons (invalid slots cleaned).
   - refresh action bar / Soul Focus lore / relevant open UI.

#### Failure/recovery rules
- **Recall must always be possible/instant/free** even if activation would fail.
- Do not strand player in ambiguous state: if hotswap recall succeeded but spawn cannot proceed due to preventable precheck failure, prechecks must run before recall where feasible. If an unavoidable post-recall spawn failure occurs, leave no active team, return clear failure, and keep presets intact.
- If individual summon spawn fails mid-activation, use a **safe partial-failure policy**:
  - **Preferred:** stop and roll back spawned summons from this activation attempt (despawn newly spawned ones), return failure (keeps team state consistent).
  - **Acceptable fallback only if rollback is impractical:** keep successfully spawned summons, report partial activation explicitly. (Choose one policy during implementation and apply consistently; preferred is rollback.)

### 2.3 Cooldown, mana, and stuck-summon responsiveness

- **Recall/toggle-off:** always instant, no cooldown, no mana, no GCD.
- **Activate/hotswap:** no cast GCD by default; configurable mana cost on activate/hotswap only.
- **Preset editing/selection/team browsing/slot assignment:** no mana/cooldown.
- **Rapid recovery:** players must be able to quickly recall and reactivate/hotswap when summons get stuck. Do not introduce hidden delays.
- **Anti-stuck support:** preserve and, if needed, improve summon stuck detection/teleport/recovery and recall-all cleanup behavior for mixed teams.

### 2.4 Spawn/stat rules (critical)

- **1 summon per valid soul slot**. No per-soul multi-summon group spawning.
- **No group HP/DMG division**. Remove any division by `groupSize`/`groupCapacity` in spawn stat application and related calculations.
- Each summon uses the **full effective stats** for its source soul:
  - base rolled stats + enhancement + soul affixes/traits/stat snapshot modifiers
  - owner/progression bonuses and runtime multipliers/caps that already apply per summon
  - existing clamping/safety rules (movement caps/floors, health/attribute safety, etc.) remain unless explicitly changed
- **Mixed teams:** each summon is independent and uses its own soul’s stats/snapshot; do not average or share stats across team members.

### 2.5 Summons count (`groupCapacity`) retirement

- Remove from gameplay logic: no rolling, no generation scaling use, no enhancement effects, no effective group size calculations for spawn count, no runtime division.
- Remove from player-facing surfaces: lore, vault/team UI, previews, tooltips, inspect/status text, command output where applicable.
- Keep for compatibility: DB columns/fields, serialized DTOs/item data may remain readable; default/ignore safely. New code must not depend on meaningful `groupCapacity` values.

---

## 3. Soul Focus and UI Flow (exact UX)

### 3.1 Soul Focus interaction model

The Soul Focus is the primary item entry point for team management and fast summoning.

**Required behavior**
- **Right-click (air/block, main hand use):** open **Teams view** (team/loadout management screen).
- **Left-click (if supported by existing interaction hooks):** perform **quick activate/recall** of the selected team (or active-team toggle semantics consistent with section 2.2) for fast, low-friction recovery. This must not open the assignment flow.
- **Drop/swap/offhand interactions:** preserve existing protections if Soul Focus is a bound/special item (do not allow loss/duplication through overhaul regressions).

> If implementing left-click quick activation conflicts with event constraints, provide equivalent low-friction activation from Teams UI and/or command while keeping right-click reserved for Teams view. Document the final mapping in item lore and code comments.

### 3.2 Teams view (new/overhauled UI)

**Purpose:** show all 3 teams and their 4 slots (filled/empty), allow slot assignment, and provide activation/recall controls.

**Layout requirements (deterministic inventory design)**
- Use a fixed-size chest inventory (recommend 54 slots) with consistent slot positions.
- Display **Team 1/2/3 sections** vertically or horizontally; each section must render 4 soul slots in order.
- Each slot item shows:
  - filled: soul icon/name/rarity/type (and concise key info) for assigned soul
  - empty: clearly labeled empty placeholder
- Visual indicators:
  - selected team marker
  - active team marker (if currently summoned)
  - optional indicator when a soul is used in other teams (informational only)
- Controls (buttons/items): activate Team 1/2/3 (or activate selected), recall active team, close/back (if nested), help/info.

**Click behavior in Teams view**
- **Left-click filled team slot:** open Soul Vault picker in assignment context for that `(teamIndex, slotIndex)` (replace flow).
- **Left-click empty team slot:** open Soul Vault picker in assignment context for that `(teamIndex, slotIndex)` (assign flow).
- **Right-click filled team slot:** clear that slot (remove soul assignment from that team only). Confirm behavior can be immediate or guarded by UI hint; if immediate, provide clear feedback.
- **Right-click empty team slot:** no-op.
- **Shift-click behavior:** optional shortcut (e.g. clear slot) but must not conflict with core flows; define and document if implemented.
- **Team selector/activate controls:** clicking a team selector sets selected team; clicking activate button activates that specific team (and sets selected/active per rules). Activating active team recalls.
- **Cancel/close:** closing Teams view persists current assignments (assignments are persisted on change; no unsaved draft model unless explicitly implemented).

### 3.3 Soul Vault picker in assignment context

**Purpose:** reuse the familiar/current soul vault browsing experience to choose a soul for a specific team slot, then return to Teams view.

**Navigation flow (required)**
1. Right-click Soul Focus -> Teams view.
2. Click a team slot -> open Soul Vault picker for `(teamIndex, slotIndex)`.
3. Click a soul in picker -> assign soul to that slot (replacing existing assignment if any), persist change, then **return to Teams view** focused on the same team.
4. Optional clear action in picker (if provided) clears slot and returns to Teams view.
5. Back/cancel in picker returns to Teams view without changing the slot.

**Picker behavior rules**
- Show vault souls with existing filters/sort/pagination patterns where possible (reuse `SoulVaultInv` logic/UI style) but change selection semantics from legacy “equip/select soul slot” to **“assign to target team slot”**.
- Exclude or visually mark souls that would violate same-team duplicate rule for the target team (except the soul already in that exact slot). Clicking an invalid/duplicate choice must not assign and must show feedback.
- Allow assigning a soul even if it is already assigned in other teams (cross-team reuse allowed).
- On assignment, clean stale/invalid team state opportunistically if discovered.
- Preserve return context (`teamIndex`, `slotIndex`, optionally previous Teams page/selected team) across pagination/filter/sort actions in the picker.

### 3.4 UI state/navigation implementation guidance

Implement explicit UI context objects to avoid ambiguous navigation (examples):
- `SoulTeamsMenuContext { viewerId, selectedTeam, activeTeam, returnHint }`
- `SoulVaultPickerContext { viewerId, targetTeam, targetSlot, vaultPage, filterType, filterRarity, sortMode }`

Possible implementation approaches (choose one and apply consistently):
- Extend existing inventory holders (`SoulVaultInv`, new `SoulTeamsInv`) with mode/context fields and distinct PDC/nav markers.
- Create dedicated holder classes for Teams view and picker mode, reusing shared rendering/item-building helpers.

**Requirements regardless of approach**
- All click handlers must validate viewer ownership and context.
- Returning to Teams view must rebuild from latest persisted state.
- Inventory titles/names should clearly distinguish Teams view vs “Select Soul for Team X Slot Y”.

### 3.5 Action bar / lore / messages

- Soul Focus lore must reflect team system (selected team, active team/status, brief controls including right-click Teams and quick activation control). Remove “Selected soul” / legacy group-capacity wording.
- Action bar/status messages during activate/recall/hotswap should reference teams and summon counts (e.g. recalled, activated X summons, skipped Y invalid slots).
- All new/updated user-facing text must use **summon(s)** terminology.

---

## 4. Database and Persistence Design

### 4.1 Additive schema changes

#### `soul_player_state` (add column)
- Add `selected_team INTEGER NOT NULL DEFAULT 1`.
- Migration helper: add column only if missing (same pattern as existing `addPlayerStateColumnIfMissing`).
- Runtime must clamp/normalize values outside `1..3` to `1`.

#### New table: `soul_teams` (preferred)
Create if not exists:
```sql
CREATE TABLE IF NOT EXISTS soul_teams (
  owner_uuid TEXT NOT NULL,
  team_index INTEGER NOT NULL,
  slot_index INTEGER NOT NULL,
  soul_id TEXT,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (owner_uuid, team_index, slot_index),
  FOREIGN KEY (soul_id) REFERENCES souls(soul_id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_soul_teams_owner ON soul_teams(owner_uuid, team_index);
CREATE INDEX IF NOT EXISTS idx_soul_teams_soul ON soul_teams(soul_id);
```

**Notes**
- `ON DELETE SET NULL` is desirable for automatic cleanup when a soul row is deleted; still perform explicit cleanup in service/repository flows for clarity and to handle caches/UI refreshes.
- If FK behavior is problematic with current SQLite setup/order, enforce integrity in repository/service logic and keep index/PK constraints.
- Enforce `team_index`/`slot_index` bounds in code before writes.

#### Legacy tables/columns retained
- Keep `soul_loadouts`, `selected_slot`, and `souls.group_capacity` intact for compatibility.
- Do not drop or repurpose destructively in this overhaul.

### 4.2 Repository API changes (`SoulRepository`)

Add methods (names indicative; keep consistent with existing style/synchronization/transaction patterns):

#### Player team state
- `int getSelectedTeam(UUID owner, int defaultCapacity)` / include in `PlayerSoulState` (extend record: add `selectedTeam`)
- `void setSelectedTeam(UUID owner, int selectedTeam, int defaultCapacity)`

#### Team slot CRUD / queries
- `UUID getTeamSlotSoul(UUID owner, int teamIndex, int slotIndex)`
- `Map<Integer, UUID> getTeamSlots(UUID owner, int teamIndex)` (slotIndex -> soulId, only non-null)
- `Map<Integer, Map<Integer, UUID>> listTeams(UUID owner)` or `List<TeamSlotRow>`
- `void setTeamSlot(UUID owner, int teamIndex, int slotIndex, UUID soulId)` (upsert; `null` clears)
- `void clearTeamSlot(UUID owner, int teamIndex, int slotIndex)`
- `void clearTeam(UUID owner, int teamIndex)`
- `void clearSoulFromAllTeams(UUID owner, UUID soulId)` and/or global cleanup by `soulId`
- `boolean isSoulAssignedToTeam(UUID owner, int teamIndex, UUID soulId, Integer excludingSlot)` (duplicate prevention helper)

#### Migration helpers
- `boolean hasAnyTeamData(UUID owner)`
- `void seedDefaultTeamsFromLegacy(UUID owner)` (idempotent per owner when no team data)

#### `PlayerSoulState` update
Extend existing record:
- from `(owner, essence, vaultCapacity, selectedSlot, pityJson, combatMode, soulTableTab)`
- to include `selectedTeam` (and keep legacy `selectedSlot` if needed for compatibility/migration; if retained, document clearly)

Update all call sites/constructors/queries accordingly.

### 4.3 Service API changes (`SoulService`)

Add team-focused methods and migrate call sites away from selected-soul/loadout-slot gameplay APIs:

#### Team state & editing
- `int getSelectedTeam(UUID owner)`
- `void setSelectedTeam(UUID owner, int teamIndex)`
- `Map<Integer, UUID> getTeam(UUID owner, int teamIndex)`
- `List/Map listTeams(UUID owner)`
- `boolean setTeamSlot(UUID owner, int teamIndex, int slotIndex, UUID soulId)` (validates ownership/duplicates/bounds)
- `void clearTeamSlot(UUID owner, int teamIndex, int slotIndex)`

#### Activation / recall
- `CastAttempt` (or new result type) `activateTeam(Player player, int teamIndex)`
- `boolean recallActiveTeam(Player player)` / result enum
- helpers to inspect active team state via tracker/runtime (`getActiveTeamIndex`, `hasActiveTeamSummons`)

#### Cleanup hooks
- On soul removal (salvage/extract/combine/delete): remove `soulId` from all team slots for owner (or globally if soul IDs unique) and invalidate caches/UI state as needed.
- On invalid-slot detection during load/activate/UI render: persist cleanup and notify when user-initiated.

#### Legacy API handling
- Deprecate or remove gameplay usage of `getSelectedSoul`, `attemptCastSelectedSoul`, selected-slot equip flows, and `effectiveGroupCapacity`-based spawn sizing.
- Keep compatibility wrappers only if needed by external/internal call sites; wrappers should map to team behavior or be clearly non-gameplay.

### 4.4 Migration logic (idempotent)

Implement migration during DB init/service initialization (choose a single consistent location; DB init + repository helper is acceptable).

**Per database/init**
1. Ensure `soul_teams` table and indexes exist.
2. Ensure `soul_player_state.selected_team` exists (default 1).

**Per owner (lazy or eager, but idempotent)**
1. `getOrCreatePlayerState` normalizes `selected_team` to 1..3.
2. If `hasAnyTeamData(owner)` is false:
   - create 12 slot rows for teams 1..3 / slots 0..3 with `soul_id NULL` (or implicit empty) as needed.
   - seed **Team 1** from legacy `soul_loadouts`/`selected_slot`:
     - collect legacy assignments in a stable order prioritizing `selected_slot` first, then other populated slots ascending.
     - place up to 4 unique valid owned souls into Team 1 slots 0..3 in that order.
     - ignore invalid/missing/duplicate legacy entries.
   - leave Teams 2 and 3 empty.
3. Mark/ensure no repeated reseeding once any team data exists.

**Legacy summons normalization**
- On plugin enable/reload boundary (or first activation per player), ensure legacy active summons do not conflict with team tracking. Preferred: recall/despawn tracked legacy summons once during migration/version transition if they cannot be confidently mapped to a team context.

---

## 5. Runtime Implementation Details

### 5.1 `SummonManager` refactor requirements

`SummonManager` currently spawns groups based on `minionsPerHit`/`maxActive` and applies soul snapshot stats divided by `groupSize`.

**Required changes**
- Add team-based spawn entrypoint(s), e.g. `spawnTeam(Player player, int teamIndex, List<SoulInstance> soulsInSlotOrder)` or `spawnResolvedTeam(...)` returning structured result (`spawned`, `failed`, spawned entities/UUIDs).
- For each soul entry spawn **exactly 1 summon** (never loop by old per-soul group count).
- Remove dependence on `effectiveGroupCapacity` / `groupCapacity` for spawn count and stat division.
- Update `applySoulSnapshotToMinion(...)`:
  - compute and apply full effective attack/health/other stats **without dividing by group size**.
  - keep existing safety clamps, attribute handling, slime size normalization timing, owner/progression bonuses, and reassert scheduling logic unless incompatible.
  - set `groupSize` assumptions to 1-equivalent behavior; remove `groupSize` parameterization if no longer needed.
- Replace/adjust capacity logic:
  - team max summons = configured slots per team (default 4)
  - global/per-type legacy caps used for old group spawning must be redefined or bypassed for team activation to avoid blocking valid 4-slot teams (define exact rule in config/service; default should allow full team spawn)
- Ensure summon spawn uses correct soul/profile/entity resolution per slot soul (mixed team).

### 5.2 Tracking and PDC metadata

Update tracker/runtime tagging so active summons can be recalled by active team and audited per source soul.

**PDC keys (use/introduce consistently via helper/constants)**
- Existing: owner, soul/profile/family identifiers (keep as needed)
- Add (recommended): `summon_team_index` (INTEGER) on each spawned summon in team mode
- Add (recommended): `summon_team_slot` (INTEGER 0..3) for deterministic mapping/debugging
- Keep per-summon `soul_id` (or equivalent) so traits/stats/combat/progression remain soul-specific

**Tracker responsibilities**
- Query active summons for owner and for owner+team.
- Determine active team index from tracked summons (all should match; if mixed due to legacy data, normalize by recalling all).
- Recall active team = despawn/remove all tracked summons for owner’s active team context.
- Cleanup/orphan detection must recognize valid team-tagged summons and not misclassify them.

### 5.3 `SoulService` cast flow replacement

Replace selected-soul cast path with team activation path:
- Soul Focus quick activation, Teams UI activate buttons, and `/gloved soul team activate` all call the same service-level activation method/result handling.
- `attemptCastSelectedSoul` and selected-slot logic should no longer drive gameplay. Remove or adapt command/item call sites.
- Team selection updates (`selected_team`) must be persisted and cache-invalidated appropriately.

### 5.4 Commands (`/gloved soul ...`)

Update command parsing/help/permissions to support team workflows. Minimum command surface:
- `/gloved soul team list` — list Team 1-3 with slot summaries and active/selected markers
- `/gloved soul team select <1-3>` — set selected team
- `/gloved soul team info [1-3]` — show detailed slots for a team
- `/gloved soul team activate <1-3>` — activate/recall/hotswap target team
- `/gloved soul team recall` — recall active team
- `/gloved soul team clear <1-3> <slot|all>` — clear slot(s)
- `/gloved soul team set <1-3> <slot> <soulRef>` — assign soul to slot (support implementation-feasible soul selector/id/short-id; if complex, prioritize UI and provide minimal command form)

**Requirements**
- Validate bounds/ownership/permissions.
- Reuse existing permission nodes where sensible; add new nodes only if necessary and document them.
- Provide migration-friendly messages for removed/changed legacy commands (e.g. old selected-slot equip/cast behavior).

### 5.5 Config (`summon.yml`, loaders, backfill)

Add team/cast settings with defaults aligned to this plan. Keep naming consistent with existing config style; if existing cast keys are present, map or supersede them clearly.

**Recommended keys (exact defaults)**
```yml
souls:
  teams:
    enabled: true
    count: 3
    slots-per-team: 4
    allow-same-soul-in-multiple-teams: true
    allow-duplicate-soul-in-same-team: false
    skip-invalid-slots-on-activate: true
  cast:
    activate-mana-cost: 0.0
    activate-cooldown-ticks: 0
    hotswap-cooldown-ticks: 0
    recall-cooldown-ticks: 0
    free-recall: true
```

**Implementation requirements**
- Resource default in `src/main/resources/summon.yml`.
- Runtime fallback/default values in config class(es).
- Backfill missing keys on enable/reload without overwriting admin values.
- Clamp invalid values (count/slots >=1 within supported bounds for this overhaul, cooldowns/mana >=0).
- Ensure reload path re-reads/applies team/cast settings safely.

### 5.6 Lore, previews, and stat displays

- Remove “Summons count”, group capacity, and group-division wording from soul lore, vault/team UI, inspect messages, and previews.
- Update damage/HP preview helpers (e.g. in `SoulService` and lore builders) to report **full per-summon** values (no “pre group-division” divided-at-spawn semantics).
- Ensure Soul Focus lore describes team controls/status (right-click Teams, quick activation control, selected/active team) using **summon(s)** terminology.

---

## 6. File-by-File Implementation Guidance

> Use this as the primary checklist; adjust for actual code structure discovered during implementation while preserving behavior.

### 6.1 Database and repository
- `summon/soul/db/SoulDatabase.java`
  - add `selected_team` column migration for `soul_player_state`
  - create `soul_teams` table and indexes
  - keep migrations additive/idempotent
- `summon/soul/db/SoulRepository.java`
  - extend `PlayerSoulState` and state queries/inserts
  - add team CRUD/query/cleanup/migration methods
  - update soul deletion/removal paths to clear team references (or rely on FK + explicit cleanup)

### 6.2 Domain/config/data
- `summon/soul/domain/SoulInstance.java`
  - retain `groupCapacity` field/serialization accessors for compatibility but mark deprecated/unused for gameplay; remove gameplay-facing assumptions
- `summon/soul/domain/SoulGenerator.java`
  - stop rolling meaningful/variable group capacity for new souls (set constant/default compatibility value, e.g. 1) and remove Summons-count affix/stat generation logic if tied to group capacity
- `summon/soul/domain/SoulConfig.java` and related config classes
  - add team/cast config fields, getters, defaults, load/backfill logic
- `SoulDropItem`/DTOs/lore builders/stat services
  - stop emitting/using group capacity in new displays and previews; keep read compatibility

### 6.3 Service and runtime
- `summon/soul/service/SoulService.java`
  - replace selected-soul/loadout-slot gameplay APIs with team APIs and activation flow
  - add team resolution/validation/cleanup and activation result handling
  - invalidate caches on team edits and soul mutations
  - ensure salvage/extract/combine/delete clear team slot references
- `summon/runtime/SummonManager.java`
  - implement per-slot single-summon team spawning
  - remove HP/DMG group division and `groupCapacity`-based spawn sizing
  - preserve stat application safety, reassert behavior, and per-summon setup
- `summon/runtime/SummonInstanceTracker.java` (+ spawn/listeners)
  - add team-aware tracking/tagging/recall/cleanup
  - support mixed-soul active teams
- `DamageProxy`, combat/AI/progression listeners/services
  - verify/update assumptions that expect same-soul group behavior; maintain per-summon soul attribution

### 6.4 UI and items
- `summon/soul/item/SoulItem.java` and Soul Focus listeners/handlers
  - revamp interactions per section 3.1 (right-click Teams view, quick activation affordance)
  - refresh lore/status for team system
- `summon/soul/ui/SoulVaultInv.java`
  - add assignment-picker mode/context or companion implementation reusing vault rendering
  - return-to-Teams navigation and slot assignment semantics
- New Teams UI inventory/holder/screen (e.g. `summon/soul/ui/SoulTeamsInv.java`)
  - render teams/slots/controls and handle clicks/navigation
- UI registration/routing (inventory click/drag/close listeners)
  - register handlers for Teams view and picker contexts

### 6.5 Commands and plugin wiring
- Soul command handlers
  - implement `team` subcommands and migrate/remove legacy selected-slot gameplay commands
- `core/GlovedPlugin.java`
  - wire any new UI listeners/services, ensure migration/init order, and preserve reload behavior

---

## 7. Edge Cases and Required Handling

| Scenario | Required behavior |
|---|---|
| Activate active team | Recall all active team summons instantly, free, no cooldown/mana; return recalled/toggled state |
| Activate different team while one active | Validate and recall current, then spawn target (hotswap); avoid ambiguous partial states |
| Team has empty slots | Skip empty slots |
| Team slot has missing/deleted/unowned soul | Persistently clear slot, skip, notify when user-triggered |
| Team has no valid slots after cleanup | Fail activation with clear message; no summons spawned |
| Duplicate soul in same team assignment attempt | Reject assignment; show feedback |
| Same soul in different teams | Allowed |
| Soul salvaged/extracted/combined/deleted | Remove from all team slots referencing it |
| Rapid repeated activation/clicks | Guard against race/duplicate spawns; no punitive GCD by default |
| Mana insufficient for activate/hotswap | Do not activate/spawn/hotswap-charge; recall remains available/free |
| Spawn failure mid-activation | Prefer rollback of newly spawned summons and return failure; do not leave inconsistent team state |
| Legacy active summons/group data encountered | Normalize safely (prefer recall/cleanup) before/at team activation |
| Reload/restart | Team presets/selected team persist; active summons follow existing runtime cleanup/recovery rules |
| Legacy `groupCapacity` data present | Ignored by gameplay/UI/stat division; compatibility-safe only |

---

## 8. Verification Matrix (must pass)

### 8.1 Build
- Run `./build.sh` successfully.

### 8.2 Functional tests
- **Migration:** legacy player with selected/loadout data gets Team 1 seeded correctly, `selected_team` defaults to 1, Teams 2-3 empty; rerun/restart is idempotent.
- **Soul Focus flow:** right-click opens Teams view; clicking slot opens vault picker for that slot; selecting soul assigns and returns to Teams; cancel/back returns without change.
- **Team editing:** assign/clear slots across Team 1-3, duplicate-in-same-team blocked, same soul allowed in different teams, persistence across relog/restart.
- **Activation:** activating a team spawns 1 summon per valid slot in order (mixed types), max/default team size respected.
- **Recall/hotswap:** active-team activation recalls instantly; activating different team recalls current and spawns new team.
- **Responsiveness:** recall is instant/free; rapid recall/reactivate/hotswap works with no default GCD (mana behavior per config).
- **Stat correctness:** summoned HP/DMG are full effective per-summon values (no group division); UI/previews/tooltips match runtime.
- **Invalid slot cleanup:** missing/deleted/unowned souls removed from slots on interaction/activation, skipped during spawn with clear feedback.
- **Lifecycle cleanup:** salvage/extract/combine/delete removes soul from all team presets.
- **Commands/UI parity:** team commands and UI controls perform equivalent operations correctly.
- **Regression:** combat attribution, traits/affixes, AI/follow/stuck recovery, progression hooks, and summon cleanup/orphan handling work for mixed active teams.

### 8.3 Manual QA scenarios (recommended)
- Summons stuck far away/blocked -> recall and immediately reactivate team.
- Hotswap repeatedly between teams with different compositions.
- Team with some empty and some invalid slots -> activation spawns remaining valid summons.
- Soul assigned to multiple teams, remove/salvage it -> all references cleaned.

---

## 9. Implementation Sequence (recommended)

1. **Schema/config groundwork:** DB migrations (`selected_team`, `soul_teams`), config keys/defaults/backfill/load.
2. **Repository/service foundation:** team CRUD/state/cleanup/migration APIs, `PlayerSoulState` updates, cache invalidation.
3. **Activation/runtime core:** team activation service flow, `SummonManager` per-slot spawning + no HP/DMG division, tracker team tagging/recall.
4. **UI revamp:** Teams view, Soul Focus right-click flow, vault picker assignment mode + return navigation, lore/action bar updates.
5. **Commands and compatibility cleanup:** team subcommands, remove/redirect legacy selected-soul flows, group-capacity UI/stat removal.
6. **Verification and tuning:** `./build.sh`, full QA matrix, adjust defaults/messages.

---

## 10. Implementation Notes / Guardrails

- Preserve additive compatibility (especially DB/item serialization) unless a change is explicitly required.
- Prefer transactional/atomic updates for multi-step state changes where repository patterns already support it.
- Keep all new/updated terminology as **summon(s)**.
- Do not reintroduce summon-group splitting by old `groupCapacity` in any runtime, preview, or display path.
- If a discovered implementation detail conflicts with this plan, preserve the **locked decisions in section 0** and adapt the lowest-risk technical detail while documenting the deviation in code comments/PR notes.

This plan is the implementation baseline for the Soul Team / Loadout Overhaul.
