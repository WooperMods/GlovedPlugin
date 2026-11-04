# Elite Chests Plan

## Objective
Add per-player random chance for a Lootr chest to become an Elite Chest on first open. Elite Chests contain only reasonably random EliteMobs gear, no vanilla loot. Roll is per-player, locked on first open, slightly affected by luck.
This replaces the current EliteMobs Lootr integration which currently mixes EliteMobs gear into every chest probabilistically via `elitemobsEnabled` and `elitemobsMixVanilla`.

## Requirements
- Per-player one-time random roll, never re-roll on subsequent opens.
- If elite roll succeeds, chest contains EliteMobs gear only, no vanilla loot.
- If roll fails, chest behaves as normal Lootr chest.
- Roll chance configurable, slightly modified by player luck.
- Persistence via player_loot table elite_flag.

## Config
lootr:
  elite:
    enabled: true
    chance: 0.08
    luckMultiplier: 0.02
    minItems: 1
    maxItems: 3
    titleSuffix: "&6[Elite]"

## DB changes
ALTER TABLE player_loot ADD COLUMN elite_flag INTEGER NOT NULL DEFAULT 0;

## Replacement details for existing EliteMobs integration

Current integration:
- `LootrConfig.elitemobsEnabled` boolean
- `LootrConfig.elitemobsMixVanilla` boolean
- In `ChestOpenListener.openPerPlayerInventory`, after vanilla fill, if enabled:
  * 30% chance to add one random Elite leveled Diamond Sword
  * 10% chance per slot to replace item with Elite version
This mixes Elite gear into every chest, always, with no player-specific lock.

New Elite Chests replaces this with:
- No automatic mixing. Vanilla chests are vanilla only.
- Elite gear appears only when the per-player roll succeeds, and then the whole chest is Elite-only.
- Elite roll is one-time per player per chest, stored in `elite_flag`.
- Config knobs for chance and luck influence instead of per-slot random replace.
- Existing `EliteMobsIntegration.applyEliteLevel` can be reused for gear generation, but is only called in the Elite Chest path.
- Old config keys `elitemobsEnabled` and `elitemobsMixVanilla` can be removed or deprecated with migration note.

## Implementation steps
1. Extend LootrConfig with elite settings.
2. Update PlayerLootService with elite_flag getters/setters.
3. Modify ChestOpenListener.openPerPlayerInventory:
   - Load blob, if exists return.
   - Check elite_flag, if set skip roll.
   - Perform roll = baseChance + luck * luckMultiplier.
   - On success: generate EliteMobs gear only, set elite_flag=1, update title. **Do not run existing EliteMobs mix code.**
   - On fail: generate vanilla loot via existing fillInventory, **remove current always-on EliteMobs mixing**. Normal chests remain vanilla only unless future config adds mix back.
4. Remove or deprecate existing `elitemobsEnabled`, `elitemobsMixVanilla`, and the per-open random EliteMobs item injection in `ChestOpenListener`. Elite gear is now exclusively gated by the Elite Chest roll.
5. Add debug logging for roll outcome.
6. Ensure /lootr reset clears elite_flag.

## Testing
- Force chance 1.0 and 0.0.
- Verify per-player different outcomes.
- Verify no re-roll on reopen.
- Verify persistence after server reload.
