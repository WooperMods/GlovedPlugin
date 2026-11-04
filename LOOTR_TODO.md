# Lootr Module TODO

## Completed
- [x] Add dependencies: EliteMobs compileOnly, SQLite JDBC
- [x] Update plugin.yml softdepend and permissions
- [x] Add lootr config section defaults
- [x] Create LootrConfig loader
- [x] Create SQLite DB schema
- [x] Create LootrManager skeleton
- [x] Create ContainerRegistry
- [x] Basic container detection on interact
- [x] Basic per-player inventory open placeholder
- [x] Integrate module into GlovedPlugin onEnable/onDisable
- [x] Per-player inventory persistence in SQLite with BLOB serialization
- [x] InventorySerializer using BukkitObjectStreams
- [x] PlayerLootService save/load
- [x] Inventory close handling to persist changes back to DB
- [x] Particle visual indicators on open
- [x] Auto-save task stub
- [x] Protection listeners scaffold for break/explosion/piston/hopper
- [x] Auto-registration on chunk load

## In Progress
- [ ] EliteMobs procedural gear mix – real API integration for scaling to combat level
- [ ] Protection listeners: break double-confirm, piston, explosion, hopper with registry check
- [ ] Auto-save task and async DB writes
- [ ] Admin commands: /gloved lootr reload/info/stats/clear/reset/list/convert
- [ ] Config reload handling
- [ ] World disable list and bypass permission handling
- [ ] Disabled worlds and container type filtering
- [ ] Sound effects on open/close

## Remaining
- [ ] Tests for DB operations
- [ ] Documentation and README updates
- [ ] Optimization for large chest counts
