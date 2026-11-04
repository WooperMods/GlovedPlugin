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

## In Progress
- [ ] Full container auto-registration on chunk load / structure generate
- [ ] Per-player inventory persistence in SQLite with BLOB serialization
- [ ] Vanilla loot table roll per player with proper LootContext
- [ ] EliteMobs procedural gear mix – real API integration for scaling to combat level
- [ ] Protection listeners: break double-confirm, piston, explosion, hopper
- [ ] Particle visual indicators for unopened chests
- [ ] Auto-save task and async DB writes
- [ ] Admin commands: /gloved lootr reload/info/stats/clear/reset/list/convert
- [ ] Config reload handling
- [ ] World disable list and bypass permission handling
- [ ] Disabled worlds and container type filtering
- [ ] Inventory close handling to persist changes back to DB
- [ ] Sound effects on open/close

## Remaining
- [ ] Tests for DB operations
- [ ] Documentation and README updates
- [ ] Optimization for large chest counts
