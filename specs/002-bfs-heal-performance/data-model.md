# Phase 1 Data Model: BFS Heal-Walk Performance

This feature adds no new persisted schema beyond reusing the existing healing column families. The "data"
is: the existing walk entities (clarified), new in-memory observability counters, and new configuration
keys. Below: entities, the completeness-marker state machine, the new counters/gauges, and config.

## Existing entities (clarified, behavior unchanged unless noted)

| Entity | Representation | Role | Change |
|--------|----------------|------|--------|
| **State trie node** | content-addressed (32-byte keccak hash) value in CF `n` | Unit the walk reads, classifies (Branch/Extension/Leaf), checks for presence (`None` ⇒ missing). | none |
| **Level queue** | persisted, dense 8-byte big-endian sequential keys in CF `q` (`BfsQueueStorage`) | Holds nodes discovered for the next BFS level; written as parents expand, read back per level. | US5: read via forward `scanRange` instead of batched point lookups (identical content/order) |
| **Visited set** | bounded **insertion-order (FIFO)** in-memory `LinkedHashMap`-backed set, cap `healing-visited-cap` (4M) | Gates whether a child is enqueued; eviction ⇒ bounded re-walk, **never a skip**. | US6: corrected naming/docs (it is FIFO, ~120–150B/entry); cap tuned only on measured inflation |
| **Completeness marker** | 21-byte sentinel key in CF `g` (`HealingFrontierStorage`) | When set, authorizes skipping the full-state walk on restart. | US1: set on the verification-complete path; not cleared on a same-root pivot refresh |
| **Healing frontier** | persisted set of known-missing nodes in CF `g` | Emptiness + marker = complete heal. | none |

## Completeness marker — state transitions (US1)

The marker is the only consensus-adjacent state this feature changes. Invariant:
**`marker set` ⟺ a walk covered the trie AND `isComplete` (pendingTasks ∪ activeRequests empty).**

```
                       ┌─────────────────────────────────────────────────────────┐
                       │                       UNSET                              │
                       │   (restart ⇒ run walk; never skips)                       │
                       └───────────────┬───────────────────────▲──────────────────┘
   verification walk covered trie      │                       │  HealingForceComplete (abandon)
   AND isComplete (zero missing):      │                       │  OR HealingPivotRefreshed(root ≠ current)
     VerificationDFSComplete  ─────────┤                       │  OR FrontierRebuildComplete clears on a
     OR HealingCheckCompletion         │                       │     genuinely new rebuild
       (verificationPassComplete arm)  ▼                       │
                       ┌──────────────────────────────────────┴──────────────────┐
                       │                        SET                               │
                       │   (restart ⇒ skip full-state walk → verification/sync)    │
                       │   HealingPivotRefreshed(root == current) ⇒ NO-OP (stays)  │
                       └──────────────────────────────────────────────────────────┘
```

**Allowed set transitions** (must satisfy the invariant):
- `VerificationDFSComplete` after `isComplete` holds — primary fix.
- `HealingCheckCompletion` **only** on the `verificationPassComplete == true` arm.
- `FrontierRebuildComplete` (existing) — fresh full-state rebuild finished clean.

**Forbidden set transitions** (would violate FR-004 — marking an untraversed trie):
- The `totalNodesHealed == 0` idle arm of `HealingCheckCompletion` (coordinator never walked).
- Any controller-level `StateHealingComplete` handler (also reached from abandonment/idle).

**Clear transitions**:
- `HealingForceComplete` — unconditional (abandonment invalidates the snapshot).
- `HealingPivotRefreshed` — **only** when `newStateRoot != stateRoot` (full 32-byte compare). Same-root ⇒ no-op.

## New in-memory observability counters (US2) — not persisted

Per-walk accumulators in `rebuildFrontierBFS` (alongside `visitedCount`/`frontierCount`):

| Counter | Type | Increment point | Reset | Emitted as |
|---------|------|-----------------|-------|------------|
| `queueReadNanos` | `AtomicLong` | around `queue.iterateRange` chunk fetch (per chunk) | per walk | `setHealingPhaseQueueReadMs` (per level) |
| `trieReadNanos` | `AtomicLong` | around `mptStorage.multiGetNodes` (per chunk) | per walk | `setHealingPhaseTrieReadMs` (per level) |
| `queueWriteNanos` | `AtomicLong` | around `queue.enqueueBatch` (per chunk) | per walk | `setHealingPhaseQueueWriteMs` (per level) |
| `childRefsSeen` | `AtomicLong` | every HashNode child reference, **before** `markIfNew` | per level | numerator of inflation ratio |
| `distinctEnqueued` | `AtomicLong` | inside `markIfNew == true` branch | per level | denominator of inflation ratio |

> Under parallel sub-ranges the three `*Nanos` are aggregate CPU time across readers (label as such; report
> per-level wall time separately).

`GcPressureSampler` (new, `actors/GcPressureSampler.scala`) — pure helper:
- Fields: baseline sum of `GarbageCollectorMXBean.getCollectionTime`, baseline wall-clock (injectable for tests).
- `sample(): (deltaPauseMs: Long, fraction: Double)` — windowed to the walk; zero-wall-time guarded.

`RocksDbDataSource.cacheStats` (US2/FR-005) — `Option[(blockCacheHit, blockCacheMiss, indexFilterHit, indexFilterMiss)]`,
`None` when statistics disabled; backed by a `Statistics` object at `StatsLevel.EXCEPT_DETAILED_TIMERS`.

## New metric series (US2)

All auto-prefixed `app_` via `Metrics.mkName`, landing under `app_snapsync.healing.*` beside the existing
`setHealingRebuildVisited` series:

- `app_snapsync.healing.phase.queue_read_ms`, `…trie_read_ms`, `…queue_write_ms` (per-level gauges, FR-006)
- `app_snapsync.healing.gc.pause_ms`, `…gc.fraction` (windowed GC, FR-007)
- `app_snapsync.healing.inflation_ratio` (per-level enqueued ÷ distinct, FR-008)
- `app_db.rocksdb.block_cache.hit`, `…block_cache.miss`, `…index_filter.hit/miss`, `…table_readers_mem`
  (poll gauges, FR-005, only when statistics enabled)

## New / changed configuration keys

| File | Key | Default | Story | Notes |
|------|-----|---------|-------|-------|
| `sync.conf` | `healing-min-parallelism` | `2` | US3 | floor for effective parallelism |
| `sync.conf` | `healing-reserved-cores` | `2` | US3 | `effective = min(traversal-parallelism, max(min, nproc − reserved))` |
| `db.conf` | `rocksdb.enable-statistics` | `false` | US2 | enables block-cache hit/miss tickers (~1-2% read overhead) |
| `db.conf` | `rocksdb.max-open-files` | `512` (doc) | US4 | document raising to ≥ SST count (1591 ref) or `-1`; already wired |
| `db.conf` | `rocksdb.block-cache-size` | `536870912` (doc) | US4 | document raising only on low hit rate; **off-heap**, counts against cgroup |
| `pekko.conf` | `healing-reader-dispatcher` | new fixed pool (4–6) | US3 | decouples sub-range Futures from the parent's blocking Await |

## New interface (US5)

`DataSource.scanRange(namespace, fromKey, toKeyExclusive): Iterator[(Array[Byte], Array[Byte])]` — forward,
half-open, unsigned-lexicographic order. See [contracts/internal-interfaces.md](./contracts/internal-interfaces.md).

## Validation rules (cross-entity)

- **VR-1 (FR-004/FR-023)**: the marker may be SET only when `isComplete` AND a walk has classified every
  locally-held node; a missing referenced node ⇒ frontier entry ⇒ `isComplete == false` ⇒ marker stays unset.
- **VR-2 (FR-022/SC-006)**: `multiGet` results MUST equal `keys.map(get)` element-for-element in every pruning
  mode (including `None` for misses).
- **VR-3 (FR-016/AS5.1)**: `scanRange` over a dense gapless key window MUST return the same entries, in the
  same order, as `multiGetOptimized` over that window.
- **VR-4 (FR-018)**: a `scanRange` call MUST not leave any native iterator open after it returns or aborts.
- **VR-5 (SC-005)**: no config at documented defaults or operator values within the stated budget pushes the
  node past its container memory limit.
