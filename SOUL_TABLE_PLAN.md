# Soul Table & Soul Stat System — Implementation Plan

## Objective

Add a fully custom **Soul Table** station that turns Soul Essence into soul progression, plus a
richer per-soul stat system so souls roll varied, rarity-scaled stats and show them in lore.

The table is a real vanilla **Smithing Table** block with a custom full-block `ItemDisplay`
overlay rendered from a GlovedPlugin-generated resource pack. Only registered Soul Tables get the
overlay and the custom GUI; every other Smithing Table on the server is untouched.

---

## Locked decisions

| Area | Decision |
|---|---|
| Block approach | Real `SMITHING_TABLE` + full-block `ItemDisplay` overlay (no Nexo/Oraxen) |
| Overlay art | Procedurally generated texture + model (`BufferedImage`), deployed like `RankGlyphService` |
| Pack delivery | `plugins/GlovedPlugin/soultable/SoulTable.zip` → `plugins/ResourcePackManager/mixer/SoulTable.zip` → `rspm reload` |
| GUI | One 54-slot menu with tab buttons on the top row |
| Features (v1) | Combine, Enhance, Reforge, Bulk Salvage |
| Stat scaling | Rarity multiplier + per-stat random variance + configurable caps |
| Legacy souls | Backfilled on enable / first load |
| Acquisition | 3×3 shaped recipe + `/gloved soul table give` |
| Costs | Medium profile (see below) |
| Live minions | Enhancement does **not** refresh already-summoned minions; applies on next summon |
| Access | Use is public; only owner or admin can break |

### Out of scope for v1

- Trait transfer, codex tab, affix locking on reforge
- Piston/redstone interaction with the table
- Bedrock/Geyser model parity beyond what RSPM already converts
- Cross-server table sync

### Invariants

- **Follow/target range is never a soul stat.** It stays a fixed minion AI limit from
  `SummonProfile` (`followRange = 16` today) and must not be captured, rolled, enhanced, reforged,
  displayed, or written to `Attribute.FOLLOW_RANGE`.
- Only registered Soul Tables get the overlay and custom GUI.

---

## Cost profile (medium)

| Action | Cost |
|---|---|
| Combine → Uncommon | 10 Essence |
| Combine → Rare | 25 Essence |
| Combine → Epic | 75 Essence |
| Combine → Legendary | 200 Essence |
| Combine → Mythic | 500 Essence |
| Combine → above Mythic | not allowed |
| Enhance level `L` → `L+1` | `10 × (L + 1)²` Essence (L = current level, 0-based) |
| Reforge — Common | 20 Essence |
| Reforge — Uncommon | 40 Essence |
| Reforge — Rare | 80 Essence |
| Reforge — Epic | 160 Essence |
| Reforge — Legendary | 320 Essence |
| Reforge — Mythic | 640 Essence |
| Salvage | free (produces Essence) |

Enhance max level: **10**. Per level: **+2.5% base stats**.

Existing salvage values (`SoulService.essenceValue`): Common 1, Uncommon 2, Rare 5, Epic 12,
Legendary 30, Mythic 80.

---

## Soul stat system

### `SoulBaseStats` record

New file: `src/main/java/dev/gloved/glovedplugin/summon/soul/SoulBaseStats.java`

```java
public record SoulBaseStats(
        double health,
        double attackDamage,
        double movementSpeed,
        double armor,
        double armorToughness,
        double knockbackResistance,
        double attackKnockback,
        double attackSpeed
) {
    public static SoulBaseStats fromEntity(LivingEntity entity);   // reads live attribute base values
    public static SoulBaseStats defaults(EntityType type);         // EntityType#getDefaultAttributes()
    public SoulBaseStats withVariance(Random random, SoulRarity rarity, SoulStatConfig cfg);
    public SoulBaseStats enhanced(int enhanceLevel, double perLevelBonus);
    public SoulBaseStats capped(SoulStatConfig cfg);
    public String toJson();
    public static SoulBaseStats fromJson(String json);
}
```

`fromEntity` reads `Attribute` base values via `entity.getAttribute(...).getBaseValue()`, using the
modern Paper attribute names already used in this codebase (`Attribute.MAX_HEALTH`,
`Attribute.MOVEMENT_SPEED`, …):

- `Attribute.MAX_HEALTH`
- `Attribute.ATTACK_DAMAGE`
- `Attribute.MOVEMENT_SPEED`
- `Attribute.ARMOR`
- `Attribute.ARMOR_TOUGHNESS`
- `Attribute.KNOCKBACK_RESISTANCE`
- `Attribute.ATTACK_KNOCKBACK`
- `Attribute.ATTACK_SPEED`

`Attribute.FLYING_SPEED` is intentionally **not** part of `SoulBaseStats`; flying minions keep
their existing handling in `SummonMoveGoal`.

`Attribute.FOLLOW_RANGE` is intentionally **not** captured or scaled. Follow/target range is a
fixed minion AI limit owned by `SummonProfile` (currently `followRange = 16`) and must stay
deterministic for pathing and target-selection behaviour.

Attributes missing on a mob fall back to a neutral default (health/damage/speed from
`EntityType#getDefaultAttributes()` when available, otherwise `0` for defensive stats and
`1.0` for multipliers). All reads are null-safe.

### Rarity multipliers

| Rarity | Multiplier |
|---|---|
| Common | 1.00 |
| Uncommon | 1.05 |
| Rare | 1.12 |
| Epic | 1.20 |
| Legendary | 1.30 |
| Mythic | 1.45 |

### Variance (uniform, per stat, applied once at capture)

| Stat | ± variance |
|---|---|
| Health | 10% |
| Attack damage | 10% |
| Movement speed | 5% |
| Armor | 15% |
| Armor toughness | 15% |
| Knockback resistance | 10% |
| Attack knockback | 10% |
| Attack speed | 10% |

Follow range is never rolled — see the AI-limit note above.

Formula per stat:

```
rolled = clamp(source * rarityMultiplier * (1 + uniform(-variance, +variance)), 0, cap)
```

### Caps (configurable)

| Stat | Cap |
|---|---|
| Health | 200.0 |
| Attack damage | 30.0 |
| Movement speed | 0.5 |
| Armor | 30.0 |
| Armor toughness | 20.0 |
| Knockback resistance | 1.0 |
| Attack knockback | 5.0 |
| Attack speed | 8.0 |

### Effective stats at summon

`SoulStatSnapshot` keeps its current job (affix multipliers). Base stats are applied separately,
then combined:

```
health            = base.health            * (1 + VIGOROUS)   * enhance
attackDamage      = base.attackDamage      * (1 + BRUTAL)     * enhance
movementSpeed     = base.movementSpeed     * (1 + SWIFT)      * enhance
armor             = base.armor             * enhance
armorToughness    = base.armorToughness    * enhance
knockbackResist   = min(1.0, base.kbResist * enhance)
attackKnockback   = base.attackKnockback   * enhance
attackSpeed       = base.attackSpeed       * enhance
enhance           = 1.0 + 0.025 * enhanceLevel
```

Follow and target range are **not** part of this formula. They stay fixed at the `SummonProfile`
AI limit (`followRange = 16` today) so soul stats never widen or shrink minion engagement range.

Owner AuraSkills health bonus is added after the above, exactly as `computeSpawnedMaxHealth`
already does today.

### Backfill of legacy souls

On enable (and lazily on load when `base_stats_json` is null):

1. Source health = stored `max_health` when `> 0`, otherwise `EntityType#getDefaultAttributes()`.
2. Source for every other captured stat = `EntityType#getDefaultAttributes()`. Follow range is
   never backfilled because it is not a soul stat.
3. Apply **variance only** for health (elite mobs already inflate `max_health`; applying the
   rarity multiplier would double-dip), and rarity multiplier + variance for the rest.
4. Apply caps.
5. Persist via a single `UPDATE ... WHERE base_stats_json IS NULL` pass.

Backfill is idempotent and safe to run on every enable.

---

## Data model

### Schema changes — `souls`

```sql
ALTER TABLE souls ADD COLUMN base_stats_json TEXT;
ALTER TABLE souls ADD COLUMN enhance_level INTEGER NOT NULL DEFAULT 0;
```

Guard with `PRAGMA table_info(souls)` exactly like the existing `max_health` migration.

### New table — `soul_tables`

```sql
CREATE TABLE IF NOT EXISTS soul_tables (
    table_id    TEXT PRIMARY KEY,          -- UUID
    world       TEXT NOT NULL,
    x           INTEGER NOT NULL,
    y           INTEGER NOT NULL,
    z           INTEGER NOT NULL,
    owner_uuid  TEXT NOT NULL,
    display_uuid TEXT,                     -- overlay entity UUID, nullable
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_soul_tables_chunk ON soul_tables(world, x, z);
CREATE UNIQUE INDEX IF NOT EXISTS idx_soul_tables_pos ON soul_tables(world, x, y, z);
```

### Transaction handling

The database uses one shared `Connection` (`SoulDatabase`). Add:

```java
public synchronized <T> T transaction(SqlWork<T> work) throws SQLException; // commit/rollback
```

All combine / enhance / reforge / salvage operations run inside this helper so concurrent
players cannot interleave statements mid-transaction. Inputs (souls + Essence) are validated and
consumed in the same transaction that inserts the result soul.

### Physical soul item PDC

`SoulDropItem` already serializes a soul JSON blob. Add:

- `base_stats` — JSON string
- `enhance_level` — integer

Reading falls back to generated defaults when absent (legacy items).

---

## Config (`summon.yml`)

Add under `souls:` (values are the defaults; existing keys are never overwritten):

```yaml
souls:
  table:
    enabled: true
    base-block: SMITHING_TABLE
    use-public: true
    break-owner-only: true
    costs:
      combine:
        UNCOMMON: 10
        RARE: 25
        EPIC: 75
        LEGENDARY: 200
        MYTHIC: 500
      enhance:
        base: 10
        exponent: 2
        max-level: 10
        per-level-bonus: 0.025
      reforge:
        COMMON: 20
        UNCOMMON: 40
        RARE: 80
        EPIC: 160
        LEGENDARY: 320
        MYTHIC: 640
      salvage:
        confirmation-seconds: 5
    recipe:
      enabled: true
      pattern:
        - "SSS"
        - "PLP"
        - "ONO"
      ingredients:
        S: SOUL_SAND
        P: POLISHED_BLACKSTONE
        L: SOUL_LANTERN
        O: OBSIDIAN
        N: NETHERITE_SCRAP

  stats:
    enabled: true
    backfill-legacy: true
    rarity-multipliers:
      COMMON: 1.00
      UNCOMMON: 1.05
      RARE: 1.12
      EPIC: 1.20
      LEGENDARY: 1.30
      MYTHIC: 1.45
    variance:
      health: 0.10
      attack-damage: 0.10
      movement-speed: 0.05
      armor: 0.15
      armor-toughness: 0.15
      knockback-resistance: 0.10
      attack-knockback: 0.10
      attack-speed: 0.10
    caps:
      health: 200.0
      attack-damage: 30.0
      movement-speed: 0.5
      armor: 30.0
      armor-toughness: 20.0
      knockback-resistance: 1.0
      attack-knockback: 5.0
      attack-speed: 8.0
```

Follow range has no config entry here on purpose: it is a fixed `SummonProfile` AI limit, not a
soul stat.

Per repo convention, every default must exist in **three** places:

1. `src/main/resources/summon.yml`
2. the fallback in `SoulConfig.load` / new `SoulTableConfig.load`
3. the backfill defaults in `GlovedPlugin.onEnable`

Plus the live server `summon.yml` for immediate effect.

---

## New files

Package root: `dev.gloved.glovedplugin.summon.soul`

| File | Responsibility |
|---|---|
| `SoulBaseStats.java` | Record, JSON, capture/default reads, variance, caps, enhancement |
| `SoulLoreBuilder.java` | Shared Stats lore block used by vault, drops, table, inspect |
| `table/SoulTableConfig.java` | Parses `souls.table.*` and `souls.stats.*` |
| `table/SoulTableItem.java` | Builds the tagged Soul Table item (`SMITHING_TABLE` + PDC + item model) |
| `table/SoulTableManager.java` | Placement registry, overlay spawn/despawn/rehydrate, persistence |
| `table/SoulTableListener.java` | Place, break, interact, chunk load, GUI suppression |
| `table/SoulTableService.java` | Combine / enhance / reforge / salvage transactions |
| `table/SoulTablePackService.java` | Generates and deploys the resource pack |
| `table/SoulTableRecipeManager.java` | Registers the 3×3 recipe |
| `table/ui/SoulTableInv.java` | 54-slot GUI, tab state, selection state, previews |
| `db/SoulTableRepository.java` | CRUD for `soul_tables`, chunk queries, overlay UUID updates |

## Modified files

| File | Change |
|---|---|
| `SoulInstance.java` | Add `SoulBaseStats baseStats`, `int enhanceLevel`; keep `maxHealth` for back-compat |
| `SoulGenerator.java` | Roll base stats at generation using rarity + variance + caps |
| `SoulService.java` | Capture live stats, expose table helpers, legacy backfill, essence get/spend |
| `SoulRepository.java` | Read/write `base_stats_json`, `enhance_level`; backfill pass; transactional helpers |
| `SoulDatabase.java` | Migrations + `soul_tables` + `transaction(...)` |
| `SoulDropItem.java` | Serialize/deserialize base stats + enhance level |
| `ui/SoulVaultInv.java` | Use `SoulLoreBuilder` for the Stats section |
| `SummonManager.java` | Apply base stats in `applySoulSnapshotToMinion`; update `computeSpawnedMaxHealth` |
| `handlers/MenuHandler.java` | Route `SoulTableInv` clicks (alongside existing vault routing) |
| `GlovedPlugin.java` | Construct/register table manager, listener, recipe manager, pack service; backfill on enable |
| `commands/GlovedCommand.java` | `/gloved soul table ...` subcommands + tab completion |
| `src/main/resources/summon.yml` | New `table` / `stats` sections |
| `AGENTS.md` | Document Soul Table quirks + new config keys |

No new plugin dependency: `ResourcePackManager` is already a soft dependency in
`paper-plugin.yml`.

---

## Soul Table block

### Item

`SoulTableItem.create()` → `ItemStack(Material.SMITHING_TABLE)` with:

- PDC `gloved:soul_table` = `1`
- `ItemMeta#setItemModel(NamespacedKey("gloved", "soul_table"))`
- display name `Soul Table`, lore describing use, `hideComponents` where appropriate

`isSoulTable(ItemStack)` checks the PDC tag (not the item model), so it survives pack removal.

### Placement

`BlockPlaceEvent`:

1. If the item is a Soul Table item and the placed block is `SMITHING_TABLE`:
2. Generate `table_id` (UUID), insert into `soul_tables` with owner + position.
3. Spawn overlay at block center.
4. If the DB insert fails, cancel the event and refund the item.

### Overlay

```java
ItemDisplay display = world.spawn(blockCenter, ItemDisplay.class, d -> {
    d.setItemStack(SoulTableItem.createDisplayStack()); // item model gloved:soul_table
    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
    d.setTransformation(new Transformation(
            new Vector3f(0f, 0.5f, 0f),   // lift so the cube sits flush on the block
            new Quaternionf(),
            new Vector3f(1f, 1f, 1f),
            new Quaternionf()));
    d.setBillboard(Display.Billboard.FIXED);
    d.setPersistent(true);
    d.setInvulnerable(true);
    d.setGravity(false);
    d.setSilent(true);
    d.getPersistentDataContainer().set(OVERLAY_KEY, STRING, tableId);
});
```

Notes:

- Model must be a **full cube** (`minecraft:block/cube_all`) so no vanilla pixels leak.
- Scale/translation are runtime-tuned; verify visually at 1.0 scale and adjust Y only.
- PDC `gloved:soul_table_overlay` stores the `table_id` so duplicates can be detected and
  cleaned up on enable/chunk load.

### Interaction

- `PlayerInteractEvent` (right-click block, `SMITHING_TABLE`, registered position):
  cancel the event and open `SoulTableInv`.
- `InventoryOpenEvent` for `SMITHING_TABLE`: if the location is registered, cancel. Covers
  plugins/commands that open the vanilla GUI directly.
- `PlayerInteractEvent` on the overlay entity is unnecessary — the overlay is non-interactive
  (`setInvulnerable`, no hitbox use); clicks pass through to the block.

### Breaking

`BlockBreakEvent` on a registered position:

- Allow if `player.getUniqueId().equals(owner)` or `player.hasPermission("gloved.soul.table.break")`.
- Otherwise cancel and message the player.
- On success: remove overlay entity, delete DB row, drop `SoulTableItem`, and **do not** drop a
  vanilla Smithing Table (cancel default drop by clearing `event.setDropItems(false)`).

### Persistence / rehydration

- `ChunkLoadEvent`: query `soul_tables` for the chunk, spawn missing overlays.
- `PluginEnable`: load all rows; for loaded chunks spawn overlays; scan loaded worlds for
  `ItemDisplay` entities tagged `gloved:soul_table_overlay` and delete orphans whose `table_id`
  has no row.
- `ChunkUnloadEvent`: overlays are persistent entities and are saved with the chunk; no manual
  handling needed, but clear any in-memory cache entries for that chunk.
- On disable: nothing to clean up (entities are persistent). Never delete overlays on disable.

---

## Soul Table GUI

`SoulTableInv implements InventoryHolder`, 54 slots, title `Soul Table`.

### Layout

```
Row 0  (0-8)   [Combine] [ ] [Enhance] [ ] [Reforge] [ ] [Salvage] [ ] [Info]
Row 1  (9-17)  soul grid
Row 2  (18-26) soul grid
Row 3  (27-35) soul grid
Row 4  (36-44) soul grid
Row 5  (45-53) [Prev] [Sel1] [Sel2] [Sel3] [Essence] [Confirm] [Clear] [ ] [Next]
```

- Soul grid = up to 36 vault souls per page, sorted by rarity → quality → recent.
- Selected souls render with an enchant glint and a `SELECTED` lore line.
- `Info` shows the player's Essence balance, vault usage, and a short usage hint.

### Tab behaviour

**Combine**

- Click up to 3 souls. Each new pick must match the family and rarity of the first pick; invalid
  picks are rejected with an action-bar message.
- `Confirm` enabled only with exactly 3 valid souls and enough Essence.
- On confirm: consume 3 souls + Essence, generate a new soul of the next rarity (same family),
  insert it, refresh GUI.
- Mythic souls cannot be selected; the tab shows "Mythic souls cannot be combined."

**Enhance**

- Select exactly 1 soul.
- `Sel1` shows the soul, `Confirm` lore shows `Level L → L+1`, cost, and the base-stat delta.
- Blocked at level 10 with "Maximum soul level reached."
- On confirm: deduct Essence, increment `enhance_level`, persist.

**Reforge**

- Select exactly 1 soul.
- `Confirm` lore shows current affixes and "affixes will be rerolled".
- On confirm: deduct Essence, reroll affixes using the existing `SoulGenerator` affix logic for
  that rarity, recompute `SoulQuality`, persist. Family, rarity, base stats, and enhance level are
  preserved.

**Salvage**

- Click any number of souls to toggle selection (no family/rarity restriction).
- `Essence` slot shows `Selected N souls → X Essence`.
- First `Confirm` click arms the action for `confirmation-seconds` (default 5) and the button
  turns red with "Click again to confirm".
- Second click within the window: consume all selected souls, add Essence, refresh.
- Timeout or `Clear` disarms.

### Close behaviour

Selection state is per-session and discarded on close. Nothing is consumed until a confirm action
succeeds.

---

## Services (`SoulTableService`)

```java
record TableResult(boolean ok, String message, SoulInstance result) {}

TableResult combine(UUID owner, List<UUID> soulIds, SoulRarity targetRarity);
TableResult enhance(UUID owner, UUID soulId);
TableResult reforge(UUID owner, UUID soulId);
TableResult salvage(UUID owner, List<UUID> soulIds);
int essenceCostCombine(SoulRarity target);
int essenceCostEnhance(int currentLevel);
int essenceCostReforge(SoulRarity rarity);
```

Rules:

- Every method re-validates ownership, vault state, rarity, level, and Essence **inside** the DB
  transaction. The GUI is never trusted.
- Failure messages: `"Not enough Essence"`, `"Soul no longer available"`, `"Mythic souls cannot
  be combined"`, `"Maximum soul level reached"`, `"Souls must share the same family and rarity"`.
- On success, invalidate the in-memory soul cache for the owner (existing cache pattern in
  `SoulService`).

---

## Resource pack

`SoulTablePackService` mirrors `RankGlyphService`:

1. Build in memory, write to `plugins/GlovedPlugin/soultable/SoulTable.zip`.
2. Copy to `plugins/ResourcePackManager/mixer/SoulTable.zip` when the folder exists.
3. Run `rspm reload` via console dispatch when RSPM is present.
4. Log a clear message when RSPM is absent (players see a vanilla Smithing Table but the table
   still functions).

### Pack contents

```
pack.mcmeta
pack.png
assets/gloved/items/soul_table.json
assets/gloved/models/item/soul_table.json
assets/gloved/textures/block/soul_table.png
```

`assets/gloved/items/soul_table.json` (1.21.4+ item definition):

```json
{
  "model": {
    "type": "minecraft:model",
    "model": "gloved:item/soul_table"
  }
}
```

`assets/gloved/models/item/soul_table.json`:

```json
{
  "parent": "minecraft:block/cube_all",
  "textures": { "all": "gloved:block/soul_table" }
}
```

Texture: 16×16 `BufferedImage`, generated deterministically (fixed seed):

- Base: soul-sand / deepslate tones (`#3A2A22`, `#4A352A`)
- Inset rune ring: amethyst (`#8A5BD6`) with lighter highlights (`#B98CFF`)
- Center soul glyph: cyan-white (`#7FE7E0` / `#E8FFFD`)
- 1px darker border to read as a block edge
- `ImageIO.write(png, "png", ...)` to the zip entry

`pack.mcmeta` reuses the exact `pack_format` / `supported_formats` used by `RankGlyphService`
(currently format 97.1) so both generated packs merge cleanly.

---

## Recipe

`SoulTableRecipeManager` registers a `ShapedRecipe` keyed `gloved:soul_table`:

```
SSS
PLP
ONO
```

- S = `SOUL_SAND`
- P = `POLISHED_BLACKSTONE`
- L = `SOUL_LANTERN`
- O = `OBSIDIAN`
- N = `NETHERITE_SCRAP`

Result: `SoulTableItem.create()` (crafting preserves the PDC tag and item model component).
Registered on enable and after `/gloved reload`; removed with `Bukkit.removeRecipe` before
re-registering to avoid duplicates.

---

## Commands

```
/gloved soul table give [player] [amount]   # default: self, 1
/gloved soul table list [page]              # admin: placed tables + owner + coords
/gloved soul table reload                   # regenerate + redeploy pack, reload config
```

Permissions:

- `gloved.soul.table.give` (default op)
- `gloved.soul.table.break` (default op; owners can always break their own)
- `gloved.soul` already gates the `/gloved soul` tree

Tab completion: `give`, `list`, `reload`; player names for `give`.

---

## Implementation phases

### Phase 0 — Safety

- [ ] Back up `plugins/GlovedPlugin/souls.db` plus `-wal` / `-shm` before deploying.
- [ ] Confirm `PRAGMA table_info` guard pattern is reused for all migrations.

### Phase 1 — Data layer

- [ ] `SoulBaseStats` record + JSON + attribute reads + variance/caps/enhance.
- [ ] `SoulDatabase`: `base_stats_json`, `enhance_level`, `soul_tables`, `transaction(...)`.
- [ ] `SoulRepository`: map new columns, upsert preserving `enhance_level`, backfill pass.
- [ ] `SoulTableRepository`: CRUD + chunk query + overlay UUID update.

### Phase 2 — Stat generation & application

- [ ] `SoulGenerator` rolls base stats on generate.
- [ ] `SoulService.tryCapture` passes the victim's live stats into generation.
- [ ] `SoulDropItem` PDC round-trip for base stats + enhance level.
- [ ] `SummonManager.applySoulSnapshotToMinion` applies base × affix × enhancement.
- [ ] `computeSpawnedMaxHealth` mirrors the same math for previews.
- [ ] Backfill on enable.

### Phase 3 — Lore

- [ ] `SoulLoreBuilder` with the Stats block.
- [ ] Wire into `SoulVaultInv`, `SoulDropItem`, `/gloved soul inspect`, and table previews.

### Phase 4 — Block & pack

- [ ] `SoulTableItem`, `SoulTableManager`, `SoulTableListener`.
- [ ] `SoulTablePackService` + procedural texture/model.
- [ ] `SoulTableRecipeManager`.
- [ ] Register everything in `GlovedPlugin`.

### Phase 5 — GUI & services

- [ ] `SoulTableInv` layout, tabs, selection, previews, confirmations.
- [ ] `SoulTableService` combine/enhance/reforge/salvage transactions.
- [ ] `MenuHandler` routing for `SoulTableInv`.
- [ ] `/gloved soul table` commands.

### Phase 6 — Config & docs

- [ ] `summon.yml` sections (resource + fallback + backfill defaults + live server file).
- [ ] `SoulTableConfig` parsing with safe fallbacks.
- [ ] `AGENTS.md` notes: Soul Table quirks, new config keys, migration behaviour.

### Phase 7 — Verification

- [ ] Build with `./build.sh` (never raw Gradle), deploy jar.
- [ ] Run the acceptance tests below.

---

## Acceptance tests

### Block

- [ ] Craft the recipe → item has the custom name and model.
- [ ] `/gloved soul table give` → same item.
- [ ] Place → custom model covers the block; no vanilla pixels visible from any angle.
- [ ] Right-click → Soul Table GUI; vanilla Smithing Table GUI never appears.
- [ ] Place a normal Smithing Table elsewhere → vanilla model and vanilla GUI still work.
- [ ] Break as owner → drops Soul Table item, overlay disappears, no vanilla table drop.
- [ ] Break as non-owner without permission → blocked with message.
- [ ] Break as admin → allowed.
- [ ] Chunk unload + reload → overlay returns exactly once.
- [ ] Full server restart → overlays return, tables still open.
- [ ] Delete the DB row manually → orphan overlay is cleaned up on next enable.

### Combine

- [ ] 3 same family + rarity + Essence → next rarity, family preserved.
- [ ] Mismatched family or rarity → rejected with message.
- [ ] Mythic → rejected.
- [ ] Insufficient Essence → rejected, souls untouched.
- [ ] DB failure simulated → souls and Essence restored (transaction rollback).

### Enhance

- [ ] Level 0 → 1 costs 10, stats +2.5%.
- [ ] Level 9 → 10 costs 1000, blocked afterwards.
- [ ] Summoned minion reflects new stats only after resummon.
- [ ] Insufficient Essence → rejected.

### Reforge

- [ ] Affixes change, family/rarity/base stats/enhance level preserved.
- [ ] Quality recalculated within the rarity band.
- [ ] Insufficient Essence → rejected.

### Salvage

- [ ] Multi-select accumulates correct Essence total.
- [ ] First confirm arms, second confirm within window converts.
- [ ] Waiting past the window disarms.
- [ ] Essence balance increases by the exact total.

### Stats

- [ ] New captures show varied health/damage/speed/armor/toughness/KB resist/attack speed.
- [ ] Rarity visibly raises the baseline.
- [ ] Caps are respected (test with a high-HP elite).
- [ ] Legacy souls are backfilled once and keep the same values on later restarts.
- [ ] Follow/target range is identical for every soul, unchanged by capture, enhance, or reforge,
      and still equals the `SummonProfile` AI limit (`16`).
- [ ] Vault, drop item, and inspect lore all show the same Stats block.
- [ ] Minion spawn applies health/damage/speed/armor correctly (verify with `Attribute` reads).

### Regression

- [ ] Existing soul capture, vault, loadout, cast, and salvage flows still work.
- [ ] `/gloved reload` reloads table + stat config without breaking listeners.
- [ ] `souls.db` integrity: `PRAGMA integrity_check` returns `ok`.

---

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Overlay doesn't fully cover the block | Use a true `cube_all` model; verify from all angles; adjust Y translation only |
| Vanilla pixels leak at edges | Slightly oversize scale (1.001) if needed; test with F3+B off |
| Players without the pack see a Smithing Table | Accepted; functionality is unaffected, log a warning when RSPM is absent |
| Display entity accumulation | One per table; orphan sweep on enable; unique position index prevents duplicates |
| Transaction interleaving on one shared connection | All table mutations go through `SoulDatabase.transaction(...)` |
| Elite mobs inflate health past caps | Caps applied after variance; backfill uses variance-only for health |
| Soul stats widening minion engagement range | `FOLLOW_RANGE` excluded from `SoulBaseStats`; `SummonManager` never touches it |
| Recipe conflicts with other plugins | Distinct shape; `Bukkit.removeRecipe` before re-registering |
| DB migration failure | Back up `souls.db` (+ WAL/SHM) before deploy; migrations are additive |
| Enhancement mid-fight power spike | Documented: applies on next summon only |

---

## Rollback

- Code: revert the commit; the new columns and `soul_tables` table are additive and ignored by
  old code.
- Data: restore `souls.db` from the pre-deploy backup if the backfill must be undone.
- Pack: delete `plugins/GlovedPlugin/soultable/SoulTable.zip` and
  `plugins/ResourcePackManager/mixer/SoulTable.zip`, then `rspm reload`.
