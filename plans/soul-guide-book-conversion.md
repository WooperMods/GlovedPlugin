# Soul Guide Book Conversion — Full Implementation Plan

## 0. Summary

Convert the Soul Guide from the current **inventory-GUI** (`SoulTableGuideInv`, 54-slot `InventoryHolder` with tabbed sections/info items) into an in-game **written-book reader** experience (Adventure `Book` opened via `player.openBook(...)`), while preserving the physical Soul Guide item identity, shapeless recipe, and one-time first-join grant.

**Primary outcomes**
- Right-clicking the Soul Guide opens a read-only, multi-page book UI (native page turning) instead of a chest/inventory GUI.
- Guide content remains accurate and runtime-derived where applicable (dynamic trait registry, affix conditions such as Enduring gating, rarity tiers, and current system descriptions).
- Guide code becomes simpler (no slot layout, tab/subpage state, inventory click routing, or `SoulTableScreen` integration for the guide).
- **Behavior change (chosen/default for this plan):** opening the guide from a Soul Table screen is treated as opening a standalone reference book; the automatic in-inventory "return to originating Soul Table screen" flow is removed (player reopens the Soul Table manually). Rationale: book UI does not provide a reliable, inventory-equivalent return callback/action.

> **Build/deploy:** use `./build.sh` only (never run Gradle directly).

---

## 1. Current state (baseline)

### 1.1 Physical item, recipe, and grant
- `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideItem.java`
  - PDC-tagged `Material.BOOK` (`gloved:soul_guide`), display name/lore, enchant glint, hint lore.
  - `receivedKey` (`gloved:soul_guide_received`) marks one-time grant.
  - `isSoulGuide(plugin, item)` checks PDC (BYTE/INTEGER compatibility).
- `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideRecipeManager.java`
  - Registers shapeless recipe key `gloved:soul_guide` (Book + Soul Sand), reload-safe (`Bukkit.removeRecipe(key)` before add), discovers recipe for online/joining players.
- `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideListener.java`
  - On first join: if not received, grants guide (inventory or drop-at-feet if full), sets received flag once.
  - On right-click (main/off-hand, air/block): cancels event and opens `SoulTableGuideInv` (standalone).

### 1.2 Guide UI (inventory GUI)
- `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableGuideInv.java`
  - Implements `SoulTableScreen` (`InventoryHolder`), 54-slot inventory, PDC action tagging (`gloved:soul_guide_action`).
  - Sections: Overview, Traits & Affixes, Soul Table, Condense & Infusion, Recipes (single-page, horizontal tabs, no bottom bar after recent iterations).
  - Dynamic/conditional content:
    - Traits: `SoulTraitRegistry.getActive().all()` with `displayName()` / `guideDescription()`.
    - Affixes: `SoulAffixType.values()`, `ENDURING` hidden when `SoulConfig.getLifetimeTicks() > 0` is false (via `plugin.getSoulService().soulConfig()`); `isCostReductionEnabled()` computed for parity (no filtering effect today).
    - Rarity: `SoulRarity.values()` with colored names.
  - Standalone vs table-context behavior via `tableId` and `SoulTableReturnTarget`.

### 1.3 Navigation/context integration
- `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableNav.java`
  - `openGuide(plugin, player, tableId, returnTarget)` and `openGuide(plugin, player, tableId, section, subPage, returnTarget)` create/open `SoulTableGuideInv`.
  - `returnToTarget(...)` resolves row and reopens hub/process screen for `tableId`.
- Call sites opening the guide (all currently target Overview and pass a `SoulTableReturnTarget`):
  - `summon/soul/guide/SoulGuideListener.java` (standalone, `tableId == null`)
  - `summon/soul/table/ui/SoulTableHubInv.java` (`HUB`)
  - `summon/soul/table/ui/SoulTableCombineInv.java` (`COMBINE`)
  - `summon/soul/table/ui/SoulTableEnhanceInv.java` (`ENHANCE`)
  - `summon/soul/table/ui/SoulTableReforgeInv.java` (`REFORGE`)
  - `summon/soul/table/ui/SoulTableSalvageInv.java` (`SALVAGE`)
  - `summon/soul/table/ui/SoulTableCondenseInv.java` (`CONDENSE`)
  - `summon/soul/table/ui/SoulTableInfusionInv.java` (`INFUSION`)
- `summon/soul/table/ui/SoulTableUiRouter.java` routes clicks for `SoulTableScreen` holders; `menu/listener/MenuHandler.java` cancels clicks/drags for `SoulTableScreen` inventories (guide currently benefits from this as a screen).

### 1.4 Constraints/notes (from repo)
- Paper API 26.3+, Adventure components already used throughout. Java 25 toolchain.
- Config backfill/preservation conventions apply if config changes are introduced (not expected for this conversion).
- `SoulTraitRegistry.getActive()` may be null/empty; guide must handle gracefully.

---

## 2. Goals, non-goals, and decisions

### 2.1 Goals
- Replace inventory-GUI guide with a readable written-book UI (native page turning) that is easy to scan and no longer composed of many inventory "info button" items.
- Preserve all guide topics/content accuracy:
  - **Overview:** intro, stats (Health, Defense, Damage, Move Speed, Attack Speed, Respawn Time, Enhancing/next-summon), rarity tiers, essence/currency overview.
  - **Traits & Affixes:** all affixes with current conditional behavior (Enduring gated when duration disabled), and all registered traits dynamically (`displayName`, `guideDescription`).
  - **Soul Table:** Combine, Enhance, Reforge, Salvage semantics/cost/constraints as currently described.
  - **Condense & Infusion:** Essence ⇄ Condensed Soul Essence conversion/batches/catalysts/notes/redeem tip; Infusion add/replace, trait choice/no duplicates, prerequisites/cost, applies next summon, situational warnings.
  - **Recipes:** Soul Guide, Soul Table, Soul Catalyst, Soul Traps (tiers/progression), Condensed Soul Essence acquisition/redeem note.
- Keep physical item identity, shapeless recipe, and one-time grant behavior unchanged.
- Generate book content dynamically on open so runtime state/conditions/registry changes are reflected.

### 2.2 Non-goals
- No gameplay/balance/stat/recipe/config logic changes.
- No writable/editable Book-and-Quill (`WRITABLE_BOOK`) UX; guide is read-only.
- No redesign of Soul Table, Codex, Vault, or Teams UIs beyond removing guide-as-inventory coupling.
- No persistent per-player "last page/section" state.

### 2.3 Key decisions
- **Book API (planned):** use Adventure `net.kyori.adventure.inventory.Book` (`Book.book(title, author, pages)` / collection overload) and open via `player.openBook(book)` (Audience/Player). Verify exact signatures against the project’s bundled Paper/Adventure (Paper 26.3+) during implementation and adjust if needed.
- **Table-context return:** **drop** automatic return-to-originating-table behavior. Guide opens as a standalone book reference from all entry points; player manually reopens the Soul Table if needed. (Inventory-specific `tableId`/`returnTarget` no longer drive in-book navigation/return.)
- **Content delivery:** build pages at open time from current sources; do not rely on static pages embedded in the physical item.

---

## 3. Proposed UX/content structure (book)

### 3.1 Entry behavior
- **Physical Soul Guide item:** right-click (main/off-hand, air or block) opens the Soul Guide book (event cancelled as today).
- **Soul Table guide buttons:** open the same Soul Guide book (standalone reference; no auto-return to the originating table screen).
- **First-join grant & recipe:** unchanged.

### 3.2 Book structure (pages)
Use a clear, sectioned layout with native page turning and a short index/TOC:

1. **Title / TOC page(s)**
   - "Soul Guide" title, brief intro, and a contents list mapping to sections/page ranges or section headings (e.g. Overview, Traits & Affixes, Soul Table, Condense & Infusion, Recipes).
2. **Overview**
   - What souls are / basics.
   - Stats: Health, Defense, Damage, Move Speed, Attack Speed, Respawn Time.
   - Enhancing/next-summon note.
   - Rarity tiers (`SoulRarity.values()`), Essence overview.
3. **Affixes**
   - What affixes are, stronger/better souls roll more.
   - One entry per visible affix (`SoulAffixType`), omitting `ENDURING` when duration is disabled (preserve current condition/intent).
4. **Traits**
   - What traits are.
   - All registered traits from `SoulTraitRegistry.getActive().all()` with display name and guide description (formatted readably). Handle empty/null registry with a clear "No traits registered/available" note. Paginate across book pages as needed.
5. **Soul Table (core actions)**
   - Combine (3 same type/rarity, higher rarity, Mythic not combinable), Enhance (level up, next summon), Reforge (rerolls stats/bonuses while keeping type/rarity/identity as stated today), Salvage (break down for Essence, multi-salvage), Essence usage note.
6. **Condense & Infusion**
   - Condense: Essence ⇄ Condensed Soul Essence, batch x1/x16/x64/Max concept, catalysts, early availability note, no soul selection, redeem tip.
   - Infusion: add/replace trait, trait choice/no duplicates/preview-synergy intent as described, prerequisites/cost/catalysts, applies next summon, situational trait warnings.
7. **Recipes / Items**
   - Soul Guide (shapeless: Book + Soul Sand), Soul Table (shaped/default materials note), Soul Catalyst (shaped/configurable note), Soul Traps (tiers/cost progression), Condensed Soul Essence acquisition/redeem note.

### 3.3 Formatting guidelines
- Prefer Adventure `Component` pages; use color/decoration for headings and labels consistent with existing soul UI tone where practical.
- Keep lines short and wrap/split content to fit written-book page constraints (avoid overflow/cutoff). Chunk long descriptions (traits/recipes/notes) across pages.
- Centralize page-building helpers (append lines, new page, section heading, bullet/label formatting, safe text normalization) to keep content maintainable.

---

## 4. Implementation approach (concrete)

### 4.1 New guide book builder
- **Add:** `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideBook.java` (name flexible; keep in `summon.soul.guide`).
- **Responsibilities:**
  - Build a `Book` (or pages collection) from live sources: `plugin.getSoulService()`, `SoulTraitRegistry.getActive()`, `SoulAffixType`, `SoulRarity`, and static-but-accurate text ported/reorganized from current guide content.
  - `open(Player player)` / `createBook()` API for listeners/nav to call.
  - Pure formatting/content helpers: section builders, trait/affix renderers, text wrapping/splitting into pages, null/empty guards.
- **Data sourcing/parity:**
  - Affix visibility: hide `ENDURING` when `svc == null || svc.soulConfig() == null || svc.soulConfig().getLifetimeTicks() <= 0` (match current behavior). Preserve `isCostReductionEnabled()` intent if referenced today (no affix filtering change).
  - Traits: `registry != null ? registry.all() : List.of()`; render `displayName()` and `guideDescription()` lines (prefix/default color if line has no formatting), empty-state when none.
  - Rarities: iterate `SoulRarity.values()`.

### 4.2 Listener and entry points
- **Update:** `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideListener.java`
  - Replace `new SoulTableGuideInv(...).open(...)` with `SoulGuideBook` open (scheduled on main thread as today).
  - Keep grant-once logic, PDC checks, off-hand handling, and event cancellation unchanged.
- **Update:** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableNav.java`
  - Replace guide opening to delegate to `SoulGuideBook` (e.g. `new SoulGuideBook(plugin).open(player)` or static helper).
  - Simplify/remove guide-specific context parameters/overloads that only existed for inventory return (`tableId`, `Section`, `subPage`, `returnTarget`) once no longer needed; or keep a temporary compatible overload that ignores context to minimize caller churn, then clean up.
  - `openHub/openProcess/returnToTarget/resolveRow/runSync` behavior remains for non-guide navigation (only guide flow changes).
- **Update guide button call sites** (remove `SoulTableReturnTarget` dependence for guide opens if Nav is simplified):
  - `summon/soul/table/ui/SoulTableHubInv.java`
  - `summon/soul/table/ui/SoulTableCombineInv.java`
  - `summon/soul/table/ui/SoulTableEnhanceInv.java`
  - `summon/soul/table/ui/SoulTableReforgeInv.java`
  - `summon/soul/table/ui/SoulTableSalvageInv.java`
  - `summon/soul/table/ui/SoulTableCondenseInv.java`
  - `summon/soul/table/ui/SoulTableInfusionInv.java`
  - Replace `SoulTableNav.openGuide(plugin, player, tableId, <RETURN_TARGET>)` (or old signatures) with the new no-context guide open call.

### 4.3 Remove inventory-guide implementation
- **Remove or replace:** `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableGuideInv.java`
  - Delete the `SoulTableScreen` inventory implementation once no longer referenced. (If compilation requires a temporary stub during refactor, remove it before completion.)
- **Clean up references:** remove unused imports/usages of `SoulTableGuideInv`, its `Section` enum, and guide-only action keys across `table/ui`, `guide`, and any other packages (`rg -n "SoulTableGuideInv|soul_guide_action"`).
- **No router/handler changes expected:** `SoulTableUiRouter`/`MenuHandler` only special-case `SoulTableScreen` holders; with guide no longer an inventory screen they simply won’t apply (desired).

### 4.4 Physical item/recipe (preserve, minor adjustments only if needed)
- **Keep:** `SoulGuideItem` PDC tag/received key, `isSoulGuide`, recipe key/ingredients, join grant/discovery behavior.
- **Optional (only if desired/compatible):** adjust guide item lore to say "Right-click to read/open the guide" (book reader) — cosmetic, no behavior/identity change. Do not change PDC keys or material identity without need.

### 4.5 API/compat verification during implementation
- Confirm `player.openBook(Book)` and `Book.book(...)` usage against the server’s Paper/Adventure (Paper 26.3+). If API differences exist (e.g. `Server#createBook`, different factory signatures), adapt the builder accordingly.
- Confirm book page/component length behavior in-game; adjust splitting/wrapping helpers to prevent clipped pages.

---

## 5. Files expected to change

| Path | Change |
|---|---|
| `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideBook.java` | **New:** dynamic book content builder + open helper |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideListener.java` | Open book instead of `SoulTableGuideInv` |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableNav.java` | Delegate guide open to book; simplify/remove guide context/return wiring |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableHubInv.java` | Update guide open call (no return target) |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableCombineInv.java` | Update guide open call |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableEnhanceInv.java` | Update guide open call |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableReforgeInv.java` | Update guide open call |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableSalvageInv.java` | Update guide open call |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableCondenseInv.java` | Update guide open call |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableInfusionInv.java` | Update guide open call |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableGuideInv.java` | **Remove** (inventory guide no longer used) |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideItem.java` | Preserve; optional cosmetic lore tweak only |
| `src/main/java/dev/gloved/glovedplugin/summon/soul/guide/SoulGuideRecipeManager.java` | Expected unchanged |
| `summon.yml` / config/backfill/resource-pack assets | No changes expected |

---

## 6. Implementation sequence

1. **Introduce book builder:** create `SoulGuideBook` with section/page builders and runtime content sourcing (traits/affixes/rarities/conditions), including TOC and page-splitting helpers.
2. **Switch open flow:** update `SoulGuideListener` and `SoulTableNav.openGuide(...)` to open the book; update all 7 UI callers to the new signature.
3. **Remove inventory guide:** delete `SoulTableGuideInv` and purge stale references/usages/imports; confirm no `SoulTableScreen`/action-key dependencies remain for the guide.
4. **Parity pass:** compare book text against previous guide content to ensure every topic/fact and conditional behavior is covered and accurate.
5. **Build & deploy:** run `./build.sh`.
6. **Manual verification:** execute the checklist below in-game and adjust formatting/page breaks as needed.

---

## 7. Verification plan

1. **Build:** `./build.sh` succeeds (required).
2. **Physical item/recipe/grant:**
   - Soul Guide item still recognized by PDC; first-join grant-once behavior unchanged (including drop-at-feet when inventory full, received flag set once).
   - Shapeless recipe (Book + Soul Sand) still crafts and is discovered/reload-safe.
3. **Book opens correctly:** right-click Soul Guide (air/block, main/off-hand) opens the written-book reader with generated content (not an inventory GUI). Closing the book is native (no close button required).
4. **Content coverage & accuracy:**
   - TOC/sections present: Overview, Affixes, Traits, Soul Table, Condense & Infusion, Recipes.
   - Overview includes stats (Health/Defense/Damage/Move Speed/Attack Speed/Respawn Time), enhancing/next-summon, rarity tiers, essence.
   - Affixes list correct and `ENDURING` only shown when duration is enabled (matches current condition).
   - Traits list is dynamic (all registered traits with names/descriptions) and shows a clear empty state if none; long lists paginate across book pages without cutoff.
   - Soul Table actions (Combine/Enhance/Reforge/Salvage) and Condense & Infusion details (conversion/batches/catalysts/redeem, add/replace/prereqs/cost/next-summon/warnings) accurate.
   - Recipes for Soul Guide, Soul Table, Soul Catalyst, Soul Traps, Condensed Soul Essence note present and accurate.
5. **Opened from Soul Table screens:** Hub and each process (Combine/Enhance/Reforge/Salvage/Condense/Infusion) guide button opens the book. (Expected behavior change: no automatic return to the originating table screen; player reopens the table manually.)
6. **Regression/safety:** no inventory interactions/drags related to a guide GUI; other Soul Table screens and navigation unaffected; no errors when trait registry/service/config is unavailable/null.

---

## 8. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Book page length/width overflow or clipping | Content cut off/unreadable | Implement conservative wrapping/splitting helpers, chunk by section/bullets, and validate in-game with longest trait descriptions/affix lists. |
| Loss of automatic return-to-table context | UX change for table users | Intentional per plan; document as behavior change. (If later required, add a best-effort reopen-on-close mechanism as a follow-up, acknowledging reliability limits.) |
| Paper/Adventure Book API signature differences | Compile/runtime issues | Verify against Paper 26.3+/bundled Adventure during implementation; adapt factory/open call (`Book.book(...)` vs alternatives) as needed. |
| Dynamic source nullability (trait registry/service/config) | NPEs or missing content | Null/empty guards and explicit fallback text (e.g. "No traits registered"); keep conditional checks parity with current guide. |
| Stale references to removed inventory guide | Compile errors or dead code | `rg` for `SoulTableGuideInv`, old `openGuide` signatures, and `soul_guide_action`; remove/adjust all usages. |
| Text parity drift during port | Inaccurate guide | Port from existing guide strings/logic first, reorganize into book pages, then do a topic-by-topic parity check against current content. |

---

## 9. Follow-ups (optional, out of scope)

- Add clickable book TOC/index entries if a reliable click-action approach is desired and supported for the target client/server behavior.
- Reintroduce best-effort "return to originating Soul Table screen" after closing the guide if UX feedback requires it.
- Externalize guide text to config/resources for easier localization/editing (requires careful backfill/default handling per repo conventions).
- Minor visual polish (headings/icons via text styling) based on in-game readability feedback.
