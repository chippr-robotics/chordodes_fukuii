# Fukuii Modernization — Deferred Backlog

**Last updated**: 2026-06-18 (Part 8 added — Classic TestKit, opaque types, memory audit, IO threading, ScalaFix expansion, dead code, braceless syntax, property-based testing, RLP modernization, test quality; sprint sequence updated with parallel housekeeping tracks)
**Purpose**: Single reference for all deferred cleanup work — completed items,
active deferred items, and follow-up sprint plans.

Active sprint plan: `/home/dev/.claude/plans/we-are-working-on-noble-whisper.md`

---

## Part 1: Deferred — Remaining Compiler Warnings

**Status: W2-P1 sweep COMPLETE (7 commits, 2026-06-21). ~507 non-E165 → 87 non-E165 remaining.**

**Commits and earlier-phase fixes:** see `completed/DEFERRED-BACKLOG.md` W2-P1 History section.

### Remaining 87 Non-E165 Warnings (all externally gated)

| Count | Cause | Gate |
|-------|-------|------|
| 68 | `json4s extract[T]` Manifest synthesis (16 files) | json4s 4.x upgrade (DEFERRED-BACKLOG §4e) |
| 9 | `OpCode.scala` — infix ops + wildcard in `vm/` | FORGE gate |
| 2 | RocksDB `ClockCache` deprecated | Library upgrade |
| 2 | diffx `DiffMatcher` | Library upgrade |
| 1 | Guava `CacheBuilder` | Library upgrade |
| 1 | `EngineApiService.scala:581` `Ordering.Iterable` | BEACON gate |
| 1 | web3j `Admin` deprecated | Library upgrade |
| 1 | `TrieNodeHealingCoordinator` inside Pekko library boundary | Unfixable |
| 1 | `PeerRequestHandler` `ClassTag` unsound type-test | Needs `TypeTest[A,B]` (non-trivial) |

**E165 floor: 333** (intentional Pekko Classic bridges — not touched, expected permanent).

---

## Part 2: Pekko Classic → Typed Migration (follow-up sprint)

**Status**: Deferred from scala3-cleanup-june — needs subsystem-scoped plan, not actor-by-actor.
**Spec**: `pekko-typed-migration-p2.md`
**Agent**: LOOM (`.claude/agents/loom.md`)
**Prerequisite**: scala3-cleanup-june merged to staging first (wildcard migration complete).

### Why Subsystem Scoping

Actor-by-actor migration creates half-migrated packages: callers hold Classic `ActorRef` while
callees are Typed, requiring Classic→Typed adapters at every boundary. Migrating a whole subsystem
at once keeps callers and callees coherent in a single commit.

### Subsystem Order (lowest risk → highest)

**All 4 subsystems DONE** — see `completed/DEFERRED-BACKLOG.md` (faucet `551bccfaf`, jsonrpc `2ac71a58e`+`1309bb968`, transactions `0be6dd776`, OmmersPool+MockedMiner `0aa837d5e`).

### Remaining Classic→Typed Bridges (require network/P2P sprint to remove)

The actors in P2a–P2d are **genuinely Typed** (no Classic code inside them). However, three
structural bridges remain because the root `ActorSystem` in `NodeBuilder` is still Classic:

| Bridge | Location | Why it exists | Removed when |
|--------|----------|---------------|--------------|
| `system.spawn(...)` via `adapter._` | `NodeBuilder.OmmersPoolBuilder`, `MockedMiner.spawn` | Classic root can't spawn Typed children natively | Root flipped to `ActorSystem[Nothing]` |
| `system.toTyped.scheduler` | `PoWBlockCreator`, `PoWMining` | Typed ask needs a Typed Scheduler | Same — root flip |
| `MockedMiner.Send(msg, replyTo)` envelope | `PoWMining.sendMiner/askMiner`, callers | External `MockedMinerProtocol` API kept stable to avoid breaking `Mining` and `QAService` | When `Mining` API is absorbed into `Command` ADT — MEDIUM priority; `PoWMining` is production mining code, not test-only |
| `ctx.messageAdapter` / `toClassic.eventStream` | PTM (`transactions/`, P2c) | `eventStream` is Classic; Typed actors subscribe via adapter bridge | Root flip + eventStream modernization |

**These are intentional scaffolding, not shortcuts.** The actors are real Typed. The bridges
stack at the spawn boundary and will be eliminated together when the network/P2P sprint
migrates the remaining Classic actors and allows the root system to be flipped.

**The migration is functionally complete, not architecturally complete.** Wave 2 is not
the end of Pekko modernization — it is the first half. The second half is the network/P2P
sprint below.

### NET Group — Deferred Items

**Both NET Group items DONE** — see `completed/DEFERRED-BACKLOG.md` (`4b101b612`, `6b506a63f`).

### Network/P2P Sprint — Pekko Migration Completion Gate

**This sprint completes the modernization.** It is not optional cleanup.

`network/` and `sync/` actors (~20 files, ~25k LOC) are the core P2P/sync engine. They
must be migrated in their own sprint (R1 research → implementation) because:
- They are deeply coupled to the wire protocol (HERALD review required per subsystem)
- SNAPSyncController alone is 5,052 LOC
- Migration atomicity requires grouping by caller coherence, not individual actors

**After all network/sync actors are migrated:**
1. Flip `NodeBuilder.system` → `ActorSystem[Nothing]` — removes `adapter._` bridges
2. Remove `system.toTyped.scheduler` shims in `PoWBlockCreator`, `PoWMining`
3. Absorb `MockedMinerProtocol` into `MockedMiner.Command` ADT; clean `PoWMining` API
4. Replace `toClassic.eventStream` bridges with native Typed `EventStream`

**Sequence:** Run R1 from `WAVE3-RESEARCH-PLAN.md` to produce the migration plan, then
implement per the R1 output. See SPRINT-QUEUE.md Part 6 for the kickoff spec.

### Pre-flight Before Each Subsystem

```bash
# Verify no remaining ._ imports at migration start
grep -rn "import .*\._" src/main/scala/ --include="*.scala" | wc -l  # must be 0

# eventStream audit (run before jsonrpc and transactions)
grep -rn "eventStream\.publish\|eventStream\.tell\|eventStream\.subscribe" \
  src/main/scala/ --include="*.scala"
```

### Thread Format

Each subsystem = one implementation thread:
> "Use the LOOM agent to migrate the `[subsystem]` subsystem to Pekko Typed on
> scala3-cleanup-june. Files: [list]. Read pekko-typed-migration-p2.md and the LOOM agent
> brief. For transactions/ and OmmersPool: run FORGE first. After migration: `sbt compile-all`.
> One commit per subsystem."

### Rejection Criteria

- `grep -rn "import .*\._" src/main/ | wc -l` > 0 at migration start
- PTM eventStream types cross network boundary → full `@SerializabilityTrait` pre-flight required
- Any file under `consensus/`, `vm/`, `crypto/`, `domain/` touched → invoke FORGE before proceeding
- `sbt testEssential` drops below 3,601 tests

---

## Part 3: Scala 3 Modernization (separate sprint — do not mix with Pekko)

### 3a — implicit → given/using

**Spec**: `scala-implicit-to-given.md`
**Agent**: MITHRIL (after `sbt scalafix GivenUsing`)
**Blast radius**: 198 files, 522 `implicit val/def` declarations

Hotspot files (highest density — start here):
1. `jsonrpc/McpJsonMethodsImplicits.scala` — 32 implicits
2. `utils/Picklers.scala` — 21
3. `jsonrpc/JsonMethodsImplicits.scala` — 21
4. `jsonrpc/EthBlocksJsonMethodsImplicits.scala` — 19

**Prerequisite**: Add `GivenUsing` to `.scalafix.conf` BEFORE running. Must run AFTER
Pekko migration sprint (actor files will also be touched by GivenUsing).

### 3b — implicit class → extension methods — DONE `c0a3612b4` — see `completed/DEFERRED-BACKLOG.md`

### 3c — isInstanceOf / asInstanceOf audit

**Count**: 83 instances across network decoders and JSON-RPC marshalling
**Risk**: MODERATE — bypasses type safety
**Approach**: `grep -rn "isInstanceOf\|asInstanceOf" src/main/ --include="*.scala"`
Audit hot paths; replace with pattern matching / algebraic data types.

### 3d — sealed trait → enum (recommended polish — elevated)

**Count**: ~50 pure `case object` hierarchies out of 146 sealed traits
**Risk**: LOW
**Constraint**: NEVER migrate hierarchies with `case class` subtypes.
**Priority**: LOW-MEDIUM — elevated from "optional" because high-value candidates exist:
- `SyncPhase`, `ForkIdValidationResult` — DONE `adf4e69ea` (see completed)
- `Blacklist.BlacklistReason` ❌ REJECTED — has 7 `final case class` subtypes (`EmptyBlockBodies`, `EmptyReceipts`, `InvalidReceipts`, `FastSyncRequestFailed`, `InvalidStateResponse`, `RegularSyncRequestFailed`, `BlockImportError`). Not a pure discriminant; cannot be an enum.
- `Blacklist.BlacklistReasonType` ❌ REJECTED — non-trivial behavior fields (`code: Int`, `name: String`) and mixin group traits (`FastSyncBlacklistGroup` etc.). Not a pure discriminant enum.
- `SyncProtocol.SyncStatus` equivalents — still candidate; verify subtypes are pure `case object` before migrating

Enum promotes exhaustiveness checking and derives `ordinal`, `values`, `fromOrdinal` for free.
**Parallel-safe**: Can be done file-by-file, no actor migration gate. Good housekeeping task.

#### §3d residual — SyncProtocol.SyncStatus candidate

One remaining candidate not yet assessed:
```bash
grep -rn "SyncProtocol\.SyncStatus\|sealed.*SyncStatus\|case object.*SyncStatus" \
  src/main/ --include="*.scala"
```
If all subtypes are pure `case object` (no fields, no methods, no constructor params): migrate to `enum` in the same commit pattern as `SyncPhase` (`adf4e69ea`). If any subtype has fields → reject (add ❌ REJECTED note here). This is a 5-minute check + 15-minute migration if confirmed. Handle opportunistically when already in `sync/` files.

### 3g — StateValidator.scala Exception Swallowing — DONE 2026-06-20 — see `completed/DEFERRED-BACKLOG.md`

---

### 3e — Console output → logging

**Count**: 28 `println`/`System.out`/`System.err` calls
**Priority**: Opportunistic — fix when already touching a file.
**Replace with**: `ctx.log.info(...)` (Typed actors) or SLF4J logger.

### 3f — Manual synchronization outside actors

**Count**: 20 `.synchronized`/`.wait()`/`.notify()` outside actor boundaries
**Priority**: Address where overlapping with Pekko migration; audit remainder separately.

**Audit DONE** `cf33cfa87` — see `completed/DEFERRED-BACKLOG.md`. One FORGE-gated site (`PoWMining.scala:106`) logged in CHASE-QUEUE.

---

### 3h — `Any` in Type Signatures — DONE 2026-06-22 — see `completed/DEFERRED-BACKLOG.md`


## Part 4: Dependency Upgrades (blocked or deferred)

### 4a — JLine 3.x → 4.x

**Current pin**: `3.30.13`
**Target**: `4.1.x`
**Why deferred**: JLine 4.x is not drop-in compatible. Two files need refactoring:
- `console/TuiRenderer.scala` — `AttributedString`, `AttributedStyle`
- `console/Tui.scala` — `Terminal`, `TerminalBuilder`

Terminal dimension APIs standardised in 4.x (`Sized` interface, `Size.of()` factory).
Panama FFM replaced signal handling.

**Prerequisite**: Dedicated jline-upgrade sprint; assess TUI rendering test impact.

### 4b — Logstash-Logback-Encoder 8.x → 9.x

**Current pin**: `8.1`
**Target**: `9.0` (October 2025, latest)
**Why deferred**: 9.0 requires Jackson 3 exclusively (jackson-bom:3.0.1). Also bumps minimum Java to 17. 8.1 is the last Jackson 2 release.

**Prerequisite**: 4e (Jackson 2→3) first.

### 4c — Kanela-Agent 1.x → 2.x

**Why deferred**: Complete rewrite (AspectJ → ByteBuddy). Requires Kamon 2.8.1+ and
custom instrumentation recompile. Runtime compatibility cannot be verified by compile alone.

**Prerequisite**: Kamon 2.8.1+ confirmed stable; dedicated sprint with changelog review.

### 4e — Jackson 2.x → 3.x (transitive)

**Status**: BLOCKED — gate nearly open as of 2026-06-20 (R3 re-check, Wave 3)

**Sole blocker: json4s.** Library verdicts as of 2026-06-20:

| Library | Gate | Version checked | Finding |
|---------|------|----------------|---------|
| json4s | **GATE NEARLY OPEN** | `4.2.0-M5-SNAPSHOT` (repo-ref HEAD, 2026-06-20) | Jackson 3 already in snapshot: `"tools.jackson.core" % "jackson-databind" % "3.2.0"`; source code imports `tools.jackson.databind.*` (Jackson 3 namespace). Jackson 2 was present at M4 (Jun 11 2026). Transition from Jackson 2 → 3 happened between M4 and M5-SNAPSHOT. Latest stable is still `4.1.1` (Jackson 2). Gate opens on M5 release. |
| circe | **NON-BLOCKER** (confirmed) | `0.14.15` (repo-ref) | circe-core has no Jackson dependency; no jackson module in circe repo. `circe-jackson` is a separate project not on fukuii's classpath. |
| sangria / sangria-circe | **NON-BLOCKER** (confirmed) | `4.2.18` / `1.3.2` (repo-ref) | Zero Jackson references in sangria build or sangria-circe. Depends only on circe-core. |

**Gate condition**: `json4s 4.2.0-M5` (or later) published as a milestone or stable artifact. Verify at the release tag: `project/Dependencies.scala` must reference `tools.jackson.core` (Jackson 3), not `com.fasterxml.jackson.core` (Jackson 2).

**Watch**: https://github.com/json4s/json4s/tags — last tagged release `4.2.0-M4` (Jun 11 2026); repo HEAD is at `M5-SNAPSHOT`. Re-check weekly until M5 tag appears. When it does: update fukuii pin from `4.0.7` → `4.2.0-M5` (or first stable).

**Alternative unblock path (permanent fix)**: Migrate `jsonrpc/` from `json4s-native` to circe. circe is already on the classpath via `sangria-circe`. Scope: ~79 files, `JValue → io.circe.Json`. Non-trivial but eliminates the Jackson gate permanently and removes the `json4s` dependency entirely.

---

## Part 5: Blocked (gate conditions)

### 5a — Scala 3.9 Upgrade

**Spec**: `scala-39-upgrade.md`
**Gate**: Scala 3.9.x LTS appears on endoflife.date with LTS designation
**Action when unblocked**: Bump `scalaVersion` in `build.sbt`; update scapegoat to
matching version; run `sbt compile-all`; fix new warnings/errors.

### 5b — Virtual Threads / Ox Evaluation

**Spec**: `virtual-threads-evaluation.md`
**Status**: Research only — low priority
**Reference**: `.claude/virtuslab/scala-skill/direct-style-scala/SKILL.md`
Post-Typed migration: evaluate replacing Actor mailboxes with Ox `supervised` scopes.

### 5c — Constitution v1.2.0

**Spec**: `constitution-amendment-v1.2.0.md`
**Status**: Blocked on Scala 3.9 upgrade (5a).

---

## Part 6: Tech Debt Deletion

### 6a — extvm/ Dead Code Deletion — DONE `a948fda1d` — see `completed/DEFERRED-BACKLOG.md`

---

## Part 7: Post-CAPSTONE Typed API Maturity

These items gate on CAPSTONE complete (all actors Typed, ActorSystem[Nothing] root, adapter.* removed).
The mechanical migration preserves Classic-era design decisions. Once the scaffolding is gone, these
three phases evaluate and improve the code as a native Typed system.

### 7c — Typed Supervision Hierarchy

**What**: The mechanical migration preserved default stop-on-failure supervision from the Classic
system root. Pekko Typed supports explicit `Behaviors.supervise(...).onFailure[ExceptionType](strategy)`
per actor. A full supervision design would specify restart/stop/escalate per failure class per actor,
matching the system's fault-tolerance requirements.

**Scope**: Audit every actor's failure modes. Apply explicit supervision at spawn sites for actors
where restart-on-failure is safe (coordinator workers, peer workers) vs. stop-and-alert for actors
where restart could cause state corruption (SSC, NPMA).

**Gate**: CAPSTONE complete + 7a done (sealed ADTs make failure typing cleaner).
**Priority**: Low — the current behavior is safe (default stop is conservative); explicit supervision
is a correctness/resilience improvement, not a bug fix.
**Agent**: PRISM (review) + LOOM (implementation per subsystem).

---

### 7d — Post-CAPSTONE Classic Artifact Audit — DONE 2026-06-21 — see `completed/DEFERRED-BACKLOG.md`

---

### 7e — Design Review Summary

Completed portions (P4, P4a) → `completed/DEFERRED-BACKLOG.md`. Open items below.

---

### 7e-P2 — HealingState Extraction (SSC)

**What**: Six SSC fields are semantically phase-local to the healing phase:
`trieWalkInProgress`, `healingServeRootRequestInFlight`, and four related vars.
Currently on `Impl`, they bleed across pivot refreshes if a pivot rotation occurs while healing.
Extract to `case class HealingState(...)` threaded as a behavior parameter into the healing
behavior function, making the phase boundary explicit and eliminating the cross-phase bleed risk.

**Scope**: `sync/snap/SNAPSyncController.scala` — `Impl` field extraction + healing behavior split.
**Gate**: 7a complete (✅ done). Recommend HERALD pre-flight for pivot-rotation interaction.
**Priority**: LOW — latent correctness risk on pivot rotation during heal; not currently triggered.
**Effort**: S–M — field identification is complete (7e audit); refactor is mechanical but touches
healing entry/exit paths that are test-covered (74/74 SNAPSync specs must remain green).
**Agent**: LOOM (implementation) + EYE (verify baseline).

---

### 7e-P3 — ChainDownloader Stagnation Push (SSC)

**What**: SSC currently polls `ChainDownloader` progress timestamps to detect download stagnation
(`CheckDownloadStagnation` timer). Invert the relationship: `ChainDownloader` pushes a
`DownloadStagnated` message to SSC when it detects stagnation internally, eliminating the
polling timer and making stagnation detection reactive.

**Scope**: `sync/snap/ChainDownloader.scala` + `sync/snap/SNAPSyncController.scala` — stagnation
detection path only.
**Gate**: 7a complete (✅ done).
**Priority**: LOW — polling timer works correctly; this is a design improvement (back-pressure,
reduced coupling).
**Effort**: S — stagnation logic is isolated; no consensus path touched.
**Agent**: LOOM (implementation) + HERALD (verify no wire-protocol interaction) + EYE (verify baseline).

---

---

## Part 8: Additional Modernization Gaps

These gaps were identified during the Pekko migration sprint and the post-CAPSTONE plan design
session. They are not captured in earlier parts but belong in the full modernization roadmap.
Each is scoped with a gate condition and a "parallel-safe" flag — parallel-safe items can run
during the 24-minute `testEssential` wait without blocking the primary migration thread.

---

### 8a — Classic TestKit → ActorTestKit Migration

**Scope**: 99 test files still use `org.apache.pekko.testkit.TestKit` (Classic) vs. 14 already
using `org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit` (Typed).

```bash
grep -rn "TestKit\|TestActorRef\|ActorSystem(" src/test/ --include="*.scala" -l | wc -l  # 99
grep -rn "ActorTestKit\|BehaviorTestKit\|TestInbox" src/test/ --include="*.scala" -l | wc -l  # 14
```

**Why it matters**: Classic TestKit creates a full Classic `ActorSystem` per test suite, incurring
startup overhead. Typed `ActorTestKit` is lighter, provides `BehaviorTestKit` for deterministic
unit testing (no real actor system needed for simple behaviors), and catches protocol violations
at compile time via ADT message types.

**Strategy**: Migrate per-actor in the same LOOM thread that migrates the production actor.
Do not defer all 99 files to a single later sprint — that creates a massive tangled changeset.
Each LOOM session: after migrating the actor, migrate its test file(s) from `TestKit` to
`ActorTestKit`/`BehaviorTestKit` in the same commit.

**For already-migrated actors** (faucet, jsonrpc, transactions, consensus/mining) whose tests
were not converted: address in a dedicated test-cleanup sprint (8a-retro).

**Batches 1–3 DONE** — see `completed/DEFERRED-BACKLOG.md` (`0d65a85c4` batch 1, `b5e11c0a4`+`722f316f2` batch 2, `12c23cf8a`+`a719520db` batch 3). §8a-infra-c DONE — see completed.

**Gate**: Per-actor gate = that actor's LOOM migration is complete.
**Parallel-safe**: No — test file migration must follow actor migration.
**Priority**: HIGH — should be embedded in each LOOM thread, not deferred.
**Agent**: LOOM (test migration paired with production migration per actor).

**What §8a unlocks (beyond the obvious):**

1. **777 Classic `TestProbe` without `[T]`** — `org.apache.pekko.testkit.TestProbe` has no type
   parameter. The 777-site grep metric (`TestProbe\b` without `[`) cannot be driven to zero without
   §8a. Once a test file is migrated to `ActorTestKit`, use
   `org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[T]` which accepts type params.

2. **20 `fishForMessage` E165 sites** (11 files: `StateSyncSpec`, `ChainWeightCalibrationSpec`,
   `CalibratePivotTDSpec`, `RegularSyncFixtures`, `PeerManagerSpec`, and 6 scoped-verification
   SNAP specs) — Classic `TestProbe.fishForMessage` takes `PartialFunction[Any, Boolean]`, making
   every `case Foo(...) =>` inside it an E165 selector on `Any`. These are part of the intentional
   333 E165 floor and cannot be fixed without §8a migration. After migration, replace
   `fishForMessage { case T => true }` with `expectMessageType[T]` on a Typed probe.

**Research prompt (before starting remaining §8a-retro batches):**
> Read `.claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md` §8a retro batch notes (lines ~525–553)
> for established patterns (PatienceConfig ambiguity, top-level-actor guardian, `system.toClassic`
> for HTTP/pekko-http). Then list the test files for the next migration wave:
> ```bash
> grep -rn "org.apache.pekko.testkit.TestKit" src/test/ --include="*.scala" -l | grep -v "ActorTestKit"
> ```
> For each file, identify: (a) which production actor it tests (must already be Typed), (b) any
> `fishForMessage` calls to replace with `expectMessageType`, (c) any `system.toClassic` needs
> (Pekko HTTP, Classic eventStream). Produce a migration plan per file before touching any code.


---

### 8b — Opaque Types for Domain Value Concepts

**Problem**: Core domain concepts are represented as raw primitive types throughout the codebase.
`BigInt` is used for both block numbers and balances; `ByteString` is used for hashes, state roots,
addresses, and arbitrary byte payloads. There are zero `opaque type` declarations.

```bash
# Zero opaque types:
grep -rn "opaque type" src/main/ --include="*.scala"  # → 0 results

# Raw BigInt used for block concept in protocol messages:
grep -rn "blockNumber: BigInt\|stateRoot: ByteString\|peerId: " \
  src/main/ --include="*.scala" | wc -l  # ~161 occurrences
```

**High-value opaque type candidates**:
| Concept | Current type | Opaque type |
|---------|-------------|-------------|
| Block number | `BigInt` | `opaque type BlockNumber = BigInt` |
| Block hash / state root | `ByteString` | `opaque type Hash = ByteString` |
| Account address | `ByteString` | `opaque type Address = ByteString` |
| Peer ID | `PeerId` (already a type alias?) | Seal as opaque in domain |
| Storage key | `BigInt` | `opaque type StorageKey = BigInt` |
| Balance / nonce | `BigInt` | `opaque type Balance = BigInt`, `opaque type Nonce = BigInt` |

**Why it matters**: Without opaque types, passing a block hash where a state root is expected
compiles silently. Opaque types enforce semantic distinctness at compile time with zero runtime cost.

**Approach**: Research thread first (R6) — map all `BigInt`/`ByteString` usages by semantic role.
Introduce opaque types in `domain/` starting with the highest-confusion pairs (`BlockNumber`/`Balance`
and `Hash`/`Address`). Each opaque type is a one-file change; callers need trivial `.value` unwraps
at boundaries.

**Gate**: None — but do after Part 3a (given/using) since implicit conversions interact.
**Parallel-safe**: YES — each opaque type introduction is file-scoped with clear blast radius. Good
housekeeping task during test waits for specific domain files.
**Priority**: MEDIUM — correctness improvement. Prevents entire class of type confusion bugs.
**Agent**: MITHRIL (Scala 3 type design) + FORGE (for domain/ and consensus/ intersections).

---

### 8c — Memory/Resource Leak Audit

**Context:** VAULT-gate audit of resource lifecycle in `db/`, `node/`, and `core/utils/`. H-series = heap/iterator leaks; M-series = DataSource cache invalidation.

**H2/H3 DONE** `4907406fe`, **H4+M1 DONE** `ef75a5608` — see `completed/DEFERRED-BACKLOG.md`.

**Remaining open:**

#### M4 — DataSource close cache invalidation (VAULT gate)

**Problem:** When a `RocksDbDataSource` is closed (e.g., test teardown, node shutdown), any in-memory `LRU` caches layered over it retain stale references. A subsequent re-open (or test DataSource reconstruction) may read from an invalidated cache entry, producing incorrect data without error.

**Gate:** VAULT review — confirm whether `DataSource.close()` flushes or invalidates overlay caches. If the close protocol is correct, mark M4 as by-design.

**Scope:** `db/` — `RocksDbDataSource.scala`, `EphemDataSource.scala`, any `caching/` layer.

**Agent:** VAULT
**Priority:** LOW — only visible in test isolation or restart scenarios; runtime nodes do not re-open DBs.

**Prompt (VAULT):**
> Read `RocksDbDataSource.close()` and any overlay cache layers in `db/`. Determine whether `close()` invalidates in-memory LRU cache entries or leaves stale references. Two outcomes: (a) if invalidation is missing — add `cache.invalidateAll()` before `rocksDb.close()`, run `sbt compile-all`, then `sbt "testOnly *DataSource*"`; (b) if the close protocol is already correct — add a short inline comment explaining why no explicit invalidation is needed and mark M4 as by-design. Either way, record the verdict in `storage-rocksdb.md` under a "DataSource close protocol" note.

---

### 8d — IO Threading Model Follow-Up (R9 audit items)

**Context:** R9 research (`threading-model-audit.md`, DONE 2026-06-18) found 3 IO/threading issues. Two are cleared; A1 remains open.

**B1+B2 DONE** — see `completed/DEFERRED-BACKLOG.md`.

**Remaining open:**
- **A1** — `EngineApiService.scala`: `Await.result` on CE3 compute thread — **OPEN, BEACON gate**
- **Additional jsonrpc sites** — `api/jsonrpc.md` notes remaining IO boundary sites beyond A1 — **OPEN, CONDUIT review**

#### A1 — EngineApiService `Await.result` on CE3 compute thread (BEACON gate)

**Problem:** `EngineApiService` uses `Await.result(future, timeout)` on the Cats Effect 3 compute thread pool. Blocking a CE3 fiber thread starves the entire compute pool — any concurrent EC3 fiber that needs that thread will hang until `Await` returns.

**Gate:** BEACON — confirm the call site and safe fix approach (defer to IO boundary, use `IO.fromFuture`, or confirm the call never executes on the CE3 pool).

**Spec:** `.local/docs/threading-model-audit.md` — A1 entry.

**Agent:** BEACON
**Priority:** MEDIUM — latency/liveness issue under concurrent engine API load; not data-correctness.

**Prompt (BEACON):**
> Read `threading-model-audit.md` A1 entry and locate `Await.result` in `EngineApiService.scala`. Determine: (a) does this call execute on the CE3 compute pool or on a dedicated blocking dispatcher? (b) if on CE3, is the correct fix `IO.fromFuture` + returning `IO`, switching to a `blocking {}` wrapper, or moving to a dedicated EC? Implement the safer approach. Byte-for-byte response semantics must be preserved — only the threading model changes. Run `sbt compile-all` then `sbt "testOnly *EngineApi*"` to verify.

#### Additional jsonrpc IO boundary sites

**Problem:** `api/jsonrpc.md` open section notes "additional IO boundary sites" beyond A1 that were observed during the R9 research pass but not catalogued in `threading-model-audit.md`.

**Clearing prompt:** CONDUIT audit of `jsonrpc/` for any remaining `scala.concurrent.blocking`, `Await`, or `EC.global` usage not covered by B1/B2/A1. Output a short table of sites + severity.

**Agent:** CONDUIT
**Priority:** LOW — likely few/none after B1/B2 cleared; run as a 15-minute scan before closing §8d.

---

### 8e — ScalaFix Ruleset Expansion + `noReturns` Ratchet Lock

**C2 DONE `9eb1f4e06`, TNHC DONE `7a48c5988`** — see `completed/DEFERRED-BACKLOG.md`.

**Remaining to lock the ratchet** (`sbt scalafixAll` not yet green): 38 deferred sites.

| Deferred category | File(s) | Count | Gate |
|-------------------|---------|-------|------|
| Classic actor — Wave 3 LOOM sprint | `sync/snap/SNAPSyncController.scala` | 36 | Wave 3 network/sync migration (SNAP1) |
| ~~Consensus-critical — FORGE review~~ | ~~6 files~~ | ~~0~~ | ✅ DONE `4544b8025` — 6 CLEAR + 9 `scalafix:ok` DEFER; see `completed/DEFERRED-BACKLOG.md §8e-FORGE` |
| Consensus-path (ETH Engine API) — BEACON review | `consensus/engine/EngineApiController.scala:96` (`handleNewPayload`, malformed-payload decode `Left` branch), `consensus/engine/EngineApiController.scala:226` (`handleForkchoiceUpdated`, malformed-params decode `Left` branch) | 2 | BEACON sign-off (S3-D) |

**Full ratchet lock checklist:**
1. ~~C2 chore~~ ✅ DONE `9eb1f4e06`
2. ~~LOOM Phase 0 TNHC~~ ✅ DONE `7a48c5988`
3. ~~FORGE reviews and clears 6 consensus sites~~ ✅ DONE `4544b8025` — 6 CLEAR + 9 DEFER (`scalafix:ok DisableSyntax.return`); ratchet sees 0 violations across all 6 files
4. BEACON reviews and clears 2 ETH Engine API sites — `EngineApiController.scala:96` + `:226` (S3-D). Both are early-`return IO.pure(...)` decode-error guards inside large consensus-path method bodies; removing the `return` requires wrapping ~90 lines of post-decode body into the `Right`/`else` branch. Deferred from S3-A/S3-D/S3-F commit (2026-06-22): the byte-for-byte response behavior must be preserved across the re-indent; gated on a focused BEACON pass, not bundled with the low-risk Option/val changes.
5. Wave 3 SNAP1 migration sprint clears SNAPSyncController 36 sites ← gated on NET2
6. After all above: run `sbt scalafixAll` to confirm 0 violations → ratchet locked

**Other rules to evaluate enabling (unchanged from original plan):**
```
LeakingImplicitClassVal  # implicit class vals that escape scope
OrganizeImports          # import grouping/deduplication
ExplicitResultTypes      # explicit return types on public defs (enable gradually)
```
These are lower priority and unblocked — add one at a time, fix violations, commit together.

**Gate**: Full ratchet lock gated on LOOM S3 (TNHC) + FORGE (consensus files) + BEACON (ETH Engine API S3-D) + Wave 3 (SNAP).
Partial progress (C2) is safe to run any time.
**Agent**: MITHRIL (rule evaluation); FORGE (consensus files); BEACON (Engine API S3-D); LOOM (TNHC + SNAPSyncController).

---

### 8f — Dead Code Audit (Broader than extvm) ✅ RESEARCH DONE (2026-06-22) — see `completed/DEFERRED-BACKLOG.md`

**FastSyncBranchResolverActor** ✅ WIRED `ea60c4f29` — see completed.

**Deletion sprint open** (4 high-confidence candidates in CHASE-QUEUE.md DEAD entries 2026-06-22):
- Test helpers with `@Ignore` annotations (56 occurrences in tests) — audit which are permanently dead

**Output**: `dead-code-audit.md` — file list, confidence level (definitely dead / possibly dead / uncertain).
Each "definitely dead" file gets a deletion PR.

**Gate**: None — standalone sweep any sprint.
**Parallel-safe**: YES — research only. File-by-file deletion commits are lightweight.
**Priority**: LOW — cleanup only, no functional impact.
**Agent**: PRISM (8-lens review of findings) + FORGE (for any files in consensus/).

---

### 8g — Braceless Scala 3 Syntax

**What**: Scala 3 supports optional braces (indentation-based syntax). The codebase is almost
entirely brace-based (Scala 2 style). Migrating to braceless is purely cosmetic — no behavior
change — but aligns with idiomatic Scala 3 style for new code.

**Scope assessment**:
```bash
# Files still using def foo(...) {  procedure-syntax (should be zero after scalafmt)
grep -rn "def \w\+.*) {$" src/main/ --include="*.scala" | wc -l

# Files using braceless already (if any were written braceless during migration)
grep -rn "^\s\+then\b\|^\s\+do\b" src/main/ --include="*.scala" | wc -l
```

**Recommendation**: LOW PRIORITY. Do NOT do a mass braceless migration — it creates massive diffs
that obscure semantic changes in code review. Instead: adopt braceless for all NEW code written
during subsequent sprints, and opportunistically convert small files touched for other reasons.

Add scalafmt config to prefer braceless in new files:
```
rewrite.scala3.convertToNewSyntax = true
rewrite.scala3.removeOptionalBraces = true
```

**Partial work committed (2026-06-18, style(scala3): enable braceless syntax preference in scalafmt config):**
- `convertToNewSyntax = true` — APPLIED. 392 source files, keyword-syntax only (`if (x) {` → `if x then {`, `while (x) {` → `while x do {`). 3,125 ins / 3,482 del, zero logic impact.
- `removeOptionalBraces = true` — **DEFERRED**. Triggered 945-file brace removal — too aggressive for a single chore commit; would bury semantic changes in code review. Needs its own dedicated pass, scoped per-subsystem or gated post-CAPSTONE.

**Remaining work**: Enable `removeOptionalBraces = true` in `.scalafmt.conf` and apply subsystem by subsystem (e.g. `jsonrpc/` first, then `network/`, etc.) rather than a single 945-file sweep.

**Gate**: After CAPSTONE (new-code policy; don't distract migration diffs with style churn).
**Parallel-safe**: YES per-file, but mass conversion creates large diffs — scope per-subsystem.
**Priority**: LOW — style only. Scalafmt config change is the high-value step; mass rewrite is not.
**Agent**: MITHRIL (config change + selective file conversions).

---

### 8h — Property-Based Testing Expansion

**Current state**: 589 ScalaCheck usages already exist in the test suite — the codebase has
`forAll` / `Gen.*` / `Arbitrary` in use. However coverage is uneven.

**Gaps to fill**:
1. **RLP codec round-trip properties**: Every RLP-encodeable domain type should have a
   `forAll { value => decode(encode(value)) == value }` property test. Missing for several
   new message types added in ETH68/69/70 work.
2. **Domain type invariant properties**: `Block`, `BlockHeader`, `Transaction` have
   invariants (e.g., `gasUsed <= gasLimit`) that should be property-tested, not just
   unit-tested with fixed examples.
3. **SNAP protocol message codecs**: `AccountRangePacket`, `StorageRangesPacket`, `ByteCodesPacket`
   — check if fuzz-tested with boundary values (empty ranges, maximum-size ranges, malformed keys).
4. **Cryptographic operations**: `keccak256`, `recoverPublicKey` — check property coverage.

**Approach**: R2 (test quality audit) should enumerate existing ScalaCheck coverage. Add missing
properties during the sprint that migrates the relevant actor (natural pairing — you're already
touching the code and understand the invariants).

**Gate**: R2 (test quality audit) ✅ COMPLETE — `test-quality-audit.md` documents existing ScalaCheck coverage; unblocked.
**Parallel-safe**: YES — individual property test additions are file-scoped.
**Priority**: MEDIUM — catches codec correctness bugs that unit tests miss.
**Agent**: EYE (test validation) + HERALD (for wire-protocol message codecs).

---

### 8i — RLP Typeclass Derivation Modernization

**Current state**: 183 `implicit val`/`def` RLP encoder/decoder instances across the codebase.
These are all handwritten `RLPList`/`RLPValue` construction. Scala 3's `given`/`using` derivation
can replace many of these with automatic derivation for product types.

```bash
grep -rn "RLPImplicit\b\|implicit.*RLP\|implicit.*Encoder\|implicit.*Decoder" \
  src/main/ --include="*.scala" | wc -l  # 183 instances
```

**Two categories**:
1. **Product type codecs** (simple ADTs): Can use Magnolia/Shapeless-style derivation via
   `deriving` clause or `given RLPEncoder[MyType] = RLPEncoder.derived`. Eliminates boilerplate.
2. **Custom-layout codecs** (ETH wire protocol, RLP spec compliance): MUST remain handwritten.
   ETH encoding has non-trivial layout (e.g., empty byte string for zero vs. single-byte for 1).
   Do NOT auto-derive these — FORGE review required.

**Approach**: Research thread (R7) to map which codecs are safe to derive vs. must remain manual.
Part 3a (implicit→given) should be done first — converts the instances to `given` syntax before
considering derivation.

**Gate**: Part 3a (implicit→given) complete. R7 research thread complete.
**Parallel-safe**: NO — codec changes are high-risk (wire protocol correctness). Full test suite
run required after each change.
**Priority**: MEDIUM — reduces maintenance burden for new message types; reduces RLP boilerplate.
**Agent**: MITHRIL (derivation design) + FORGE (any codec touching consensus message layout) +
EYE (full codec test suite after each change).

---

### 8j — Test Quality: Thread.sleep and Timing Sensitivity

**Current state**: 56 occurrences of `Thread.sleep` / `@Ignore` in test files (original baseline). **EYE baseline 2026-06-22**: combined grep now 49 lines (36 `@Ignore`/ignore + 13 `Thread.sleep` raw hits). Live `Thread.sleep` call sites: **2** (comments-only in 3 other files). Prior audit count of 56 reflected a different codebase state.

```bash
grep -rn "Thread\.sleep\|@Ignore\b\|ignore\b" src/test/ --include="*.scala" | wc -l  # 56
```

**Why it matters**: `Thread.sleep` in tests is a primary cause of flakiness under load (CI
slower than dev machine → timeouts). `@Ignore` annotations silently hide untested behavior.

**Approach**: Part of R2 (test quality audit). Map all 56 occurrences:
- `Thread.sleep` → replace with `eventually(...)` from ScalaTest or Pekko's `TestProbe.expectMsg(duration)`
- `@Ignore` → determine if the test is permanently dead (delete) or blocked on missing infra (document why)

**Gate**: R2 audit ✅ COMPLETE (`test-quality-audit.md`, 303 lines). All 7 confirmed Tier B — see `thread-sleep-audit.md §B1–B5` for recipes. **EYE baseline verified 2026-06-22**: 2 live call sites (SubscriptionManagerSpec:249, EthMiningServiceSpec:302). 11 comment-only references (PoWMiningCoordinatorSpec ×3 comments, PendingTransactionsManagerSpec ×4 Scaladoc anti-patterns, SNAPSyncControllerResumeSpec ×1, GcPressureSamplerSpec ×1). No new FLAKY sites beyond the 2 live calls.
**Parallel-safe**: YES — each test file fix is independent. Good downtime task during long test runs.
**Priority**: LOW-MEDIUM — prevents CI flakiness as the test suite grows.
**Agent**: EYE (test validation after fixes).

---

### 8k — Classic Interop Elimination: `.toClassic` / `actorSelection` / Classic Bridge Audit

**What**: Scala's type system is Typed's main advantage. The codebase currently has ~180 `.toClassic` / `.toTyped` bridge calls, 10 `actorSelection` sites (test-only), and 6 `classicSystem.actorOf` sites in production. These are necessary bridges while classic actors remain in the system, but every bridge site is a hole in the type lattice — an untyped `ActorRef` flowing where a `ActorRef[T]` could carry compile-time guarantees. The goal is to inventory all sites, understand WHY each exists (which classic actor is the root cause), and eliminate them in priority order as classic actors complete their LOOM migrations.

**Root-cause breakdown (§8k-R1 audit COMPLETE 2026-06-23 — full detail at `.local/docs/classic-interop-audit.md`):**

~130 production bridge sites + 2 test `actorSelection` sites. Permanent floor: 4 TCP bridges. Eliminatable: ~126 production + 2 test.

**Clusters A,B,C,D,F,G,H,J,K,L,M,N DONE** — see `completed/DEFERRED-BACKLOG.md`.

| Cluster | Sites | Root cause | Pre/Post-CAPSTONE | Sprint |
|---------|-------|-----------|-------------------|--------|
| E — `externalAdapter.toClassic` in SyncController | 21 remaining | Per-child adapter pattern: eliminated one spawn-site at a time when the receiving child updates its constructor param from `ActorRef` → `ActorRef[T]`. NOT the same as OQ-5. | Pre-CAPSTONE | §8k-G2 (immediate: FastSync + NPMA cmd) + per-child LOOM migration |
| I — TCP I/O bridge (RLPxConnectionHandler, ServerActor) | 4 | Akka TCP requires Classic `sender()` — **permanent** | N/A | — |

**Principle**: Each `.toClassic` call is a symptom, not the disease. The disease is an unconverted classic actor upstream. The fix strategy is: **migrate the upstream actor first (LOOM), then delete the bridge**. Bridges must never be removed before the upstream is converted — that produces a type error at the call site that blocks compilation.

---

#### §8k-R1 — DONE 2026-06-23 — see `completed/DEFERRED-BACKLOG.md`

#### §8k-G — DONE 2ef2b6637 — see `completed/DEFERRED-BACKLOG.md`

---

#### §8k-G2 — PRISM + MITHRIL: Cluster E immediate cohort — spawn-site `.toClassic` elimination

**Agent:** PRISM (audit which children are already Typed), then MITHRIL (update constructor params + spawn sites)
**Risk:** LOW-MEDIUM — touches child constructor signatures and SyncController spawn sites; compile-verified
**Gate:** §8k-G ✅ DONE
**Bridge sites targeted:** subset of the 21 remaining `externalAdapter.toClassic` sites where the receiving
child has already been migrated to Typed but its constructor param type was not updated to accept `ActorRef[T]`

**Pekko 2.x context:** This sprint implements **pekko-typed-api.md P16** — the protocol standard
that constructor params must declare `ActorRef[T]` (Typed), not Classic `ActorRef`, whenever the
receiving actor is a Typed Behavior. Pekko 2.x removes `org.apache.pekko.actor.typed.scaladsl.adapter`
entirely: every `.toClassic` call on a Typed ref becomes a compile error. Each site eliminated here
is one less blocker for `pekko-version := "2.x"` in `build.sbt`. The spawn-site `.toClassic` pattern
is also the systematic gap that **pre-migration-checklist.md Step 13** is designed to catch: after
any LOOM migration, verify the child's constructor param type matches the Typed caller's ActorRef.

**Background:**
When §8k-G ran, CONDUIT correctly identified that `externalAdapter.toClassic` sites are per-child adapter
patterns: each site disappears only when the receiving child's constructor param changes from Classic
`ActorRef` to `ActorRef[T]`. Two known immediate candidates (child already Typed, param not yet updated):

1. **FastSync `syncController: ActorRef` param** — FastSync was cleaned up in §8k-F/§8k-G but its
   constructor still declares `syncController: ActorRef` (Classic). SyncController passes `externalAdapter.toClassic`
   at line ~1530. Since FastSync is already Typed, update the param to `ActorRef[Any]` (the adapter type)
   and remove the `.toClassic` at the spawn site.

2. **NPMA `RegisterChainWeightCalibrationTarget(replyTo: ActorRef)`** — NPMA was migrated in §8k-E but
   this command still carries a Classic `ActorRef`. SyncController sends `RegisterChainWeightCalibrationTarget(externalAdapter.toClassic)`
   at line ~1635. Update the command field to `ActorRef[Any]` (or the specific type NPMA sends back)
   and remove the `.toClassic`.

There may be additional candidates (ForkChoiceManager, PivotHeaderBootstrap). PRISM audit identifies them.

**PRISM audit step (run first):**
```bash
cd /media/dev/2tb/dev/fukuii

# Find all 21 remaining externalAdapter.toClassic sites with their receiving actor
grep -n "externalAdapter\.toClassic" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala

# For each receiving actor/command found, check if it's already a Typed Behavior:
grep -rn "class FastSync\|object FastSync\|extends Behavior\|Behaviors\." \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala | head -5

grep -rn "RegisterChainWeightCalibrationTarget" \
  src/main/scala/com/chipprbots/ethereum/network/ --include="*.scala"

grep -rn "class ForkChoiceManager\|extends Behavior" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/ --include="*.scala"

grep -rn "class PivotHeaderBootstrap\|extends Behavior" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/ --include="*.scala"
```

For each site, PRISM should classify as:
- **IMMEDIATE** — receiving actor is Typed; only constructor param type update needed
- **GATED** — receiving actor is still Classic; blocked on that actor's LOOM migration

**MITHRIL implementation (immediate sites only):**

For each IMMEDIATE site:
1. Update the receiving actor's constructor param from `ActorRef` (Classic) to `ActorRef[Any]`
   (or a more specific `ActorRef[T]` if the sent message type is known and narrow).
2. Update internal usages of that param inside the child (Classic `!` → Typed `!` — same syntax, type changes).
3. In SyncController: remove `.toClassic` at the spawn site — pass `externalAdapter` directly.
4. `sbt compile-all` after each actor.

**Verify:**
```bash
# Count should decrease from 21 toward the gated-only floor
grep -rn "externalAdapter\.toClassic" src/main/ --include="*.scala" | wc -l

# No new compilation errors
sbt compile-all
./media/dev/2tb/dev/fukuii/.local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage SyncController + each updated child constructor file
3. `git commit -m "refactor(8k-G2): Cluster E immediate cohort — drop externalAdapter.toClassic at Typed child spawn sites"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(8k-G2): clearout — $SHA"`
5. Update CHASE-QUEUE: mark cleared sites as CLEARED with SHA
6. **DELETE §8k-G2**

---

#### §8k-R2 — PRISM: Post-migration gap audit — spawn-site `.toClassic` slippage

**Agent:** PRISM (read-only, 8-lens analysis)
**Risk:** ZERO — research only, no code changes
**Gate:** Any time. Run after any LOOM migration sprint completes.
**Purpose:** Catch the systematic gap where a child actor is fully migrated to Typed but its spawn-site
caller still passes `.toClassic` because the child's constructor param type was not updated in the same commit.

**Why this keeps happening:**
LOOM migration tasks focus on the actor's *internals* (removing `sender()`, `context.become`, timers,
`Props`). They do not require updating the actor's *constructor signature* to accept `ActorRef[T]` instead
of `ActorRef`. The spawn-site caller (often SyncController or NodeBuilder) then continues to pass
`.toClassic` because the param type demands it. These slipped sites are invisible to `sbt compile-all`
(they compile fine) and to the existing bridge-count grep (they ARE bridge sites, just wrongly categorised).

**Audit prompt:**
```
You are auditing the fukuii codebase for spawn-site `.toClassic` slippage:
cases where a Typed actor's constructor param still declares a Classic `ActorRef`
parameter, forcing the Typed caller to write `typedRef.toClassic` at the spawn site
even though both sides are Typed.

Step 1 — Find all Typed actors that have Classic ActorRef constructor params:

cd /media/dev/2tb/dev/fukuii

# Find case classes / classes that extend Behavior but have ActorRef (Classic) params
grep -rn "ActorRef\b" src/main/ --include="*.scala" \
  | grep -v "typed\.ActorRef\|ActorRef\[" \
  | grep -v "//.*ActorRef"

# Cross-reference: which of those files also have Behaviors / extends AbstractBehavior?
# The overlap is a Typed actor with Classic params.

Step 2 — For each hit, determine:
  a. Is this actor a Typed Behavior (Behaviors.receive, AbstractBehavior, ctx.spawn)?
  b. Does it have a Classic ActorRef param in its constructor or Props?
  c. Is there a caller that passes `.toClassic` to fill that param?
  d. Is the `.toClassic` truly necessary (child is still Classic) or is it slippage?

Step 3 — Produce a table:

| Actor | File:line | Param name | Type | Root cause | Removable now? | Tracked in? |
|-------|-----------|------------|------|-----------|----------------|-------------|
| FastSync | FastSync.scala:42 | syncController | ActorRef | externalAdapter passed as Classic | YES | §8k-G2 |
| SNAPSyncController | SNAPSyncController.scala:88 | syncController | ActorRef | Still Classic actor | NO (SNAP1) | CHASE-QUEUE |

Step 4 — Flag any sites NOT already tracked in §8k-G2 or CHASE-QUEUE.
  These are new gaps. Add them to CHASE-QUEUE with type CLASSIC.

Step 5 — Also run the general bridge census to see if the count has improved:

  grep -rn "\.toClassic\b" src/main/ --include="*.scala" | grep -v "//.*toClassic" | wc -l
  # Compare against §8k-R1 baseline of ~130 production sites.
  # Document delta and which sprints caused which reductions.

Output a short report: gaps found, gaps already tracked, new gaps to add.
```

**Expected output:** Short gap report + any new CHASE-QUEUE entries.

**MANDATORY final steps:**
1. Add any new untracked gaps to CHASE-QUEUE with type CLASSIC
2. `git commit -m "docs(8k-R2): post-migration gap audit — <N> new gaps found"` (docs-only commit)
3. **DELETE §8k-R2**

---

#### §8k-B — Post-CAPSTONE: Final classic bridge verification sweep

**Agent:** PRISM (verification only)
**Risk:** LOW — read-only final check
**Gate:** ~~§8k-I complete~~ ✅ `4613e398f` AND CAPSTONE merged. Run §7d artifact audit first (they overlap).

**Background:**
After §8k-A through §8k-I, only 4 intentional TCP permanent bridges should remain.
This sprint verifies that claim and deletes the `pekko.actor.typed.scaladsl.adapter` imports
that are no longer needed anywhere outside the TCP path.

**Expected state after §8k-A–I:**
- `grep -rn "\.toClassic" src/main/ --include="*.scala" | grep -v "//"` → 4 lines only (TCP)
- `grep -rn "toClassic\|toTyped" src/main/ --include="*.scala" | wc -l` → ≤ 4
- `import org.apache.pekko.actor.typed.scaladsl.adapter` → only in TCP-path files

**Prompt (run AFTER §8k-I + CAPSTONE):**
```
§8k-A through §8k-I are complete. Verify the TCP floor:

Step 1 — grep for any remaining .toClassic / .toTyped outside of:
  ServerActor.scala, RLPxConnectionHandler.scala (TCP I/O — permanent)
Step 2 — If found: identify which sprint was supposed to clear it and create
  a §8k-J follow-up entry in DEFERRED-BACKLOG.md.
Step 3 — Delete all `import org.apache.pekko.actor.typed.scaladsl.adapter`
  lines from files that no longer use the adapter.
Step 4 — Run §7d artifact audit sweep (grep commands in §7d).
Step 5 — Run testEssential — confirm baseline holds.
Step 6 — git commit -m "chore(8k-B): remove adapter imports — TCP floor verified (4 bridges)"
```

---

## Part 8l: VM Tracer Model Modernization (research-first)

**Background:** `VM.scala:140` carries a `// scalafix:ok DisableSyntax.return` suppression from §8e-FORGE. The `return` exits `create()` before `tracer.foreach(_.onCallExit(...))` fires. Converting it to an expression would cause `onCallExit` to fire during an initcode-too-large abort — a behaviour change whose correctness is spec-dependent. Two open questions must be resolved before touching this site:

1. **Spec question:** Should `onCallExit` fire when `create()` aborts? ETC spec and core-geth `CaptureExit` are the reference. If yes → the `return` is a latent bug and the suppression is wrong. If no → the suppression is permanent and needs an explanatory comment.
2. **Design question:** Is the current `Option[VMTracer]` callback model the right Scala 3 / Pekko Typed shape? Alternatives: ADT event stream (`sealed trait VMEvent`), `given VMTracer` typeclass, or a Typed actor receiving trace messages.

---

#### §8l-R1 — FORGE: VM tracer model research + spec verdict

**Agent:** FORGE
**Risk:** ZERO — read-only research, no code changes
**Gate:** None — parallel-safe any time
**Purpose:** Answer the spec question at `VM.scala:140`, map the current tracer model, and produce a design recommendation before any code changes are made

**Steps:**
1. **Read** `VM.scala` in full — map every `tracer.foreach(...)` call site. For each: method name, event type (`onCallEntry`/`onCallExit`/`onCreate`/etc.), and whether it fires before or after any `return` in the same method.
2. **Read** core-geth at `reference-clients-evm/go-ethereum/core/vm/evm.go` and `interpreter.go` — specifically: does `CaptureExit` fire for a failed `create()` (e.g., initcode-too-large)? What arguments does it receive?
3. **Spec verdict** — record one of:
   - **SHOULD_FIRE** → the `return` is a latent tracer bug; the `scalafix:ok` suppression is incorrect. Implementation prompt §8l-I required.
   - **SHOULD_NOT_FIRE** → the `return` is correct; suppression is permanent. Update the annotation: `// scalafix:ok DisableSyntax.return — onCallExit must NOT fire on initcode-too-large abort (spec: create abort ≠ normal call exit)`.
4. **Tracer type inventory:** What is `VMTracer`? Interface, abstract class, or actor ref? Is it Classic or Typed? How is it threaded (constructor param, context, `given`)?
5. **Scala 3 / Pekko Typed assessment** — given the tracer type, propose the appropriate modernisation shape:
   - Interface/callback → `given VMTracer` typeclass, or ADT event stream
   - Classic actor → LOOM candidate; identify which subsystem sprint gates it
   - Already Typed → no structural change needed; spec fix at `:140` is sufficient
6. **Write** `.local/docs/vm-tracer-model.md`:
   ```markdown
   # VM Tracer Model — Research (§8l-R1)
   ## Spec verdict (SHOULD_FIRE / SHOULD_NOT_FIRE)
   ## core-geth CaptureExit reference behaviour
   ## Call site map (table: method | event | fires before/after return?)
   ## Current tracer type and threading
   ## Modernisation recommendation and proposed next step
   ```
7. `git add .local/docs/vm-tracer-model.md` → `git commit -m "docs(8l-r1): VM tracer model — spec verdict and design assessment"`
8. **Update this section** — add the spec verdict as a one-line note after the background block; add `§8l-I` implementation prompt below if SHOULD_FIRE or a redesign is warranted; mark as RESOLVED-PERMANENT-DEFER and delete this prompt if SHOULD_NOT_FIRE.
9. **DELETE §8l-R1**

---

## Recommended Sprint Sequence (post scala3-cleanup-june)

Two tracks run in parallel: **Primary** (blocking, sequential) and **Housekeeping** (parallel-safe,
fills test-wait downtime ~24 min per `testEssential` run). Housekeeping tasks have no gate
dependencies on each other and can be picked up in any order when the primary track is waiting.

### Primary Track (blocking — sequential)

| Sprint | Work | Agents | Gate |
|--------|------|--------|------|
| **Pekko migration** | Part 2: faucet → jsonrpc → transactions → consensus/mining | LOOM, FORGE | scala3-cleanup-june merged |
| **Network/sync Pekko** | S3→S4/S7→NET2→SNAP1→SNAP2→ROOT→CAPSTONE (see SPRINT-QUEUE.md) | LOOM, FORGE, HERALD | Part 2 complete |
| **→ CAPSTONE** | Root flip: ActorSystem[Nothing], bridge/adapter removal, Behavior[Any] narrowing | LOOM | All actors Typed |
| **7d — Artifact audit** | Post-CAPSTONE sweep: surviving Classic patterns, adapter imports, raw schedulers | PRISM, HERALD | CAPSTONE merged |
| **8k-B — Bridge elimination** | Post-CAPSTONE: remove all ~170 remaining `.toClassic`/`.toTyped` bridge calls now that every upstream actor is Typed | LOOM, PRISM | CAPSTONE + 7d done |
| **7a — ADT consolidation** | DONE `04615ad43` `4e8b42263` — see completed | — | ✅ |
| **7b — EventStream** | DONE `849c0dcf0`+`b35b35cf6` — see completed | — | ✅ |
| **7e — Design review** | DONE — see completed | — | ✅ |
| **7c — Supervision** | Explicit Behaviors.supervise per actor with typed failure strategies | PRISM, LOOM | 7a done |
| **8b — Opaque types** | Domain value type safety: BlockNumber, Hash, Address, Balance | MITHRIL, FORGE | Part 3a done |
| **8i — RLP derivation** | Replace handwritten product-type RLP codecs with derivation | MITHRIL, FORGE, EYE | Part 3a + R7 done |
| **Scala 3 idioms** | Part 3a implicit→given (198 files) | MITHRIL + scalafix | After Pekko migration |
| **Dep upgrades** | Part 4a JLine 4.x | — | Dedicated sprint |
| **Dep upgrades** | Part 4e Jackson 3 → then 4b logstash | — | Ecosystem gate |
| **Scala 3.9** | Part 5a | — | 3.9 release gate |
| **Constitution** | Part 5c | — | After 5a |

### Housekeeping Track (parallel-safe — fill test-wait downtime)

These tasks are independent of the primary track. Any can be started when `testEssential`
(~24 min) or `testStandard` (~30 min) is running and there's an unblocked file to fix.
No actor migration gate. Commit individually; do not bundle with primary-track migration commits.

| Task | Work | Agents | Effort |
|------|------|--------|--------|
**8g, 8k-R1 DONE** — see `completed/DEFERRED-BACKLOG.md`.

| Task | Work | Agents | Effort |
|------|------|--------|--------|
| **8e — ScalaFix expansion** | C2+TNHC DONE — see completed; **§8e-FORGE DONE 2026-06-24** (all 6 consensus files: 6 CLEAR + 9 DEFER w/ scalafix:ok) + **§8e-StackTrie DONE 2026-06-24** (`09307c5a7` — both DEFER sites CLEAR: `:120` node expr, `:462` var-result) + **§8e-BEACON DONE 2026-06-24** (`d78177bda` — 3 sites CLEAR: handleNewPayload, handleForkchoiceUpdated, priority-fee helper); 36 SSC gated (SNAP1) | BEACON / FORGE | **DONE** |
| **8l — VM tracer research** | §8l-R1 FORGE research: spec verdict on `VM.scala:140` tracer call + tracer model Scala 3 / Typed design assessment | FORGE | unblocked |
| **8j — Thread.sleep** | 2 live call sites (EthMiningServiceSpec:302, SubscriptionManagerSpec:249) — both NECESSARY; defer to §8a-retro | EYE | deferred to §8a |
| **8a-retro** | Batches 1–4 DONE — see completed. **Batch 5:** BlockFetcherSpec + PendingTxMgrSpec DONE `5ff14017b`; RegularSyncSpec → §9c. PeerActorSpec + RLPxConnectionHandlerSpec wait for Wave 3. | LOOM, EYE | ~2h |

### Research Threads (run before implementation; can overlap with primary track)

| Thread | Goal | Output doc | Agent |
|--------|------|-----------|-------|
| **R9** | §8k-R1: Classic interop inventory — map every `.toClassic`/`actorSelection`/bridge site to its root-cause classic actor and LOOM sprint | `classic-interop-audit.md` | PRISM |
| **R0** | Full codebase completeness audit (mandatory gate before Wave 2) | `codebase-completeness-audit.md` | PRISM, MITHRIL |
| **R1** | Network/sync Pekko migration plan (22 actors) | `network-sync-pekko-migration-plan.md` | HERALD, LOOM |
| **R2** | Test quality audit (Thread.sleep, coverage gaps, ignored tests) | `test-quality-audit.md` | PRISM, EYE |
| **R3** ✅ | Jackson ecosystem gate — DONE, see completed | — | — |
| **R4** | Scala 3.9 readiness (periodic — when 3.9 LTS appears) | Update `scala-39-upgrade.md` | MITHRIL, WRAITH |
| **R5** ✅ | EventStream pub/sub topology map — DONE, see completed | — | — |
| **R6** | Opaque type domain analysis (map BigInt/ByteString semantic roles) | Feeds 8b implementation | MITHRIL, FORGE |
| **R7** | RLP codec derivation safety analysis (safe-to-derive vs must-stay-manual) | Feeds 8i implementation | MITHRIL, FORGE |
| **R8** ✅ | Memory / resource retention audit — DONE, see completed | — | — |
| **R9** ✅ | IO threading model audit — DONE, see completed | — | — |

| Sprint | Work | Agents | Gate |
|--------|------|--------|------|
| **Pekko migration** | Part 2: all 4 subsystems DONE — see completed | — | ✅ |
| **Warning cleanup** | Part 1 remaining 87 non-E165 warnings | WRAITH | Externally gated |
| **Scala 3 idioms** | Part 3a implicit→given (198 files) | MITHRIL + scalafix | After Pekko migration |
| **Dep upgrades** | Part 4a JLine 4.x | — | Dedicated sprint |
| **Dep upgrades** | Part 4e Jackson 3 → then 4b logstash | — | Ecosystem gate |
| **Network/sync Pekko** | S3→S4/S7→NET2→SNAP1→SNAP2→ROOT→CAPSTONE (see SPRINT-QUEUE.md) | LOOM, FORGE, HERALD | Active sprint |
| **→ CAPSTONE** | Root flip: ActorSystem[Nothing], bridge/adapter removal, Behavior[Any] narrowing | LOOM | All actors Typed |
| **7d — Artifact audit** | DONE 2026-06-21 — see completed | — | ✅ |
| **7a — ADT consolidation** | DONE `04615ad43` `4e8b42263` — see completed | — | ✅ |
| **7b — EventStream** | DONE `849c0dcf0` + `b35b35cf6` — see completed | — | ✅ |
| **7e — Design review** | DONE — see completed | — | ✅ |
| **7c — Supervision** | Explicit Behaviors.supervise per actor with typed failure strategies | PRISM, LOOM | 7a done |
| **Scala 3.9** | Part 5a | — | 3.9 release gate |
| **Constitution** | Part 5c | — | After 5a |
| **extvm deletion** | Part 6a DONE `a948fda1d` — see completed | — | ✅ |

---

## Clearout Prompts (Unblocked Items)

Items below are actionable now on `scala3-cleanup-june` without external gates.
Each prompt can run independently. Commit individually.

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
**A3,A4,B4,C1–C4,D3,E3–E5e,F1 DONE** — see `completed/DEFERRED-BACKLOG.md`.

| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| E6 | Batch E | §8a-retro batch 5 — multi-system + TestActorRef specs (3 assessable, 2 Wave 3 gate) | Partial — RegularSyncSpec → §9c; BlockFetcherSpec + PendingTxMgr DONE `5ff14017b`; PeerActor + RLPx wait for Wave 3 |
| G1 | Batch G | §8a-retro-5b — DONE `5ff14017b` (specs migrated in 8a-retro multi-system commit; clearout follows) | — |
| ~~G2~~ | ~~Batch G~~ | ~~§8e-FORGE — 6 consensus `return` → expression conversions~~ | DONE 2026-06-24 — FORGE executed across all 6 files: 6 sites CLEAR (converted to if/else), 9 sites DEFER (`scalafix:ok DisableSyntax.return`: VM.scala tracer short-circuit, PrecompiledContracts KZG/BLS crypto + MODEXP guard, StackTrie MPT-mutation + loop comparator). Prior archive's "2/6 clear" assessment was inaccurate — BlockPreparator/StackTrie had real returns that were converted. |
| ~~G3~~ | ~~Batch G~~ | ~~§8e-BEACON — EngineApiController S3-D `return` → expression (2 sites)~~ | DONE 2026-06-24 — `d78177bda` (3 sites: handleNewPayload, handleForkchoiceUpdated, priority-fee helper; 16/16 EngineApiSpec ✅) |
| G4 | Batch G | §8d-A1 — BEACON: EngineApiService `Await.result` on CE3 compute thread | MEDIUM priority; BEACON gate; prompt in §8d above |
| G5 | Batch G | §8d-CONDUIT — CONDUIT: jsonrpc/ remaining IO boundary scan (Await/EC.global/blocking) | LOW priority; unblocked; 15-min scan; prompt in §8d above |
| G6 | Batch G | §8c-M4 — VAULT: DataSource close cache invalidation verify-or-by-design | LOW priority; VAULT gate; prompt in §8c above |
| ~~G7~~ | ~~Batch G~~ | ~~§8e-StackTrie — FORGE: StackTrie `:120`+`:462` DEFER re-assessment (2 `scalafix:ok` sites)~~ | DONE 2026-06-24 — `09307c5a7` (both CLEAR: `:120` node expr, `:462` var-result; see modernization-log/core/mpt.md) |
| G8 | Batch G | §8l-R1 — FORGE: VM tracer model research + spec verdict (read-only) | Parallel-safe; FORGE-only; unblocked |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

## Part 10: Test Suite Performance

### P7 — EYE/MITHRIL: Test timing audit + slow-test reduction — DONE — see completed/DEFERRED-BACKLOG.md

---

## Part 9: Dead Code — Deferred Wiring Candidates

Items identified during dead-code sweeps where the verdict was DEFER rather than DELETE.
See `agent-protocols/dead-code-review.md` for the full assessment protocol.

### 9a — SyncStartupStrategy extraction (from deleted AdaptiveSyncStrategy)

**Context:** `AdaptiveSyncStrategy.scala` (193 lines) was deleted in Part 8f as
unintegrated dead code. However, the design logic it contained addresses a real
gap: `SyncController.start()` selects sync mode from static config booleans with
no peer-count or latency pre-flight. If `doSnapSync=true` but fewer than 3 peers
are available, SNAP attempts and fails N times before reactive fallback triggers.

**What should be built:** A lightweight pure function:
```scala
def selectSyncMode(peerCount: Int, snapCapablePeers: Int, latencyMs: Long,
                   config: SyncConfig): SyncMode
```
This is NOT the full `AdaptiveSyncController` class (mutable state, strategy objects).
It is the decision logic only — extracted, tested as a pure function, wired into
`SyncController.start()` at the sync mode selection branch (~line 1387).

**Wiring point:** `SyncController.start()` — the 5-branch pattern-match on
`(isSnapSyncDone, isFastSyncDone, doSnapSync, doFastSync)` config booleans.
Add a pre-flight check: if `doSnapSync && snapCapablePeers < 3`, downgrade to
`doFastSync` mode rather than attempting SNAP and waiting for reactive failure.

**Benefit:** Reduces day-1 sync latency on low-peer-count or high-latency networks.
Turns "wait for N failures then fallback" into "check conditions upfront, start on
the right mode immediately."

**Prerequisite:** None. Not consensus-critical. Candidate for a standalone task
after the current sprint queue clears.

**Agent:** Sonnet (pure function + SyncController wiring, not consensus-critical)

**Prompt:**
> `SyncController.start()` selects sync mode via a 5-branch pattern-match on config booleans with no peer pre-flight. The deleted `AdaptiveSyncStrategy.scala` (removed in Part 8f) contained the right decision logic. Extract it as a pure function in `SyncController.scala` (or a companion object):
> ```scala
> def selectSyncMode(peerCount: Int, snapCapablePeers: Int, latencyMs: Long,
>                    config: SyncConfig): SyncMode
> ```
> Wire it into `SyncController.start()` at the 5-branch match (~line 1387): if `doSnapSync && snapCapablePeers < 3`, downgrade to `doFastSync`. Do not add mutable state or strategy objects — pure function only. Write a unit test covering all 5 branches (0 peers, 1 peer, 3 peers, snap-capable majority, fast-only config). Run `sbt compile-all` then `sbt "testOnly *SyncController*"` to verify.

---

### 9b — RegularSync Divergence-Path Spec Fix (gate OPEN — §8k-F done)

**Context:** CHASE-QUEUE "RegularSync divergence path EXCEPT" (cleared 2026-06-21) — HERALD audit confirmed the three-path fork recovery in `BlockImporter.scala` (`handleForkRecovery`) uses a blind 128-block rewind with no LCA knowledge. MESS makes >128-block forks near-impossible on ETC mainnet so this is latent-correctness, not active-risk. Gate was §8k-F (RegularSync Typed) — **now done** (`b24515637`).

**Fix spec:**
1. Confirm whether FSBA `replyTo: ActorRef[BranchResolverResponse]` was already wired (SNAP2 note in CHASE-QUEUE ~line 94)
2. Add `ResolvingFork` behavior to `BlockImporterLogic` — spawn `FastSyncBranchResolverActor` + handle `FinishedBranchResolution` response
3. Replace 4-line blind rewind in `handleForkRecovery` with actor spawn + response path
4. Re-enable/rewrite the divergence-path EXCEPT test in `RegularSyncSpec`

**Prompt (LOOM + EYE):**
> `RegularSync.scala` is now fully Typed (`b24515637`). The divergence path EXCEPT in `RegularSyncSpec` is now actionable (DEFERRED-BACKLOG §9b). Read `sync/regular.md`, `sync/fast.md` (FSBA), and CHASE-QUEUE cleared entry "RegularSync divergence path EXCEPT". Implement the `ResolvingFork` behavior in `BlockImporterLogic` and re-enable the test. Gate: none.
>
> **Pre-flight — pekko-typed-api.md P16 + pre-migration-checklist.md Step 13:** After wiring the FSBA spawn, confirm `FastSyncBranchResolverActor`'s constructor param declares `ActorRef[BranchResolverResponse]` (Typed), not Classic `ActorRef`. The spawn site must not write `.toClassic`. Run: `grep -n "ActorRef\b" BlockImporterLogic.scala | grep -v "typed\.\|ActorRef\["` — expected 0 hits.

**Size:** S. **Agent:** LOOM + EYE. **Priority:** LOW.

---

### 9c — RegularSyncSpec Full Migration (gate OPEN — §8k-F done)

**Context:** `RegularSyncSpec` was deferred because it required `RegularSync` to be Typed first. **§8k-F (`b24515637`) opens the gate.**

**Fix:** Migrate `RegularSyncSpec` from `Resource[IO, ActorSystem]` lifecycle to `ScalaTestWithActorTestKit`. Restructure teardown to use `testKit.system` + `testKit.shutdown()`.

**Prompt (LOOM + EYE):**
> `RegularSync.scala` is now fully Typed (`b24515637`). Migrate `RegularSyncSpec` from its `Resource[IO, ActorSystem]` lifecycle to `ScalaTestWithActorTestKit`. Read `node/testing-infra.md` §8a-retro batches for migration patterns. Verify 33/33 tests pass.
>
> **Pre-flight — pre-migration-checklist.md Step 13:** After migrating, verify no spawn-site slippage was introduced: `grep -n "ActorRef\b" RegularSyncSpec.scala | grep -v "typed\.\|ActorRef\["` — expected 0 hits. Also opportunistically check `SyncProtocol.SyncStatus` for enum candidacy (§3d residual — 5-min check while in sync/ territory).

**Size:** M. **Agent:** LOOM + EYE. **Priority:** MED — unblocks E165 TestProbe narrowing in this spec.

---

---

## Part 11: Full Test Suite Coverage Audit

**Goal:** 100% coverage of the test suite — no test permanently hidden behind an exclusion tag
without a documented verdict. Every excluded test gets one of: Fix & enable / Delete / Defer with
written reason. P7 covered only `testEssential`; this part closes the gap.

**Tag inventory at 2026-06-22:**
| Tag | Count | Currently excluded from | Intent (per ADR-017 / Tags.scala) |
|-----|-------|------------------------|----------------------------------|
| `SyncTest` | ~50+ tests, 8 files | ALL tiers | "blockchain synchronisation" — many are actually pure unit tests, mislabelled |
| `DisabledTest` | 9 tests, 5 files | ALL tiers | "temporarily disabled due to known issues — should be re-enabled" |
| `FlakyTest` | 8 tests, 3 files | ALL tiers | "investigate and fix but temporarily marked to avoid blocking CI" |
| `SlowTest` | ~30 tests, 8 files | testEssential only | Legitimately slow (PoW/DAG CPU) — some may be mislabelled |
| `StressTest` | unknown | ALL tiers | Long-running stress — not yet assessed |
| `ManualTest` | unknown | ALL tiers | Requires human verification — may be deletable or automatable |

**Run order — this section:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|----------------|
**E1–E4 DONE** — see `completed/DEFERRED-BACKLOG.md`.

| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|----------------|
| E5 | Batch E | P12 Tag taxonomy + build target architecture review | Yes (read-only) |

---

### P8 — EYE: SyncTest tag audit — rescue mis-tagged unit tests — DONE — see completed/DEFERRED-BACKLOG.md

---

### P9 — EYE/MITHRIL: DisabledTest audit — fix, wire, or delete — DONE — see completed/DEFERRED-BACKLOG.md

---

### P10 — EYE/MITHRIL: FlakyTest root cause audit — fix or delete — DONE — see completed/DEFERRED-BACKLOG.md

---

### P11 — EYE: testStandard baseline + SlowTest tag audit — DONE — see completed/DEFERRED-BACKLOG.md

---

### P11b — Docs: migrate `fukuii-test-timing.md` → `test-quality-log.md` — COMPLETE 2026-06-23 — see `completed/DEFERRED-BACKLOG.md`

---

### P12 — MITHRIL: Tag taxonomy + build target architecture review + gaps

**Agent:** MITHRIL (read-only analysis → build.sbt edits for new targets)
**Prerequisite:** P8, P9, P10 complete (stable tag counts before auditing the architecture).
**Parallel-safe:** Yes (read-only except for new `addCommandAlias` additions).

**Architectural principle (read this first):**

> Test groupings must reflect reality, not hide failures. An exclusion tag is only legitimate
> when the test is *correctly categorised* as non-essential (too slow, genuine live-network
> requirement, compliance-only). An exclusion that exists because a test is *broken* is a
> workaround that buries technical debt. `testEssential` must include everything that is
> essential — if it's too slow for the essential gate, create a `testFast` tier for speed,
> not an exclusion that erases the test from CI entirely.

**Legitimate exclusions (keep):**
- `SlowTest` from `testEssential` — correct: too slow for daily commit gate; runs in Standard
- `BenchmarkTest` / `EthereumTest` from Essential+Standard — correct: 3-hour compliance suite
- `IntegrationTest` from `testEssential` — assess: may belong there if actor system tests are fast enough

**Workaround exclusions (must go to zero after P8–P10):**
- `SyncTest` from ALL tiers — workaround for broken/flaky sync tests; not a categorisation
- `DisabledTest` from ALL tiers — workaround for broken tests that need fixing or deleting
- `FlakyTest` from ALL tiers — workaround for non-deterministic tests that need fixing or deleting

**Context:** The tag system has grown organically. After P8–P10 clean up individual tests,
this prompt steps back and asks: is the *architecture* right? Four questions:

1. **Tag coverage gaps** — Which tags defined in `Tags.scala` have no corresponding `sbt`
   inclusion command? (`ConsensusTest`, `StateTest`, `RPCTest`, `OlympiaTest`, etc.)
2. **Tier completeness** — Are all tiers running the right submodule tests? (`testStandard`
   skips `rlp / test`, `bytes / test`, `crypto / test` — is that intentional?)
3. **Missing tiers / new build targets** — Should new tiers exist? What would `testConsensus`,
   `testRPC`, `testMining` enable? Should `testFast` exist as a <2-min subset of testEssential
   for pre-commit hooks, freeing `testEssential` to be truly comprehensive?
4. **Workaround exclusion removal** — Capstone of P8+P10. Remove every workaround exclusion
   from the tier definitions. `testEssential` must include all essential tests. Sync is
   essential. RPC is essential. State is essential. If a test in those domains is currently
   excluded because it's broken, fixing it (P8/P9/P10) is the prerequisite — not permanent
   exclusion.

**Known gaps to assess:**

| Issue | Current state | Proposed fix |
|-------|--------------|--------------|
| **`-l SyncTest` in ALL tiers** | Core sync logic has zero CI coverage — a workaround, not a design | **Remove from all tiers** once P8+P10 complete; sync tests belong in testEssential |
| `ConsensusTest` tag — no sbt target | Tests included in tiers but can't be run in isolation | Add `testConsensus` alias: `-n ConsensusTest` |
| `RPCTest` tag — no sbt target | Same problem for JSON-RPC layer tests | Add `testRPC` alias: `-n RPCTest` |
| `StateTest` tag — no sbt target | Same for state management | Add `testState` alias: `-n StateTest` |
| `OlympiaTest` tag — no sbt target | Fork-specific tests can't be run in isolation | Add `testOlympia` alias: `-n OlympiaTest` |
| `testStandard` skips rlp/bytes/crypto submodules | Possible coverage gap | Assess whether these submodule tests are covered elsewhere |
| `testMining` — no dedicated target | Ethash/PoW tests scattered across SlowTest+ConsensusTest | Consider adding `testMining` alias for CI on mining-heavy PRs |
| `StressTest` / `ManualTest` — defined but unused | No tests use them, no commands reference them | Assess: forward-declared or dead tag definitions? |
| `testAll` runs bare `test` without submodule isolation | `testAll` and `testComprehensive` overlap | Confirm no double-execution or gaps |

**Steps:**

0. **Exclusion legitimacy audit** — For every `-l TAG` in every tier definition, classify:
   ```
   testEssential excludes:  SlowTest | IntegrationTest | SyncTest | DisabledTest | FlakyTest
   testStandard excludes:   BenchmarkTest | EthereumTest | SyncTest | DisabledTest | FlakyTest
   testComprehensive excludes: SyncTest | FlakyTest | DisabledTest
   ```
   For each: answer "Is this exclusion *categorisation* (the test genuinely does not belong
   here) or *workaround* (the test belongs here but is broken)?"

   | Tag excluded | Type | Verdict after P8–P10 |
   |-------------|------|----------------------|
   | `SlowTest` from testEssential | Categorisation | Keep — legitimately slow |
   | `IntegrationTest` from testEssential | Assess | May belong; actor tests can be hermetic |
   | `BenchmarkTest` from Essential+Standard | Categorisation | Keep — 3h compliance suite |
   | `EthereumTest` from Essential+Standard | Categorisation | Keep — 3h compliance suite |
   | `SyncTest` from ALL tiers | **Workaround** | **Remove after P8+P10** |
   | `DisabledTest` from ALL tiers | **Workaround** | **Remove after P9 (no tests left disabled)** |
   | `FlakyTest` from ALL tiers | **Workaround** | **Remove after P10 (no tests left flaky)** |

   The end state: `testEssential` excludes ONLY `SlowTest`, `BenchmarkTest`, `EthereumTest`
   (legitimate speed categorisations), nothing else. `FlakyTest`, `DisabledTest`, `SyncTest`
   disappear from the exclusion lists because no tests carry those tags any more.

   If `IntegrationTest` from `testEssential` is also a workaround (actor tests that are actually
   fast and hermetic), reschedule those tests too.

1. **Tag usage census** — For each tag in `Tags.scala`, count actual usages:
   ```bash
   for tag in UnitTest FastTest IntegrationTest SlowTest EthereumTest BenchmarkTest StressTest CryptoTest RLPTest VMTest NetworkTest MPTTest StateTest ConsensusTest RPCTest DatabaseTest SyncTest FlakyTest DisabledTest ManualTest EthSmoke OlympiaTest; do
     count=$(grep -rn "taggedAs.*$tag\|$tag," src/test/ --include="*.scala" | grep -v "import\|object $tag\|//\|class.*Tag" | wc -l)
     echo "$tag: $count"
   done
   ```

2. **Submodule test coverage** — Run each submodule standalone and check counts:
   ```bash
   sbt "rlp / test" 2>&1 | grep "Tests:" | tail -1
   sbt "bytes / test" 2>&1 | grep "Tests:" | tail -1
   sbt "crypto / test" 2>&1 | grep "Tests:" | tail -1
   ```
   Confirm these are covered by `testEssential` (they are — lines 526-528) but NOT by
   `testStandard` (line 540 — only `testOnly` without submodule prefix). Document this gap.

3. **New build targets** — For each proposed new alias, verify the tag is actually used by
   enough tests to warrant a dedicated command (threshold: ≥3 tests). Propose the `addCommandAlias`
   line for each justified target.

4. **Tier gap fix proposal** — For `testStandard`, determine if adding submodule test runs is
   needed or if the existing `testEssential` coverage is sufficient. If a gap exists, propose
   adding `; rlp / test ; bytes / test ; crypto / test` to `testStandard`.

5. **Dead tag removal** — If `StressTest` or `ManualTest` have 0 usages, either:
   - Remove from `Tags.scala` if no near-term plan to use them (clean up dead definitions)
   - Add a comment in `Tags.scala` marking them as "reserved for future use" if there's a plan

6. **Write a tag taxonomy document** at `fukuii/.local/docs/test-tag-taxonomy.md`:
   ```markdown
   # Test Tag Taxonomy
   | Tag | Usage count | sbt command | Tier coverage | Notes |
   ```
   One row per tag. This becomes the authoritative reference for tagging new tests.

7. **Remove `-l SyncTest` from all tier definitions** (capstone step — only after P8+P10 complete):
   - Confirm zero SyncTest tests remain without a proper tier tag (UnitTest or IntegrationTest).
   - Edit `build.sbt` lines 525, 540, 557: remove `-l SyncTest` from each `testOnly` invocation.
   - Remove the build.sbt comment block explaining the exclusion (the workaround is gone).
   - Run `testEssential` to confirm the newly-included sync tests pass and the count is correct.
   - Commit: `"build: remove -l SyncTest exclusion — sync tests now in testEssential (P8+P10 complete)"`

8. **Add justified `addCommandAlias` entries** to `build.sbt` — place after the existing domain
   aliases. Include a comment explaining the purpose of each.

**Target end state for `testEssential` in `build.sbt`:**
```scala
// BEFORE (workarounds present):
testOnly -- -l SlowTest -l IntegrationTest -l SyncTest -l DisabledTest -l FlakyTest

// AFTER (only legitimate categorisations remain):
testOnly -- -l SlowTest -l BenchmarkTest -l EthereumTest
```
If `IntegrationTest` is also found to be a workaround (actor tests that are hermetic and fast),
it is removed too. If a `testFast` tier is warranted (e.g., pre-commit hook, <2 min), it is
added as a new alias — not as a reason to exclude essential tests from `testEssential`.

**Verification:** `sbt compile-all` clean after all `build.sbt` edits. `testEssential` passes
with sync tests included and a higher test count than the P7 baseline. `sbt testConsensus` (and
other new targets) each find ≥3 tests. Zero `-l SyncTest`, `-l DisabledTest`, `-l FlakyTest`
lines remain in any tier definition.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add build.sbt` → `git commit -m "build(p12): add domain test targets (testConsensus, testCrypto, testVM, testNetwork)"` — commit 1: new aliases only
3. `SHA1=$(git rev-parse --short HEAD)` — capture SHA for commit 1
4. `git add build.sbt <any test files re-tagged>` → `git commit -m "build(p12): remove workaround exclusions from testEssential — SyncTest/DisabledTest/FlakyTest"` — commit 2: exclusion removal (only after P8+P10 complete)
5. `SHA2=$(git rev-parse --short HEAD)` — capture SHA for commit 2
6. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E5 → `| ~~E5~~ | ... | ✅ DONE [date] — $SHA1 (aliases), $SHA2 (exclusion removal) |`
7. Write `test-tag-taxonomy.md` at `.local/docs/`
8. Update `test-quality-log.md` with new testEssential count and the clean exclusion list
9. Update MEMORY.md `test-quality-log.md` entry with final test count
10. `git add .claude/ .local/docs/test-tag-taxonomy.md .local/docs/test-quality-log.md` → `git commit -m "docs(p12): clearout — $SHA1 $SHA2"`

**Rejection criteria:** Removing a workaround exclusion before the underlying tests are fixed
(P8+P10 must be complete first). Adding a build target for a tag with 0–1 test usages.
Introducing a new exclusion that is a workaround — if a test is broken, fix or delete it,
do not exclude it. Tiers must reflect reality.

---

## Part 12: Pre-Olympia Consensus Correctness Gate

---

## Part 15: P9 DisabledTest Deferred — Resolution Prompts

Deferred during P9 audit (2026-06-23, commit `86c76fd4e`). Four targeted fixes listed below.
The `handleRegularSyncMsg` production bug (SyncController:895-897) is tracked under P10 (F7).

---

## Part 16: ETH69/ETH70 PoW Safety — Hardening (P1/P2)

**Source:** Wire Protocol audit 2026-06-23 — `.local/Wire-Protocol-Modernization/eth69-pow-safety-audit.md`
**P0 items** (G1 TD gate, G5 backlink) → `SPRINT-QUEUE.md §ETH69-A/B` — implement first.
**P1 items** (G2 Tier3 accuracy, G6 BRU) and **P2 items** (G3/G4 archive node) tracked here.
**Gate:** §ETH69-A and §ETH69-B must be complete before hardening items are useful.

---

### §ETH69-C — MITHRIL: BlockchainReader Tier3 rolling-window median (G2, P1)

**Agent:** MITHRIL
**Risk:** MEDIUM — changes Tier3 TD estimation formula; affects peer ranking and chainWeight accuracy
**Gate:** §ETH69-A complete (TD gate in place) + §ETH69-B complete
**File:** `src/main/scala/com/chipprbots/ethereum/domain/BlockchainReader.scala:217-221`

**Background:**
Tier3 `POW_SCALING` uses current head difficulty as the marginal rate in:
```scala
val rate = rollingWindowDiff(head, ourBestTD)  // 10K-block rolling avg, fallback to head.difficulty
val gap  = (latestBlock - ourBestNum).max(BigInt(0))
val estimatedTD = ourBestTD + rate * gap
```
Under flex-load difficulty oscillation (±50% swing observed in `ETH69OscillationChainWeightSpec.scala:142-156`),
this causes Tier3 to systematically overestimate or underestimate TD by 10-50%. Archive nodes
receiving an inflated Tier3 estimate are never corrected (monotonic guard + no NewBlock sent).
Peers arriving mid-oscillation trough are deprioritised unfairly.

**Steps:**
1. **Read** `BlockchainReader.scala:199-248` in full — understand `rollingWindowDiff`,
   `resolveETH69ChainWeight`, and what `head` / `ourBestTD` are.
2. **Read** `ETH69OscillationChainWeightSpec.scala` — understand existing oscillation test cases
   and what accuracy targets they assert.
3. **Implement** a 1,000-block rolling-median difficulty store:
   - On each new block import (hook into the existing block-update path), record
     `header.difficulty` in a ring buffer of size 1,000.
   - Expose `rollingMedianDifficulty: BigInt` as a new `BlockchainReader` method.
   - Replace `rollingWindowDiff(head, ourBestTD)` call in Tier3 with `rollingMedianDifficulty`
     (fallback to `head.difficulty` if buffer is not yet full).
4. **Add test cases** to `ETH69OscillationChainWeightSpec`:
   - Verify Tier3 estimate variance is < ±20% under 50% oscillation (rolling median dampens swings)
   - Verify rolling median correctly averages out high/low difficulty alternation
5. `sbt "testOnly *BlockchainReader* *ETH69*"` after changes.

**Verify:**
```bash
grep -n "rollingWindowDiff\|rollingMedian\|rate = " \
  src/main/scala/com/chipprbots/ethereum/domain/BlockchainReader.scala

sbt "testOnly *ETH69Oscillation*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../domain/BlockchainReader.scala src/test/.../ETH69OscillationChainWeightSpec.scala`
3. `git commit -m "fix(sync): ETH69 Tier3 POW_SCALING — rolling-median difficulty reduces estimate variance (G2)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(eth69-c): clearout — $SHA"`
5. **DELETE §ETH69-C**

---

### §ETH69-D — MITHRIL: Tier3 accuracy telemetry (P1)

**Agent:** MITHRIL
**Risk:** LOW — instrumentation only, no logic change
**Gate:** §ETH69-A complete
**File:** `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala:794-799`

**Background:**
When an ETH69 peer sends a `NewBlock`, `updateChainWeight` directly replaces the peer's
chainWeight with the NewBlock TD — no monotonic guard. This is the only moment where the
**actual** TD is revealed after a Tier3 estimate. Capturing the estimate-vs-actual delta
at this point provides the data needed to tune the Tier3 formula and detect systematic bias.

**Steps:**
1. **Read** `NetworkPeerManagerActor.scala:789-810` — understand `updateChainWeight` and how
   `NewBlock.totalDifficulty` and `initialPeerInfo.chainWeight` are accessible together.
2. **Add logging** at the point of NewBlock TD replacement (inside `updateChainWeight`):
   ```scala
   case newBlock: ETHPackets.NewBlock =>
     val prevTD      = initialPeerInfo.chainWeight.totalDifficulty
     val actualTD    = newBlock.totalDifficulty
     val delta       = actualTD - prevTD
     val deltaPercent = if (prevTD > 0) (delta * 100) / prevTD else BigInt(0)
     log.debug(
       "ETH69_TIER3_ACCURACY: peer={} prevTD={} actualTD={} delta={} deltaPercent={}%",
       initialPeerInfo.remoteStatus.bestHash,
       prevTD, actualTD, delta, deltaPercent
     )
     initialPeerInfo.copy(chainWeight = ChainWeight.totalDifficultyOnly(newBlock.totalDifficulty))
   ```
3. **No test change required** — this is debug logging only. Confirm it compiles.
4. `sbt compile-all` to confirm no errors.

**Verify:**
```bash
grep -n "ETH69_TIER3_ACCURACY\|deltaPercent" \
  src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala
# Must see the log line

sbt compile-all
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../network/NetworkPeerManagerActor.scala`
3. `git commit -m "feat(telemetry): ETH69 Tier3 estimate-vs-actual TD logging on NewBlock (G2 instrumentation)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(eth69-d): clearout — $SHA"`
5. **DELETE §ETH69-D**

---

### §ETH69-E — MITHRIL: Archive node monotonic guard exemption (G3/G4, P2)

**Agent:** MITHRIL
**Risk:** LOW — refinement to chainWeight update guard; no consensus impact
**Gate:** §ETH69-C complete (rolling-median in place provides more stable Tier3 before disabling guard)
**Files:** `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala:841-851, 335-366`

**Background:**
Non-mining peers (archive nodes, light-mode relayers) on ETH69 never emit NewBlock.
Their chainWeight is set at handshake via Tier3 POW_SCALING and can only be updated via
the periodic `RefreshPeerBestBlocksTick` path (every ~5 min, lines 335-366), which calls
`resolveETH69ChainWeight` and applies:
```scala
val isImprovement = cw.totalDifficulty > updated.chainWeight.totalDifficulty
if isImprovement && source != "COLD_START" then updated.withChainWeight(cw)
else updated
```
The monotonic guard (`isImprovement`) prevents downward corrections. If Tier3 overestimated
at handshake (e.g., peer arrived during a difficulty spike), the chainWeight is permanently
inflated for archive nodes. They are ranked higher than honest active peers in some contexts.

**Steps:**
1. **Read** `NetworkPeerManagerActor.scala:823-867` (`updateMaxBlock` function) in full.
2. **Read** `NetworkPeerManagerActor.scala:335-366` (`RefreshPeerBestBlocksTick` path).
3. **Implement archive node detection:** Track `lastMaxBlockNumber` per peer across
   consecutive `RefreshPeerBestBlocksTick` probes. If `maxBlockNumber` has not advanced
   in N consecutive probes (N=3, configurable), classify peer as "static" (not mining).
4. **Exempt static peers from the monotonic guard** in the Tier3 re-resolve path:
   ```scala
   val isPeerStatic = consecutiveUnchangedProbes(peerId) >= 3
   val shouldUpdate = (isImprovement || isPeerStatic) && source != "COLD_START"
   if shouldUpdate then updated.withChainWeight(cw)
   else updated
   ```
5. **Write tests** in NetworkPeerManagerActorSpec:
   - Mining peer (maxBlockNumber advances each probe) → monotonic guard active
   - Archive peer (maxBlockNumber unchanged 3× probes) → downward Tier3 correction allowed
   - Archive peer corrects from inflated Tier3 estimate to accurate actual TD

**Verify:**
```bash
grep -n "isImprovement\|consecutiveUnchanged\|isPeerStatic" \
  src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala

sbt "testOnly *NetworkPeerManager*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../network/NetworkPeerManagerActor.scala src/test/.../NetworkPeerManagerActorSpec.scala`
3. `git commit -m "fix(sync): ETH69 archive node Tier3 chainWeight — exempt static peers from monotonic guard (G3/G4)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(eth69-e): clearout — $SHA"`
5. **DELETE §ETH69-E**
