# Soul Table UI/Logic Redesign Plan (Tab/Screen-based, Implementation-Ready)

## 0. Motivation, scope, and success criteria

### Current state (what exists today)
- **Monolithic UI:** `summon/soul/table/ui/SoulTableInv.java` (`InventoryHolder`, 54-slot) implements hub+tabs+grid+selection+preview+confirm+salvage-arming in one class.
  - Tabs: `COMBINE`, `ENHANCE`, `REFORGE`, `SALVAGE` (`TAB_SLOTS = {0,2,4,6}`).
  - Shared layout/state: `GRID_START=9`, `GRID_SIZE=36`, bottom controls `SLOT_PREV=45`, `SLOT_SEL_1..3=46-48`, `SLOT_ESSENCE=49`, `SLOT_CONFIRM=50`, `SLOT_CLEAR=51`, `SLOT_GUIDE=52`, `SLOT_INFO=8`, `SLOT_NEXT=53`.
  - PDC keys: `soul_table_soul_id` (soul UUID string), `soul_table_action` (action string).
  - Persists/restores last tab via `SoulService.getSoulTableTab/setSoulTableTab` / DB column `soul_player_state.soul_table_tab`.
- **Routing/open paths:**
  - `summon/soul/table/SoulTableListener#open(...)` opens `new SoulTableInv(plugin, player, row)`.
  - `summon/soul/table/ui/SoulTableUiRouter#handleClick(...)` routes clicks for `SoulTableInv` and `SoulTableGuideInv`.
  - `menu/listener/MenuHandler` has holder checks for `SoulTableInv` (e.g. drag/top-inventory handling) and should be updated to cover new holders consistently.
  - `summon/soul/table/ui/SoulTableGuideInv` action `back_table` reopens `SoulTableInv`.
  - `summon/soul/guide/SoulGuideListener` opens `SoulTableGuideInv` (standalone, `tableId == null`).
- **Service boundary (keep):** `summon/soul/table/SoulTableService` performs mutations/transactions (`combine`, `enhance`, `reforge`, `salvage`) and revalidation; UI must remain a client of this service.

### Problems
- Shared state/layout/click handling makes multi-step or state-heavy flows (e.g. planned Condense/Infusion) brittle and messy.
- Per-process UX is constrained by one bottom bar and overloaded confirm/selection semantics.

### Goals
- **Flow:** `Open Soul Table -> Hub (pick process) -> Dedicated process menu`.
  - Initial processes: **Combine, Enhance, Reforge, Salvage** (parity).
  - Architecture must support future **Condense, Infusion** as separate screens with multi-step/wizard state.
- **Architecture:** one `InventoryHolder`/screen per view, process-specific state, extracted shared UI components/utilities, and clear UI↔service boundaries.
- **Parity:** preserve existing gameplay rules, costs, previews, validations, salvage double-confirm arming, transaction safety, and feedback behavior (or improve clarity without changing outcomes).
- **Safe migration:** update all open/click/navigation paths; remove monolithic coupling; keep behavior compatible where data/preferences exist.

### Non-goals
- No balance/cost/formula changes, no DB schema changes (except only if a new optional preference key is explicitly added later; prefer reusing/ignoring existing safely), no Soul Vault redesign, no implementation of Condense/Infusion in this redesign (only readiness).

### Definition of done
- Soul Table opens **Hub**; each process opens its own menu; Back/Close/Guide navigation correct.
- Combine/Enhance/Reforge/Salvage behavior matches current semantics and uses `SoulTableService` for mutations.
- `MenuHandler`/routers/guide/listeners route all new holders correctly with no unsafe inventory interactions.
- Monolithic `SoulTableInv` removed or fully deprecated/replaced (no live references) after parity.
- `./build.sh` succeeds and manual parity checklist passes.

---

## 1. Target UX and navigation

### 1.1 Entry
- `SoulTableListener#open(Player, SoulTableRow)` opens **`SoulTableHubInv`** for the placed table (`tableId = row.tableId()`).
- Any other code path that opens the Soul Table GUI must open the Hub (not a process screen) unless explicitly restoring a process context.

### 1.2 Hub screen (`SoulTableHubInv`)
- 54-slot menu titled `Soul Table` (consistent branding), shows process choices as buttons:
  - Combine, Enhance, Reforge, Salvage, Guide (and later Condense/Infusion).
- Displays player Essence (and optional hints) but **no soul grid/selection**.
- Actions: open process screen, open guide (return to Hub), close.
- **Preference behavior:** do **not** auto-skip Hub into a process. Legacy `soul_table_tab` may be ignored on open (or used only to highlight/offer “Continue”, optional). Handle unknown/legacy values safely.

### 1.3 Dedicated process screens
Each process is its own screen with tailored layout/state:
- **Combine:** select up to 3 compatible souls (same family+rarity, not Mythic) → preview target/result + cost → confirm.
- **Enhance:** select 1 soul → preview level/cost/stat changes → confirm.
- **Reforge:** select 1 soul → preview scope/cost/current stats → confirm.
- **Salvage:** multi-select souls → preview total yield/count → **double-confirm arm window** → confirm.
- **Common controls:** Back (to Hub, rendered as the Soul Table block-texture button per §3.1/§3.4), Clear/Reset selection, Paging (prev/next) where grid exists, Guide (returns to same process screen), Close, Essence/cost/status display.
- **Future:** Condense (no soul grid, batch amounts) and Infusion (multi-step wizard: target → mode → trait/slot choice → preview/warnings/cost → confirm) implemented as additional screens without changing existing ones.

### 1.4 Navigation contract
- `Back` from a process screen opens Hub for same `viewerId/tableId`.
- `Guide` opens `SoulTableGuideInv` with an explicit **return target** (Hub or specific process screen). Back/close in guide restores that target/context.
- Process-local sub-steps (future wizards) use internal step state; “Back” behavior must be unambiguous (step back vs return to Hub — define per screen, default to Hub for initial 4 processes).

---

## 2. Package, files, and class design

### 2.1 New UI classes (`src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/`)

| Class | Responsibility |
|---|---|
| `SoulTableHubInv` | Entry/hub menu; process selection + guide/close. |
| `SoulTableCombineInv` | Combine flow (3 compatible souls). |
| `SoulTableEnhanceInv` | Enhance flow (single soul). |
| `SoulTableReforgeInv` | Reforge flow (single soul). |
| `SoulTableSalvageInv` | Salvage flow (multi-select + arm/confirm). |
| `SoulTableScreen` (interface, recommended) | Common contract for routing: `UUID getViewerId(); UUID getTableId(); Inventory getInventory(); void open(Player); void refresh(); void handleClick(Player,int,ClickType);` (or match existing style). |
| `SoulTableUiKeys` (final util) | Central PDC keys/action constants and action parsing helpers. |
| `SoulTableUiItems` / `SoulTableUiText` (util) | Shared item/chrome builders, lore/format helpers, glint/fillers, common buttons. |
| `SoulTableData` (util/helper) | Load/sort souls, build index, load essence, prune selections. |
| `SoulTableGrid` (helper) | Pagination + slot↔soul mapping/render for grid region. |
| `SoulSelection` / selection validators (util) | Selection rules per process and user-facing rejection reasons. |
| `SoulTableNav` (util) | Open hub/process/guide with return-target support. |

*Names are prescriptive defaults; if you choose different names, keep responsibilities equivalent and update all references consistently.*

### 2.2 Modified classes/files
- `summon/soul/table/ui/SoulTableGuideInv.java` — support return navigation targets (Hub/process) instead of always reopening `SoulTableInv`; preserve standalone guide behavior (`tableId == null`).
- `summon/soul/table/ui/SoulTableUiRouter.java` — route clicks for all new Soul Table holders (hub + process screens + guide). Prefer dispatch via common interface or explicit `instanceof` chain.
- `summon/soul/table/SoulTableListener.java` — `open(...)` creates/opens `SoulTableHubInv` (not `SoulTableInv`).
- `menu/listener/MenuHandler.java` — include new holders in top-inventory drag/click protections and any Soul Table-specific handling (replace `instanceof SoulTableInv` checks with coverage of all new holders / shared marker).
- `summon/soul/guide/SoulGuideListener.java` — keep opening guide; ensure back/close behavior remains correct (standalone returns/close as today).
- `summon/soul/service/SoulService.java` (only if needed) — optional helpers for last-process preference (or leave `get/setSoulTableTab` unused-but-safe); no behavior-breaking changes required.
- Any other open sites: search for `new SoulTableInv` and replace with hub/process-appropriate navigation.

### 2.3 Removed/deprecated
- `summon/soul/table/ui/SoulTableInv.java` — delete after all references are migrated and parity verified (preferred). Do not leave dead routing paths.

---

## 3. Shared contracts and utilities (concrete)

### 3.1 PDC keys and actions (`SoulTableUiKeys`)
Centralize to avoid mismatches:
- `NamespacedKey SOUL_ID = new NamespacedKey(plugin, "soul_table_soul_id")` (UUID string) — reuse existing key name for continuity.
- `NamespacedKey ACTION = new NamespacedKey(plugin, "soul_table_action")` — reuse existing key name.
- **Common actions:** `back_hub`, `close`, `guide`, `prev`, `next`, `clear`, `confirm`.
  - `back_hub` / “Back to Soul Table Hub” buttons must render with the **Soul Table custom block/item texture** (use `summon/soul/table/SoulTableItem` — e.g. clone `SoulTableItem.create(plugin)` or its display/model meta so `gloved:soul_table` item model is preserved — then override name/lore/PDC action for UI context such as `§dBack to Soul Table Hub`). Do **not** use a generic material icon for this control.
- **Hub actions:** `open_combine`, `open_enhance`, `open_reforge`, `open_salvage`.
- **Process-specific actions (as needed):** e.g. `toggle_mode_*`, `select_trait_slot:*`, `condense_*` (future) — keep namespaced/parseable (`prefix:value`).
- Helper methods: `String readAction(ItemStack)`, `UUID readSoulId(ItemStack)`, `void tagAction(ItemMeta, String)`, `void tagSoul(ItemMeta, UUID)`.

### 3.2 Data loading and sorting (`SoulTableData`)
- Mirror current `SoulTableInv#loadData()` semantics for parity:
  - Load all owned souls via `SoulService.listSoulsFiltered(viewerId, null, null, p, 50)` paging until short page (cap pages defensively).
  - Sort: rarity desc, then quality desc (`SoulQuality.percent`), then `createdAt` desc.
  - Build `Map<UUID, SoulInstance> index`.
  - Load essence `SoulService.getEssence(viewerId)`.
  - Prune selections not present in index.
- Expose `List<SoulInstance> souls()`, `Map<UUID,SoulInstance> index()`, `int essence()`.

### 3.3 Grid helper (`SoulTableGrid`)
- Configurable grid region (`startSlot`, `size`, columns/rows assumptions for 54-slot chest). Default for initial screens: `start=9`, `size=36` (rows 1–4), same as today, unless a screen needs a different layout.
- Computes `maxPage`, clamps `page`, places soul items for current page, and wires prev/next actions to bottom bar.
- Renders soul items using shared renderer (see 3.4) with selection highlighting state.

### 3.4 Item/chrome helpers (`SoulTableUiItems`)
- Reuse/port current visual behavior for consistency: filler panes, action items (named + lore + action tag), glint via `ItemMeta#setEnchantmentGlintOverride(true)` with fallback, soul item rendering (material via `SoulDropItem.materialFor`, name/quality, traits/stats lore via `SoulLoreBuilder` with `viewerId` and `showAffixQuality=true` in table contexts), selection/confirmation states.
- Common buttons: Back to Hub, Close, Guide, Prev/Next, Clear, Confirm (enabled/disabled/armed variants), Essence display.
  - **Back to Hub button visual:** always use the Soul Table block texture/model (`SoulTableItem`-based item with `gloved:soul_table` model preserved, UI-specific name/lore/action) in all process screens and in guide contexts that return to the Soul Table hub/process flow.

### 3.5 Selection rules (`SoulSelection`)
- **Combine:** max 3; when adding, reject if Mythic (`"Mythic souls cannot be combined."`), reject if >3, reject if family/rarity mismatch with existing (`"Souls must share the same family and rarity"`). Toggle off if already selected.
- **Enhance/Reforge:** single-select toggle (selecting another replaces).
- **Salvage:** multi-select toggle (add/remove freely).
- Return structured result/reason so UI can send action-bar feedback identical/close to current messages.

### 3.6 Navigation (`SoulTableNav`) and return targets
- `openHub(plugin, player, tableId)` → new `SoulTableHubInv(...).open(player)`.
- `openProcess(plugin, player, tableId, process)` → corresponding screen.
- `openGuide(plugin, player, tableId, page, returnTarget)` where `returnTarget` encodes destination: `HUB`, `COMBINE`, `ENHANCE`, `REFORGE`, `SALVAGE` (and later others) plus optional minimal context if needed.
- **Guide changes:**
  - Replace hardcoded `openSoulTable(...) -> new SoulTableInv(...)` with navigation to the return target (Hub by default when opened from Hub; specific process when opened from that process). Preserve `tableId == null` standalone behavior (back/close exits appropriately).
  - Keep existing page system/actions; only change return semantics.

---

## 4. Screen specifications (concrete)

> Use 54-slot chest inventories. Layouts below are prescriptive baselines; keep regions consistent and update click/slot logic together if adjusted. All non-interactive filler slots must be tagged/handled so clicks/drags are cancelled by holder routing as today.

### 4.1 `SoulTableHubInv`
- **Title:** `Soul Table` (purple, consistent with current).
- **State:** `plugin, viewerId, tableId`.
- **Suggested layout:**
  - `10` Combine, `12` Enhance, `14` Reforge, `16` Salvage (process buttons with descriptions).
  - `31` Guide, `49` Essence info (read-only), `53` Close (or similar). Fill rest.
  - Hub is the destination screen; any return-to-hub controls shown in process screens/guide must use the Soul Table block-texture item (see §3.1/§3.4), not a generic icon.
- **Actions:** `open_combine|open_enhance|open_reforge|open_salvage`, `guide` (return `HUB`), `close`.
- **Behavior:** clicking process opens corresponding screen; no soul data required except essence display (optional lightweight fetch).

### 4.2 `SoulTableCombineInv`
- **Title:** `Soul Table - Combine` (or equivalent clear title).
- **State:** `page`, `LinkedHashSet<UUID> selected` (max 3), `List<SoulInstance> souls`, `Map<UUID,SoulInstance> index`, `essence`.
- **Layout:**
  - Grid: `9-44` (36 slots), paged.
  - Bottom: `45` Prev, `46-48` selected soul displays (slots 1-3), `49` cost/essence/target info, `50` Confirm, `51` Clear, `52` Guide, `53` Next. `0-8` info/header/fillers incl. Back.
  - `0` (or dedicated) Back→Hub.
- **Preview/confirm logic (parity with `SoulTableInv`):**
  - Need exactly 3 selected; target = `config().nextRarity(first.getRarity())` (null/invalid if Mythic) — block accordingly.
  - Cost: `service.essenceCostCombine(target)` (fallback config as today). Show target rarity + family, “Not enough Essence” if unaffordable.
  - Confirm: `service.combine(viewerId, ids, target)`, on success clear selection/refresh/feedback `Combined into <target>.` (or service message), on failure show reason and refresh.
- **Actions:** common + soul-id toggles.

### 4.3 `SoulTableEnhanceInv`
- **State:** `page`, `selected` (size 0 or 1), data/essence.
- **Layout:** grid `9-44`; `45` Prev, `46` selected soul, `49` cost/info, `50` Confirm, `51` Clear, `52` Guide, `53` Next; header includes Back→Hub.
- **Preview/confirm parity:**
  - Need 1 soul; reject if at `config.getEnhanceMaxLevel()`.
  - Cost: `service.essenceCostEnhance(level)`; show level `L -> L+1`, per-level bonus, and **next-summon preview** (HP/DEF/ATK/MV/APS/Summons) using same computation path as current `SoulTableInv` (`SoulService` + `SoulLoreBuilder` formatting).
  - Confirm: `service.enhance(viewerId, soulId)`.

### 4.4 `SoulTableReforgeInv`
- **State:** `page`, `selected` (single).
- **Layout:** grid + single selected slot + info/cost + confirm/clear/guide/back/paging.
- **Preview/confirm parity:**
  - Need 1 soul; cost `service.essenceCostReforge(rarity)`.
  - Show scope: rerolls affixes, base stats, and Summons capacity at current rarity; preserves family, rarity, enhancement level, identity (match current text/behavior). Include current stats section (`SoulLoreBuilder.statsSection(plugin, soul, viewerId, true)`).
  - Confirm: `service.reforge(viewerId, soulId)` (clear or keep selection per UX choice; default clear on success for consistency).

### 4.5 `SoulTableSalvageInv`
- **State:** `page`, `LinkedHashSet<UUID> selected` (multi), `long salvageArmedUntil`, data/essence.
- **Layout:** grid + summary/selected indicators + total yield info + confirm/clear/guide/back/paging.
- **Preview/confirm parity:**
  - Need >=1 soul; total yield = sum `soulService.essenceValueOf(rarity)` (fallback static as today).
  - **Arming:** if unarmed and `config.getSalvageConfirmationSeconds() > 0`, set `salvageArmedUntil = now + seconds*1000`, refresh, schedule timed refresh (`runTaskLater`) to update expiry while open; if `seconds <= 0` execute immediately. If armed and not expired, execute salvage.
  - Confirm UI states/text: unarmed prompt vs armed “Click again to confirm” with expiry countdown (match current behavior/messages closely).
  - Execute: `service.salvage(viewerId, ids)`, reset armed state, clear selection on success, feedback/refresh.
  - Reset `salvageArmedUntil` on selection changes/clear/navigation-refresh actions as appropriate (parity with current toggle/clear logic).

### 4.6 `SoulTableGuideInv` (updated)
- Add return-target support (e.g. constructor params or a small `ReturnTarget` enum + context). Default: `HUB` when `tableId != null` unless specified; standalone (`tableId == null`) keeps close/no-table behavior.
- `back_table` action becomes “return to Soul Table context”: open Hub or originating process per return target. Its item must use the Soul Table block texture/model (`SoulTableItem`-based, UI name/lore/action applied) consistent with process-screen Back-to-Hub controls. `page:*` and `back_main` behavior preserved.
- When returning to Hub/process for a placed table, resolve `tableId` via `SoulTableManager` as today and schedule open on main thread if needed; if table missing, close gracefully.

---

## 5. Routing and integration changes (concrete)

### 5.1 `SoulTableUiRouter`
- Update `handleClick(...)` to dispatch to every Soul Table screen holder implementing the common contract/interface (or explicit checks for Hub/Combine/Enhance/Reforge/Salvage/Guide). Preserve `event.setCancelled(true)` and pass `player, rawSlot, clickType`.
- Ensure it is the single routing point used by `MenuHandler` for soul-table UIs (or keep logic consistent if MenuHandler routes directly).

### 5.2 `MenuHandler`
- Replace/extend all `instanceof SoulTableInv` holder checks with coverage for new holders:
  - Drag cancellation over top inventory for any Soul Table screen (`SoulTableHubInv`, all process screens, `SoulTableGuideInv`) — same safety behavior as current.
  - Click routing: delegate to `SoulTableUiRouter` or holder handlers for these screens (consistent with existing soul table/vault handling patterns in this class).
- Keep Soul Vault, Menu, and other GUI behavior unchanged.

### 5.3 Open paths
- `SoulTableListener#open`: `new SoulTableHubInv(plugin, player, row).open(player);` (or via `SoulTableNav`).
- Replace all `new SoulTableInv(...)` usages (listener, guide back, any commands/services) with hub/process navigation as appropriate.
- `SoulGuideListener` continues to open guide in standalone mode.

### 5.4 Click/drag safety
- All Soul Table screens cancel top-inventory interactions that could move GUI items; only respond to tagged actions/soul items. Bottom inventory remains player-controlled except where existing protections apply.
- Reuse/centralize action+soul PDC tagging to prevent unhandled clicks from causing item movement.

---

## 6. Data, config, and preference handling

- **No DB schema changes.** Continue using `soul_player_state.soul_table_tab` read/write APIs only if needed for backward compatibility; do not depend on them for correctness. Safe behavior: ignore unknown values, do not auto-open into removed monolithic tab states.
- **Essence/cap/cost/config:** use `SoulService`, `SoulTableService`, and `SoulTableConfig` exactly as today for parity (including fallbacks).
- **Stat/preview rendering:** reuse `SoulLoreBuilder` and existing service computations for enhance/reforge/soul display to avoid logic drift.

---

## 7. Phased implementation steps (agent-executable)

### Phase 1 — Shared foundation
1. Create `SoulTableUiKeys` (PDC keys/actions/helpers), shared item/chrome utilities, `SoulTableData`, grid/selection helpers, and navigation/return-target types.
2. Introduce `SoulTableScreen` interface (or equivalent) implemented by new screens for uniform routing.
3. Keep code compiling; do not switch open paths yet except behind new classes.

### Phase 2 — Implement screens with parity
4. Implement `SoulTableHubInv` (process selection, essence info, guide/close).
5. Implement `SoulTableCombineInv` (port combine rules, preview, cost, confirm).
6. Implement `SoulTableEnhanceInv` (port single-select, level-cap, next-summon preview, confirm).
7. Implement `SoulTableReforgeInv` (port preview/scope, cost, confirm).
8. Implement `SoulTableSalvageInv` (port multi-select, yield, arm timer/confirm, execute).
9. Update `SoulTableGuideInv` for return-target navigation and reuse shared helpers where sensible.

### Phase 3 — Wire routing and entry points
10. Update `SoulTableUiRouter` and `MenuHandler` to recognize/route all new holders and cancel drags/clicks safely.
11. Change `SoulTableListener#open` and all `new SoulTableInv` call sites to Hub/process navigation (guide `back_table` → return target).
12. Verify standalone guide and Soul Vault flows unaffected.

### Phase 4 — Cleanup and parity hardening
13. Remove `SoulTableInv.java` and obsolete imports/references; consolidate duplicated logic into shared utilities.
14. Align titles/messages/disabled reasons with current behavior (or clearer equivalents) and ensure selections prune/refresh correctly on success, failure, and stale data.
15. Confirm no behavior relies on legacy tab auto-restore in a broken way.

### Phase 5 — Future-readiness check
16. Sanity-check that adding `Condense` and multi-step `Infusion` screens requires only: new screen classes/state, new service hooks (per `plans/soul-infusion-and-condensation.md`), hub buttons, router/handler registration, and nav entries — no changes to Combine/Enhance/Reforge/Salvage internals.

---

## 8. Edge cases, safeguards, and parity rules
- **Authoritative validation:** UI prechecks for UX; `SoulTableService` revalidates in DB transactions (ownership, existence, compatibility, caps, essence). Always handle `TableResult` failure reasons.
- **Stale state:** after any mutation or detected invalid selection, refresh from source and prune missing soul IDs.
- **Salvage:** arm timeout honored via scheduled refresh; selecting/clearing resets arm; confirm only executes when armed (or zero-second config path) per current logic.
- **Concurrency/races:** double-clicks/rapid confirms guarded by UI state where practical; service transactions prevent inconsistent commits.
- **Permissions/ownership:** viewer must match `viewerId`; table access/privacy/break rules remain enforced by `SoulTableListener`/manager as today (UI redesign does not change block access rules).
- **Navigation safety:** guide/process always has a valid return path (Hub or close) even if table row missing.

---

## 9. Verification checklist

### Build
- Run `./build.sh` and ensure `BUILD SUCCESSFUL`.

### Functional parity
- **Hub:** placed Soul Table opens Hub; buttons open correct process screens; Guide/Close work.
- **Combine:** 3-soul limit, same family+rarity required, Mythic blocked, correct target/cost/essence checks, service result handled, selection cleared/refreshed on success.
- **Enhance:** single-select, max-level blocked, correct cost and next-summon stat preview, service result handled.
- **Reforge:** single-select, correct cost/scope/current-stats preview, service result handled.
- **Salvage:** multi-select, correct total yield, double-confirm arm/countdown/timeout/reset behavior, service result handled.
- **Guide:** pages work; return navigation goes to Hub or originating process as designed; standalone guide unaffected.

### Safety/regression
- No item movement/duplication via clicks/drags in any Soul Table screen; bottom inventory behaves normally where allowed.
- `MenuHandler`/`SoulTableUiRouter` route all new holders; no unhandled Soul Table inventories.
- Soul Table block place/break/interact/open protections unchanged; Soul Vault and other menus unaffected.
- Rapid clicks, reopen/close, and stale soul selections handled gracefully with clear feedback.

### Readiness
- Confirm a new multi-step process screen can be added without modifying existing process screen logic (only registration/navigation/hub entry).
