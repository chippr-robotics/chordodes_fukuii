# Fukuii Modernization — Deferred Backlog

**Last updated**: 2026-06-25 (§ETH-T4-D FIXED — `deductBlobGas` routes through `BlobGasUtils.getBlobGasPrice`; local `computeBlobBaseFee` deleted; `BlockPreparatorSpec` test added)
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
| 1 | `EngineApiService.scala:661` `Ordering.Iterable` (tx sort key — `(Seq[Byte], BigInt)`) | BEACON gate — **not a correctness concern** (T10 verdict 2026-06-24); fix: supply explicit `Ordering` to silence warning without changing semantics |
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

**H2/H3 DONE** `4907406fe`, **H4+M1 DONE** `ef75a5608`, **M4 DONE (by-design) 2026-06-24** — see `completed/DEFERRED-BACKLOG.md`.

---

### 8d — IO Threading Model Follow-Up ✅ DONE 2026-06-24 — see `completed/DEFERRED-BACKLOG.md §8d`

### 8d-J — CONDUIT: jsonrpc IO boundary fixes ✅ DONE 2026-06-24 — see `completed/DEFERRED-BACKLOG.md §8d-J`

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
| E — `externalAdapter.toClassic` in SyncController | ✅ DONE | §8k-G3+G3-SSC narrowed all child adapters; §8k-G4a–G4e narrowed all remaining consumers (FCM, NPMA, PHB); §8k-G4-FINAL deleted externalAdapter. See `completed/DEFERRED-BACKLOG.md §8k-G4`. | — | — |
| I — TCP I/O bridge (RLPxConnectionHandler, ServerActor) | 4 | Akka TCP requires Classic `sender()` — **permanent** | N/A | — |

**Principle**: Each `.toClassic` call is a symptom, not the disease. The disease is an unconverted classic actor upstream. The fix strategy is: **migrate the upstream actor first (LOOM), then delete the bridge**. Bridges must never be removed before the upstream is converted — that produces a type error at the call site that blocks compilation.

---

#### §8k-J — PRISM: Re-run TCP floor verification after CAPSTONE ✅ DONE 2026-06-25

**Commit:** `<docs-only>` (net zero code changes — see below)
**Executed:** Post-CAPSTONE (all phases 2a-2g merged) + post-§8k-K (`a6b0304e7`).

**Step 1 — TCP floor NOT achieved (30 code bridges remain):**

`grep -rn "\.toClassic" src/main/ --include="*.scala" | grep -v "//"` → **30 code occurrences**
(down from 91 pre-CAPSTONE; expected ≤4 was overly optimistic — see root cause below).

Permanent TCP floor confirmed (3 `.toClassic` + 3 PoisonPill = 6 sites):
- `ServerActor.scala:70,77` — `ctx.system.toClassic` + `ctx.toClassic.actorOf` (TCP bind + bridge spawn)
- `RLPxConnectionHandler.scala:323` — `context.self.toClassic` (TCP write ack sender)
- `PeerManagerActor.scala:982,986,990` — `connection ! PoisonPill` (TCP-extension-owned actors; confirmed §8k-L 2026-06-24)

Root cause for remaining 24 bridges: two systemic dependencies noted at §8k-J time:
1. ✅ **§8k-M RESOLVED** — `PivotHeaderBootstrap` is now a `Behavior[Command]` (migrated in Group ROOT/CAPSTONE). The 10 SyncController bridges attributed to PHB at §8k-J time were bridges to FastSync (1), SnapSync (4), RegularSync (3), and recovery actors (2) — correctly attributed below. The 5 FastSync bridges attributed to PHB no longer exist (FastSync has no PHB references).
2. **PeerEventBusActor callers pass Classic refs via implicit adapter conversion** — not visible to grep;
   blocks adapter import removal in RegularSync, FastSyncBranchResolverActor, and others.

Remaining bridge clusters (24 code sites, excluding 6 permanent floor):

| Cluster | File | Sites | Unblock |
|---------|------|-------|---------|
| SyncController → FastSync forward | SyncController.scala | 1 | FastSync migration |
| SyncController → SnapSync/SSC forward | SyncController.scala | 4 | SNAPSyncController migration |
| SyncController → RegularSync forward | SyncController.scala | 3 | RegularSync migration |
| SyncController → recovery actors | SyncController.scala | 2 | BytecodeRecoveryActor/StorageRecoveryActor |
| PeerManager → PeerActor | PeerManagerActor.scala | 4 | PeerActor migration (TCP-adjacent) |
| PivotBlockSelector adapter | PivotBlockSelector.scala | 2 | PeerRequestHandler cleanup |
| BlockImporter self+adapter | BlockImporter.scala | 2 | Needs LOOM survey |
| Recovery → SSC adapters | BytecodeRecoveryActor + StorageRecoveryActor | 2 | SNAPSyncController Typed interface |
| PeerRequestHandler adapter | PeerRequestHandler.scala | 1 | §8k-K follow-through |
| SNAPSyncController bridge | SNAPSyncController.scala | 1 | SSC Typed interface widening |
| AkkaTaskOps Classic ask | AkkaTaskOps.scala | 1 | Typed ask helper (toClassic for ask compat) |
| PeerEventBusActor self-watch | PeerEventBusActor.scala | 1 | PEB Typed migration |
| NodeBuilder wiring | NodeBuilder.scala | 2 | PEB Typed migration |

**Step 3 — 0 adapter imports removable:**

Attempted removal from 2 candidates (FastSyncBranchResolverActor, RegularSync). Both failed
compile — same root cause: implicit `ClassicActorRef → ActorRef[PeerEventBusActor.Command]`
conversion via adapter (PEB interface still expects Classic callers).
- `MockedMiner`, `PoWMining`, `FaucetSupervisor` — `classicSystem.spawn()` extension method; must keep.
- All remaining 22 adapter imports confirmed load-bearing.

**Step 4 — §7d artifact audit (8-lens sweep):**

| Lens | Finding | Status |
|------|---------|--------|
| 1: `sender()` | Only doc comments — no code uses | ✅ Clean |
| 2: `context.actorOf` | Only RLPxConnectionHandler TCP floor | ✅ Clean |
| 3: `context.system.scheduler` | 5 Typed fetchers import Classic `Scheduler` (compatible via extends); 2 Classic TCP actors (expected) | ⚠️ Minor |
| 4: `Behavior[Any]` | Both grep hits are doc comments; actual impls are `Behavior[Command]` | ✅ Clean |
| 5: Unlogged catch-all | Pre-existing: `Behaviors.same` silent drops in SSA/FSBRA; `case _ => None` data patterns | ⚠️ Pre-existing CHASE |
| 6: Classic import leaks | `SNAPRequestTracker.scala` wildcard `pekko.actor.*` — pre-existing CHASE-QUEUE item | ⚠️ Pre-existing |
| 7: `Props.apply` | None outside TCP floor | ✅ Clean |
| 8: `preStart/postStop` | Only RLPxConnectionHandler TCP floor; AccountRangeWorker uses `postStopSignal` (Typed) | ✅ Clean |

No regressions vs §8k-B sweep.

**Step 5 — testEssential:** Not run — net zero code change; `sbt compile-all` confirmed clean.

#### §8k-N — MITHRIL: SyncController catch-all bridge elimination (10 sites)

**Agent:** MITHRIL
**Risk:** LOW-MEDIUM — no behaviour change on happy paths; catch-all arms only fire for messages outside the typed ADT
**Gate:** None — all target actors are already `Behavior[Command]`; SyncController already holds typed refs

**Background:**
All Classic children SyncController forwards to are already Typed. The 10 remaining `.toClassic.tell`
bridges in SyncController exist because each state has a catch-all arm:
```scala
case other => fastSync.toClassic.tell(other, noSender)       // runningFastSync — 1 site
case msg   => snapSync.toClassic.tell(msg, noSender)          // runningSnapSync — 3 sites + line 1628
case msg   => regularSync.toClassic.tell(msg, noSender)       // runningRegularSync/Backfill — 3 sites
bytecodeActor.foreach(_.toClassic.tell(msg, noSender))        // recovery state — 2 sites
storageActor.foreach(_.toClassic.tell(msg, noSender))
```
Refs are already narrowed (`TypedActorRef[FastSync.Command]`, `TypedActorRef[SNAPSyncController.Command]`,
`TypedActorRef[RegularSync.Command]`, `TypedActorRef[BytecodeRecoveryActor.Command]`,
`TypedActorRef[StorageRecoveryActor.Command]`). The bridges are needed only because the catch-all arm
forwards types that are NOT yet in the child's Command ADT.

**Steps:**

1. **Audit each catch-all arm** — for each of the 5 arms above, run:
   ```bash
   # example for FastSync catch-all (line 560)
   grep -rn "SyncController.*!" src/main/scala --include="*.scala" | grep -v "\/\/"
   # then check: what types flow into SyncController from Classic callers that would reach runningFastSync
   # and not be matched by the explicit cases before the catch-all?
   ```
   Identify the actual message types that flow through each catch-all. Check `unwrap(cmd)` and the
   `messageAdapter[Any]` registration in `apply()` to understand what can arrive.

2. **For `runningFastSync` catch-all (line 560):**
   Determine what `other` types arrive. Candidates: `FastSync.Done` (handled explicitly above),
   `SyncProtocol.*` (handled via `WrappedSyncProtocol`). If no types remain, the catch-all is dead —
   replace with `case other => log.warning("Unexpected msg in runningFastSync: {}", other); Behaviors.same`.

3. **For `runningSnapSync` catch-all (line 763) + `RegisterSnapSyncController` (line 1628):**
   Line 1628: `snapSync.toClassic` passed to NPMA. Check if NPMA accepts `TypedActorRef[SSC.Command]` —
   if so, drop `.toClassic`. Lines 763/1206: identify what `msg` types arrive; add to `SNAPSyncController.Command`
   as `WrappedExternal` variants or handle explicitly in SyncController.

4. **For `runningRegularSync` / `runningRegularSyncWithBackfill` catch-alls (lines 949, 1068, 1075):**
   Line 1068: explicitly forwards `GetStatus` — check if `RegularSync.Command` includes it
   (`type Command = SyncProtocol.RegularSyncCommand`; check whether `GetStatus` is a `RegularSyncCommand`).
   If not, add it. Lines 949/1075: general catch-all — identify types.

5. **For recovery catch-all (lines 2183-2184):**
   The comment says "Forward SNAP protocol responses to both active recovery actors." Check what SNAP
   protocol response types flow through `recoverySnapAdapter` and arrive here. Add them explicitly to
   `BytecodeRecoveryActor.Command` and `StorageRecoveryActor.Command` (or a shared `RecoveryCommand` trait),
   then replace the catch-alls with typed sends.

6. After each arm is resolved (dead catch-all → logged warning, or live → typed sends), run:
   ```bash
   sbt compile-all
   sbt "testOnly *SyncController*"
   sbt testEssential   # at end only
   ```

**Verify:**
```bash
sbt compile-all
sbt "testOnly *SyncController* *FastSync* *SNAPSync* *RegularSync* *BytecodeRecovery* *StorageRecovery*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage and commit per actor (risk-stratified: one commit per catch-all arm resolved)
3. Update §8k-J cluster table in working-docs to reflect resolved sites
4. **DELETE §8k-N when all 10 sites resolved**

---

#### §8k-O — MITHRIL: FastSync internal bridge elimination (`fastSyncClassicSelf` + PivotBlockSelector/StateStorageActor)

**Agent:** MITHRIL
**Risk:** MEDIUM — PivotBlockSelector is spawned as a child of FastSync; changing its constructor signature
touches FastSync (3 spawn sites), FastSync's spec, and PivotBlockSelector's own type
**Gate:** None — standalone; does not depend on §8k-N

**Background:**
FastSync (`Behavior[Command]`) has 5 internal `.toClassic` bridges:
- Line 186: `private val fastSyncClassicSelf: ActorRef = pivotResultAdapter.toClassic`
  — passed as `self` to Classic-signature collaborators
- Lines 296, 322, 710: `ctx.spawn(PivotBlockSelector(...)).toClassic`
  — PivotBlockSelector is spawned Typed but held as Classic `ActorRef`
- Line 415: `ctx.spawn(StateStorageActor()).toClassic`
  — StateStorageActor is spawned Typed but held as Classic `ActorRef`

`PivotBlockSelector` and `StateStorageActor` are the root cause: their constructors or the code holding
their refs uses Classic `ActorRef`. Eliminate by:
1. Narrowing `PivotBlockSelector`'s ref to `TypedActorRef[PivotBlockSelector.Command]`
2. Narrowing `StateStorageActor`'s ref similarly
3. Replacing `fastSyncClassicSelf` with typed self-ref where possible

**Steps:**

1. **Audit PivotBlockSelector:**
   ```bash
   head -30 src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/PivotBlockSelector.scala
   grep -n "extends Actor\|Behavior\[" \
     src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/PivotBlockSelector.scala
   ```
   If already `Behavior[Command]`: why does FastSync hold it as Classic? Check the `pivotBlockSelector ! ...`
   send sites — if they use Classic `!`, update to typed `pivotBlockSelector ! PivotBlockSelector.SelectPivotBlock`.
   Drop `.toClassic` from the spawn.

2. **Audit StateStorageActor:**
   ```bash
   head -30 src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/StateStorageActor.scala
   ```
   Same check — if Typed, drop `.toClassic` from spawn site (line 415).

3. **If PivotBlockSelector is Classic** — run LOOM pre-migration-checklist, then migrate:
   - Convert to `Behavior[Command]`
   - Update all 3 FastSync spawn sites to drop `.toClassic`
   - Update `PivotBlockSelectorSpec` if it uses `TestActorRef`

4. **Audit `fastSyncClassicSelf` usage** — lines 288, 314, 436, 702. These pass `fastSyncClassicSelf` to
   collaborator constructors as the reply-to address. Check each collaborator's constructor signature:
   ```bash
   grep -n "fastSyncClassicSelf" \
     src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala
   ```
   For each collaborator that accepts it: check if it can accept `TypedActorRef[FastSync.Command]` instead
   (or a narrower reply type). If yes, update the constructor param type and drop `fastSyncClassicSelf`.

5. Once `fastSyncClassicSelf` has no remaining uses: delete its definition (line 186).

**Verify:**
```bash
sbt compile-all
sbt "testOnly *FastSync* *PivotBlockSelector*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Commit PivotBlockSelector migration separately from FastSync narrowing
3. **DELETE §8k-O when all 5 sites resolved**

---

#### §8k-P — MITHRIL: PeerEventBusActor caller narrowing (unblocks adapter import removal in 22+ files)

**Agent:** MITHRIL
**Risk:** MEDIUM — PEB is used throughout sync, network, and node-builder; changing its constructor
signature is a broad refactor touching ~15 call sites
**Gate:** None — PEB is already `Behavior[Command]` (line 277); callers just pass Classic `ActorRef`

**Background:**
`PeerEventBusActor.behavior(): Behavior[Command]` is fully Typed. However, every actor that accepts
a `peerEventBus` parameter declares it as `peerEventBus: ActorRef` (Classic), not
`peerEventBus: TypedActorRef[PeerEventBusActor.Command]`. The adapter import's implicit conversion
(`ClassicActorRef → TypedActorRef[T]`) bridges the gap silently.

This is the primary reason adapter import removal fails across 22+ files: the implicit is load-bearing
at every constructor call that passes a Classic `peerEventBus` ref.

Sites confirmed requiring it (from §8k-J Step 3):
- `RegularSync.apply(... peerEventBus: ActorRef ...)` → `BlockFetcher` and `BlockBroadcasterActor`
- `FastSyncBranchResolverActor(... peerEventBus: ActorRef ...)` → `PeerListHelper`
- `NodeBuilder` — wires PEB at startup (lines 2 bridge sites)
- `PeerEventBusActor.scala` itself — 1 self-watch site

**Steps:**

1. **Survey all `peerEventBus: ActorRef` constructor params:**
   ```bash
   grep -rn "peerEventBus.*: ActorRef\b" src/main/scala --include="*.scala"
   grep -rn "peerEventBus.*: ActorRef\b" src/test/scala --include="*.scala"
   ```
   List all files. This is the full change surface.

2. **Update each constructor param** from `ActorRef` to `TypedActorRef[PeerEventBusActor.Command]`
   (add import alias: `import org.apache.pekko.actor.typed.ActorRef as TypedActorRef` is likely already
   present; add `import com.chipprbots.ethereum.network.PeerEventBusActor` where needed).

3. **Update all call sites** — wherever `peerEventBus` is passed, it must now be a
   `TypedActorRef[PeerEventBusActor.Command]`. Trace from `NodeBuilder` (the spawn site) down through
   each layer. NodeBuilder already spawns PEB — check if it holds the ref as Classic or Typed:
   ```bash
   grep -n "PeerEventBusActor\|peerEventBus" \
     src/main/scala/com/chipprbots/ethereum/nodebuilder/NodeBuilder.scala
   ```

4. **Attempt adapter import removal** after all params are narrowed:
   - For each file where the ONLY adapter import usage was the implicit `ClassicActorRef → TypedActorRef[PEB.Command]`:
     remove the import, run `sbt compile-all`, confirm clean.

5. **PeerEventBusActor self-watch site** (1 bridge): inside PEB itself. Check if it uses `.toClassic` for
   a death-watch; if so, convert to Typed `ctx.watch(peerRef)` directly.

**Verify:**
```bash
sbt compile-all
sbt "testOnly *PeerEventBus* *RegularSync* *FastSyncBranchResolver* *NodeBuilder*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Commit param narrowing + call site updates together (one commit per actor if large)
3. Commit adapter import removals as a separate pass (mechanical, Bucket A)
4. Update §8k-J cluster table: mark PEB+NodeBuilder sites resolved; note how many adapter imports removed
5. **DELETE §8k-P when all sites resolved and adapter imports cleaned**

---

## Part 8l: VM Tracer Model Modernization ✅ DONE 2026-06-24 — see `completed/DEFERRED-BACKLOG.md §8l-R1` + `§8l-I`

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
| **8l — VM tracer model** | §8l-R1 DONE `37c9d081b`/`5c2adeaaf` · §8l-I DONE — tracer fix in `VM.create()`; `scalafix:ok` suppression removed — see completed | FORGE | **✅ ALL DONE** |
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
| **R10** ✅ **ALL DONE** | **ETH/Sepolia assumption audit** — systematic hunt for ETC-first design leaking into ETH code paths. 10 threads: (1) fork dispatch `forBlock` vs `forTimestamp` ✅ → §ETH-T1-A/B/C, (2) PoW/PoS divergence guards ✅ → §ETH-T2-A, (3) EIP-1559 fee routing ✅ FIXED `f868b75a8`, (4) CL integration completeness ✅ → §ETH-T4-A/B/C/D, (5) chain ID hardcoding ✅ NO CODE FIXES — zero leakage; chainId/networkId config-driven throughout; EIP-155 signing config-bound; `TestService.scala:239 networkId=1` is retesteth-only (harmless), (6) VM tracer abort-path completeness ✅ 0 unbalanced paths — §ETH-T6-A (try/finally hardening) + §ETH-T6-B (EIP-2681 nonce-max) added, (7) test coverage ratio ETC vs ETH ✅ → §ETH-T7-A/B/C/D/E, (8) Sepolia config completeness ✅ NO CODE FIXES — all values correct; blob gas hardcoded correctly in BlobGasUtils; `network-type="eth"` present; treasury=0x0; audit doc had wrong Prague ts (1740434112→1741159776, fixed 2026-06-24), (9) SNAP sync ETH path ✅ → §ETH-T9-A/B/C/D, (10) Engine API Osaka edge cases ✅ → §ETH-T10-A/B/C/D. Findings feed an ETH sprint. **Prompt:** `.local/docs/eth-sepolia-assumption-audit.md`. Gate: none. | BEACON (T1,3,4,6,8,10), FORGE (T2,5,7), EYE (T9) |

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
| ~~G1~~ | ~~Batch G~~ | ~~§8a-retro-5b — specs migrated in 8a-retro multi-system commit~~ | ✅ DONE `5ff14017b` |
| ~~G2~~ | ~~Batch G~~ | ~~§8e-FORGE — 6 consensus `return` → expression conversions~~ | DONE 2026-06-24 — FORGE executed across all 6 files: 6 sites CLEAR (converted to if/else), 9 sites DEFER (`scalafix:ok DisableSyntax.return`: VM.scala tracer short-circuit, PrecompiledContracts KZG/BLS crypto + MODEXP guard, StackTrie MPT-mutation + loop comparator). Prior archive's "2/6 clear" assessment was inaccurate — BlockPreparator/StackTrie had real returns that were converted. |
| ~~G3~~ | ~~Batch G~~ | ~~§8e-BEACON — EngineApiController S3-D `return` → expression (2 sites)~~ | DONE 2026-06-24 — `d78177bda` (3 sites: handleNewPayload, handleForkchoiceUpdated, priority-fee helper; 16/16 EngineApiSpec ✅) |
| ~~G4~~ | ~~Batch G~~ | ~~§8d-A1 — BEACON: EngineApiService `Await.result` on CE3 compute thread~~ | DONE 2026-06-24 — verified already fixed: `IO.fromFuture` in place at lines 629–640 with explanatory comment; no code change needed |
| ~~G5~~ | ~~Batch G~~ | ~~§8d-CONDUIT — CONDUIT: jsonrpc/ remaining IO boundary scan (Await/EC.global/blocking)~~ | DONE 2026-06-24 — zero findings; all 55 `jsonrpc/` files clean (see `completed/DEFERRED-BACKLOG.md §8d`) |
| ~~G6~~ | ~~Batch G~~ | ~~§8c-M4 — VAULT: DataSource close cache invalidation verify-or-by-design~~ | DONE 2026-06-24 — by-design; Scaladoc comment on `RocksDbDataSource.close()`; verdict in `storage-rocksdb.md` |
| ~~G7~~ | ~~Batch G~~ | ~~§8e-StackTrie — FORGE: StackTrie `:120`+`:462` DEFER re-assessment (2 `scalafix:ok` sites)~~ | DONE 2026-06-24 — `09307c5a7` (both CLEAR: `:120` node expr, `:462` var-result; see modernization-log/core/mpt.md) |
| ~~G8~~ | ~~Batch G~~ | ~~§8l-R1/I — FORGE: VM tracer research + implementation~~ | DONE 2026-06-24 — R1 `37c9d081b`/`5c2adeaaf`; I impl complete; `VM.create()` tracer balanced; suppression removed |
| ~~H1~~ | ~~Batch H~~ | ~~**§8k-CQ1** — MITHRIL: Remove `GetKnownNodes` dead shim (KnownNodesManager.scala:117 + CommonFakePeer.scala:162)~~ | ✅ DONE `d4cc7a7fa` (2026-06-24) |
| ~~H2~~ | ~~Batch H~~ | ~~**§8k-CQ2** — MITHRIL: Fix `PeerActorSpec:429` PeerClosedConnection regression~~ | ✅ DONE `359692a3b` (2026-06-24) |
| J1 | Batch J | **§8k-N** — MITHRIL: SyncController catch-all bridge elimination (10 sites) — all target actors already Typed; audit each catch-all arm, extend ADTs or handle explicitly, replace `.toClassic.tell` | YES — standalone per catch-all arm |
| J2 | Batch J | **§8k-O** — MITHRIL: FastSync `fastSyncClassicSelf` + PivotBlockSelector/StateStorageActor bridge elimination (5 sites) — check if PBS/SSA already Typed; if so drop `.toClassic` from spawn | YES — standalone |
| J3 | Batch J | **§8k-P** — MITHRIL: PeerEventBusActor caller narrowing — update `peerEventBus: ActorRef` → `TypedActorRef[PEB.Command]` across ~15 constructors; enables adapter import removal in 22+ files | NO — broad refactor; run after J1/J2 compile-all passes |
| I1 | ETH Sprint (unblocked) | ~~**§ETH-T1-A**~~ ✅ ed4db9df9 · ~~**§ETH-T1-B**~~ ✅ 6f8f74708 · **§ETH-T2-A** `isPostMerge`→`isPoS` rename · ~~**§ETH-T4-A**~~ ✅ 02aaa05fc KZG trusted setup · ~~**§ETH-T4-C**~~ ✅ b934caffe EIP-4788 beacon roots bytecode · ~~**§ETH-T4-D**~~ ✅ f6cf7fb9c blob base fee unification · **§ETH-T6-A** VM tracer try/finally · **§ETH-T6-B** EIP-2681 nonce-max · **§ETH-T7-A** `EvmConfigTimestampForkSpec` · **§ETH-T7-C** `EngineApiVersionRejectionSpec` · **§ETH-T7-D** `BlockRangeUpdateDecodePathSpec` | Partial — each standalone |
| I2 | ETH Sprint (gated) | ~~**§ETH-T4-B**~~ ✅ maxFeePerBlobGas validation · **§ETH-T7-B** `Eip4788BeaconRootStorageSpec` · ~~**§ETH-T1-C**~~ ✅ `89863ac80` · ~~**§ETH-T9-A**~~ ✅ · ~~**§ETH-T9-B**~~ ✅ `4ac7e2842` · **§ETH-T9-C/D** SNAP sync ETH paths · **§ETH-T10-A/B/C/D** Engine API Osaka edge cases | NO — run after I1 items; gate conditions above |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

## Part 10: Test Suite Performance

### P7 — EYE/MITHRIL: Test timing audit + slow-test reduction — DONE — see completed/DEFERRED-BACKLOG.md

---

## Part 9: Dead Code — Deferred Wiring Candidates

Items identified during dead-code sweeps where the verdict was DEFER rather than DELETE.
See `agent-protocols/dead-code-review.md` for the full assessment protocol.

### 9a — SyncStartupStrategy extraction ✅ DONE 2026-06-24

**Commit:** `3140db465` — see `completed/DEFERRED-BACKLOG.md §9a` for full context.

---

### 9b — RegularSync Divergence-Path Spec Fix ✅ DONE 2026-06-24

**Context:** CHASE-QUEUE "RegularSync divergence path EXCEPT" (cleared 2026-06-21) — HERALD audit confirmed the three-path fork recovery in `BlockImporter.scala` (`handleForkRecovery`) uses a blind 128-block rewind with no LCA knowledge. MESS makes >128-block forks near-impossible on ETC mainnet so this is latent-correctness, not active-risk. Gate was §8k-F (RegularSync Typed) — **now done** (`b24515637`).

**Completed:**
- Items 1-3 were already done in `0d290019e` — `resolvingFork` behavior, FSBA wiring, `blindRewind` as fallback
- Item 4: divergence-path test written 2026-06-24 — `"rewind canonical chain to resolver LCA on BranchResolvedSuccessful (divergence path)"` in `RegularSyncSpec.scala` (96 lines, `UnitTest + SyncTest`)
- Pre-flight P16 + Step 13: confirmed correct. `compile-all` green. 34 tests / 30 pass (4 pre-existing status failures → §9d).

**Side-finding:** 4 `testCaseT` status tests (ClassCastException) — fixed in §9d (✅ DONE 2026-06-24, `69146a244`).

---

### 9c — RegularSyncSpec Full Migration ✅ DONE 2026-06-24

**Commit:** `57d638d49` — see `completed/DEFERRED-BACKLOG.md §9c` for full context and fix details.

---

### 9d — RegularSyncFixtures `getSyncStatus` Classic ask → Typed send ✅ DONE 2026-06-24

**Commit:** `69146a244` — see `completed/DEFERRED-BACKLOG.md §9d` for full context and fix details.

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
**E1–E5 DONE** — see `completed/DEFERRED-BACKLOG.md`.

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

### P12 — MITHRIL: Tag taxonomy + build target architecture review — DONE — see completed/DEFERRED-BACKLOG.md

---

## Part 12: Pre-Olympia Consensus Correctness Gate

---

## Part 15: P9 DisabledTest Deferred — Resolution Prompts

Deferred during P9 audit (2026-06-23, commit `86c76fd4e`). Four targeted fixes listed below.
The `handleRegularSyncMsg` production bug (SyncController:895-897) is tracked under P10 (F7).

---

## Part 10: ETH/Sepolia Assumption Audit Findings (2026-06-24)

Source: `.local/docs/eth-sepolia-assumption-audit.md` — Thread 1 (fork dispatch completeness).
Thread 3 (EIP-1559 fee routing) audited: functionally CORRECT — ETH base fee is burned, ETC base fee credited to treasury. Found one logging bug: `log.error` in `BlockPreparator.creditBaseFeeToTreasury` fired for every ETH/Sepolia block (treasury-address=0 is correct config, not an error). **FIXED `f868b75a8`** — guard added `&& networkType == NetworkType.ETC`. See `completed/DEFERRED-BACKLOG.md §ETH-T3-LOG`.

---

### §ETH-T6-A — BEACON: VM.scala tracer try/finally hardening

**Agent:** BEACON
**Risk:** LOW — no behaviour change on the happy path; only affects exceptional/unreachable abort
paths that currently skip `onCallExit`. ETC execution is identical (ETC uses the same `VM.scala`).
**Gate:** None — standalone fix, no sprint prerequisite
**Files:** `src/main/scala/com/chipprbots/ethereum/vm/VM.scala` — `call()` (lines 55-104) and
`create()` (lines 120-208)

**Background (Thread 6 of the ETH/Sepolia assumption audit, 2026-06-24):**

The Thread 6 audit found that `call()` and `create()` use a `val result = if/else` expression
pattern where `onCallEnter` fires at the top and `onCallExit` fires unconditionally after the
if/else. This is correct and balanced for every currently-reachable path. However, three defensive
guards (`require`/`throw`) are placed inside the if/else and can throw past the trailing `onCallExit`:

| # | Location | Guard | Reachable from opcode dispatch? |
|---|----------|-------|---------------------------------|
| C2 | `VM.create()` line 133 | `require(recipientAddr.isEmpty)` | NO — `CreateOp.exec` always passes `None` |
| C3 | `VM.create()` line 134 | `require(doTransfer)` | NO — `CreateOp.exec` always passes `true` |
| L2 | `VM.call()` line 71-73 | `throw IllegalArgumentException` | NO — `CallOp.exec` always passes `Some(toAddr)` |

These are the **same class of structural hole** as the §8l-I bug (EIP-3860 unbalanced path) but
are currently unreachable through normal sub-call opcode dispatch. The structural fix is to move
`onCallExit` into a `finally` block, making "every enter has an exit" true **by construction**.
This matches Besu's `traceContextExit` guarantee and also closes C9/L6 (unexpected `exec()` throw).

**Steps:**

1. **Read** `VM.scala` lines 55-104 (`call()`) and 120-208 (`create()`) in full — confirm exact
   line numbers of `tracer.foreach(_.onCallEnter(...))`, the result `val`, and the trailing
   `tracer.foreach(_.onCallExit(...))` in each method.

2. **In `call()` — wrap result computation in try/finally:**
   ```scala
   // BEFORE (simplified):
   if isSubCall then tracer.foreach(_.onCallEnter(...))
   val result = if !isValidCall then invalidCallResult else { ... }
   if isSubCall then tracer.foreach(_.onCallExit(...))
   result

   // AFTER:
   if isSubCall then tracer.foreach(_.onCallEnter(...))
   val result =
     try
       if !isValidCall then invalidCallResult else { ... }
     finally
       if isSubCall then tracer.foreach(_.onCallExit(...))
   result
   ```
   Remove the standalone trailing `if isSubCall then tracer.foreach(_.onCallExit(...))` line.

3. **In `create()` — same pattern** for the `val (result, newAddress) = ...` binding.
   Remove the standalone trailing `onCallExit` line.

4. **Confirm §8l-I fix is preserved** — the EIP-3860 check (lines 136-142) returns a value
   inside the `try` block; it does NOT throw. The `finally` block fires after it. §8l-I is
   structurally preserved; the wrapper additionally closes C2/C3/L2.

5. **Write a targeted regression test** in the existing VM tracer test suite covering the latent
   paths:
   - `create()` called with `recipientAddr = Some(addr)` at sub-call depth → `require` fires
     → verify `onCallExit` IS emitted (previously it was not)
   - `call()` called with `recipientAddr = None` at sub-call depth → `throw` fires
     → verify `onCallExit` IS emitted

**Verify:**
```bash
sbt compile-all
sbt "testOnly *VMTracer*" "testOnly *CallTracer*" "testOnly *VM*"
sbt testVM
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../vm/VM.scala` + test file(s)
3. `git commit -m "fix(vm): wrap call()/create() result in try/finally — onCallExit always fires even on require/throw abort (closes C2/C3/L2 latent tracer paths)"`
4. `SHA=$(git rev-parse --short HEAD)` → update `.local/docs/eth-sepolia-assumption-audit.md` Thread 6 entry with SHA
5. **DELETE §ETH-T6-A**

---

### §ETH-T6-B — BEACON: EIP-2681 nonce overflow enforcement at transaction layer

**Agent:** BEACON
**Risk:** LOW — nonce overflow is an extreme edge case (account nonce must reach 2^64 - 1);
however, the absence of this check is a spec deviation vs go-ethereum (`ErrNonceMax`).
Affects both ETC and ETH (same nonce semantics).
**Gate:** None — standalone investigation; no sprint prerequisite
**Files:**
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala`
- `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala` (stateless mempool path)

**Background (Thread 6 of the ETH/Sepolia assumption audit, 2026-06-24):**
The VM tracer abort-path audit (Thread 6) confirmed that nonce overflow (EIP-2681, post-Berlin)
is **not** enforced in the VM — it is a transaction-layer concern. EIP-2681 specifies that
transactions from an account with nonce `>= 2^64 - 1` must be rejected at the validator boundary.
go-ethereum enforces this with `ErrNonceMax` in `state_transition.go`. Fukuii's transaction
validator was not checked for this guard during Thread 6 (VM-only scope). This entry tracks
the transaction-layer investigation.

**Steps:**

1. **Search for nonce-max enforcement in Fukuii:**
   ```bash
   grep -rn "nonce\|Nonce" \
     src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala
   grep -rn "NonceTooHigh\|ErrNonceMax\|nonce.*max\|nonce.*overflow\|nonce.*64" \
     src/main/scala/ --include="*.scala"
   ```

2. **Compare against go-ethereum reference:**
   ```bash
   grep -n "NonceTooHigh\|ErrNonceMax\|nonce.*2\^64\|nonce.*overflow" \
     /media/dev/2tb/dev/reference-clients-evm/go-ethereum/core/state_transition.go
   ```

3. **If the check is missing** — add nonce-max validation to `StdSignedTransactionValidator`
   AND to the stateless mempool path (`SignedTransaction.getStatelessValidTransactions`):
   ```scala
   if tx.tx.nonce >= BigInt(2).pow(64) - 1 then
     Left(TransactionError.NonceTooHigh(tx.tx.nonce))
   else Right(())
   ```
   Add `NonceTooHigh` to the `TransactionError` sealed hierarchy if absent.

4. **Confirm ETC safety** — ETC uses the same nonce semantics (uint64). The fix applies
   to both chains equally; no chain-specific gating needed.

5. **Write tests** covering:
   - Nonce `== 2^64 - 2` → accepted
   - Nonce `== 2^64 - 1` → rejected with `NonceTooHigh`
   - Nonce `== 2^64` → rejected
   - Normal nonce (< `2^64 - 1`) → unaffected

**Verify:**
```bash
sbt compile-all
sbt "testOnly *StdSignedTransactionValidator*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../consensus/validators/std/StdSignedTransactionValidator.scala` + any error ADT files + test files
3. `git commit -m "fix(tx): enforce EIP-2681 nonce-max (>= 2^64-1) at transaction validator layer"`
4. `SHA=$(git rev-parse --short HEAD)` → add SHA to `.local/docs/eth-sepolia-assumption-audit.md` Thread 6 entry
5. **DELETE §ETH-T6-B**

---

### §ETH-T7-A — BEACON: Add `EvmConfigTimestampForkSpec` — ETH fork transition tests MISSING

**Agent:** BEACON
**Risk:** LOW — new test file only; no production code changes
**Gate:** None — standalone
**Files:**
- `src/test/scala/com/chipprbots/ethereum/vm/EvmConfigTimestampForkSpec.scala` (new file)
- Reference: `src/test/scala/com/chipprbots/ethereum/consensus/OlympiaEipEnablementSpec.scala` (ETC analog)

**Background (Thread 7, 2026-06-24):**
Zero test files call `EvmConfig.forBlock(blockNumber, timestamp, config)` with ETH/Sepolia configs. `OlympiaEipEnablementSpec` provides the exact ETC analog — calls `forBlock` with ETC configs and asserts opcode presence/absence at fork boundaries. No ETH equivalent exists. A regression in `forTimestamp()` dispatch would be invisible to `testEssential`. CLZ opcode (Osaka, `0x1e`) has no unit test.

**Steps:**
1. **Read** `OlympiaEipEnablementSpec.scala` in full — understand the pattern: synthetic `BlockchainConfigForEvm` with specific fork timestamps, call `EvmConfig.forBlock(0L, timestamp, config)`, assert flags and opcode presence.
2. **Read** `EvmConfig.scala:27-80` — confirm the 3-arg overload and what fork-specific flags/opcode sets are returned per timestamp.
3. **Create** `EvmConfigTimestampForkSpec.scala` with `taggedAs(UnitTest, ConsensusTest)`. One `it` block per fork boundary:
   - **Pre-Shanghai** (ts = 0): `eip3860Enabled = false`; PUSH0 (`0x5F`) absent
   - **At Shanghai**: `eip3860Enabled = true`; PUSH0 present
   - **At Cancun**: BLOBHASH (`0x49`) present; BLOBBASEFEE (`0x4A`) present; `isCancunTimestamp = true`
   - **At Prague**: `isPragueTimestamp = true`; EIP-7623 calldata floor in fee schedule
   - **At Osaka**: CLZ (`0x1e`) present; `isOsakaTimestamp = true`
   - **ETC chain** (use ETC config, olympiaBlockNumber, no timestamps): `forTimestamp`-path returns Olympia config; no Shanghai/Cancun/Prague opcodes
4. `sbt "testOnly *EvmConfigTimestampFork*"`

**Verify:**
```bash
sbt compile-all
sbt "testOnly *EvmConfigTimestampFork*"
sbt testVM
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/test/scala/.../vm/EvmConfigTimestampForkSpec.scala`
3. `git commit -m "test(eth): EvmConfigTimestampForkSpec — opcode presence at each ETH timestamp fork (T7-A)"`
4. `SHA=$(git rev-parse --short HEAD)` → update `.local/docs/eth-sepolia-assumption-audit.md` Thread 7 entry
5. **DELETE §ETH-T7-A**

---

### §ETH-T7-B — BEACON: Add `Eip4788BeaconRootStorageSpec` — ring-buffer write untested (THIN)

**Agent:** BEACON
**Risk:** LOW — new test file only
**Gate:** §ETH-T4-C complete (deploys EIP-4788 contract bytecode + nonce=1; tests should see the updated account)
**Files:**
- `src/test/scala/com/chipprbots/ethereum/ledger/Eip4788BeaconRootStorageSpec.scala` (new file)
- Reference: `src/test/scala/com/chipprbots/ethereum/ledger/BlockHashHistorySpec.scala` (EphemBlockchainTestSetup pattern)

**Background (Thread 7, 2026-06-24):**
`applyEip4788` (`BlockExecution.scala:209-237`) writes two ring-buffer slots per block. The slot formula (`timestamp % 8192` and `timestamp % 8192 + 8192`), wrap-around at entry 8192, and the pre-Cancun guard are entirely untested. `BlockHashHistorySpec` uses `EphemBlockchainTestSetup`, executes real in-memory blocks, and reads storage directly — the identical infrastructure is needed here.

**Steps:**
1. **Read** `BlockHashHistorySpec.scala` in full — understand `EphemBlockchainTestSetup`, block construction with Cancun config, `world.getStorage(address, slot)` post-execution.
2. **Read** `BlockExecution.scala:200-240` — confirm slot formula: timestamp-slot and timestamp-slot+8192 root slot.
3. **Create** `Eip4788BeaconRootStorageSpec.scala` with `taggedAs(UnitTest, ConsensusTest)`:

   **Case 1 — First post-Cancun block:**
   - Execute a block at Cancun-activated timestamp with a specific `parentBeaconBlockRoot`.
   - `slot = timestamp % 8192`
   - Assert `world.getStorage(BEACON_ROOTS_ADDRESS, slot)` = `timestamp`
   - Assert `world.getStorage(BEACON_ROOTS_ADDRESS, slot + 8192)` = `parentBeaconBlockRoot`

   **Case 2 — Pre-Cancun block:**
   - Execute pre-Cancun. Both storage slots → zero (empty).

   **Case 3 — Wrap-around:**
   - Execute block at `timestamp % 8192 = 8191`. Execute next block at `timestamp % 8192 = 0`.
   - Verify slot 0 overwritten with new values; slot 8191 retains previous block's values.

   **Case 4 — §ETH-T4-C contract deployment:**
   - After first post-Cancun block: `world.getAccount(BEACON_ROOTS_ADDRESS).codeHash != EMPTY_CODE_HASH`
   - `world.getAccount(BEACON_ROOTS_ADDRESS).nonce == 1`

4. `sbt "testOnly *Eip4788BeaconRoot*"`

**Verify:**
```bash
sbt compile-all
sbt "testOnly *Eip4788BeaconRoot*"
sbt testVM
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/test/scala/.../ledger/Eip4788BeaconRootStorageSpec.scala`
3. `git commit -m "test(eth): Eip4788BeaconRootStorageSpec — slot formula, wrap-around, pre-Cancun guard, bytecode deploy check (T7-B)"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 7 entry
5. **DELETE §ETH-T7-B**

---

### §ETH-T7-C — BEACON: Add `EngineApiVersionRejectionSpec` — version-mismatch guards untested (THIN)

**Agent:** BEACON
**Risk:** LOW — new test file only
**Gate:** None — standalone
**Files:**
- `src/test/scala/com/chipprbots/ethereum/consensus/engine/EngineApiVersionRejectionSpec.scala` (new file)
- Reference: `src/test/scala/com/chipprbots/ethereum/consensus/engine/EngineApiSpec.scala` (existing harness)

**Background (Thread 7, 2026-06-24):**
`EngineApiController.scala` has 5 version-mismatch rejection guards (version/fork envelope enforcement). They only fire under hive integration tests; no unit test exercises them. If a guard is broken a misconfigured CL can push wrong-version payloads silently.

Guards to test:
- `getPayloadV2` for a Cancun-era block → error `-38005`
- `getPayloadV3` for a Shanghai-era block → error `-38005`
- `getPayloadV1` for a Shanghai-era block → error `-38005`
- `newPayloadV3` pre-Cancun → `InvalidParams`
- `forkchoiceUpdatedV3` with non-zero `parentBeaconBlockRoot` before Cancun → `UnsupportedFork`

**Steps:**
1. **Read** `EngineApiController.scala:36-58` (dispatch table) and each `handleGetPayload`/`handleNewPayload`/`handleForkchoiceUpdated` version gate.
2. **Read** `EngineApiSpec.scala` — understand how the controller is instantiated, how requests are built and dispatched in tests.
3. **Create** `EngineApiVersionRejectionSpec.scala` with `taggedAs(UnitTest, ConsensusTest)`. One `it` block per guard (5 total):
   - Build the appropriate payload/request type for each case.
   - Call the controller method.
   - Assert error code matches the expected value (`-38005` / `InvalidParams` / `UnsupportedFork`).
4. `sbt "testOnly *EngineApiVersionRejection*"`

**Verify:**
```bash
sbt compile-all
sbt "testOnly *EngineApiVersionRejection* *EngineApi*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/test/scala/.../consensus/engine/EngineApiVersionRejectionSpec.scala`
3. `git commit -m "test(eth): EngineApiVersionRejectionSpec — 5 version-mismatch guards now have unit coverage (T7-C)"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 7 entry
5. **DELETE §ETH-T7-C**

---

### §ETH-T7-D — HERALD: Add `BlockRangeUpdateDecodePathSpec` — ETH69 decode path untested (MISSING)

**Agent:** HERALD
**Risk:** LOW — new test file only; also clears a CHASE-QUEUE false-positive coverage item
**Gate:** None — standalone
**Files:**
- `src/test/scala/com/chipprbots/ethereum/network/p2p/messages/BlockRangeUpdateDecodePathSpec.scala` (new file)
- `src/main/scala/com/chipprbots/ethereum/network/PeerActor.scala` (malformed message path)
- `src/main/scala/com/chipprbots/ethereum/sync/BlockFetcher.scala` (`withPossibleNewTopAt`)

**Background (Thread 7, 2026-06-24):**
`BlockFetcherSpec:298-305` appears to cover the `BlockRangeUpdate` inbound path but uses a stub that never feeds a decoded `ETHPackets.BlockRangeUpdate` into `BlockFetcher`. The real decode path — `PeerActor` feeds `BlockFetcher` — has no unit test. Two behaviors need coverage:
1. Malformed bytes → `PeerActor` protocol-breach disconnect
2. Valid `BlockRangeUpdate` → `BlockFetcher` calls `withPossibleNewTopAt`

**Steps:**
1. **Read** `PeerActor.scala` — find where `ETHPackets.BlockRangeUpdate` is decoded and handled; identify the protocol-breach disconnect path.
2. **Read** `BlockFetcher.scala` — find `withPossibleNewTopAt` and what triggers it.
3. **Read** `BlockFetcherSpec.scala:298-305` — confirm the stub and why the real path is missed.
4. **Create** `BlockRangeUpdateDecodePathSpec.scala` with `taggedAs(UnitTest, NetworkTest)`:

   **Case 1 — Malformed bytes → PeerActor disconnect:**
   - Feed truncated/invalid bytes as `BlockRangeUpdate` wire payload into `PeerActor`'s inbound handler.
   - Assert disconnect with `ProtocolBreachError` (or equivalent).

   **Case 2 — Valid message → `withPossibleNewTopAt`:**
   - Construct `ETHPackets.BlockRangeUpdate(lowestBlock=N, highestBlock=M)`.
   - Feed into `BlockFetcher`'s inbound handler.
   - Assert `withPossibleNewTopAt(M)` is called.

5. Add a comment in `BlockFetcherSpec:298-305` pointing to this spec as the real coverage.

**Verify:**
```bash
sbt compile-all
sbt "testOnly *BlockRangeUpdate* *BlockFetcher*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/test/scala/.../network/p2p/messages/BlockRangeUpdateDecodePathSpec.scala`
3. `git commit -m "test(net): BlockRangeUpdateDecodePathSpec — malformed disconnect + valid withPossibleNewTopAt (T7-D, clears CHASE-QUEUE false-positive)"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 7 entry; update CHASE-QUEUE `BlockRangeUpdate` entry
5. **DELETE §ETH-T7-D**

---

### §ETH-T7-E — HERALD: Add `ForkIdSepoliaSpec` — Sepolia CRC32 accumulation untested (MISSING)

**Agent:** HERALD
**Risk:** LOW — new test file only; regression here silently causes ALL Sepolia peers to disconnect at handshake
**Gate:** None — standalone
**Files:**
- `src/test/scala/com/chipprbots/ethereum/network/ForkIdSepoliaSpec.scala` (new file)
- Reference: existing `ForkIdSpec.scala` (ETC/Mordor ForkId tests — use same pattern)
- Reference: `reference-clients-evm/go-ethereum/params/config.go` (`SepoliaChainConfig`, known checksums)

**Background (Thread 7, 2026-06-24):**
ForkId CRC32 accumulation is tested for ETC/Mordor but not for Sepolia. A regression in `forTimestamp`-based ForkId computation (wrong order, wrong timestamp used) would cause fukuii to compute an incorrect `ForkId`. Every incoming Sepolia peer would disconnect at ETH handshake with `ErrLocalIncompatibleOrStale`. This is silent — `testEssential` does not catch it.

**Steps:**
1. **Read** `ForkIdSpec.scala` — understand how `ForkId` is constructed and how CRC32 is accumulated for ETC.
2. **Read** `reference-clients-evm/go-ethereum/params/config.go` — extract Sepolia's `SepoliaChainConfig` fork hashes at each checkpoint. Run:
   ```bash
   grep -n "Sepolia\|sepolia\|1735371\|1677557\|1706655" \
     /media/dev/2tb/dev/reference-clients-evm/go-ethereum/params/config.go | head -30
   ```
3. **Read** Fukuii's Sepolia `BlockchainConfig` — confirm timestamp values match go-ethereum.
4. **Create** `ForkIdSepoliaSpec.scala` with `taggedAs(UnitTest, NetworkTest)`. For each checkpoint, compute `ForkId(sepoliaConfig, block=N, timestamp=T)` and assert `checksum` equals the known go-ethereum value:
   - Genesis (block 0, ts 0)
   - At merge netsplit block (1,735,371)
   - At Shanghai timestamp
   - At Cancun timestamp
   - At Prague timestamp
   - At Osaka timestamp (if known in go-ethereum `upstream`)
5. `sbt "testOnly *ForkIdSepolia*"`

**Verify:**
```bash
sbt compile-all
sbt "testOnly *ForkIdSepolia* *ForkId*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/test/scala/.../network/ForkIdSepoliaSpec.scala`
3. `git commit -m "test(net): ForkIdSepoliaSpec — CRC32 at genesis, merge-netsplit, and 6 timestamp forks vs go-ethereum ground truth (T7-E)"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 7 entry
5. **DELETE §ETH-T7-E**

---

### §ETH-T9-C — BEACON: Verify StorageScheme routing in SNAP coordinators (HIGH — verify first)

**Agent:** BEACON
**Risk:** HIGH if gap confirmed — trie writes silently use wrong scheme; MEDIUM if already wired (false positive from Explore audit)
**Gate:** §ETH-T9-A complete; requires BEACON read-only verification before implementing any fix
**Files:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/AccountRangeCoordinator.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/StorageRangeCoordinator.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`

**Background:**
Thread 9 audit (Explore agent) flagged that all three SNAP coordinators accept
`storageScheme: StorageScheme` as a constructor parameter but the Explore agent could not
confirm that this parameter actually drives trie read/write routing internally.

On ETH/Sepolia (path-scheme storage), the trie nodes must be stored and queried using the
path-keyed layout. On ETC (hash-scheme storage), the hash-keyed layout is used. If the
parameter is wired up to a helper or base class that the Explore agent did not find (e.g.,
a `TrieStorage` abstraction), this may be a false positive.

**Verification first — do NOT implement a fix before reading the code.**

**Steps:**
1. **Read** each coordinator file in full — search for every use of `storageScheme` or
   `pathNodeStorage` inside the actor body, including calls to helper methods or base classes.
2. **Read** any `SnapStorage`, `TrieNodeStorage`, or `NodeStorage` helper classes referenced
   by the coordinators.
3. **Determine verdict:**
   - **WIRED:** `storageScheme` drives a dispatch (e.g., `storageScheme match { case Hash => ...; case Path => ... }`
     or passed to a storage abstraction that does the dispatch) → **NO FIX NEEDED**; add a ✅ WIRED note here and DELETE §ETH-T9-C.
   - **NOT WIRED:** `storageScheme` is stored but never read inside the trie write/read path →
     proceed to Step 4 (implement the routing).
4. **If NOT WIRED — implement routing:**
   - Identify the trie node write/read call in each coordinator.
   - Add a dispatch on `storageScheme`:
     ```scala
     storageScheme match
       case StorageScheme.Hash => hashNodeStorage.put(nodeHash, nodeBytes)
       case StorageScheme.Path => pathNodeStorage.getOrElse(sys.error("path storage not configured")).put(path, nodeBytes)
     ```
   - Gate path-scheme usage on `pathNodeStorage.isDefined` — throw at construction time if
     `storageScheme == Path && pathNodeStorage.isEmpty`.
5. **Write tests:**
   - If NOT WIRED: coordinator with `storageScheme = Path` writes to `pathNodeStorage`, not `hashNodeStorage`.
   - If WIRED: just document the finding and DELETE this entry.

**Verify:**
```bash
sbt compile-all
sbt "testOnly *AccountRange* *StorageRange* *TrieNodeHealing*"
./local/scripts/fukuii-test
```

**MANDATORY final steps (if fix was needed):**
1. `sbt scalafmtAll`
2. `git add` coordinator files + test files
3. `git commit -m "fix(eth): wire StorageScheme routing in SNAP coordinators — path-scheme storage now used for ETH/Sepolia trie nodes"`
4. `SHA=$(git rev-parse --short HEAD)` → update Thread 9 entry in audit doc
5. **DELETE §ETH-T9-C**

**MANDATORY final steps (if WIRED — false positive):**
1. Add note here: `✅ WIRED — storageScheme correctly routed via <helper class>. No fix needed.`
2. `git commit -m "docs(eth-t9-c): verify StorageScheme routing — WIRED, no code change needed"` (docs-only)
3. **DELETE §ETH-T9-C**

---

### §ETH-T9-D — BEACON: Startup assertion — storageScheme must match chain type (MEDIUM)

**Agent:** BEACON
**Risk:** LOW — defensive assertion only; misconfiguration is caught at startup before any sync
**Gate:** §ETH-T9-C complete (confirm correct scheme per chain before adding the assertion)
**Files:**
- `SNAPSyncController.scala` or `NodeBuilder.scala` — node startup / actor construction path

**Background:**
`SNAPSyncController` is instantiated without a runtime check that the configured `storageScheme`
matches the chain's expected scheme:
- ETH/Sepolia → `StorageScheme.Path` (default)
- ETC/Mordor → `StorageScheme.Hash` (default)

A misconfigured node (e.g., ETC with `storageScheme = path` in `reference.conf`) would start
successfully, sync some state into the wrong storage layout, and fail later with a corrupt trie.
The failure would be silent until state verification.

**Steps:**
1. **Read** `NodeBuilder.scala` (or wherever `SNAPSyncController` is constructed) —
   find where `storageScheme` and `networkType` are both in scope.
2. **Add a startup assertion:**
   ```scala
   val expectedScheme = networkType match
     case NetworkType.ETH => StorageScheme.Path
     case NetworkType.ETC => StorageScheme.Hash
   require(
     storageScheme == expectedScheme,
     s"storageScheme=$storageScheme does not match expected $expectedScheme for networkType=$networkType — check reference.conf"
   )
   ```
3. **Confirm ETC path:** `StorageScheme.Hash` is the ETC default and is enforced.
4. **Confirm ETH path:** `StorageScheme.Path` is the ETH default and is enforced.
5. **No test needed** — `require` throws `IllegalArgumentException` at startup; the existing
   integration tests will catch any regression if the assertion fires incorrectly.

**Verify:**
```bash
sbt compile-all
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add` the startup file
3. `git commit -m "fix(config): assert storageScheme matches chain type at SNAPSyncController startup — misconfiguration now fails fast"`
4. `SHA=$(git rev-parse --short HEAD)` → update Thread 9 entry in audit doc
5. **DELETE §ETH-T9-D**

---

### §ETH-T10-A — BEACON: Implement `engine_getPayloadV5` — Osaka block proposal blocked (HIGH)

**Agent:** BEACON
**Risk:** HIGH — without V5, fukuii cannot propose any Osaka block; the CL calls `getPayloadV5` on post-Osaka forkchoiceUpdated, fukuii falls through to `InvalidParams`
**Gate:** None — standalone; but requires KZG cell-proof generation support as a prerequisite (see step 1)
**Files:**
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiController.scala:47-50` (dispatch)
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiService.scala:1004-1023` (`exchangeCapabilities`)
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiDomain.scala` (payload types)

**Background (Thread 10, 2026-06-24):**
`engine_getPayloadV5` is the only hard Osaka Engine API requirement. An Osaka-active CL (Lighthouse, Prysm, Teku post-Fusaka) calls `getPayloadV5` when building a block proposal payload. Fukuii's dispatcher at `EngineApiController.scala:47-50` has no V5 case — it falls through to the `case _ =>` branch at L204-207 returning `InvalidParams`. **Fukuii cannot propose Osaka blocks.**

The V4→V5 difference is the blobs bundle envelope: V5 returns `BlobsBundleV2` (cell proofs — `CELLS_PER_EXT_BLOB × len(blobs)`) per EIP-7594/PeerDAS instead of V4's `BlobsBundleV1` (one KZG proof per blob).

go-ethereum reference: `eth/catalyst/api.go:482-500` (GetPayloadV5), `beacon/engine/types.go:148-156, 167-170` (BlobsBundleV1 vs V2).

`forkchoiceUpdatedV4` and `newPayloadV5` are **Amsterdam** (the fork after Osaka) — not needed for Osaka. `forkchoiceUpdatedV3` and `newPayloadV4` remain the correct Osaka cap.

**Steps:**
1. **Prerequisite check — KZG cell proofs:**
   ```bash
   grep -rn "CELLS_PER_EXT_BLOB\|cellProof\|computeCells\|splitBlob\|PeerDAS\|EIP.*7594" \
     src/main/scala/ --include="*.scala" | head -20
   ```
   If cell-proof generation is absent: this is a hard prerequisite before plumbing the V5 response. Surface to user and add a sub-entry §ETH-T10-A1 for KZG cell-proof implementation. Do not proceed with the API wiring until cell proofs are available.

2. **Read** `EngineApiController.scala:36-58` (dispatch) and `EngineApiService.scala:700-900` (payload build path) to understand how V4 constructs the response envelope.

3. **Read** `EngineApiDomain.scala` — find `ExecutionPayload`, `BlobsBundleV1`. Determine if `BlobsBundleV2` already exists or needs to be added.

4. **Add `engine_getPayloadV5` dispatch** at `EngineApiController.scala:50`:
   ```scala
   case "engine_getPayloadV5" => handleGetPayload(request, version = 5)
   ```

5. **Add V5 envelope in `handleGetPayload`** — mirror V4's `case 4` but wrap blobs bundle as `BlobsBundleV2` (cell proofs). Add fork-version gating: `getPayloadV5` valid only for Osaka-or-later payloads; `getPayloadV4` must reject Osaka payloads (matching geth api.go:471-480 Prague-only gate).

6. **Add `"engine_getPayloadV5"` to `exchangeCapabilities`** (`EngineApiService.scala:1017` area).

7. **Write tests** in `EngineApiSpec` or a new `EngineApiGetPayloadV5Spec`:
   - V5 called for Osaka block → correct `BlobsBundleV2` envelope returned
   - V4 called for Osaka block → error `-38005` (wrong version)
   - V5 called for Prague block (pre-Osaka) → error `-38005`

**Verify:**
```bash
sbt compile-all
sbt "testOnly *EngineApi*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../consensus/engine/EngineApiController.scala src/main/scala/.../consensus/engine/EngineApiService.scala` + domain/test files
3. `git commit -m "fix(eth): implement engine_getPayloadV5 — BlobsBundleV2 cell proofs for Osaka block proposal (T10-A)"`
4. `SHA=$(git rev-parse --short HEAD)` → update `.local/docs/eth-sepolia-assumption-audit.md` Thread 10 entry
5. **DELETE §ETH-T10-A**

---

### §ETH-T10-B — BEACON: Implement `engine_getBlobsV2` — Osaka/PeerDAS blob serving (MEDIUM)

**Agent:** BEACON
**Risk:** MEDIUM — missing V2 degrades blob availability serving on Osaka but does not block block import/proposal
**Gate:** §ETH-T10-A prerequisite check complete (cell-proof KZG support verified); §ETH-T10-A itself need not be complete
**Files:**
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiController.scala:53` (dispatch)
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiService.scala:1004-1023` (`exchangeCapabilities`)

**Background (Thread 10, 2026-06-24):**
Only `getBlobsV1` is dispatched (controller L53). Osaka/PeerDAS CLs use `getBlobsV2` to fetch blobs-with-cell-proofs from the EL mempool for gossip reconstruction (EIP-7594). Missing V2 means Osaka CLs cannot retrieve cell proofs from fukuii → degraded blob availability.

`BlobAndProofV2` = `{blob: Blob, cellProofs: CELLS_PER_EXT_BLOB×48-byte-proofs}` per go-ethereum `beacon/engine/types.go:167-170`.

**Steps:**
1. **Read** `EngineApiController.scala:50-55` — find `getBlobsV1` dispatch; read `EngineApiService` blob-serving method it calls.
2. **Read** go-ethereum `beacon/engine/types.go:148-170` — understand `BlobAndProofV1` vs `BlobAndProofV2`.
3. **Add `BlobAndProofV2`** to `EngineApiDomain.scala` (blob + cell proofs array).
4. **Add `engine_getBlobsV2` dispatch** at `EngineApiController.scala:54` — calls the blob fetch path and returns `BlobAndProofV2` list.
5. **Add `"engine_getBlobsV2"` to `exchangeCapabilities`**.
6. **Write a test** asserting `getBlobsV2` returns the cell-proof format for a mempool blob.

**Verify:**
```bash
sbt compile-all
sbt "testOnly *EngineApi*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add` relevant files
3. `git commit -m "fix(eth): implement engine_getBlobsV2 — BlobAndProofV2 cell proofs for PeerDAS blob serving (T10-B)"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 10 entry
5. **DELETE §ETH-T10-B**

---

### §ETH-T10-C — BEACON: Explicit Osaka fork gate on `newPayloadV4` acceptance (LOW)

**Agent:** BEACON
**Risk:** LOW — correctness-neutral today (Prague gate fires correctly at Osaka timestamps); latent gap for Amsterdam V5 split
**Gate:** §ETH-T10-A complete (Osaka V5 context established)
**Files:**
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiController.scala:192`
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiService.scala:316`

**Background (Thread 10, 2026-06-24):**
`EngineApiService.scala:316` gates `executionRequests` verification on `isPragueTimestamp`. Since Osaka > Prague, `isPragueTimestamp` is `true` at Osaka timestamps — verification still fires, so the behaviour is currently correct. However, the controller's V4 dispatch at L192 accepts `executionRequests` for `version >= 4` without an explicit Osaka-aware fork window. go-ethereum explicitly lists `forks.Prague, forks.Osaka, BPO1-5` in NewPayloadV4 (`api.go:782`). Without an explicit Osaka gate, when Amsterdam introduces V5 a clean version split becomes harder (V4 remains valid for Prague+Osaka only, not Amsterdam+).

**Steps:**
1. **Read** `EngineApiController.scala:185-210` and `EngineApiService.scala:310-330` — understand the current fork gating logic.
2. **Read** go-ethereum `api.go:767-795` — see how `checkFork(Prague, Osaka, BPO1-5)` is expressed for NewPayloadV4; compare against NewPayloadV5 (Amsterdam only).
3. **Add** an explicit `isOsakaTimestamp`-aware acceptance window to `handleNewPayload` for V4: accept for `isPragueTimestamp || isOsakaTimestamp`, reject with `UnsupportedFork` for `isAmsterdamTimestamp` (when Amsterdam is defined). This is a no-op today (no Amsterdam timestamps defined) but documents the boundary.
4. Update the `exchangeCapabilities` docstring/comment if one exists.
5. **Write a test**: `newPayloadV4` at a Prague timestamp → accepted; `newPayloadV4` at a pre-Prague timestamp → rejected. (The Osaka case is the same as Prague — covered implicitly.)

**Verify:**
```bash
sbt compile-all
sbt "testOnly *EngineApi*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add` relevant files
3. `git commit -m "fix(eth): explicit Osaka fork gate on newPayloadV4 — documents Prague+Osaka window, safe for future Amsterdam V5 split (T10-C)"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 10 entry
5. **DELETE §ETH-T10-C**

---

### §ETH-T10-D — BEACON: `validateRequests` ordering/empty-check at Engine API boundary (LOW)

**Agent:** BEACON
**Risk:** LOW — current check catches honest mismatches; this adds rejection of deliberately malformed CL input at the boundary, matching go-ethereum's `validateRequests`
**Gate:** §ETH-T10-A complete (V5 + requests context established)
**Files:**
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiService.scala` (near L311-329 `executionRequests` handling)

**Background (Thread 10, 2026-06-24):**
go-ethereum's `validateRequests` (`api.go:1257`) rejects `executionRequests` entries that are:
- Empty (`len(entry) < 2` — no type byte prefix)
- Out-of-type-order or duplicate-type (type bytes must be strictly ascending)

Fukuii's current check (`suppliedRequests != derivedRequests` at L317) catches honest CL/EL mismatches but won't produce go-ethereum's specific `InvalidParams` error for deliberately malformed-but-self-consistent request lists. This matters for hive `engine-prague` request-validation variants.

**Steps:**
1. **Read** go-ethereum `api.go:1257-1290` (`validateRequests`) — understand the length and strict-ascending-type-byte checks.
2. **Read** `EngineApiService.scala:305-335` — find where `executionRequests` is received and validated.
3. **Add pre-validation** before the existing mismatch check:
   ```scala
   // Reject empty request entries and non-strictly-ascending type bytes
   val requestTypeBytes = executionRequests.map(_.headOption.getOrElse(0.toByte))
   if executionRequests.exists(_.length < 2) then
     return Left(InvalidParams("executionRequests entry too short — missing type prefix"))
   if requestTypeBytes != requestTypeBytes.sorted.distinct then
     return Left(InvalidParams("executionRequests type bytes must be strictly ascending"))
   ```
4. **Gate on post-Prague** — only apply when `isPragueTimestamp` (requests field only present Prague+).
5. **Write tests**: malformed empty entry → `InvalidParams`; duplicate type byte → `InvalidParams`; correct ascending types → proceeds to mismatch check.

**Verify:**
```bash
sbt compile-all
sbt "testOnly *EngineApi*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../consensus/engine/EngineApiService.scala` + test files
3. `git commit -m "fix(eth): validateRequests — reject empty entries and non-ascending type bytes at Engine API boundary (T10-D)"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 10 entry; **update Part 1 warning table**: mark `EngineApiService.scala:661 Ordering.Iterable` DONE with SHA (the `@nowarn` or explicit-Ordering fix for line 661 should be committed in this same session — see Part 1 table)
5. **DELETE §ETH-T10-D**

---

### §NAMING-A — MITHRIL: Rename `PostMerge` → `PoS` throughout (terminology alignment)

**Agent:** MITHRIL
**Risk:** LOW — pure rename, no logic change; `BlockHeader.scala:83-84` already defines the canonical pattern
**Gate:** none — standalone, can run any time

**Files (~60 occurrences across 8 files):**

*Main source (29 occurrences):*
- `consensus/engine/PostMergeBlockHeaderValidator.scala` — rename file + object + 4 private methods
- `consensus/validators/BlockHeaderValidator.scala` — `PostMergeNonceError`, `PostMergeOmmersError`
- `consensus/engine/TransitionBlockHeaderValidator.scala` — 2 references to `PostMergeBlockHeaderValidator`
- `blockchain/sync/snap/SNAPSyncController.scala` — `isPostMergeChain` val + 8 usages + import + 1 log string
- `blockchain/sync/SyncController.scala` — `isPostMergeChain` val + 1 usage
- `utils/BlockchainConfig.scala` — `isPostMerge(totalDifficulty): Boolean`

*Test source (31+ occurrences):*
- `test/.../validators/PostMergeBlockHeaderValidatorSpec.scala` — rename file + class + ~18 internal references
- `test/.../sync/snap/SNAPSyncControllerSpec.scala` — import + 5 call sites + 1 comment

*Lower-priority (local variable names only — context is ETH Merge event, not consensus type):*
- `test/.../ETH69OscillationChainWeightSpec.scala:100-101` — `preMerge`, `postMerge` local vals
- `test/.../ledger/BlockExecutionSpec.scala:688,698` — `postMergeHeader` local val

**Background:**
"PostMerge" refers to Ethereum's specific historical event — The Merge (Sept 2022), when ETH transitioned from PoW to PoS. Fukuii is a multi-chain client: ETC is a permanent PoW chain that never had a "merge". Using `PostMerge` in shared infrastructure conflates ETH's migration event with the chain's consensus type, making ETC code harder to reason about.

`BlockHeader.scala:83-84` already defines the canonical pattern:
```scala
def isPoS: Boolean = difficulty == 0 && baseFee.isDefined
def isPoW: Boolean = !isPoS
```

All `PostMerge` identifiers should align with this existing `isPoS`/`isPoW` vocabulary.

**Investigation audit (run first — confirm scope before renaming):**
```bash
# Full occurrence list in main source
grep -rn "PostMerge\|postMerge\|isPostMerge" \
  /media/dev/2tb/dev/fukuii/src/main/scala/ --include="*.scala"

# Full occurrence list in test source
grep -rn "PostMerge\|postMerge\|isPostMerge" \
  /media/dev/2tb/dev/fukuii/src/test/scala/ --include="*.scala"

# Confirm canonical pattern already exists
grep -n "isPoS\|isPoW" \
  /media/dev/2tb/dev/fukuii/src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala
```

**Rename map:**

| From | To | Where |
|------|----|-------|
| `PostMergeBlockHeaderValidator` (object) | `PoSBlockHeaderValidator` | file rename + all refs |
| `PostMergeBlockHeaderValidatorSpec` (class) | `PoSBlockHeaderValidatorSpec` | file rename + all refs |
| `validatePostMergeDifficulty` | `validatePoSDifficulty` | `PoSBlockHeaderValidator.scala` |
| `validatePostMergeNonce` | `validatePoSNonce` | `PoSBlockHeaderValidator.scala` |
| `validatePostMergeOmmers` | `validatePoSOmmers` | `PoSBlockHeaderValidator.scala` |
| `PostMergeNonceError` | `PoSNonceError` | `BlockHeaderValidator.scala` + spec |
| `PostMergeOmmersError` | `PoSOmmersError` | `BlockHeaderValidator.scala` + spec |
| `isPostMergeChain` (val) | `isPoSChain` | `SyncController.scala`, `SNAPSyncController.scala` |
| `isPostMerge(totalDifficulty)` | `isPoS(totalDifficulty)` | `BlockchainConfig.scala` |
| log string `"postMergeChain={}"` | `"isPoSChain={}"` | `SNAPSyncController.scala` |

**Steps:**
1. Run the investigation greps above — confirm the counts before proceeding.
2. `git mv` the two files with structural renames:
   ```bash
   git mv src/main/scala/.../consensus/engine/PostMergeBlockHeaderValidator.scala \
          src/main/scala/.../consensus/engine/PoSBlockHeaderValidator.scala
   git mv src/test/scala/.../validators/PostMergeBlockHeaderValidatorSpec.scala \
          src/test/scala/.../validators/PoSBlockHeaderValidatorSpec.scala
   ```
3. Apply all symbol renames in the map above. Prefer `sed -i` on each file for precision over IDE batch rename:
   ```bash
   # Example (adjust paths to full package paths):
   sed -i 's/PostMergeBlockHeaderValidator/PoSBlockHeaderValidator/g' \
     src/main/scala/.../consensus/engine/PoSBlockHeaderValidator.scala \
     src/main/scala/.../consensus/engine/TransitionBlockHeaderValidator.scala \
     src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala \
     src/test/scala/.../validators/PoSBlockHeaderValidatorSpec.scala \
     src/test/scala/.../sync/snap/SNAPSyncControllerSpec.scala
   sed -i 's/PostMergeNonceError/PoSNonceError/g; s/PostMergeOmmersError/PoSOmmersError/g' \
     src/main/scala/.../consensus/validators/BlockHeaderValidator.scala \
     src/main/scala/.../consensus/engine/PoSBlockHeaderValidator.scala \
     src/test/scala/.../validators/PoSBlockHeaderValidatorSpec.scala
   sed -i 's/isPostMergeChain/isPoSChain/g' \
     src/main/scala/.../blockchain/sync/SyncController.scala \
     src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala \
     src/test/scala/.../sync/snap/SNAPSyncControllerSpec.scala
   sed -i 's/isPostMerge(/isPoS(/g' \
     src/main/scala/.../utils/BlockchainConfig.scala
   sed -i 's/validatePostMergeDifficulty/validatePoSDifficulty/g; s/validatePostMergeNonce/validatePoSNonce/g; s/validatePostMergeOmmers/validatePoSOmmers/g' \
     src/main/scala/.../consensus/engine/PoSBlockHeaderValidator.scala
   sed -i 's/postMergeChain=/isPoSChain=/g' \
     src/main/scala/.../blockchain/sync/snap/SNAPSyncController.scala
   ```
4. `sbt compile-all` — fix any missed references. Expected: 0 errors.
5. Grep to confirm no `PostMerge`/`postMerge`/`isPostMerge` remain in main source (lower-priority local vars in test files are acceptable to leave):
   ```bash
   grep -rn "PostMerge\|isPostMerge" src/main/scala/ --include="*.scala"
   ```
6. `sbt scalafmtAll`

**Verify:**
```bash
sbt compile-all
sbt "testOnly *PoSBlockHeader* *BlockHeaderValidator* *SNAPSync*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage with `git add` — list all 8 touched files individually
3. `git commit -m "refactor: rename PostMerge → PoS — align with BlockHeader.isPoS/isPoW canonical pattern"`
4. **DELETE §NAMING-A**
