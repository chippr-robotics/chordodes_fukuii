# Fukuii Modernization — Deferred Backlog

**Last updated**: 2026-06-24 (§8k-G4 fully DONE — all sub-tasks committed, externalAdapter deleted, §8k-G cluster closed; see completed/DEFERRED-BACKLOG.md §8k-G4)
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

#### §8k-R1 — DONE 2026-06-23 — see `completed/DEFERRED-BACKLOG.md`

#### §8k-G — DONE 2ef2b6637 — see `completed/DEFERRED-BACKLOG.md`

---

#### §8k-G3 + §8k-G3-SSC ✅ DONE 2026-06-24 — see `completed/DEFERRED-BACKLOG.md`

---

#### §8k-G4 ✅ DONE 2026-06-24 — see `completed/DEFERRED-BACKLOG.md §8k-G4`

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
| **R10** | **ETH/Sepolia assumption audit** — systematic hunt for ETC-first design leaking into ETH code paths. 10 threads: (1) fork dispatch `forBlock` vs `forTimestamp`, (2) PoW/PoS divergence guards, (3) EIP-1559 fee routing (burn vs treasury), (4) CL integration completeness (withdrawals/4788/4844), (5) chain ID hardcoding, (6) VM tracer abort-path completeness, (7) test coverage ratio ETC vs ETH, (8) Sepolia config completeness, (9) SNAP sync ETH path, (10) Engine API Osaka edge cases. Findings feed an ETH sprint. **Prompt:** `.local/docs/eth-sepolia-assumption-audit.md`. Gate: none. | BEACON (T1,3,4,6,8,10), FORGE (T2,5,7), EYE (T9) |

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
| ~~G4~~ | ~~Batch G~~ | ~~§8d-A1 — BEACON: EngineApiService `Await.result` on CE3 compute thread~~ | DONE 2026-06-24 — verified already fixed: `IO.fromFuture` in place at lines 629–640 with explanatory comment; no code change needed |
| ~~G5~~ | ~~Batch G~~ | ~~§8d-CONDUIT — CONDUIT: jsonrpc/ remaining IO boundary scan (Await/EC.global/blocking)~~ | DONE 2026-06-24 — zero findings; all 55 `jsonrpc/` files clean (see `completed/DEFERRED-BACKLOG.md §8d`) |
| ~~G6~~ | ~~Batch G~~ | ~~§8c-M4 — VAULT: DataSource close cache invalidation verify-or-by-design~~ | DONE 2026-06-24 — by-design; Scaladoc comment on `RocksDbDataSource.close()`; verdict in `storage-rocksdb.md` |
| ~~G7~~ | ~~Batch G~~ | ~~§8e-StackTrie — FORGE: StackTrie `:120`+`:462` DEFER re-assessment (2 `scalafix:ok` sites)~~ | DONE 2026-06-24 — `09307c5a7` (both CLEAR: `:120` node expr, `:462` var-result; see modernization-log/core/mpt.md) |
| ~~G8~~ | ~~Batch G~~ | ~~§8l-R1/I — FORGE: VM tracer research + implementation~~ | DONE 2026-06-24 — R1 `37c9d081b`/`5c2adeaaf`; I impl complete; `VM.create()` tracer balanced; suppression removed |

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

---

## Part 10: ETH/Sepolia Assumption Audit Findings (2026-06-24)

Source: `.local/docs/eth-sepolia-assumption-audit.md` — Thread 1 (fork dispatch completeness).
Thread 3 (EIP-1559 fee routing) audited: functionally CORRECT — ETH base fee is burned, ETC base fee credited to treasury. Found one logging bug: `log.error` in `BlockPreparator.creditBaseFeeToTreasury` fired for every ETH/Sepolia block (treasury-address=0 is correct config, not an error). **FIXED `f868b75a8`** — guard added `&& networkType == NetworkType.ETC`. See `completed/DEFERRED-BACKLOG.md §ETH-T3-LOG`.

---

### §ETH-T1-A — BEACON: Fix `validateInitCodeSize` fork dispatch on ETH/Sepolia

**Agent:** BEACON
**Risk:** MEDIUM — consensus-touching transaction validator; ETH-only behaviour change
**Gate:** None — standalone fix, no sprint prerequisite
**Files:** `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala:245`

**Background:**
`validateInitCodeSize` calls the 2-arg (block-only) `EvmConfig.forBlock(blockHeader.number, blockchainConfig)`.
On Sepolia (`olympiaBlockNumber=0`, `spiral=1e18`) the block-only dispatch always returns a
London-era config, so `eip3860Enabled = false` regardless of block timestamp. EIP-3860
(max initcode size: `2 * MAX_CODE_SIZE = 49152` bytes, plus initcode word cost) was activated
at Shanghai (2023-04-12 on mainnet, block 2,778,137 on Sepolia). Any CREATE transaction on
ETH/Sepolia with initcode > 49152 bytes is incorrectly accepted by Fukuii post-Shanghai.

The same file already does this correctly for gas-cap and blob validation:
- `validateTxGasLimitCap` (line 47) → `blockHeader.unixTimestamp`
- `validateBlobTransactionSupport` (line 90) → `isCancunTimestamp`

**Steps:**
1. **Read** `StdSignedTransactionValidator.scala` lines 230-260 in full to confirm
   the call site and available variables.
2. **Read** `EvmConfig.scala` lines 27-70 to confirm the 3-arg overload signature:
   `forBlock(blockNumber: BigInt, timestamp: Long, blockchainConfig: BlockchainConfigForEvm)`.
3. **Verify** that `blockHeader` (with `unixTimestamp`) is in scope at line 245.
4. **Change** line 245 from 2-arg to 3-arg:
   ```scala
   // BEFORE
   val evmConfig = EvmConfig.forBlock(blockHeader.number, blockchainConfig)
   // AFTER
   val evmConfig = EvmConfig.forBlock(blockHeader.number, blockHeader.unixTimestamp, blockchainConfig)
   ```
5. **Confirm ETC safety:** `isShanghaiTimestamp` et al. return `false` for ETC configs
   (no timestamp fields set), so the 3-arg overload collapses to the existing block-only
   result on ETC — behaviour unchanged.
6. **Write / update a test** in `StdSignedTransactionValidatorSpec` covering:
   - ETH/Sepolia post-Shanghai: initcode > 49152 bytes → rejected
   - ETH/Sepolia pre-Shanghai: large initcode → accepted (timestamp before Shanghai)
   - ETC: large initcode → accepted (EIP-3860 not active on ETC)

**Verify:**
```bash
sbt compile-all
sbt "testOnly *StdSignedTransactionValidator*"
sbt "testOnly *Osaka*" "testOnly *Sepolia*"
sbt testVM
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../consensus/validators/std/StdSignedTransactionValidator.scala`
3. `git commit -m "fix(eth): use timestamp-aware EvmConfig in validateInitCodeSize — EIP-3860 now enforced post-Shanghai on ETH/Sepolia"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc `.local/docs/eth-sepolia-assumption-audit.md` Thread 1 entry
5. **DELETE §ETH-T1-A**

---

### §ETH-T1-B — BEACON: Fix `validateGasLimitEnoughForIntrinsicGas` fork dispatch on ETH/Sepolia

**Agent:** BEACON
**Risk:** MEDIUM — consensus-touching transaction validator; ETH-only behaviour change
**Gate:** §ETH-T1-A complete (same file; apply in the same session or back-to-back)
**Files:** `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala:271`

**Background:**
`validateGasLimitEnoughForIntrinsicGas` calls the 2-arg (block-only) `EvmConfig.forBlock`.
On ETH/Sepolia the block-only overload returns London-era config, so intrinsic-gas validation
uses `MystiqueFeeSchedule` (London/Paris calldata costs: zero bytes = 4 gas, non-zero = 16 gas).
Post-Prague (EIP-7623), calldata floor pricing changes. A transaction valid under
London calldata costs may be invalid under the Prague floor — or vice versa — meaning
Fukuii can admit ETH transactions it should reject (or reject ones it should admit) at the
validator boundary post-Prague.

**Steps:**
1. **Read** `StdSignedTransactionValidator.scala` lines 255-290 to confirm call site
   and available variables.
2. **Read** `EvmConfig.scala` lines 27-70 to confirm the 3-arg overload signature.
3. **Verify** `blockHeader.unixTimestamp` is in scope at line 271.
4. **Change** line 271 from 2-arg to 3-arg:
   ```scala
   // BEFORE
   val evmConfig = EvmConfig.forBlock(blockHeader.number, blockchainConfig)
   // AFTER
   val evmConfig = EvmConfig.forBlock(blockHeader.number, blockHeader.unixTimestamp, blockchainConfig)
   ```
5. **Confirm ETC safety** — same reasoning as §ETH-T1-A (timestamp fields absent on ETC,
   3-arg collapses to block-only result; ETC unaffected).
6. **Write / update a test** in `StdSignedTransactionValidatorSpec`:
   - ETH/Sepolia post-Prague: transaction with calldata that passes London floor
     but fails EIP-7623 floor → rejected
   - ETC: same calldata → accepted (no EIP-7623 on ETC)

**Verify:**
```bash
sbt compile-all
sbt "testOnly *StdSignedTransactionValidator*"
sbt "testOnly *Prague*" "testOnly *Sepolia*"
sbt testVM
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../consensus/validators/std/StdSignedTransactionValidator.scala`
3. `git commit -m "fix(eth): use timestamp-aware EvmConfig in validateGasLimitEnoughForIntrinsicGas — correct intrinsic-gas floor post-Prague on ETH/Sepolia"`
4. `SHA=$(git rev-parse --short HEAD)` → update audit doc Thread 1 entry
5. **DELETE §ETH-T1-B**

---

### §ETH-T1-C — Design decision: stateless mempool fee schedule on ETH (SUSPICIOUS, low severity)

**Agent:** BEACON (design review, not a direct fix)
**Risk:** LOW — not consensus-final; block-execution re-validates
**Gate:** §ETH-T1-A and §ETH-T1-B complete
**Files:** `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala:610`

**Background:**
`getStatelessValidTransactions` (line 610) calls `EvmConfig.forBlock(olympiaBlockNumber, ...)`
as a fixed block-number proxy. On ETH, `olympiaBlockNumber = 0`, so this always returns
London config — correct for pre-Shanghai blocks, stale for post-Shanghai. The method is a
stateless mempool pre-filter (runs on incoming p2p txs via `SignedTransactionsFilterActor`
and `PendingTransactionsManager`) and has no access to a block timestamp.

This is classified SUSPICIOUS rather than WRONG because:
- It is not consensus-final (block-execution validates again with the correct `evmConfig`)
- It cannot silently corrupt state — it can only cause false rejection of valid ETH txs
  from the mempool, or false admission of txs that will fail at execution

**Decision required:** Choose one of:
1. **Use `latestForkTimestamp` proxy** — derive the latest activated ETH timestamp from
   `blockchainConfig` (e.g., `pragueTimestamp` if present) and call the 3-arg overload.
   Gives a "current fork" approximation. Safe and inexpensive.
2. **Skip intrinsic-gas floor for ETH** — gate the intrinsic check on `networkType != ETH`,
   rely entirely on block-execution for ETH. Simpler, less precise mempool filtering.
3. **Accept as-is** — document the known approximation; mempool pre-filters can be lenient.

**Steps for BEACON:**
1. Read `SignedTransaction.scala:595-630` to understand what pre-checks are done stateless.
2. Read how `latestActivatedTimestamp` (or equivalent) could be derived from `BlockchainConfig`.
3. Recommend one of the three options above with rationale. Do not implement — surface the
   decision to the user first.

**DELETE §ETH-T1-C** after the design decision is recorded and (if applicable) implemented.

---

### §ETH-T2-A — MITHRIL + BEACON + FORGE: Rename `isPostMerge` → `isPoS` / add `isPoW` companion

**Agent:** MITHRIL (mechanical rename) — pre-flight read by BEACON (ETH call sites) and FORGE (ETC call sites)
**Risk:** LOW — pure rename; predicate logic unchanged; all call sites are small and compile-verified
**Gate:** None — standalone housekeeping, no sprint prerequisite
**Files:**
- `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala` (definition)
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` (PoW reward skip)
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` (PREVRANDAO dispatch)
- `src/main/scala/com/chipprbots/ethereum/vm/VM.scala` (EIP-7610 CREATE conflict)
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/PostMergeBlockHeaderValidator.scala` (difficulty guard)
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/TransitionBlockHeaderValidator.scala` (routing split)
- All test files referencing `isPostMerge` (find with grep below)

**Background:**

Thread 2 of the ETH/Sepolia assumption audit (`eth-sepolia-assumption-audit.md`) confirmed all
PoW/PoS divergence guards are correct. The naming inconsistency was identified separately:

The codebase uses two guard patterns:
- **Chain-level (static):** `isPoWChain` = `terminalTotalDifficulty.isEmpty` — set at node startup,
  consistent across the whole chain. Used in `BlockBroadcast`, `EthNodeStatus69ExchangeState`,
  `RegularSync`, `NodeBuilder`, `BlockchainReader`, `NetworkPeerManagerActor`.
- **Block-level (dynamic):** `BlockHeader.isPostMerge` = `difficulty == 0 && baseFee.isDefined` —
  per-block runtime check. Used in `BlockPreparator`, `OpCode`, `VM`, validators.

`isPostMerge` is ETH-specific terminology ("The Merge" was an ETH event). The chain-level
pattern already uses the chain-agnostic `isPoW`/`isPoS` vocabulary. For consistency and
future-proofing (other PoS EVM chains), the block-level predicate should use the same vocabulary:

- `isPostMerge` → `isPoS` (positive: this block runs under PoS consensus)
- Add `isPoW = !isPoS` companion (positive: this block runs under PoW consensus)

Design rationale: most EVM networks are PoS. PoW is the exception (ETC/Mordor only in
production use). The default assumption should be PoS; PoW behaviour is explicitly opted into.
`if block.header.isPoW then <pow behaviour>` reads more naturally in a multi-chain client
than `if !block.header.isPostMerge then <pow behaviour>`.

The predicate logic does not change. `difficulty == 0 && baseFee.isDefined` is still correct:
- ETC: `difficulty > 0` → `isPoS = false`, `isPoW = true` always
- ETH post-merge: `difficulty == 0 && baseFee.isDefined` → `isPoS = true`, `isPoW = false`
- ETH pre-merge (historical): `difficulty > 0` → `isPoS = false`, `isPoW = true` (correct)

`isPostMergeChain` / `isPoWChain` at the chain-config level are already correct and do not
need renaming — they serve a different purpose (static chain-type flag vs. per-block predicate).

**Steps:**

1. **Pre-flight grep — find all call sites:**
   ```bash
   cd /media/dev/2tb/dev/fukuii
   grep -rn "isPostMerge\b" src/ --include="*.scala"
   ```
   Expected production hits:
   - `BlockHeader.scala` (definition — 2 lines: `def isPostMerge` + `def prevRandao`)
   - `BlockPreparator.scala` (1 site)
   - `OpCode.scala` (1 site)
   - `VM.scala` (2 sites — log comment + guard)
   - `PostMergeBlockHeaderValidator.scala` (1 site — class name contains "PostMerge", body uses `difficulty == 0` directly, not the predicate — verify)
   - `TransitionBlockHeaderValidator.scala` (uses `difficulty == 0` directly — verify no `isPostMerge` call)
   Also check test files:
   ```bash
   grep -rn "isPostMerge\b" src/test/ --include="*.scala"
   ```

2. **In `BlockHeader.scala`** — rename + add companion:
   ```scala
   // BEFORE
   def isPostMerge: Boolean = difficulty == 0 && baseFee.isDefined
   def prevRandao: Option[ByteString] = if isPostMerge then Some(mixHash) else None

   // AFTER
   def isPoS: Boolean = difficulty == 0 && baseFee.isDefined
   def isPoW: Boolean = !isPoS
   def prevRandao: Option[ByteString] = if isPoS then Some(mixHash) else None
   ```

3. **In `BlockPreparator.scala`** — update guard:
   ```scala
   // BEFORE
   if block.header.isPostMerge then worldStateProxy
   // AFTER
   if block.header.isPoS then worldStateProxy
   ```

4. **In `OpCode.scala`** — update PREVRANDAO dispatch:
   ```scala
   // BEFORE
   if s.env.blockHeader.isPostMerge then UInt256(s.env.blockHeader.mixHash)
   // AFTER
   if s.env.blockHeader.isPoS then UInt256(s.env.blockHeader.mixHash)
   ```

5. **In `VM.scala`** — update EIP-7610 CREATE conflict guard (2 sites — comment + code):
   ```scala
   // BEFORE
   // BlockHeader.isPostMerge (difficulty==0 && baseFee set) as the Paris signal.
   if context.blockHeader.isPostMerge then context.world.nonEmptyCodeOrNonceOrStorageAccount(contractAddr)
   // AFTER
   // BlockHeader.isPoS (difficulty==0 && baseFee set) as the Paris / PoS signal.
   if context.blockHeader.isPoS then context.world.nonEmptyCodeOrNonceOrStorageAccount(contractAddr)
   ```

6. **Update any test files** found in step 1.

7. **Confirm `PostMergeBlockHeaderValidator` and `TransitionBlockHeaderValidator`** do NOT
   call `isPostMerge` — they use `difficulty == 0` directly. If they do call `isPostMerge`,
   update those sites too.

**Verify:**
```bash
# No remaining isPostMerge references (other than comments that explain the history)
grep -rn "\.isPostMerge\b" src/ --include="*.scala"
# Expected: 0 results

# New predicate is present
grep -rn "\.isPoS\b\|\.isPoW\b" src/ --include="*.scala" | grep "BlockHeader\|blockHeader\|header\."

sbt compile-all
sbt "testOnly *BlockPreparator*" "testOnly *VM*" "testOnly *OpCode*"
sbt testVM
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../domain/BlockHeader.scala src/main/scala/.../ledger/BlockPreparator.scala src/main/scala/.../vm/OpCode.scala src/main/scala/.../vm/VM.scala` (+ any test files changed)
3. `git commit -m "refactor: rename BlockHeader.isPostMerge → isPoS, add isPoW companion — align PoW/PoS vocabulary with chain-level isPoWChain pattern"`
4. `SHA=$(git rev-parse --short HEAD)` → update `.local/docs/eth-sepolia-assumption-audit.md` Thread 2 entry with SHA
5. **DELETE §ETH-T2-A**
