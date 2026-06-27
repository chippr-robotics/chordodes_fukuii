# Fukuii Modernization — Deferred Backlog

**Last updated**: 2026-06-26
**Purpose**: Active deferred items only. Completed section detail lives in `completed/DEFERRED-BACKLOG.md`.

Active sprint plan: `/home/dev/.claude/plans/we-are-working-on-noble-whisper.md`

---

## Completed Sections

| Section | Description | Commit(s) | Date | Detail |
|---------|-------------|-----------|------|--------|
| Part 2 — subsystems 1–4 | Pekko migration: faucet, jsonrpc, transactions, OmmersPool+MockedMiner | `551bccfaf` `2ac71a58e` `1309bb968` `0be6dd776` `0aa837d5e` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| Part 2 — NET group | NPMA classicSystem.scheduler noted; actorSelection test item resolved | `4b101b612` `6b506a63f` | 2026-06-21 | completed/DEFERRED-BACKLOG.md |
| §3b | implicit class → extension methods | `c0a3612b4` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §3c | isInstanceOf/asInstanceOf audit (1 fixed, 82 intentional) | `7cc9eda3a` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §3d — SyncPhase/ForkId | Sealed trait → enum (SyncPhase + ForkIdValidationResult) | `adf4e69ea` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §3e | Console output → logging (12 fixed, 8 CLI preserved) | `c3fec6390` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §3f | Manual synchronization audit (20 sites; 1 FORGE-gated in CHASE-QUEUE) | `cf33cfa87` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §3g | StateValidator.scala exception swallowing | — | 2026-06-20 | completed/DEFERRED-BACKLOG.md |
| §3h | `Any` in type signatures (15 sites; 7 FORGE-gated deferred) | `cc63882fa` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §3i | BlockExecutionError hierarchy redesign (union type + `describe`) | `64ab4786e` `d4344962f` | 2026-06-23 | completed/DEFERRED-BACKLOG.md |
| §6a | extvm/ dead code deletion | `a948fda1d` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §7a | ADT consolidation | `04615ad43` `4e8b42263` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §7b | EventStream pub/sub topology map + migration | `849c0dcf0` `b35b35cf6` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §7d | Post-CAPSTONE classic artifact audit (8-lens sweep) | docs only | 2026-06-21 | completed/DEFERRED-BACKLOG.md |
| §7e-P4/P4a | Design review: heal path completeness | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §8a batches 1–5 + §8a-infra-b/c | Classic TestKit → ActorTestKit migration (coordinator/heal specs, E165 floor) | `0d65a85c4` `b5e11c0a4` `722f316f2` `12c23cf8a` `a719520db` `5eae34c21` `5ff14017b` `8b9bef67d` `781c8e985` `a193bc794` `5f28e8ae6` | 2026-06-21–23 | completed/DEFERRED-BACKLOG.md |
| §8c H2/H3/H4/M1/M3/M4 | Memory/resource leak audit (H-series + most M-series) | `4907406fe` `ef75a5608` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §8d | IO threading model follow-up (BEACON EngineApiService; CONDUIT jsonrpc) | `0a8ed3038` `276c77735` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §8d-J | CONDUIT jsonrpc IO boundary scan (zero findings — no code changes) | docs only | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §8e C2/TNHC/FORGE/StackTrie/BEACON | ScalaFix noReturns ratchet (partial) | `9eb1f4e06` `7a48c5988` `4544b8025` `09307c5a7` `d78177bda` | 2026-06-22–24 | completed/DEFERRED-BACKLOG.md |
| §8f | Dead code audit: MetricsAlreadyConfiguredError, LocalVM, AdaptiveSyncStrategy, DeltaSpikeGauge, StaticNodesLoader | `fa57df9b9` `c6b3da4cb` `ff2fc219c` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §8k | Classic bridge elimination (Clusters A–N, B sprint, J PRISM audit) — TCP floor confirmed | `791c0211f`…`68035cb85` `35db7dc61` | 2026-06-23–25 | completed/DEFERRED-BACKLOG.md |
| §8l | VM tracer model (R1 research + implementation) | `37c9d081b` `5c2adeaaf` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9a | SyncStartupStrategy extraction | `3140db465` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9b | RegularSync divergence-path spec fix + LCA recovery test | `0d290019e` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9c | RegularSyncSpec full migration | `57d638d49` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9d | RegularSyncFixtures getSyncStatus Classic ask → Typed send | `69146a244` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| Part 10 — P7 | Test suite timing audit + slow-test reduction (3,595/0 baseline, 680s) | `edfb69f35` | 2026-06-22–23 | completed/DEFERRED-BACKLOG.md |
| Part 11 — P8–P12 | Full test coverage audit: SyncTest rescue, DisabledTest, FlakyTest, SlowTest, tag taxonomy | multiple | 2026-06-22–24 | completed/DEFERRED-BACKLOG.md |
| §R3 | Jackson ecosystem gate research | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R5 | EventStream pub/sub topology map | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R6 | Opaque type domain analysis (`.local/docs/opaque-type-domain-analysis.md`) | docs only | 2026-06-25 | completed/DEFERRED-BACKLOG.md |
| §R8 | Memory/resource retention audit | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R9 | IO threading model audit | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R10 | ETH/Sepolia assumption audit (10 threads, all complete) | multiple | 2026-06-24–25 | completed/DEFERRED-BACKLOG.md |
| §R11 / Part 16 | ETC-only artifact sweep + ETH sprint items (T1–T10, all threads) | `e0cebcd72` `6b2b41e49` `d1a7073bf` `6be73300f` `2277555c7` `e701281a0` `9591ff9e1` + ETH sprint commits | 2026-06-24–26 | completed/DEFERRED-BACKLOG.md |

---

## Part 1: Deferred — Remaining Compiler Warnings

**Status: W2-P1 sweep COMPLETE. ~507 non-E165 → 87 non-E165 remaining (all externally gated).**

**Earlier commits:** see `completed/DEFERRED-BACKLOG.md` W2-P1 History section.

### Remaining 87 Non-E165 Warnings

| Count | Cause | Gate |
|-------|-------|------|
| 68 | `json4s extract[T]` Manifest synthesis (16 files) | json4s 4.x upgrade (§4e) |
| 9 | `OpCode.scala` — infix ops + wildcard in `vm/` | FORGE gate |
| 2 | RocksDB `ClockCache` deprecated | Library upgrade |
| 2 | diffx `DiffMatcher` | Library upgrade |
| 1 | Guava `CacheBuilder` | Library upgrade |
| 1 | `EngineApiService.scala:661` `Ordering.Iterable` (tx sort key) | BEACON gate — not a correctness concern (T10 verdict 2026-06-24) |
| 1 | web3j `Admin` deprecated | Library upgrade |
| 1 | `TrieNodeHealingCoordinator` inside Pekko library boundary | Unfixable |
| 1 | `PeerRequestHandler` `ClassTag` unsound type-test | Needs `TypeTest[A,B]` (non-trivial) |

**E165 floor: 333** (intentional Pekko Classic bridges — permanent).

---

## Part 2: Pekko Classic → Typed Migration (Network/P2P Sprint)

**Status**: Subsystems 1–4 DONE (see completed). NET group DONE (see completed). Remaining: network/P2P sprint.
**Spec**: `pekko-typed-migration-p2.md` | **Agent**: LOOM

### Remaining Classic→Typed Bridges (require network/P2P sprint to remove)

| Bridge | Location | Why it exists | Removed when |
|--------|----------|---------------|--------------|
| `system.spawn(...)` via `adapter._` | `NodeBuilder.OmmersPoolBuilder`, `MockedMiner.spawn` | Classic root can't spawn Typed children natively | Root flipped to `ActorSystem[Nothing]` |
| `system.toTyped.scheduler` | `PoWBlockCreator`, `PoWMining` | Typed ask needs a Typed Scheduler | Same — root flip |
| `MockedMiner.Send(msg, replyTo)` envelope | `PoWMining.sendMiner/askMiner`, callers | External `MockedMinerProtocol` API kept stable | When `Mining` API is absorbed into `Command` ADT |
| `ctx.messageAdapter` / `toClassic.eventStream` | PTM (`transactions/`, P2c) | `eventStream` is Classic | Root flip + eventStream modernization |

### Network/P2P Sprint — Migration Completion Gate

`network/` and `sync/` actors (~20 files, ~25k LOC) must be migrated in their own sprint (R1 research → implementation):
- Deeply coupled to the wire protocol (HERALD review required per subsystem)
- SNAPSyncController alone is 5,052 LOC
- Migration atomicity requires grouping by caller coherence

**After all network/sync actors migrated:**
1. Flip `NodeBuilder.system` → `ActorSystem[Nothing]`
2. Remove `system.toTyped.scheduler` shims in `PoWBlockCreator`, `PoWMining`
3. Absorb `MockedMinerProtocol` into `MockedMiner.Command` ADT
4. Replace `toClassic.eventStream` bridges with native Typed `EventStream`

**Sequence:** Run R1 from `WAVE3-RESEARCH-PLAN.md` → implement per R1 output → see SPRINT-QUEUE.md Part 6.

### Pre-flight Before Each Subsystem

```bash
grep -rn "import .*\._" src/main/scala/ --include="*.scala" | wc -l  # must be 0
grep -rn "eventStream\.publish\|eventStream\.tell\|eventStream\.subscribe" src/main/scala/ --include="*.scala"
```

### Rejection Criteria

- `grep -rn "import .*\._" src/main/ | wc -l` > 0 at migration start
- PTM eventStream types cross network boundary → full `@SerializabilityTrait` pre-flight required
- Any file under `consensus/`, `vm/`, `crypto/`, `domain/` touched → invoke FORGE or BEACON before proceeding
- `sbt testEssential` drops below 3,601 tests

---

## Part 3: Scala 3 Modernization

**Sections §3b–§3i all COMPLETE** — see completed table above.

### 3a — implicit → given/using

**Spec**: `scala-implicit-to-given.md` | **Agent**: MITHRIL (after `sbt scalafix GivenUsing`)
**Blast radius**: 198 files, 522 `implicit val/def` declarations

Hotspot files (highest density — start here):
1. `jsonrpc/McpJsonMethodsImplicits.scala` — 32 implicits
2. `utils/Picklers.scala` — 21
3. `jsonrpc/JsonMethodsImplicits.scala` — 21
4. `jsonrpc/EthBlocksJsonMethodsImplicits.scala` — 19

**Prerequisite**: Add `GivenUsing` to `.scalafix.conf` BEFORE running. Must run AFTER Pekko migration sprint.

### 3d — sealed trait → enum (residual note)

`SyncProtocol.Status.Syncing` ❌ REJECTED (2026-06-25) — `case class` with three constructor params; cannot be an enum. `Blacklist.BlacklistReason` and `BlacklistReasonType` also REJECTED (non-pure discriminants). No further candidates identified.

---

## Part 4: Dependency Upgrades (blocked or deferred)

### 4a — JLine 3.x → 4.x

**Current pin**: `3.30.13` | **Target**: `4.1.x`
**Why deferred**: JLine 4.x is not drop-in compatible. Two files need refactoring:
- `console/TuiRenderer.scala` — `AttributedString`, `AttributedStyle`
- `console/Tui.scala` — `Terminal`, `TerminalBuilder`

**Prerequisite**: Dedicated jline-upgrade sprint; assess TUI rendering test impact.

### 4b — Logstash-Logback-Encoder 8.x → 9.x

**Current pin**: `8.1` | **Target**: `9.0`
**Why deferred**: 9.0 requires Jackson 3 exclusively (jackson-bom:3.0.1). Also bumps minimum Java to 17.
**Prerequisite**: 4e (Jackson 2→3) first.

### 4c — Kanela-Agent 1.x → 2.x

**Why deferred**: Complete rewrite (AspectJ → ByteBuddy). Requires Kamon 2.8.1+ and custom instrumentation recompile.
**Prerequisite**: Kamon 2.8.1+ confirmed stable; dedicated sprint with changelog review.

### 4e — Jackson 2.x → 3.x (transitive)

**Status**: BLOCKED — json4s is the sole blocker.

| Library | Gate | Finding |
|---------|------|---------|
| json4s | **GATE NEARLY OPEN** | `4.2.0-M5-SNAPSHOT` already imports `tools.jackson.databind.*` (Jackson 3). Latest stable is still `4.1.1` (Jackson 2). Gate opens on M5 release. |
| circe | **NON-BLOCKER** | No Jackson dependency in circe-core. |
| sangria / sangria-circe | **NON-BLOCKER** | Zero Jackson references. |

**Gate condition**: `json4s 4.2.0-M5` (or later) published. Verify at release tag: must reference `tools.jackson.core` (Jackson 3), not `com.fasterxml.jackson.core` (Jackson 2).

**Watch**: https://github.com/json4s/json4s/tags — last tagged release `4.2.0-M4` (Jun 11 2026).

**Alternative unblock**: Migrate `jsonrpc/` from `json4s-native` to circe (~79 files, `JValue → io.circe.Json`). Eliminates the Jackson gate permanently.

---

## Part 5: Blocked (gate conditions)

### 5a — Scala 3.9 Upgrade

**Gate**: Scala 3.9.x LTS appears on endoflife.date with LTS designation
**Action when unblocked**: Bump `scalaVersion` in `build.sbt`; update scapegoat; run `sbt compile-all`.

### 5b — Virtual Threads / Ox Evaluation

**Status**: Research only — low priority
**Reference**: `.claude/virtuslab/scala-skill/direct-style-scala/SKILL.md`

### 5c — Constitution v1.2.0

**Gate**: 5a (Scala 3.9 upgrade).

---

## Part 7: Post-CAPSTONE Typed API Maturity

**§7a, §7b, §7d, §7e-P4/P4a all DONE** — see completed table above.

### 7c — Typed Supervision Hierarchy

**Gate**: CAPSTONE ✅ + 7a ✅ — both met 2026-06-25.
**PRISM audit complete 2026-06-25.** Design: `.local/docs/supervision-design-7c.md`

Summary:
- 49 actors audited. Zero existing `Behaviors.supervise` wrappers.
- **6 STOP-AND-ALERT**: PeerEventBusActor, PeerManagerActor, NetworkPeerManagerActor, SNAPSyncController, SyncController, SubscriptionManager
- **39 SAFE-TO-RESTART** across Thread Groups A/B/C
- **4 NEEDS-ANALYSIS** (risk flags RF-1 BlockImporter, RF-2 BlockFetcher, RF-3 SyncStateSchedulerActor)
- **Implementation order:** Phase 1=Group D (alert wrappers) → A → B → C → E (risk-flagged)
- **Protocol needed:** `alert-wrapper-protocol.md` before Group D LOOM threads begin

#### §7c-P0 — Write alert-wrapper-protocol.md (prerequisite)

**Files:** `.claude/agent-protocols/alert-wrapper-protocol.md` (new) | **Agent:** Main session | **Gate:** None — do first.

**Prompt:**
```
Write `.claude/agent-protocols/alert-wrapper-protocol.md` to standardise the
STOP-AND-ALERT supervision pattern used in §7c Group D.

The pattern applies to actors where restart causes state corruption. Rather than
adding restart supervision, the parent (typically NodeBuilder) is amended to
watchWith the critical child and emit a structured alarm on failure.

## Pattern

At spawn site in the parent behavior:

```scala
val criticalRef = ctx.spawn(CriticalActor(...), "critical-actor")
ctx.watchWith(criticalRef, CriticalActorFailed("critical-actor"))
```

Add `CriticalActorFailed(name: String)` to the parent's Command ADT. Handle in receive:

```scala
case CriticalActorFailed(name) =>
  log.error("CRITICAL actor stopped unexpectedly — node restart required: {}", name)
  Behaviors.stopped
```

## When to use
Apply to the 6 STOP-AND-ALERT actors (§7c audit):
PeerEventBusActor, PeerManagerActor, NetworkPeerManagerActor,
SNAPSyncController, SyncController, SubscriptionManager.

## When NOT to use
Do not use for SAFE-TO-RESTART actors — those get Behaviors.supervise restartWithBackoff.

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

Commit: `docs(7c-P0): add alert-wrapper-protocol.md — STOP-AND-ALERT supervision pattern`
```

---

#### §7c-D — Group D: STOP-AND-ALERT monitoring wrappers (6 actors)

**Files:** Spawn sites for PeerEventBusActor, PeerManagerActor, NetworkPeerManagerActor, SNAPSyncController, SyncController, SubscriptionManager — in `NodeBuilder.scala` / `StdNode.scala` / JSON-RPC server setup.
**Agent:** LOOM | **Gate:** §7c-P0 done.

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
  after the `ctx.spawn(...)` call. Add `CriticalActorFailed` to parent's Command ADT.
  Add handler: log.error + Behaviors.stopped.

  If multiple STOP-AND-ALERT actors share the same parent, reuse a single
  `CriticalActorFailed(name: String)` case class.

Step 3 — sbt compile-all — must be clean.
Step 4 — sbt "testOnly *NodeBuilder* *StdNode* *Subscription*" if test coverage exists.
Step 5 — git commit -m "feat(7c-D): STOP-AND-ALERT watchWith alarm on 6 critical actors"
```

---

#### §7c-A — Group A: Infrastructure + application restart wrappers

**Files:** Spawn sites in NodeBuilder/StdNode for: ServerActor, KnownNodesManager, PeerStatisticsActor, PeerDiscoveryManager, OmmersPool, PendingTransactionsManager, FilterManager, FaucetHandler, PeriodicConsistencyCheck. PeerActor spawn in PeerManagerActor.peerFactory.
**Agent:** MITHRIL | **Gate:** §7c-D done.

**Prompt:**
```
Read `.local/docs/supervision-design-7c.md` Part 2 Group A.

Task: wrap SAFE-TO-RESTART actors at their spawn sites with Behaviors.supervise.
Import: `import org.apache.pekko.actor.typed.SupervisorStrategy`
        `import org.apache.pekko.actor.typed.scaladsl.Behaviors`
        `import scala.concurrent.duration._`

Apply these strategies (grep for actor name to locate spawn call first):

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
  PeerActor (spawn in PeerManagerActor.peerFactory):
    Behaviors.supervise(PeerActor(...)).onFailure[Throwable](
      SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3))
  RLPxConnectionHandler — NO CHANGE (keep default stop; leave comment explaining why).

Step N-1 — sbt compile-all — must be clean.
Step N   — git commit -m "feat(7c-A): restart supervision for 10 safe-to-restart infrastructure actors"
```

---

#### §7c-B — Group B: SNAP workers, coordinators, sync support

**Files:** Worker spawn sites in coordinator files; coordinator spawn in SNAPSyncController; sync support spawn in SyncController/NodeBuilder.
**Agent:** MITHRIL | **Gate:** §7c-A done.

**Prompt:**
```
Read `.local/docs/supervision-design-7c.md` Part 2 Groups B1/B2/B3.

B1 — SNAP Workers (spawn sites inside each coordinator's worker-spawn call):
  AccountRangeWorker, ByteCodeWorker, StorageRangeWorker, TrieNodeHealingWorker:
    Behaviors.supervise(XxxWorker(...)).onFailure[Throwable](
      SupervisorStrategy.restart.withMaxRestarts(5))

B2 — SNAP Coordinators (spawn sites in SNAPSyncController):
  AccountRangeCoordinator, ByteCodeCoordinator, StorageRangeCoordinator, TrieNodeHealingCoordinator:
    Behaviors.supervise(XxxCoordinator(...)).onFailure[Throwable](
      SupervisorStrategy.restartWithBackoff(1.second, 10.seconds, 0.2).withMaxRestarts(3))

B3 — Sync support actors (locate spawn sites via grep):
  PeersClient:              restartWithBackoff(500.millis, 10.seconds, 0.2).withMaxRestarts(5)
  ChainDownloader:          restart
  PivotBlockSelector:       restart
  PivotHeaderBootstrap:     restart
  BlockchainHostActor:      restart
  BytecodeRecoveryActor:    restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(2)
  StorageRecoveryActor:     restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(2)
  CombinedRecoveryScanActor: restart
  StateStorageActor:        restart
  FastSyncBranchResolverActor: restart
  BlockBroadcasterActor:    restart
  PeerRequestHandler — NO CHANGE (self-limiting leaf, effectively irrelevant).

Step N-1 — sbt compile-all — must be clean.
Step N-2 — sbt "testOnly *SNAP* *Coordinator* *Worker*"
Step N   — git commit -m "feat(7c-B): restart supervision for SNAP workers, coordinators, sync support"
```

---

#### §7c-E1 — RF-1: BlockImporter write idempotency (forge + beacon consultation)

**Files:** `blockchain/sync/regular/BlockImporter.scala`, `blockchain/BlockchainWriter.scala`
**Agent:** FORGE (ETC) + BEACON (ETH) | **Gate:** None — run in parallel with §7c-A/B.

**Prompt:**
```
Use the FORGE agent first, then the BEACON agent.

`BlockchainWriter` is shared infrastructure used by both ETC and ETH block acceptance paths.
Both write paths must be idempotent for `BlockImporter` restartWithBackoff to be safe.

Question (FORGE — ETC path): Is `BlockchainWriter.save(block, ...)` idempotent on the ETC
block-acceptance path (ECIP-1017 rewards, Ethash seal, ETC-specific state writes)?
If the same block is submitted twice (same hash, same number): does it silently succeed
(no-op / overwrite with identical data) or throw / corrupt state?

Question (BEACON — ETH path): Is the same `BlockchainWriter.save(block, ...)` idempotent on
the ETH path (validator withdrawals, beacon root syscall, PoS-specific state writes)?
Could a duplicate write corrupt the withdrawal accumulator or beacon state?

Context: `BlockImporter` has a companion-object `var survivedExhausts` intentionally preserved
across Pekko restarts (comment confirms restart is expected). We want to know if adding
`Behaviors.supervise(BlockImporter(...)).onFailure[Throwable](
SupervisorStrategy.restartWithBackoff(1.second, 30.seconds, 0.2).withMaxRestarts(3))`
is safe on BOTH chains.

Produce: one verdict per chain. If BOTH chains idempotent → include BlockImporter in §7c-C restart group.
If EITHER chain NOT idempotent → BlockImporter stays at default stop, note which chain and why.
```

---

#### §7c-C — Group C: RegularSync fetch children (RF-2 decision + implementation)

**Files:** `blockchain/sync/regular/BlockFetcher.scala`, `BlockImporter.scala`, `HeadersFetcher.scala`, `BodiesFetcher.scala`, `StateNodeFetcher.scala`, `RegularSync.scala`
**Agent:** MITHRIL | **Gate:** RF-2 decision (below) + §7c-E1 result for BlockImporter.

**RF-2 decision (choose before prompting MITHRIL):**
- Option A (recommended): `BlockFetcher` → stop (default). `RegularSync` death-watches `BlockFetcher` and re-spawns it. `HeadersFetcher`/`BodiesFetcher`/`StateNodeFetcher` are children of BlockFetcher — stop automatically when BlockFetcher stops.
- Option B: Add `PreRestart` signal handler to `BlockFetcher` to stop all children before restart. More complex.

**Prompt (Option A):**
```
Read `.local/docs/supervision-design-7c.md` Part 3 RF-2.

Decision: Option A — BlockFetcher keeps default stop; RegularSync handles re-spawn.

Step 1 — Confirm RegularSync currently death-watches BlockFetcher:
  grep -n "watch\|watchWith\|BlockFetcher" src/main/scala/.../RegularSync.scala
  If death-watch exists and re-spawn logic is present, proceed.
  If not, add ctx.watchWith(blockFetcherRef, BlockFetcherStopped) + re-spawn handler.

Step 2 — Confirm HeadersFetcher/BodiesFetcher/StateNodeFetcher are children of BlockFetcher
  (spawned in BlockFetcher constructor). If so, they stop automatically when BlockFetcher stops.

Step 3 — Add comment at BlockFetcher spawn site in RegularSync:
  // BlockFetcher uses AbstractBehavior and spawns children in its constructor.
  // Pekko restart would re-run the constructor and ghost the old children.
  // Default stop-on-failure is intentional; RegularSync re-spawns on BlockFetcherStopped.

Step 4 — sbt compile-all — must be clean.
Step 5 — git commit -m "docs(7c-C): document BlockFetcher stop-on-failure rationale (RF-2 ghost-child risk)"
```

---

#### §7c-E3 — RF-3: SyncStateSchedulerActor (FastSync, storm-bounded restart)

**Files:** `blockchain/sync/fast/SyncStateSchedulerActor.scala` spawn site in FastSync
**Agent:** MITHRIL | **Gate:** FastSync spec review confirms acceptable re-request behaviour.

**Prompt:**
```
Read `.local/docs/supervision-design-7c.md` Part 3 RF-3.

Precondition: confirm that re-requesting all in-flight peer assignments simultaneously
on SyncStateSchedulerActor restart is tolerable — FastSync will time out pending requests
regardless, so restart just accelerates the timeout. The PeerRateTracker re-initialises
from zero (conservative), so the burst is rate-limited by the conservative initial
per-peer window.

If confirmed — apply at the SyncStateSchedulerActor spawn site (in FastSync):
  Behaviors.supervise(SyncStateSchedulerActor(...)).onFailure[Throwable](
    SupervisorStrategy.restartWithBackoff(5.seconds, 60.seconds, 0.3).withMaxRestarts(2))

sbt compile-all — must be clean.
git commit -m "feat(7c-E3): restartWithBackoff for SyncStateSchedulerActor (bounded storm)"
```

---

### 7e-P2 — HealingState Extraction (SSC)

**What**: Six SSC fields are semantically phase-local to the healing phase (`trieWalkInProgress`, `healingServeRootRequestInFlight`, and four related vars). Currently on `Impl`, they bleed across pivot refreshes if a pivot rotation occurs while healing. Extract to `case class HealingState(...)` threaded as a behavior parameter.

**Scope**: `sync/snap/SNAPSyncController.scala` — `Impl` field extraction + healing behavior split.
**Gate**: 7a ✅ done. HERALD pre-flight recommended for pivot-rotation interaction.
**Priority**: LOW — latent correctness risk on pivot rotation during heal; not currently triggered.
**Agent**: LOOM + EYE.

---

### 7e-P3 — ChainDownloader Stagnation Push (SSC)

**What**: SSC currently polls `ChainDownloader` progress timestamps to detect download stagnation (`CheckDownloadStagnation` timer). Invert: `ChainDownloader` pushes a `DownloadStagnated` message to SSC when it detects stagnation internally.

**Scope**: `sync/snap/ChainDownloader.scala` + `sync/snap/SNAPSyncController.scala` — stagnation detection path only.
**Gate**: 7a ✅ done.
**Priority**: LOW — polling timer works correctly; this is a design improvement (back-pressure, reduced coupling).
**Agent**: LOOM + HERALD + EYE.

---

## Part 8: Additional Modernization Gaps

### 8a — Classic TestKit → ActorTestKit Migration

**Batches 1–5 + §8a-infra-b/c DONE** — see completed table above. **E165 floor now 65 unnarrowed sites.**

**Remaining (Batch E6):** PeerActorSpec + RLPxConnectionHandlerSpec — **wait for Wave 3** (net/P2P sprint). RegularSyncSpec ✅ DONE `57d638d49`. BlockFetcherSpec + PendingTxMgrSpec ✅ DONE `5ff14017b`.

**Remaining (Batch E6b — timing failures):** `ChainWeightCalibrationSpec` — **Wave-3-deferred**. Two tests fail with `fishForMessage()` unexpectedly receiving `GetHandshakedPeersCmd` (actor message-ordering / timing sensitivity). Confirmed in §8b-H2 testEssential run 2026-06-26. Uses `WithActorSystemShutDown` (CHASE-QUEUE line 36). Resolution: migrate to `ScalaTestWithActorTestKit`, replace `fishForMessage` with `expectMessageType` + `drainMailbox` pattern — add an explicit registration-message drain before each assertion to absorb `GetHandshakedPeersCmd`. Gate: Wave 3 net/P2P sprint (same as E6).

**Research prompt for remaining §8a-retro batches:**
> List test files still using Classic TestKit:
> ```bash
> grep -rn "org.apache.pekko.testkit.TestKit" src/test/ --include="*.scala" -l | grep -v "ActorTestKit"
> ```
> For each file: (a) which production actor it tests (must already be Typed), (b) `fishForMessage` → `expectMessageType` replacements, (c) any `system.toClassic` needs (Pekko HTTP, Classic eventStream).

---

### 8b — Opaque Types for Domain Value Concepts

**R6 research complete** — see `.local/docs/opaque-type-domain-analysis.md` (2026-06-25).
Scale: 2,276 `BigInt` usages / 204 files · 2,305 `ByteString` usages / 243 files.

**Tiered candidate summary:**

| Tier | Candidate | Raw type | Files | Gate |
|------|-----------|----------|-------|------|
| LOW | `TxHash` | `ByteString` | 23 | None |
| LOW | `BloomFilter` | `ByteString` | ~30 | None |
| LOW | `BlobVersionedHash` | `ByteString` | ~15 | None (ETH-only) |
| MEDIUM | `StorageKey` Phase A (AccessListItem only) | `BigInt` | 7 | None |
| MEDIUM | `CodeHash` | `ByteString` | 32 | FORGE + BEACON advisory |
| MEDIUM | `StorageKey` Phase B (ProgramState/EVM) | `BigInt` | ~12 | FORGE + BEACON |
| MEDIUM | `StorageKey` Phase C (transientStorage) | `BigInt` | ~5 | FORGE + BEACON advisory |
| HIGH | `BlockHash` | `ByteString` | ~50 | FORGE + BEACON |
| HIGH | `TrieRoot` | `ByteString` | ~25 | FORGE + BEACON |
| HIGH | `Difficulty` | `BigInt` | ~24 | FORGE + BEACON |
| HIGH | `TotalDifficulty` | `BigInt` | ~26 | FORGE + BEACON |
| HIGH | `GasAmount` | `BigInt` | ~39 | FORGE + BEACON |
| HIGH | `GasPrice` | `BigInt` | ~25 | FORGE + BEACON |
| HIGH | `BlockNumber` | `BigInt` | ~124 | FORGE + BEACON (fork dispatch) |
| HIGH | `ChainId` | `BigInt` | ~27 | FORGE + BEACON (signing) |

**Caveats:**
- `UInt256` and `Address` are hand-rolled wrappers — do not introduce competing types.
- `ECDSASignature(r, s, v: BigInt)` — crypto domain, leave as-is.
- HIGH tier deferred until LOW/MEDIUM establishes RLP `.xmap` pattern.

---

#### §8b-L1 — `TxHash`: ByteString → opaque type

**Files:** `domain/TxHash.scala` (new), `SignedTransaction.scala`, `jsonrpc/` (~23 files)
**Agent:** MITHRIL | **Gate:** None.

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type TxHash = ByteString` in `src/main/`.

Context: R6 analysis confirmed `TxHash` crosses no consensus paths and has no RLP
serialization at the field level — the hash is computed, not decoded from wire bytes.
Reference: `.local/docs/opaque-type-domain-analysis.md` §1.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/TxHash.scala`:
```scala
opaque type TxHash = ByteString
object TxHash:
  def apply(bs: ByteString): TxHash = bs
  def fromBytes(bs: ByteString): TxHash = bs
  extension (th: TxHash)
    def value: ByteString = th
    def toHexString: String = th.map("%02x".format(_)).mkString("0x", "", "")
```
Step 2 — Update `SignedTransaction.scala`: `def hash: ByteString` → `TxHash`; wrap result: `TxHash(kec256(...))`.
Step 3 — Update `txSenders` cache key type: `Cache[ByteString, Address]` → `Cache[TxHash, Address]`.
Step 4 — Update all `jsonrpc/` callers: where return value flows into an API response field typed `ByteString`, call `.value` to unwrap.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — `sbt "testOnly *SignedTransaction* *TransactionPool*"`.
Step 7 — `git commit -m "feat(8b-L1): introduce TxHash opaque type (ByteString) — 23 call sites"`
```

---

#### §8b-L2 — `BloomFilter`: ByteString → opaque type

**Files:** `domain/BloomFilter.scala` (new), `BlockHeader.scala`, `Receipt.scala`, jsonrpc response types (~30 files)
**Agent:** MITHRIL | **Gate:** None.

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type BloomFilter = ByteString` in `src/main/`.

Context: `logsBloom` is a fixed 256-byte field on `BlockHeader` and `Receipt`. Never used in
consensus validation (Ethash, fork dispatch, MESS). One-line RLP derivation via `.xmap`.
Reference: `.local/docs/opaque-type-domain-analysis.md` §2.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/BloomFilter.scala`:
```scala
opaque type BloomFilter = ByteString
object BloomFilter:
  val Empty: BloomFilter = ByteString(new Array[Byte](256))
  def apply(bs: ByteString): BloomFilter = bs
  extension (bf: BloomFilter) def value: ByteString = bf
  given rlpCodec: RLPCodec[BloomFilter] = summon[RLPCodec[ByteString]].xmap(BloomFilter.apply, _.value)
```
Step 2 — Update `BlockHeader.scala`: `logsBloom: ByteString` → `BloomFilter`. Wrap/unwrap at codec boundary.
Step 3 — Update `Receipt.scala`: same pattern.
Step 4 — Update jsonrpc response types: call `.value.toHexString` instead of `.toHexString`.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — `sbt "testOnly *BlockHeader* *Receipt*"`.
Step 7 — `git commit -m "feat(8b-L2): introduce BloomFilter opaque type (ByteString) — ~30 sites"`
```

---

#### §8b-L3 — `BlobVersionedHash`: ByteString → opaque type

**Files:** `domain/BlobVersionedHash.scala` (new), `Transaction.scala` (`BlobTransaction`), `ETHPackets.scala`, jsonrpc blob response types (~15 files)
**Agent:** MITHRIL | **Gate:** None (ETH-only, no consensus path).

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type BlobVersionedHash = ByteString` in `src/main/`.

Context: `blobVersionedHashes: List[ByteString]` in `BlobTransaction` is EIP-4844 specific and
ETH-only. ~15 files. Reference: `.local/docs/opaque-type-domain-analysis.md` §3.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/BlobVersionedHash.scala`:
```scala
opaque type BlobVersionedHash = ByteString
object BlobVersionedHash:
  def apply(bs: ByteString): BlobVersionedHash = bs
  extension (bvh: BlobVersionedHash) def value: ByteString = bvh
  given rlpCodec: RLPCodec[BlobVersionedHash] = summon[RLPCodec[ByteString]].xmap(BlobVersionedHash.apply, _.value)
```
Step 2 — Update `Transaction.scala` `BlobTransaction`: `blobVersionedHashes: List[ByteString]` → `List[BlobVersionedHash]`.
Step 3 — Update `ETHPackets.scala` codec: wrap at decode, `h.value.toArray` at encode.
Step 4 — Update jsonrpc blob response fields.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — `sbt "testOnly *BlobTransaction* *ETH*"`.
Step 7 — `git commit -m "feat(8b-L3): introduce BlobVersionedHash opaque type (ByteString) — ~15 sites"`
```

---

#### §8b-M1 — `StorageKey` Phase A: AccessListItem (no EVM touch)

**Files:** `domain/StorageKey.scala` (new), `Transaction.scala` (`AccessListItem`), `ETHPackets.scala` EIP-2930 codec (~7 files)
**Agent:** MITHRIL | **Gate:** None for Phase A.

**Prompt:**
```
Use the MITHRIL agent. Introduce `opaque type StorageKey = BigInt` — Phase A only
(AccessListItem; do NOT touch ProgramState or EthereumUInt256Mpt in this pass).

Context: `AccessListItem.storageKeys: List[BigInt]` is EIP-2929/2930. Phase A is safe because
AccessListItem is never read inside the EVM opcode dispatcher directly.
Reference: `.local/docs/opaque-type-domain-analysis.md` §4 Phase A.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/StorageKey.scala`:
```scala
opaque type StorageKey = BigInt
object StorageKey:
  def apply(v: BigInt): StorageKey = v
  extension (sk: StorageKey) def value: BigInt = sk
  given rlpCodec: RLPCodec[StorageKey] = summon[RLPCodec[BigInt]].xmap(StorageKey.apply, _.value)
```
Step 2 — Update `Transaction.scala` `AccessListItem`: `storageKeys: List[BigInt]` → `List[StorageKey]`.
Step 3 — Update `ETHPackets.scala` EIP-2930/EIP-1559 typed tx codecs: wrap at decode, `.value` at encode.
Step 4 — Check other AccessListItem callers. STOP if you reach `ProgramState.scala` or `EthereumUInt256Mpt` — those are Phase B.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — `sbt "testOnly *Transaction* *AccessList*"`.
Step 7 — `git commit -m "feat(8b-M1): StorageKey opaque type Phase A — AccessListItem only, ~7 files"`
```

---

#### §8b-M2 — `CodeHash`: ByteString → opaque type (FORGE + BEACON advisory)

**Files:** `domain/CodeHash.scala` (new), `Account.scala`, `BlockchainReader.scala`, state trie layer (~32 files)
**Agent:** MITHRIL + FORGE advisory + BEACON advisory | **Gate:** FORGE + BEACON — EIP-161 and EIP-684 are consensus paths on both chains.

**Prompt:**
```
Use MITHRIL to implement `opaque type CodeHash = ByteString`, then ask FORGE and
BEACON to review before committing.

Context: `Account.codeHash` and `Account.EmptyCodeHash` are used in EIP-161 empty-account checks
and EIP-684 collision detection on BOTH ETC and ETH. The opaque wrapper preserves runtime `==`.
Reference: `.local/docs/opaque-type-domain-analysis.md` §5.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/CodeHash.scala`:
```scala
opaque type CodeHash = ByteString
object CodeHash:
  val Empty: CodeHash = ByteString(new Array[Byte](32))  // kec256(ByteString.empty)
  def apply(bs: ByteString): CodeHash = bs
  extension (ch: CodeHash)
    def value: ByteString = ch
    def isEmpty: Boolean = ch == Empty
  given rlpCodec: RLPCodec[CodeHash] = summon[RLPCodec[ByteString]].xmap(CodeHash.apply, _.value)
```
Step 2 — Update `Account.scala`: `codeHash: ByteString` → `CodeHash`. `EmptyCodeHash` → `val EmptyCodeHash: CodeHash = CodeHash.Empty`. Wrap/unwrap at codec boundary only.
Step 3 — Update all sites referencing `Account.codeHash` or `Account.EmptyCodeHash`. EIP-161/684 `==` checks: still work through opaque type — no change needed.
Step 4 — `sbt compile-all` — must be clean.
Step 5 — FORGE review: confirm EIP-161/684 semantics preserved on ETC, no byte encoding altered.
Step 5b — BEACON review: confirm EIP-161/684 semantics preserved on ETH.
Step 6 (after FORGE + BEACON confirm) — `sbt "testOnly *Account* *State*"`.
Step 7 — `git commit -m "feat(8b-M2): CodeHash opaque type (ByteString) — Account + EIP-161/684 sites, FORGE+BEACON reviewed"`
```

---

#### §8b-M3 — `StorageKey` Phase B: ProgramState / EVM (FORGE + BEACON gate)

**Files:** `ProgramState.scala`, `EthereumUInt256Mpt.scala`, EVM opcode warm-storage sites
**Agent:** FORGE + BEACON | **Gate:** FORGE + BEACON — `ProgramState.addAccessedStorageKey` flows through EVM opcode dispatcher for both ETC and ETH.
**Prerequisite:** §8b-M1 committed.

**Prompt:**
```
Use the FORGE agent for ETC EVM paths, then BEACON agent for ETH EVM paths. Extend `StorageKey`
opaque type from §8b-M1 into `ProgramState` and the EVM storage layer (Phase B).

Pre-flight:
1. Confirm §8b-M1 committed: `grep "opaque type StorageKey" src/main/scala/.../StorageKey.scala`
2. Confirm `sbt compile-all` is clean.

Step 1 — Update `ProgramState.scala`: `addAccessedStorageKey(address: Address, key: BigInt)` → `key: StorageKey`. At call sites wrap with `StorageKey(bigInt)`.
Step 2 — Update `EthereumUInt256Mpt`: only if key is clearly a storage slot.
Step 3 — Verify EVM SLOAD/SSTORE opcode sites: `grep -rn "addAccessedStorageKey\|isStorageKeyWarm" src/main/ --include="*.scala"`.
Step 4 — `sbt compile-all` — must be clean.
Step 5 — `sbt testVM` — EVM opcode tests must pass.
Step 6 — `sbt "testOnly *ProgramState* *EthereumUInt256Mpt*"`.
Step 7 — `git commit -m "feat(8b-M3): StorageKey Phase B — ProgramState + EVM warm-storage check, FORGE+BEACON reviewed"`
```

---

#### §8b-M4 — `StorageKey` Phase C: transientStorage type consistency (FORGE + BEACON advisory)

**Files:** `ProgramState.scala`, `ProgramContext.scala`, `ProgramResult.scala`, `OpCode.scala` (TLOAD/TSTORE sites)
**Agent:** MITHRIL + FORGE advisory + BEACON advisory | **Gate:** §8b-M3 committed.
**Origin:** BEACON M3 post-commit review finding — `transientStorage: Map[(Address, BigInt), BigInt]` intentionally left as `BigInt` in M3 (EIP-1153 is not EIP-2929 warm-tracked). This prompt applies `StorageKey` for slot-key type consistency.

**Prompt:**
```
Use MITHRIL to extend `StorageKey` opaque type into the EIP-1153 transient storage maps (Phase C).
Then ask FORGE and BEACON for advisory confirmation.

Pre-flight: confirm §8b-M3 committed.

Step 1 — Update `ProgramState.scala`: `transientStorage: Map[(Address, BigInt), BigInt]` → `Map[(Address, StorageKey), BigInt]`. In `getTransientStorage`/`setTransientStorage` wrap the raw `BigInt` key with `StorageKey(storageKey)` at the boundary.
Step 2 — Update `ProgramContext.scala`: same field rename.
Step 3 — Update `ProgramResult.scala`: same field rename.
Step 4 — Update TLOAD/TSTORE opcode sites in `OpCode.scala`: wrap slot key with `StorageKey(offset.toBigInt)`.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — FORGE advisory: confirm TLOAD/TSTORE dispatch on ETC is semantically unchanged.
Step 7 — BEACON advisory: confirm TLOAD/TSTORE dispatch on ETH (EIP-1153) is semantically unchanged.
Step 8 — `sbt "testOnly *OpCode* *ProgramState* *VM*"`.
Step 9 — `git commit -m "feat(8b-M4): StorageKey Phase C — transientStorage type consistency (TLOAD/TSTORE), FORGE+BEACON advisory"`
```

---

#### §8b-H1 through §8b-H8 — High-tier opaque types (deferred until LOW/MEDIUM proven)

**Gate:** §8b-L1/L2/L3 and §8b-M1 committed and CI green (establishes RLP `.xmap` pattern in CI).
**Candidates in sequence:** `BlockHash` (H1, ~50 files) → `TrieRoot` (H2, ~25 files) → `Difficulty` (H3, ~24 files) → `TotalDifficulty` (H4, ~26 files) → `GasAmount` (H5, ~39 files) → `GasPrice` (H6, ~25 files) → `BlockNumber` (H7, ~124 files, largest) → `ChainId` (H8, ~27 files, most dangerous — sign last).

Each requires FORGE + BEACON review. Full prompts available in `completed/DEFERRED-BACKLOG.md §8b-H1–H8` (captured from prior session context).

---

### 8c — Memory/Resource Leak Audit

**H2/H3 DONE** `4907406fe`, **H4+M1 DONE** `ef75a5608`, **M3/M4 DONE (by-design)** — see completed table.

**Remaining deferred (no prompt yet, VAULT gate):**
- M2 — EngineApiService hash-keyed maps with finalized-watermark prune (BEACON + CONDUIT)
- L1/L2/L3 — lower-priority lifecycle items identified in R8 audit

See `completed/DEFERRED-BACKLOG.md §8c` for full M2/L1/L2/L3 context.

---

### 8e — ScalaFix Ruleset Expansion + `noReturns` Ratchet Lock

**C2/TNHC/FORGE/StackTrie/BEACON DONE** — see completed table.

**Remaining to lock the ratchet** (`sbt scalafixAll` not yet green): 38 deferred sites.

| Deferred category | File(s) | Count | Gate |
|-------------------|---------|-------|------|
| Classic actor — Wave 3 LOOM sprint | `sync/snap/SNAPSyncController.scala` | 36 | Wave 3 network/sync migration (SNAP1) |
| Consensus-path (ETH Engine API) — BEACON review | `consensus/engine/EngineApiController.scala:96` + `:226` | 2 | BEACON sign-off (S3-D) |

**Full ratchet lock checklist:**
1. ~~C2 chore~~ ✅ `9eb1f4e06`
2. ~~LOOM Phase 0 TNHC~~ ✅ `7a48c5988`
3. ~~FORGE: 6 consensus sites~~ ✅ `4544b8025` (6 CLEAR + 9 DEFER `scalafix:ok`)
4. ~~StackTrie `:120`+`:462` DEFER re-assessment~~ ✅ `09307c5a7` (both CLEAR)
5. ~~BEACON: 3 ETH Engine API sites~~ ✅ `d78177bda`
6. BEACON reviews and clears 2 remaining ETH Engine API sites — `EngineApiController.scala:96` + `:226` (S3-D). Both are early-`return IO.pure(...)` decode-error guards; removing the `return` requires wrapping ~90 lines into the `Right`/`else` branch. Gated on a focused BEACON pass.
7. Wave 3 SNAP1 migration sprint clears SNAPSyncController 36 sites (gated on NET2)
8. After all above: run `sbt scalafixAll` → 0 violations → ratchet locked.

**Other rules to evaluate enabling (unblocked, lower priority):**
```
LeakingImplicitClassVal  # implicit class vals that escape scope
OrganizeImports          # import grouping/deduplication
ExplicitResultTypes      # explicit return types on public defs (enable gradually)
```

---

### 8g — Braceless Scala 3 Syntax

**Partial work committed (2026-06-18):** `convertToNewSyntax = true` applied (392 source files, 3,125 ins / 3,482 del, zero logic impact).

**Remaining work**: Enable `removeOptionalBraces = true` in `.scalafmt.conf` and apply subsystem by subsystem (`jsonrpc/` first, then `network/`, etc.) rather than a 945-file sweep.

**Gate:** After CAPSTONE (don't distract migration diffs with style churn).
**Parallel-safe:** YES per-file, but mass conversion creates large diffs — scope per-subsystem.
**Priority:** LOW — style only.
**Agent:** MITHRIL.

---

### 8i — RLP Typeclass Derivation Modernization

**Current state**: 183 `implicit val`/`def` RLP encoder/decoder instances. Two categories:
1. **Product type codecs** (simple ADTs) — can use Magnolia/Shapeless-style derivation via `given RLPEncoder[MyType] = RLPEncoder.derived`.
2. **Custom-layout codecs** (ETH wire protocol) — MUST remain handwritten.

**Gate:** Part 3a (implicit→given) complete. R7 research thread complete.
**Parallel-safe:** NO — codec changes are high-risk (wire protocol correctness). Full test suite run required after each change.
**Priority:** MEDIUM — reduces maintenance burden for new message types.
**Agent:** MITHRIL (derivation design) + FORGE (consensus message layout) + EYE (full codec test suite after each change).

---

### 8j — Test Quality: Thread.sleep and Timing Sensitivity

**EYE baseline 2026-06-22:** 2 live `Thread.sleep` call sites (SubscriptionManagerSpec:249, EthMiningServiceSpec:302). 11 comment-only references. No new FLAKY sites.

**Status:** Both live call sites are NECESSARY. Deferred to §8a-retro (Wave 3 test migration will naturally address the surrounding test structure).
**Priority:** LOW-MEDIUM — prevents CI flakiness as test suite grows.
**Agent:** EYE.

---

## Recommended Sprint Sequence

### Primary Track (blocking — sequential)

| Sprint | Work | Gate |
|--------|------|------|
| **Network/sync Pekko** | Part 2: S3→S4/S7→NET2→SNAP1→SNAP2→ROOT→CAPSTONE (see SPRINT-QUEUE.md) | scala3-cleanup-june merged |
| **→ CAPSTONE** | Root flip: `ActorSystem[Nothing]`, bridge/adapter removal, `Behavior[Any]` narrowing | All actors Typed |
| **7c — Supervision** | §7c-P0 → §7c-D → §7c-A → §7c-B → §7c-C/E | CAPSTONE + 7a ✅ |
| **8b — Opaque types** | LOW tier first → MEDIUM → HIGH (in tier order) | Part 3a done |
| **8i — RLP derivation** | Replace handwritten product-type RLP codecs | Part 3a + R7 done |
| **3a — implicit→given** | 198 files, 522 declarations (scalafix GivenUsing) | After Pekko migration |
| **4e Jackson 3** | json4s 4.2.0-M5 gate | json4s M5 release |
| **4a JLine 4** | TUI refactor | Dedicated sprint |
| **5a Scala 3.9** | Version bump | 3.9 LTS release |
| **5c Constitution** | v1.2.0 | After 5a |

### Housekeeping Track (parallel-safe — fill test-wait downtime)

| Task | Work | Effort |
|------|------|--------|
| **8e BEACON S3-D** | Clear 2 remaining EngineApiController return sites | ~30 min |
| **8e SNAP1** | Clear 36 SSC sites (gated on NET2 Wave 3) | Wave 3 sprint |
| **8g braceless** | `removeOptionalBraces` per-subsystem passes | Low, per-subsystem |
| **8a retro batch E6** | PeerActorSpec + RLPxConnectionHandlerSpec (wait Wave 3) | Wave 3 |
| **8a retro batch E6b** | ChainWeightCalibrationSpec — `fishForMessage` timing failures (wait Wave 3) | Wave 3 |
| **8j Thread.sleep** | 2 live sites (both NECESSARY — revisit in Wave 3 test migration) | Wave 3 |

### Research Threads

| Thread | Goal | Gate |
|--------|------|------|
| **R4** | Scala 3.9 readiness (periodic — when 3.9 LTS appears) | 3.9 LTS release |
| **R7** | RLP codec derivation safety analysis (safe-to-derive vs must-stay-manual) | Part 3a done |

---

## Clearout Prompts

### Active

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| E6 | Batch E | §8a-retro batch E6 — PeerActorSpec + RLPxConnectionHandlerSpec | Gate: Wave 3 network/P2P sprint |
| E6b | Batch E | §8a-retro batch E6b — ChainWeightCalibrationSpec (`fishForMessage` timing) | Gate: Wave 3 network/P2P sprint |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

## Known Pre-existing Failures

Test failures that exist independently of any ongoing migration. Not caused by recent changes.

| Spec | Issue | Mitigation / Gate |
|------|-------|-------------------|
| `KzgPointEvaluationSpec` | JVM SIGSEGV at `__libc_free` (`libc.so.6`) via KZG JNI (`ethereum-consensus:kzg4844`, EIP-4844 blob point evaluation). Crashes testEssential at ~737 s. First confirmed in §8b-H2 testEssential run 2026-06-26 — pre-existing, unrelated to TrieRoot opaque type. Solo run with `sbt "testOnly *KzgPointEvaluation*"` confirms reproduction. | Research: JDK 25 vs JDK 21 native compat; bump native `ethereum-consensus:kzg4844` lib version. Mitigation: run solo in CI, add `@NativeTest` tag to skip in testEssential. |
