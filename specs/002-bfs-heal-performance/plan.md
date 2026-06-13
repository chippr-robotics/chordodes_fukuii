# Implementation Plan: Post-SNAP BFS State-Healing Walk — Performance, Redundancy-Avoidance, and Observability

**Branch**: `002-bfs-heal-performance` (work branched from `staging`) | **Date**: 2026-06-13 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-bfs-heal-performance/spec.md`

## Summary

Make the post-SNAP BFS state-healing walk fast, non-redundant, and observable on resource-constrained
ETC-mainnet hosts. The investigation behind the spec established that the ~17-hour walk is not a cache
regression and not unbatched reads, but a combination of (1) a **redundant** full-state walk — the
completeness marker is never written when a node heals via the verification path, so every restart
re-walks — and (2) host CPU/GC saturation running the intentionally heavier BFS+disk-queue walk at
effective parallelism 2. The plan delivers, in priority order: a conservative fix so a genuinely-healed
trie skips the walk (US1, the dominant win); observability to measure rather than guess (US2); host
parallelism + read/decode pipelining (US3); tunable resource knobs (US4); a forward-scan queue read
(US5); honest visited-set accounting (US6); and a defensive batched read for basic pruning (US7).

The single hard constraint is **heal completeness is never traded for speed**: the only change that
touches consensus-adjacent logic is *when the completeness marker is set/cleared* (US1) and the
read-only inflation counters (US6/US2) — neither alters which nodes the walk enqueues, visits, or
declares missing. US1's marker logic goes through the `forge` protocol; everything else is
observability, concurrency, IO, or config.

## Technical Context

**Language/Version**: Scala 3.3.7 LTS on JDK 25 (Temurin); sbt 1.10.7+.

**Primary Dependencies**: Apache Pekko (actors), Cats Effect `IO` + fs2 (existing `seekFrom`),
RocksDB (JNI), Micrometer/Prometheus (metrics).

**Storage**: RocksDB column families — state nodes `n` (54.66GB on the reference node), BFS level
queue `q`, healing frontier `g` (holds the completeness sentinel). Production pruning mode is
**archive**.

**Testing**: ScalaTest (FlatSpec/AnyWordSpec), ScalaCheck (property), Pekko TestKit (actors),
deterministic only (no `Thread.sleep`); tiers per ADR-017 (`testEssential` PR gate).

**Target Platform**: Linux server. Reference host: 4 cores, 10GiB container limit, 6g heap, DRAM-less
SATA SSD behind LUKS/dm-crypt.

**Project Type**: Single-project blockchain node (`src/main`).

**Performance Goals**: SC-001 healed+marked node reaches regular sync < 5 min after restart (no walk);
SC-002 ≥ 2× walk throughput on a host with ≥ 4 cores free and unsaturated disk; SC-006 heal
completeness unchanged (100% of injected missing nodes still detected).

**Constraints**: Consensus-adjacent — completeness sacred (Constitution I); consensus/state code within
~10% of perf baseline and byte-identical results; no node OOM at documented defaults (SC-005); no live
production restart without operator sign-off (FR-026); `scalafmt`/`scalafix` clean (no `return`/
`finalize`); statement coverage ≥ 70%.

**Scale/Scope**: ~73M Level-7 nodes on ETC mainnet; ~7 files of production change + tests across four
modules (snap actors, db storage, db dataSource, conf).

## Constitution Check

*GATE: evaluated before Phase 0 and re-checked after Phase 1 design.*

| Principle | Assessment | Gate |
|-----------|------------|------|
| **I. Consensus Determinism (NON-NEGOTIABLE)** | Healing is consensus-*adjacent*: an incomplete heal yields a bad state root that fails block import. US1 changes only *when the completeness marker is set/cleared* and is provably conservative (marker set ⟺ a walk covered the trie AND `isComplete`); US6/US2 inflation counters are observation-only and do not change enqueue/skip/detection. All other stories are non-consensus (observability, concurrency, IO batching that returns byte-identical results, config). **US1 follows the `forge` protocol; FR-023/024/025 encode the guardrails (no Bloom filter; required shared-ancestor completeness regression test).** | **PASS** |
| **II. Spec-Driven Development** | In the Spec Kit flow; spec + plan + (next) tasks under `specs/002-bfs-heal-performance/`. | **PASS** |
| **III. Test Discipline & Tiered Coverage** | Deterministic tests mandated (FR-025); no `Thread.sleep`; same-thread EC injection for actor/walk tests; coverage ≥ 70%. Consensus-adjacent US1 validated by completeness regression tests. | **PASS** |
| **IV. Idiomatic Scala 3** | All new code under `com.chipprbots.ethereum`; `scalafmt`/`scalafix` clean; the `scanRange` impl uses `try/finally` (no `return`/`finalize`) for iterator close. | **PASS** |
| **V. Quality Gates** | `sbt pp` green before PR; CI gates unchanged. | **PASS** |
| **VI. Security & Operational Safety** | No key/RPC-surface change. RocksDB statistics is opt-in (default off). Live node not restarted without operator sign-off. No secrets. | **PASS** |
| **VII. Versioning & Decision Records** | Consensus-relevant completeness-marker semantics recorded as an ADR (see below); conventional commits; spec/plan linked. | **PASS (ADR follow-up)** |

**No violations requiring Complexity Tracking.** One follow-up: record an ADR under `docs/adr/` for
the completeness-marker set/clear semantics and the Bloom-filter rejection (consensus-relevant
decisions, Principle VII).

## Project Structure

### Documentation (this feature)

```text
specs/002-bfs-heal-performance/
├── plan.md              # This file
├── research.md          # Phase 0 — design decisions (4 areas)
├── data-model.md        # Phase 1 — entities, counters, config keys, state transitions
├── quickstart.md        # Phase 1 — per-story validation guide
├── contracts/
│   └── internal-interfaces.md   # Phase 1 — DataSource.scanRange, metric series, config keys, marker contract
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT here)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/
├── blockchain/sync/snap/
│   ├── actors/TrieNodeHealingCoordinator.scala   # US1 marker set/clear; US2 counters+timing; US3 parallelism+pipeline; US6 naming
│   ├── actors/GcPressureSampler.scala             # US2 (new) — windowed GC pause sampler
│   └── SNAPSyncMetrics.scala                      # US2 — new gauges (cache hit/miss, phase ms, GC, inflation)
├── db/storage/
│   ├── HealingFrontierStorage.scala               # US1 — marker (markComplete/isComplete/clearComplete) [read; behavior unchanged]
│   ├── BfsQueueStorage.scala                       # US5 — iterateRange via scanRange
│   ├── ReferenceCountNodeStorage.scala             # US7 — batched multiGet override
│   └── ReadOnlyNodeStorage.scala                   # US7 (optional) — batched multiGet override
└── db/dataSource/
    ├── DataSource.scala                            # US5 — abstract scanRange
    ├── RocksDbDataSource.scala                     # US5 native scanRange; US2 Statistics; US4 (already wired)
    └── EphemDataSource.scala                       # US5 — scanRange fallback (map filter)

src/main/resources/conf/base/
├── sync.conf    # US3 — healing-min-parallelism, healing-reserved-cores
├── db.conf      # US2 — enable-statistics; US4 — max-open-files / block-cache-size docs
└── pekko.conf   # US3 — healing-reader-dispatcher

src/test/scala/com/chipprbots/ethereum/...           # FR-025 completeness regression + per-story tests
```

**Structure Decision**: Single-project layout; changes are localized to the SNAP healing actors, the
db storage/dataSource layers, and base config. No new module, no layer-violating dependency.

## Phase Outputs

- **Phase 0** (`research.md`): four resolved design areas — completeness-marker lifecycle (forge),
  observability wiring, walk concurrency/pipelining, scanRange + config + basic-pruning batching.
- **Phase 1** (`data-model.md`, `contracts/`, `quickstart.md`): entities/counters/config, the internal
  interface contracts (`DataSource.scanRange`, metric series names, config keys, marker behavior
  contract), and a per-story validation guide.

## Complexity Tracking

> No Constitution Check violations. Section intentionally empty.
