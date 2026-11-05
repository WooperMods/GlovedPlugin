# GlovedPlugin – Agent Notes

## Build & Deploy
- Build: `./gradlew clean build --no-daemon`
- Dev shortcut: `./build.sh` – sets `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`, runs Gradle, copies `build/libs/GlovedPlugin.jar` to `~/Downloads` and to `/home/crafty/crafty-4/servers/9db363c7-b2ff-47b6-b334-c647675017c9/plugins`
- Jar name is fixed to `GlovedPlugin.jar` (no version). `processResources` expands `version` into `paper-plugin.yml`
- Paper API 26.3+, `compileOnly io.papermc.paper:paper-api:26.3.build.+`, Java 25 toolchain, Gradle 8.14.4 wrapper
- Release: `bun scripts/release.ts [major|minor|patch|X.Y.Z]` – bumps version, changelog, tag, GitHub release

## Structure
- Entry: `GlovedPlugin.java`
- Menu: `MenuConfig.java` – centered layout engine; `Menus/MenuInv.java`; `commands/GlovedCommand.java`; `handlers/MenuHandler.java`
- Combat: `combat/` package. Runtime config `combat.yml`. Arc hit detection in `ArcHitbox.java`, listener in `CombatListener.java`, registry in `WeaponRegistry.java`
- Lootr: `lootr/` package. Runtime config `lootr.yml`. `LootrManager` (particle + orphan-cleanup scan), `ContainerRegistry`, `PlayerLootService` (SQLite `lootr.db`), `ChestOpenListener`, `EliteMobsIntegration` (reflection)
- Config: `config.yml` – menu items with `anchor`/`offset-x`/`offset-y`/`slot`, opener item. `combat.yml` – weapon presets, `spare_tamed`, `spare_named`, `min_attack_strength`, `adventure_mode_while_armed`, `sneak_bypass`

## Commands
- `/menu` or `/gloved menu` – open GUI
- `/gloved reload` – reload `config.yml` + refresh opener
- `/gloved reset` – restore default config
- `/gloved edit` – in-game menu editor, requires `gloved.edit`
- `/gloved debug [on|off]` – toggles file logging to `plugins/GlovedPlugin/debug.log`
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

## Conventions
- Conventional Commits enforced for repo commits
- Config values are backfilled on enable/reload; existing keys are never overwritten. To change a `lootr.yml` default change all three: `src/main/resources/lootr.yml`, the fallback in `LootrConfig.load`, and the backfill default in `GlovedPlugin.onEnable` — plus the live server yml for immediate effect.
- `combat.yml` is saved as resource on load
