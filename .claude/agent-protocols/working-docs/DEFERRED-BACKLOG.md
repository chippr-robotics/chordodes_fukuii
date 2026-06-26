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

**Gate**: CAPSTONE complete + 7a done (sealed ADTs make failure typing cleaner). ✅ BOTH MET 2026-06-25.
**Priority**: Low — the current behavior is safe (default stop is conservative); explicit supervision
is a correctness/resilience improvement, not a bug fix.
**Agent**: PRISM (review) + LOOM (implementation per subsystem).

**PRISM audit complete 2026-06-25.** Design: `.local/docs/supervision-design-7c.md`
- 49 actors audited. Zero existing `Behaviors.supervise` wrappers.
- **6 STOP-AND-ALERT** (keep stop, add monitoring alert wrapper): PeerEventBusActor, PeerManagerActor,
  NetworkPeerManagerActor, SNAPSyncController, SyncController, SubscriptionManager
- **39 SAFE-TO-RESTART** across Thread Groups A/B/C (restart/restartWithBackoff per spec)
- **4 NEEDS-ANALYSIS** blocked on risk flags: RF-1 BlockImporter (→ forge idempotency check),
  RF-2 BlockFetcher ghost children, RF-3 SyncStateSchedulerActor storm bound
- **Implementation order:** Phase 1=Group D (alert wrappers) → A → B → C → E (risk-flagged)
- **Protocol needed:** `alert-wrapper-protocol.md` before Group D LOOM threads begin

#### §7c-P0 — Write alert-wrapper-protocol.md (prerequisite)

**Files:** `.claude/agent-protocols/alert-wrapper-protocol.md` (new)
**Agent:** Main session
**Gate:** None — do this first before any Group D LOOM threads.

**Prompt:**
```
Write `.claude/agent-protocols/alert-wrapper-protocol.md` to standardise the
STOP-AND-ALERT supervision pattern used in §7c Group D.

The pattern applies to actors where restart causes state corruption. Rather than
adding restart supervision, the parent (typically the guardian / NodeBuilder) is
amended to watchWith the critical child and emit a structured alarm on failure.

## Pattern

At spawn site in the parent behavior:

```scala
// 1. Spawn the critical actor
val criticalRef = ctx.spawn(CriticalActor(...), "critical-actor")

// 2. Register death-watch with a typed failure message
ctx.watchWith(criticalRef, CriticalActorFailed("critical-actor"))
```

Add `CriticalActorFailed(name: String)` to the parent's Command ADT (or
use a dedicated sealed trait if the parent has no Command ADT of its own).

Handle it in the parent's Behaviors.receive:

```scala
case CriticalActorFailed(name) =>
  log.error("CRITICAL actor stopped unexpectedly — node restart required: {}", name)
  // Do NOT restart the child. Propagate failure upward.
  Behaviors.stopped
```

## When to use

Apply to actors in the STOP-AND-ALERT class (§7c audit):
PeerEventBusActor, PeerManagerActor, NetworkPeerManagerActor,
SNAPSyncController, SyncController, SubscriptionManager.

## When NOT to use

Do not use for actors in the SAFE-TO-RESTART class — those get
`Behaviors.supervise(…).onFailure[Throwable](SupervisorStrategy.restart…)` instead.

## Variant: guardian with no Command ADT

If the guardian is a Behaviors.setup block with no explicit Command ADT,
add a private sealed trait inside the behavior scope:

```scala
Behaviors.setup[Any] { ctx =>
  sealed trait GuardianMsg
  case class CriticalActorFailed(name: String) extends GuardianMsg
  val child = ctx.spawn(CriticalActor(...), "critical-actor")
  ctx.watchWith(child, CriticalActorFailed("critical-actor"))
  Behaviors.receiveMessage {
    case CriticalActorFailed(name) =>
      log.error("CRITICAL: {} stopped — restarting node", name)
      Behaviors.stopped
    case other => Behaviors.same
  }
}
```

## Do not use Behaviors.supervise for these actors

The whole point of the STOP-AND-ALERT class is that restart is unsafe.
Never wrap STOP-AND-ALERT actors with SupervisorStrategy.restart.
```

Commit: `docs(7c-P0): add alert-wrapper-protocol.md — STOP-AND-ALERT supervision pattern`
```

---

#### §7c-D — Group D: STOP-AND-ALERT monitoring wrappers (6 actors)

**Files:** Spawn sites for PeerEventBusActor, PeerManagerActor, NetworkPeerManagerActor,
  SNAPSyncController, SyncController, SubscriptionManager — expected in `NodeBuilder.scala`
  / `StdNode.scala` / JSON-RPC server setup. Grep to confirm.
**Agent:** LOOM (or MITHRIL if spawn sites are pure wiring with no behavior logic)
**Gate:** §7c-P0 done (alert-wrapper-protocol.md written and understood).

**Prompt:**
```
Read `.claude/agent-protocols/alert-wrapper-protocol.md` first.
Read `.local/docs/supervision-design-7c.md` Part 1 (STOP-AND-ALERT section).

Task: apply the alert-wrapper pattern to the 6 STOP-AND-ALERT actors at their spawn sites.

Step 1 — Locate all 6 spawn sites:
  grep -rn "PeerEventBusActor\|PeerManagerActor\|NetworkPeerManagerActor\|SNAPSyncController\|SyncController\|SubscriptionManager" \
    src/main/scala --include="*.scala" | grep "ctx\.spawn\|system\.spawn\|actorOf"

Step 2 — For each spawn site, apply the pattern from alert-wrapper-protocol.md:
  - D1: PeerEventBusActor  spawn site
  - D2: PeerManagerActor   spawn site
  - D3: NetworkPeerManagerActor spawn site
  - D4: SNAPSyncController spawn site (inside SyncController)
  - D5: SyncController     spawn site (in NodeBuilder/StdNode)
  - D6: SubscriptionManager spawn site (in JSON-RPC server setup)

  For each: add `ctx.watchWith(ref, CriticalActorFailed("actor-name"))` immediately
  after the `ctx.spawn(...)` call. Add `CriticalActorFailed` to the parent's
  Command ADT. Add handler: log.error + Behaviors.stopped.

  If multiple STOP-AND-ALERT actors share the same parent, reuse a single
  `CriticalActorFailed(name: String)` case class — don't create one per actor.

Step 3 — sbt compile-all — must be clean.

Step 4 — sbt "testOnly *NodeBuilder* *StdNode* *Subscription*" if test coverage exists.

Step 5 — git commit -m "feat(7c-D): STOP-AND-ALERT watchWith alarm on 6 critical actors"
```

---

#### §7c-A — Group A: Infrastructure + application restart wrappers

**Files:** Spawn sites in NodeBuilder/StdNode for: ServerActor, KnownNodesManager,
  PeerStatisticsActor, PeerDiscoveryManager, OmmersPool, PendingTransactionsManager,
  FilterManager, FaucetHandler, PeriodicConsistencyCheck. PeerActor spawn in
  PeerManagerActor.peerFactory.
**Agent:** MITHRIL (pure spawn-site wiring changes)
**Gate:** §7c-D done.

**Prompt:**
```
Read `.local/docs/supervision-design-7c.md` Part 2 Group A.

Task: wrap SAFE-TO-RESTART actors at their spawn sites with Behaviors.supervise.
Import: `import org.apache.pekko.actor.typed.SupervisorStrategy`
        `import org.apache.pekko.actor.typed.scaladsl.Behaviors`
        `import scala.concurrent.duration._`

Apply these strategies (locate each ctx.spawn call first — grep for actor name):

  ServerActor:
    Behaviors.supervise(ServerActor(...)).onFailure[Throwable](
      SupervisorStrategy.restartWithBackoff(2.seconds, 60.seconds, 0.1))

  KnownNodesManager:
    Behaviors.supervise(KnownNodesManager(...)).onFailure[Throwable](SupervisorStrategy.restart)

  PeerStatisticsActor:
    Behaviors.supervise(PeerStatisticsActor(...)).onFailure[Throwable](SupervisorStrategy.restart)

  PeerDiscoveryManager:
    Behaviors.supervise(PeerDiscoveryManager(...)).onFailure[Throwable](SupervisorStrategy.restart)

  OmmersPool (ETC-only):
    Behaviors.supervise(OmmersPool(...)).onFailure[Throwable](SupervisorStrategy.restart)

  PendingTransactionsManager:
    Behaviors.supervise(PendingTransactionsManager(...)).onFailure[Throwable](
      SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3))

  FilterManager:
    Behaviors.supervise(FilterManager(...)).onFailure[Throwable](
      SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3))

  FaucetHandler:
    Behaviors.supervise(FaucetHandler(...)).onFailure[Throwable](SupervisorStrategy.restart)

  PeriodicConsistencyCheck:
    Behaviors.supervise(PeriodicConsistencyCheck(...)).onFailure[Throwable](
      SupervisorStrategy.restart.withMaxRestarts(3))

  PeerActor (spawn site in PeerManagerActor.peerFactory):
    Behaviors.supervise(PeerActor(...)).onFailure[Throwable](
      SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3))

  RLPxConnectionHandler — NO CHANGE (keep default stop; leave a comment explaining why).

Step N-1 — sbt compile-all — must be clean.
Step N   — git commit -m "feat(7c-A): restart supervision for 10 safe-to-restart infrastructure actors"
```

---

#### §7c-B — Group B: SNAP workers, coordinators, sync support

**Files:** Worker spawn sites in coordinator files; coordinator spawn sites in
  SNAPSyncController; sync support spawn sites in SyncController/NodeBuilder.
**Agent:** MITHRIL
**Gate:** §7c-A done.

**Prompt:**
```
Read `.local/docs/supervision-design-7c.md` Part 2 Groups B1/B2/B3.

Task: wrap SNAP workers, coordinators, and sync support actors at their spawn sites.

B1 — SNAP Workers (spawn sites inside each coordinator's worker-spawn call):
  AccountRangeWorker, ByteCodeWorker, StorageRangeWorker, TrieNodeHealingWorker:
    Behaviors.supervise(XxxWorker(...)).onFailure[Throwable](
      SupervisorStrategy.restart.withMaxRestarts(5))

B2 — SNAP Coordinators (spawn sites in SNAPSyncController):
  AccountRangeCoordinator, ByteCodeCoordinator,
  StorageRangeCoordinator, TrieNodeHealingCoordinator:
    Behaviors.supervise(XxxCoordinator(...)).onFailure[Throwable](
      SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3))

B3 — Sync support actors (locate spawn sites via grep):
  PeersClient:       restartWithBackoff(500.millis, 10.seconds, 0.2).withMaxRestarts(5)
  ChainDownloader:   restart
  PivotBlockSelector: restart
  PivotHeaderBootstrap: restart
  BlockchainHostActor: restart
  BytecodeRecoveryActor: restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(2)
  StorageRecoveryActor:  restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(2)
  CombinedRecoveryScanActor: restart
  StateStorageActor: restart
  FastSyncBranchResolverActor: restart
  BlockBroadcasterActor: restart
  PeerRequestHandler — NO CHANGE (self-limiting leaf, effectively irrelevant).

Step N-1 — sbt compile-all — must be clean.
Step N-2 — sbt "testOnly *SNAP* *Coordinator* *Worker*"
Step N   — git commit -m "feat(7c-B): restart supervision for SNAP workers, coordinators, sync support"
```

---

#### §7c-E1 — RF-1: BlockImporter write idempotency (forge consultation)

**Files:** `blockchain/sync/regular/BlockImporter.scala`, `blockchain/BlockchainWriter.scala`
**Agent:** FORGE (ETC consensus — write idempotency is a chain correctness question)
**Gate:** None — can run in parallel with §7c-A/B.

**Prompt:**
```
Use the FORGE agent.

Question: Is `BlockchainWriter.save(block, ...)` (or equivalent ETC block-write path)
idempotent? Specifically: if a block is written to RocksDB successfully and then the
same block is submitted again (same hash, same number), does it:
  (a) silently succeed (no-op / overwrite with identical data), or
  (b) throw an exception or corrupt state?

Context: `BlockImporter` has a companion-object `var survivedExhausts` that is
intentionally preserved across Pekko restarts (comment confirms restart is expected).
We want to know if adding `Behaviors.supervise(BlockImporter(...)).onFailure[Throwable](
SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3))`
at the BlockImporter spawn site is safe, or if a mid-import restart could cause a
duplicate write to corrupt ETC chain state.

Produce: a one-paragraph verdict with the relevant code path cited.
If idempotent → update §7c-C to include BlockImporter in the restart group.
If NOT idempotent → BlockImporter stays at default stop (no supervise wrapper).
```

---

#### §7c-C — Group C: RegularSync fetch children (RF-2 decision + implementation)

**Files:** `blockchain/sync/regular/BlockFetcher.scala`, `BlockImporter.scala`,
  `HeadersFetcher.scala`, `BodiesFetcher.scala`, `StateNodeFetcher.scala`, `RegularSync.scala`
**Agent:** MITHRIL (after RF-2 decision is made)
**Gate:** RF-2 decision confirmed (see risk flag below).

**RF-2 decision (choose before prompting MITHRIL):**

Option A (recommended): `BlockFetcher` → stop (keep default). `RegularSync` death-watches
  `BlockFetcher` and re-spawns it. `HeadersFetcher`/`BodiesFetcher`/`StateNodeFetcher` are
  children of BlockFetcher — they stop when BlockFetcher stops; RegularSync's re-spawn of
  BlockFetcher re-creates them. `BlockBroadcasterActor` → restart (already in Group A).

Option B: Add `PreRestart` signal handler to `BlockFetcher` to `ctx.stop` all children
  before restart, then wrap with restartWithBackoff. More complex, more fragile.

**Prompt (for Option A):**
```
Read `.local/docs/supervision-design-7c.md` Part 3 RF-2.

Decision: Option A — BlockFetcher keeps default stop; RegularSync handles re-spawn.

Step 1 — Confirm RegularSync currently death-watches BlockFetcher:
  grep -n "watch\|watchWith\|BlockFetcher" src/main/scala/.../RegularSync.scala
  If death-watch exists and re-spawn logic is present, proceed.
  If not, add ctx.watchWith(blockFetcherRef, BlockFetcherStopped) + re-spawn handler.

Step 2 — Confirm HeadersFetcher/BodiesFetcher/StateNodeFetcher are children of
  BlockFetcher (spawned in BlockFetcher constructor). If so, they stop automatically
  when BlockFetcher stops — no additional supervision needed.

Step 3 — Add a comment at the BlockFetcher spawn site in RegularSync explaining the
  decision (ghost-children risk with AbstractBehavior restart — see §7c RF-2):
  // BlockFetcher uses AbstractBehavior and spawns children in its constructor.
  // Pekko restart would re-run the constructor and ghost the old children.
  // Default stop-on-failure is intentional; RegularSync re-spawns on BlockFetcherStopped.

Step 4 — sbt compile-all — must be clean.
Step 5 — git commit -m "docs(7c-C): document BlockFetcher stop-on-failure rationale (RF-2 ghost-child risk)"
```

---

#### §7c-E3 — RF-3: SyncStateSchedulerActor (FastSync, storm-bounded restart)

**Files:** `blockchain/sync/fast/SyncStateSchedulerActor.scala` spawn site in FastSync
**Agent:** MITHRIL (after RF-3 confirmation)
**Gate:** FastSync spec review confirms acceptable re-request behaviour on restart.

**Prompt:**
```
Read `.local/docs/supervision-design-7c.md` Part 3 RF-3.

Precondition: confirm that re-requesting all in-flight peer assignments simultaneously
on SyncStateSchedulerActor restart is tolerable — FastSync will time out pending requests
regardless, so restart just accelerates the timeout. The PeerRateTracker re-initialises
from zero (conservative), which means the burst is rate-limited by the conservative
initial per-peer window, not an unconstrained flood.

If confirmed — apply at the SyncStateSchedulerActor spawn site (in FastSync):
  Behaviors.supervise(SyncStateSchedulerActor(...)).onFailure[Throwable](
    SupervisorStrategy.restartWithBackoff(5.seconds, 60.seconds, 0.3).withMaxRestarts(2))

sbt compile-all — must be clean.
git commit -m "feat(7c-E3): restartWithBackoff for SyncStateSchedulerActor (bounded storm)"
```

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
addresses, and arbitrary byte payloads. There are zero `opaque type` declarations. Without opaque
types, passing a block hash where a state root is expected compiles silently.

**R6 research complete** — see `.local/docs/opaque-type-domain-analysis.md` (2026-06-25).
Scale: 2,276 `BigInt` usages / 204 files · 2,305 `ByteString` usages / 243 files.

**Tiered candidate summary** (full detail in R6 doc):

| Tier | Candidate | Raw type | Files | Gate |
|------|-----------|----------|-------|------|
| LOW | `TxHash` | `ByteString` | 23 | None |
| LOW | `BloomFilter` | `ByteString` | ~30 | None |
| LOW | `BlobVersionedHash` | `ByteString` | ~15 | None (ETH-only) |
| MEDIUM | `StorageKey` Phase A (AccessListItem only) | `BigInt` | 7 | None |
| MEDIUM | `CodeHash` | `ByteString` | 32 | FORGE advisory |
| MEDIUM | `StorageKey` Phase B (ProgramState/EVM) | `BigInt` | ~12 | FORGE |
| HIGH | `BlockNumber`, `Difficulty`, `TotalDifficulty`, `GasAmount`, `GasPrice`, `ChainId`, `BlockHash`, `TrieRoot` | `BigInt`/`ByteString` | 25–124 | FORGE (consensus) |

**Caveats (from R6 doc):**
- `UInt256` and `Address` are hand-rolled wrappers (not opaque types) — do not introduce competing `Balance`/`Nonce` types that conflict with `Account.balance: UInt256`.
- `ECDSASignature(r, s, v: BigInt)` — crypto domain, leave as-is.
- HIGH tier deferred until LOW/MEDIUM establishes RLP `.xmap` pattern.

---

#### §8b-L1 — `TxHash`: ByteString → opaque type

**Files:** `domain/TxHash.scala` (new), `SignedTransaction.scala`, `jsonrpc/` (~23 files)
**Agent:** MITHRIL
**Gate:** None — cache key and API response only; no consensus or RLP serialization path.

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type TxHash = ByteString` in `src/main/`.

Context: R6 analysis confirmed `TxHash` crosses no consensus paths and has no RLP
serialization at the field level — the hash is computed, not decoded from wire bytes.
Reference: `.local/docs/opaque-type-domain-analysis.md` §1.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/TxHash.scala`:
```scala
package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

opaque type TxHash = ByteString
object TxHash:
  def apply(bs: ByteString): TxHash = bs
  def fromBytes(bs: ByteString): TxHash = bs
  extension (th: TxHash)
    def value: ByteString = th
    def toHexString: String = th.map("%02x".format(_)).mkString("0x", "", "")
```

Step 2 — Update `SignedTransaction.scala`:
  Change `def hash: ByteString` return type → `TxHash`. Wrap the existing `kec256(...)` result:
  `TxHash(kec256(...))`.

Step 3 — Update `txSenders` cache:
  Find `Cache[ByteString, Address]` where the key is a tx hash. Change key type to `Cache[TxHash, Address]`.
  Update the lookup site to wrap the raw ByteString with `TxHash(...)`.

Step 4 — Update all `jsonrpc/` callers:
  `grep -rn "\.hash" src/main/scala --include="*.scala" | grep -i "tx\|transaction"` to find usage sites.
  Where the return value flows into an API response field typed `ByteString`, call `.value` to unwrap.

Step 5 — `sbt compile-all` — must be clean.

Step 6 — `sbt "testOnly *SignedTransaction*" *TransactionPool*"` to verify no regressions.

Step 7 — `git commit -m "feat(8b-L1): introduce TxHash opaque type (ByteString) — 23 call sites"`
```

---

#### §8b-L2 — `BloomFilter`: ByteString → opaque type

**Files:** `domain/BloomFilter.scala` (new), `BlockHeader.scala`, `Receipt.scala`, jsonrpc response types (~30 files)
**Agent:** MITHRIL
**Gate:** None — post-execution metadata only; never used in fork dispatch or Ethash validation.

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type BloomFilter = ByteString` in `src/main/`.

Context: `logsBloom` is a fixed 256-byte field on `BlockHeader` and `Receipt`. Never used in
consensus validation (Ethash, fork dispatch, MESS). One-line RLP derivation via `.xmap`.
Reference: `.local/docs/opaque-type-domain-analysis.md` §2.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/BloomFilter.scala`:
```scala
package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString
import com.chipprbots.ethereum.rlp.RLPImplicits.given

opaque type BloomFilter = ByteString
object BloomFilter:
  val Empty: BloomFilter = ByteString(new Array[Byte](256))
  def apply(bs: ByteString): BloomFilter = bs
  extension (bf: BloomFilter)
    def value: ByteString = bf
  given rlpCodec: RLPCodec[BloomFilter] = summon[RLPCodec[ByteString]].xmap(BloomFilter.apply, _.value)
```

Step 2 — Update `BlockHeader.scala`: change `logsBloom: ByteString` → `logsBloom: BloomFilter`.
  In `BlockHeaderEnc.toRLPEncodable`: call `.value` at the RLP encode site (already a byteStringEncDec call).
  In `BlockHeaderDec.toBlockHeader`: wrap the decoded ByteString with `BloomFilter(...)`.

Step 3 — Update `Receipt.scala`: same pattern for `logsBloom: ByteString` → `BloomFilter`.

Step 4 — Update jsonrpc response types that expose `logsBloom` as a hex string.
  These call `.toHexString` or similar on the raw ByteString — call `.value.toHexString` instead.

Step 5 — `sbt compile-all` — must be clean.

Step 6 — `sbt "testOnly *BlockHeader* *Receipt*"` to verify no regressions.

Step 7 — `git commit -m "feat(8b-L2): introduce BloomFilter opaque type (ByteString) — ~30 sites"`
```

---

#### §8b-L3 — `BlobVersionedHash`: ByteString → opaque type

**Files:** `domain/BlobVersionedHash.scala` (new), `Transaction.scala` (`BlobTransaction`), `ETHPackets.scala`, jsonrpc blob response types (~15 files)
**Agent:** MITHRIL (ETH-only change — no BEACON gate needed; no consensus path touched)
**Gate:** None — EIP-4844 blob hash field, ETH-only, no Ethash/ETC paths.

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type BlobVersionedHash = ByteString` in `src/main/`.

Context: `blobVersionedHashes: List[ByteString]` in `BlobTransaction` is EIP-4844 specific and
ETH-only. ~15 files. RLP encoding in `getBlobTxBytesToSign` is already `RLPValue(h.toArray)` per
element — adding `.value` is surgical.
Reference: `.local/docs/opaque-type-domain-analysis.md` §3.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/BlobVersionedHash.scala`:
```scala
package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

opaque type BlobVersionedHash = ByteString
object BlobVersionedHash:
  def apply(bs: ByteString): BlobVersionedHash = bs
  extension (bvh: BlobVersionedHash)
    def value: ByteString = bvh
  given rlpCodec: RLPCodec[BlobVersionedHash] =
    summon[RLPCodec[ByteString]].xmap(BlobVersionedHash.apply, _.value)
```

Step 2 — Update `Transaction.scala` `BlobTransaction`:
  `blobVersionedHashes: List[ByteString]` → `List[BlobVersionedHash]`.

Step 3 — Update `ETHPackets.scala` codec sites:
  In `getBlobTxBytesToSign`: `RLPValue(h.toArray)` → `RLPValue(h.value.toArray)`.
  In the blob tx decoder: wrap raw `ByteString` results with `BlobVersionedHash(...)`.

Step 4 — Update jsonrpc blob response fields that expose the list as hex strings.

Step 5 — `sbt compile-all` — must be clean.

Step 6 — `sbt "testOnly *BlobTransaction* *ETH*"` to verify no regressions.

Step 7 — `git commit -m "feat(8b-L3): introduce BlobVersionedHash opaque type (ByteString) — ~15 sites"`
```

---

#### §8b-M1 — `StorageKey` Phase A: AccessListItem (no EVM touch)

**Files:** `domain/StorageKey.scala` (new), `Transaction.scala` (`AccessListItem`), `ETHPackets.scala` EIP-2930 codec (~7 files directly)
**Agent:** MITHRIL
**Gate:** None for Phase A — `AccessListItem` does not touch EVM opcode dispatch. Phase B (ProgramState) requires FORGE.

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type StorageKey = BigInt` in `src/main/` — Phase A only
(AccessListItem; do NOT touch ProgramState or EthereumUInt256Mpt in this pass).

Context: `AccessListItem.storageKeys: List[BigInt]` identifies EVM storage slot keys (EIP-2929/2930).
An opaque type prevents accidental swap with storage values at compile time. Phase A is safe because
AccessListItem is never read inside the EVM opcode dispatcher directly — it flows in through
transaction validation and into the access-list prewarming step.
Reference: `.local/docs/opaque-type-domain-analysis.md` §4 Phase A.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/StorageKey.scala`:
```scala
package com.chipprbots.ethereum.domain

opaque type StorageKey = BigInt
object StorageKey:
  def apply(v: BigInt): StorageKey = v
  extension (sk: StorageKey)
    def value: BigInt = sk
  given rlpCodec: RLPCodec[StorageKey] = summon[RLPCodec[BigInt]].xmap(StorageKey.apply, _.value)
```

Step 2 — Update `Transaction.scala` `AccessListItem`:
  `storageKeys: List[BigInt]` → `List[StorageKey]`.

Step 3 — Update `ETHPackets.scala` EIP-2930 / EIP-1559 typed transaction codecs:
  At the decode site, wrap each decoded BigInt: `StorageKey(bigInt)`.
  At the encode site, call `.value` to unwrap.

Step 4 — Check for other AccessListItem callers:
  `grep -rn "AccessListItem\|storageKeys" src/main/ --include="*.scala"`
  Update any site that constructs or deconstructs `storageKeys`.
  STOP if you reach `ProgramState.scala` or `EthereumUInt256Mpt` — those are Phase B (FORGE gate).

Step 5 — `sbt compile-all` — must be clean.

Step 6 — `sbt "testOnly *Transaction* *AccessList*"` to verify no regressions.

Step 7 — `git commit -m "feat(8b-M1): StorageKey opaque type Phase A — AccessListItem only, ~7 files"`
```

---

#### §8b-M2 — `CodeHash`: ByteString → opaque type (FORGE advisory)

**Files:** `domain/CodeHash.scala` (new), `Account.scala`, `BlockchainReader.scala`, state trie layer (~32 files)
**Agent:** MITHRIL + FORGE advisory
**Gate:** FORGE advisory — EIP-161 (empty-account) and EIP-684 (collision) touch consensus semantics. The opaque wrapper itself is safe (preserves `==`), but FORGE should confirm before merging.

**Prompt:**
```
Use the MITHRIL agent to implement `opaque type CodeHash = ByteString`, then ask FORGE to review
before committing.

Context: `Account.codeHash` and `Account.EmptyCodeHash` are used in EIP-161 empty-account checks
and EIP-684 collision detection — consensus semantics, but the opaque wrapper does NOT change
runtime equality (Scala 3 opaque types preserve the underlying `==`). FORGE review is a gate
on the commit, not on implementation.
Reference: `.local/docs/opaque-type-domain-analysis.md` §5.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/CodeHash.scala`:
```scala
package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

opaque type CodeHash = ByteString
object CodeHash:
  val Empty: CodeHash = ByteString(new Array[Byte](32))  // kec256(ByteString.empty)
  def apply(bs: ByteString): CodeHash = bs
  extension (ch: CodeHash)
    def value: ByteString = ch
    def isEmpty: Boolean = ch == Empty
  given rlpCodec: RLPCodec[CodeHash] = summon[RLPCodec[ByteString]].xmap(CodeHash.apply, _.value)
```

Step 2 — Update `Account.scala`:
  `codeHash: ByteString` → `codeHash: CodeHash`.
  `EmptyCodeHash: ByteString` → `val EmptyCodeHash: CodeHash = CodeHash.Empty`.
  In `AccountEnc`/`AccountDec`: wrap/unwrap at the codec boundary only.

Step 3 — Update all sites that reference `Account.codeHash` or `Account.EmptyCodeHash`:
  `grep -rn "codeHash\|EmptyCodeHash" src/main/ --include="*.scala"`
  For EIP-161 check (`codeHash == Account.EmptyCodeHash`): the `==` still works through the opaque type — no change needed.
  For EIP-684 check: same.
  For codec sites: add `.value` to unwrap to ByteString where needed.

Step 4 — `sbt compile-all` — must be clean.

Step 5 — FORGE review: present the diff to the FORGE agent and confirm:
  (a) EIP-161 equality check semantics are preserved.
  (b) EIP-684 check semantics are preserved.
  (c) No consensus byte encoding is altered.

Step 6 (after FORGE confirms) — `sbt "testOnly *Account* *State*"`.

Step 7 — `git commit -m "feat(8b-M2): CodeHash opaque type (ByteString) — Account + EIP-161/684 sites, FORGE-reviewed"`
```

---

#### §8b-M3 — `StorageKey` Phase B: ProgramState / EVM (FORGE gate)

**Files:** `ProgramState.scala`, `EthereumUInt256Mpt.scala`, EVM opcode warm-storage sites
**Agent:** FORGE (consensus-touching — EVM opcode dispatch, EIP-2929 warm storage check)
**Gate:** FORGE — `ProgramState.addAccessedStorageKey` flows through EVM opcode dispatcher.
**Prerequisite:** §8b-M1 must be committed first (establishes `StorageKey` type).

**Prompt:**
```
Use the FORGE agent. Extend `StorageKey` opaque type from §8b-M1 into `ProgramState` and the
EVM storage layer (Phase B).

Context: §8b-M1 introduced `StorageKey` and applied it to `AccessListItem`. Phase B extends this
into the EVM warm-storage check (`ProgramState.addAccessedStorageKey`) and `EthereumUInt256Mpt`.
This crosses EVM opcode dispatch (EIP-2929), so FORGE review is mandatory.
Reference: `.local/docs/opaque-type-domain-analysis.md` §4 Phase B.

Pre-flight:
1. Confirm §8b-M1 is committed: `grep "opaque type StorageKey" src/main/scala/com/chipprbots/ethereum/domain/StorageKey.scala`
2. Confirm `sbt compile-all` is clean before starting.

Step 1 — Update `ProgramState.scala`:
  `storageKey: BigInt` → `StorageKey` in any field or method that represents an EVM storage slot key.
  `addAccessedStorageKey(address: Address, key: BigInt)` → `key: StorageKey`.
  At all call sites that pass a raw BigInt, wrap with `StorageKey(bigInt)`.

Step 2 — Update `EthereumUInt256Mpt`:
  Review whether the `BigInt` key here is a storage slot (→ `StorageKey`) or a general integer index
  (→ leave as `BigInt`). Only change if it is clearly a storage slot key.

Step 3 — Verify EVM SLOAD/SSTORE opcode sites:
  `grep -rn "addAccessedStorageKey\|isStorageKeyWarm" src/main/ --include="*.scala"`
  Confirm all call sites pass `StorageKey(...)` not bare `BigInt`.

Step 4 — `sbt compile-all` — must be clean.

Step 5 — `sbt testVM` — EVM opcode tests must pass.

Step 6 — `sbt "testOnly *ProgramState* *EthereumUInt256Mpt*"`.

Step 7 — `git commit -m "feat(8b-M3): StorageKey Phase B — ProgramState + EVM warm-storage check, FORGE-reviewed"`
```

---

#### §8b-H1 — `BlockHash`: ByteString → opaque type

**Files:** `domain/BlockHash.scala` (new), `BlockHeader.scala`, `ETHPackets.scala`, `ETH68.scala`, `ETH69.scala`, chain storage layer (~50 files)
**Agent:** FORGE (ETC block propagation, fork ID) + BEACON (ETH block hash fields, `parentBeaconBlockRoot`)
**Gate:** FORGE + BEACON — block hash appears in P2P wire protocol, fork ID computation, and chain validation on both chains.
**Prerequisite:** §8b-L1/L2/L3 committed (RLP `.xmap` pattern proven in CI).

**Prompt:**
```
Use the FORGE agent for ETC paths and BEACON agent for ETH paths. Introduce
`opaque type BlockHash = ByteString` in `src/main/`.

Context: `parentHash`, `ommersHash`, `mixHash`, `hash` (computed), and `parentBeaconBlockRoot`
are all raw `ByteString`. An opaque `BlockHash` prevents passing a `stateRoot` where a
`parentHash` is expected. Equality semantics are preserved (opaque types delegate `==`
to the underlying type), so `header.parentHash == parent.hash` remains correct.
Reference: `.local/docs/opaque-type-domain-analysis.md` §BlockHash.

Pre-flight:
  `grep -rn "parentHash\|ommersHash\|mixHash\|parentBeaconBlockRoot" src/main/ --include="*.scala" | wc -l`
  `sbt compile-all` — must be clean before starting.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/BlockHash.scala`:
```scala
package com.chipprbots.ethereum.domain
import org.apache.pekko.util.ByteString

opaque type BlockHash = ByteString
object BlockHash:
  val Zero: BlockHash = ByteString(new Array[Byte](32))
  def apply(bs: ByteString): BlockHash = bs
  extension (bh: BlockHash)
    def value: ByteString = bh
    def toHexString: String = bh.map("%02x".format(_)).mkString("0x", "", "")
  given rlpCodec: RLPCodec[BlockHash] = summon[RLPCodec[ByteString]].xmap(BlockHash.apply, _.value)
```

Step 2 — Update `BlockHeader.scala`:
  Fields: `parentHash`, `ommersHash`, `mixHash`, `parentBeaconBlockRoot` → `BlockHash`.
  `def hash: ByteString` (computed via kec256) → `def hash: BlockHash`; wrap result: `BlockHash(kec256(...))`.
  In `BlockHeaderEnc.toRLPEncodable`: call `.value` at each BlockHash field.
  In `BlockHeaderDec.toBlockHeader`: wrap decoded ByteString fields with `BlockHash(...)`.

Step 3 — Update `ETHPackets.scala`, `ETH68.scala`, `ETH69.scala`:
  Message fields carrying block hashes (e.g., `GetBlockHeaders`, `BlockHeaders`, `NewBlockHashes`):
  wrap at decode sites, unwrap with `.value` at encode sites.

Step 4 — Update chain storage:
  `grep -rn "parentHash\|blockHash\|\.hash" src/main/ --include="*.scala" | grep -v "txHash\|codeHash\|stateRoot\|receiptsRoot"` 
  Update storage read/write sites in `BlockchainReader`, `BlockchainWriter`, `ChainWeightStorage`.

Step 5 — Fork ID: confirm `ForkId` computation still works — it hashes chain history.
  FORGE: verify `EthashBlockHeaderValidator` block hash usage is unchanged.
  BEACON: verify `parentBeaconBlockRoot` ETH consensus path is unchanged.

Step 6 — `sbt compile-all` — must be clean.

Step 7 — `sbt "testOnly *BlockHeader* *ETHPackets* *Blockchain*"`.

Step 8 — FORGE + BEACON review diff before committing.

Step 9 — `git commit -m "feat(8b-H1): BlockHash opaque type (ByteString) — ~50 files, FORGE+BEACON reviewed"`
```

---

#### §8b-H2 — `TrieRoot`: ByteString → opaque type

**Files:** `domain/TrieRoot.scala` (new), `BlockHeader.scala`, `Account.scala`, `SyncStateSchedulerActor.scala`, SNAP sync layer (~58 files)
**Agent:** FORGE (state root is ETC world-state commitment, SNAP sync pivot) + BEACON (ETH `withdrawalsRoot`, `requestsHash`)
**Gate:** FORGE + BEACON — state root is the highest-risk field in the codebase. Wrong encoding breaks chain sync.
**Prerequisite:** §8b-H1 committed (ByteString opaque type pattern established for hashes).

**Prompt:**
```
Use the FORGE agent for ETC paths (stateRoot, SNAP sync pivot) and BEACON agent for ETH paths
(withdrawalsRoot, requestsHash). Introduce `opaque type TrieRoot = ByteString` in `src/main/`.

Context: `stateRoot`, `transactionsRoot`, `receiptsRoot`, `withdrawalsRoot`, `requestsHash`
(BlockHeader), and `storageRoot` (Account) are all raw `ByteString`. A single `TrieRoot`
opaque type covers all trie commitment hashes — passing `stateRoot` where `receiptsRoot` is
expected becomes a compile error. The RLP codec is one-line `.xmap`.
Reference: `.local/docs/opaque-type-domain-analysis.md` §TrieRoot.

Pre-flight:
  `grep -rn "stateRoot\|transactionsRoot\|receiptsRoot\|withdrawalsRoot\|requestsHash\|storageRoot" src/main/ --include="*.scala" | wc -l`

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/TrieRoot.scala`:
```scala
package com.chipprbots.ethereum.domain
import org.apache.pekko.util.ByteString

opaque type TrieRoot = ByteString
object TrieRoot:
  val Empty: TrieRoot = ByteString(new Array[Byte](32))  // kec256(RLP([]))
  def apply(bs: ByteString): TrieRoot = bs
  extension (tr: TrieRoot)
    def value: ByteString = tr
    def toHexString: String = tr.map("%02x".format(_)).mkString("0x", "", "")
  given rlpCodec: RLPCodec[TrieRoot] = summon[RLPCodec[ByteString]].xmap(TrieRoot.apply, _.value)
```

Step 2 — Update `BlockHeader.scala`:
  `stateRoot`, `transactionsRoot`, `receiptsRoot`, `withdrawalsRoot`, `requestsHash` → `TrieRoot`.
  In `BlockHeaderEnc`/`BlockHeaderDec`: wrap/unwrap at codec boundary.

Step 3 — Update `Account.scala`:
  `storageRoot: ByteString` → `TrieRoot`.
  In `AccountEnc`/`AccountDec`: wrap/unwrap.

Step 4 — Update SNAP sync actors:
  `grep -rn "stateRoot\|storageRoot\|pivotStateRoot" src/main/ --include="*.scala" | grep -i "snap\|sync\|pivot"`
  These carry trie roots as sync pivot points — update field types and constructor calls.

Step 5 — Update blockchain reader/writer and state trie layer:
  `grep -rn "stateRoot\|storageRoot" src/main/ --include="*.scala" | grep -v "//\|import"`
  Add `.value` at MPT API boundaries where raw `ByteString` is required.

Step 6 — FORGE: verify SNAP sync pivot root handling is byte-identical to Besu/go-ethereum.
  BEACON: verify `withdrawalsRoot` and `requestsHash` ETH fields are unchanged in EIP handling.

Step 7 — `sbt compile-all` — must be clean.

Step 8 — `sbt "testOnly *BlockHeader* *Account* *Snap* *State*"`.

Step 9 — FORGE + BEACON review diff before committing.

Step 10 — `git commit -m "feat(8b-H2): TrieRoot opaque type (ByteString) — ~58 files, FORGE+BEACON reviewed"`
```

---

#### §8b-H3 — `Difficulty`: BigInt → opaque type (ETC only)

**Files:** `domain/Difficulty.scala` (new), `BlockHeader.scala`, `EthashDifficultyCalculator.scala`, `TargetTimeDifficultyCalculator.scala`, `ArtificialFinality.scala`, `EthashBlockHeaderValidator.scala` (~47 files)
**Agent:** FORGE only — Difficulty is ETC/Ethash specific; no ETH path uses it post-merge.
**Gate:** FORGE — arithmetic operations on `difficulty` feed directly into Ethash validation.

**Prompt:**
```
Use the FORGE agent. Introduce `opaque type Difficulty = BigInt` in `src/main/`.

Context: `BlockHeader.difficulty`, `MinimumDifficulty`, `DifficultyBoundDivision`, and all
Ethash difficulty calculator inputs/outputs are raw `BigInt`. Arithmetic is heavy here
(division, addition, max/min). Define arithmetic extensions on `Difficulty` to avoid `.value`
noise at every calculation site.
Reference: `.local/docs/opaque-type-domain-analysis.md` §Difficulty.

Pre-flight: `grep -rn "difficulty" src/main/ --include="*.scala" | grep -v "//\|import\|total\|Difficulty\b" | wc -l`

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/Difficulty.scala`:
```scala
package com.chipprbots.ethereum.domain

opaque type Difficulty = BigInt
object Difficulty:
  val Zero: Difficulty = BigInt(0)
  val Minimum: Difficulty = BigInt(131072)  // MinimumDifficulty from Ethash spec
  def apply(v: BigInt): Difficulty = v
  extension (d: Difficulty)
    def value: BigInt = d
    def +(other: Difficulty): Difficulty = d.value + other.value
    def -(other: Difficulty): Difficulty = (d.value - other.value).max(Minimum.value)
    def /(divisor: BigInt): Difficulty = d.value / divisor
    def *(factor: BigInt): Difficulty = d.value * factor
    def max(other: Difficulty): Difficulty = d.value.max(other.value)
    def >=(other: Difficulty): Boolean = d.value >= other.value
    def >(other: Difficulty): Boolean = d.value > other.value
    def <(other: Difficulty): Boolean = d.value < other.value
  given rlpCodec: RLPCodec[Difficulty] = summon[RLPCodec[BigInt]].xmap(Difficulty.apply, _.value)
```

Step 2 — Update `BlockHeader.scala`: `difficulty: BigInt` → `Difficulty`.
  In `BlockHeaderEnc`/`BlockHeaderDec`: wrap/unwrap at codec boundary.

Step 3 — Update `EthashDifficultyCalculator.scala` and `TargetTimeDifficultyCalculator.scala`:
  All inputs and outputs involving difficulty become `Difficulty`. Arithmetic ops use extensions above.

Step 4 — Update `EthashBlockHeaderValidator.scala`:
  Difficulty bound checks: `header.difficulty >= calculatedDifficulty` — types must both be `Difficulty`.

Step 5 — Update `ArtificialFinality.scala` (MESS):
  MESS uses difficulty in the anti-reorg weight comparison — update field types.

Step 6 — `grep -rn "MinimumDifficulty\|DifficultyBoundDivision\|\.difficulty" src/main/ --include="*.scala"` — fix remaining sites.

Step 7 — `sbt compile-all` — must be clean.

Step 8 — FORGE: verify Ethash difficulty calculation output matches go-ethereum byte-for-byte on at least one known block.

Step 9 — `sbt "testOnly *Ethash* *Difficulty* *BlockHeader*"`.

Step 10 — `git commit -m "feat(8b-H3): Difficulty opaque type (BigInt) — ~47 files, FORGE-reviewed"`
```

---

#### §8b-H4 — `TotalDifficulty`: BigInt → opaque type

**Files:** `domain/TotalDifficulty.scala` (new), `ChainWeight.scala`, `ChainWeightStorage.scala`, `ETHPackets.scala`, `BlockchainConfig.scala` (~26 files)
**Agent:** FORGE (MESS anti-reorg, ETC handshake TD) + BEACON (ETH `terminalTotalDifficulty` merge gate)
**Gate:** FORGE + BEACON — `terminalTotalDifficulty` is the ETH PoS merge gate; wrong value breaks ETH consensus.

**Prompt:**
```
Use the FORGE agent for ETC paths (MESS, ChainWeight, P2P handshake TD) and BEACON agent for
ETH paths (terminalTotalDifficulty merge gate). Introduce `opaque type TotalDifficulty = BigInt`.

Context: `ChainWeight.totalDifficulty`, `terminalTotalDifficulty` in `BlockchainConfig`,
and TD fields in P2P status messages are all raw `BigInt`. MESS compares total difficulties
for anti-reorg; the ETH merge checks `>= terminalTotalDifficulty`.
Reference: `.local/docs/opaque-type-domain-analysis.md` §TotalDifficulty.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/TotalDifficulty.scala`:
```scala
package com.chipprbots.ethereum.domain

opaque type TotalDifficulty = BigInt
object TotalDifficulty:
  val Zero: TotalDifficulty = BigInt(0)
  def apply(v: BigInt): TotalDifficulty = v
  extension (td: TotalDifficulty)
    def value: BigInt = td
    def +(d: Difficulty): TotalDifficulty = td.value + d.value  // accumulate block difficulty
    def >=(other: TotalDifficulty): Boolean = td.value >= other.value
    def >(other: TotalDifficulty): Boolean = td.value > other.value
    def <(other: TotalDifficulty): Boolean = td.value < other.value
  given rlpCodec: RLPCodec[TotalDifficulty] = summon[RLPCodec[BigInt]].xmap(TotalDifficulty.apply, _.value)
```
Note: the `+(d: Difficulty)` extension requires `Difficulty` from §8b-H3 — that must be committed first.

Step 2 — Update `ChainWeight.scala`: `totalDifficulty: BigInt` → `TotalDifficulty`.

Step 3 — Update `ChainWeightStorage.scala`: storage codec and retrieval.

Step 4 — Update `BlockchainConfig.scala`: `terminalTotalDifficulty: BigInt` → `TotalDifficulty`.
  BEACON: verify `isPoS` check (`totalDifficulty >= terminalTotalDifficulty`) uses `TotalDifficulty.>=`.

Step 5 — Update `ETHPackets.scala` Status message:
  `td: BigInt` in `Status` → `TotalDifficulty`. Wrap at decode, unwrap at encode.

Step 6 — Update MESS in `ArtificialFinality.scala`:
  FORGE: verify anti-reorg weight comparison is semantically unchanged.

Step 7 — `sbt compile-all` — must be clean.

Step 8 — BEACON: verify `terminalTotalDifficulty` value is not altered for Sepolia config.
  FORGE: verify `ourBestTotalDifficulty` handshake field is unchanged.

Step 9 — `sbt "testOnly *ChainWeight* *BlockchainConfig* *ETHPackets*"`.

Step 10 — `git commit -m "feat(8b-H4): TotalDifficulty opaque type (BigInt) — ~26 files, FORGE+BEACON reviewed"`
```
**Prerequisite:** §8b-H3 (`Difficulty`) committed first — `TotalDifficulty.+` cross-references `Difficulty`.

---

#### §8b-H5 — `GasAmount`: BigInt → opaque type

**Files:** `domain/GasAmount.scala` (new), `BlockHeader.scala`, `Transaction.scala`, `BlockHeaderValidatorSkeleton.scala`, `EngineApiController.scala` (~39 files)
**Agent:** FORGE (ETC gas validation, ECIP-1017 emission) + BEACON (ETH EIP-1559 blob gas, `excessBlobGas`)
**Gate:** FORGE + BEACON — gas validation in block validators is consensus-critical on both chains.

**Prompt:**
```
Use the FORGE agent for ETC gas validation paths and BEACON agent for ETH EIP-4844 blob gas
fields (`blobGasUsed`, `excessBlobGas`). Introduce `opaque type GasAmount = BigInt`.

Context: `gasLimit`, `gasUsed`, `blobGasUsed`, `excessBlobGas`, `intrinsicGas` are all raw
`BigInt`. Gas validation (`gasUsed <= gasLimit`) is enforced in `BlockHeaderValidatorSkeleton`
for both chains. Define arithmetic and comparison extensions to keep validation code readable.
Reference: `.local/docs/opaque-type-domain-analysis.md` §GasAmount.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/GasAmount.scala`:
```scala
package com.chipprbots.ethereum.domain

opaque type GasAmount = BigInt
object GasAmount:
  val Zero: GasAmount = BigInt(0)
  def apply(v: BigInt): GasAmount = v
  extension (g: GasAmount)
    def value: BigInt = g
    def +(other: GasAmount): GasAmount = g.value + other.value
    def -(other: GasAmount): GasAmount = g.value - other.value
    def *(factor: BigInt): GasAmount = g.value * factor
    def /(divisor: BigInt): GasAmount = g.value / divisor
    def <=(other: GasAmount): Boolean = g.value <= other.value
    def <(other: GasAmount): Boolean = g.value < other.value
    def >=(other: GasAmount): Boolean = g.value >= other.value
    def >(other: GasAmount): Boolean = g.value > other.value
  given rlpCodec: RLPCodec[GasAmount] = summon[RLPCodec[BigInt]].xmap(GasAmount.apply, _.value)
```

Step 2 — Update `BlockHeader.scala`: `gasLimit`, `gasUsed`, `blobGasUsed`, `excessBlobGas` → `GasAmount`.
  In `BlockHeaderEnc`/`BlockHeaderDec`: wrap/unwrap.

Step 3 — Update `Transaction.scala`: `gas: BigInt` (gas limit on tx) → `GasAmount`.

Step 4 — Update `BlockHeaderValidatorSkeleton.scala`:
  Gas validation checks (`gasUsed <= gasLimit`) use `GasAmount.<=` extension — confirm readability.
  FORGE: verify ETC gas schedule comparisons are semantically unchanged.
  BEACON: verify EIP-4844 `excessBlobGas` computation (`(prevExcess + prevUsed - TARGET) / TARGET_SCALER`) uses `.value` for raw arithmetic then rewraps.

Step 5 — Update `EngineApiController.scala`: `blobGasUsed`/`excessBlobGas` fields in payload response.

Step 6 — `grep -rn "gasLimit\|gasUsed\|intrinsicGas\|blobGas" src/main/ --include="*.scala" | grep -v "//\|import"` — fix remaining sites.

Step 7 — `sbt compile-all` — must be clean.

Step 8 — `sbt "testOnly *BlockHeader* *Transaction* *Gas*"`.

Step 9 — FORGE + BEACON review diff before committing.

Step 10 — `git commit -m "feat(8b-H5): GasAmount opaque type (BigInt) — ~39 files, FORGE+BEACON reviewed"`
```

---

#### §8b-H6 — `GasPrice`: BigInt → opaque type

**Files:** `domain/GasPrice.scala` (new), `Transaction.scala`, `BlockHeader.scala` (via `HefPostOlympia`/`HefPostCancun`), `BlockchainConfig.scala`, `EngineApiController.scala` (~25 files)
**Agent:** FORGE (ETC `HefPostOlympia`, `effectiveGasPrice`, baseFeeFloor) + BEACON (ETH EIP-1559 `baseFee`, EIP-4844 `maxFeePerBlobGas`)
**Gate:** FORGE + BEACON — `effectiveGasPrice` is used at EVM execution time; wrong value alters gas accounting.

**Prompt:**
```
Use the FORGE agent for ETC fee paths (HefPostOlympia, baseFeeFloor, minTip) and BEACON agent
for ETH EIP-1559 paths (baseFee, maxFeePerGas, maxPriorityFeePerGas, maxFeePerBlobGas).
Introduce `opaque type GasPrice = BigInt`.

Context: `baseFee`, `gasPrice`, `maxFeePerGas`, `maxPriorityFeePerGas`, `maxFeePerBlobGas`,
`effectiveGasPrice`, `baseFeeFloor`, `minTip` are all raw `BigInt`. EIP-1559 fee arithmetic
is the most complex: `effectiveGasPrice = baseFee + min(maxFeePerGas - baseFee, maxPriorityFeePerGas)`.
Define arithmetic extensions to keep this readable without pervasive `.value` noise.
Reference: `.local/docs/opaque-type-domain-analysis.md` §GasPrice.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/GasPrice.scala`:
```scala
package com.chipprbots.ethereum.domain

opaque type GasPrice = BigInt
object GasPrice:
  val Zero: GasPrice = BigInt(0)
  def apply(v: BigInt): GasPrice = v
  extension (p: GasPrice)
    def value: BigInt = p
    def +(other: GasPrice): GasPrice = p.value + other.value
    def -(other: GasPrice): GasPrice = p.value - other.value
    def *(factor: BigInt): GasPrice = p.value * factor
    def min(other: GasPrice): GasPrice = p.value.min(other.value)
    def max(other: GasPrice): GasPrice = p.value.max(other.value)
    def <=(other: GasPrice): Boolean = p.value <= other.value
    def <(other: GasPrice): Boolean = p.value < other.value
    def >=(other: GasPrice): Boolean = p.value >= other.value
    def >(other: GasPrice): Boolean = p.value > other.value
  given rlpCodec: RLPCodec[GasPrice] = summon[RLPCodec[BigInt]].xmap(GasPrice.apply, _.value)
```

Step 2 — Update transaction types in `Transaction.scala`:
  `gasPrice: BigInt` → `GasPrice` (LegacyTransaction)
  `maxFeePerGas`, `maxPriorityFeePerGas`, `maxFeePerBlobGas` → `GasPrice` (typed txs)
  In `ETHPackets.scala` typed tx codecs: wrap/unwrap at boundaries.

Step 3 — Update `HefPostOlympia` / `HefPostCancun` constructors:
  `baseFeeFloor: BigInt`, `minTip: BigInt` → `GasPrice`.
  FORGE: verify `effectiveGasPrice` calculation is unchanged.

Step 4 — Update `BlockHeader.scala` (if `baseFeePerGas` is stored directly):
  `baseFeePerGas: BigInt` → `GasPrice`. In `BlockHeaderEnc`/`BlockHeaderDec`: wrap/unwrap.

Step 5 — Update `EngineApiController.scala`: `baseFeePerGas` in execution payload response.

Step 6 — BEACON: verify EIP-1559 base fee update rule:
  `newBaseFee = parentBaseFee + parentBaseFee * (parentGasUsed - TARGET) / TARGET / DENOMINATOR`
  This mixes `GasPrice` and `GasAmount` in arithmetic — at the division boundary use `.value` then rewrap.

Step 7 — `sbt compile-all` — must be clean.

Step 8 — `sbt "testOnly *Transaction* *BlockHeader* *Fee* *EIP1559*"`.

Step 9 — FORGE + BEACON review diff before committing.

Step 10 — `git commit -m "feat(8b-H6): GasPrice opaque type (BigInt) — ~25 files, FORGE+BEACON reviewed"`
```

---

#### §8b-H7 — `BlockNumber`: BigInt → opaque type (largest migration)

**Files:** `domain/BlockNumber.scala` (new), `BlockHeader.scala`, `ForkBlockNumbers.scala` (26 fields, atomic), `SyncProtocol.scala`, `PivotBlockSelector.scala`, `RegularSyncMetrics.scala`, all fork dispatch sites (~124 files)
**Agent:** FORGE (ETC fork dispatch, `forBlock()`, ForkBlockNumbers) + BEACON (ETH `forTimestamp()` callers that also reference `blockNumber`)
**Gate:** FORGE + BEACON — fork dispatch `forBlock(blockNumber)` comparisons are the core of ETC hard fork activation. ForkBlockNumbers must migrate atomically (26 fields).
**Prerequisite:** §8b-H3/H4 committed (BigInt opaque pattern proven for arithmetic-heavy types).

**Prompt:**
```
Use the FORGE agent for ETC fork dispatch (forBlock, ForkBlockNumbers, all ECIP activation blocks)
and BEACON agent for ETH blockNumber references in timestamp-fork code. Introduce
`opaque type BlockNumber = BigInt`. This is the largest single migration (~124 files).

Context: `BlockHeader.number`, all 26 fields of `ForkBlockNumbers`, `blockNumber` parameters in
sync actors and fork dispatch are raw `BigInt`. `ForkBlockNumbers` must migrate ALL 26 fields in
one commit — partial migration breaks fork dispatch comparisons. Arithmetic extensions on
`BlockNumber` eliminate most `.value` noise at comparison sites.
Reference: `.local/docs/opaque-type-domain-analysis.md` §BlockNumber.

Pre-flight:
  `grep -rn "ForkBlockNumbers\|forBlock\|\.number\b\|blockNumber" src/main/ --include="*.scala" | wc -l`
  Result should be ~500+. Accept this — migrations at this scale are mechanical.
  `sbt compile-all` — must be clean before starting.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/BlockNumber.scala`:
```scala
package com.chipprbots.ethereum.domain

opaque type BlockNumber = BigInt
object BlockNumber:
  val Zero: BlockNumber = BigInt(0)
  def apply(v: BigInt): BlockNumber = v
  def apply(v: Long): BlockNumber = BigInt(v)
  extension (bn: BlockNumber)
    def value: BigInt = bn
    def +(n: BigInt): BlockNumber = bn.value + n
    def -(n: BigInt): BlockNumber = (bn.value - n).max(BigInt(0))
    def >=(other: BlockNumber): Boolean = bn.value >= other.value
    def >(other: BlockNumber): Boolean = bn.value > other.value
    def <(other: BlockNumber): Boolean = bn.value < other.value
    def <=(other: BlockNumber): Boolean = bn.value <= other.value
    def toLong: Long = bn.value.toLong
  given rlpCodec: RLPCodec[BlockNumber] = summon[RLPCodec[BigInt]].xmap(BlockNumber.apply, _.value)
```

Step 2 — Update `ForkBlockNumbers.scala` (ATOMIC — all 26 fields in one edit):
  Every `BigInt` field → `BlockNumber`. This is the highest-risk step.
  FORGE: verify every ECIP activation block field name is correct and unchanged.

Step 3 — Update `BlockHeader.scala`: `number: BigInt` → `BlockNumber`.
  In `BlockHeaderEnc`/`BlockHeaderDec`: wrap/unwrap.
  `forBlock(blockNumber: BigInt)` in fork config → `forBlock(blockNumber: BlockNumber)`.

Step 4 — Update fork dispatch call sites:
  `grep -rn "forBlock\|\.number\b" src/main/ --include="*.scala"`
  All `header.number` accesses now return `BlockNumber`. Fork dispatch comparisons
  (`blockNumber >= forkBlockNumbers.atlantis`) use `BlockNumber.>=` — no change needed if
  both sides are `BlockNumber`. Sites that mix `BlockNumber` with raw `BigInt` in comparisons
  need one side wrapped.

Step 5 — Update sync actors:
  `grep -rn "blockNumber\|bestBlockNumber\|pivotBlockNumber\|highestCommonBlock" src/main/ --include="*.scala" | grep -v "//\|import"`
  These carry block numbers as sync progress markers. Update field types in
  `SyncProtocol.scala`, `PivotBlockSelector.scala`, `RegularSyncMetrics.scala`.

Step 6 — Update P2P protocol messages:
  `blockNumber: BigInt` in `ETHPackets.scala` `GetBlockHeaders` → `BlockNumber`.
  Wrap at decode, unwrap with `.value` at encode.

Step 7 — `sbt compile-all` — must be clean. Fix any type mismatch errors.
  Most errors will be: raw `BigInt` literal where `BlockNumber` expected — wrap with `BlockNumber(n)`.
  Arithmetic on `BlockNumber` that returns `BigInt` — use extension ops or rewrap.

Step 8 — FORGE: run block import on a known ETC mainnet range spanning multiple hard forks.
  Verify fork activation blocks fire at the correct `BlockNumber` values.

Step 9 — `sbt testEssential` — full suite.

Step 10 — FORGE + BEACON review diff before committing.

Step 11 — `git commit -m "feat(8b-H7): BlockNumber opaque type (BigInt) — ~124 files, ForkBlockNumbers atomic, FORGE+BEACON reviewed"`
```

---

#### §8b-H8 — `ChainId`: BigInt → opaque type (sign last — most dangerous)

**Files:** `domain/ChainId.scala` (new), `Transaction.scala` (all typed tx variants), `BlockchainConfig.scala`, `SignedTransaction.scala`, `ECDSASignature.scala` (~27 files)
**Agent:** FORGE (ETC EIP-155 signing, `v = chainId * 2 + 35`) + BEACON (ETH EIP-155, EIP-2718 typed tx signing)
**Gate:** FORGE + BEACON — wrong `ChainId` wrapping silently produces invalid transaction signatures. Do this last, after all other opaque types are proven stable.
**Prerequisite:** All §8b-H1 through §8b-H7 committed and CI green.

**Prompt:**
```
Use the FORGE agent for ETC EIP-155 signing paths and BEACON agent for ETH typed transaction
signing paths. Introduce `opaque type ChainId = BigInt`. This is the most security-sensitive
migration — wrong wrapping breaks transaction signing silently.

Context: `chainId: BigInt` appears in all typed tx types (`TransactionWithAccessList`,
`TransactionWithDynamicFee`, `BlobTransaction`, `SetCodeTransaction`, `SetCodeAuthorization`)
and in `BlockchainConfig.chainId`. The critical arithmetic is EIP-155 `v = chainId * 2 + 35`
in `ECDSASignature`. This MUST use `.value` for the multiplication — do not define `*(factor: BigInt)`
on `ChainId` to avoid accidental use of the wrong type in the v-calculation.
Reference: `.local/docs/opaque-type-domain-analysis.md` §ChainId.

Pre-flight:
  `grep -rn "chainId" src/main/ --include="*.scala" | grep -v "//\|import" | wc -l`
  `sbt compile-all` — must be clean before starting.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/ChainId.scala`:
```scala
package com.chipprbots.ethereum.domain

opaque type ChainId = BigInt
object ChainId:
  def apply(v: BigInt): ChainId = v
  def apply(v: Long): ChainId = BigInt(v)
  extension (c: ChainId)
    def value: BigInt = c
    def ==(other: ChainId): Boolean = c.value == other.value
  // Intentionally NO arithmetic extensions — all v-calculation arithmetic must use .value explicitly
  given rlpCodec: RLPCodec[ChainId] = summon[RLPCodec[BigInt]].xmap(ChainId.apply, _.value)
```

Step 2 — Update `BlockchainConfig.scala`: `chainId: BigInt` → `ChainId`.

Step 3 — Update all typed tx types in `Transaction.scala`:
  `chainId: BigInt` → `ChainId` on `TransactionWithAccessList`, `TransactionWithDynamicFee`,
  `BlobTransaction`, `SetCodeTransaction`, `SetCodeAuthorization`.
  In `ETHPackets.scala` typed tx codecs: wrap/unwrap at boundaries.

Step 4 — Update `ECDSASignature.scala` (CRITICAL):
  The EIP-155 `v` calculation: `chainId * 2 + 35` → `chainId.value * 2 + 35`.
  FORGE: verify this expression is byte-identical to the current output. Run sign+verify test.
  BEACON: verify ETH EIP-155 recovery also uses `.value`.

Step 5 — Update `SignedTransaction.scala`:
  `extractChainId(v: BigInt): Option[BigInt]` signature update if it returns a chain id.
  The extracted chain ID: `(v - 35) / 2` → wrap result: `ChainId((v - 35) / 2)`.

Step 6 — `sbt compile-all` — must be clean.

Step 7 — `sbt "testOnly *SignedTransaction* *ECDSASignature* *Transaction*"`.
  CRITICAL: run a sign-then-verify round-trip test for both ETC (chainId=61) and ETH (chainId=1)
  to confirm the `v` calculation is numerically identical before and after the migration.

Step 8 — FORGE + BEACON review diff with special attention to every arithmetic site involving `.value`.

Step 9 — `git commit -m "feat(8b-H8): ChainId opaque type (BigInt) — ~27 files, EIP-155 v-calc .value verified, FORGE+BEACON reviewed"`
```

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

#### §8k-J — PRISM: Re-run TCP floor verification after CAPSTONE — ✅ DONE 2026-06-25

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

**TCP floor confirmed 2026-06-25:** §8k-B sprint complete — §8k-B11 (`68035cb85`) removed the last non-TCP bridge. 5 grep lines remain (all permanent TCP floor). Target achieved.

---

#### §8k-B — Bridge elimination to TCP floor ✅ COMPLETE 2026-06-25 — sprint prompts archived

**Agent:** PRISM / LOOM
**Status:** ✅ COMPLETE 2026-06-25 — TCP floor achieved. All B1–B11 done. Final commit: `68035cb85`.

**Final state (post-§8k-B11):**
- Real code bridges: **5 grep lines** = 7 actual `.toClassic` calls (all TCP floor — permanent)
  - `ServerActor.scala:70,77` — 2 calls (TCP bind)
  - `RLPxConnectionHandler.scala:323` — 1 call (TCP write ack)
  - `PeerManagerActor.scala:591` — 2 calls on one line (`peer.ref.toClassic` + `peerEventAdapter.toClassic`)
  - `PeerManagerActor.scala:629` — 2 calls on one line (`peer.ref.toClassic` + `peerEventAdapter.toClassic`)
- **Eliminatable: 0** — target reached

**Bridge reduction path:**

| Sprint | Bridges freed | After (grep lines) |
|--------|--------------|---------------------|
| ~~§8k-Q~~ ✅ | 1 — FastSync:180 `fastSyncClassicSelf` | **19** |
| ~~§8k-B1~~ ✅ | 2 — BytecodeRecovery:199, StorageRecovery:229 | **17** |
| ~~§8k-B2~~ ✅ | 2 — SyncController:1668, :2079 (NPMA type fix) | **15** |
| ~~§8k-B3~~ ✅ | 1 — SSC:555 (chainDownloaderReplyAdapter) | **14** |
| ~~§8k-B4~~ ✅ | 2 — BlockImporter:207, :214 | **12** |
| ~~§8k-B5~~ ✅ | 2 — PivotBlockSelector:418, :579 | **10** |
| ~~§8k-B6~~ ✅ | 2 — PeerEventBusActor:42, PeerRequestHandler:78 (NB:419+1009 moved to §8k-B8) | **8** |
| ~~§8k-B7~~ ✅ | 1 — AkkaTaskOps:37 | **5** |
| ~~§8k-B8~~ ✅ | NB:419+NB:1009 removed + 7 silent-drop GOAL-A fixes — structural pass-through eliminated; remaining bridges are explicit per-actor (see B8 outcome note) | **explicit** |
| ~~§8k-B10~~ ✅ | 11+4 actors (scope expanded: +BlockBroadcast/BlockBroadcasterActor/BlockImporter/ByteCodeWorker); NPMA `PeerInfoRequestCmd.replyTo` → TypedActorRef; SSC:3376 (ARC) held — `e30facc0e`+`4523b9256` | **6** |
| ~~§8k-B11~~ ✅ | ARC `networkPeerManager` Classic→TypedActorRef in ARC+ARWorker; remove SSC:3376 — `68035cb85` | **5** ✅ |

**Sprint prompts §8k-B1 through §8k-B11 archived to `completed/DEFERRED-BACKLOG.md §8k-B`.**


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
