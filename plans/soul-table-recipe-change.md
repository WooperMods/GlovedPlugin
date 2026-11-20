# Soul Table crafting recipe change (vault-extracted COMMON souls + Crafting Table)

## Goal
Change the Soul Table craft so it is:
- **Row 1 (top): 3× “extracted common souls”** = any **COMMON rarity `SoulDropItem`** obtained by extracting from the Soul Vault (the physical soul drop items). These may come from different mobs/materials (heads/spawn eggs/fallbacks).
- **Row 2 middle: 1× Crafting Table** (replaces Soul Torch)
- **All other materials unchanged**: Row 2 ends `COBBLESTONE, COBBLESTONE`; Row 3 `OAK_PLANKS, IRON_INGOT, OAK_PLANKS`.

Requirements:
- Recipe must remain **visible in the vanilla recipe book**.
- In the **Soul Table in-game recipe viewer**, the top row should be clearly labeled as “Common Extracted Soul (from Vault) — any COMMON soul drop (any mob)”, visually represented by mob-head style items (e.g. cycling/representative heads) for clarity.

## Current behavior (code references)
- Runtime recipe registration: `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableRecipeManager.java`
  - Reads from `SoulTableConfig` (`souls.table.recipe.*`), registers `ShapedRecipe` keyed `gloved:soul_table`, removes old recipe on reload, discovers for online players.
- Config/defaults/backfill: `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableConfig.java`
  - Defaults: pattern `["SSS","PLP","ONO"]`, `S=SOUL_SAND, P=COBBLESTONE, L=SOUL_TORCH, O=OAK_PLANKS, N=IRON_INGOT`.
  - Backfill sets these keys in `summon.yml`.
- UI recipe viewer model: `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableRecipe.java`
  - `all()` hardcodes Soul Table grid as Soul Sand/Soul Sand/Soul Sand, Cobblestone/Soul Torch/Cobblestone, Oak Planks/Iron Ingot/Oak Planks.
- UI detail rendering: `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableRecipeDetailInv.java`, grid items via `SoulTableUiItems.recipeGridItem(...)`.
- Soul drop identity/material: `src/main/java/dev/gloved/glovedplugin/summon/soul/item/SoulDropItem.java`
  - `isSoulDrop(plugin, item)`, `readSoul(plugin, item)`, `materialFor(EntityType)` returns heads (`ZOMBIE_HEAD, SKELETON_SKULL, CREEPER_HEAD, WITHER_SKELETON_SKULL, PIGLIN_HEAD, DRAGON_HEAD, PLAYER_HEAD`) or spawn egg fallbacks (`*_SPAWN_EGG`) or `EGG`.

## Design decisions
1. **Vanilla recipe book visibility**: keep a registered Bukkit `ShapedRecipe` (via `RecipeChoice.MaterialChoice` for the top row) so it appears in the recipe book. Enforce the true rules (must be `SoulDropItem` with `rarity == COMMON`) in craft events.
2. **Top row permissive materials (for MaterialChoice)**: include all known possible materials from `SoulDropItem.materialFor` for mobs that can produce heads, plus common fallbacks:
   - `ZOMBIE_HEAD, SKELETON_SKULL, CREEPER_HEAD, WITHER_SKELETON_SKULL, PIGLIN_HEAD, DRAGON_HEAD, PLAYER_HEAD, EGG`
   - Optionally add a small set of common spawn eggs if desired (e.g. `ZOMBIE_SPAWN_EGG`, `SKELETON_SPAWN_EGG`, etc.), but not required. Start with the list above to cover most cases; event validation is the source of truth.
3. **Event enforcement (source of truth)**:
   - On `PrepareItemCraftEvent`: inspect the 3x3 matrix in the crafting view that corresponds to this recipe shape. For the three top-row slots (recipe positions), require:
     - item not null/air and `SoulDropItem.isSoulDrop(plugin, item) == true`
     - `SoulDropItem.readSoul(plugin, item)` not null and `getRarity() == SoulRarity.COMMON`
   - Require other slots match exactly:
     - (row2,col1)=`COBBLESTONE`, (row2,col2)=`CRAFTING_TABLE`, (row2,col3)=`COBBLESTONE`
     - (row3,col1)=`OAK_PLANKS`, (row3,col2)=`IRON_INGOT`, (row3,col3)=`OAK_PLANKS`
   - If all match, set result to `SoulTableItem.create(plugin)`. If the player placed ingredients that look like the permissive recipe but violate rules (e.g. wrong rarity, not a SoulDropItem), set result to `null` to prevent crafting.
   - Optionally also listen to `CraftItemEvent` to double-check and cancel if somehow mismatched (defensive).
4. **UI recipe viewer**: show the recipe as:
   - Top row: three representative “Common Extracted Soul” entries (use `ZOMBIE_HEAD` as visual stand-in) with custom display text in the detail view: name like `§dCommon Extracted Soul`, lore `§7Any COMMON Soul Drop from the Vault (any mob)`.
   - Middle: `CRAFTING_TABLE`.
   - Bottom: `OAK_PLANKS, IRON_INGOT, OAK_PLANKS`.
5. **Config**: update defaults/backfill in `SoulTableConfig` to reflect the new logical recipe keys/pattern for clarity and future reloads (even if enforcement is event-based). Keep keys minimal/clear.

## Implementation steps

### A) Update config/defaults (`SoulTableConfig.java`)
- In the default `recipeIngredients` map (constructor/init) change:
  - remove/replace `L` meaning (no longer used? Or repurpose) — better to define explicitly:
    - Set pattern default to `["SSS","PCP","ONO"]` (or keep letters but change meanings). Alternatively define:
      - `S` = representative for “common extracted soul drop” in config terms is awkward because it’s not a single Material; but easier to:
        - set pattern to `["XXX","PCT","ONO"]`-style is not necessary. Simpler approach:
          - Keep pattern `["SSS","PCP","ONO"]` and map: `S` = one of the head materials used for display/MaterialChoice registration context? Or better: change registration to use hardcoded MaterialChoice for `S` slots in code (don’t rely on config `S` being a single Material). Then update config/backfill to reflect the new intent (comments + values) — e.g. set `souls.table.recipe.ingredients.S` to a note value not used, or repurpose: set `C` (or a new key) for `CRAFTING_TABLE`. 
  - Concrete change:
    - `recipePattern` default: `["SSS","PCP","ONO"]`
    - `recipeIngredients`: put `S` as a placeholder Material not used by registration logic (e.g. `ZOMBIE_HEAD`) for backfill clarity, `P = COBBLESTONE`, `C = CRAFTING_TABLE`, `O = OAK_PLANKS`, `N = IRON_INGOT`. Update `backfill(...)` to write these keys/values.
    - In `reload(...)`, when building ingredients map, still read from config but `SoulTableRecipeManager` will override `S` handling to use `MaterialChoice` for the soul-drop materials.
- Add a short comment in code: “S slots are enforced as COMMON SoulDropItems via PrepareItemCraftEvent; MaterialChoice is used for recipe book visibility.”

### B) Update runtime registration (`SoulTableRecipeManager.java`)
- Replace the current Material-only logic for the 3 `S` slots with `RecipeChoice.MaterialChoice`:
  - Build the choice list: `List.of(Material.ZOMBIE_HEAD, Material.SKELETON_SKULL, Material.CREEPER_HEAD, Material.WITHER_SKELETON_SKULL, Material.PIGLIN_HEAD, Material.DRAGON_HEAD, Material.PLAYER_HEAD, Material.EGG)`.
- For each character in the used set:
  - if `c == 'S'` (top row soul slots): `recipe.setIngredient('S', new RecipeChoice.MaterialChoice(soulDropMaterials))`
  - else if `c == 'C'`: `recipe.setIngredient('C', Material.CRAFTING_TABLE)` (or from config)
  - else use `ingredients.get(c)` as before (`P,O,N`)
- Keep pattern reading/validation same (3x3). Update logging/messages if helpful.
- Ensure imports: `org.bukkit.inventory.RecipeChoice`.

### C) Add craft event enforcement (new listener)
- Create `SoulTableCraftListener` (package `summon.soul.table`), implements `Listener`:
  - Inject `GlovedPlugin`.
  - On `@EventHandler` `PrepareItemCraftEvent event`:
    - Get `CraftingInventory` and its `recipe` (nullable). If recipe is null or recipe result is not the Soul Table item (check with `SoulTableItem.isSoulTable(plugin, result)`), return.
    - Get the 3x3 matrix: `event.getInventory().getMatrix()` (length 9). Map indices to grid positions:
      - index 0..8 correspond to rows 0..2, cols 0..2 in order? (standard crafting grid order in Bukkit API: slots 1..9 in some contexts, but `getMatrix()` returns the 3x3 in row-major order: [ (0,0),(0,1),(0,2),(1,0),(1,1),(1,2),(2,0),(2,1),(2,2) ]).
    - Validate:
      - (0,0),(0,1),(0,2): each must be non-null, non-air, `SoulDropItem.isSoulDrop(plugin, stack) == true`, and `SoulDropItem.readSoul(plugin, stack)` not null with `.getRarity() == SoulRarity.COMMON`.
      - (1,0): `COBBLESTONE`, (1,1): `CRAFTING_TABLE`, (1,2): `COBBLESTONE`
      - (2,0): `OAK_PLANKS`, (2,1): `IRON_INGOT`, (2,2): `OAK_PLANKS`
    - If any fails: set `event.getInventory().setResult(new ItemStack(Material.AIR))`.
    - If all pass: ensure result is `SoulTableItem.create(plugin)`.
  - Optionally on `CraftItemEvent`, if result is Soul Table item, re-validate quickly; if invalid, cancel and set result air (extra safety). Usually not necessary if `PrepareItemCraftEvent` blocks it.
- Register the listener in `GlovedPlugin` (alongside `SoulTableRecipeManager`).

### D) Update UI recipe viewer
- `SoulTableRecipe.java`:
  - Change the `"soul_table"` grid to:
    - `{{Material.ZOMBIE_HEAD, Material.ZOMBIE_HEAD, Material.ZOMBIE_HEAD}, {Material.COBBLESTONE, Material.CRAFTING_TABLE, Material.COBBLESTONE}, {Material.OAK_PLANKS, Material.IRON_INGOT, Material.OAK_PLANKS}}`
  - (Optional) add a helper/static note in comments: these represent COMMON vault-extracted soul drops.
- `SoulTableRecipeDetailInv.java`:
  - In `build()`, when rendering the grid for recipe id `"soul_table"`, for grid indices corresponding to row 0 (first 3 GRID slots: 11,12,13), create a custom display item instead of `recipeGridItem(...)`:
    - Material `Material.ZOMBIE_HEAD` (or cycle in future; start with one)
    - Name: `§dCommon Extracted Soul`
    - Lore: `§7Any COMMON Soul Drop from the Vault`, `§7(any mob type)`
    - Keep it non-interactive display only.
  - For other slots, keep existing behavior.
- `SoulTableUiItems.java` (optional): could add a helper like `recipeGridItemCustom(Material, String name, List<String> lore)`, but inline is fine.

### E) Docs
- Update `AGENTS.md` under “Soul Table quirks” (or relevant section): change the recipe note from “Recipe is shaped (`SSS/PLP/ONO` by default)…” to describe the new rule (3 COMMON vault-extracted SoulDropItems + Crafting Table in middle, others Cobblestone/Oak Planks/Iron Ingot). Mention it’s visible in recipe book via permissive materials + enforced by craft events.

### F) Testing checklist (manual)
- `/gloved reload` (or restart): recipe registers without errors; no duplicate recipe issues.
- Open vanilla recipe book: Soul Table recipe appears and shows the new pattern (with head-like materials in top row, Crafting Table in middle).
- Crafting:
  - Place 3 COMMON `SoulDropItem`s (extracted from vault) in top row, `COBBLESTONE, CRAFTING_TABLE, COBBLESTONE` middle, `OAK_PLANKS, IRON_INGOT, OAK_PLANKS` bottom → result is Soul Table; crafting consumes items correctly.
  - Place 3 NON-COMMON soul drops in top row → result should be blocked (air) by event enforcement.
  - Place random heads/eggs that are not `SoulDropItem`s in top row → blocked.
  - Place wrong materials in other slots → blocked.
- Soul Table recipe viewer (in-game UI): shows the updated grid with clear labels for the top row.
- Edge cases: different head types for the 3 slots (all COMMON) should be allowed.

## Files to touch (expected)
- `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableConfig.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableRecipeManager.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/table/SoulTableCraftListener.java` (new)
- `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableRecipe.java`
- `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableRecipeDetailInv.java`
- `src/main/java/dev/gloved/glovedplugin/core/GlovedPlugin.java` (register new listener)
- `AGENTS.md` (doc note)
- (optional) `src/main/java/dev/gloved/glovedplugin/summon/soul/table/ui/SoulTableUiItems.java` (helper if desired)