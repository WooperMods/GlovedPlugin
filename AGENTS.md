# GlovedPlugin – Agent Notes

## Build & Deploy
- Never run Gradle directly. Always use `./build.sh` unless the script is broken. It sets `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`, runs `./gradlew clean shadowJar --no-daemon`, and copies `build/libs/GlovedPlugin.jar` to `~/Downloads` and `/home/crafty/crafty-4/servers/9db363c7-b2ff-47b6-b334-c647675017c9/plugins`.
- Jar name is fixed to `GlovedPlugin.jar` (no version). `processResources` expands `version` into `paper-plugin.yml`.
- Shadow jar bundles implementation deps; `jar` task outputs `GlovedPlugin-plain.jar`. Never deploy the plain jar.
- Paper API 26.3+, `compileOnly io.papermc.paper:paper-api:26.3.build.+`, Java 25 toolchain, Gradle 8.14.4 wrapper.
- Release: `bun scripts/release.ts [major|minor|patch|X.Y.Z]` – bumps version, changelog, tag, GitHub release.

## Structure
- Entry: `core/GlovedPlugin.java` (`dev.gloved.glovedplugin.core.GlovedPlugin`)
- Menu: `menu/config/MenuConfig.java` – centered layout engine; `menu/inventory/MenuInv.java`; `menu/command/MenuCommand.java`, `menu/command/MenuOpenService.java`; `menu/listener/MenuHandler.java`; `menu/editor/*`
- Commands: `commands/GlovedRootCommand.java` (`/gloved`), `commands/gloved/GlovedSoulSubcommandHandler.java`, `menu/command/MenuCommand.java` (`/menu`), `lootr/command/LootrCommand.java` (`/lootr`)
- Combat: `combat/` package. Runtime config `combat.yml`. `combat/config/WeaponRegistry.java`, `combat/runtime/CombatListener.java`, `combat/targeting/ArcHitbox.java`
- Lootr: `lootr/` package. Runtime config `lootr.yml`. `lootr/LootrModule.java` (runtime facade, particle + orphan-cleanup scan via `lootr/task/*`), `lootr/repository/ContainerRegistry.java`, `lootr/service/PlayerLootService.java` (SQLite `lootr.db`), `lootr/listener/ChestOpenListener.java`, `lootr/integration/EliteMobsIntegration.java` (reflection)
- Summon: `summon/` package. Runtime config `summon.yml`. `summon/config/{SummonConfig,SummonProfile}.java`, `summon/runtime/{SummonManager,SummonEnchant,SummonListener,SummonInstanceTracker,DamageProxy}.java`, `summon/ai/legacy/SummonAI.java`, `summon/spawn/*`, `summon/nms/api/*`, `summon/nms/v26p3/*`, `summon/soul/{domain,service,db,item,ui,table,trap}/*`
- Config: `config.yml` – menu items with `anchor`/`offset-x`/`offset-y`/`slot`, opener item. `combat.yml` – weapon presets, `spare_tamed`, `spare_named`, `min_attack_strength`, `adventure_mode_while_armed`, `sneak_bypass`

## Commands
- `/menu` or `/gloved menu` – open GUI
- `/gloved reload` – reload `config.yml` + refresh opener
- `/gloved reset` – restore default config
- `/gloved edit` – in-game menu editor, requires `gloved.edit`
- `/gloved debug [on|off]` – toggles file logging to `plugins/GlovedPlugin/debug.log`
- `/gloved soul table give [player] [amount]` – give a Soul Table station item (`gloved.soul.table.give`)
- `/gloved soul table list [page]` – list placed Soul Tables + owner/coords
- `/gloved soul table reload` – reload `souls.table`/`souls.stats`, re-register recipe, regenerate + redeploy the resource pack
- `/lootr generate [normal|elite|blank]` – place + register chest; `elite` forces elite, `blank` registers with empty loot table (no vanilla fill)

## Debug logging
- `GlovedPlugin.logDebug` only emits when `/gloved debug` is on. Combat `[BetterCombat]` logs and `DIAG*` menu logs are gated behind this.
- `DebugLog.isEnabled()` controls both console and file output. Plugin lifecycle logs via `logInfo` remain always on.

## Combat quirks
- Custom arc system replaces vanilla melee for weapons. Do not rely on vanilla attack events.
- `spare_tamed` skips `Tameable.isTamed()` entities
- `spare_named` skips mobs with `customName()!=null && isPersistent()` – name-tag heuristic
- Combat fully disabled while sneaking: `onPreAttack`, `onSwing`, `onModdedSwing`, `applyReach` all early-return on `player.isSneaking()`
- Reach applied via `ENTITY_INTERACTION_RANGE` attribute modifier `weapon_reach`
- Adventure mode while armed is silent; sneak bypass restores survival

## Lootr quirks
- Orphan cleanup treats **any** `Container` block as present. Never require a loot table there: `/lootr generate` chests have none, and Lootr clears the loot table on takeover. Requiring one unregisters live chests and cascade-deletes `player_loot` (losing `elite_flag`).
- `ContainerRegistry.register` is an upsert that preserves `is_elite`. Never bare `INSERT OR IGNORE` — a pre-existing row silently keeps the stale loot table (`lootTableKey=null` → empty chests). Admin commands use `upsert`.
- Elite is both per-player (`player_loot.elite_flag`, locked on first open) and container-wide (`containers.is_elite`, set by `/lootr generate elite`). Either one marks the chest elite.
- Elite chest = EliteMobs gear only (no vanilla fill). Regular chests get vanilla fill plus elite injection via `elite.regular-chance` (sequential coin-toss per item, capped at `regular-max-items`).
- Combat level via `CombatLevelCalculator.calculateCombatLevel(UUID)` reflection (+ PlaceholderAPI fallback). Loot via `LootTables.generateItemStack(level, Player, null)`. Luck source is `Attribute.LUCK` only.
- `/lootr reset` preserves `elite_flag` (UPDATE, not DELETE).
- Paper API 26.3 LootContext builder has `.killer(player)` and `.luck(float)` — `.withPlayer`/`.withRandom`/`.withLuck` do not exist. There is no `World.getChunkIfLoaded(int,int)` — use `getChunkAt` + `isLoaded()`.

## Summon quirks
- Soul casting (Soul Focus / selected soul) is **soulId-exclusive**: only one active summoned soul group per owner at a time (tied to the specific `soulId`, not mob type/family). Casting the same active soul recalls its group (toggle, no mana/cooldown). Casting a different soul while any group is active performs a hotswap (recalls the active group, then casts the selected soul if cast checks pass). A cast spawns the full group at once up to that soul's resolved max-active cap. Spawned minions are tagged with `summon_soul_id`.
- Cast cooldown is configurable via `souls.cast.*` but **disabled by default** (`base-cooldown-ticks: 0`, `shared-gcd-ticks: 0`); when enabled, cooldowns apply to casts (not recalls). The selected-soul action bar shows cooldown/recall/mana readiness but no active soul cap indicator.
- Detection is lore-based and strict: a profile triggers only when any of its item's lore lines (legacy `&`/`§` formatting stripped, case-insensitive) contains that profile's configured `enchant-name`. No PDC-key fallback. The resolved profile key is cached in PDC `gloved:summon_profile` and revalidated against loaded config.
- `SummonProfile` config fields: `mob-types` list (random per spawn), `enchant-name` (required; name to match in lore, formatting stripped), `chance`, `minions-per-hit` (batch size per trigger, migrated from the old `minions` key), `max-active`, `nametag-color`. Hardcoded in `SummonProfile`: invulnerable (`true`), cooldown (`5` ticks), lifetime (`600` ticks), follow range (`16`), nametag format (`{player}'s {mob}`), nametag visible (`true`), all equipment slots valid. Any tameable minion is always tamed and owned by the summoner. Attack range uses the vanilla melee reach formula `(mobWidth * 2)^2 + targetWidth` plus a 2-block bonus. Minion hits manually apply knockback (`0.4` base plus the minion's `ATTACK_KNOCKBACK` attribute) pushing the target away from the owner. `LivingEntity#knockback(strength, x, z)` subtracts `(x, z)`, so the vector must point victim -> source. On spawn, `SummonManager` removes all vanilla goals and adds `SummonMoveGoal` (movement-only, Paper `Pathfinder.moveTo`) to avoid mob quirks (e.g. bee pollination) while keeping obstacle-aware pathing. `MinionAI` handles target selection/coordinator, sets `mob.setTarget`, owner-credited combat, and stuck/teleport recovery. `ai`, `damage-proxy`, `tamed`, and `friendly` config fields were removed. `DamageProxy` cancels non-player-credited minion melee events.
- `max-active` is per-profile/type (counted by the `summon_profile` PDC key, dead minions pruned). Soul casts use exclusive full-group spawning (`spawnSoulGroupToCap`); legacy/trigger flows may still use `canSummon`/`spawnFromSoul` batch behavior and caps. `summon.yml` is reloaded in place on `/gloved reload` so listeners/managers keep working with the new values.
- On a successful hit, a minion has `chance * 0.25` to trigger the same spawn flow for its own profile (same cooldown and `max-active` rules as player-triggered summons), targeting the entity it hit.
- On plugin enable and `summon.yml` reload, GlovedPlugin auto-generates missing EliteMobs enchantment marker files (`EliteMobs/enchantments/<profile>.yml` + `.lua`) for each configured profile, auto-detecting `plugins/EliteMobs`. Existing files are never overwritten. EliteMobs may need `/em reload` to register newly created enchantments.
- `SummonMoveGoal` uses Paper `Pathfinder.moveTo` for obstacle-aware movement toward `mob.getTarget()` or the owner. `ReachabilityService` uses vanilla `Pathfinder.findPath().canReachFinalPoint()` for reachability checks (Paper-only).
- Unreachable enemies are cached per minion with 10 s TTL and location invalidation: cache is cleared if target moves. Heuristic pre-filters drop candidates with vertical delta >8 blocks, distance >64², or unreachable via probe.
- Target stuck detection drops target after 60 ticks of no progress; unreachable probe also caches target immediately.
- Follow uses stable anchor with idle radius ~4 blocks. Stuck follow detection only runs outside idle radius; within idle radius stuck timer is reset to avoid teleport spam.
- Teleport near player preserves Y to work underground; small random XZ offset avoids solid blocks.
- Target candidates filtered to `profile.followRange` of owner. Sticky target for 200 ticks. Stable distribution per minion via `minion.getUniqueId().hashCode() % candidates.size()`. Protection override pulls far minions >6 blocks away to closest threat within 10 blocks of owner.
- Damage proxy redirects melee and projectile damage to player-credited damage for enchants/loot. `forceFullAttackStrength` sets attack cooldown to full.
- Minions cleaned up on plugin disable via `SummonInstanceTracker.removeAll()`.
- No code change needed to add new summons; add profile to `summon.yml` and EliteMobs enchant with matching lore.

## Soul Table quirks
- A Soul Table is a real `SMITHING_TABLE` block plus a persistent full-block `ItemDisplay` overlay; only positions in `soul_tables` get the overlay/GUI. Ordinary Smithing Tables keep vanilla behaviour.
- Overlay uses `ItemDisplayTransform.NONE`, scale `1.002`, at the block centre (`SoulTableManager.COVER_SCALE`) so the `minecraft:block/cube_all` model covers the block without z-fighting. The overlay PDC key `gloved:soul_table_overlay` stores the table UUID for orphan/duplicate sweeps.
- `soul_tables` uses a unique `(world,x,y,z)` index. `SoulTableManager.register` deletes a stale row at the same position first, then inserts. Break = delete DB row first, then remove overlay; if the delete fails the break is cancelled. `BlockBreakEvent#setDropItems(false)` suppresses the vanilla Smithing Table drop and a tagged `SoulTableItem` is dropped instead.
- Access: use is public (`souls.table.use-public`); breaking is owner-only when `souls.table.break-owner-only` is true, unless the player has `gloved.soul.table.break`.
- `SoulTableInv` (54 slots) holds its own `Inventory` and rebuilds it in place on every refresh, so tab/page/selection state survives clicks. It is routed from `MenuHandler` alongside `SoulVaultInv`; drags into the top inventory are cancelled.
- GUI actions: Combine (exactly 3 same-family + same-rarity, next rarity only; Mythic rejected), Enhance (1 soul, max level 10), Reforge (1 soul, rerolls affixes at its rarity, preserves family/rarity/base stats/level), Bulk Salvage (any number, no favourite/equipped filter; first Confirm arms for `costs.salvage.confirmation-seconds`, second converts).
- Costs live in `souls.table.costs.*` and essence values in `souls.essence.values.*` (overflow uses `souls.essence.overflow-multiplier`). Defaults (backfilled, existing keys preserved): combine 8/20/55/150/425; enhance `base * (L + offset)^exponent` with base 8.0/exponent 1.8/offset 1/min-cost 1/max-cost 0 (L9→10 = 327); reforge 12/20/40/80/150/275; salvage/overflow essence 1/3/8/18/42/95 (Common→Mythic). Salvage free.
- All mutations run inside `SoulDatabase.transaction(...)`: ownership, family/rarity, level and Essence are re-validated there, and a failure rolls back both souls and Essence. `SoulTableService` is the only writer; never trust the GUI.
- Soul stats are a `SoulBaseStats` record (health, defense/DEF, attack damage, movement speed, attack speed). Base rolls are sampled from auto-partitioned absolute rarity bands driven by `souls.stats.bounds.*`/`souls.stats.ranges.*` (legacy `souls.stats.rarity-multipliers`/`variance` are deprecated and ignored); only Common can roll the global floor and only Mythic the global max, and base/rolled stats are rarity-ceilinged so non-Mythic cannot reach the global max. Per-stat `souls.stats.ranges.split.<stat>.*` and `souls.stats.ranges.roll-skew.*` can shape band width and roll bias (HP defaults to smaller low-rarity bands/wider high-rarity bands with high-end rolls harder to hit). DEF is a soul armor stat (default 1.0–13.75) applied to `Attribute.ARMOR` (toughness 0), providing up to ~55% base armor damage negation at the base cap; armor toughness, knockback resistance and attack knockback remain non-soul/vanilla attributes. `Attribute.FOLLOW_RANGE` is **never** captured, rolled, enhanced, reforged, displayed or written — follow range stays the fixed `SummonProfile.FOLLOW_RANGE = 16` AI limit.
- Enhancement adds `+2.5%` per level to every base stat (config `costs.enhance.per-level-bonus`) and only applies on the next summon; live minions are not refreshed. Enhancement can raise stats above rarity/global/base caps (including DEF above its 13.75 base cap). `SummonManager.applySoulSnapshotToMinion` applies enhanced base stats (including DEF/armor) × affix multipliers; `computeSpawnedMaxHealth` mirrors it for lore previews.
- Legacy souls are backfilled/migrated idempotently on enable / first load: missing `base_stats_json` rows are rolled into current rarity bands (including DEF), and stored base-stat payloads are normalized/migrated (e.g. missing/legacy DEF) without rewriting unchanged rows. The pass only touches rows that need migration/backfill.
- `SoulDropItem` round-trips `base_stats` + `enhance_level` in PDC; legacy items fall back to entity defaults.
- Resource pack is generated procedurally (deterministic seed) to `plugins/GlovedPlugin/soultable/SoulTable.zip`, copied to `plugins/ResourcePackManager/mixer/SoulTable.zip` when RSPM exists, then `rspm reload` runs. Pack format matches `RankGlyphService` (`min_format [97,1]`, `max_format 97`). Without RSPM/players without the pack, the station still works but looks like a vanilla Smithing Table.
- Recipe is shaped (`SSS/PLP/ONO` by default) and keyed `gloved:soul_table`; `Bukkit.removeRecipe` runs before every registration so `/gloved reload` cannot duplicate it.
- Before deploying schema/backfill changes, back up `plugins/GlovedPlugin/souls.db` (+ `-wal`/`-shm`). `base_stats_json`, `enhance_level`, `soul_player_state.soul_table_tab` and `soul_tables` are additive migrations.

## EliteMobs Custom Items – quick guide for summon enchants
- Custom items live in `/home/crafty/crafty-4/servers/9db363c7-b2ff-47b6-b334-c647675017c9/plugins/EliteMobs/customitems/`. Subfolders are allowed; filename including `.yml` must be unique. Relative path is `plugins/EliteMobs/customitems/`.
- Minimal file:
  ```
  isEnabled: true
  material: DIAMOND_SWORD
  name: '&4Bee Swarm Blade'
  lore:
    - '&7Can summon bee_swarm on hit.'
    - '&7EliteMobs custom enchant'
  enchantments:
    - 'elitemobs:bee_swarm,1'
  level: 10
  itemType: custom
  soulbound: true
  ```
- `enchantments` uses namespaced IDs: `elitemobs:<enchant_id>,<level>` for EliteMobs custom enchants, vanilla names e.g. `SHARPNESS,5` also allowed.
- Lore line containing `can summon ... on hit` / `can spawn ... on hit` is what GlovedPlugin parses to detect the summon profile. Profile name is derived from the text between trigger and `on hit`, lowercased and spaces → `_`.
- For unique weapons that should only drop from specific loot tables, set `itemType: unique` and reference the file name in the loot table. `custom` makes it eligible for random elite drops and shops.
- Reload EliteMobs after editing: `/em reload` or server restart.
- Custom enchant definitions live in `/home/crafty/crafty-4/servers/9db363c7-b2ff-47b6-b334-c647675017c9/plugins/EliteMobs/enchantments/`. Default summon enchants are provider-owned YAML+Lua; do not edit core files directly – create a copy with a new ID if you need a variant.

## Conventions
- Conventional Commits enforced for repo commits
- Config values are backfilled on enable/reload; existing keys are never overwritten. To change a `lootr.yml`/`summon.yml` default change all three: `src/main/resources/*.yml`, the fallback in `*Config.load`, and the backfill default in `GlovedPlugin.onEnable` — plus the live server yml for immediate effect.
- `combat.yml` is saved as resource on load
