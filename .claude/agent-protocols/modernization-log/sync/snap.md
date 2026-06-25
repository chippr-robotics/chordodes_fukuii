# sync/snap — SNAP Sync

**Package:** `blockchain/sync/snap/`, `blockchain/sync/checkpoint/`
**Gate:** None (sync infrastructure); `herald` for SNAP wire protocol
**Key files:** `SNAPSyncController.scala`, `AccountRangeCoordinator.scala`, `ByteCodeCoordinator.scala`, `HealingCoordinator.scala`

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-SNAP1/SNAP2 commits — SNAPSyncController + coordinators Typed
- **What:** `extends Actor` → `Behaviors.receive`; sealed Command ADTs; explicit `replyTo`
- **HERALD pre-flight:** HERALD-3 (SNAP protocol), HERALD-5 (healing coordinator)
- **Cross-refs:** `sync/fast.md` (FastSync is SNAP client), `network/snap-server.md` (serve side)

---

## SSC Design Review (7e — DEFERRED-BACKLOG)

#### `1da94de11` — 7e-P4: SSC Idle-State Catch-All redesign
- **What:** 59 explicit handlers added to SNAPSyncController idle state; removed unsafe catch-all
- **Scope:** `SNAPSyncController.scala` idle behavior block

#### `74db726d1` — 7e-P4a: GetProgress missing idle-state handler
- **What:** `GetProgress` command lacked an idle-state handler; added explicit response
- **Source:** Chase-queue housekeeping: PivotBlockSelector UnsubscribeAllCmd follow-up

---

## Quality Fixes

#### W3-WormToBrainBar `c8a1ddbfc` — worm bar deleted from FastSync inline; shared utility wired in
- **Cross-refs:** `sync/fast.md` (utility origin), `sync/regular.md`

---

## Quality Fixes (G2 sprint — 2026-06-22)

#### `[pending]` — S3-B: Remove dead null guards on `info.filePath` in SNAPSyncController
- **What:** Removed `if info.filePath != null then` guards at former lines 1286/1296 in `AccountRangeSyncComplete` handler. Both `contractStorageFile` and `uniqueCodeHashesFile` are `private val` fields in `AccountRangeCoordinator` initialized by `Files.createTempFile()` — they are never null. The response case classes use non-nullable `filePath: java.nio.file.Path`. Guards were dead code.
- **Fix:** Removed the `if` wrapping, made both `.foreach { info => ... }` bodies unconditional.
- **Scope:** `SNAPSyncController.scala` (2 sites, same handler)

---

#### `8cdf1290d` — S3-E: SNAP task types immutable (D2)
- **What:** Converted all `var` constructor fields to `val` in three `case class` task types: `AccountTask` (9 fields), `ByteCodeTask` (3 fields), `StorageTask` (4 fields). Updated 34 coordinator mutation sites to `.copy(...)` in `AccountRangeCoordinator`, `ByteCodeCoordinator`, and `StorageRangeCoordinator`. Updated `ByteCodeTaskSpec` to use `.copy()` for state-transition assertions. `HealingTask` (plain `class` with `var`) left unchanged — intentional.
- **Scope:** 6 production files, 1 test file
- **Verification:** `sbt compile-all` clean; 63/63 targeted tests pass (`*SNAPSyncController* *AccountRange* *ByteCode* *StorageRange*`)
- **Note:** Local iteration vars `var i` / `var carry` inside `StorageTask.incrementHash32` method body are correct as-is — not case class fields

---

#### `5eae34c21` — §8a-retro batch 4: coordinator/heal specs → ActorTestKit (135 tests)
- **What:** Migrated 15 test files from Pekko Classic `TestKit` to `ScalaTestWithActorTestKit`. Root cause fixed: `HealingTrieFixtures.coordinatorProps` returned `Props` via `PropsAdapter`; under `ActorTestKitGuardian`, stopping the bridge sent classic `StopChild` to the guardian (which only accepts `TestKitCommand`) → `ClassCastException` → whole-system shutdown cascade across all 14 specs. Fix: replaced `coordinatorProps(...): Props` with `spawnCoordinator(...)(implicit testKit: ActorTestKit): ActorRef[Command]` via `testKit.spawn`.
- **Specs migrated:** `HealingTrieFixtures` (shared fixture); `AccountRangeCoordinatorSpec`, `ByteCodeCoordinatorSpec`, `StorageRangeCoordinatorSpec`, `TrieNodeHealingCoordinatorSpec` (4 direct coordinator specs); `DecoupledHealObservabilitySpec`, `DecoupledHealSafetySpec`, `DecoupledHealServeRootSpec`, `ScopedVerificationObservabilitySpec`, `ScopedVerificationParitySpec`, `ScopedVerificationFallbackSpec`, `TrieNodeHealingScopedVerificationSpec`, `TrieNodeHealingScopeCaptureSpec`, `HealingFrontierResumeSpec`, `RebuildFrontierBfsMultiSeedSpec` (10 heal family specs)
- **Verification:** 135 tests, 0 failures; `sbt compile-all` clean
- **Workaround still in place:** `ScalaTestWithActorTestKit(ConfigFactory.load())` — proper fix is E5b (`application-test.conf`)
- **Deferred:** ~209 E165 `TestProbe()` sites (unnarrowed classic probes) → E5d; worker teardown audit → E5c
- **New pitfalls documented:** pekko-typed-api.md P14 (bare ctor config), P15 (`testKit.stop` no-op for classic workers)
- **Cross-refs:** `node/testing-infra.md` (E5b/E5c/E5d)

---

## Classic Interop — §8k-A (COMPLETE)

#### `b4453d117` — refactor(8k-C): typed snapSyncController ref in SNAP coordinators
- **What:** 4 coordinators (`AccountRangeCoordinator`, `ByteCodeCoordinator`, `StorageRangeCoordinator`, `TrieNodeHealingCoordinator`) — `snapSyncController: ActorRef` param lifted to `ActorRef[SNAPSyncController.Command]`. `SNAPSyncController`: 7 coordinator spawn sites `ctx.self.toClassic` → `ctx.self`.
- **Scope:** 4 coordinator files + SNAPSyncController + 15 test specs (20 files total)
- **Sites eliminated:** 7 `.toClassic` from SSC coordinator spawn sites (Cluster F SSC→coordinator). Remaining 3 in SSC (lines 524, 531, 549) are intentional message adapter bridges, not spawn sites.
- **`.toClassic` count in SSC:** 7 → 3

---

#### `791c0211f` — refactor(8k-A): typed coordinator ref in all 4 SNAP workers; docs `4c333b178`
- **What:** `AccountRangeWorker`, `ByteCodeWorker`, `StorageRangeWorker`, `TrieNodeHealingWorker` — `coordinator: org.apache.pekko.actor.ActorRef` param lifted to `ActorRef[<Coordinator>.Command]`. Corresponding `ctx.self.toClassic` / `context.self.toClassic` at coordinator spawn sites in each coordinator + `SNAPSyncController` removed (→ `ctx.self`).
- **Scope:** 6 production files (4 workers + 2 coordinator spawn-site files), 4 test files
- **Sites eliminated:** ~12 `.toClassic` from Cluster F (coordinator→worker spawn path)
- **Verification:** 142 targeted tests pass; `sbt compile-all` clean; `scalafmtAll` applied

---

## Per-Child Typed Adapter — §8k-G3-SSC (2026-06-24)

#### `79068ad11` — §8k-G3-SSC: type SNAPSyncController.syncController via SyncControllerReply marker trait
- **What:** SSC's `syncController` constructor param typed from `TypedActorRef[Any]` → `TypedActorRef[SyncProtocol.SyncControllerReply]`. Added `trait SyncControllerReply` (unsealed — needed cross-file) to `SyncProtocol.scala`; `HealingImpossible` now extends it. Six SSC companion types extend `SyncProtocol.SyncControllerReply`. Both constructor sites (apply factory + Impl class) updated.
- **Files:** `SNAPSyncController.scala`, `SyncProtocol.scala`, `SyncController.scala`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8k-G3-SSC`, `modernization-log/sync/controller.md §8k-G3-SSC`

---

## ETH69 Pivot Safety — §ETH69-A + §ETH69-B (FORGE)

#### `12d2ede7e` — fix(sync): ETH69 pivot TD consensus gate in collectVoters (G1)
- **What:** `PivotBlockSelector.collectVoters` now filters the peer pool by a TD floor of `ourBestTD × 0.8`. Added `ourBestTotalDifficulty: () => BigInt` closure to `FastSync` (reads `blockchainReader → getChainWeightByHash → totalDifficulty`; returns 0 when unavailable so gate is inert on cold start). Gate threaded into all 3 `PivotBlockSelector` spawn sites in `FastSync`. If no peer passes the gate, logs `ETH69_PIVOT_TD_GATE_EMPTY` and falls back to block-number-only ranking for liveness.
- **Tests:** 4 new tests in `PivotBlockSelectorSpec` (17 total): low-TD excluded, high-TD included, K-sybil honest peer wins, liveness fallback triggers.
- **Files:** `PivotBlockSelector.scala`, `FastSync.scala`, `PivotBlockSelectorSpec.scala`
- **Cross-refs:** `completed/SPRINT-QUEUE.md §ETH69-A`

#### `0092e5f03` — fix(sync): ETH69 pivot parent-chain backlink validation before SNAP bootstrap (G5)
- **What:** Added `verifyingBacklink` state to `PivotBlockSelector`. After pivot election, sends `GetBlockHeaders(Right(pivot.hash), count=20, reverse=true)` to pivot-voting peers. `checkBacklink` validates: chain rooted at pivot, per-header PoW via `validateHeaderPoW` closure, `parentHash` continuity, canonical match via `getCanonicalHeaderByNumber` within 20 hops. Canonical match found → `Result` to FastSync. No match → log `ETH69_PIVOT_BACKLINK_FAIL` + deepen-retry. Forged-PoW peers blacklisted. `SNAPSyncController` not modified — validation belongs upstream of `Result` emission.
- **Tests:** 22 tests in `PivotBlockSelectorSpec` (5 new G5 scenarios): canonical within 5 hops, canonical at exactly hop N, no match → retry, invalid PoW → blacklist, probe timeout → retry.
- **Files:** `PivotBlockSelector.scala`, `FastSync.scala`, `PivotBlockSelectorSpec.scala`, `FastSyncSpec.scala`
- **Cross-refs:** `completed/SPRINT-QUEUE.md §ETH69-B`, `.local/Wire-Protocol-Modernization/G5-pivot-backlink.md`

---

## ETH/Sepolia SNAP Sync ETH Path Audit — §ETH-T9-A/B/C/D (2026-06-25, ALL COMPLETE)

#### `4ac7e2842` — fix(eth): validate pivot header against PostMergeBlockHeaderValidator before SNAP commit (§ETH-T9-A)
- **What:** `isPostMergeChain` gate added at both SNAP pivot storage paths in `SNAPSyncController`: the `BootstrapComplete` handler and `completePivotRefreshWithStateRoot`. On ETH/Sepolia, calls `PostMergeBlockHeaderValidator.validateHeaderOnly(header)` before any `appStateStorage` write. Rejection in bootstrap calls `startSnapSync()`; rejection in pivot refresh returns early. ETC (`isPostMergeChain = false`) skips the gate entirely.
- **Files:** `SNAPSyncController.scala`, `SNAPSyncControllerSpec.scala` (4 new tests)
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T9-A`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9

#### `4ac7e2842` — fix(eth): validateFieldCount — gate BlockHeader RLP field-count on fork timestamp (§ETH-T9-B)
- **What:** Added `BlockHeader.validateFieldCount(header, config)` to `BlockHeader` companion object. Chained at both SNAP pivot acceptance sites before `PostMergeBlockHeaderValidator` (bootstrap + pivot refresh). Also called in `SyncBlocksValidator.validateHeaderOnly` for the fast sync header path. ETC short-circuits immediately (`networkType != ETH`).
- **Files:** `domain/BlockHeader.scala`, `sync/snap/SNAPSyncController.scala` (2 sites), `sync/fast/SyncBlocksValidator.scala`, new `domain/BlockHeaderFieldCountSpec.scala` (6 tests)
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T9-B`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9

#### N/A — §ETH-T9-C: StorageScheme routing in SNAP coordinators — FALSE POSITIVE (2026-06-25)
- **What:** Thread 9 Explore audit flagged three SNAP coordinators as potentially missing `storageScheme` dispatch. Full file reads confirm all three are correctly wired: `AccountRangeCoordinator.getOrCreateTaskStackTrie:1570`, `StorageRangeCoordinator.getOrCreateAccountTrie:490`, `TrieNodeHealingCoordinator.processActiveResponse:1373`. Each has an explicit `storageScheme match { case Hash => ...; case Path => ... }` dispatch. No code change.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T9-C`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9

#### `f74158a3c` — fix(config): assert storageScheme matches chain type at SNAPSyncController startup (§ETH-T9-D)
- **What:** `SyncController.loadSnapSyncConfig()` now validates `storageScheme` against `blockchainConfig.networkType` via `require()`. ETH → `StorageScheme.Path`; ETC → `StorageScheme.Hash`. Assertion fires before any actor is spawned; all three SNAP startup paths in `SyncController` call `loadSnapSyncConfig()`. Two new imports added (`StorageScheme`, `NetworkType`). Complements the existing `SNAPSyncControllerImpl.checkStorageSchemeMismatch()` (DB-state vs config) with an earlier config vs chain-type gate.
- **Files:** `blockchain/sync/SyncController.scala`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T9-D`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9

---

## Open / Deferred

- INFO-8: `refreshFreshRootCache` function no longer exists in SNAPSyncController (searched 2026-06-22, 0 results). `getBlockHeaderByNumber` has 7 scattered call sites, none in a tight loop. No run-logs available. Marking MONITORED — no action needed.
- 36 `return` statements in SNAPSyncController (§8e ratchet — up from 33 at last audit; 3 new returns added since previous count)
- Wave 3 SNAP servo is the primary next network migration sprint target
- E5b: create `application-test.conf` (bare ctor fix — DEFERRED-BACKLOG Part 8)
- E5c: worker teardown leak audit (DEFERRED-BACKLOG Part 8)
- E5d: E165 TestProbe narrowing ~209 sites (DEFERRED-BACKLOG Part 8 — after E5b)
