# node/testing-infra — Test Infrastructure

**Package:** `src/test/` utilities, spec base classes
**Gate:** None
**Key files:** TestKit migration utilities, `ActorTestKit`, spec base traits

---

## TestKit Migration (DEFERRED-BACKLOG 8a, Batches 1-2)

Migrating from `TestActorRef` / `TestKit` (Classic) to `ActorTestKit` (Typed).

- **Batch 1:** Core actor specs migrated to `ActorTestKit`
- **Batch 2:** Sync-subsystem specs migrated
- **Batch 3:** Pending — DEFERRED-BACKLOG §8a open

---

## ETH Test Coverage (DEFERRED-BACKLOG Part 9, G1-G5)

All 5 ETH coverage gaps closed:

#### `583aded58` — G1: ETH engine API integration baseline tests
- **What:** Initial ETH/Sepolia happy-path spec

#### `dbef878` — G2: ETH fork-transition tests
- **What:** Timestamp-based fork dispatch coverage (Sepolia forks)

#### `00166a555` — G2-R: G2 retroactive fixes
- **What:** Edge cases from G2 spec cleaned up

#### `ef5ad3376` — G3: blob transaction (EIP-4844) handling tests
- **What:** Blob tx RLP, type-3 transaction validation coverage

#### `f4746250e` — G4: withdrawal handling tests
- **What:** EIP-4895 withdrawal processing coverage

#### `12a79b7c3` — G5: multi-chain parity tests
- **What:** ETC vs ETH isolation — tests confirm chain paths don't cross-contaminate

---

## EIP-2935 Account-Existence Fix

#### `bbc5f1df8` — Account-existence check gap
- **What:** `BlockExecution.applyEip2935` missing account-existence guard; added
- **Note:** Fix is tested; FORGE + BEACON review required before Olympia activation

---

## Scala 3 Idioms

#### `b305ef41b` — 3d: SealEngineType → enum
- **What:** `sealed trait SealEngineType` + 2 plain `object` singletons in `testmode/SealEngineType.scala` → `enum SealEngineType` with `case NoProof` / `case NoReward`
- **File:** `testmode/SealEngineType.scala`
- **Call sites unchanged:** `SealEngineType.NoProof`, `SealEngineType.NoReward` — same qualified paths

---

## expectMsgType[Any] → concrete type (E165 batch 1)

#### `8cdf1290d` — Narrow 20 expectMsgType[Any] calls in 4 SNAP sync specs
- **What:** `expectMsgType[Any]` → `expectMsgType[ConcreteType]` in TrieNodeHealingCoordinatorSpec (6),
  ByteCodeCoordinatorSpec (4), AccountRangeCoordinatorSpec (4), StorageRangeCoordinatorSpec (6)
- **Types used:** `HealingStatistics`, `ByteCodeCoordinator.ByteCodeProgress`,
  `NetworkPeerManagerActor.SendMessage`, `StorageRangeCoordinator.SyncStatistics`
- **Files changed:** 4 test specs; 104 tests pass; compile clean
- **Blocker documented:** Classic Pekko `TestProbe` (`org.apache.pekko.testkit.TestProbe`)
  has no type parameter — `TestProbe[T]()` syntax is invalid for these files. The P4
  fix strategy's `TestProbe[T]()` is only valid for Typed TestProbe
  (`org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[T]`). The 777-site grep
  metric tracks Classic TestProbe declarations without brackets and cannot be reduced
  without migrating to Typed TestKit infrastructure. See PENDING.md and DEFERRED-BACKLOG P4.

---

## Pekko TestKit Migration — Batch 5 (multi-system + TestActorRef specs)

#### `5ff14017b` — §8a-retro batch 5: 2 migrated, 8 deferred to Wave 3 (testEssential 3,595/0, 665s)

**Migrated (2):**
- `BlockFetcherSpec` (`regular/`) — extended `ScalaTestWithActorTestKit(ConfigFactory.load())`; Classic `TestProbe`s via `classicSystem`; `testKit.stop(blockFetcher)` per-test; `classicSystem.scheduler` for `scheduleOnce`.
- `PendingTransactionsManagerSpec` (`transactions/`) — migrated; dropped `NormalPatience` (conflicts with `ScalaTestWithActorTestKitBase.patience`); implicit `classicSystem` + `typedScheduler` wired from `testKit`.

**Deferred to Wave 3 (8) — deferred comments added in file headers:**
- `RegularSyncSpec` — `Resource[IO, ActorSystem]` lifecycle is load-bearing; migrate when `RegularSync` itself is Typed. See `sync/regular.md`.
- `PeerActorSpec`, `PeerActorHandshakingSpec` — `TestActorRef` is Classic-only; migrate when `PeerActor` is Typed (Wave 3 network sprint). See `network/peers.md`.
- `RLPxConnectionHandlerSpec` — Classic parent-injection (`TestActorRef(Props, parent.ref)`); Wave 3 gate.
- `CalibratePivotTDSpec` — `TestActorRef`-based lifecycle; Wave 3 gate.
- `ChainWeightCalibrationSpec` — `TestActorRef`-based lifecycle; Wave 3 gate. Classic message types (`GetHandshakedPeers`, `CalibrateChainWeightNow`) replaced with Typed (`GetHandshakedPeersCmd`, `CalibrateChainWeightNowCmd`) in `3c4b15543` (§8a-E6b); timing failures resolved. Full `ManualTime` migration remains Wave 3.
- `SyncControllerSpec` — deferred comment added; migrate when full sync actor hierarchy is Typed. See `sync/controller.md`.
- `WithActorSystemShutDown.scala` — left in place; still referenced by the 8 deferred specs.

---

## application-test.conf — E5b (COMPLETE)

#### `8b9bef67d` — test(infra): application-test.conf created; ConfigFactory.load() stripped from 25 specs
- **What:** Created `src/test/resources/application-test.conf` (`include "application.conf"` + `throughput=1`). Bare `ScalaTestWithActorTestKit()` ctor now loads `sync-dispatcher` and all custom dispatchers without a `ConfigFactory.load()` workaround. Verified: `SNAPRequestTrackerSpec` passes 16/16 with bare ctor, no `ConfigurationException`.
- **Cleanup:** Stripped `ScalaTestWithActorTestKit(ConfigFactory.load())` → `ScalaTestWithActorTestKit()` across 25 affected sync/snap specs. `PivotBlockSelectorSpec` (uses `ConfigFactory.load("explicit-scheduler")`) left unchanged.
- **pekko-typed-api.md P14:** Bare ctor pitfall marked resolved.
- **E5c, E5d** remain open.

---

## Pekko TestKit Migration — Batch 4 (SNAP coordinator/heal specs)

#### `5eae34c21` — §8a-retro batch 4: coordinator/heal specs → ActorTestKit (135 tests)
- **Root cause fixed:** `HealingTrieFixtures.coordinatorProps` returned `Props` via `PropsAdapter`. Under `ActorTestKitGuardian`, stopping the bridge sent classic `StopChild` to the guardian (which only accepts `TestKitCommand`) → `ClassCastException` → whole-system shutdown cascade across all 14 specs. Fix: replaced `coordinatorProps(...): Props` with `spawnCoordinator(...)(implicit testKit: ActorTestKit): ActorRef[Command]` via `testKit.spawn`.
- **Specs migrated:** `HealingTrieFixtures` (shared fixture) + 4 direct coordinator specs + 10 heal family specs (15 files total)
- **Verification:** 135 tests, 0 failures; `sbt compile-all` clean
- **Workaround still in place:** `ScalaTestWithActorTestKit(ConfigFactory.load())` — proper fix is E5b below
- **New pitfalls documented in pekko-typed-api.md:** P14 (bare `ScalaTestWithActorTestKit()` ctor doesn't load `application.conf`), P15 (`testKit.stop` is a no-op for classic workers spawned via `PropsAdapter`)
- **Cross-refs:** `sync/snap.md` (5eae34c21 entry)

---

## Testing Infrastructure — Deferred Items (E5b / E5c / E5d)

#### E5b — §8a-infra: `application-test.conf` (DEFERRED-BACKLOG Part 8)
- **Problem:** `ScalaTestWithActorTestKit()` bare ctor does not load `application.conf`; custom dispatchers like `sync-dispatcher` throw `ConfigurationException` at runtime. Current workaround: `ConfigFactory.load()` passed explicitly to every test class.
- **Fix:** Create `src/test/resources/application-test.conf`:
  ```hocon
  include "application.conf"
  pekko.actor.default-dispatcher.throughput = 1
  ```
  Then remove `ConfigFactory.load()` from all migrated test classes.
- **Status:** DEFERRED — parallel-safe, no prerequisite (but do before E5d)

#### E5c — §8a-infra-b: worker teardown leak audit (DEFERRED-BACKLOG Part 8)
- **Status:** ✅ DONE 2026-06-23 — EYE audit (`722576ef4`) found **no leaks**. All coordinator workers are Typed `context.spawnAnonymous` children (not classic `context.actorOf`); stopped automatically by Pekko actor hierarchy when coordinator stops. `classicSystem.stop(workerRef)` in `ByteCodeCoordinatorSpec` is intentional mid-test scenario simulation, not a leak mitigation. 150/150 coordinator tests ×2 JVM runs confirmed no cross-test isolation issues.
- **New deferred (E5e):** `actorSelection`-based worker-ref pattern in `ByteCodeCoordinatorSpec` + `AccountRangeCoordinatorSpec` — cosmetic; replace with Typed `TestProbe` injection (DEFERRED-BACKLOG §8a-infra-c; after E5d)

#### E5d — §8a-retro batch 4b: TestProbe narrowing (COMPLETE)

#### `a193bc794` — test(8a-retro): narrow TestProbe[M] in 14 coordinator/heal specs — E165 cleared (batch 4b)
- **What:** All Classic `TestProbe()` sites in 14 SNAP coordinator/heal specs converted to typed `testKit.createTestProbe[M]()`. Transformations: `import org.apache.pekko.testkit.TestProbe` removed; `expectMsgType[T]` → `expectMessageType[T]`; `fishForMessage { => bool }` → `FishingOutcomes.complete / continueAndIgnore`; `expectTerminated` → `testKit.stop`; untyped `assertNoCompletion` loops → typed `fishForMessage`. Note: `FishingOutcomes` is in `org.apache.pekko.actor.testkit.typed.scaladsl` (not parent package) — corrected across 6 files.
- **E165 floor:** 92 → 65 unnarrowed sites (65 in non-coordinator files; scoped to future batches)
- **Result:** 141/141 tests pass; docs `76668d9fd`
- **Left unstaged:** `SyncControllerSpec.scala` — 5-line race fix from concurrent §P9-FRESHPIVOT thread; committed separately as `083f08836`

#### E5e — §8a-infra-c: `actorSelection` worker-ref cleanup (COMPLETE)

#### `5f28e8ae6` — test(8a-e5e): replace actorSelection worker-ref with Typed ActorRef in 2 coordinator specs
- **What:** `ByteCodeCoordinatorSpec` + `AccountRangeCoordinatorSpec` — `resolveWorkerChild()` helpers now return `ActorRef[WorkerMessage]` via `.toTyped[WorkerMessage]` instead of raw classic `ActorRef`. All `actorSelection(coordinator.path / "*") ! message` sends replaced with typed `resolveWorkerChild(coordinator) ! message`. `classicSystem.stop(ref.toClassic)` used for worker stop (preserves fire-and-forget; `testKit.stop()` timed out on real child actors and cascaded to corrupt testKit state for subsequent tests).
- **Note:** Full injection (factory param at coordinator construction) was not feasible without production code changes — coordinators expose no factory parameter. `.toTyped[]` on the resolved ref is the correct test-only fix.
- **Result:** 40/40 pass (21 ByteCodeCoordinatorSpec + 19 AccountRangeCoordinatorSpec); docs `b1b55ecb0`

---

## SyncTest Tag Rescue — P8 (COMPLETE)

#### `3aef474a9` — test(p8): SyncTest audit — rescue 40 tests to UnitTest; docs `537397983`
- **What:** Audited 8 files tagged `SyncTest` (excluded from all tiers in `build.sbt:85`). 40 tests rescused to `UnitTest`; 36 kept `SyncTest` (real wall-clock / multi-actor integration).
- **Rescued (40):** `RetryStrategySpec` (15 — pure backoff math), `PeersClientSpec` (7 — pure data-structure), `CacheBasedBlacklistSpec` (6 — fake clock via `FakeTicker.advance()`), `BlockchainHostActorSpec` (12 — hermetic TestProbe + in-memory blockchain)
- **Kept SyncTest (36):** `StateStorageActorSpec` (1 — `eventually` + NormalPatience), `StateSyncSpec` (5 — `expectMsg(20.seconds)`), `FastSyncSpec` (6 — IO fiber waits), `SyncControllerSpec` (~24 — LongPatience multi-actor)
- **testEssential count after rescue:** 3,539; recorded in `test-quality-log.md` (migrated from `fukuii-test-timing.md` in P11b)
- **Pre-existing failure logged:** `BlockchainHostActorSpec` "return Receipts for block hashes" — `Subscribe(...)` vs `SubscribeCmd(...)` tag-type mismatch; pre-dates P8; CHASE-QUEUE entry added

---

## Classic Interop Audit — §8k-R1 (COMPLETE, read-only)

**Research only — no commits.** PRISM produced `.local/docs/classic-interop-audit.md` (535 lines, 2026-06-23).

- **Census:** ~130 production bridge sites + 2 test `actorSelection`. Permanent floor: 4 TCP bridges. Eliminatable: ~126 production + 2 test.
- **14 clusters, 8 root-cause families.** Execution order: §8k-A → §8k-C → §8k-D → §8k-E → §8k-F → §8k-G → §8k-H → §8k-I → §8k-B (post-CAPSTONE TCP floor verification).
- **Working docs updated:** DEFERRED-BACKLOG (§8k root-cause table, §8k-A scope expanded to 4 SNAP workers, 6 new prompts §8k-C through §8k-I); SPRINT-QUEUE (bridge elimination sprint table); CHASE-QUEUE (audit completion).

---

## P11 — testStandard baseline + SlowTest promotions (COMPLETE)

#### `edfb69f35` — test(p11): 6 mislabelled SlowTests promoted to UnitTest
- **Baseline:** testStandard 961s (16m 1s), 3,579 tests
- **Promoted (6):** `MiningSpec` "have unique names" (17ms), "contain ethash" (0ms); `PoWMiningSpec` "use RestrictedPoWBlockGeneratorImpl…" (56ms), "start only one mocked miner…MockedPow" (56ms), "start only the normal miner…PoW" (50ms), "start only the normal miner…RestrictedPoW" (40ms)
- **Kept SlowTest (legitimately slow):** "use NoAdditionalPoWData…" (202ms), "not start a miner when miningEnabled=false" (425ms) — TestMiningNode initialization overhead
- **testStandard failures:** 2 — DnsDiscoverySpec (Mordor DNS returned 9 enodes vs threshold 10, network-flaky, not a code issue); BlockchainHostActorSpec (pre-existing Subscribe→SubscribeCmd; fixed by parallel agent `07e5d505f`)

## BHA-fix — BlockchainHostActorSpec Subscribe→SubscribeCmd (COMPLETE)

#### `07e5d505f` — spec fix: expectMsg(Subscribe(…)) → expectMsgType[SubscribeCmd].to; docs `7dfb91c6b`
- **What:** "return Receipts for block hashes" test in `BlockchainHostActorSpec` was matching Classic `Subscribe(classifier, ref)` but `BlockchainHostActor` sends Typed `SubscribeCmd(classifier, peerEventAdapter)`. Fixed: `expectMsgType[SubscribeCmd].to shouldBe classifier`. Import swapped: `Subscribe` → `SubscribeCmd`.
- **Result:** 12/12 tests pass; test-only change, no production source edits

---

## P12 — Tag taxonomy + build target architecture review (COMPLETE 2026-06-24)

#### `55361ea6f` — build(p12): add domain test targets + remove workaround exclusions
- **New `addCommandAlias` targets (build.sbt):** `testConsensus` (284 tests), `testRPC` (219), `testOlympia` (201), `testState` (63), `testSync` (84) — all ≥3 threshold.
- **`Tags.scala` cleanup:** 15 dead definitions removed — 12 fork-specific tags (Homestead through Spiral), 3 environment tags (MainNet/PrivNet/PrivNetNoMining), FastTest. StressTest + ManualTest marked "reserved for future use."
- **Workaround exclusions removed from all tiers:**
  - Global `(Test/testOptions)`: `-l FlakyTest` + `-l DisabledTest` removed (0 remaining tests carry these tags post-P9/P10)
  - `testEssential`: `-l SlowTest -l IntegrationTest` only (SyncTest/Disabled/Flaky exclusions gone)
  - `testStandard`: `-l BenchmarkTest -l EthereumTest` only
  - `testComprehensive`: no exclusions
- **`-l SyncTest` removed everywhere** — all 84 SyncTest tests carry `(UnitTest, SyncTest)`; included in `testEssential` via `UnitTest`. Core sync logic now has CI coverage.
- **Files:** `build.sbt`, `src/test/.../testing/Tags.scala`

#### `deb421392` — docs(p12): CODEBASE-AUDIT E5 clearout
- `CODEBASE-AUDIT.md` E5 row struck through with `55361ea6f` SHA.
- `test-tag-taxonomy.md` written at `.local/docs/`.

---

## Open / Deferred

- **E165 `expectMsgType[Any]` — COMPLETE** (`8cdf1290d`) — 0 remaining. §8a-gated remainder:
  - 777 Classic `TestProbe` without `[T]` (requires ActorTestKit migration)
  - 20 `fishForMessage` PF[Any,Boolean] sites in 11 files (replace with `expectMessageType[T]` post-§8a)
  - Both in intentional 333 E165 floor; see DEFERRED-BACKLOG §8a research prompt.
- ~~**Wall-clock assertions** — 3 known test files; S5 sweep (CODEBASE-AUDIT) not yet run~~ — ✅ DONE 2026-06-22 — S5 EYE sweep confirmed walls in known files only, no new ones discovered
- ~~**TestKit Batch 5**~~ — ✅ DONE 2026-06-23 (`5ff14017b`) — 2 migrated (`BlockFetcherSpec`, `PendingTransactionsManagerSpec`); 8 deferred to Wave 3 network sprint (see DEFERRED-BACKLOG §8a "Remaining (blocked)"). `WithActorSystemShutDown.scala` stays until last deferred spec migrates (tracked in CHASE-QUEUE).
- ~~**E5b** — `application-test.conf` infra fix~~ — ✅ DONE `8b9bef67d` — see "application-test.conf — E5b (COMPLETE)" section above
- ~~**E5c** — worker teardown leak audit~~ — ✅ DONE 2026-06-23 (`722576ef4`) — no leaks found; see above
- ~~**E5d** — TestProbe narrowing~~ — ✅ DONE 2026-06-23 (`a193bc794`) — 141/141; E165 floor 92→65
- ~~**E5e** — `actorSelection` worker-ref cleanup~~ — ✅ DONE 2026-06-23 (`5f28e8ae6`) — 40/40
- `PeerRequestHandler` `ClassTag` unsound → `TypeTest[A,B]` — deferred (DEFERRED-BACKLOG Part 1 warnings)
- ~~**§8a-E6b** — `ChainWeightCalibrationSpec` Typed message rewrite~~ — ✅ DONE 2026-06-27 (`3c4b15543`) — see section below

---

## §8a-E6b — ChainWeightCalibrationSpec Typed message rewrite (COMPLETE 2026-06-27)

#### `3c4b15543` — fix(8a-E6b): ChainWeightCalibrationSpec — rewrite drain to Typed message types

- **Root cause:** Spec imported Classic `GetHandshakedPeers` + `CalibrateChainWeightNow`; SyncController sends only Typed `GetHandshakedPeersCmd` + `CalibrateChainWeightNowCmd`. `fishForMessage` throws on any message not in its partial function — all 18 tests failed.
- **Fix:** Dropped Classic imports; `fishForMessage` updated to `CalibrateChainWeightNowCmd` (done) and `_: GetHandshakedPeersCmd` (skip); all 10 `expectMsg(CalibrateChainWeightNow)` → `expectMsg(CalibrateChainWeightNowCmd)`.
- **Note on approach:** `ignoreMsg` (permanent probe filter) was rejected in favour of fixing the `fishForMessage` partial function — more targeted, more intentional, correct direction for Typed migration.
- **Result:** 18/18 pass.
- **Still deferred (Wave 3):** Full `ScalaTestWithActorTestKit` + `ManualTime` migration (replacing `ExplicitlyTriggeredScheduler`), alongside E6 (PeerActorSpec + RLPxConnectionHandlerSpec).
- Wave 3 deferred specs (7): `RegularSyncSpec`, `PeerActorSpec`, `PeerActorHandshakingSpec`, `RLPxConnectionHandlerSpec`, `CalibratePivotTDSpec`, `ChainWeightCalibrationSpec`, `SyncControllerSpec` — all gated on respective actor migrations in Wave 3 network sprint (DEFERRED-BACKLOG §8a "Remaining (blocked)"). Note: `ChainWeightCalibrationSpec` timing failures resolved in `3c4b15543` (§8a-E6b) — still deferred for `ManualTime` / `ScalaTestWithActorTestKit` migration.
