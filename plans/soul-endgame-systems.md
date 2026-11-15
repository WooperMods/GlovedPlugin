# Soul Endgame Systems — Follow-up Plan (Post Infusion & Condensation)

> **Builds on:** implemented `plans/soul-infusion-and-condensation.md` baseline (Condense, Soul Catalyst crafting, Infusion, supporting config/items/services/UI/pack), the implemented Soul Groups loadout system (`plans/soul-team-loadout-overhaul.md`) plus follow-up adjustments, and the MVP implementations of **Soul Mastery / Attunement** and **Account/Family Codex** (`plans/soul-mastery-codex-subplan.md`). This document defines the remaining endgame/progression systems.

## 0. Summary

Remaining layered systems to deepen soul buildcraft, economy choice, and long-term goals:

1. **Catalyst Tiers / Variants** (economy depth and player choice)
2. **Fragments & Targeted Crafting** (deterministic paths, reduced RNG frustration)
3. **Essence Sinks with Player Choice** (healthy, flexible economy)
4. **Socketed “Soul Cores”** (modular build slots)
5. **Affinity/Element Flavor** (lightweight identity and synergies)

**Design intent**
- **Incremental progression:** visible steps and milestones, not all-or-nothing.
- **Meaningful agency with clarity:** intentional builds, strong previews/callouts, understandable tradeoffs.
- **Endgame sink with tradeoffs:** powerful but constrained (caps, prerequisites, costs, situational choices) to avoid “solved” builds.
- **Early taste, late mastery:** systems should onboard players into goals and pay off more deeply over time.

**Cross-cutting implementation notes**
- Layer onto existing souls (rarity, base stats, affixes, traits, enhancement, essence, Soul Table), the Soul Groups loadout system, and the Condense → Catalyst → Infusion loop.
- Build on the implemented **Mastery** and **Codex** MVP foundations (additive per-soul mastery progression/milestones, account/family codex tiers/claims, related config/DB/service/UI hooks). See `plans/soul-mastery-codex-subplan.md` for MVP scope and deferred extensions.
- Respect current Soul Groups behavior: **4 groups per player, 4 slots per group, 1 summon per valid slot** (mixed soul types), activate/recall/hotswap semantics, and per-summon full effective stats (no legacy `groupCapacity`/group-division gameplay). Design future progression/buildcraft (cores, affinity, etc.) to apply per soul/summon and to compose cleanly with group activation.
- Prefer additive config/data and avoid DB schema changes initially where possible (PDC/JSON fields, config registries, account-level storage consistent with repo patterns); finalize storage at implementation time.
- Keep most rewards horizontal/utility/cosmetic or bounded; preserve caps/ICDs/diminishing returns to limit power creep.
- Build/verify with `./build.sh`. Follow repo config conventions (bundled resource + runtime fallback + backfill, preserve existing keys).

**Current baseline notes (implemented, relevant to this plan)**
- **Soul Groups loadout (current):** players have **4 saved groups** with **4 slots each**; each valid slot summons **1** soul (mixed types allowed, 1 summon per slot). One group is active at a time: activating the active group recalls/toggles off, activating another group hotswaps. Empty/invalid slots are skipped/cleaned on activation. **Soul Focus:** right-click opens the Soul Groups view, left-click performs quick activate/recall of the selected group; `/gloved soul menu` opens the Groups view. Commands use `/gloved soul group ...` with `group`/`groups` (and compatibility alias `team`/`teams`). Player-facing terminology is **Group/Groups**; config keys, DB schema, PDC keys, and internal APIs remain team-based for compatibility (e.g. `souls.teams.*`, `soul_teams`). Legacy `groupCapacity` ("Summons count") is retired from gameplay/UI/previews/lore (kept only as compatibility data where present).
- **Soul Mastery / Attunement (MVP):** per-soul mastery XP/level/rank and configurable milestone rewards (default essence claims), with summon hit/kill and capture/refine/mastery hooks as implemented, persisted additively and surfaced in soul lore/tooltips. Further reward effects, anti-farm tuning, and deeper system integrations remain deferred per `plans/soul-mastery-codex-subplan.md`.
- **Account/Family Codex (MVP):** additive account/family codex progress/tier tracking and claimable rewards (discovered/collected/refined/mastered foundations), surfaced in `/gloved soul codex` and the **Soul Codex** menu (accessible from Soul Groups below Soul Vault). Richer objectives, reward types, and browser UX remain deferred per `plans/soul-mastery-codex-subplan.md`.
- **Condensed Soul Essence item:** PDC-tagged, stackable material (default `AMETHYST_SHARD`, non-placeable/non-vanilla-interactable); configurable name/material/rate via `souls.table.essence.condense.*`. Lore shows total/value and per-item rate (no redundant title line). Right-click redeems **1** condensed item at a time (cancels normal interaction/placement) and grants Essence; rapid redeems show an accumulated action-bar total. Condensed Essence can also appear as a rare bonus in newly generated Lootr chest inventories (`elite.condensed-essence.*`, configurable).
- **Soul Catalyst (current):** single crafted catalyst item used for Infusion (no tiers/variants yet). Current lore describes infusion use; future tier/variant work should preserve/extend PDC identity and config-driven recipes/rules.
- **Config/UI conventions:** item/display color codes in soul-table config use `&` in YAML and are normalized to `§` in code. Player-facing Soul Table/Guide/Infusion copy avoids config/admin/internal implementation wording.
- **Soul Table recipe baseline:** early-game ingredients by default (`SOUL_SAND`, `COBBLESTONE`, `SOUL_TORCH`, `OAK_PLANKS`, `IRON_INGOT`) and reload-safe registration; existing server configs may retain prior values until changed/backfilled as applicable.

---

## 1. Catalyst Tiers / Variants

### Purpose
Add depth and choice to the crafting/infusion economy: catalysts express intent and support different progression paths.

### Player loop
1. Condense essence and gather materials/fragments → 2. Craft a specific **tier/variant** for a goal → 3. Use in Infusion (or future systems) → 4. Progress to higher tiers/variants.

### Mechanics
- **Tiers (progression):**
  - **Basic Catalyst:** entry-level, standard replace/early infusion actions.
  - **Refined Catalyst:** mid-tier, higher-rarity or add-trait infusions.
  - **Pristine/Perfect Catalyst:** endgame, top-tier infusions/awakened interactions or premium safeguards.
- **Variants (intent/strategy), examples:**
  - **Stability:** extra safety/clarity for valuable souls.
  - **Imprint:** oriented toward **adding** traits.
  - **Overwrite:** oriented toward **replacing** traits (supports cheaper/easier refinement).
  - **Resonant/Focused (optional):** unlocks niche options or supports affinity/core-related actions; for deterministic infusion, may reduce cost or gate options.
- **Usage rules:** each infusion action specifies accepted tier(s)/variant(s) and quantities via config (e.g. Mythic add requires higher tier than replace). Preserves **replace cheaper/easier than add**.
- **Acquisition:** crafted from condensed essence + tiered materials (+ fragments). Higher tiers may require rarer materials or endgame drops (configurable hooks).

### Interaction with other systems
- **Condense/Essence sinks:** primary material source (Condensed Soul Essence is now a concrete item/source, including rare Lootr chest drops).
- **Infusion:** core consumer with add/replace-specific variants.
- **Cores/Affinity/Fragments:** catalyst ingredients/consumers.
- **Mastery/Codex:** potential objective/reward hooks (future/deferred extensions).
- **Bounties:** reward sources.

### UI / feedback
- Item lore states tier, variant, and valid uses.
- Infusion UI shows required/accepted catalysts, missing items, and variant benefits.

### Config/data considerations
- Config-driven tier/variant definitions, recipes, accepted-catalyst rules per action, and item tags/models.
- PDC must encode tier/variant for exact matching.

### Balance/risks
- Avoid clutter; start with tiers + 2–3 clear variants.
- Variants should offer strategic choice, not mandatory extra grind for the same result.

---

## 2. Fragments & Targeted Crafting

### Purpose
Reduce pure RNG frustration and provide deterministic paths toward specific goals while keeping rare outcomes meaningful.

### Player loop
1. Play content / salvage / complete objectives → 2. Earn **fragments** → 3. Craft into catalysts, cores, affinity items, or infusion materials → 4. Progress toward a chosen build.

### Mechanics
- **Fragment types (start small):**
  - **Essence/Material fragments:** generic (e.g. Condensed Shards) convertible into condensed essence/catalyst materials.
  - **Trait fragments (optional, scoped):** aligned to trait groups or specific traits for focused catalysts or targeted infusion support.
  - **Family/Affinity fragments:** for codex progress, affinity attunement, or themed cores.
- **Sources (configurable):** salvaging souls (better with higher rarity/quality/mastery), codex milestones, bounties/contracts, endgame activities/bosses/exploration loot (e.g. Lootr chests), and optional small infusion byproducts (pity tuning). Condensed Soul Essence itself is already a rare Lootr bonus in the current baseline and can serve as an early material/essence bridge.
- **Crafting/recipes:** deterministic, visible recipes combining fragments + condensed essence + materials into catalysts/cores/attunement items (crafting table or Condense/Craft UI).
- **Guardrails:** targeting reduces randomness/cost/time, but top-tier results still require meaningful investment.

### Interaction with other systems
- **Condense/Catalysts:** primary conversion/crafting targets.
- **Infusion:** targeted support for chosen traits/build refinement.
- **Mastery/Codex:** key sources and rewards (existing foundations, with future extensions as needed).
- **Cores/Affinity:** primary crafting inputs.

### UI / feedback
- Clear item lore (“used for”) and recipe/progress visibility in Condense/Craft or guide UI.

### Config/data considerations
- PDC-tagged fragment definitions, drop/salvage tables, recipes, caps/stacking.
- Keep types limited and expandable via config.

### Balance/risks
- Tune generosity to avoid trivializing or irrelevance.
- Keep inventory manageable (stackable, consolidatable).

---

## 3. Essence Sinks with Player Choice

### Purpose
Ensure Essence/condensed materials always have meaningful uses, enabling players to choose progression priorities.

### Sink portfolio
- **Condense → Catalyst crafting (core):** foundational conversion loop.
- **Infusion (core):** high-cost, high-impact customization (add/replace).
- **Condensed Essence liquidity/redemption (baseline):** right-click redeem converts condensed items back into Essence (when reversible/available), supporting flexible sink/source balancing.
- **Socketed Cores crafting/upgrading:** modular build investment.
- **Affinity attunement/re-attunement:** thematic/soft-synergy customization with meaningful cost.
- **Targeted crafting & exchanges:** fragments↔materials, focused catalysts, components.
- **Mastery/Codex accelerators (bounded, optional):** capped convenience, not required power.
- **Convenience/QoL unlocks (limited/account-level):** e.g. additional Soul Group slots/groups beyond the current baseline (if ever expanded), vault/cosmetic unlocks where appropriate.

### Design principles
- No single dominant sink; each serves a different goal (power, customization, collection, QoL).
- Clear value/cost previews; scales across early/mid/endgame priorities.

### UI / feedback
- Surfaces showing balances (Essence, condensed, catalysts, fragments) and outcomes per sink; optional non-prescriptive “next sink” hints.

### Config/data considerations
- Configurable costs/rewards; extensible for new sinks/recipes.
- Consistent balance/cap tracking and transaction/compensation patterns.

### Balance/risks
- Monitor inflation/deflation (earn rates vs sink costs).
- Keep accelerators minor/capped.

---

## 4. Socketed “Soul Cores” (build slots)

### Purpose
Add a modular, swappable customization layer for playstyle/utility roles (horizontal progression) without endless base-stat inflation.

### Player loop
1. Unlock core slot(s) → 2. Craft/find a core → 3. Socket into a soul → 4. Test, swap, or upgrade to refine builds.

### Mechanics
- **Slots:** **1 core slot** per eligible soul initially; optional **2nd slot** as high-end reward (configurable/gated).
- **Core types (playstyle/utility, bounded):** command/aura behavior, trigger modifiers, or theme/synergy amplifiers (e.g. guardian, hunter, tempo, debuff-synergy). Prefer behavior/utility/conditional effects with ICDs/caps; any stat bonuses small and bounded.
- **Socketing/removal:** at Soul Table (dedicated sub-UI/panel). Unsocketing may have a small configurable cost/reagent; cores become inventory items when unsocketed.
- **Crafting/upgrading:** condensed essence + catalysts + fragments (+ affinity materials for themed cores). Tier upgrades improve utility/consistency, not raw power spikes.

### Interaction with other systems
- **Infusion/Traits:** complementary; UI surfaces synergies/conflicts.
- **Mastery/Codex:** unlock slots/recipes/rewards (potential future extensions of existing foundations).
- **Affinity:** resonant cores gain thematic/conditional bonuses.
- **Essence sinks/Catalysts/Fragments:** crafting inputs.

### UI / feedback
- Soul details show sockets/effects/synergies; socketing UI previews outcomes and warnings.

### Config/data considerations
- Config/data registry for core definitions (id, tier, effects/params, recipes, eligibility).
- Store socketed core IDs per soul (additive serialized field); apply at summon/snapshot time via existing hook/attribute systems.

### Balance/risks
- Limit stacking/power creep (slots, ICDs, diminishing returns).
- Start with a small curated core set.

---

## 5. Affinity/Element Flavor (lightweight)

### Purpose
Add thematic identity and soft build direction (flavor + light synergies) without heavy elemental counters.

### Player loop
1. Discover/capture souls and affinity materials → 2. Attune/unlock affinity → 3. Gain thematic visuals and light synergies → 4. Optionally re-attune/craft affinity cores.

### Mechanics
- **Affinity set (small, readable):** e.g. Flame, Frost, Storm, Nature, Umbral/Arcane (configurable).
- **Acquisition (configurable):** innate/rolled tendency, crafted attunement/re-attunement (cost/cooldown), or hybrid.
- **Effects (lightweight):**
  - **Visual identity:** particles/sounds/badges.
  - **Soft synergies:** modest, capped, conditional bonuses with matching traits/cores/situations (e.g. Umbral with debuff synergy, Storm with tempo/multi-hit).
  - **No hard counters by default.**
- **Progression links:** infusion/mastery may unlock or strengthen resonance; mastery can unlock cosmetic variants.

### Interaction with other systems
- **Traits/Cores:** primary synergy targets.
- **Fragments/Catalysts/Essence sinks:** attunement materials/costs.
- **Mastery/Codex:** collection/completion objectives (potential future extensions of existing foundations).

### UI / feedback
- Affinity icon/description and active synergy vs cosmetic indicators in tooltips, codex, and previews.

### Config/data considerations
- Config/registry for affinity definitions (visuals, synergy tags/modifiers, recipes/costs).
- Store affinity per soul (additive field); prefer data-driven effect mapping.

### Balance/risks
- Keep affinities flavor + light synergy, not mandatory damage gates.
- Minimize visual noise; make effects configurable where practical.

---

## 6. Recommended implementation sequencing (incremental)

1. **Foundations:** extend item/recipe/config framework for catalyst tiers/variants and fragment types (building on Condense/Catalyst).
2. **Fragments & targeted crafting + Catalyst tiers/variants integration:** deepen economy and player choice; wire into Infusion/Condense.
3. **Essence sink expansion pass:** unify balances/previews/recipes and add bounded optional sinks.
4. **Soul Cores (slot + small curated set):** modular buildcraft with synergies/warnings.
5. **Affinity (flavor + light synergies):** thematic layer over traits/cores.

## 7. Verification & tuning (when implemented)

- `./build.sh` succeeds per phase; in-game validation for each system.
- Validate transactional safety (DB vs inventory), cache invalidation, reload/backfill behavior, and UI clarity/previews.
- Playtest tuning: pacing (earn vs sink), replace-vs-add costs, fragment generosity, core/affinity impact, and overall “rewarding vs exhausting” progression with distinct, non-solved builds. Tie new systems into existing Mastery/Codex foundations where appropriate without unbalancing MVP rewards/progression.
