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
- [x] EliteMobs procedural gear mix – real API integration for scaling to combat level
- [x] Protection listeners: break double-confirm logic
- [x] Auto-save task async DB writes
- [x] Admin commands full implementation
- [x] Config reload handling
- [x] World disable list & bypass permission handling
- [x] Disabled worlds & container type filtering
- [x] Sound effects on open/close

## Remaining
- [ ] Tests for DB operations
- [ ] Documentation and README updates
- [ ] Optimization for large chest counts
