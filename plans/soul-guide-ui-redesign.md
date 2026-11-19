# Soul Guide UI/UX Redesign Plan (Tabbed Sidebar, Implementation-Ready)

## 0. Motivation, scope, and success criteria

### Current state (what exists today)
- **Physical item + grant/craft:**
  - `summon/soul/guide/SoulGuideItem` — PDC-tagged `BOOK` (`gloved:soul_guide`), display name/lore, enchant glint, right-click hint; `receivedKey` (`gloved:soul_guide_received`) marks one-time grant.
  - `summon/soul/guide/SoulGuideRecipeManager` — registers shapeless recipe `gloved:soul_guide` (Book + Soul Sand), reload-safe (`removeRecipe` before add), discovers for online/joining players.
  - `summon/soul/guide/SoulGuideListener` — on first join grants guide (inventory or drop-at-feet if full, then sets received flag once) and on right-click opens the guide GUI.
- **Guide UI:** `summon/soul/table/ui/SoulTableGuideInv` (`SoulTableScreen`, 54-slot `InventoryHolder`) with 8 pages: `MAIN`, `TRAITS`, `AFFIXES`, `STATS`, `TABLE`, `CONDENSE`, `INFUSION`, `CRAFTING`.
  - Main is a hub (intro + 7 category buttons scattered in rows 3-4).
  - Each category opens a separate read-only subpage; navigation is hub-and-spoke (Back returns to `MAIN`, no lateral topic switching).
  - Bottom row only is filled (slots 45-53) with black panes + `Back`/`Close`; the rest of the inventory is largely empty air with floating items.
  - Uses `actionKey` PDC (`gloved:soul_guide_action`) for `back_table`, `back_main`, `close`, `page:*`.
- **Context/navigation integration:**
  - `SoulTableNav.openGuide(plugin, player, tableId, page, returnTarget)` constructs/opens guide.
  - Opened from physical item: `tableId == null`, `Page.MAIN` (standalone). `Close` closes; `Back to Soul Table` is hidden.
  - Opened from Soul Table screens (Hub/Combine/Enhance/Reforge/Salvage/Condense/Infusion) via `SoulTableUiKeys.ACTION_GUIDE` → `SoulTableNav.openGuide(..., Page.MAIN, <origin process/hub>)`. `Back to Soul Table` / `Close` return to `SoulTableNav.returnToTarget(...)` for the same `tableId`/`returnTarget` (resolved via `SoulTableManager`).
  - `SoulTableGuideInv` currently hardcodes `openSoulTable(...)` behavior around hub-vs-return logic; `SoulTableReturnTarget` supports `HUB, COMBINE, ENHANCE, REFORGE, SALVAGE, CONDENSE, INFUSION`.
- **Content sources (must remain accurate/live):**
  - Traits: `SoulTraitRegistry.getActive().all()` with `displayName()` and `guideDescription()` (dynamic registry).
  - Affixes: `SoulAffixType.values()` with hand-authored descriptions; `ENDURING` hidden when duration disabled (`SoulConfig.getLifetimeTicks() > 0`); cost-reduction relevance helper exists today (`isCostReductionEnabled()`), keep behavior consistent with current intent.
  - Stats/rarity: static stat explanations + `SoulRarity.values()` listing.
  - Soul Table processes, Condense, Infusion, Crafting: static explanatory/recipe text reflecting current systems (Combine 3 same type/rarity, no Mythic combine; Enhance max/next-summon apply; Reforge scope; Salvage essence/multi; Condense Essence⇄Condensed + catalysts/redeem tip; Infusion add/replace, prerequisites/cost, next summon, situational traits; recipes for Guide/Table/Catalyst/Traps/Condensed acquisition).

### Problems (user-confirmed pain points)
- **Information fragmented/overlapping:** related systems are split across multiple pages with duplicated/partial explanations (e.g. TABLE vs CONDENSE vs INFUSION vs CRAFTING; stats/rarity/affixes/traits as separate disconnected topics), forcing users to mentally stitch concepts together.
- **Sparse/empty visual layout:** most of the 54-slot grid is empty air (only bottom row filled), so content feels like floating items rather than a cohesive, scannable guide.

### Goals
- **Streamlined navigation:** replace hub-and-spoke with a **tabbed single guide** that has a **persistent sidebar/tabs**; switch topics/sections in-place without returning to a main hub.
- **Consolidated IA:** reduce fragmentation/overlap by merging related content into fewer, clearer sections while covering **all current topics/questions**.
- **Cohesive layout:** full, structured background/chrome (no sparse empty air), consistent visual hierarchy and controls, aligned with Soul Table inventory UI conventions.
- **Preserve behavior/contracts:** keep inventory GUI format, keep physical Soul Guide item + recipe + first-join grant, preserve standalone vs table-context open/return behavior, and keep dynamic/runtime-derived content accurate (trait registry, affix conditions, rarity list, etc.).

### Non-goals
- No conversion to written-book/chat-only guide, no removal of the physical item/recipe/grant flow.
- No gameplay/balance/stat/recipe logic changes; text may be reorganized/clarified but must remain accurate to current behavior.
- No Soul Codex, Soul Vault, or Soul Teams UI redesign (except no regressions from shared navigation patterns).
- No new config schema requirements unless a safe, optional preference is later desired (not required for this redesign).

### Definition of done
- Soul Guide opens as a tabbed/sidebar single view (no hub-and-spoke main page required to browse topics); all sections reachable in-place.
- All current topics are covered and accurate: traits (dynamic), affixes (with existing conditions), stats/rarity/respawn/enhance, Soul Table actions (Combine/Enhance/Reforge/Salvage), Condense/catalysts/essence conversion, Infusion (add/replace/prereqs/cost/next-summon/warnings), Crafting/recipes/items.
- Layout is fully framed/filled (no sparse empty-air feel), visually consistent with Soul Table screens.
- Physical Soul Guide item, shapeless recipe, and one-time grant still work; opening from item (standalone) and from every Soul Table screen (context return to correct target) works correctly.
- `./build.sh` succeeds and manual verification checklist passes.

---

## 1. Proposed UX and layout

### 1.1 Screen model
- Keep a single 54-slot inventory GUI implementing `SoulTableScreen` (`SoulTableGuideInv`), opened/rebuilt in-place on navigation (same holder type, compatible with existing router/handler checks).
- Replace the `Page` hub-and-spoke enum with a **section + subpage** model:
  - **Sections (sidebar tabs):** fixed, always visible.
  - **Subpages:** optional per-section pagination/index for content that exceeds one view (e.g. traits list) or for logically split subtopics (e.g. Condense vs Infusion) while remaining within the same section/tab context.
- Opening the guide defaults to **Overview**, subpage 0.

### 1.2 Layout (54 slots = 6 rows × 9 cols)

| Region | Slots | Purpose |
|---|---|---|
| **Sidebar tabs** | `0, 9, 18, 27, 36` | Persistent section tabs (click to switch section in-place). Selected tab visually distinct (material/color/glint/icon/name), others show section label + short hint. |
| **Sidebar context/control** | `45` | If `tableId != null`: `Back to Soul Table` / return-to-context action (styled consistently with Soul Table "home/back" controls, e.g. Soul Table block-texture action item via `SoulTableItem`/`SoulTableUiItems` conventions where appropriate). If standalone: non-action filler/branding. |
| **Content area** | columns `1-8`, rows `0-4` (`1-8,10-17,19-26,28-35,37-44`) | Section header + grouped info blocks/items for active `(section, subPage)`. Fully framed on background fill. |
| **Bottom control bar** | `46,47,48,49,50,51,52,53` | `46-48` status/filler (e.g. section/subpage context), `49` Close, `50` page indicator when paginated, `51` Prev, `52` filler/section hint, `53` Next. Prev/Next enabled only when the active section has multiple subpages. |

- **Background:** fill all 54 slots with filler/border panes first (consistent with `SoulTableUiItems.filler()` / Soul Table screen framing), then place interactive/content items. No intentional empty air.
- **Titles:** keep clear titling (e.g. `Soul Guide` or `Soul Guide: <Section>`); sidebar remains the primary location indicator.

### 1.3 Interaction behavior
- **Tab switch:** clicking a sidebar section sets `section = selected`, `subPage = 0`, rebuilds in-place. No return to a hub.
- **Subpage nav:** `Prev/Next` change `subPage` within bounds of the active section; disabled/greyed when not applicable. Page indicator shows `Page x/y` for multi-subpage sections.
- **Close:**
  - `tableId == null` (standalone/item): close inventory.
  - `tableId != null` (opened from table): return to `returnTarget` via `SoulTableNav.returnToTarget(plugin, player, tableId, returnTarget)` (same contract as today so "close" from guide context behaves consistently with existing table UX).
- **Back-to-table control (slot 45):** when in table context, returns to `returnTarget` (explicit context return). Standalone has no table destination.
- **Clicks/drags:** remain fully controlled (cancel unsafe interactions) via existing holder/routing protections (`SoulTableScreen` + `SoulTableUiRouter`/`MenuHandler` coverage); only tagged actions respond.

---

## 2. Consolidated information architecture

Consolidate 8 current pages into **5 persistent sidebar sections**. Every current topic/question must be represented.

| Section (tab) | Subpages (proposed) | Covers current pages/content | Consolidation / placement notes |
|---|---|---|---|
| **Overview** | 1 | `MAIN` intro + `STATS` (Health, Defense, Damage, Move Speed, Attack Speed, Respawn Time, Enhancing/next-summon note) + `STATS` rarity tiers + Essence-at-a-glance | Orientation in one place: what souls are, how power/rarity work, respawn/enhance basics, essence as shared currency. Replaces the hub menu. |
| **Traits & Affixes** | 2+ (Affixes; Traits [paginated as needed]) | `AFFIXES` (all `SoulAffixType`, descriptions; `ENDURING` shown only when duration enabled; preserve cost-reduction/other existing conditional behavior) + `TRAITS` (all registered traits, dynamic) | Merges "soul modifiers/rolls" that were fragmented. Affixes on a stable subpage; Traits rendered from `SoulTraitRegistry.getActive().all()` with `displayName`/`guideDescription` and shared/default guide icon, paginated within content area if count exceeds capacity. |
| **Soul Table** | 1 | `TABLE` core actions: Combine, Enhance, Reforge, Salvage + how Essence is used in core upgrades | Core station loop together (merge 3→higher rarity rules, enhance leveling/cost/next-summon, reforge scope/preservation, salvage yield/multi + essence). Avoid duplicating advanced-process details except brief cross-reference if helpful. |
| **Condense & Infusion** | 2 (Condense; Infusion) | `CONDENSE` (Essence ⇄ Condensed Soul Essence, batch/x1-x16-x64-Max concept as described, catalysts/crafting use, early-taste/notes/redeem tip) + `INFUSION` (add/replace, trait choice/no duplicates/preview-synergies as described, prerequisites/cost, applies next summon, situational trait warnings) | Merges advanced/economy and endgame trait-control systems that were split/overlapped with TABLE. Separate subpages under one advanced section for readability while eliminating redundant hub entries. |
| **Recipes** | 1 (or paginated if needed) | `CRAFTING` (Soul Guide recipe, Soul Table recipe/default materials note, Soul Catalyst recipe/configurable note, Soul Traps by tier/cost progression, Condensed Soul Essence acquisition/redeem note) | Central, practical reference. Keep wording accurate to current/default-config behavior as today. |

**Coverage check against current pages:** MAIN→Overview/sidebar; TRAITS→Traits & Affixes; AFFIXES→Traits & Affixes; STATS→Overview; TABLE→Soul Table; CONDENSE→Condense & Infusion; INFUSION→Condense & Infusion; CRAFTING→Recipes. No topic dropped.

---

## 3. Visual/UX details

- **Filled, framed layout:** background fill across all slots; content placed on a consistent grid with a clear header item per section/subpage and grouped blocks (labeled items with concise lore) for scanability.
- **Sidebar tab affordances:** selected state unmistakable (highlight/glint/accent color + icon); unselected tabs readable with short labels. Use existing `UiIcons`/item conventions where they fit.
- **Bottom controls consistency:** fixed positions for Close and Prev/Next; disabled nav uses non-interactive/grey styling (similar to existing nav item patterns, e.g. `SoulTableUiItems.pageNavItem` conventions where reusable) and shows no-op state.
- **Typography/content style:** preserve current color-coded, concise lore style; reorganize into grouped sections to reduce repetition while keeping all facts present.
- **Empty/conditional states:** if trait registry yields none, show a clear "No traits registered/available" info item; affix conditional omissions follow existing rules (e.g. hide Enduring when duration disabled).

---

## 4. Implementation approach (concrete)

### 4.1 Primary refactor: `summon/soul/table/ui/SoulTableGuideInv`

- **State/model:**
  - Replace `enum Page { MAIN, TRAITS, AFFIXES, STATS, TABLE, CONDENSE, INFUSION, CRAFTING }` with a section/subpage model, e.g.:
    - `enum Section { OVERVIEW, TRAITS_AFFIXES, SOUL_TABLE, CONDENSE_INFUSION, RECIPES }` (names flexible if kept consistent)
    - fields: `GlovedPlugin plugin`, `UUID viewerId`, `UUID tableId`, `SoulTableReturnTarget returnTarget`, `Section section`, `int subPage`, `Inventory inventory`.
  - Provide constructors/factory defaults: default to `Section.OVERVIEW`, `subPage=0`, `returnTarget=HUB` when null.
- **Build pipeline:** refactor `build()` into deterministic steps:
  1. `fillBackground()` (all slots framed/filled).
  2. `renderSidebar(activeSection)` (tabs + context back control).
  3. `renderBottomBar(section, subPage, subPageCount)` (close, prev/next, indicator/status).
  4. `renderContent(section, subPage)` dispatching to per-section builders.
- **Action/tagging:**
  - Keep/extend guide action PDC (`actionKey`) with explicit actions such as `section:<SECTION>`, `prev`, `next`, `close`, `back_table` (or align names with `SoulTableUiKeys` constants where appropriate) so clicks are unambiguous.
  - Continue to tag only interactive items; all other slots remain inert fillers.
- **Per-section content builders (port + consolidate existing logic):**
  - `buildOverview(...)`: intro + stats (health/defense/damage/move/attack/respawn) + enhancing/next-summon + rarity tiers list + essence note.
  - `buildTraitsAffixes(subPage, ...)`: subpage 0 affixes grid/list using `SoulAffixType.values()` filtered by existing conditions (`isDurationEnabled()` / current relevance helpers) and current descriptions; subpage 1+ traits pages from `SoulTraitRegistry.getActive()` (`all()` / null-safe) rendered with shared/default icon and definition `displayName`/`guideDescription`.
  - `buildSoulTable(...)`: Combine/Enhance/Reforge/Salvage and essence/cost semantics as today (Mythic not combinable, 3 same type/rarity, enhance max/next-summon, reforge preserves family/rarity/level/identity and rerolls as stated, salvage multi/essence).
  - `buildCondenseInfusion(subPage, ...)`: subpage 0 Condense (conversion, catalysts, batch/redeem/notes), subpage 1 Infusion (add/replace, trait choice/no-dupes, prerequisites/cost, next-summon, situational warnings) with clear grouping.
  - `buildRecipes(...)`: all craftable/acquisition items currently in Crafting page.
  - Extract shared helpers as needed (e.g. `sectionTabItem`, `contentHeader`, `infoBlock`, trait/affix item renderers) to keep methods small and consistent.
- **Navigation handlers in `handleClick`:**
  - Validate viewer, bounds, tagged action.
  - `section:*` → set section/subpage 0, refresh/open.
  - `prev/next` → clamp within section subpage count, refresh.
  - `back_table` (table context) / `close` → `tableId == null ? close : returnToTarget(tableId, returnTarget)` (preserve current semantics, routed via `SoulTableNav`/sync safety as today).
- **Subpage counts:** compute deterministically (static per section or dynamic for traits based on registry size and slots-per-page) so nav bounds and indicators are correct.

### 4.2 Navigation API and call sites

- **`summon/soul/table/ui/SoulTableNav`:**
  - Evolve `openGuide(...)` to target the new model, e.g. `openGuide(plugin, player, tableId, Section section, int subPage, SoulTableReturnTarget returnTarget)` with convenience overload/defaults `openGuide(..., tableId, returnTarget)` → Overview/0.
  - Keep `returnToTarget(...)`, `openHub(...)`, `openProcess(...)` behavior unchanged.
- **Update all openers to default to Overview:**
  - `summon/soul/guide/SoulGuideListener` (item right-click): standalone open → Overview.
  - `summon/soul/table/ui/SoulTableHubInv` guide button.
  - `summon/soul/table/ui/SoulTableCombineInv`, `SoulTableEnhanceInv`, `SoulTableReforgeInv`, `SoulTableSalvageInv`, `SoulTableCondenseInv`, `SoulTableInfusionInv` guide buttons: open with their respective `SoulTableReturnTarget` so return lands back on the originating process.
- **`SoulTableGuideInv` external API:** adjust constructors/signature and any direct usages (search for `new SoulTableGuideInv`, `Page.`, `openGuide`) to compile cleanly with no stale page references.

### 4.3 Unchanged (preserve)
- `SoulGuideItem` (PDC identity, creation, grant key), `SoulGuideRecipeManager` (key/recipe/discovery), `SoulGuideListener` grant-once behavior and interaction trigger (except the opened view defaults to new Overview).
- `SoulTableScreen` contract, `SoulTableUiRouter` holder dispatch (still routes `SoulTableGuideInv`), `MenuHandler` protections for Soul Table holders, `SoulTableReturnTarget` enum/semantics.
- All soul/table/service/domain logic and content sources; no recipe/balance/config behavior changes.

### 4.4 Files expected to change
- **Primary:** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableGuideInv.java` (major refactor).
- **Navigation/callers:** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableNav.java`, `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideListener.java`, `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableHubInv.java`, `SoulTableCombineInv.java`, `SoulTableEnhanceInv.java`, `SoulTableReforgeInv.java`, `SoulTableSalvageInv.java`, `SoulTableCondenseInv.java`, `SoulTableInfusionInv.java` (guide-open call sites/defaults).
- **Possibly (only if beneficial/needed):** shared UI helpers in `SoulTableUiItems`/`SoulTableUiKeys` for reusable tab/nav chrome (keep changes minimal and scoped).
- **No changes expected:** `SoulGuideItem.java`, `SoulGuideRecipeManager.java`, domain/service/DB/config/resource files (unless a purely cosmetic text constant is centralized).

---

## 5. Migration / compatibility notes

- Replaces in-memory page identifiers/layout only; no persisted data format changes.
- All previous entry points (item, recipe grant, each table screen guide button) continue to function, defaulting to Overview.
- Standalone vs table-context return behavior preserved via existing `tableId`/`SoulTableReturnTarget` resolution.

---

## 6. Verification plan

1. **Build:** `./build.sh` (required per project conventions) — must succeed.
2. **Standalone guide:** give/use Soul Guide item and right-click (air/block) → opens redesigned guide at Overview; sidebar switches all sections in-place; Close exits. First-join grant and craft recipe still function.
3. **Table-context guide:** open from Hub and each process (Combine/Enhance/Reforge/Salvage/Condense/Infusion) → Back-to-table/Close returns to the correct originating screen/target for the same table.
4. **Navigation:** tab switching resets to subpage 0; Prev/Next only active/bounded for multi-subpage sections with correct page indicator; no hub bounce required.
5. **Layout:** entire inventory filled/framed (no sparse empty-air regions); selected/unselected tabs and controls visually clear and consistent.
6. **Content coverage & correctness:** Overview (intro/stats/respawn/enhance/rarity/essence), Traits & Affixes (all affixes with correct conditions incl. Enduring gating; all registered traits with dynamic names/descriptions), Soul Table (4 core actions + essence semantics), Condense & Infusion (conversion/catalysts/tips + add/replace/prereqs/cost/next-summon/warnings), Recipes (guide, table, catalyst, traps, condensed essence note) — all present and accurate.
7. **Safety/regression:** clicks/drags in guide do not move items; other Soul Table screens and routing unaffected.

---

## 7. Risks and mitigations

- **Dynamic trait count/layout overflow:** compute traits-per-page from available content slots and paginate; show explicit empty state if none.
- **Return-target regressions:** centralize guide open/return through `SoulTableNav` and test every originating process screen.
- **Text drift while consolidating:** port from existing strings/logic first, then reorganize/deduplicate with a parity pass against current topics.
- **Holder/routing assumptions:** keep `SoulTableGuideInv` implementing `SoulTableScreen` and verify `SoulTableUiRouter`/`MenuHandler` continue to recognize and protect it.

---

## 8. Future follow-ups (out of scope)

- Optional: remember last-viewed guide section per player (only if a safe, explicit preference is desired).
- Optional: deep-link from specific process screens into the most relevant guide section (would require targeted caller changes beyond default Overview).
- Optional: further visual polish once in-game feedback is collected (icons/material accents per tab) without changing IA/behavior.
