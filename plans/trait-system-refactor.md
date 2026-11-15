# Soul Trait System Refactor — Full Implementation Plan

## 0. Summary

Refactor soul traits from a scattered enum + hardcoded behavior/UI model into a centralized, registry-backed trait system so that **adding a new trait is primarily a single edit in a definitions module** (metadata + hooks), with automatic inclusion in the roll pool, persistence lookup, guide, lore, and runtime behavior dispatch.

**Locked decisions (from discussion):**
- **Behavior scope:** full hook-based centralization now (damage/crit/procs/mitigation/ability modifiers/VFX triggers moved into trait definitions; systems dispatch generically).
- **Type migration:** replace `SoulTrait` enum with a registry-backed trait type/definition identity. **Persisted trait ID strings must remain unchanged**: `VAMPIRIC`, `FRENZIED`, `BULWARK`, `HEXING`, `SPLINTERING`, `UNSTABLE`, `PACKBOUND`.
- **Guide metadata:** shared/default guide icon for all traits; each trait definition provides `displayName` + `guideDescription` lines (data-driven traits page).

**Non-goals / constraints:**
- No DB schema change (`soul_tables`/`souls` trait storage remains JSON array of trait IDs in existing column/format).
- No balance/behavior changes: this is a parity refactor. Port constants/chances/caps/ICDs/formulas/ordering 1:1 from current implementation.
- Traits remain code-defined (central module), not config-defined (builder/registry can support config later if desired).
- Keep existing `SummonVfx` presets/builder primitives reusable; trait definitions decide *when* to trigger them.

---

## 1. Current state (inventory)

### 1.1 Identity, storage, domain
- `dev.gloved.glovedplugin.summon.soul.domain.SoulTrait` — enum (`VAMPIRIC, FRENZIED, BULWARK, HEXING, SPLINTERING, UNSTABLE, PACKBOUND`).
- `dev.gloved.glovedplugin.summon.soul.domain.SoulInstance`
  - Fields: legacy `trait` (first), `List<SoulTrait> traits`, `hasTrait(SoulTrait)`, constructors/`with*`/copy paths carry trait lists.
- `dev.gloved.glovedplugin.summon.soul.db.SoulRepository`
  - Serialize: `trait.name()` JSON array; deserialize: parse JSON array of names -> `SoulTrait.valueOf`, with legacy single-name fallback.
- `dev.gloved.glovedplugin.summon.soul.db.SoulDatabase` / schema: `trait TEXT` column (unchanged).

### 1.2 Rolling / pool / formatting
- `dev.gloved.glovedplugin.summon.soul.domain.SoulGenerator`
  - `rollTraits(rarity, rng)`: pool = `SoulTrait.values()` (uniform), rarity-based trait-count distribution, shuffle+pick.
  - `describe(...)`: prints `trait.name()` in brackets.
- Trait count distribution (current behavior to preserve):
  - COMMON: 10% chance 1 else 0
  - UNCOMMON: 20% chance 1 else 0
  - RARE: 1, +50% second, +5% third (independent)
  - EPIC: 2, +25% third
  - LEGENDARY: 2, +50% third, +5% fourth
  - MYTHIC: 3, +25% fourth, +5% fifth
  - Capped to available pool size.

### 1.3 Behavior sites (must be centralized via hooks)
- `summon/combat/SummonCombatService`
  - Outgoing/hit modifiers: `FRENZIED` `soulDamageMult *= 1.08`; `PACKBOUND` situational multiplier via `packboundMultiplier(...)` (same-family summoned allies in radius, bonus when >=2).
  - Hit accents: `playFrenziedHit` when frenzied, `playPackboundHit` when packbound active.
  - `applyUnstableTrait(...)` proc: requires `UNSTABLE`, per-minion ICD (`unstableIcd`, 80 ticks), 18% chance, AOE radius `2.75`, max 3 extra targets, AoE damage `clamp(max(finalDamage, baseDamage)*0.35, 0.75, 6.0)`, burst VFX `playUnstableBurst` at primary target, excludes primary/owner/minion/protected/summons, applies pipeline damage and kill credit; debug log on proc.
  - `packboundMultiplier`: checks tracked active minions, same world, `distanceSquared <= 256.0`, same family/profile key, returns `1.0 + min(0.12, (same-1)*0.03)` when `same >= 2` else `1.0`.
- `summon/progression/SummonAbilityService`
  - `SoulCombatMods`: aggregates soul crit/ability potency and carries trait list; `FRENZIED => critChanceBonus += 0.03, critDamageBonus += 0.08, abilityPotencyMult *= 1.05`.
  - Trait checks in ability/crit/lifesteal/debuff/cleave paths: `HEXING` (debuff chance/amplify + `playHexingProc`), `SPLINTERING` (cleave chance/ratio/bonus behavior + `playSplinteringProc`), `VAMPIRIC` (lifesteal heal/cap bonuses + `playVampiricProc`), FRENZIED crit/potency influence. **Port exact current formulas/thresholds 1:1.**
- `summon/runtime/SummonListener`
  - Incoming damage on summoned minions: owner toughness reduction + TENACIOUS affix + `BULWARK` trait (`totalReduction += 0.10`, plays `playBulwarkProc`), final capped reduction `min(totalReduction, 0.8)`.
- `summon/soul/service/SoulService`
  - Preview/estimate logic includes FRENZIED effects (e.g. damage multiplier *1.08, crit chance +0.03 / multiplier +0.08 in estimates). Must stay consistent with runtime via shared trait hook contributions/helpers.
- `summon/vfx/SummonVfx`
  - Presets: `playVampiricProc`, `playHexingProc`, `playSplinteringProc`, `playFrenziedHit`, `playPackboundHit`, `playBulwarkProc`, `playUnstableBurst` (builder-based).

### 1.4 Presentation
- `summon/soul/item/SoulLoreBuilder`: traits section prints `pretty(trait.name())`.
- `summon/soul/table/ui/SoulTableGuideInv`: `buildTraits()` iterates `SoulTrait.values()`, fixed slots, default/trait-specific item rendering via `traitItem(SoulTrait)` switch with hardcoded descriptions and icon per trait. **Refactor target:** data-driven from registry with shared/default icon and definition descriptions.
- `summon/soul/guide/*` and Soul Guide book open `SoulTableGuideInv`.

### 1.5 Known compile/reference sites to audit
Any `import`/usage of `dev.gloved.glovedplugin.summon.soul.domain.SoulTrait` and enum API (`values()`, `valueOf`, `.name()`, switch) across `summon`, `core`, and soul table flows (e.g. `SoulTableService`, spawn/runtime/progression, item/lore, UI, DB). A full `rg "SoulTrait"` pass is required during implementation.

---

## 2. Target architecture

### 2.1 New module/package: `dev.gloved.glovedplugin.summon.soul.trait`

#### `SoulTraitDefinition` (immutable, canonical)
Responsibilities: identity, metadata for guide/lore, roll eligibility, and behavior hooks.

Suggested shape:
```java
public interface SoulTraitDefinition {
    String id();                         // stable persisted key, e.g. "VAMPIRIC"
    String displayName();                // e.g. "Vampiric"
    List<String> guideDescription();     // immutable, non-empty player-facing lines
    int order();                         // stable sort order for guide/pool iteration
    boolean rollable();                  // included in generation pool (default true)
    SoulTraitHooks hooks();              // no-op defaults
}
```
Implementation notes:
- Concrete final class/record (e.g. `DefaultSoulTraitDefinition`) with defensive copies and `equals/hashCode/toString` by `id`.
- `id` must be uppercase canonical (matching legacy enum names) and unique in registry.

#### `SoulTraitBuilder` (fluent)
```java
public final class SoulTraitBuilder {
    public static SoulTraitBuilder trait(String id) { ... }
    public SoulTraitBuilder displayName(String displayName);
    public SoulTraitBuilder description(String... lines);      // or List<String>
    public SoulTraitBuilder guideDescription(List<String> lines);
    public SoulTraitBuilder order(int order);
    public SoulTraitBuilder rollable(boolean rollable);
    public SoulTraitBuilder hooks(SoulTraitHooks hooks);       // or granular hook setters
    public SoulTraitDefinition build();
}
```
- Validate: non-blank `id`, non-blank `displayName`, non-empty description, non-null hooks (default no-op).
- Builder does **not** self-register (registration explicit via `SoulTraitRegistry.register(...)` or `SoulTraits` bootstrap) for clarity/testability.

#### `SoulTraitRegistry` (runtime source of truth)
```java
public final class SoulTraitRegistry {
    public void register(SoulTraitDefinition def);                 // throws/logs on duplicate
    public Optional<SoulTraitDefinition> byId(String id);
    public SoulTraitDefinition getOrNull(String id);
    public List<SoulTraitDefinition> all();                        // stable order, unmodifiable
    public List<SoulTraitDefinition> rollable();                   // stable order, rollable==true
    public SoulTraitDefinition resolve(String id, SoulTraitDefinition fallbackOrNull); // safe helper
    public boolean isRegistered(String id);
    public void freeze();                                          // optional: prevent late mutation after init
}
```
- Storage: `LinkedHashMap<String, SoulTraitDefinition>` (or map + ordered list) keyed by canonical id.
- `byId` should normalize/canonicalize input (`trim`, case-insensitive match to canonical id) to be tolerant of legacy/malformed stored values.
- Unknown-id behavior: `byId` empty, debug-log once per unknown id (rate/seen-set) rather than throwing.

#### `SoulTraits` (central definitions module — single edit point)
A single class responsible for constructing and registering all built-in traits.

Suggested API:
```java
public final class SoulTraits {
    public static void registerAll(SoulTraitRegistry registry, /* optional services/context */) { ... }
    // Optional public accessors/constants for canonical IDs if needed for rare non-hook call sites
    public static final String ID_VAMPIRIC = "VAMPIRIC"; // etc.
}
```
- Defines metadata + hooks for all 7 existing traits in one place (or delegates to small per-trait factory methods within this same module/package to keep it organized, but definitions remain centralized/discoverable).
- **Registration order = stable `all()`/guide order** (preserve a sensible, deterministic order; recommend current enum order unless product preference dictates otherwise).
- Adding a new trait = add its definition + hook implementation and register it here.

#### `SoulTrait` (replaced enum; registry-backed identity)
Replace `summon.soul.domain.SoulTrait` enum with a type that preserves persisted identity and eases migration.

Recommended shape (value object backed by definition/id):
```java
public final class SoulTrait {
    private final String id;
    // equals/hashCode/toString by id; resolve definition via registry
    public String id();
    public String name();                       // compat alias for id()/enum-style usage
    public SoulTraitDefinition definition();    // resolved (or cached) from registry
    public String displayName();
    public List<String> guideDescription();
    public static SoulTrait of(String id);      // canonicalized
}
```
Migration compatibility goals:
- `id()`/`name()` return the canonical persisted string (e.g. `VAMPIRIC`).
- `equals/hashCode` by id so `List#contains`, set membership, and comparisons remain correct.
- `toString()` returns id (or stable form) to avoid breaking logs/serialization assumptions.
- Static helpers to replace enum API where convenient: `SoulTrait.fromId(String)`, `SoulTrait.all()`/`values()` compatibility wrappers delegating to registry **if needed** during migration (prefer direct registry access in refactored code).

> **Implementation choice:** implement `SoulTrait` in `summon.soul.domain` (replacing enum) or move/introduce canonical trait type in `summon.soul.trait` and adapt domain references. Pick one consistent package and update imports; keep API minimal and explicit.

### 2.2 Hook/context API (no-op defaults, parity-focused)

Create hook interfaces/records in `summon.soul.trait` (or `summon.soul.trait.hook`). Services iterate a soul’s resolved trait definitions in stable order and invoke hooks; shared mechanics remain in services.

#### Core hook contract
```java
public interface SoulTraitHooks {
    default void modifyOutgoingDamage(OutgoingDamageContext ctx) {}
    default void afterPrimaryHit(HitContext ctx) {}
    default void modifyIncomingDamage(IncomingDamageContext ctx) {}
    default void modifyCrit(CritContext ctx) {}
    default void modifyAbility(AbilityContext ctx) {}
    default void contributePreview(PreviewContext ctx) {} // for SoulService estimate parity
}
```
(Exact split can vary; keep contexts cohesive and sufficient for all current traits without per-trait `instanceof`/id checks in services.)

#### Contexts (mutable accumulators / accessors; suggested)
- **`OutgoingDamageContext`**
  - Read: `plugin`, `summonConfig`, `tracker`/`summonManager` access, `minion`, `owner`, `target`, `soul`, `soulSnapshot`, `baseDamage`, `finalDamage` (current), `nowTick`, `profileName`, helper `double packboundMultiplier()`/environment data.
  - Write: `multiplyDamage(double factor)`, `addDamage(double delta)` as needed, flags/VFX intent if useful.
  - Use for: FRENZIED `*1.08`, PACKBOUND situational multiplier, and hit-time trait accents (or split accents to `afterPrimaryHit`).
- **`HitContext`** (post-primary-hit / proc phase)
  - Read: all combat entities/owner/soul/config/services, `finalDamage`, `baseDamage`, `crit`, `targetDebuffedBefore`, `nowTick`, progression/credit helpers.
  - Use for: UNSTABLE proc (chance, ICD, AoE damage/targets/radius, protection/exclusion rules, VFX, kill credit), and any on-hit trait VFX/side effects not tied to damage math.
- **`IncomingDamageContext`**
  - Read: `plugin`, `victimMinion`, `attacker`, `ownerId/owner`, `soul`, `soulSnapshot`, `baseEventDamage`/current damage.
  - Write: accumulate/apply reduction (e.g. `addReduction(double)`), request mitigation VFX.
  - Use for: BULWARK `+0.10` contribution (final cap remains enforced centrally in listener/service as today: `min(totalReduction, 0.8)`).
- **`CritContext`**
  - Read/write: `critChance`, `critDamageMultiplier`, `baseDamage`, `finalDamage`, owner stats/config, `soul/snapshot`.
  - Use for: FRENZIED `+0.03` crit chance, `+0.08` crit damage (combat and ability crit resolution paths).
- **`AbilityContext`**
  - Read/write/modifiers for: ability potency multiplier, debuff proc chance/amplification, cleave chance/ratio/behavior params, lifesteal heal amount and per-hit cap/bonus behavior, VFX triggers.
  - Must cover current `HEXING`, `SPLINTERING`, `VAMPIRIC`, and FRENZIED potency effects in `SummonAbilityService`.
- **`PreviewContext`** (or equivalent hooks)
  - Read: `viewerId/owner`, `soul`, `base/enhanced stats`, `soulSnapshot`, `summonConfig/progression`.
  - Write/accumulate: preview damage multiplier, crit chance/multiplier adjustments, or expose helper values used by `SoulService` estimates so preview == runtime parity (especially FRENZIED).

**Dispatch rules:**
- Iterate `soul.getTraits()` (resolved definitions) in stable stored/list order for each hook point.
- Skip null/unresolved traits defensively.
- Shared concerns (protection checks, cooldown consumption, base damage application, event damage commit/capping, global ICD maps infrastructure if centralized) remain in owning services; hooks express trait-specific deltas/procs.
- ICD storage: allow hooks to use service-provided per-trait keyed ICD helpers (e.g. `traitProcCooldown(minionId, traitId, nowTick, cdTicks)` / existing map access) or move UNSTABLE’s `unstableIcd` behind a generic per-trait cooldown utility to avoid reintroducing per-trait fields in services.

### 2.3 Persistence & compatibility contract

- **Storage format unchanged:** JSON array of trait ID strings in existing `trait` column (e.g. `["FRENZIED","BULWARK"]`).
- **Serialization:** write `definition.id()` / canonical id.
- **Deserialization:**
  - Accept JSON array of strings (current format) and legacy single-name string fallback.
  - Resolve each id via `SoulTraitRegistry`; collect resolved traits in original order (dedupe optional only if current behavior dedupes — **preserve current behavior**).
  - Unknown/unregistered ids: ignore safely and debug-log once per id (do not throw; do not corrupt soul load). This preserves forward/back compat and tolerance.
- **In-memory API:** `SoulInstance#getTraits()` returns resolved trait definitions/types (unmodifiable). `hasTrait` supports id/type/definition checks as needed by migrated call sites.
- **Item/transfer paths:** any soul item/drop/copy logic that serializes or reconstructs traits must use ids/registry resolution (audit `SoulDropItem`, soul table combine/reforge/copy flows, imports/exports if present).

### 2.4 Presentation contract

- **Soul Guide (`SoulTableGuideInv`)**
  - `buildTraits()` renders from `SoulTraitRegistry.all()` in stable order.
  - Shared/default icon for every trait entry (choose a consistent material, e.g. current generic trait icon/enchanting motif used in UI, unless preserving one is trivial; keep it uniform per decision).
  - Display `definition.displayName()` and `definition.guideDescription()` lines.
  - Remove hardcoded `traitItem(SoulTrait)` switch and enum iteration.
  - Slot layout: compute/fill slots deterministically from the ordered list (support future trait count > current 7 without layout breakage where feasible).
- **Lore (`SoulLoreBuilder`)**
  - Render trait display names from definitions (not `pretty(enum.name())`) to keep lore consistent with guide/metadata.
- **Descriptions source of truth**
  - Move current guide text from `SoulTableGuideInv` switch into `SoulTraits` definitions:
    - VAMPIRIC: “Your summons heal a bit when dealing damage.”
    - FRENZIED: “Hits harder and lands critical strikes more often.”
    - BULWARK: “Tougher summons that take less damage in fights.”
    - HEXING: “More likely to weaken enemies when your summons hit.”
    - SPLINTERING: “Better at hurting groups of enemies at once.”
    - UNSTABLE: “Attacks can burst and damage nearby enemies.”
    - PACKBOUND: “Fights stronger when other summons are nearby.” (preserve exact/near-exact current player-facing copy).

---

## 3. Trait-by-trait parity mapping (port 1:1)

| Trait | Current behavior | Current sites | Hook/context target |
|---|---|---|---|
| **VAMPIRIC** | Lifesteal bonuses: heal amount/cap adjustments and proc VFX on lifesteal | `SummonAbilityService`, `SummonVfx.playVampiricProc` | `modifyAbility` (+ any lifesteal hook point) triggers VFX/amount-cap modifiers with identical math |
| **FRENZIED** | Outgoing damage `*1.08`; crit chance `+0.03`, crit damage `+0.08`; ability potency `*1.05`; hit VFX; preview/estimate adjustments | `SummonCombatService`, `SummonAbilityService`, `SoulService`, `SummonVfx.playFrenziedHit` | `modifyOutgoingDamage`, `modifyCrit`, `modifyAbility`, `afterPrimaryHit`/hit accent, `contributePreview` |
| **BULWARK** | Incoming damage reduction `+0.10` (subject to global cap with other reductions, total capped at 0.8) and mitigation VFX | `SummonListener`, `SummonVfx.playBulwarkProc` | `modifyIncomingDamage` |
| **HEXING** | Increased debuff chance/amplification behavior + proc VFX | `SummonAbilityService`, `SummonVfx.playHexingProc` | `modifyAbility` |
| **SPLINTERING** | Increased cleave chance/ratio/bonus behavior + proc VFX | `SummonAbilityService`, `SummonVfx.playSplinteringProc` | `modifyAbility` |
| **UNSTABLE** | On-hit AoE proc: ICD 80t, 18% chance, radius 2.75, max 3 extra targets, AoE dmg `clamp(max(finalDamage,baseDamage)*0.35,0.75,6.0)`, burst VFX, protection/summon/owner exclusions, kill credit | `SummonCombatService.applyUnstableTrait`, `unstableIcd`, `SummonVfx.playUnstableBurst` | `afterPrimaryHit` (with trait-keyed ICD helper) |
| **PACKBOUND** | Situational outgoing damage multiplier based on nearby same-family summoned allies (active when bonus > 1, `1 + min(0.12,(same-1)*0.03)` for `same>=2`, same-world & within 256 dist²), hit VFX when active | `SummonCombatService.packboundMultiplier` + hit path, `SummonVfx.playPackboundHit` | `modifyOutgoingDamage` / `afterPrimaryHit` using shared helper logic |

> During implementation, treat the **current code as the spec**: copy/port exact conditionals, ordering, RNG points, cooldown keys, and exclusions.

---

## 4. Bootstrap & wiring

1. **Registry creation/ownership**
   - Instantiate `SoulTraitRegistry` once (owned by summon/soul subsystem or `GlovedPlugin`) and expose accessor if needed (e.g. via `SoulService`/summon module/plugin getter) for DB, generator, UI, and services.
2. **Registration timing (critical)**
   - Call `SoulTraits.registerAll(registry, ...)` early in enable/init flow: **before** soul DB loads/deserializes cached souls that may be accessed, before soul generation/roll paths run, before listeners/services resolve traits, and before UIs render guide/lore from registry.
   - Recommended: register during summon/soul subsystem construction or at start of `GlovedPlugin#onEnable` soul initialization sequence (before `SoulDatabase`/`SoulRepository` warm loads and before guide/command/service usage). Verify current init order in `GlovedPlugin`.
3. **Freeze (optional but recommended):** mark registry immutable after registration to catch late/duplicate registrations.

---

## 5. Implementation plan (ordered, parity-focused)

### Phase 1 — Scaffolding (no behavior change yet)
1. Add `summon.soul.trait` package with:
   - `SoulTraitDefinition` (+ impl), `SoulTraitBuilder`, `SoulTraitRegistry`
   - Hook interfaces and context classes (`SoulTraitHooks`, `OutgoingDamageContext`, `HitContext`, `IncomingDamageContext`, `CritContext`, `AbilityContext`, `PreviewContext` and any needed helpers)
   - `SoulTraits` skeleton
2. Define all 7 traits in `SoulTraits` with **correct ids, display names, and guide descriptions** (copied from current guide), default/shared icon handled in UI, `order` stable, `rollable=true`, hooks initially no-op or wired in Phase 5.
3. Decide and implement `SoulTrait` replacement strategy (domain type) and registry access pattern; keep code compiling incrementally (temporary adapters allowed only inside this refactor branch/step if necessary, but end state has no enum).

### Phase 2 — Type migration & registry integration
1. Replace `summon.soul.domain.SoulTrait` enum with registry-backed `SoulTrait` type/identity.
2. Update `SoulInstance`:
   - Store resolved trait types/definitions (`List<SoulTrait>` or `List<SoulTraitDefinition>` consistently).
   - Update constructors, getters (`getTraits`, legacy `getTrait`), `hasTrait` overloads, `with*`/copy methods, and `affixValue`/other logic unaffected except type signatures.
3. Update `SoulGenerator`:
   - `rollTraits` pulls from `SoulTraitRegistry.rollable()` (uniform over rollable definitions) and preserves rarity count distribution/cap logic exactly.
   - `describe` uses `displayName()`.
4. Update all compile sites to new API (`id()`/registry lookups instead of `valueOf/name/values/switch` where appropriate). Run/iterate with `rg "SoulTrait"` until clean.

### Phase 3 — Persistence/back-compat
1. Refactor `SoulRepository` serialization/deserialization to use trait ids + `SoulTraitRegistry` resolution.
2. Preserve legacy single-name trait storage read path.
3. Handle unknown ids gracefully (skip + debug-log once). Ensure no NPEs when traits list contains unresolved entries (filter to resolved).
4. Audit any other persistence/transfer paths (`SoulTableService`, soul drop/item encode-decode, combine/reforge result construction, caches) for correct trait propagation.

### Phase 4 — Behavior hook refactor (parity port)
Perform in small, verifiable slices; after each slice keep behavior identical.

1. **Incoming mitigation — `SummonListener` / BULWARK**
   - Replace direct `SoulTrait.BULWARK` check and hardcoded reduction/VFX with iteration over minion soul traits calling `modifyIncomingDamage`; keep toughness/TENACIOUS/total-cap logic centralized as today.
2. **Outgoing damage, on-hit accents, PACKBOUND, UNSTABLE — `SummonCombatService`**
   - Compute outgoing modifiers via `modifyOutgoingDamage` hooks (FRENZIED, PACKBOUND) instead of inline checks.
   - Move trait hit accents and UNSTABLE proc/AOE to `afterPrimaryHit` hooks with 1:1 logic and ICD handling (generic/trait-keyed cooldown helper).
   - Keep `packboundMultiplier` logic accessible to hooks (extract helper/util or pass sufficient context/tracker access).
3. **Crit/ability/lifesteal/debuff/cleave — `SummonAbilityService`**
   - Replace `SoulCombatMods` trait-contains logic and per-trait branches (FRENZIED/HEXING/SPLINTERING/VAMPIRIC) with `modifyCrit`/`modifyAbility` hook aggregation; preserve chance caps, multipliers, budgets, cooldowns, and VFX trigger conditions exactly.
4. **Preview/estimate parity — `SoulService`**
   - Replace hardcoded FRENZIED preview adjustments with trait hook contributions/shared helpers so UI/estimate outputs match runtime formulas.
5. **Service/registry plumbing**
   - Ensure hooks can access required collaborators (`plugin`, `SummonConfig`, `SummonInstanceTracker`, progression/ability services, VFX helpers) via context or lightweight provider without recreating tight coupling issues; prefer passing needed services in context.

### Phase 5 — Presentation & UX integration
1. **`SoulTableGuideInv`**
   - Replace `buildTraits()` + `traitItem(SoulTrait)` switch with data-driven rendering from `SoulTraitRegistry.all()` in stable order.
   - Use a **single shared/default icon** for all trait entries; render display name and description lines from definitions.
   - Make slot assignment robust for the registry list (at least current count; preferably dynamic row fill).
2. **`SoulLoreBuilder`**
   - Render trait display names from definitions/registry-backed traits.
3. **Any other trait-name formatting sites** (`SoulGenerator.describe`, inspect/tooltips/messages) use definition metadata.

### Phase 6 — Cleanup
1. Remove dead enum/switch code, obsolete per-trait branches, and unused imports/fields (e.g. legacy enum helpers, redundant trait VFX dispatch branches once hooks own them).
2. Ensure `SoulTraits` is the only place containing per-trait metadata/behavior definitions.
3. Confirm registry initialization order in `GlovedPlugin`/summon bootstrap and freeze registry if implemented.

---

## 6. File-level change checklist

### New files
- `src/main/java/dev/gloved/glovedplugin/summon/soul/trait/SoulTraitDefinition.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/trait/SoulTraitBuilder.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/trait/SoulTraitRegistry.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/trait/SoulTraitHooks.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/trait/SoulTraits.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/trait/*Context*.java` (or equivalent records/classes: outgoing, hit, incoming, crit, ability, preview)
- (Optional helpers) e.g. `TraitProcCooldowns.java` if ICD/generalization is extracted

### Modified files (expected)
- `summon/soul/domain/SoulTrait.java` (replace enum with registry-backed type)
- `summon/soul/domain/SoulInstance.java`
- `summon/soul/domain/SoulGenerator.java`
- `summon/soul/db/SoulRepository.java`
- `summon/combat/SummonCombatService.java`
- `summon/progression/SummonAbilityService.java`
- `summon/runtime/SummonListener.java`
- `summon/soul/service/SoulService.java`
- `summon/soul/item/SoulLoreBuilder.java`
- `summon/soul/table/ui/SoulTableGuideInv.java`
- `core/GlovedPlugin.java` (bootstrap/wiring)
- `summon/vfx/SummonVfx.java` (only if minor helper exposure needed)

### Audit/update as discovered (must compile clean with no stale enum usage)
- `summon/soul/table/SoulTableService.java`
- `summon/soul/table/ui/*` (other soul table views if they reference traits)
- `summon/soul/domain/SoulConfig.java`, `summon/config/SummonConfig.java` (if trait-related)
- Any soul drop/item, spawn, or debug/command paths referencing `SoulTrait`

> Run `rg -n "dev\.gloved\.glovedplugin\.summon\.soul\.domain\.SoulTrait|SoulTrait\." src` during implementation to catch all sites.

---

## 7. Risks, edge cases, and mitigations

- **DB/back-compat regression:** persisted IDs must not change; preserve legacy single-trait string read format; tolerate unknown IDs without load failure.
- **Behavior drift (highest risk):** subtle parity issues in RNG/chance ordering, ICD keys/timing, packbound eligibility/radius/family matching, damage caps/clamps, crit/ability stacking, and VFX trigger conditions. Mitigate by porting logic 1:1 per trait and validating each trait in-game.
- **Init-order bugs:** registry must be ready before deserialization/generation/UI; otherwise null/empty trait resolution. Mitigate with explicit early bootstrap and defensive resolution.
- **Unknown traits in old/new data:** skip safely, log once, never crash rendering/combat.
- **Collection identity changes:** replacing enum changes `==` semantics in code that incorrectly compared by reference; use `equals`/id comparison consistently (registry-backed value object should enforce this).
- **Performance:** hook iteration over small trait lists (max 5) per hit/tick event is negligible; avoid allocations in hot paths (reuse contexts where sensible, but prioritize correctness/readability).

---

## 8. Validation & acceptance criteria

### Build/deploy
- Use `./build.sh` (never Gradle directly) per project rules. Resolve compile errors from enum removal across all modules.

### Functional parity (manual/in-game)
- **BULWARK:** summoned minion takes reduced damage (+10% contribution path) and mitigation VFX triggers as before; total reduction still capped correctly.
- **FRENZIED:** outgoing damage, crit behavior, ability potency effects, hit VFX, and SoulService preview/estimate values match pre-refactor behavior.
- **PACKBOUND:** bonus applies only under same prior conditions (nearby same-family allies, same world/radius/count threshold) and packbound hit VFX triggers only when active.
- **UNSTABLE:** proc chance, ICD (80t), AoE radius/count/damage clamp, exclusions, burst VFX, and kill credit match.
- **VAMPIRIC / HEXING / SPLINTERING:** ability modifiers, proc chances/ratios/caps, and corresponding VFX match current behavior.

### Back-compat
- Existing `souls.db` records load correctly; traits resolve to the same IDs/effects.
- Legacy stored formats (single trait string, older rows/items if applicable) still deserialize.

### Auto-registration (“no searching around”)
- `SoulTableGuideInv` traits page lists all traits from `SoulTraits`/registry with shared/default icon and definition descriptions (no switch edits).
- `SoulGenerator` roll pool and `SoulLoreBuilder` display derive from definitions/registry.
- Smoke test: temporarily add a test trait in `SoulTraits` (unique ID, description, no-op hooks) -> it appears in guide/lore eligibility/pool paths as expected; remove test trait.

### Regression checks
- Summon attack pipeline, cooldowns, protection/ownership rules, kill credit, and non-trait combat behavior unchanged.
- No new errors/spam in debug/server logs during normal summon combat and soul UI usage.

---

## 9. Definition-of-done for “add a new trait” (post-refactor)

1. Add a built `SoulTraitDefinition` in `summon.soul.trait.SoulTraits` (id, display name, guide description, hook behavior).
2. (Only if needed) add a new reusable `SummonVfx` preset for a novel visual.
3. No edits required in: `SoulGenerator` pool, `SoulRepository` ID mapping, `SoulTableGuideInv` trait switch/page wiring, `SoulLoreBuilder` naming, or scattered `if (hasTrait(...))` checks in combat/ability/listener/service — these are registry/hook-driven.

This is the target state and the acceptance bar for this refactor.
