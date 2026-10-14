# GlovedPlugin

A Paper (Bukkit) server plugin providing a configurable, centered Chest GUI menu where each icon proxies a server command.

## Features

- **Configurable menu GUI** (`/menu`): Chest inventory (`1–6` rows) with auto-centered layout, per-item `material`, `name`, `lore` (`&` color codes), and proxied `command` (auto-shown as the first lore line, so configs don't repeat it).
- **Flexible positioning**: per-item `anchor` (9 positions) + `offset-x` / `offset-y`, explicit `slot` override, and `enabled` toggle.
- **Operator-only buttons**: per-item `op: true` shows (and runs) only for server operators; everyone else sees filler in that spot, positions unchanged.
- **Menu opener item**: Chest item auto-given in player inventory slot 17 (configurable). Clicking it opens the menu; re-given on join, reload, and plugin enable (hot-reload safe).
- **Admin commands**: `/gloved reload` (reload `config.yml`), `/gloved reset` (restore defaults), `/gloved menu`.
- **Hot-reload safe**: closes ghost menus and clears cursor opener items on disable.
- **Demo handler**: `TorchHandler` logs torch placements.

Default menu (7 items): Warps (`/warps`), Spawn (`/spawn`), Homes (`/homes`), RTP (`/rtp`), Craft (`/craft`), Skills (`/skills`), Ender Chest (`/ender`).

## Requirements

- Paper API `26.3`+ (compiled against `io.papermc.paper:paper-api:26.3.build.+`)
- Java 25 (Gradle toolchain auto-provisions if host JDK is older)
- Gradle 8.14.4 wrapper (`./gradlew`)

## Install

1. Build: `./gradlew clean build --no-daemon`
2. Copy `build/libs/GlovedPlugin.jar` into the server's `plugins/` folder.
3. Restart the server. `config.yml` is created on first run.

## Usage

| Command | Description | Permission |
|---|---|---|
| `/menu` | Open the menu GUI | — |
| `/gloved menu` | Open the menu GUI | — |
| `/gloved reload` | Reload `config.yml` + refresh opener for online players | `gloved.reload` |
| `/gloved reset` | Restore default `config.yml` and reload | `gloved.reset` |
| `/gloved edit` | Open the in-game menu editor | `gloved.edit` |

Clicking a menu icon closes nothing itself — it runs the configured command as the player and plays the configured click sound.

## Menu editor (`/gloved edit`)

Operators (or anyone with `gloved.edit`, e.g. via LuckPerms) can edit the menu in-game — via `/gloved edit`, or by Shift-clicking any button in the open menu (buttons show a hint for editors). It opens a live preview of the menu:

- **Click a button** to edit it (name, lore, command, material all typed in chat; anchor/offset/slot toggles), **Shift-click** to delete (click again to confirm), **click empty space** for controls: add by material name, menu settings (title/size/sound/filler), opener settings, hidden (disabled) buttons, save, discard.
- Text is typed in chat (`&` color codes work, `cancel` aborts, lore takes one line per message with `done`/`clear`, 30s timeout, never broadcast). Type commands exactly as in chat (`//wand` works); a leading `/` is trimmed for commands only, and `//` escapes a literal slash everywhere.
- Save validates (every enabled button needs a command), warns if `config.yml` changed underneath you (save again to overwrite), then writes and reloads. Closing without saving discards everything.
- Unsaved edits are cached to disk — they survive relogs and restarts; reopen `/gloved edit` to resume.

## Configuration (`config.yml`)

Missing options are filled in from the defaults automatically on enable and
on `/gloved reload` (present values are never overwritten). Note: Bukkit
cannot preserve file comments when saving, so the first backfill strips them —
this section is the full reference.

```yaml
menu:
  title: "GlovedMenu"
  rows: 6                        # 1-6
  items-per-row: 5               # 1-9
  sound: ENTITY_EXPERIENCE_ORB_PICKUP  # namespaced key also works: minecraft:entity.experience_orb.pickup
  filler:
    enabled: true                # fill empty menu slots with a decorative pane
    material: BLACK_STAINED_GLASS_PANE
    name: " "                    # blank display name
    lore: []                     # no lore
  items:
    - material: NETHER_STAR
      name: "&b&lWarps"
      lore: ["&a/warps"]
      command: "warps"
      # optional per item:
      # enabled: true            # false hides the item without deleting it
      # op: true                 # only shown to (and clickable by) server operators
      # anchor: bottomright      # topleft, top, topright, left, center, right, bottomleft, bottom, bottomright
      # offset-x: 0              # shift columns (+right / -left), clamped to 0-8
      # offset-y: 0              # shift rows (+down / -up), clamped to menu bounds
      # slot: 13                 # explicit 0-based slot, wins over anchor and auto-center
opener:
  material: CHEST
  slot: 17
  name: "&b&lMenu"
  lore: ["&aClick to Open the Menu!"]
```

- `rows`: `1–6`, `items-per-row`: `1–9`.
- `anchor`: `topleft | top | topright | left | center | right | bottomleft | bottom | bottomright`.
- `slot`: explicit 0-based inventory slot — the only manual positioning, wins over everything.
- `anchor` (default `center`): every other item auto-positions through its anchor — lone items pin to it, shared anchors arrange as a block around it (centered for middle anchors, edge-aligned for side anchors); overflow takes the nearest free slots.
- Layout: explicit `slot` pins first (collision warns); everything else groups by anchor (default `center`) and arranges around it.

## Project Structure

```
src/main/java/dev/gloved/glovedplugin/
  GlovedPlugin.java        # entry point: config load, command registration, enable/disable handling
  MenuConfig.java          # config parsing + centered layout engine
  Menus/MenuInv.java       # InventoryHolder, menu + opener ItemStack builders (PDC-tagged)
  commands/Menu.java       # /menu (Brigadier BasicCommand)
  commands/GlovedCommand.java  # /gloved <menu|reload|reset>
  handlers/MenuHandler.java    # click routing, opener give-on-join
  handlers/TorchHandler.java   # demo: log torch placement
src/main/resources/
  paper-plugin.yml         # plugin entry (name, version, api-version 26.3)
  plugin.yml               # legacy duplicate
  config.yml               # default menu + opener config
scripts/
  build_and_copy.sh        # local build + copy jar to Downloads / test server
  release.ts               # semver release: changelog, version bump, tag, gh release (bun)
.github/workflows/
  build.yaml               # CI: build on push/PR, upload jar
  release.yml              # on v* tag: verify version, build, publish with changelogs/<ver>.md
CHANGELOG.md / changelogs/ # conventional-commit generated notes
```

## Development

```bash
./gradlew clean build --no-daemon
```

- Single `compileOnly` dependency: Paper API. Repos: Maven Central, Papermc, Sonatype, Jitpack.
- `processResources` expands `version` into `paper-plugin.yml`.
- Release: `bun scripts/release.ts [major|minor|patch|X.Y.Z] [--dry-run] [--no-push|--no-release|--no-changelog]`; undo with `--undo`.
- CI runs on `ubuntu-latest` with Temurin JDK 17 (runner) + JDK 25 toolchain.

## License

No license file declared. All rights reserved by default.
