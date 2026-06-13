# Internal Interface Contracts: BFS Heal-Walk Performance

This feature exposes no external/public API. Its "contracts" are internal interfaces other components and
tests depend on. Each is a behavioral contract with invariants that the implementation MUST satisfy and tests
MUST assert.

---

## C1 — `DataSource.scanRange` (US5)

```scala
/** Forward range scan over [fromKey, toKeyExclusive) in unsigned-lexicographic key order.
  * Synchronous; the returned Iterator is fully materialized from a bounded window — no native
  * iterator/resource outlives this call (close-on-return; abort-safe).
  */
def scanRange(
    namespace: Namespace,
    fromKey: Array[Byte],
    toKeyExclusive: Array[Byte]
): Iterator[(Array[Byte], Array[Byte])]
```

**Contract**:
- **Ordering**: ascending unsigned-lexicographic by key (`Arrays.compareUnsigned`). Keys with high bytes
  (≥ 0x80) MUST order correctly.
- **Boundaries**: half-open — `fromKey` inclusive, `toKeyExclusive` exclusive. `scanRange(k, k)` ⇒ empty.
- **Namespace isolation**: only entries in `namespace`; other CFs untouched (parity with `deleteRange`).
- **Resource safety (FR-018/VR-4)**: no open RocksDB iterator after return; caller dropping the iterator
  mid-consumption leaks nothing.
- **Equivalence (FR-016/VR-3)**: over a dense, gapless key window, returns the same values, in the same order,
  as `multiGetOptimized` over the enumerated keys (which drops `None`s; a gapless scan has none).
- **Implementations**: `RocksDbDataSource` (native iterator, `scanReadOptions` `fillCache=false`);
  `EphemDataSource` (sorted map-filter fallback) — required so in-memory/test backends work (FR-017).

**Consumer**: `RocksDbBfsQueueStorage.iterateRange` chunks `scanRange` and decodes each value to `BfsEntry`,
preserving the existing outer `Iterator[Seq[BfsEntry]]` shape and O(chunkSize) memory.

---

## C2 — Completeness marker behavior (US1, consensus-adjacent)

`HealingFrontierStorage` API (`markComplete()` / `isComplete` / `clearComplete()`) is unchanged. The
**contract on its callers** in `TrieNodeHealingCoordinator` changes:

**Invariant (VR-1/FR-004/FR-023)**: `isComplete == true` (persisted) ⟺ a frontier walk has classified every
locally-held node reachable from the state root AND no missing node was found (`pendingTasks ∪ activeRequests`
empty at the set-point).

**SET allowed at**:
- `VerificationDFSComplete` (after `verificationPassComplete = true`, before `StateHealingComplete`).
- `HealingCheckCompletion` on the `verificationPassComplete == true` arm only.
- `FrontierRebuildComplete` (existing).

**SET forbidden at**: the `totalNodesHealed == 0` idle arm; any controller `StateHealingComplete` handler.

**CLEAR at**:
- `HealingForceComplete` — always.
- `HealingPivotRefreshed` — only if `newStateRoot != stateRoot` (full 32-byte compare). Same-root ⇒ no-op
  (marker, frontier, pending state all preserved).

**Restart skip (existing gate, PR #1335)**: when `isComplete && frontier empty`, skip the full-state walk and
go straight to verification/sync. This feature only ensures the marker is correctly present/absent.

---

## C3 — Storage `multiGet` batching (US7)

For any `NodesKeyValueStorage` used as healing backing storage, the contract is **batched and exact**:

```scala
override def multiGet(keys: Seq[NodeHash]): Seq[Option[NodeEncoded]]
```

**Contract (FR-021/FR-022/VR-2/SC-006)**:
- Resolves to a single batched datasource call (`multiGetOptimized` → `db.multiGetAsList`), not N point gets.
- Result is **byte-identical** to `keys.map(get)` element-for-element, including `None` for absent keys and
  the same post-read decode (ref-count unwrap for `ReferenceCountNodeStorage`; buffer-shadows-wrapped for
  `ReadOnlyNodeStorage`).
- `ArchiveNodeStorage` already satisfies this (reference behavior); the override is added to
  `ReferenceCountNodeStorage` (inherited by `FastSyncNodeStorage`) and optionally `ReadOnlyNodeStorage`.

---

## C4 — Metric series (US2)

New gauges, `app_`-prefixed, registered via the existing Micrometer patterns. Names and semantics are the
contract dashboards/alerts will key on:

| Series | Kind | Semantics | Reset |
|--------|------|-----------|-------|
| `app_snapsync.healing.phase.queue_read_ms` | gauge | per-level time in queue reads (aggregate CPU when parallel) | per level |
| `app_snapsync.healing.phase.trie_read_ms` | gauge | per-level time in `multiGetNodes` | per level |
| `app_snapsync.healing.phase.queue_write_ms` | gauge | per-level time in `enqueueBatch` | per level |
| `app_snapsync.healing.gc.pause_ms` | gauge | GC pause ms in the walk window | windowed |
| `app_snapsync.healing.gc.fraction` | gauge | GC pause ÷ wall in the window | windowed |
| `app_snapsync.healing.inflation_ratio` | gauge | childRefsSeen ÷ distinctEnqueued (per level) | per level |
| `app_db.rocksdb.block_cache.hit` / `.miss` | gauge (poll) | RocksDB block-cache tickers; absent when statistics off | n/a |
| `app_db.rocksdb.index_filter.hit` / `.miss` | gauge (poll) | index/filter block cache tickers | n/a |

**Contract**: gauges are present and move in the correct direction; cache gauges read `None`/absent when
`rocksdb.enable-statistics = false`; no gauge throws at scrape time.

---

## C5 — Configuration contract

New/clarified keys (see data-model.md for the table). Contract:
- `healing-min-parallelism` / `healing-reserved-cores`: `effective = min(traversal-parallelism, max(min, nproc − reserved))`;
  `traversal-parallelism = 1` ⇒ serial (FR-012). Never exceeds `nproc`.
- `rocksdb.enable-statistics`: default `false`; flipping on enables C4 cache gauges at documented cost.
- `rocksdb.max-open-files` / `block-cache-size`: already wired; documented safe ranges + off-heap memory
  guardrail (FR-015/SC-005). Defaults unchanged.
