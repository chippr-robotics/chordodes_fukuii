# Phase 0 Research: BFS Heal-Walk Performance

Four design areas, resolved against the actual code (Decision / Rationale / Alternatives). Citations are
`file:line` at the time of research (2026-06-13). The `completeness-marker` area is consensus-adjacent and
was researched via the `forge` agent.

---

## R1 — Completeness marker (US1, P1, consensus-adjacent)

**Decision**: Set the completeness marker on the verification-complete path (the common already-healed
case), and stop a no-op pivot refresh from clearing it.

1. **Set on verification success.** The `VerificationDFSComplete` handler
   (`TrieNodeHealingCoordinator.scala:674-683`) sets `verificationPassComplete = true` and sends
   `StateHealingComplete` but **never calls `markComplete()`**. Add `healingFrontierStorage.foreach(_.markComplete())`
   there, after `isComplete` holds and before `StateHealingComplete`. Mirror into the
   `HealingCheckCompletion` handler **only** on the `verificationPassComplete` true-branch (line 657) —
   **not** the `totalNodesHealed == 0` idle arm (that arm declares completion without ever walking the
   trie; marking it would let an untraversed trie skip on restart → violates FR-004).
2. **Do not clear on a no-op pivot refresh.** `clearPersistedFrontier()` (`:202-209`) unconditionally
   calls `store.clearComplete()` and is invoked from `HealingForceComplete` (`:558`) and
   `HealingPivotRefreshed` (`:575`). Guard `HealingPivotRefreshed` (`:566`): when `newStateRoot == stateRoot`
   (full 32-byte compare, not the 4-byte log prefix), log a no-op and return without clearing. Keep the
   differing-root path clearing (a different root may invalidate healed subtries — conservative). Keep
   `HealingForceComplete`'s clear unconditional (abandonment genuinely invalidates).
3. **No new locking.** Marker set/clear both run on the actor thread; the verification walk has ended
   (`verificationDFSRunning` cleared) before the set, so there is no walk-thread race.

**Rationale**: `markComplete()` has exactly **one** production caller —
`TrieNodeHealingCoordinator.scala:491`, inside `FrontierRebuildComplete`, which fires only on a fresh
full-state rebuild (`:462`). A node that heals on its first run completes via `VerificationDFSComplete` /
`HealingCheckCompletion`, which write **no** marker. So on the next restart, `StartTrieNodeHealing`
(`:393`) sees the root, finds the persisted frontier empty and `isComplete == false`, and falls to the
full-state DFS (`:444-446`) — the ~17h walk. The skip gate itself is already correct (PR #1335 /
`5ae84d963`, proven by `HealingFrontierResumeSpec:174-184`); the marker is simply never set. Secondary:
the unconditional clear on every `HealingPivotRefreshed` would wipe a freshly-set marker even on a
same-root refresh (frequent on peer-scarce mainnet). **Conservatism (FR-004):** a missing referenced node
produces a frontier entry (`multiGetNodes` → `None` → `HealingEntry`, `:1242-1244`), keeping
`isComplete == false`; thus "marked complete" provably means "walk covered the trie AND found nothing
missing," and the skip never fires when a node could be missing.

**Alternatives rejected**:
- *Set the marker in `SNAPSyncController.StateHealingComplete`* — that message also arrives from the
  abandonment (`HealingForceComplete`) and idle arms, which would mark an unwalked/abandoned trie complete
  (violates FR-004).
- *Reuse the `appStateStorage` `healingValidatedRoot` flag* — set on a different completion route, gates the
  controller's phase transition, not the coordinator's resume gate; couples two state machines.
- *Keep clearing on every refresh and make re-walk cheap* — a "cheap" re-walk over 73M nodes is still hours;
  the unconditional clear is the defeating bug.
- *Per-root completeness key* — cleaner long-term (a new root naturally invalidates), but a larger change to
  the sentinel key layout (`HealingFrontierStorage.scala:66-69`); the same-root guard achieves identical
  safety now. **Recorded as a future refinement.**

---

## R2 — Observability (US2, P1, non-consensus)

**Decision**: Add four surfaces, reusing the existing Micrometer wiring, low-overhead/off-by-default where
they touch hot paths.

- **(A) RocksDB cache hit/miss (FR-005)** — behind a new `db.rocksdb.enable-statistics` flag (**default OFF**).
  When on, attach a `Statistics` (`StatsLevel.EXCEPT_DETAILED_TIMERS`) to the state datasource options;
  expose `cacheStats: Option[(blockCacheHit, blockCacheMiss, indexFilterHit, indexFilterMiss)]` reading
  `TickerType.BLOCK_CACHE_*` plus `getLongProperty("rocksdb.estimate-table-readers-mem")`. Register as
  poll-style closure gauges (`metrics.gauge(name, () => Double)`, the `EngineApiMetrics.scala:22` pattern).
- **(B) Per-phase timing (FR-006)** — three per-walk `AtomicLong` nanosecond accumulators
  (queueRead / trieRead / queueWrite), timed **per chunk** (one `nanoTime` pair per 50K-node chunk, not per
  node), emitted per level as gauges + appended to the `[HEAL-BFS] Level N complete` log.
- **(C) GC pressure (FR-007)** — a small `GcPressureSampler` capturing baseline `getCollectionTime` sum at
  walk start; `sample()` returns `(deltaPauseMs, fraction)` per level. Complements (does not replace) the
  cumulative `jvm_gc_*` Prometheus series, which are not windowed to the walk.
- **(D) Inflation (FR-008)** — two per-level `AtomicLong`s: `childRefsSeen` (incremented at every HashNode
  child reference, **before** `markIfNew`) and `distinctEnqueued` (incremented inside the `markIfNew == true`
  branch); emit `ratio = childRefsSeen / max(1, distinctEnqueued)` per level, reset per level. This is the
  faithful measure of H3 re-walk inflation and feeds SC-004's 1.5× check.

**Rationale**: Two complete Micrometer patterns already exist — the push (`registry.gauge(name, AtomicLong)`
+ setter, `SNAPSyncMetrics.scala:349`) and the poll/closure (`EngineApiMetrics.scala:22`). RocksDB statistics
are genuinely off today (`RocksDbDataSource.scala:419-434` sets no `.setStatistics`), so the only honest way
to surface hit/miss is to attach a `Statistics` object. Per-chunk timing keeps overhead immeasurable (~1.5K
timing calls for the whole walk). The inflation counters are the only faithful numerator/denominator — the
existing `visitedCount`/`queue.counter` count post-gate reads/enqueues, not pre-gate child references.

**Alternatives rejected**: surfacing only `block-cache-usage` properties (reports bytes, not hit/miss);
always-on statistics (per-read overhead on the tight host); per-node timing (perturbs the hot loop);
reusing only cumulative GC series (not windowed); Timers/Summaries (we want per-level last-value gauges that
sum to wall time); deriving inflation from existing counters (measures enqueue volume, not inflation).

**Caveats**: statistics must default OFF (documented ~1-2% cost); under parallel sub-ranges the three phase
accumulators are aggregate CPU time across readers, not wall time — label them as such or report per-level
wall time separately; the GC sampler reports JVM-wide GC during the walk window (acceptable as the walk
dominates), name it accordingly; `cacheStats` must null-guard when the flag is off. Multi-instance metrics
share the static registry (existing limitation; do not fix here).

---

## R3 — Concurrency (US3, P2, non-consensus but concurrency-sensitive)

**Decision**: Lift the parallelism floor and pipeline within each sub-range, leaving the per-level
non-overlap invariant and the synchronized visited gate exactly as they are.

1. **Parallelism floor (FR-009/FR-012).** Replace `min(cfg, max(1, nproc-2))`
   (`TrieNodeHealingCoordinator.scala:1386-1389`) with `min(cfg, max(healingMinParallelism, nproc - healingReservedCores))`,
   new `sync.conf` keys `healing-min-parallelism` (default 2) and `healing-reserved-cores` (default 2). Never
   exceeds `cfg` or `nproc`; `cfg=1` stays serial (preserves the serial baseline, FR-012). On the 4-core host
   this still yields 2 by default — an operator with measured headroom lowers reserved-cores.
2. **Fix the Await-on-same-pool starvation FIRST (blocking prerequisite).** Today the parent walk Future and
   the N sub-range Futures share `healing-writer-dispatcher` and the parent does `Await.result(..., Inf)`
   (`:1340-1342`) — parking a pool thread while waiting for pool threads. With pool size 4 and walk(1)+subranges(2)=3
   it survives by luck; raising parallelism toward pool size deadlocks. **Add a dedicated
   `healing-reader-dispatcher`** (fixed pool sized to the parallelism ceiling) for the sub-range Futures, or
   wrap the Await in `scala.concurrent.blocking { }`. Separate pools preferred.
3. **Pipeline within a sub-range (FR-010/FR-011).** Convert `processSubRange`'s strict
   read→multiGet→decode→enqueue (`:1224-1313`) into a bounded 2-stage producer/consumer: prefetch chunk k+1's
   `mptStorage.multiGetNodes` (the dominant random read) while decoding/expanding chunk k. Bounded handoff
   queue (1-2 chunks, ~4-8MB) keeps heap bounded and preserves backpressure. This raises SSD queue depth above
   the measured ~1 — the only lever that helps the CPU-bound reference host (overlaps disk-stall with decode).
4. **Invariants preserved.** Reads `[levelStart, levelEnd)`, writes children to keys `>= levelEnd` (AtomicLong,
   `BfsQueueStorage.scala:100`); `levelEnd = queue.counter` read only after all sub-ranges join. Non-overlap is
   degree-independent. Visited set stays a single `visitedLru.synchronized` `markIfNew` — correctness is the
   global mutual exclusion, not the parallelism degree.

**Rationale**: The host is CPU/GC-saturated at parallelism 2 with serial chunk processing while the SSD sits
at 50-71% util / QD~1 — the textbook signature of an I/O path that stalls on disk with no pipelining.
Overlapping read and decode raises queue depth without adding threads, so it helps even on 4 cores where more
threads only add GC/context-switch overhead (Amdahl). The parallelism floor is the lever for *larger* hosts
(honest: little gain on the reference host — why US3 is P2 under US1). The starvation fix is non-negotiable: a
latent deadlock the current pool size masks.

**Alternatives rejected**: `.par` (uncontrolled global ForkJoinPool, no config ceiling); raise pool size +
parallelism without decoupling the Await (leaves the deadlock); lock-free/sharded visited set (changes
eviction/de-dup semantics — consensus-sensitive, and the lock is not the bottleneck); larger `BfsChunkSize`
(does not raise queue depth, grows heap); full fs2 rewrite (over-scoped for a P2 change).

---

## R4 — IO & Config (US5/US4/US7, P2/P3, non-consensus)

**Decision**: three independent read-path/config changes.

- **(US5/H1) `scanRange`.** Add a **synchronous** `scanRange(namespace, fromKey, toKeyExclusive): Iterator[(Array[Byte],Array[Byte])]`
  (forward, half-open, unsigned-lexicographic) to the `DataSource` trait. `RocksDbDataSource` implements it via
  `db.newIterator(handles(ns), scanReadOptions)` (reuse the existing `fillCache=false` `scanReadOptions`,
  `:160-165`) + `seek(fromKey)` + iterate-while-`< toKey`, draining the **bounded** chunk into a buffer and
  closing the iterator + releasing the read lock in a `try/finally` (no `return`/`finalize`). Because each call
  opens-drains-closes one bounded chunk, **no native iterator outlives the call** — abort-safe by construction
  (FR-018). `EphemDataSource` overrides it by filtering its in-memory map on namespace + `[from,to)` and
  emitting **sorted** pairs (reuse the `deleteRange` comparator). Rewrite `RocksDbBfsQueueStorage.iterateRange`
  (`BfsQueueStorage.scala:107-121`) to chunk `scanRange` instead of materializing keys + `multiGetOptimized`;
  same outer `Iterator[Seq[BfsEntry]]`, O(chunkSize) memory, **identical order and content** (dense, gapless
  keys). `InMemoryBfsQueueStorage` stays as-is (test default, already identical).
- **(US4) Config — no new plumbing.** `maxOpenFiles` (`RocksDbDataSource.scala:327`) and `blockCacheSize`
  (`:331`) are already wired trait → `InstanceConfig` (`:163`/`:167`) → `createDB` (`setMaxOpenFiles :422`,
  `setBlockCache :414`). **Doc-and-validation only**: update `db.conf` comments to raise `max-open-files` to ≥
  the on-disk SST count (1,591 on the reference node) or `-1` for unlimited, and raise `block-cache-size` only
  when US2 hit/miss metrics show a low hit rate — with an explicit warning that the block cache is **off-heap**
  and counts against the container memory cgroup (FR-015/SC-005).
- **(US7) Basic-pruning batched multiGet.** Healing reads via `getBackingStorage` (archive → batched). On
  *basic* pruning that is `ReferenceCountNodeStorage`, which does not override `multiGet` → inherits
  `keys.map(get)` (`MerklePatriciaTrie.scala:95`) → 50K serial gets. Add
  `override def multiGet(keys) = nodeStorage.multiGet(keys).map(_.map(raw => storedNodeFromBytes(raw).nodeEncoded.toArray))`
  — one `db.multiGetAsList` + the same ref-count unwrap as the per-key `get` (`:37`), byte-identical results
  (FR-022). `FastSyncNodeStorage` inherits it unchanged. Optionally mirror on `ReadOnlyNodeStorage` (buffer-first
  then batch misses). `ArchiveNodeStorage` unchanged (already batched, `:19`).

**Rationale**: `seekFrom` is `Stream[IO,...]` and the `iterateRange` call site is synchronous on the actor
thread — a synchronous, self-closing `scanRange` is the type-compatible, leak-proof primitive. US4's fields are
already wired end-to-end, so the real ask (FR-013/014/015) is documentation + the off-heap memory guardrail.
The US7 override reuses the exact decode of the per-key `get`, so it is purely a batching change.

**Alternatives rejected**: `scanRange` as fs2 `Stream[IO,...]` (forces `unsafeRunSync` at a sync boundary or a
wide refactor); one long-lived iterator across the whole level (fragile close under no-`return`/`finalize`,
leaks on abort); new config keys for limits (already wired — dead code); `multiGet` on the abstract trait
(can't know each subclass's decode — would corrupt ref-count results); migrating `InMemoryBfsQueueStorage`
through `EphemDataSource` (coupling for no gain).

**Caveats**: `toKeyExclusive` compare MUST be unsigned (`Arrays.compareUnsigned`) or high-byte keys mis-order;
`EphemDataSource.scanRange` MUST emit sorted to match RocksDB order and the existing `flatten`-by-key order;
buffering a chunk is O(chunkSize) (~2MB at 50K×37B — identical to the current result list); the basic-pruning
override must return byte-identical results (FR-022/SC-006).

---

## Cross-cutting

- **Consensus boundary**: only R1 (marker set/clear) and the R2/US6 inflation counters touch
  consensus-adjacent code; R1 goes through `forge`, and a shared-ancestor completeness regression test
  (FR-025) is mandatory. R2/R3/R4 are observation/concurrency/IO/config and preserve enqueue/skip/detection
  byte-for-byte.
- **Live node**: none of this is applied to the running production node without operator sign-off; config
  changes (US3/US4 keys, statistics flag) take effect on the next controlled restart.
- **ADR**: record the completeness-marker set/clear semantics and the Bloom-filter rejection under
  `docs/adr/` (Constitution VII).
