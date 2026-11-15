# Soul Table Infusion + Essence Condensation — Detailed Implementation Plan (for Hub + Screen-based Soul Table UI)

## 0. Summary

Implement two new Soul Table processes and supporting systems on top of the **new screen-based Soul Table UI** (`SoulTableHubInv` + dedicated `SoulTableScreen` process views, shared `SoulTableUi*`/`SoulTableNav`/`SoulTableReturnTarget` routing):

1. **Condense** process: convert Soul Essence ↔ a new **Condensed Soul Essence** material (default **128:1**, reversible) via an intuitive exchange screen. Designed to be accessible as an **early taste** of the economy/progression loop while remaining relevant at endgame.
2. **Infusion** process (endgame, mastery): **add** a trait (if below cap) or **replace** an existing trait with a **player-chosen** trait, consuming **high Essence + Soul Catalyst(s)** with endgame prerequisites.
3. **New items/recipe**: PDC-tagged `Condensed Soul Essence` and `Soul Catalyst` items; a registered crafting recipe to craft catalysts from condensed essence + high-end materials (configurable).
4. **Service/domain/persistence/config/UI/guide/pack/navigation** updates required to support the above, with transactional safety, cache invalidation, and strong preview/clarity UX.

**Key constraints**
- No DB schema changes. Trait storage remains the serialized trait-ID list in `souls.trait` (existing `SoulRepository` serialization/deserialization).
- Infusion/add-replace is deterministic (player-selected trait), not random.
- Endgame gating: Essence + Soul Catalyst(s) + prerequisites (rarity/enhancement/action), not essence-only.
- Fits the current UI architecture: each process is its own `InventoryHolder`/`SoulTableScreen` with process-local state; existing Combine/Enhance/Reforge/Salvage screens must not be modified except shared registration/navigation touchpoints.
- Build/verify with `./build.sh` only. Config backfills must follow repo convention: bundled `src/main/resources/summon.yml` + runtime fallback in loader/config + `GlovedPlugin.onEnable`/backfill path (and live server yml for immediate effect when needed).

**Design intent (fun, retention, buildcraft)**
- **Incremental progression:** players can make visible progress in steps (condense batches, craft catalysts, perform targeted replaces/adds) rather than all-or-nothing upgrades.
- **Meaningful agency with clarity:** player-chosen traits, strong before/after previews, synergy/warning callouts, and clear costs/prereqs.
- **Endgame sink with tradeoffs:** infusion is powerful but constrained (caps, prerequisites, costs, situational traits) so builds remain interesting and not trivially “perfect”.
- **Early taste, late mastery:** Condense is available earlier (or at lower barriers) while top-tier Infusion remains a true endgame goal.

---

## 1. Locked decisions and defaults (tunable/configurable)

### 1.1 Conversion and materials
- **Condense rate:** `128` Essence = `1` Condensed Soul Essence (config `souls.table.essence.condense.rate`, default 128). Reversible by default (`souls.table.essence.condense.reversible`, default true).
- **Early access / progression tuning:** Condense is enabled earlier than Infusion by default (`souls.table.essence.condense.enabled: true` with no high rarity gate). Optional tuning hooks allow lighter early engagement without trivializing endgame (e.g. configurable rate, reversible on/off, or future bonus-yield hooks); Infusion remains the primary high-cost sink.
- **Condensed Soul Essence:** custom PDC-tagged item (stackable material, block-themed). Default material `AMETHYST_BLOCK` (configurable), display `§bCondensed Soul Essence`, lore indicating essence value/rate and primary uses. Not placeable as a special block initially unless a dedicated block system is added; functions as a crafting/material item.
- **Soul Catalyst:** custom PDC-tagged item (stackable). Default material `NETHER_STAR` (configurable), display `§dSoul Catalyst`, lore indicating use in Infusion and future systems/recipes.
- **Catalyst recipe:** vanilla crafting recipe (registered, reload-safe), **shaped** by default, consuming condensed essence + high-end materials. Default (configurable in `souls.table.catalyst.recipe.*`):
  - Pattern: `NEN`, `ECE`, `NEN`
  - Ingredients: `N=NETHERITE_INGOT`, `E=ECHO_SHARD`, `C=<Condensed Soul Essence item>` (custom ingredient/result handling as needed)
  - Result: `1` Soul Catalyst
  - If custom-ingredient shaped recipes are cumbersome with Bukkit API in this codebase, implement a robust alternative: register a normal recipe with a placeholder material key and validate/replace via prepare-craft/craft listeners, or use an unshaped recipe of tagged components — but **prefer a clean, reload-safe implementation consistent with existing recipe managers**.
- **Grind/balance hook (extensible):** config structure should allow future bonus sources of condensed essence/catalysts from endgame activities (drops/rewards integration points) without redesigning the core systems; initial implementation may be table-conversion + crafting only, but avoid hardcoding assumptions that prevent later reward hooks.

### 1.2 Infusion rules
- **Operations:** `ADD_TRAIT` and `REPLACE_TRAIT`, player chooses the target trait.
- **Trait eligibility for selection:** must exist in `SoulTraitRegistry` (active), be a known/canonical trait, and not duplicate a trait already on the soul (for replace, the trait being replaced may be re-eligible only if different — effectively exclude current soul traits except the replaced slot is being removed).
- **Generation-only roll conditions:** `SoulTraitDefinition.canRoll(...)` / `rollIf` are **not** hard blockers in infusion (endgame explicit choice). UI shows a **warning** when a chosen trait is situational/useless for the target soul (e.g. `PACKBOUND` and effective group capacity `<= 1`, computed with enhance level/capacity parity). Optional config flag `souls.table.infusion.strict-roll-conditions` (default `false`) can make such traits unselectable later if desired.
- **Trait caps (add):** a soul may not exceed its infusion trait cap. Default cap = **natural max rollable trait count for the soul’s rarity** (same rarity distribution maxima as `SoulGenerator`: COMMON 1, UNCOMMON 1, RARE 3, EPIC 3, LEGENDARY 4, MYTHIC 5). Config-overridable per rarity via `souls.table.infusion.trait-cap.<RARITY>`. Caps preserve tradeoffs and prevent fully “solved” souls.
- **Prerequisites (defaults, configurable):**
  - Infusion enabled: `souls.table.infusion.enabled` (default `true`).
  - Min rarity: `souls.table.infusion.min-rarity` (default `EPIC` to position Infusion as endgame mastery; tunable, e.g. RARE for earlier access).
  - Min enhance level: `souls.table.infusion.min-enhance-level` (default `5`, tunable).
  - Target soul must be owned, exist, and not be in a state that violates invariants.
- **Costs (defaults, configurable, endgame):** Essence + catalysts, scaled by rarity and action. **Replace is intentionally cheaper than Add** to encourage iterative build refinement without trivializing trait acquisition.
  - Essence: `souls.table.costs.infusion.essence.<ACTION>.<RARITY>` (ACTION = `ADD`/`REPLACE`) with high defaults (example: ADD RARE 500, EPIC 1200, LEGENDARY 3000, MYTHIC 7000; REPLACE ~50–60% of add, all tunable).
  - Catalysts: `souls.table.costs.infusion.catalysts.<ACTION>` (default ADD 2, REPLACE 1) or rarity-scaled if desired.
  - Cost/payment is validated and charged atomically with the trait mutation where possible (Essence in DB tx; catalysts/items with safe inventory transaction handling).

### 1.3 Persistence / behavior
- No schema migration. `trait` column continues to store JSON array of trait IDs (`SoulRepository.serializeTraits/deserializeTraits`).
- Trait changes take effect on **next summon/spawn snapshot** (consistent with enhancement). Active summoned minions are not live-mutated; caches/snapshots for the soul are invalidated.

---

## 2. Files to create / modify (implementation map)

### 2.1 Domain & persistence
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/domain/SoulInstance.java`
  - Add `public SoulInstance withTraits(List<SoulTrait> traits)` returning a copy with normalized traits (reuse `normalizeTraits` semantics), preserving `trait` primary/legacy field consistency (`getTrait()` remains first or null as today).
  - Optionally add helpers: `int traitCount()`, `boolean hasTraitId(String)`.
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/db/SoulRepository.java`
  - Add `public synchronized void updateSoulTraits(UUID soulId, List<SoulTrait> traits) throws SQLException` executing `UPDATE souls SET trait = ? WHERE soul_id = ?` using `serializeTraits(traits)`.
  - Keep null/empty handling consistent with existing serialization (`[]`).
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/service/SoulService.java`
  - Ensure `invalidateSoul(UUID soulId, UUID ownerId)` (or equivalent existing invalidation) clears any cached soul instance/snapshot/stat caches so infused traits are reflected. Add helper if needed for infusion results.

### 2.2 Custom items & recipes
- **Create** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/item/CondensedSoulEssenceItem.java` (or similar package)
  - `NamespacedKey pdcKey(Plugin)` e.g. `gloved:condensed_soul_essence`
  - `NamespacedKey modelKey()` if custom model used (e.g. `gloved:condensed_soul_essence`)
  - `ItemStack create(Plugin, int amount, int essencePerUnit)` with PDC tag, display name, lore (shows essence value/rate), item flags; material from config/default.
  - `boolean isCondensed(ItemStack)` null-safe PDC check.
  - `int countInInventory(Player)` / `boolean removeFromInventory(Player, int)` utilities (exact tagged-item matching) for safe consumption.
- **Create** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/item/SoulCatalystItem.java`
  - PDC key `gloved:soul_catalyst`, model key optional, `create(...)`, `isCatalyst(...)`, inventory count/remove helpers.
- **Create** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulCatalystRecipeManager.java`
  - Mirrors `SoulTableRecipeManager`: holds `NamespacedKey key = new NamespacedKey(plugin, "soul_catalyst")`, registers from config, `removeRecipe` before add, logs outcome, supports reload-safe re-registration.
  - Uses `SoulTableConfig` (or dedicated config access) for pattern/ingredients/result/enabled.
  - If recipe requires custom condensed-essence ingredient matching: implement supporting listener/recipe-choice handling or fallback recipe strategy (documented in code) so crafted result is only granted when valid tagged materials are used.
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableItem.java` only if sharing utilities/constants; otherwise keep separate.

### 2.3 Config (bundled + fallback + backfill)
- **Modify** `src/main/resources/summon.yml`
  - Add defaults under `souls.table.essence.condense.*`, `souls.table.catalyst.*` (item/recipe), and `souls.table.infusion.*` / `souls.table.costs.infusion.*` (see §4).
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableConfig.java`
  - Add fields/getters/load/backfill for all new keys (preserve existing values, never overwrite admin-set keys).
  - Provide helpers: `int condenseRate()`, `boolean condenseReversible()`, `boolean condenseEnabled()`, `Material condensedMaterial()`, `Material catalystMaterial()`, recipe getters, `boolean infusionEnabled()`, `SoulRarity infusionMinRarity()`, `int infusionMinEnhanceLevel()`, `int infusionTraitCap(SoulRarity)`, `int infusionEssenceCost(action, rarity)`, `int infusionCatalystCost(action)`, `boolean infusionStrictRollConditions()`, etc.
  - Ensure `reload(FileConfiguration)` applies runtime fallbacks matching bundled defaults.
- **Modify** `src/main/java/dev/gloved/glovedplugin/core/GlovedPlugin.java`
  - Backfill new keys on enable (consistent with existing soul/table backfill behavior).
  - Instantiate/register `SoulCatalystRecipeManager` alongside `SoulTableRecipeManager`; re-register on reload.
  - If resource pack generation must include new item models/textures, invoke/extend pack service during the same soul-table pack generation flow and reload path.

### 2.4 Resource pack / visuals
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTablePackService.java`
  - Add item model JSON and texture entries for condensed essence and catalyst (deterministic/procedural textures consistent with existing pack generation style, or minimal distinct textures).
  - Copy/deploy behavior unchanged (writes `SoulTable.zip` / copies to RSPM mixer and triggers reload as today) so new assets ship with the existing pack artifact.

### 2.5 Service layer (transactions & game logic)
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableService.java`
  - Inject/hold references needed for item creation (plugin/config) if not already available.
  - **Condense APIs (Essence is DB state; items are inventory state):**
    - `TableResult condenseEssence(Player player, int units)` where `units` = number of condensed items to create (or a clearly documented unit) — UI passes units.
      1. Validate enabled/rate/units > 0; compute `essenceCost = units * rate`.
      2. Validate player online and inventory can receive `units` condensed items (stacking-aware simulation) before mutating.
      3. In DB transaction: `require/load owner state`, `spendEssence(owner, essenceCost, cap)`; fail `NOT_ENOUGH` on false.
      4. Grant condensed items to player inventory. If grant fails unexpectedly, **compensate** by refunding essence in a best-effort follow-up transaction and return failure (log debug) to avoid loss.
      5. Invalidate/update any cached essence displays as needed; return success with counts.
    - `TableResult decondenseEssence(Player player, int units)` (if reversible):
      1. Validate player has `units` tagged condensed items and that adding `units * rate` essence is possible within vault/cap rules.
      2. Remove condensed items from inventory first (or reserve) only after confirming add is possible; in DB transaction `addEssence(owner, gained, cap)`.
      3. On DB failure after removal, best-effort restore items; on success return result.
    - *Note:* because inventory and DB are separate systems, implement ordering + compensation carefully (prefer: check → DB spend/add in tx → inventory mutate → compensate on inventory failure) and keep operations idempotent-ish per click.
  - **Infusion APIs:**
    - `TableResult infuseAddTrait(UUID owner, Player player, UUID soulId, String traitId)`
    - `TableResult infuseReplaceTrait(UUID owner, Player player, UUID soulId, String replaceTraitId, String newTraitId)`
    - Shared validation helpers: resolve soul ownership, check prerequisites, resolve/canonicalize trait IDs via registry, enforce caps/duplicates, compute costs, detect situational warnings (for UI preview helper methods, not only on commit).
    - **Commit sequence (dupe/loss safe):**
      1. Synchronous prechecks on main thread context as needed: player online, has required catalyst count (tagged items) in inventory, inventory state valid.
      2. DB transaction: re-load soul (`requireOwned`), validate unchanged eligibility/state, validate trait rules, compute essence cost, `spendEssence`; `updateSoulTraits`; build updated soul.
      3. After successful DB commit: remove catalyst items from player inventory (exact count). If removal fails (race/desync), **log warning**, attempt compensating refund of essence and revert trait update in a compensating transaction if feasible, and return a clear failure; prioritize no-dupe over no-friction.
      4. `soulService.invalidateSoul(...)`; return `TableResult.ok(...)` with updated soul.
    - Provide non-mutating preview/query helpers for UI: `boolean canInfuseAdd(...)`, `boolean canInfuseReplace(...)`, `int infusionEssenceCost(...)`, `int infusionCatalystCost(...)`, `List<SoulTraitDefinition> eligibleInfusionTraits(SoulInstance, @Nullable String replacingTraitId)`, `List<String> infusionWarnings(SoulInstance, SoulTraitDefinition)` (includes situational/useless-trait warnings and synergy notes where applicable).

### 2.6 UI — screen-based Soul Table integration (new architecture)

Do **not** reintroduce a monolithic tabbed `SoulTableInv`. Implement Condense and Infusion as dedicated `SoulTableScreen`s and wire them into the existing hub/navigation/routing/guide system.

- **Create** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableCondenseInv.java`
  - Implements `SoulTableScreen` (`viewerId`, `tableId`, `inventory`, `open`, `refresh`, `handleClick`).
  - Process-local state only (no soul selection/grid): e.g. `selectedUnits` or batch mode, `condenseDirection` (`ESSENCE_TO_CONDENSED` / `CONDENSED_TO_ESSENCE`), cached `essence` and condensed-item counts, and any feedback/armed state if used.
  - Reads/writes **no** soul mutation state; interacts with `SoulService` (essence balance), `SoulTableService` (condense/decondense ops), config, and custom item helpers.
  - Uses shared `SoulTableUiKeys/UiItems/Nav/Data` patterns where applicable (actions, chrome, essence display, back/close/guide).

- **Create** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableInfusionInv.java`
  - Implements `SoulTableScreen` with process-local, wizard-friendly state.
  - Suggested state: `page`, `allSouls/index/essence`, single `selectedSoulId`, `enum InfuseMode { ADD, REPLACE }`, `String replaceTraitId` (slot/source), `String chosenTraitId`, trait-picker page/index, cached catalyst count, and transient validation/preview data.
  - **Step/flow (internal, in one screen):** select target soul → choose mode (`ADD`/`REPLACE`) → choose replacement slot (replace only) → choose candidate trait → review preview/warnings/costs/prereqs → confirm.
  - Uses shared grid/data loading for soul selection (single-select parity with Enhance/Reforge semantics unless action requires otherwise), but keeps infusion-only controls/state isolated.
  - All eligibility/cost/preview computations must be derived from authoritative state (service/helpers/config/registry) on refresh/confirm, not trusted from clicks.

- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableHubInv.java`
  - Add process buttons for **Condense** and **Infusion** (icons/labels/lore consistent with branding, e.g. `AMETHYST_BLOCK`/`NETHER_STAR`).
  - Actions open the corresponding process screens via `SoulTableNav.openProcess(...)`.
  - Hub remains a picker only (no soul grid/selection, no auto-skip to a process).

- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableNav.java`
  - Extend `openProcess(...)` to open `SoulTableCondenseInv` and `SoulTableInfusionInv` for the new targets.
  - Ensure `returnToTarget(...)` and row resolution handle the new process targets (including missing table → safe close).

- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableReturnTarget.java`
  - Add `CONDENSE` and `INFUSION` so the guide and navigation can return to the originating process.

- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableUiKeys.java`
  - Add hub action constants for opening new processes (e.g. `ACTION_OPEN_CONDENSE`, `ACTION_OPEN_INFUSION`).
  - Add process-specific action constants as needed (e.g. condense/decondense + batch amount actions, mode toggle, trait-page prev/next, trait/candidate selection, replace-slot selection). Keep parseable/stable names (or a `prefix:value` convention) and reuse existing common actions (`back_hub`, `close`, `guide`, `prev`, `next`, `clear`, `confirm`) where semantics match.

- **Modify (if helpful)** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableUiItems.java`
  - Add reusable builders for mode toggles, trait/slot items, warning/cost/prereq panels, and batch amount controls to avoid duplication and keep visuals consistent.

- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableGuideInv.java`
  - Add guide content/pages/entries for **Condense** and **Infusion** (endgame): purpose, conversion/crafting flow, catalyst use, add vs replace flow, prerequisites/costs being config-driven, example synergies/buildcraft tips, and “applies on next summon”.
  - Return navigation must already work generically via `SoulTableReturnTarget`; ensure new targets/pages route correctly and standalone guide behavior remains unchanged.

- **Routing/listeners**
  - `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableUiRouter.java`: **no structural change required** if new screens implement `SoulTableScreen` (interface dispatch covers them).
  - `src/main/java/dev/gloved/glovedplugin/menu/listener/MenuHandler.java`: **no structural change required** if drag/click safety continues to key off `SoulTableScreen`; verify new interactive layouts do not allow item movement in top inventory.

- **UI invariants:** all actions remain server-side validated; do not trust client clicks/item payloads; recompute eligibility/costs from authoritative state before commit; refresh/prune stale selections on mutation/failure.

### 2.7 Integration/wiring
- **Modify** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTablePackService.java` + any model/texture helpers to include new assets.
- **Modify** plugin bootstrap/reload paths to: backfill config, load `SoulTableConfig`, (re)register Soul Table and Catalyst recipes, regenerate/redeploy pack if applicable, and keep services/managers consistent.

---

## 3. Screen layout and UX proposal (54-slot, per-process)

> Final slot indices may be adjusted during implementation to fit aesthetics; keep interactions unambiguous and consistent. All non-interactive filler slots must be handled/cancelled by existing holder routing.

### 3.1 `SoulTableHubInv` (updated)
- 54-slot `Soul Table` hub with process choices: **Combine, Enhance, Reforge, Salvage, Condense, Infusion** + Guide + Essence info + Close.
- Suggested placement: keep existing four processes prominent and add Condense/Infusion as additional buttons (e.g. second row or balanced grid), without removing clarity.
- No soul grid/selection; clicking a process opens its dedicated screen.

### 3.2 `SoulTableCondenseInv` (early taste, incremental, no soul grid)
- **Title:** `Soul Table - Condense` (clear, consistent branding).
- **State:** `viewerId/tableId`, batch amount/units, direction or explicit action mode, cached essence + tagged condensed-item count.
- **Suggested layout:**
  - Header: Back→Hub (`0`), title/info, Guide, Close.
  - Center info panel: conversion rate, current Essence, current Condensed items owned, preview of selected batch result/cost, and inventory-space/affordability status.
  - Batch controls: `Condense x1/x16/x64/Max` and `Decondense x1/x16/x64/Max` (decondense shown/usable only if reversible). Controls may select a pending batch or execute directly; pick one consistent interaction model and reflect it in lore/confirm state.
  - Bottom: optional Prev/Next if paging amounts/modes, Essence/cost summary, Confirm (if using pending-batch model), Clear/Reset batch, Guide.
- **Disabled states/messages:** feature disabled, not enough essence, not enough condensed items, no inventory space, invalid amount.
- **UX:** emphasize incremental, visible progress (batching and clear before/after numbers).

### 3.3 `SoulTableInfusionInv` (endgame mastery, multi-step in one screen)
- **Title:** `Soul Table - Infusion`.
- **State:** single `selectedSoulId`, `ADD`/`REPLACE` mode, replace-slot/source trait, chosen candidate trait, soul/trait paging, cached essence + catalyst count, computed preview/warnings/eligibility.
- **Suggested layout (single screen, internal wizard state):**
  - Header: Back→Hub, mode toggle (`ADD` / `REPLACE`), context info (essence/catalysts/prereqs), Guide.
  - Soul grid region (default `9-44`, paged, single-select): choose target soul. Changing target resets dependent choices (mode-specific selections/candidate/replace slot) as needed.
  - Target/current traits panel: show target soul and its current traits; in `REPLACE` mode trait slots are clickable to select which trait to replace.
  - Trait picker panel: paginated eligible trait options from registry (name, multiline description, synergy notes, situational warnings; ineligible/already-owned marked clearly).
  - Preview/cost panel: before→after trait result (added/replaced highlighted), Essence cost, catalyst cost, catalysts owned, prerequisites, and warnings.
  - Bottom controls: Prev/Next (contextual to soul grid or trait picker per current focus/step), Clear/Reset infusion choices, Confirm, Guide, Back→Hub.
- **Step flow:** target selection → mode → (replace source trait if needed) → candidate trait → preview/warnings/cost/prereqs → confirm. “Back” returns to Hub (process-level), while UI can allow stepping back within the flow via mode/clear/reselection controls.
- **UX clarity:** strong before/after preview, explicit cost/prereq blockers, and situational/synergy callouts (e.g. `PACKBOUND` low value at effective capacity `<= 1`).

---

## 4. Configuration keys and concrete defaults

Add to bundled `summon.yml`, load with fallbacks in `SoulTableConfig.reload`, and backfill in `SoulTableConfig.backfill`/`GlovedPlugin` enable path (without overwriting existing keys).

```yml
souls:
  table:
    essence:
      condense:
        enabled: true
        rate: 128                # essence per 1 condensed item
        reversible: true
        item:
          material: AMETHYST_BLOCK
          name: "&bCondensed Soul Essence"
    catalyst:
      enabled: true
      item:
        material: NETHER_STAR
        name: "&dSoul Catalyst"
      recipe:
        enabled: true
        shape: [ "NEN", "ECE", "NEN" ]
        ingredients:
          N: NETHERITE_INGOT
          E: ECHO_SHARD
          C: CONDENSED_SOUL_ESSENCE   # custom tagged item
        amount: 1
    infusion:
      enabled: true
      min-rarity: EPIC           # endgame mastery default (tunable)
      min-enhance-level: 5       # endgame mastery default (tunable)
      strict-roll-conditions: false
      trait-cap:
        COMMON: 1
        UNCOMMON: 1
        RARE: 3
        EPIC: 3
        LEGENDARY: 4
        MYTHIC: 5
    costs:
      infusion:
        catalysts:
          ADD: 2
          REPLACE: 1             # cheaper to encourage iterative refinement
        essence:
          ADD:
            COMMON: 0        # blocked by min-rarity/prereqs by default
            UNCOMMON: 0
            RARE: 500
            EPIC: 1200
            LEGENDARY: 3000
            MYTHIC: 7000
          REPLACE:
            COMMON: 0
            UNCOMMON: 0
            RARE: 300
            EPIC: 750
            LEGENDARY: 1800
            MYTHIC: 4200
```

*Notes:*
- Treat `0`/unavailable combinations as disallowed when below min-rarity/prereqs. Exact endgame numbers are tuning values; keep them centralized in config + fallbacks + backfill.
- `replace` costs are lower than `add` by design; adjust ratios during playtesting to balance grind vs build iteration.
- Condense is available early by default; tune `rate`/reversibility to control pacing. Leave room for future bonus reward hooks without redesign.

---

## 5. Transaction, consistency, and anti-dupe rules

- **Essence mutations:** only via `SoulDatabase.transaction(...)` + `SoulRepository.spendEssence/addEssence` (re-validate balances/caps inside tx).
- **Trait mutations:** re-load target soul inside tx, re-check ownership/eligibility/cap/duplication, then `updateSoulTraits` and return updated instance.
- **Inventory items vs DB:** perform prechecks (space/resources), commit DB changes, then mutate inventory; on inventory mutation failure after DB commit, run compensating DB action (refund essence / revert traits) where possible and fail loudly/log. Never grant rewards before successful charge when avoidable.
- **Catalyst consumption:** count/remove only tagged `SoulCatalyst` items (ignore similarly named vanilla items). Match exact PDC.
- **Condensed item grants:** grant tagged items with correct amount; stacking-aware placement checks.
- **Cache invalidation:** after any successful soul mutation, invalidate soul + snapshot caches for owner/soul.
- **UI safety:** process screens must recompute/validate from authoritative state on confirm; handle stale selections and races gracefully with clear feedback.

---

## 6. Edge cases

- Target soul deleted/moved/ownership changed between preview and confirm → fail with “Soul no longer available”.
- Duplicate trait add/replace to same trait / replace with trait already present → reject.
- Add at/above cap, replace with no existing trait selected, or no eligible traits available → reject with clear reason.
- Not enough Essence or catalysts, or no inventory space for condensed items → reject (no partial mutation).
- Infusion/condense disabled by config or prerequisites unmet (rarity/enhance level) → reject and show requirement.
- Situational trait warning (e.g. PACKBOUND at effective capacity 1) shown in UI; allowed unless `strict-roll-conditions` enabled.
- Guide/navigation: opening guide from Condense/Infusion returns to the originating process; missing/invalid table context closes safely.
- Reload: recipes de-registered/re-registered, config refreshed, pack regenerated as applicable, open screens refresh safely on next interaction/reopen.
- Player offline/quit mid-action: guarded by player checks; no background mutation without player context except safe DB-only compensation if needed.

---

## 7. Implementation order (recommended)

1. **Config & constants:** `summon.yml`, `SoulTableConfig` fields/load/backfill, `GlovedPlugin` backfill + defaults parity.
2. **Domain/persistence:** `SoulInstance.withTraits`, `SoulRepository.updateSoulTraits`, `SoulService` invalidation hooks.
3. **Custom items:** condensed essence + catalyst item helpers (PDC, create/detect/count/remove).
4. **Recipes & pack:** catalyst recipe manager/registration + craft validation (if needed), resource-pack assets/models for new items.
5. **Service logic:** condense/decondense APIs + infusion preview/commit APIs in `SoulTableService` with transactional/compensation rules.
6. **UI foundation/wiring for new processes:** extend `SoulTableReturnTarget`, `SoulTableUiKeys`, `SoulTableNav`, and `SoulTableHubInv` to register Condense/Infusion (no changes to existing process internals).
7. **Process screens:** implement `SoulTableCondenseInv` (incremental batching UX) and `SoulTableInfusionInv` (wizard-like state, strong previews/warnings/synergy callouts) as standalone `SoulTableScreen`s.
8. **Guide & integration:** update `SoulTableGuideInv` pages/return handling for new targets, recipe/reload wiring, and listener/pack hooks.
9. **Verification:** `./build.sh`, manual in-game tests and playtest-driven tuning.

---

## 8. Testing and tuning checklist

- `./build.sh` succeeds.
- **Navigation/UI architecture:** Hub shows Condense/Infusion and opens correct screens; Back/Close/Guide work; guide returns to Hub/Condense/Infusion as appropriate; `SoulTableUiRouter`/`MenuHandler` safely handle new `SoulTableScreen` holders with no illegal item movement.
- **Condense:** correct 128:1 conversion both directions (if reversible), correct Essence/inventory updates, blocks on insufficient funds/items/space, no dupes/loss on failures; batching (+1/+16/+64/max) feels incremental and clear.
- **Catalyst:** recipe crafts only with valid materials (including tagged condensed essence), yields correct tagged catalyst, survives reload/re-register.
- **Infusion add:** enforces prerequisites, trait cap, duplicate prevention, eligible trait choices, essence+catalyst costs, persists traits, applies on next summon.
- **Infusion replace:** replaces selected trait only, prevents duplicates/invalid choices, lower costs/consumption than add (as configured), correct persistence.
- **Previews/clarity:** before→after trait preview accurate; descriptions/synergy callouts and situational warnings (e.g. PACKBOUND on cap 1) displayed correctly; strict mode behavior if enabled.
- **UI/UX:** intuitive stepwise flow, clear disabled reasons, correct state/reset behavior, stale selections handled, guide pages accurate/helpful.
- **Reload/config:** new keys backfilled without overwriting existing values; recipe/pack/config updates take effect.
- **Retention/buildcraft tuning (playtest):** costs/rate/prereqs feel rewarding not exhausting; replace enables iteration; trait choices remain meaningfully distinct (no single solved path); Condense accessible early enough to onboard players into the endgame loop.
- **Readiness:** implementation required only additive screen classes plus shared nav/keys/hub/guide registration and backend hooks, with no changes to existing Combine/Enhance/Reforge/Salvage screen internals.
