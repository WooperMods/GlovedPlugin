# Soul Mastery / Attunement + Account/Family Codex — Sub-Implementation Plan (MVP)

> **Scope:** implement only systems **1. Soul Mastery / Attunement** and **2. Account/Family Codex** from `plans/soul-endgame-systems.md`, in a modular/extensible way that speeds up future content.
>
> **Build/verify:** `./build.sh` only. Follow repo config conventions (bundled resource + runtime fallback + backfill, preserve existing keys). Additive DB migrations only.

## 1. Goals and constraints

- Layer onto existing souls, Soul Groups (4×4, 1 summon/slot, activate/recall/hotswap, per-summon full stats), Soul Table, and Condense/Catalyst/Infusion baseline.
- Keep rewards mostly horizontal/utility/cosmetic or bounded; avoid raw stat inflation/power creep in MVP.
- Per-soul progression is soul-bound and should survive normal vault/table actions unless explicitly reset (configurable defaults below).
- Modular organization: isolate new logic in dedicated packages/classes (`summon/soul/mastery/*`, `summon/soul/codex/*` or similar) with clear APIs, config objects, registries/hooks so future milestones/rewards/objectives can be added without rewriting core flows.

## 2. Current baseline (integration points)

- **Storage:** `souls.db` via `SoulDatabase` (additive migrations), `SoulRepository` (synchronized JDBC), `PlayerSoulState` in `soul_player_state`.
- **Domain:** `SoulInstance` immutable-ish with `with*` copy methods; persisted columns include affixes/traits/base_stats/enhance/favorite/group_capacity etc. `SoulGenerator` creates souls.
- **Codex (existing basic):** `soul_codex(owner_uuid, family_key, highest_rarity, captured_count)` + `upsertCodex/listCodex/countCodex`; updated on capture/collect/trap side effects; surfaced in `/gloved soul info|codex`.
- **Soul Table ops:** `SoulTableService` / UI invs handle combine/enhance/reforge/salvage/condense/infusion (need to preserve/update mastery/codex appropriately).
- **Summon/combat hooks:** `SummonCombatService` applies hits and calls progression `onMinionHit(owner,minion,target,profileName,finalDamage)`; minion carries `summon_soul_id` PDC. `BaseSummonProgressionBridge`/`SummonXpState` track kill credit and grant AuraSkills XP on hit/kill/capture/cast. `SummonKillAttributionListener` triggers `onEntityDeath`.
- **UI:** Soul Table hub/nav/screens, Soul Guide, Soul Vault/Groups, `SoulLoreBuilder` shared lore, command handler `GlovedSoulSubcommandHandler`.

## 3. Sub-implementation scope (MVP) and deferred items

### 3.1 Soul Mastery / Attunement — MVP

**Implement now**
- **Per-soul mastery tracking:** additive DB columns on `souls` (e.g. `mastery_xp INTEGER NOT NULL DEFAULT 0`, `mastery_level INTEGER NOT NULL DEFAULT 0`, `mastery_claimed TEXT`/JSON for claimed milestone ids; exact schema finalized for simplicity/extensibility). Extend `SoulInstance`, mapping, repository getters/updaters, and `with*` helpers to preserve mastery across copy/update flows.
- **Config:** `souls.mastery.*` in `summon.yml` + `SoulConfig`/dedicated `MasteryConfig` loader with defaults/backfill: enabled, max level, XP curve/thresholds or per-level formula, XP source amounts/cooldowns/caps (hit/kill/capture hooks), and milestone definitions (level -> reward/type/metadata). Keep data-driven where practical.
- **Service/module:** `SoulMasteryService` (or `mastery` package) responsible for XP gain, level-up evaluation, milestone unlock/claim state, persistence, and cache invalidation. Integrate via `SoulService` facade methods for callers.
- **XP hooks (practical MVP):**
  - Award mastery XP on **summon-attributed kill** and **summon hit** by resolving the acting summon’s `summon_soul_id` (available on minion in combat/progression hooks). Apply configurable per-soul/per-owner cooldowns and per-minute caps to limit abuse.
  - Optional small capture-time seed XP hook if low-risk/configurable.
  - **Deferred/unclear:** robust "active in valid combat" time tracking, damage-taken participation XP, trait-proc XP, elite/boss/challenging-content bonus detection, AFK/farm heuristics beyond basic ICD/rate caps, and full anti-exploit tuning. Design API to accept future XP event sources.
- **Preservation rules (defaults):** mastery persists on enhance/reforge and normal ownership-preserving operations. Combine/salvage consume souls (mastery on consumed souls removed with them) — **deferred:** conversion of consumed mastery into bonus essence/fragments/mastery shards (add hook point/config later). Deleting a soul removes its mastery naturally.
- **Milestones/attunements (MVP):** config-defined milestone levels tracked/claimable per soul with simple reward handling (initially safe rewards such as essence grants and/or cosmetic/title metadata recorded for display; no gameplay stat perks). Modular reward handler interface/registry so cosmetic/utility/token rewards can be added later.
- **UI/feedback:** show mastery rank/level and XP progress in `SoulLoreBuilder`/soul details (vault, drops, inspect). Basic level-up/milestone feedback messages/sounds. Dedicated Mastery/Attunement sub-UI in Soul Table is **deferred** unless trivial; provide command/info surfacing and extensible data for future panel.

**Deferred (documented, not blocking MVP)**
- Cosmetic visuals/effects on summons (nameplate accent, particle trails, aura/idle effects) as applied gameplay visuals.
- Utility perks that alter behavior (recall/cast feel, pickup/aggro tweaks) and attunement tokens/items.
- Mastery-gated infusion tiers/cost reductions, mastery-based core-slot unlocks, resonance perks (future systems dependencies).
- Full anti-farm combat-validity/AFK detection and elite/boss bonus sourcing.
- Mastery conversion bonuses on combine/salvage.

### 3.2 Account/Family Codex — MVP

**Implement now**
- **Evolve existing codex storage additively:** extend `soul_codex` with columns needed for MVP tiers/progress/claims (e.g. `first_captured_at`, `discovered_claimed`, `collected_claimed`, `refined_claimed`, `mastered_claimed`, progress counters like `mastered_count`/`refined_count` as feasible) or introduce companion table(s) with FK-like owner/family keys; keep `highest_rarity`/`captured_count` compatible. Extend `CodexEntry`/repository APIs.
- **Progress tracking:** update codex on capture/collection (existing), and on relevant soul progression events available in MVP:
  - **Discovered/Collected:** first capture and capture count/highest rarity (existing semantics, formalized as tiers).
  - **Refined:** increment/track when a soul of a family reaches a refined state (MVP hook: enhancement level >= threshold and/or infused — integrate with enhance/infuse operations if present).
  - **Mastered:** increment/track when a soul reaches configured mastery milestone/rank (via mastery service hook).
  Exact tier thresholds/objective definitions will be config/data-driven with sensible defaults.
- **Codex service/module:** `SoulCodexService` (or `codex` package) encapsulating progress updates, tier evaluation, reward eligibility/claims, and discovery/tier-up notifications. Keep repository dumb where reasonable.
- **Rewards/claims (MVP):** simple, bounded, account-wide claimable rewards (e.g. essence grants, cosmetic/badge text recorded) with once-per-account claim tracking. Modular reward handler/definition structure for future cosmetics/QoL/efficiency rewards and fragment/catalyst rewards (deferred reward types).
- **UI/feedback:** improve `/gloved soul codex` to show tier/progress/claim summary; add a **Codex view/screen** accessible from an existing soul UI entry point (e.g. Soul Guide category, Soul Table hub button, or Vault nav) as a modular inventory screen. Dedicated full codex browser with filters/search/trackable hints is partially implemented as feasible, with extensible rendering/data providers.
- **Discovery moments:** message/action-bar/toast-style feedback (using existing player messaging patterns) on first discovery and tier completion/unlocks.

**Deferred (documented, not blocking MVP)**
- Rich category sets (trait/affinity/core-based collections), cross-system objectives for catalysts/cores/affinity/fragments/bounties (depend on future systems).
- Cosmetic badges/UI frames/summon titles/effects as applied rewards, vault/sort/filter/loadout-slot/QoL unlock rewards, efficiency perks, fragment/catalyst material rewards.
- Advanced codex UI (search, filters, pinning, hints, full completion browser polish) beyond a solid extensible base view.
- Bounties/contracts and pity/targeted-path integrations.

## 4. Proposed modular structure

- `summon/soul/mastery/`
  - `SoulMasteryConfig` (or records/config model), `MasteryRank`/curve helpers
  - `SoulMasteryService` (XP/level/milestones/persistence hooks)
  - `MasteryMilestone`, `MasteryRewardType`, `MasteryRewardHandler` (registry-ready)
- `summon/soul/codex/`
  - `SoulCodexConfig` / objective-tier definitions
  - `SoulCodexService` (progress/tier/claims/notifications)
  - `CodexTier`, `CodexRewardDefinition/Handler`, view models
  - `ui/SoulCodexInv` (if added) or integrate into existing table/guide UI
- **Integration touchpoints (kept thin):** `SoulDatabase` migrations, `SoulRepository` additive methods, `SoulInstance`/`SoulGenerator`/`SoulLoreBuilder`, `SoulService` facade + mutation hooks (enhance/reforge/combine/salvage/infuse/capture), summon combat/progression listeners for XP hooks, command and UI routing/handlers.

## 5. Data/config design notes

- All schema changes additive and idempotent; backfill-safe defaults.
- Prefer storing compact JSON/text for extensible milestone/claim state where normalized tables are overkill for MVP, but keep queryable columns for hot paths (level/xp/counts/rarity).
- Config keys under `souls.mastery.*` and `souls.codex.*` with bundled defaults in `src/main/resources/summon.yml`, runtime fallbacks, and backfill that preserves existing admin values.
- Cache invalidation: reuse `SoulService` soul/selected caches and invalidate on mastery/codex-affecting mutations.

## 6. Implementation sequence

1. **Sub-plan + scaffolding:** config models/defaults/backfill, DB migrations, domain/repository extensions.
2. **Mastery core:** `SoulMasteryService`, XP hooks from summon combat/progression events, persistence, level/milestone evaluation, preserve-on-mutation behavior.
3. **Codex core:** extend codex storage/service, hook capture/refine/mastery progress updates, tier/claim logic, notifications.
4. **UI/commands/lore:** lore + inspect/command surfacing, codex screen/entry point, basic feedback messages.
5. **Build/fix/polish:** `./build.sh`, resolve compile/runtime integration issues, ensure modular wiring/reload safety.

## 7. Risks / watchouts

- `SoulRepository`/`SoulDatabase` are synchronized and shared; avoid deadlocks and keep transactions consistent with existing `transaction(...)` usage.
- `SoulInstance` constructors/`with*` methods and serialization sites are numerous; missing a field propagation path can drop mastery data.
- Kill attribution to specific soul requires resolving soulId from summon/minion context; progression kill credit currently tracks profile/owner — may need additive extension or alternative hook path.
- UI inventory title/slot routing must remain compatible with existing handler patterns.
- Avoid changing behavior of unrelated systems; preserve legacy compatibility (groupCapacity/team internals etc.).

## 8. Acceptance for this MVP pass

- `./build.sh` succeeds.
- New souls and existing souls (after additive migration) support mastery fields without breaking load/save.
- Existing basic codex data remains valid; codex gains richer progress/tier/claim tracking foundations.
- Mastery XP can be gained from implemented summon combat hooks and is visible to players (lore/command/UI as implemented).
- Codex progress/tiers update from implemented hooks and are viewable/claimable in MVP form.
- Implementation is modular with clear extension points for deferred rewards/effects/objectives.
