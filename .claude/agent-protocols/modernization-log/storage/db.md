# storage/db — RocksDB Persistence Layer

**Package:** `db/`
**Gate:** `vault` on DataSource contract, iterator lifecycle, WriteBatch ordering
**Key files:** `RocksDbDataSource.scala`, `DataSource.scala`, EphemDataSource, batch impls (~50 files)

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Quality Fixes

#### `4907406fe` — H2/H3: StdNode teardown + FileUtils resource leak (8c batch)
- **What:** `StdNode` shutdown sequence missing `.waitForShutdown()`; `FileUtils` unclosed streams
- **Cross-refs:** `node/bootstrap.md` (StdNode), `core/utils.md` (FileUtils)

#### `ef75a5608` — H4 + M1: RocksDB iterator auto-close + bloom filter leak (8c batch)
- **What:** RocksDB iterator `close()` in `finally` blocks; bloom filter option leak plugged

#### `07db4e902` — M4 (by-design, 2026-06-24) — DataSource close protocol documented
- **What:** VAULT audit confirmed `RocksDbDataSource.close()` correctly omits `cache.invalidateAll()` — caches live one tier above in `DefaultStorages`; invalidating from `close()` would invert layering. Scaladoc comment added to `close()`; protocol note in `storage-rocksdb.md`.

---

---

## Scala 3 Idioms

#### `7f9c987cc` — 3d-B: PruningMode → enum
- **What:** `sealed trait PruningMode` + 3 cases (`ArchivePruning`, `BasicPruning(history: Int)`, `InMemoryPruning(history: Int)`) in `db/storage/pruning/package.scala` → Scala 3 `enum`. Added `export PruningMode.{ArchivePruning, BasicPruning, InMemoryPruning}` to preserve existing flat package-level imports across all caller files — zero caller-file changes required. `PruningModeComponent`, `Storages`, and `StoragesComponent` traits compile and behave identically.

---

## Open

- `RocksDbDataSource.scala:298` — `!= null` on RocksDB iterator (Java interop, keep)
- `ClockCache` deprecation warning — library upgrade gate
