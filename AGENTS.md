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
- Config: `config.yml` – menu items with `anchor`/`offset-x`/`offset-y`/`slot`, opener item. `combat.yml` – weapon presets, `spare_tamed`, `spare_named`, `min_attack_strength`, `adventure_mode_while_armed`, `sneak_bypass`

## Commands
- `/menu` or `/gloved menu` – open GUI
- `/gloved reload` – reload `config.yml` + refresh opener
- `/gloved reset` – restore default config
- `/gloved edit` – in-game menu editor, requires `gloved.edit`
- `/gloved debug [on|off]` – toggles file logging to `plugins/GlovedPlugin/debug.log`

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

## Conventions
- Conventional Commits enforced for repo commits
- Config values are backfilled on enable/reload; existing keys are never overwritten
- `combat.yml` is saved as resource on load
