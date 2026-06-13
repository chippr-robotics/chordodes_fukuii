# Quickstart / Validation Guide: BFS Heal-Walk Performance

How to validate each user story end-to-end. References the contracts in
[contracts/internal-interfaces.md](./contracts/internal-interfaces.md) and the entities in
[data-model.md](./data-model.md). No implementation code here — that lands in `tasks.md` / implementation.

> **Operational guardrail**: do NOT run `sbt testEssential`/`testStandard` while the barad-dûr nodes are
> active (freezes the host). Stop them first, or run targeted suites. Do NOT restart the live production node
> to validate config changes without operator sign-off (FR-026).

## Prerequisites

- Branch off `staging`; `sbt compile-all` green.
- For unit/actor validation: targeted `sbt` test runs (see each section).
- For the skip-on-restart and metrics checks against a real trie: a SNAP-synced Mordor datadir (fast) or a
  disposable ETC node; never the live primary.

## US1 — Skip a completed heal (P1, the dominant win)

**Unit/actor (deterministic, preferred):**
```bash
sbt "testOnly *HealingFrontierResumeSpec *FrontierRebuildSpec"
```
Expect new/updated tests to pass:
- After a verification-complete run over a fully-present trie, `store.isComplete == true` (C2 set on the
  verification path — the actual gap).
- `HealingPivotRefreshed(sameRoot)` leaves `isComplete == true`; `HealingPivotRefreshed(otherRoot)` clears it.
- A complete+marked store on restart logs the skip path (`[HEAL-RESTART] Complete persisted snapshot …`) and
  reaches `StateHealingComplete` **without** the full-state DFS.
- **FR-025 (mandatory)**: a missing node behind a *shared* branch/extension ancestor is still discovered after
  the visited set reports that ancestor seen.
- **Negative (FR-004)**: an incomplete trie (one injected missing node) leaves `isComplete == false`; the idle
  `totalNodesHealed == 0` arm never marks complete.

**Live behavioral check (Mordor/disposable node):** SNAP-sync to a healed state, restart, and confirm the log
shows the complete-snapshot skip (not `falling back to full-state DFS`) and the node reaches regular sync
within minutes (SC-001).

## US2 — Observability (P1)

```bash
sbt "testOnly *GcPressureSampler* *SNAPSyncMetrics* *RocksDbDataSource*"
```
Expect:
- `GcPressureSampler` returns correct `(deltaPauseMs, fraction)` under an injected clock/bean supplier
  (no `Thread.sleep`), including the zero-wall-time guard.
- With `rocksdb.enable-statistics = true`, after N hits + M misses `cacheStats` returns nonzero hit/miss
  tickers moving correctly; with the flag off, `cacheStats == None` and no scrape throws.
- Per-phase nanos accumulators (`queueRead`/`trieRead`/`queueWrite`) are all > 0 after a synthetic walk and
  recorded once per chunk; inflation counters satisfy `childRefsSeen ≥ distinctEnqueued` and the emitted ratio
  equals the quotient.

**Live (scrape):** with statistics enabled, `curl -s localhost:<metrics-port>/metrics | grep -E 'app_snapsync_healing_(phase|gc|inflation)|app_db_rocksdb_block_cache'` shows the new series, and an operator can read off whether a slow walk is cache-, GC-, or disk-bound (SC-003). Inflation ratio < 1.5× on the reference trie (SC-004) — if higher, that is the evidence to tune the cap (US6).

## US3 — Parallelism + pipelining (P2)

```bash
sbt "testOnly *TrieNodeHealingCoordinator* *FrontierRebuild*"
```
Expect:
- Parallelism formula `min(cfg, max(min, nproc − reserved))` clamps correctly across tuples (4-core ⇒ 2;
  16-core ⇒ cfg); `cfg = 1` ⇒ serial, byte-identical output to the pre-change serial path (FR-012).
- With the dedicated reader pool, a level spawning N == pool-size sub-ranges completes (no Await deadlock).
- Pipelined `processSubRange` returns the same frontier set and the same enqueued-children set as the serial
  loop on a fixed synthetic trie; peak buffered chunks ≤ handoff-queue capacity (bounded heap); the
  `awaitFrontierDrain` backpressure (#1338) is still invoked.
- Shared-ancestor completeness (FR-025) holds with sub-ranges running concurrently.

**Live (larger host):** on a host with ≥ 4 cores free and unsaturated disk, measure walk throughput before/after
on the same trie — expect ≥ 2× (SC-002). On the 4-core reference host, expect the pipeline to raise SSD queue
depth above ~1 (the honest, smaller win).

## US4 — Tunable resource limits (P2)

```bash
sbt "testOnly *BfsQueueStorageSpec* *RocksDbDataSource*"
```
Expect a `RocksDbDataSource` opened with raised `maxOpenFiles`/`blockCacheSize` (inline `RocksDbConfig`) to
open without error at the new values (FR-013/FR-014). Verify `db.conf` comments document: raise
`max-open-files` to ≥ the on-disk SST count (`ls <datadir>/<state-cf>/*.sst | wc -l`, ~1591 on the reference
node) or `-1`; raise `block-cache-size` only on a low US2 hit rate; block cache is off-heap and must stay within
the container memory budget (SC-005 — confirm no OOM at defaults on the reference host).

## US5 — Forward-scan queue read (P3)

```bash
sbt "testOnly *BfsQueueStorageSpec*"
```
Expect (extends the existing `withRocksDb` harness):
- `scanRange[from, to)` returns identical `(key, value)` pairs in key order to `multiGetOptimized` over the
  same window (C1/VR-3); half-open boundaries (`[from, from)` empty; `toExclusive` never included); unsigned
  ordering holds for high-byte keys.
- `EphemDataSource.scanRange` parity (namespace isolation, range bounds, sorted output).
- `iterateRange` via `scanRange` equals `iterateRange` via the old path across chunk sizes, including
  chunk-splitting (AS5.1/AS5.2).
- Consuming only the first chunk then dropping the outer iterator closes the DB cleanly — no open-iterator
  error (FR-018/VR-4).

## US6 — Honest visited-set accounting (P3)

- Code review: naming/docs state FIFO/insertion-order eviction and ~120–150B/entry (not "LRU"/80B).
- With the US2 inflation metric, any cap change is justified by the measured ratio and stays within the heap
  budget (no cap raise that risks OOM at 6g) — validated by the SC-004 number, not a guess.

## US7 — Basic-pruning batched read (P3)

```bash
sbt "testOnly *ReferenceCountNodeStorageSpec* *ReadOnlyNodeStorage*"
```
Expect:
- `ReferenceCountNodeStorage.multiGet(keys) == keys.map(get)` element-for-element (incl. `None` for misses),
  resolving to one batched datasource call (C3/FR-021/FR-022).
- `FastSyncNodeStorage` inherits the batched `multiGet` with an identical unwrap.
- Archive vs basic return identical decoded bytes for the same node set (SC-006).

## Full gate before PR

```bash
sbt pp   # compile-all → scalafmt → fast + integration tests
```
Then, for the consensus-adjacent US1 change, the `forge` review + the completeness regression tests (FR-025)
must be green. Record the completeness-marker semantics + Bloom-filter rejection as an ADR under `docs/adr/`.
