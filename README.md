# GlovedPlugin

GlovedPlugin is a Paper plugin (`dev.gloved.glovedplugin`) that bundles a configurable GUI menu, custom melee combat, per-player lootr containers, a dynamic soul/summon system with Soul Tables, damage holograms, and a few admin utilities.

- **Plugin:** `GlovedPlugin` `0.1.0`
- **Main:** `dev.gloved.glovedplugin.core.GlovedPlugin`
- **API:** Paper `26.3` (`paper-plugin.yml`, Mojang-mapped runtime via paperweight userdev)
- **Java:** 25 toolchain (configured in Gradle)

## Features

### Menu GUI (`/menu`)
- Configurable chest GUI with centered/anchored layout, per-item material/name/lore (`&` color codes), proxied player commands, filler, optional operator-only items, explicit slot overrides, and a click sound.
- Menu opener item (default chest in slot 17) auto-given/refreshed on join, reload, and enable; hot-reload safe cleanup on disable.
- In-game menu editor (`/gloved edit`): live preview, add/edit/delete items, menu/opener settings, save/discard validation, and disk-cached unsaved edits that survive relog/restart. Editors can also Shift-click buttons in the open menu.

### Combat
- Server-side custom melee/arc combat system ("Better Combat-lite" subset) configured via `combat.yml` (weapon presets/registry, reach/arc behavior, hold-to-spam/combo/dual-wield options, sweep falloff, mining/adventure-mode behavior while armed, sneak bypass, spare rules, PvP bridge toggles, etc.).
- Reach handling via interaction-range attribute modifier; optional per-player Gloved combat PvP toggle (`/gloved pvp`).

### Lootr (per-player loot containers)
- Converts/registers loot containers for per-player loot with configurable container types, protections (break/hopper/explosion/piston, bypass/confirm), particle visuals, custom inventory title/sound, autosave, and world limits.
- EliteMobs integration for elite loot injection/mixing (including elite chest behavior and rare condensed soul essence rolls) controlled by `lootr.yml`.
- Admin tools to inspect, convert, generate, clear/reset, and list tracked containers.

### Summon & Souls
- Dynamic soul system: capture/collect souls from eligible living mobs (entity type/family), with configurable categories/caps/lifetimes/nametags/blacklist and soul trap tiers/items/recipes.
- Player soul management: vault/list/filter/sort, inspect/compare/favorite/salvage, essence economy, soul groups/teams/loadouts/selection/activation (cast/recall), codex/progression hooks, and in-game soul GUIs (vault/teams/codex, Soul Focus item flow).
- Soul Table system: placeable custom station (Smithing Table block + display overlay), owner-aware access/break rules, combine/enhance/reforge/bulk-salvage operations with configurable costs/essence values, transactional persistence in `souls.db`, and procedurally generated/deployed resource pack assets (ResourcePackManager integration when available).
- Summon runtime/progression: NMS-backed summon entities/AI/goals/abilities for many mob types, owner combat stat scaling, progression bridges (including AuraSkills), cooldown/state/respawn handling, VFX, and auto-generation of missing EliteMobs summon-enchant marker files on enable/reload.

### Damage holograms
- AuraSkills-compatible damage hologram formatting/scaling/colors/offsets read live from AuraSkills config where available, with module fallbacks in `damage-holograms.yml`.
- Excludes EliteMobs targets by default and integrates with FancyHolograms as the provider (disable AuraSkills' own damage holograms to avoid duplicates).

### Rank glyphs & utilities
- Rank glyph generation/application for LuckPerms prefixes via ResourcePackManager (`/gloved rankglyphs`).
- EliteMobs enchant/item helpers: apply/remove enchants on held items (`/gloved enchant`) and reset held EliteMobs custom items to config defaults (`/gloved emreset`, where supported).

## Requirements

- **Server:** Paper `26.3+` (modern Paper plugin format; `api-version: '26.3'`)
- **Java:** 25 (Gradle toolchain configured; `build.sh` runs the wrapper with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`)
- **Build:** Gradle wrapper `8.14.4`, `io.papermc.paperweight.userdev` `2.0.0-beta.23`, Shadow plugin
- **Optional integrations (soft dependencies):** LuckPerms, ResourcePackManager, TAB, CMI, AuraSkills, EliteMobs, FancyHolograms, WorldGuard, FreeMinecraftModels, PacketEvents, NpcPlugin-Paper

## Build & Install

Preferred local build:

```bash
./build.sh
```

`build.sh` sets `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`, runs `./gradlew clean shadowJar --no-daemon`, and copies the built jar to `~/Downloads` and the configured local test server plugins directory.

Manual Gradle build:

```bash
./gradlew clean shadowJar --no-daemon
```

Artifacts:
- `build/libs/GlovedPlugin.jar` – shaded deployable jar (**use this one**)
- `build/libs/GlovedPlugin-plain.jar` – plain jar (do not deploy)

Install:
1. Copy `build/libs/GlovedPlugin.jar` into your server's `plugins/` folder.
2. Start/restart the Paper server.
3. Default config resources are saved as needed on first run/enable; missing config values are backfilled without overwriting existing keys.

## Commands & Permissions

### Main commands

| Command | Description | Permission |
|---|---|---|
| `/menu` | Open the menu GUI | — |
| `/gloved` / `/gloved menu` | Open the menu GUI | — |
| `/gloved edit` | Open the in-game menu editor | `gloved.edit` (default: op) |
| `/gloved reload` | Reload plugin/menu/runtime configs and refresh menu opener/soul item for online players | `gloved.reload` (default: op) |
| `/gloved reset` | Restore default `config.yml` and reload | `gloved.reset` (default: op) |
| `/gloved debug [on\|off]` | Toggle debug logging (file: `plugins/GlovedPlugin/debug.log`) | `gloved.debug` (default: op) |
| `/gloved pvp` | Toggle Gloved combat PvP for yourself | — (player-only) |
| `/gloved summon ...` | Summon status/diagnostics utilities | `gloved.summon` |
| `/gloved soul ...` / `/gloved souls ...` | Soul system commands (vault/teams/cast/give/simulate/inspect/codex/traps/table/essence, etc.) | `gloved.soul` (+ subcommand-specific perms) |
| `/gloved enchant <enchant> [level\|remove]` | Apply/remove enchant on held item | `gloved.enchant` |
| `/gloved emreset` | Reset held EliteMobs custom item to config default | `gloved.emreset` (default: op) |
| `/gloved rankglyphs <all\|generate\|apply\|status>` | Generate/apply LuckPerms rank glyph prefixes via RSPM | `gloved.rankglyphs` (default: op) |

### Lootr commands

| Command | Description | Permission |
|---|---|---|
| `/lootr` | Show lootr status/help | — |
| `/lootr info` | Show lootr enabled/tracked info | — |
| `/lootr stats` | Show container/player-loot counts | — |
| `/lootr reload` | Reload `lootr.yml` | `gloved.lootr.admin` |
| `/lootr clear <player>` | Clear a player's lootr data | `gloved.lootr.admin` |
| `/lootr reset [world x y z]` | Reset loot for a container (look target or coords; preserves elite status) | `gloved.lootr.admin` |
| `/lootr convert` | Convert looked-at container to lootr | `gloved.lootr.admin` |
| `/lootr generate [normal\|elite\|blank]` | Give a placeable lootr chest item | `gloved.lootr.admin` |
| `/lootr list` | List registered containers | `gloved.lootr.admin` |

### Soul & Soul Table permissions (selected)

| Permission | Description | Default |
|---|---|---|
| `gloved.soul` | Access `/gloved soul ...` base command | — |
| `gloved.summon` | Access `/gloved summon ...` | — |
| `gloved.enchant` | Use `/gloved enchant` | — |
| `gloved.soul.table.give` | Give/list/reload Soul Table station items and related admin soul table actions | op |
| `gloved.soul.table.break` | Break Soul Tables owned by other players | op |
| `gloved.lootr.admin` | Administrative `/lootr` subcommands | — |

> Exact in-game behavior, argument formats, and tab-completion are defined by the command implementations (see `commands/`, `lootr/command/`, and `summon/soul/` handlers).

## Configuration

Primary runtime config files in `src/main/resources/` (saved to `plugins/GlovedPlugin/` as needed):

- **`config.yml`** – Menu GUI and opener item settings (and may contain additional module defaults depending on build/resource state).
- **`combat.yml`** – Combat system toggles/behavior and weapon presets/registry.
- **`lootr.yml`** – Lootr container tracking, protections, visuals, inventory, EliteMobs loot tuning, condensed essence rolls, storage settings.
- **`summon.yml`** – Dynamic soul/summon configuration (soul traps, categories/caps, nametags, soul stats/costs/table/codex/mastery/progression options, etc.).
- **`damage-holograms.yml`** – Damage hologram provider/module settings and AuraSkills fallbacks.

Other runtime data/assets may include `souls.db`, `lootr.db`, generated Soul Table/UI resource packs, and debug logs under `plugins/GlovedPlugin/`.

## Project Structure

```text
src/main/java/dev/gloved/glovedplugin/
  core/              # plugin bootstrap/entry, logging, shared listeners
  menu/              # menu config, inventory, commands, editor, handlers
  commands/          # /gloved root command and subcommand handlers
  combat/            # combat config/runtime/targeting/fx/db
  lootr/             # lootr module, config/db/model/repository/service/tasks/listeners/integration
  summon/            # summon runtime, AI/NMS entities/goals, progression, VFX
    soul/            # dynamic souls: domain/db/service/items/UI/traps/table/codex/advancement/mastery/traits/guide
  damagehologram/    # AuraSkills-compatible hologram module and providers
  enchant/           # EliteMobs/enchant bridge utilities
  rankglyph/         # LuckPerms rank glyph pack/prefix integration
  ui/                # shared UI/icon/pack helpers
src/main/resources/
  paper-plugin.yml, plugin.yml, config.yml, combat.yml, lootr.yml, summon.yml, damage-holograms.yml, ui/, gloved_souls_datapack/
scripts/
  release.ts         # semver release helper (bun)
build.sh             # preferred local build + copy script
```

## Development

- Prefer `./build.sh` for local builds/deploy copies (see **Build & Install**).
- Paper API and optional integrations are declared in `build.gradle` (`compileOnly` for integrations such as EliteMobs, LuckPerms, AuraSkills, FancyHolograms, WorldGuard, sqlite-jdbc).
- `processResources` expands `version` into `paper-plugin.yml`.
- Release helper: `bun scripts/release.ts [major|minor|patch|X.Y.Z]` (supports options like `--dry-run`, `--no-push`, `--no-release`, `--no-changelog`, `--undo`).

## License

No `LICENSE` file is included in this repository. All rights reserved by default unless stated otherwise.
