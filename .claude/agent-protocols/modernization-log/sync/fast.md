# sync/fast — Fast Sync

**Package:** `blockchain/sync/fast/`
**Gate:** None (sync infrastructure)
**Key files:** `FastSync.scala`, `SyncStateSchedulerActor.scala`, `FastSyncBranchResolverActor.scala`, `PivotBlockSelector.scala`

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-S4/S5/S6 commits — FastSync + SyncStateSchedulerActor + PivotBlockSelector Typed
- **What:** `extends Actor` + `def receive` → `Behaviors.receive`; sealed Command ADT; explicit `replyTo`
- **HERALD pre-flight:** HERALD-2 (FastSync peer-message path)
- **Cross-refs:** `sync/controller.md` (spawn wiring from SyncController), `network/peers.md` (NPMA message routing)

#### `e41f50b7d` — FastSync fully converted to Pekko Typed
- **Key changes:** `FastSyncState` sealed Command ADT; `SyncSession` inner class for post-init state

#### `be305095f` — PivotBlockSelector Typed (NPMA Phase 2 narrowing)
- **Cross-refs:** `network/peers.md` (ADT narrowing Ph2)

---

## ADT Narrowing: Behavior[Any] → Behavior[Command]

#### `948a25008` — Phase 1: Command traits sealed across S1/S4/S5/S6 actors
- **What:** `sealed trait Command` added to FastSync, SyncStateSchedulerActor, PivotBlockSelector, BlockImporter
- **Cross-refs:** `sync/regular.md`, `sync/controller.md`, `network/peers.md`

#### `04615ad43` — Phase 2: NPMA Command ADT consolidated (FastSync replyTo updated)
- **Cross-refs:** `network/peers.md` (primary actor for this commit)

#### `e41f50b7d` + `00cf1bed1` + `0d8adfd5c` — Phase 3: FastSync narrowing passes
- **What:** Message adapters retyped; `Behavior[Any]` → `Behavior[Command]` across all FastSync handlers

---

## LCA Recovery

#### `0d290019e` — RegularSyncBranchResolver reused for FastSync fork recovery
- **What:** `FastSyncBranchResolverActor` logic reused in `handleForkRecovery`; 46/46 targeted tests
- **Cross-refs:** `sync/regular.md` (primary home for LCA recovery)

---

## Quality Fixes (PRISM / CODEBASE-AUDIT)

#### `660451a19` — C1/C3/W8: SyncSession lifecycle + sys.exit replacement
- **What:** 13 null-init `private var` fields → `SyncSession` case class; `sys.exit(1)` → `FatalError` Command + `CoordinatedShutdown`
- **Source:** CODEBASE-AUDIT C1/C3/W8

#### `13aa7585e` — W5 comment + W11 dead ETH69.BlockRangeUpdate arms deleted
- **What:** By-design comment at `FastSync.scala:220`; both dead `ETH69.BlockRangeUpdate` inbound arms deleted from NPMA
- **Cross-refs:** `network/peers.md` (W11 dead arms)

#### `3c6be4512` — INFO-3/INFO-12: Scaladoc fix + emoji → ASCII
- **What:** `Behavior[Any]` → `Behavior[Command]` in factory method doc; worm emoji → ASCII

#### D4 / INFO-10 — Replace adapter-pinning tuple with `@annotation.unused`
- **What:** Removed `val _ = (pivotFailedAdapter, schedulerResponseAdapter, stateSyncStatsAdapter)` from inside `fastSyncClassicSelf`; simplified `fastSyncClassicSelf` to a single expression; annotated the three registration-only adapter vals with `@annotation.unused` (same idiom as SyncController.scala:301/304). Zero behavior change — the side-effect registrations still run at class init time.
- **Source:** CODEBASE-AUDIT D4 / INFO-10

#### W3-WormToBrainBar commits (`c37154287`, `cae6e0ab5`, `31c51a7cc`, `c8a1ddbfc`) — shared utility
- **What:** `WormToBrainBar.scala` utility extracted; emoji confined to 2 `val` definitions; FastSync/RegularSync/SNAPSync integrated
- **Cross-refs:** `sync/regular.md`, `sync/snap.md`

---

#### `0c7d6781b` — D3/W13 + W14: exception-as-control-flow + var accumulators eliminated
- **W13** (`expandTypedReceipts`, line ~607): `throw new RuntimeException` for empty `RLPValue` bytes → `Seq(v)` passthrough; `try/catch { RuntimeException|RLPException }` → `scala.util.Try(...).fold(...)`. `RLPException` removed from local import. Empty/malformed frames already fell back to passthrough; the throw was a latent actor-crash path.
- **W14 site 1** (`drainOrderedHeaders`, ~1702): `var nextBehavior + var continue + while` → `@tailrec def drain(last)`. Last-write-wins semantics preserved.
- **W14 site 2** (`processSyncing`, ~1431): `var nextBehavior` set in `session.foreach` → `session.flatMap { ... }.getOrElse(Behaviors.same)` val. Side effects execute in same order.
- **Source:** CODEBASE-AUDIT D3

---

## FlakyTest Audit — FastSync (Part 11 P10)

#### `ab98f1370` — P10: FastSyncSpec FlakyTests de-tagged (4 tests, Part 11 P10)
- **Tests fixed (4, now UnitTest + SyncTest):** The 4 FastSyncSpec FlakyTests were race conditions caused by non-deterministic actor startup / message ordering. Fixed by adding `awaitAssert` / `expectMsgAllOf` / deterministic actor-message ordering. `FlakyTest` tag removed from all 4.
- **Verification:** 10/10 passes per test via repeated `testOnly *FastSyncSpec*` runs
- **Cross-refs:** `sync/controller.md` (SyncControllerSpec FlakyTests, production FastSync.Done guard)

---

## §P9-NOTCHANGE — SyncControllerSpec:243 "not change best block" (Part 15)

#### `37037a89b` — test: re-enable "not change best block" via Typed FastSync injection path
- **What:** Test was injecting `PeerRequestHandler.ResponseReceived` via a classic `ActorRef` to the Typed FastSync actor, bypassing its private `prhResultAdapter`. After migration, FastSync only accepts `PeerRequestHandler.Result` via `WrappedPrhResult` (the private adapter wrapper). Fix: access via `WrappedPrhResult` (package-private `private[sync]`) + `fast.toTyped[FastSync.Command]` to convert the ref. The test now exercises the real production injection path.
- **Result:** 1/1 pass; no regressions in `testOnly *SyncControllerSpec*`
- **Cross-refs:** `sync/controller.md` (§P9-NOTCHANGE deferred entry cleared)

#### `5a12c7f09` — test: cancel scheduleAtFixedRate Cancellable after `eventually` blocks
- **What:** The `Runnable`-based `scheduleAtFixedRate` from `37037a89b` continued firing after the `eventually` blocks completed, occasionally injecting `WrappedPrhResult` into the actor system during teardown of the subsequent test — triggering a `RejectedExecutionException` against the terminating dispatcher. Fix: store the `Cancellable` returned by `scheduleAtFixedRate` in a `val`, then cancel it inside a `try/finally` block wrapping both `eventually` assertions. Guarantees the injection loop stops before actor teardown.
- **Source:** F11 continuation (P9-NOTCHANGE thread)

---

## Open / Deferred

- E165 TestProbe in `FastSyncBranchResolverSpec` — 5 pre-existing warnings, deferred (PENDING.md)
- ~~INFO-10: fragile adapter-pinning tuple `FastSync.scala:180–183`~~ — ✅ DONE 2026-06-22 (D4)
- ~~§P9-NOTCHANGE: SyncControllerSpec:243~~ — ✅ DONE 2026-06-23 (`37037a89b` + `5a12c7f09`)

---

## §ETH69-A — TD Consensus Gate in PivotBlockSelector (2026-06-24)

#### `12d2ede7e` — fix(sync): ETH69 pivot TD consensus gate in collectVoters — exclude low-TD peers (G1)
- **Security fix (P0):** `collectVoters` previously sorted the snap-sync voter pool by `maxBlockNumber` only — zero `chainWeight` references. An attacker on a long low-difficulty fork could enter the pool via Tier3 TD estimation and win pivot election with K sybil peers, anchoring SNAP sync to an attacker-chosen state root.
- **Gate implementation:** `ourBestTotalDifficulty()` helper reads `blockchainReader.getBestBlock → getChainWeightByHash → totalDifficulty` (returns `BigInt(0)` when unavailable — gate inert on early sync). `minPeerTD = ourBestTD * 8 / 10` (80% floor covers Tier3 ±20% variance). Peer pool filtered by `chainWeight.totalDifficulty >= minPeerTD`.
- **Liveness fallback:** if no peers pass the gate, logs `ETH69_PIVOT_TD_GATE_EMPTY` and falls back to block-number-only ranking — sync never blocks indefinitely.
- **Wiring:** `ourBestTotalDifficulty: () => BigInt` threaded as constructor param; all 3 `PivotBlockSelector` spawn sites in `FastSync` updated.
- **Tests:** 4 new cases in `PivotBlockSelectorSpec` (17 total): low-TD excluded, high-TD included, K-sybil honest-wins, liveness fallback. 17/17 passed.
- **Spec:** `.local/Wire-Protocol-Modernization/G1-pivot-td-gate.md`

## §ETH69-B — Pivot Parent-Chain Backlink Validation (2026-06-24)

#### `0092e5f03` — fix(sync): ETH69 pivot parent-chain backlink validation before SNAP bootstrap (G5)
- **Security fix (P0 HIGH):** After G1 TD gate, a peer whose Tier3 TD estimate passes 80% threshold could still elect a pivot on a fork and anchor SNAP sync to a fabricated state root. `sendResponseAndCleanup` emitted `Result(pivotBlockHeader)` to FastSync with no PoW check and no parent-chain link to a known canonical ancestor.
- **New `verifyingBacklink` state:** after vote win, `PivotBlockSelector` sends `GetBlockHeaders(Right(pivot.hash), count=20, reverse=true)` to pivot-voting peers before emitting `Result`. N=20 chosen: deep enough to cover any plausible fork window within `pivotBlockOffset`, uses existing `obtainBlockHeaderFromPeer` request path.
- **`checkBacklink` validation:** chain rooted at pivot, per-header PoW via `validateHeaderOnly`, `parentHash` continuity, canonical match via `getCanonicalHeaderByNumber` within N hops. Match → `sendResponseAndCleanup` → `Result`; no match → log `ETH69_PIVOT_BACKLINK_FAIL` + deepen-retry; forged-PoW peers blacklisted immediately.
- **Failure mode:** reject pivot + deepen-retry (not disconnect-all). Reuses existing `scheduleRetry → idle → collectVoters` path; honest-but-divergent voters stay connected for liveness fallback.
- **Wiring:** `getCanonicalHeaderByNumber` and `validateHeaderPoW` closures added to `PivotBlockSelector` constructor from `FastSync` (where `blockchainConfig` is in scope); all 3 spawn sites updated. SNAPSyncController not modified — validation belongs upstream of `Result` emission.
- **Tests:** 5 new G5 scenarios in `PivotBlockSelectorSpec` (22 total): canonical within 5 hops → proceeds; canonical at hop N → proceeds; no canonical match → rejected + retry; invalid PoW → immediate reject + blacklist; probe timeout → retry. 22/22 passed.
- **Spec:** `.local/Wire-Protocol-Modernization/G5-pivot-backlink.md`

## §ETH69-C — BlockchainReader Tier3 Rolling-Median Difficulty (2026-06-24)

#### `2af49dcb1` — fix(sync): ETH69 Tier3 POW_SCALING — rolling-median difficulty reduces estimate variance (G2)
- **Problem:** Tier3 `POW_SCALING` used `rollingWindowDiff` (10K-block DB-lookup rolling average, fallback to head.difficulty) as the marginal TD rate. Under ETC flex-load oscillation (symmetric ±50% swing), a point-in-time sample could sit at the crest or trough, producing Tier3 estimates ±50% off the true TD. Archive nodes with inflated estimates are never corrected (monotonic guard + no NewBlock); peers mid-trough are deprioritized unfairly.
- **Fix:** Replaced `rollingWindowDiff` with a 1,000-block in-memory ring buffer (`difficultyRingBuffer: ArrayDeque[BigInt]`) and `rollingMedianDifficulty: Option[BigInt]` in `BlockchainReader`. For even-length sorted arrays, averaging the two middle elements equals the true mean of any symmetric bimodal oscillation — collapses Tier3 variance from ±50% to near-zero under sustained flex-on/flex-off cycling. Returns `None` until the buffer holds 1,000 entries (cold-start falls back to `head.difficulty`).
- **Hook points:** `BlockExecution.executeAndValidateBlocks()` (live import — after `saveBlockState`) and `ChainImporter.importChainFile()` (offline/hive — after `blockchainWriter.save`) both call `blockchainReader.recordBlockDifficulty(header.difficulty)`. Thread-safe via `synchronized` on the `BlockchainReader` intrinsic lock (safe for Pekko single-actor access).
- **Dead code removed:** `Tier3RollingWindow: BigInt` constant and `rollingWindowDiff` private method both deleted.
- **Tests:** 2 new cases in `ETH69OscillationChainWeightSpec` (15 total): (1) oldErr > 20%, newErr < 20% with anchorNum=100 (gap dominates); (2) median of {500×2 TH, 500×4 TH} = 3 TH exactly. 15/15 passed.
- **Files:** `BlockchainReader.scala`, `BlockExecution.scala`, `ChainImporter.scala`, `ETH69OscillationChainWeightSpec.scala`

## §8k-O — FastSync .toClassic Bridge Elimination (4/5 sites) (2026-06-25)

#### `fc5a3f8e7` — refactor(sync): §8k-O — FastSync .toClassic bridge elimination (4/5 sites)
- **PivotBlockSelector constructor narrowed:** `fastSync: ClassicActorRef` replaced with `replyTo: TypedActorRef[Result]` + `selectionFailedTo: TypedActorRef[SelectionFailed.type]`. Both send sites updated (`selectionFailedTo ! SelectionFailed`, `replyTo ! Result(pivot)`). All 3 FastSync spawn sites pass adapters directly — no `.toClassic` on spawn.
- **StateStorageActor ref narrowed:** `SyncSession.syncStateStorageActor` field type changed from `ActorRef` (Classic) to `TypedActorRef[StateStorageActor.Command]`. `.toClassic` on spawn and `toTyped[Nothing]` on `ctx.stop` both removed — `ctx.stop(s.syncStateStorageActor)` is now direct Typed.
- **`fastSyncClassicSelf` retained:** `SyncStateSchedulerActor` still has Classic constructor params; `fastSyncClassicSelf = pivotResultAdapter.toClassic` remains as the Classic reply target for it. Comment updated to explain the remaining dependency. Will be deleted when SyncStateSchedulerActor is migrated.
- **PivotBlockSelectorSpec migrated:** Classic `TestProbe fastSync` → two Pekko Typed probes (`fastSyncResult`, `fastSyncFailed`). `expectMsg` → `expectMessage`. `ScalaTestWithActorTestKit.createTestProbe[T]()` used. 22/22 pass.
- **Files:** `FastSync.scala`, `PivotBlockSelector.scala`, `PivotBlockSelectorSpec.scala`
