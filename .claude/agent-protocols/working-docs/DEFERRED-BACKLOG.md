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

### 3c — isInstanceOf / asInstanceOf audit ✅ DONE 2026-06-22 — see `completed/DEFERRED-BACKLOG.md`

1 site fixed (`7cc9eda3a`); remaining 82 are intentional (JSON-RPC marshalling, network decoders — pattern matching replacements not safe without full type analysis).

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

❌ REJECTED (2026-06-25) — `SyncProtocol.Status.Syncing` is a `case class` with three constructor
params (`startingBlockNumber: BigInt`, `blocksProgress: Progress`, `stateNodesProgress: Option[Progress]`).
Enum migration requires all subtypes to be pure `case object`. Hierarchy stays as sealed trait.

Note: the actual type name is `SyncProtocol.Status` (not `SyncStatus` — no such type exists in main sources).

### 3g — StateValidator.scala Exception Swallowing — DONE 2026-06-20 — see `completed/DEFERRED-BACKLOG.md`

---

### 3e — Console output → logging ✅ COMPLETE `c3fec6390` 2026-06-22 — see `completed/DEFERRED-BACKLOG.md`

12 sites fixed (3 files); 8 intentional CLI/TUI calls preserved.

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

**R6 Clearout Prompt (research only — no code changes):**

> Use the MITHRIL agent. Produce `.local/docs/opaque-type-domain-analysis.md` — a mapping of
> all `BigInt` and `ByteString` usages in `src/main/` by semantic role.
>
> **Steps:**
> 1. Run each grep to get occurrence counts:
>    ```bash
>    grep -rn "BigInt" src/main/ --include="*.scala" | grep -v "//\|import\|test" | head -200
>    grep -rn "ByteString" src/main/ --include="*.scala" | grep -v "//\|import" | head -200
>    grep -rn "opaque type" src/main/ --include="*.scala"  # should be 0
>    ```
> 2. For each `BigInt` field/parameter found, classify by semantic role:
>    - BlockNumber / BlockHash hint (in sync, chain types)
>    - Balance (in `Account`, wallet)
>    - Nonce (in `Account`, tx)
>    - GasAmount / GasPrice (in `Block`, `Transaction`)
>    - StorageKey / StorageValue (in `MptNode`, storage)
>    - Timestamp (ETH fork dispatch)
>    - Other / ambiguous
> 3. For each `ByteString` field/parameter found, classify:
>    - Hash (BlockHash, TransactionHash, UncleHash, StateRoot, TxRoot, ReceiptsRoot)
>    - Address (sender, recipient, contract)
>    - ContractCode / CodeHash
>    - RLP-encoded payload (arbitrary bytes)
>    - Other / ambiguous
> 4. For each candidate opaque type, record:
>    - Current raw type and field name
>    - Files affected (count)
>    - Whether it crosses consensus code paths (→ FORGE gate)
>    - RLP codec interaction (custom `rlpEncode`/`rlpDecode` needed?)
>    - Migration complexity: LOW (domain-only) / MEDIUM (domain+sync) / HIGH (domain+consensus)
> 5. Rank candidates by value/cost ratio. Recommend a sequenced introduction order.
> 6. Write the document — no source file edits.
>
> **Output:** `.local/docs/opaque-type-domain-analysis.md`
> **After running:** update R6 row in Research Threads table with output doc path and ✅.

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

### 8f — Dead Code Audit (Broader than extvm) ✅ COMPLETE — see `completed/DEFERRED-BACKLOG.md`

Research DONE 2026-06-22. Deletion sprint DONE: `fa57df9b9` (MetricsAlreadyConfiguredError + LocalVM + AdaptiveSyncStrategy), `c6b3da4cb` (DeltaSpikeGauge), `ff2fc219c` (StaticNodesLoader). Branch-wide audit confirmed no further candidates.

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

#### §8k-J — PRISM: Re-run TCP floor verification after CAPSTONE — AUDIT COMPLETE; §8k-B READY (§8k-Q ✅ DONE)

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

---

#### §8k-B — PRISM: TCP floor cleanup + adapter import removal (recurring checkpoint)

**Agent:** PRISM
**Status:** CHECKED 2026-06-25 (post-§8k-Q) — 0 imports removable this pass; next check after SNAP1.
**Gate:** None — `fastSyncClassicSelf` deleted; bridges now 19.

**This sprint is a recurring checkpoint — re-run after each Primary Track migration completes
(SNAP1 → BlockImporter LOOM → PEB migration), not just once.**

**Post-§8k-Q check (2026-06-25):** Attempted import removal from 5 candidates with no explicit
`.toClassic` calls (FastSyncBranchResolverActor, FastSync, SyncStateSchedulerActor, RegularSync,
FaucetSupervisor). All 5 failed compile — the adapter import enables implicit classic↔typed
conversions that do not appear in the `.toClassic` grep. No imports are removable at this gate.
Next opportunity: after SNAP1 frees 4 bridges.

**Current state (live count 2026-06-25):**
- Real code bridges: **19 grep lines** (25 raw − 6 scaladoc comment noise)
- **Permanent TCP floor: 5 grep lines = 7 actual `.toClassic` calls**
  - `ServerActor.scala:70,77` — 2 calls (TCP bind)
  - `RLPxConnectionHandler.scala:323` — 1 call (TCP write ack)
  - `PeerManagerActor.scala:584` — 2 calls on one line (`peer.ref.toClassic` + `peerEventAdapter.toClassic`)
  - `PeerManagerActor.scala:622` — 2 calls on one line (`peer.ref.toClassic` + `peerEventAdapter.toClassic`)
- **Eliminatable: 15 code bridges** — see §8k-J cluster table above

**Bridge reduction path:**

| Sprint | Bridges freed | After (grep lines) |
|--------|--------------|---------------------|
| ~~§8k-Q~~ ✅ | 1 — FastSync:180 `fastSyncClassicSelf` | **19** |
| ~~§8k-B1~~ ✅ | 2 — BytecodeRecovery:199, StorageRecovery:229 | **17** |
| §8k-B2 | 2 — SyncController:1668, :2079 (NPMA type fix) | 15 |
| §8k-B3 | 1 — SSC:555 (chainDownloaderReplyAdapter) | 14 |
| §8k-B4 | 2 — BlockImporter:207, :214 | 12 |
| §8k-B5 | 2 — PivotBlockSelector:418, :579 | 10 |
| §8k-B6 | 4 — PeerEventBusActor:42, NodeBuilder:419/:1009, PeerRequestHandler:78 | 6 |
| §8k-B7 | 1 — AkkaTaskOps:37 | **5 → TCP floor = 7 calls** |

**Run after each Primary Track sprint above:**

```bash
# Step 1 — Re-run real bridge census (exclude scaladoc noise):
grep -rn "\.toClassic\b" src/main/ --include="*.scala" | grep -v "//" | grep -v "^\s*\*" | wc -l
# Current: 19 (dropped from 20 after §8k-Q deleted FastSync:180)

# Step 2 — For each file whose ONLY .toClassic usage was just eliminated,
# attempt adapter import removal:
#   Remove: import org.apache.pekko.actor.typed.scaladsl.adapter.*
#   Then: sbt compile-all
#   Keep if compile fails; delete if clean.
# IMPORTANT: The grep census undercounts live adapter usage. The adapter import
# also enables implicit classic↔typed conversions (ActorRef[T] where classic
# ActorRef expected, system.spawn on classic ActorSystem, etc.) that don't
# appear in the .toClassic grep. Always verify with compile, not grep alone.

# Step 3 — Commit any removals:
git commit -m "chore(8k-B): remove adapter imports post-<sprint> — <N> files cleaned, bridges: <before>→<after>"

# Step 4 — Update §8k-J cluster table with new bridge count and gate status.
```

**TCP floor target: 7 calls (5 grep lines)** — not 4. PeerManagerActor:584 and :622 each contain two `.toClassic` calls on one line.

---

### §8k-B Sprint Prompts: Bridge Elimination to TCP Floor

Execute in order. Each prompt is self-contained. Run `sbt compile-all` after every change; run the §8k-B census grep after each commit. Target: 19 bridges → 5 grep lines (TCP floor).

---

#### §8k-B1 — BytecodeRecovery + StorageRecovery: pass typed adapter directly (2 bridges, immediate)

**Files:** `BytecodeRecoveryActor.scala:199`, `StorageRecoveryActor.scala:229`
**Agent:** PRISM or MITHRIL
**Gate:** None — §8k-C already migrated `ByteCodeCoordinator.snapSyncController` and `StorageRangeCoordinator.snapSyncController` to `TypedActorRef[SNAPSyncController.Command]`.

**Prompt:**
```
In BytecodeRecoveryActor.scala, around line 199, the code passes `bccAdapter.toClassic` as the
`snapSyncController` argument to `ByteCodeCoordinator(...)`. The `bccAdapter` is already typed as
`TypedActorRef[SNAPSyncController.Command]` (see its declaration ~10 lines above), and
`ByteCodeCoordinator.snapSyncController` already expects `TypedActorRef[SNAPSyncController.Command]`
(§8k-C migrated this in 2026). The `.toClassic` is a pointless round-trip that re-adds a bridge.

Fix: remove `.toClassic` at line 199. Pass `bccAdapter` directly.

Apply the same fix in StorageRecoveryActor.scala ~line 229: `srcAdapter.toClassic` → `srcAdapter`.
The `StorageRangeCoordinator.snapSyncController` parameter is also already Typed.

After both edits:
1. `sbt compile-all` — must be clean
2. Run bridge census: `grep -rn "\.toClassic\b" src/main/ --include="*.scala" | grep -v "//" | grep -v "^\s*\*" | wc -l`
   Expected: 17 (down from 19)
3. Commit: `git commit -m "fix(8k-B1): remove toClassic round-trip in BytecodeRecovery+StorageRecovery — bridges: 19→17"`
4. Update §8k-B gate table row for BytecodeRecovery:199 and StorageRecovery:229 as ✅ DONE.
```

---

#### §8k-B2 — NPMA RegisterSnapSyncController: Classic → Typed ref (2 bridges)

**Files:** `NetworkPeerManagerActor.scala:61,:1357`, `SyncController.scala:1668,:2079`
**Agent:** PRISM or MITHRIL
**Gate:** None — `SNAPSyncController` is already `Behavior[Command]`.

**Prompt:**
```
`NetworkPeerManagerActor` has two message variants that carry a Classic `ActorRef` for the
SNAPSyncController routing slot:
  - Line 61:   `final case class RegisterSnapSyncControllerCmd(ref: ActorRef) extends Command`
  - Line 1357: `case class RegisterSnapSyncController(snapSyncController: ActorRef)`

SNAPSyncController is already `Behavior[Command]`. Both variants should use
`TypedActorRef[SNAPSyncController.Command]` instead.

Step 1 — Change the message types:
  - `NetworkPeerManagerActor.scala:61`: `ref: ActorRef` → `ref: TypedActorRef[SNAPSyncController.Command]`
  - `NetworkPeerManagerActor.scala:1357`: `snapSyncController: ActorRef` → `snapSyncController: TypedActorRef[SNAPSyncController.Command]`
  Add import for SNAPSyncController if not present.

Step 2 — Update NPMA internals that store/use the slot (line 264 handler and the stored field):
  Change the stored field type to `TypedActorRef[SNAPSyncController.Command]`.
  Messages to snapSyncController are via typed `!` — no adapter needed.

Step 3 — Fix callers (both use `.toClassic` which becomes redundant):
  - `SyncController.scala:1668`: `RegisterSnapSyncController(snapSync.toClassic)` → `RegisterSnapSyncController(snapSync)`
  - `SyncController.scala:2079`: `RegisterSnapSyncController(recoverySnapAdapter.toClassic)` → `RegisterSnapSyncController(recoverySnapAdapter)`
  Note: `recoverySnapAdapter` is `TypedActorRef[SNAPSyncController.Command]` — types already match.

Step 4 — Check for any other callers: `grep -rn "RegisterSnapSyncController" src/main/ --include="*.scala"`

After all edits:
1. `sbt compile-all` — must be clean
2. Bridge census — expected: 15 (down from 17)
3. Commit: `git commit -m "fix(8k-B2): NPMA RegisterSnapSyncController Classic→Typed — bridges: 17→15"`
4. Update §8k-B gate table.
```

---

#### §8k-B3 — SNAPSyncController chainDownloaderReplyAdapter: Any → typed Done adapter (1 bridge)

**File:** `SNAPSyncController.scala:548-555`
**Agent:** LOOM
**Gate:** ChainDownloader already uses `replyTo: TypedActorRef[Done.type]` (Behavior[Command], S6 narrowed).

**Prompt:**
```
In SNAPSyncController.scala, the field `chainDownloaderReplyAdapter` (around line 548) is declared as
`org.apache.pekko.actor.ActorRef` via `.toClassic`. It handles two message types via `messageAdapter[Any]`:
`ChainDownloader.Done` and `ChainDownloader.Progress`.

ChainDownloader.start() already accepts `replyTo: TypedActorRef[Done.type]` — progress is polled
separately via `GetProgress(replyTo: TypedActorRef[Progress])`.

The bridge is unnecessary. Fix:

Step 1 — Replace the `Any` adapter with a typed Done adapter:
  ```scala
  private val chainDownloaderReplyAdapter: TypedActorRef[ChainDownloader.Done.type] =
    ctx.messageAdapter[ChainDownloader.Done.type](_ => ChainDownloaderDone)
  ```
  Remove the `Progress` case — SSC already polls progress via `GetProgress` separately.
  Verify that no code in SSC relies on Progress arriving via this adapter (search for uses of
  `chainDownloaderReplyAdapter`).

Step 2 — Find all sites where `chainDownloaderReplyAdapter` is passed to `ChainDownloader.start()` or similar.
  Confirm the type matches `TypedActorRef[Done.type]` — no `.toClassic` needed.

Step 3 — Remove the Classic type annotation and the `.toClassic` call.

Step 4:
1. `sbt compile-all` — must be clean
2. Bridge census — expected: 14 (down from 15)
3. Commit: `git commit -m "fix(8k-B3): SSC chainDownloaderReplyAdapter Any→Typed Done — bridges: 15→14"`
4. Update §8k-B gate table.
```

---

#### §8k-B4 — BlockImporter: selfClassic + fetcherReplyTo elimination (2 bridges)

**File:** `BlockImporter.scala:207,:214`
**Agent:** LOOM
**Gate:** Requires survey of `selfClassic` and `fetcherReplyTo` usages within BlockImporter.

**Prompt:**
```
BlockImporter.scala has two Classic bridge fields (lines 207-214):
  - `private val selfClassic = ctx.self.toClassic`  (line 207)
  - `private val fetcherReplyTo: ActorRef = fetcherResponseAdapter.toClassic`  (line 214)

Survey how each is used:
  `grep -n "selfClassic\|fetcherReplyTo" src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockImporter.scala`

For `selfClassic`: If used as a sender in Classic `tell()` calls, replace each site with a typed
adapter (`ctx.messageAdapter`) or change the callee to accept Typed replyTo.

For `fetcherReplyTo`: `fetcherResponseAdapter` is `TypedActorRef[BlockFetcher.FetchResponse]`.
If `BlockFetcher.start()` accepts `replyTo: TypedActorRef[FetchResponse]`, pass `fetcherResponseAdapter`
directly (no `.toClassic`). Verify `BlockFetcher` interface.

Fix whichever is addressable without a full LOOM migration of BlockImporter. If `selfClassic` requires
replacing Classic `!` sends with typed patterns, do the minimum: either convert the specific send site
or add a typed message wrapper.

After fixes:
1. `sbt compile-all` — must be clean
2. Bridge census — expected: 12 or 13 depending on how many sites are fixable
3. Commit: `git commit -m "fix(8k-B4): BlockImporter selfClassic+fetcherReplyTo bridge reduction — bridges: 14→<N>"`
4. Update §8k-B gate table.
```

---

#### §8k-B5 — PivotBlockSelector: blockHeadersAdapter sender bridges (2 bridges)

**File:** `PivotBlockSelector.scala:418,:579`
**Agent:** LOOM or MITHRIL
**Gate:** None — this is a standalone refactor of the Classic `tell(msg, sender)` pattern.

**Prompt:**
```
PivotBlockSelector.scala has two `.toClassic` bridges at lines 418 and 579:
  `networkPeerManager.tell(NetworkPeerManagerActor.SendMessage(msg, peer), blockHeadersAdapter.toClassic)`

Both convert `blockHeadersAdapter: TypedActorRef[...]` to a Classic sender for a Classic `tell(msg, sender)`.
The Classic sender pattern means: "deliver my response to this ref as the sender."

Fix: Replace the Classic tell-with-sender with the typed adapter pattern already in use elsewhere in this file.

Step 1 — Check how other message sends in PivotBlockSelector route responses back (look for messageAdapter usages):
  `grep -n "messageAdapter\|peerEventBus\|SubscribeCmd" src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/PivotBlockSelector.scala | head -20`

Step 2 — Replace `networkPeerManager.tell(msg, blockHeadersAdapter.toClassic)` with the typed adapter pattern:
  Subscribe to the response via peerEventBus (using `SubscribeCmd` + typed messageAdapter), then tell NPMA
  to SendMessage without a sender. This matches how other request/response flows work in this file.

Step 3 — Verify there are no other callers that depend on Classic sender routing for these messages.

After fixes:
1. `sbt compile-all` — must be clean
2. Bridge census — expected: 10 (down from 12)
3. Commit: `git commit -m "fix(8k-B5): PivotBlockSelector blockHeadersAdapter typed subscription pattern — bridges: 12→10"`
4. Update §8k-B gate table.
```

---

#### §8k-B6 — PeerEventBusActor: Typed migration + NodeBuilder + PeerRequestHandler (4 bridges)

**Files:** `PeerEventBusActor.scala:42`, `NodeBuilder.scala:419,:1009`, `PeerRequestHandler.scala:78`
**Agent:** LOOM (full Typed migration sprint)
**Gate:** None blocking — PEB migration unlocks adapter import removal for RegularSync, FastSyncBranchResolverActor, and 10+ other files.

**Prompt:**
```
PeerEventBusActor currently passes `.toClassic` in its Pekko Streams `messageSource` at line 42:
  `.watch(peerEventBus.toClassic)`

And is wired as Classic in NodeBuilder:
  - Line 419: `networkPeerManager spawn site` passes `.toClassic`
  - Line 1009: `peerEventBus.toClassic` passed into SyncController wiring

PeerRequestHandler:78 also converts the event adapter:
  `networkPeerManager.tell(SendMessage(...), peerEventAdapter.toClassic)`

This is a LOOM migration sprint for PeerEventBusActor. Run the standard LOOM pre-flight first:
  `grep -n "sender()\|context\.actorOf\|context\.parent\|preStart\|postStop" src/main/scala/com/chipprbots/ethereum/network/PeerEventBusActor.scala`

Migration plan:
1. PEB is already `Behavior[Command]` — audit that all Command variants use typed replyTo
2. Fix `messageSource` in PEB: `.watch(peerEventBus.toClassic)` → `.watch(peerEventBus)` requires
   Pekko Streams typed actor integration. Check if `.watch` on a typed ref is supported in the Pekko
   Streams version in use, or use `.watchTermination()` + a typed adapter.
3. Update `NodeBuilder:419` and `:1009` to pass typed PEB ref directly
4. Update `PeerRequestHandler:78`: replace Classic `tell(msg, sender)` with typed pattern
   (message adapter subscription via PEB, not Classic sender)

After migration:
1. `sbt compile-all` — must be clean
2. Bridge census — expected: 6 (down from 10)
3. Test: `./local/scripts/fukuii-test PeerEventBusActorSpec` (or equivalent)
4. Run §8k-B adapter import removal pass — PEB migration unlocks removal of adapter imports
   in: RegularSync, FastSyncBranchResolverActor, SyncStateSchedulerActor, and potentially others.
5. Commit: `git commit -m "fix(8k-B6): PEB Typed migration + NodeBuilder/PeerRequestHandler bridges — bridges: 10→6"`
6. Update §8k-B gate table.
```

---

#### §8k-B7 — AkkaTaskOps: typed ask refactor (1 bridge)

**File:** `AkkaTaskOps.scala:37`
**Agent:** MITHRIL or CONDUIT
**Gate:** None — standalone refactor.

**Prompt:**
```
AkkaTaskOps.scala:37 contains:
  `IO.fromFuture(IO(to.ask[A](typedRef => makeCmd(typedRef.toClassic))))`

The `makeCmd: ActorRef => C` lambda converts the typed temp replyTo to Classic because command
variants use `replyTo: ActorRef` (Classic). The `askForTyped` sibling below it already handles
the Typed case correctly (no `.toClassic`).

This bridge exists because some callers still have `replyTo: ActorRef` in their command ADTs.

Audit which command variants are passed via `askFor` (search all call sites):
  `grep -rn "\.askFor[^T]" src/main/ --include="*.scala"`

For each command variant found: change `replyTo: ActorRef` → `replyTo: TypedActorRef[A]` in the
Command ADT, update the handler to use typed `!`, and update the caller to use `askForTyped` instead.

Once all callers use typed replyTo, delete the `askFor` method entirely and remove the `.toClassic` bridge.

After all command variants are migrated:
1. `sbt compile-all` — must be clean
2. Bridge census — expected: 5 (TCP floor — down from 6)
3. Commit: `git commit -m "fix(8k-B7): AkkaTaskOps askFor Classic replyTo → typed — bridges: 6→5 (TCP floor)"`
4. Run final §8k-B adapter import removal pass — verify no additional imports are removable.
5. Update §8k-B status to COMPLETE. TCP floor achieved.
```

---

**Execution order:** §8k-B1 → §8k-B2 → §8k-B3 → §8k-B4 → §8k-B5 → §8k-B6 → §8k-B7

§8k-B1 and §8k-B2 are immediate (no gate). §8k-B3 through §8k-B7 can proceed in parallel
if separate agents are available, but §8k-B6 (PEB migration) unlocks the most adapter import
removals and should be prioritized.

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
| **R6** ✅ | Opaque type domain analysis (map BigInt/ByteString semantic roles) — DONE, see `.local/docs/opaque-type-domain-analysis.md` | — | — |
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
| ~~J1~~ | ~~Batch J~~ | ~~**§8k-N** — MITHRIL: SyncController catch-all bridge elimination (10 sites) — all target actors already Typed; audit each catch-all arm, extend ADTs or handle explicitly, replace `.toClassic.tell`~~ | ✅ DONE `35db7dc61` (2026-06-25) |
| ~~J2~~ | ~~Batch J~~ | ~~**§8k-O** — MITHRIL: FastSync `fastSyncClassicSelf` + PivotBlockSelector/StateStorageActor bridge elimination (5 sites)~~ | ✅ DONE `fc5a3f8e7` (2026-06-25) — 4/5 sites; `fastSyncClassicSelf` remains pending SyncStateSchedulerActor migration |
| ~~J3~~ | ~~Batch J~~ | ~~**§8k-P** — MITHRIL: PeerEventBusActor caller narrowing — update `peerEventBus: ActorRef` → `TypedActorRef[PEB.Command]` across ~15 constructors; enables adapter import removal in 22+ files~~ | ✅ DONE (2026-06-25) |
| ~~J4~~ | ~~Batch J~~ | ~~**§8k-Q** — LOOM: SyncStateSchedulerActor Typed migration — fixes `FastSyncSpec "returns Syncing"` ClassCastException + deletes `fastSyncClassicSelf` (§8k-O 5th site)~~ | ✅ DONE `c4392fe87`/`f3b9fb04c` (2026-06-25) — 17/17 tests pass |
| ~~K1~~ | ~~Batch K~~ | ~~**R6** — MITHRIL: opaque type domain analysis (`BigInt`/`ByteString` semantic roles → `.local/docs/opaque-type-domain-analysis.md`)~~ | ✅ DONE 2026-06-25 |
| I1 | ETH Sprint (unblocked) | ~~**§ETH-T1-A**~~ ✅ ed4db9df9 · ~~**§ETH-T1-B**~~ ✅ 6f8f74708 · ~~**§ETH-T2-A**~~ ✅ c470b3dac + 35db7dc61 (§NAMING-A) · ~~**§ETH-T4-A**~~ ✅ 02aaa05fc KZG trusted setup · ~~**§ETH-T4-C**~~ ✅ b934caffe EIP-4788 beacon roots bytecode · ~~**§ETH-T4-D**~~ ✅ f6cf7fb9c blob base fee unification · ~~**§ETH-T6-A**~~ ✅ b696ve6b6 · ~~**§ETH-T6-B**~~ ✅ 525a1a911 · ~~**§ETH-T7-A**~~ ✅ ac0e25b62 · ~~**§ETH-T7-C**~~ ✅ 6e72ad2a0 · ~~**§ETH-T7-D**~~ ✅ c7cc5d131 | ✅ ALL DONE 2026-06-25 |
| I2 | ETH Sprint (gated) | ~~**§ETH-T4-B**~~ ✅ maxFeePerBlobGas validation · ~~**§ETH-T7-B**~~ ✅ cb2e2aec1 · ~~**§ETH-T1-C**~~ ✅ `89863ac80` · ~~**§ETH-T9-A**~~ ✅ · ~~**§ETH-T9-B**~~ ✅ `4ac7e2842` · ~~**§ETH-T9-C**~~ ✅ false positive · ~~**§ETH-T9-D**~~ ✅ SNAP sync ETH paths · ~~**§ETH-T10-A**~~ ✅ `b131a5ec7` · ~~**§ETH-T10-B**~~ ✅ a40750ce6 · ~~**§ETH-T10-C**~~ ✅ 3bc71fe51 · ~~**§ETH-T10-D**~~ ✅ 364e395dc | ✅ ALL DONE 2026-06-25 |

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

### §ETH-T6-A ✅ DONE `b696ve6b6` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T6-B ✅ DONE `525a1a911` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T7-A ✅ DONE `ac0e25b62` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T7-B ✅ DONE `cb2e2aec1` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T7-C ✅ DONE `6e72ad2a0` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T7-D ✅ DONE `c7cc5d131` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T7-E ✅ DONE `3d362be30` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T10-C ✅ DONE `3bc71fe51` — see `completed/DEFERRED-BACKLOG.md`

---

### §ETH-T10-D ✅ DONE `364e395dc` — see `completed/DEFERRED-BACKLOG.md`
