# sync/controller — Sync Orchestrator

**Package:** `SyncController.scala`, `SyncProtocol.scala`
**Gate:** None (orchestration); `herald` for peer-message routing; `forge`/`beacon` for chain-selection logic
**Key files:** `SyncController.scala`, `SyncProtocol.scala`

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-ROOT/CAPSTONE commits — SyncController + NodeBuilder root flip
- **What:** CAPSTONE: `ActorSystem[Nothing]` root; SyncController fully Typed; last `extends Actor` removed from main path
- **HERALD pre-flight:** HERALD-5
- **Cross-refs:** `node/bootstrap.md` (NodeBuilder ActorSystem flip), `sync/fast.md`, `network/peers.md`

---

## ADT Narrowing (Wave 3, Phase 1/2/3)

#### `948a25008` — Phase 1: SyncController sealed Command trait
- **Cross-refs:** `sync/fast.md`, `sync/regular.md`, `network/peers.md`

#### `2a2d77166` + `00cf1bed1` + `0d8adfd5c` — Phase 3: SyncController narrowing passes
- **What:** `Behavior[Any]` → `Behavior[Command]`; `WrappedExternal` adapters retyped

---

## Quality Fixes (CODEBASE-AUDIT)

#### `a5132aa80` — C2: EC.global removed from SyncController
- **What:** `import scala.concurrent.ExecutionContext.Implicits.global` deleted; `given ec = ctx.system.executionContext` added in `startSnapSync` only

#### `8a65bbdb7` — W3/W10/INFO-2/INFO-4/INFO-5/INFO-6/INFO-11 batch
- **What:** HandshakedPeers fallthrough guard; `withPostStop` → `receiveSignal`; system-property path normalization + BigInt error handling; stale `Behavior[Any]` docs fixed
- **Source:** CODEBASE-AUDIT W3/W10

#### `a73ce7922` — docs: stale Behavior[Any] refs + WrappedExternal comment
- **Source:** CODEBASE-AUDIT INFO-2/INFO-4/INFO-11

#### `8bd4ed3f1` — W9: versioned child names at `startRegularSyncForBootstrap`
- **What:** Fixed child names → `s"...-$bootstrapGeneration"` to prevent `InvalidActorNameException` on restart
- **Source:** CODEBASE-AUDIT W9

#### `fc1030410` — SyncControllerSpec: SyncStateAutoPilot GetHandshakedPeersCmd handler
- **What:** 7 pre-existing MatchError failures fixed; autopilot now handles `GetHandshakedPeersCmd(replyTo)` → `HandshakedPeers` stub. Test-only change — no production source edits.

---

#### `86c76fd4e` — P9: re-enabled SyncControllerSpec:434 "re-enqueue block bodies when empty response received"
- **What:** Added `RegisterChainWeightCalibrationTarget` and `CalibrateChainWeightNow` no-op handlers to `SyncStateAutoPilot`. SyncController started sending these messages after RegularSync integration work; their absence caused `MatchError` crashes that blocked this test. Test-only change.
- **Verification:** Test passes; no regressions in `testOnly *SyncControllerSpec*`

#### Known production bug — `handleRegularSyncMsg` catch-all (fix pending in F7/P10)
- **Location:** `SyncController.scala:895-897`
- **What:** `handleRegularSyncMsg` forwards all unhandled messages to RegularSync via `regularSync.tell(msg, ctx.toClassic.sender())`. When `FastSync.Done` arrives late (after `syncSwitchDelay = 0.5s`, after SyncController has transitioned to `runningRegularSync`), it hits this catch-all and crashes RegularSync with `ClassCastException: FastSync$Done$ cannot be cast to RegularSyncCommand`.
- **Root cause of:** "start state download" FlakyTest in `SyncControllerSpec`
- **Fix:** Add `case FastSync.Done => Behaviors.same` guard before the catch-all
- **Status:** In P10 prompt (DEFERRED-BACKLOG Part 11 §P10), committed as part of F7

---

#### `ab98f1370` — P10: SyncController FastSync.Done production guard + SyncControllerSpec FlakyTests
- **Production fix:** Added `case FastSync.Done => Behaviors.same` guard in `handleRegularSyncMsg` before the catch-all `regularSync.tell(msg, ...)` forward. When `FastSync.Done` arrives late (after `syncSwitchDelay = 0.5s`), it no longer crashes RegularSync with `ClassCastException: FastSync$Done$ cannot be cast to RegularSyncCommand`. Root cause of the "start state download" FlakyTest cluster eliminated.
- **SyncControllerSpec FlakyTests:** 1 de-tagged (now stable); 2 deleted — "start state download only when pivot block is fresh enough" (depended on the production bug; deleted) and coverage gap filled by rewrite below.
- **Cross-refs:** `sync/fast.md` (FastSyncSpec FlakyTests same thread)

#### `18ceefa5b` + `0023c90be` — P10: stalePivotAfterRestart coverage gap filled
- **What:** `SyncControllerSpec` coverage gap logged during P10: the `stalePivotAfterRestart` path in `newPivotIsGoodEnough` had no test. Replacement test written: peers at `bestBlock-1=399999` produce pivot `399499=currentPivot` (rejected); then peers at `bestBlock=400000` produce pivot `399500>399499` (accepted → `stateDownloadStarted=true`). Assertion uses `should be > 0` (not `shouldBe 1`) to avoid timing flakiness. 3/3 passes confirmed. CHASE-QUEUE entry cleared.
- **Cross-refs:** F7 (P10 thread)

---

## Classic Interop — §8k-G (COMMITTED `2ef2b6637`, testEssential pending)

#### `2ef2b6637` — refactor(8k-G): OQ-5 kill — SyncProtocol ADT + SyncController sender() elimination (Cluster C)
- **What:** Cluster C — `ctx.toClassic.sender()` in `SyncController.scala` / `SyncProtocol.scala` eliminated. `GetStatus`, `ResetFastSync`, `RestartFastSync` lifted to `final case class` with `replyTo: ActorRef[T]`. All `ctx.toClassic.sender()` sites replaced with `cmd.replyTo !`.
- **Cross-refs:** `api/jsonrpc.md` (Cluster L — jsonrpc services updated), `node/bootstrap.md` (Cluster K — NodeBuilder syncController type lifted)
- **Cluster E deferred:** `externalAdapter.toClassic` at spawn sites — per-child constructor param issue, not OQ-5. Tracked in §8k-G2 + CHASE-QUEUE.

---

## Per-Child Typed Adapters — §8k-G3 (2026-06-24)

#### `a8cea433c` — refactor(8k-G3): per-child typed messageAdapters — eliminate ActorRef[Any] from child constructor params
- **What:** SyncController's single `externalAdapter: TypedActorRef[Any]` replaced with 6 narrow per-child adapters. Each child constructor param narrowed to the specific type it sends. `StorageRecoveryActor` and `PivotHeaderBootstrap` gained `sealed trait SyncControllerMsg` / `sealed trait Reply` to unify their 2-message send sets. `FastSync` gained `sealed trait SyncControllerMsg`. `ChainDownloader` param narrowed to `Done.type`. `SNAPSyncController` deferred → §8k-G3-SSC (sends 7 types across 2 files; needs unsealed marker trait).
- **Files:** `SyncController.scala`, `BytecodeRecoveryActor.scala`, `StorageRecoveryActor.scala`, `CombinedRecoveryScanActor.scala`, `PivotHeaderBootstrap.scala`, `FastSync.scala`, `ChainDownloader.scala`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8k-G3`

#### `79068ad11` — §8k-G3-SSC: type SNAPSyncController.syncController via SyncControllerReply marker trait
- **What:** The one SSC child deferred from §8k-G3. Added `trait SyncControllerReply` (unsealed) to `SyncProtocol.scala`; `HealingImpossible` now extends both `SyncProtocolMsg` and `SyncControllerReply`. Six SSC companion types (`Done`, `StartRegularSyncBootstrap`, `StartRegularSyncBootstrapByHash`, `FallbackToFastSync`, `SnapSyncFinalized`, `RequestHealingServeRoot`) all extend `SyncProtocol.SyncControllerReply`. Both SSC constructor sites changed from `TypedActorRef[Any]` → `TypedActorRef[SyncProtocol.SyncControllerReply]`. `SyncController` adds `snapAdapter` via `ctx.messageAdapter[SyncProtocol.SyncControllerReply]`. `externalAdapter` retained — 8 remaining consumers (FCM, NPMA paths) gated on §8k-G4.
- **Files:** `SyncProtocol.scala`, `SNAPSyncController.scala`, `SyncController.scala`
- **End state:** 0 `ActorRef[Any]` hits in production code under the sync package.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8k-G3-SSC`, `modernization-log/sync/snap.md`

---

## Open / Deferred

- W4: `ctx.self ! cmd` re-delivers wrapped Command (document invariant) — deferred; add by-design comment at call site during Network/P2P sprint
- ~~W15: `unwrap returns Any`~~ — ✅ DONE `79068ad11` §8k-G3-SSC. `externalAdapter: TypedActorRef[Any]` retained only for FCM + NPMA consumers (§8k-G4); 0 `ActorRef[Any]` hits remain in sync package production code. Full `WrappedExternal` elimination gated on Wave 3 LOOM / CAPSTONE.
- ~~INFO-9: `GetHandshakedPeersCmd.replyTo: ActorRef` untyped~~ — ✅ DONE `c42316b39` §8k-E
- ~~§P9-NOTCHANGE: SyncControllerSpec:243~~ — ✅ DONE 2026-06-23 (`37037a89b` + `5a12c7f09`) — see `sync/fast.md`
- ~~`handleRegularSyncMsg:895-897` catch-all `FastSync.Done` bug~~ — ✅ DONE 2026-06-23 (`ab98f1370`)
- §8a-retro batch 5: `SyncControllerSpec`, `CalibratePivotTDSpec`, `ChainWeightCalibrationSpec` — deferred comments added; gate on SyncController actor migration (Wave 3)
- ~~§P9-FRESHPIVOT: SyncControllerSpec:393~~ — ✅ DONE 2026-06-23 (`083f08836`) — two concurrent races: (1) `CombinedRecoveryScanActor` completes early on ForkJoinPool → `clearFastSyncState()` → `getSyncState()=None`; (2) recovery path sets `stateDownloadStarted=true` pre-storage-update → wrong pivot in a synchronous check outside `eventually`. Fix: kept `eventually` unified; replaced `.get` with `.map(_.pivotBlock).getOrElse(defaultPivotBlockHeader)` — safe for all three terminal states. 88/88 × 3 consecutive full-suite runs.
