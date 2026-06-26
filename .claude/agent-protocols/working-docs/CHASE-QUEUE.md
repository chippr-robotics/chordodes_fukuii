# Chase Queue

**Public document — safe-for-public content only.**
This file is tracked in the public repo. Do NOT add internal dev commentary,
private decisions, sensitive architecture notes, or anything not suitable for
public viewing. Entries must be code-pattern observations only.

---

Cross-file work identified during inline sessions that was NOT chased at the time.
Agents append here when they spot an issue outside their current file scope.
Entries are batched into dedicated sprint sessions when a cluster forms.

**Review cadence:** At the start of each sprint, scan for clusters (5+ entries of
the same type or package) that warrant a focused session. Delete cleared entries
and add a dated log entry at the bottom.

---

## Entry format

```
| File | Line(s) | Pattern | Type | Agent | Date |
```

**Type codes:** `LOG` `WARN` `RETURN` `SENDER` `CLASSIC` `MUTABLE` `EXCEPT` `IMPLICIT` `ISINST` `DEAD` `NULL` `FORGE`

---

## Open entries

| File | Line(s) | Pattern | Type | Agent | Date |
|------|---------|---------|------|-------|------|
| `sync/SyncController.scala` | 309 | `ForkChoiceManager.setListener(externalAdapter.toClassic)`. GATED: FCM is a plain Scala class (not a Pekko actor) — `setListener` stores a Classic `ActorRef` in an `AtomicReference`. Needs FCM API redesign to accept `ActorRef[T]`. | CLASSIC | MITHRIL | 2026-06-23 |
| `sync/` — SyncTest exclusion audit (P8 result) | — | 36 tests remain tagged SyncTest (not rescued): StateStorageActorSpec×1 (eventually/real patience), StateSyncSpec×5 (expectMsg 20s), FastSyncSpec×6 SyncTest-tagged (IO.cede + real timeouts), SyncControllerSpec×24 (eventually + ExplicitlyTriggeredScheduler + real patience). Prerequisite to `-l SyncTest` removal: all 36 must be either (a) de-tagged if hermetic, (b) tagged FlakyTest if wall-clock, or (c) left in a dedicated sync integration tier. P10 handles FlakyTest overlap. Full `-l SyncTest` removal blocked on P10 completion. | EXCEPT | EYE | 2026-06-23 |
| `test/utils/WithActorSystemShutDown.scala` | — | Utility still referenced by 8 Wave-3-deferred specs (`RegularSyncSpec`, `PeerActorSpec`, `PeerActorHandshakingSpec`, `RLPxConnectionHandlerSpec`, `CalibratePivotTDSpec`, `ChainWeightCalibrationSpec`, `SyncControllerSpec` + 1 other). Delete when the last referring spec migrates to `ScalaTestWithActorTestKit` in Wave 3. `RegularSyncSpec` migration gate is now open (§8k-F done `b24515637`) — see DEFERRED-BACKLOG §9c. | DEAD | EYE | 2026-06-23 |
| `BlockchainHostActor.scala` (97, 302), `fast/PivotBlockSelector.scala` (415, 570), `regular/BlockBroadcast.scala` (64, 100, 108), `snap/actors/AccountRangeWorker.scala` (106), `snap/actors/StorageRangeCoordinator.scala` (1108), `snap/actors/ByteCodeWorker.scala` (82), `snap/actors/TrieNodeHealingCoordinator.scala` (1321), `transactions/PendingTransactionsManager.scala` (193, 400) | Legacy non-Cmd `NetworkPeerManagerActor.SendMessage(...)` sent on a Classic `ActorRef` to NPMA. NPMA is now a Typed `Behavior[Command]`; `SendMessage` does NOT extend `Command`. INVESTIGATE whether NPMA's Classic shell still handles `SendMessage` in production (NET2 shell forwards only Commands + 2 ask paths) — if not, these are runtime silent-drops (same bug class as §8k-B8 GOAL A). Each is fixed by switching to `SendMessageCmd` when its owning actor migrates to a Typed `networkPeerManager` param, or as a standalone GOAL-A-style sweep. | SENDER | LOOM | 2026-06-25 |

---

## Sprint clusters

When 5+ entries share a Type or package, open a dedicated sprint:

| Cluster | Threshold | Sprint agent |
|---------|-----------|-------------|
| `LOG` / `WARN` in a package | 5+ | WRAITH (~30-60 min) |
| `RETURN` | 5+ | MITHRIL (~1h) |
| `SENDER` / `CLASSIC` in a subsystem | 5+ | LOOM (~2-4h) |
| `IMPLICIT` | 10+ | MITHRIL Part 3a (full day) |
| `EXCEPT` | 3+ | PRISM review first |

---

## Cleared entries log

| PivotBlockSelector UnsubscribeAllCmd ×4 + UnsubscribeCmd ×1 | `blockchain/sync/fast/PivotBlockSelector.scala` | Cleared 2026-06-21: S4b (`e82f41cac`) fixed all 5 unsubscribe sites — `UnsubscribeAllCmd(ctx.self.toClassic)` → `UnsubscribeAllCmd(blockHeadersAdapter.toClassic)` at `sendResponseAndCleanup`, `ElectionPivotBlockTimeout`, `votingProcess`, `idle`; `UnsubscribeCmd` in `runningPivotBlockElection` likewise updated. Subscription ref mismatch eliminated. | — | LOOM | 2026-06-21 |

| EngineApiService H1 MUTABLE | `consensus/engine/EngineApiService.scala:42–75` | Cleared 2026-06-21: BEACON review complete. 6 maps (PRISM missed `acceptedChildrenByParent` line 74). CLASS A (4 payloadId-keyed) → bounded LRU+TTL, CONDUIT owner (§8c-H1-A). CLASS B (2 hash-keyed) → finalized-watermark prune, BEACON impl (§8c-H1-B). No source files touched. | — | — | 2026-06-21 |
| IMPLICIT ×3 / P4a EXCEPT | various | Cleared 2026-06-21: (1) IMPLICIT ×3 — `*Enc extends MessageSerializableImplicit`/`RLPSerializable` subtype polymorphism + `ReceiptBloom*` wildcard collision — permanent deferrals (P3b); no fix planned. (2) P4a SSC `GetProgress` idle-state gap — fixed `74db726d1`. | — | — | 2026-06-21 |
| NPMA CLASSIC (NET-01) | `NetworkPeerManagerActor.scala:165,451,663` | Cleared 2026-06-21: HERALD verified by-design — `classicSystem.scheduler` is correct interop bridge for Classic `AddToBlacklistCmd.replyTo`; same `HashedWheelTimer` backing. Logged as NET-01 in DEFERRED-BACKLOG Part 2 NET group for LOOM cleanup when PMA migrates. | — | — | 2026-06-21 |
| `BlockExecution.applyEip2935` account-existence gap | `blockchain/ledger/BlockExecution.scala` | Cleared 2026-06-21: FORGE verdict — fix required before Olympia activation. `applyEip2935` else-branch writes storage unconditionally with no account-existence guard (unlike `applyEip4788`). Not a production sequential-execution risk but a Hive EIP-2935 compliance blocker and test-construction trap (`emptyWorld` + post-activation block → `IllegalStateException` in `getGuaranteedAccount`). Fix: drop `isActivationBlock &&` from `w1` branch condition; add `BlockHashHistorySpec` absent-account test. Full spec in DEFERRED-BACKLOG §G5 note. Owner: BEACON + FORGE review before commit. | — | FORGE | 2026-06-21 |
| PoWMining.scala MUTABLE (@volatile redundancy) | `consensus/pow/PoWMining.scala` — `minerCoordinatorRef`, `mockedMinerRef`, `minerSystem` | Cleared 2026-06-21: `551e9bf31` — removed `@volatile` from all 3 field declarations. `mutex.synchronized` already provides happens-before; removal is cosmetic, no logic change. scalafmtAll clean. Compile blocked by pre-existing `BytecodeRecoveryActor.scala:202` (unrelated); `testOnly *PoWMining* *Mining*` pending that fix. 1 file, 3 lines changed. | — | MITHRIL | 2026-06-21 |
| PoWMining.scala MUTABLE (startMiningProcess) | `consensus/pow/PoWMining.scala:103–132` | Cleared 2026-06-21: FORGE assessment — **SAFE AS-IS, no fix required.** `mutex.synchronized` wraps the entire compound check-then-act (both isEmpty guards and the write), so no race is possible. `startMiningProcess` is private and called exactly once sequentially at boot via `StdNode.start()`. A double-spawn would cause duplicate mining work only — no consensus violation (Ethash verification, ECIP-1017 rewards, state root, fork dispatch all unaffected). `AtomicBoolean` would be redundant. `@volatile` on the three fields is redundant given the synchronized block (cosmetic only — safe to remove in a MITHRIL pass). MITHRIL correctly flagged the pattern but the guard was already correct. | — | FORGE | 2026-06-21 |
| RegularSync divergence path EXCEPT | `BlockImporter.scala:797` `handleForkRecovery` | Cleared 2026-06-21: HERALD audit confirmed gap — three-path recovery (stale-tip rewind / UnknownBranch rewind / handleForkRecovery) all use blind 128-block rewind with no LCA knowledge. Will eventually converge (iterates 128 blocks per ForkDetectThreshold cycle) but destructively mutates canonical chain during recovery. MESS makes >128-block forks near-impossible on ETC mainnet. Fix spec: generalize `FastSyncBranchResolverActor.fastSync: ClassicActorRef` → `replyTo: ActorRef[BranchResolverResponse]`, add `ResolvingFork` behavior to `BlockImporterLogic`, replace 4-line blind rewind with actor spawn + response. Size S. Routed to DEFERRED-BACKLOG. | — | — | 2026-06-21 |
| RegularSyncCommand sealed | `SyncProtocol.scala` + `RegularSync.scala` | Cleared 2026-06-22: `923b18ba7` — `FetcherStatusTick`, `PrintStatusTick`, `ProgressProtocol` moved from `RegularSync.scala` → `SyncProtocol.scala`; `trait RegularSyncCommand` is now `sealed`; fallthrough `case _ => Behaviors.unhandled` arm deleted; `RegularSync.ProgressProtocol` type alias preserves all call sites; 31/31 `RegularSyncSpec` tests pass; 0 compile errors. | — | MITHRIL | 2026-06-22 |
| SyncControllerSpec SyncStateAutoPilot | `SyncControllerSpec.scala` | Cleared 2026-06-22: `fc1030410` — GetHandshakedPeersCmd handler added to autopilot; 7 pre-existing failures resolved. |
| SyncController RegisterSnapSyncController GATED (2026-06-23) | `blockchain/sync/SyncController.scala` | Cleared 2026-06-25: §8k-B8a (`3ee856a33`) — `RegisterSnapSyncController` → `RegisterSnapSyncControllerCmd` at 378/1667/2078/2090; `RegisterChainWeightCalibrationTarget(externalAdapter.toClassic)` at 309 confirmed distinct (ForkChoiceManager.setListener — still GATED; separate entry remains). Original GATED concern about dead-letter routing eliminated. |
| ETH/69 BlockRangeUpdate type confusion Part 14 §ETH-BRU | Cleared 2026-06-23: `931c615dd` — ETH69→ETHPackets in PeerActor:551 + BlockFetcher:486; BlockFetcherSpec rebuilt against ETHPackets type |
| MetricsAlreadyConfiguredError + LocalVM + AdaptiveSyncStrategy | Part 8f dead code | Cleared 2026-06-22: `fa57df9b9` — 3 confirmed dead files deleted. grep-verified 0 callers each; no test files existed; sbt compile-all 0 errors. |
| DeltaSpikeGauge + Metrics.deltaSpike() | Part 8f dead code | Cleared 2026-06-22: `c6b3da4cb` — unused spike metric, 0 call sites, pattern superseded by counter/gauge. |
| discovery/StaticNodesLoader.scala | Part 8f dead code | Cleared 2026-06-22: `ff2fc219c` — DiscoveryConfig redirected to network.StaticNodesLoader (stricter validation: full pubkey + port check vs prefix-only); duplicate deleted. |
| Branch-wide dead-code audit | scala3-cleanup-june all deletions | Cleared 2026-06-22: 19 files audited (19 Scala files across 5 commits), verdicts: 17 DELETE-CORRECT, 1 DEFER (AdaptiveSyncStrategy already in DEFERRED-BACKLOG 9a), 1 NOT-DELETED (DumpChainActor class removed; companion object constants retained). New DEFERRED-BACKLOG entries: none (9a was pre-existing). Opportunistic: Versions.scalapb dead constant → scoped immediately as C20. |
| `project/Versions.scala` — orphaned `val scalapb` | `project/Versions.scala` | Cleared 2026-06-22 (C20): `Versions.scalapb = "0.11.20"` sole consumer was `project/scalapb.sbt` (deleted in `a948fda1d` extvm cleanup). No other reference in `build.sbt`, `Dependencies.scala`, or any source. Entire `project/Versions.scala` deleted (only val). `sbt compile-all` clean. |
| FastSync NULL vars (P8-Item1) | `blockchain/sync/fast/FastSync.scala:343,363–364` | Cleared 2026-06-22: `0c7d6781b` (W13+W14) — `var syncState/syncStateStorageActor/syncStateScheduler = null` replaced with `Option[T]`/`None` during G1 var-accumulator sweep. Pre-fixed before P8 session. |
| FastSync EXCEPT expandTypedReceipts (P8-Item2) | `blockchain/sync/fast/FastSync.scala:571–591` | Cleared 2026-06-22: `0c7d6781b` (W13) — `throw new RuntimeException` + try/catch replaced with `scala.util.Try(...).fold(...)`. Pre-fixed before P8 session. |
| NPMA DEAD identical if/else arms (P8-Item3) | `network/NetworkPeerManagerActor.scala:325–327,689–692` | Cleared 2026-06-22: `504b4ca16` (NPMA W1/W2/W12/W16) — dead branches removed during NPMA dead-branch/command-seal pass; `usesRequestId` predicate confirmed pure. Pre-fixed before P8 session. |
| SyncController IMPLICIT EC.global (P8-Item4) | `blockchain/sync/SyncController.scala:15` | Cleared 2026-06-22: `a5132aa80` (C2) — `import scala.concurrent.ExecutionContext.Implicits.global` removed; `given ec: ExecutionContext = ctx.executionContext` added. Pre-fixed before P8 session. |
| §8k-R1 Classic bridge audit | `.local/docs/classic-interop-audit.md` | Cleared 2026-06-23: PRISM audit complete (535 lines). ~130 prod bridge sites + 2 test actorSelection. 14 clusters mapped to 8 root causes. 8 pre-CAPSTONE elimination sprints (§8k-A through §8k-I) drafted in DEFERRED-BACKLOG.md + SPRINT-QUEUE.md sprint sequence table. Permanent floor: 4 TCP bridges. |
| §8k-CQ1 dead shim removal | `network/KnownNodesManager.scala:113–117` + `src/it/.../CommonFakePeer.scala:150–167` | Cleared 2026-06-24: `d4cc7a7fa` — `case object GetKnownNodes` + Scaladoc + Classic bridge handler (`case GetKnownNodes => ...`) + stale `// Classic compat` comments + unused `implicit private val scheduler` + `implicit private val bridgeTimeout` vals removed. `GetKnownNodesReq(replyTo: ActorRef[KnownNodes])` Typed replacement (line 105) retained. 2 files changed, 22 lines deleted. `compile-all` green, 52/52 `KnownNodesManagerSpec` tests pass. |
| ETH/Sepolia Test Coverage Gaps (Thread 7, 2026-06-24) — 5 gaps | `vm/EvmConfig.scala`, `ledger/BlockExecution.scala`, `engine/EngineApiController.scala`, `network/PeerActor.scala` + `BlockFetcher.scala`, `network/ForkIdValidator` | Cleared 2026-06-25: Gap 1 → `ac0e25b62` (T7-A `EvmConfigTimestampForkSpec`); Gap 2 → `cb2e2aec1` (T7-B `Eip4788BeaconRootStorageSpec`); Gap 3 → `6e72ad2a0` (T7-C `EngineApiVersionRejectionSpec`); Gap 4 → `c7cc5d131` (T7-D `BlockRangeUpdateDecodePathSpec`); Gap 5 → `3d362be30` (T7-E `ForkIdSepoliaSpec`). All 5 resolved. |

---

## SNAP2 replyTo adapter dependency (S2 → SNAP2, 2026-06-21)

`FastSyncBranchResolverActor` now receives `replyTo: ActorRef[BranchResolverResponse]`. FastSync (`Behavior[Any]` core) currently passes `ctx.messageAdapter[BranchResolverResponse](identity)` as the replyTo and routes the response through its `Any` handler. When FastSync is narrowed to `Behavior[Command]` in the network/P2P sprint, that adapter and the replyTo pass will need replacing with a proper typed adapter or a direct Typed self-ref. Not a blocker now — the `identity` adapter is correct for `Behavior[Any]`. Flag at SNAP2 re-entry.

---

## E165 TestProbe pattern — test harness cleanup (S2 + S5, 2026-06-21/22)

**Corrected baseline (S5 EYE sweep 2026-06-22):** 777 unnarrowed `TestProbe` sites across 83 test files. Prior entry stated "5 in FastSyncBranchResolverSpec" — that file now has 0 unnarrowed sites (was cleaned in a prior session). The 777 sites are pervasive across SNAP coordinator and network test suites.

**Progress (§8a-retro batch 3, 2026-06-23):** 25 network/sync specs migrated to `ActorTestKit`; `PeerManagerSpec` (32 sites) migrated. Recount pending — run `grep -rn "org.apache.pekko.testkit.TestProbe\b" src/test/ --include="*.scala" | grep -v "\[" | wc -l` after batch 4 to update floor.

**Update (§8a-retro batch 4, 2026-06-23, 5eae34c21):** The four coordinator specs (`TrieNodeHealingCoordinatorSpec`, `ByteCodeCoordinatorSpec`, `AccountRangeCoordinatorSpec`, `StorageRangeCoordinatorSpec`) plus ten heal specs and the shared `HealingTrieFixtures` are now migrated to `ScalaTestWithActorTestKit` (135 tests, 0 failures). **However, their `TestProbe()` call sites remain classic/unnarrowed** (kept via `system.classicSystem`) — current counts TrieNodeHealing 59, ByteCode 57, AccountRange 54, StorageRange 39. The batch removed the `HealingTrieFixtures` PropsAdapter spawn crash (the blocker), but `TestProbe[M]` narrowing for E165 reduction is still pending and is now unblocked. Recount of import-level unnarrowed probes: 92 sites across the test tree (run `grep -rn "org.apache.pekko.testkit.TestProbe\b" src/test/ --include="*.scala" | grep -v "\[" | wc -l`).

Fix: narrow each `TestProbe` with a `[M]` type parameter matching the expected message type. Pattern selectors on `Any` from untyped probes produce E165 warnings in strict Scala 3. The coordinator/heal specs require the `HealingTrieFixtures` PropsAdapter fix (§8a-retro batch 4) before they can be migrated. Route remaining non-coordinator files to a dedicated test-harness cleanup sprint after batch 4.

**COMPLETE (§8a-retro batch 4b, 2026-06-23, a193bc794):** 14 coordinator/heal specs in `sync/snap/actors/` fully narrowed — all untyped `TestProbe()` replaced with `testKit.createTestProbe[M]()`, `awaitAssert` → `eventually`, `fishForMessage` ported to `FishingOutcomes.complete/continueAndIgnore`, death-probe pattern removed. 141 tests, 0 failures. Floor dropped from 92 → **65** unnarrowed sites. Remaining sites are in non-coordinator test files outside this batch's scope.

---

## wall-clock-assertion (protocol candidate)

Pattern: unit tests asserting real elapsed time (`elapsed should be < N.millis`).
Recurs in 3 files (`MerkleProofVerifierPhase3Spec`, `SnapServerLimitsSpec`, `WorkNotifierSpec`).
Fix: replace with op-count guard or `@SlowTest` tag. See `test-quality-audit.md §R1-b`.

---

## json4s Jackson 3 gate (R3 — 2026-06-20)

json4s 4.2.0-M5-SNAPSHOT has Jackson 3 (tools.jackson.core 3.2.0, new package namespace).
Stable release still 4.1.1 (Jackson 2). Gate opens when 4.2.0-M5 is tagged.
Check https://github.com/json4s/json4s/tags weekly.
When tagged: bump fukuii pin from 4.0.7 to 4.2.0-M5, unblocks DEFERRED-BACKLOG Part 4b (logstash chain).

---

## Test infrastructure traps

IntegrationTest tag conflict (discovered G2, 2026-06-21): Do not tag IntegrationTest-scoped tests with the IntegrationTest tag — commonSettings injects -l IntegrationTest as a global exclusion, causing zero tests to run. Tests in src/it/ are already in the IntegrationTest config by directory. Tag with the feature tag only (e.g. EthSmoke). Applies to any future sbt task scoped to IntegrationTest / testOptions.

---

## ETC mainnet SNAP peer capability — operational gate (fast sync, 2026-06-21)

`SyncStateSchedulerActor` has a correct ETH68+ fallback to `GetTrieNodes` (SNAP wire protocol) for state download when `GetNodeData` is unavailable. However, fast sync end-to-end correctness on ETC mainnet depends on peers actually advertising SNAP capability. If a node connects only to ETH68 non-SNAP peers it hits `NetworkIncompatible → FallbackToSnapSync` — correct behavior, but the fallback path has not been validated against real ETC mainnet peer stats. Scope: network/sync sprint when ETC mainnet SNAP testing is in scope. Not a blocker for `scala3-cleanup-june`.

---

## BlockExecution.applyEip2935 — account-existence gap (G5, 2026-06-21)

`BlockExecution.applyEip2935` writes to `HistoryStorageAddress` storage without first guaranteeing the account exists, unlike `applyEip4788` which creates the account if absent. Currently masked on real ETC by deployment order (account pre-exists at activation block). Latent: if activation ordering ever shifts or the account is absent in test conditions, the storage write silently fails or corrupts state. Route to FORGE for ETC consensus review before Olympia activation.

---

## Clearout Prompts

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| ~~B1~~ | ~~Batch B step 1~~ | ~~P1 MITHRIL Seal RegularSyncCommand~~ | ✅ DONE 2026-06-22 |
| ~~B2~~ | ~~Batch B step 2~~ | ~~P2 MITHRIL SyncControllerSpec autopilot~~ | ✅ DONE 2026-06-22 — 7 failures resolved |
| ~~B3~~ | ~~Batch B step 3~~ | ~~P3 WRAITH Delete 3 dead files~~ | ✅ DONE 2026-06-22 — 3 files deleted |
| ~~B4~~ | ~~Batch B step 4~~ | ~~P4 WRAITH DiscoveryConfig redirect + delete~~ | ✅ DONE 2026-06-22 — redirect + delete |
| ~~B5~~ | ~~Batch B step 5~~ | ~~P7 PRISM Retrospective dead-code audit (branch-wide)~~ | ✅ DONE 2026-06-22 — 19 files audited, 0 new deferred |
| ~~E2~~ | ~~Batch E~~ | ~~P8 — MITHRIL G1-sweep PRISM items (FastSync + NPMA + SyncController)~~ | ✅ DONE 2026-06-22 — pre-fixed in `0c7d6781b`/`504b4ca16`/`a5132aa80` (all 4 items resolved during G1 sweep) |
| ~~F1~~ | ~~Batch F~~ | ~~FORGE §3h residual~~ | ✅ DONE 2026-06-23 |
| ~~G1~~ | ~~Batch G~~ | ~~§3i MITHRIL+FORGE — BlockExecutionError hierarchy redesign: union type + `describe` — full prompt in DEFERRED-BACKLOG §3i~~ | ✅ DONE 2026-06-23 — `64ab4786e` |
| ~~F9~~ | ~~Batch F~~ | ~~Part 14 §ETH-BRU — ETH/69 BlockRangeUpdate inbound type fix~~ | ✅ DONE 2026-06-23 — `931c615dd` — ETH69→ETHPackets in PeerActor:551 + BlockFetcher:486; BlockFetcherSpec rebuilt against ETHPackets type; 26/26 tests pass |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

## ETH-Side Coverage Gaps (Part 13)

BEACON read-only audit of `scala3-cleanup-june` (`upstream/staging..HEAD`, 222
commits) for shared ETC+ETH paths changed with ETC-only / FORGE-only / Pekko-only
scope. §3i (`64ab4786e`, BlockExecutionError union + `describe`) excluded per
prior BEACON thread.

**Headline finding:** the sprint touched ETH/69 `BlockRangeUpdate` inbound
routing in NPMA (`13aa7585e`, W5/W11) and corrected NPMA to the type the decoder
actually produces (`ETHPackets.BlockRangeUpdate`), but left two sibling inbound
handlers (`PeerActor`, `BlockFetcher`) matching the never-emitted
`ETH69.BlockRangeUpdate` type. Both arms are dead on the real inbound path. ETH/69
is the only protocol that sends BlockRangeUpdate, so this is an ETH-path-only
regression masked by a test that constructs the wrong type.

| File | Sprint commit | Nature of gap | ETH risk | Recommended action |
|------|--------------|---------------|----------|--------------------|
| `network/PeerActor.scala:551` | `e6ccc5ac1` (Typed migration) + sibling of `13aa7585e` cleanup | Inbound `case bru: ETH69.BlockRangeUpdate` malformed-update validation/`BreachOfProtocol` disconnect. Decoder emits `ETHPackets.BlockRangeUpdate` (MessageDecoders.scala:234), so the arm never matches → falls through `case _ =>` and publishes unvalidated. NPMA was swept to `ETHPackets.BlockRangeUpdate`; PeerActor was not. | **High** — ETH/69-only message; malformed BlockRangeUpdate from an ETH/Sepolia peer is no longer rejected; the protocol-breach disconnect guard is dead. | BEACON: change match to `ETHPackets.BlockRangeUpdate`; add a decode→PeerActor test asserting malformed inbound triggers disconnect. |
| `blockchain/sync/regular/BlockFetcher.scala:486` | `5e3908f7f` (Behavior[Command] narrowing) + sibling of `13aa7585e` cleanup | Inbound `case AdaptedMessageFromEventBus(msg: ETH69.BlockRangeUpdate, _)` chain-tip follow (`withPossibleNewTopAt`). Subscribes to `Codes.BlockRangeUpdateCode` (line 103) but matches the wrong runtime type → head-follow signal silently dropped. | **High** — on ETH/Sepolia sync, peer-pushed chain-tip advances via BlockRangeUpdate are ignored; head following degrades to the periodic re-probe fallback only. | BEACON: change match to `ETHPackets.BlockRangeUpdate`. |
| `src/test/.../BlockFetcherSpec.scala:298-305` | pre-sprint (`6f0c606bc`), unchanged by sprint | Test "should request headers when BlockRangeUpdate announces a new chain tip" constructs `ETH69.BlockRangeUpdate`, matching the buggy line 486 — so it passes while the production decode path (`ETHPackets.BlockRangeUpdate`) would not. Test masks the gap. | **Medium** — false-positive coverage hides the High-risk gap above. | BEACON/EYE: rebuild the test to feed the decoder output type (`ETHPackets.BlockRangeUpdate`) so it exercises the real inbound path. |

**No other gaps found.** All other shared-path diffs in `domain/`, `vm/`,
`ledger/`, `network/p2p/` are mechanical Scala 3 syntax (`_`→`*`, `if () {}`→
`if … then`, `implicit class`→`extension`, wildcard imports) or are ETH-aware by
construction:
- `domain/Block.scala` / `BlockBody.scala`: `asInstanceOf[RLPList]` → guarded
  pattern match that throws on malformed RLP. Behaviour-preserving, applies to
  both chains, and the EIP-4895 withdrawals decode comments are ETH-correct. Not a gap.
- `vm/EvmConfig.scala`: timestamp-fork dispatch (`isShanghaiTimestamp` /
  `isCancunTimestamp` / `isOsakaTimestamp`, `OsakaOpCodes`) preserved verbatim — syntax only.
- `vm/OpCode.scala`, `vm/PrecompiledContracts.scala`: no opcode-list, fork-gate,
  or gas-constant changes — syntax only.
- `network/p2p/messages/{ETH69,ETHPackets,Capability}.scala`: no ADT member,
  message-code, or negotiation-logic changes — `implicit class`→`extension` + syntax.
- No new `@nowarn` / `@unused` in shared `domain/`, `vm/`, `ledger/`, `network/`.

---


---

## ETH/Sepolia Test Coverage Gaps (Thread 7 audit) ✅ ALL DONE 2026-06-25

All 5 gaps resolved — see Cleared entries log above for commit SHAs. Full spec preserved in `completed/` via DEFERRED-BACKLOG §I1/I2.

---

## §7d Audit Finding: SNAPRequestTracker Classic Wildcard Import (2026-06-24)

Discovered during §8k-B §7d Lens 6 sweep.

| # | Finding | File | Risk | Action |
|---|---------|------|------|--------|
| 1 | `import org.apache.pekko.actor.*` wildcard — only specific types (likely `Cancellable`, `Scheduler`) are needed; wildcard pulls in all Classic types | `blockchain/sync/snap/SNAPRequestTracker.scala:3` | **Low** | Replace wildcard with specific imports (read file to determine exact set); `sbt compile-all` to verify |

Occurrence count: 1 file.

**Clearout prompt:**

> Use the MITHRIL agent. Replace the Pekko Classic wildcard import in `SNAPRequestTracker.scala`:
> 1. Read `blockchain/sync/snap/SNAPRequestTracker.scala:1-20` — identify which specific Classic types are actually referenced in the file.
> 2. Replace line 3 `import org.apache.pekko.actor.*` with the specific imports only (e.g., `import org.apache.pekko.actor.{Cancellable, Scheduler}` or whatever the file actually uses).
> 3. `sbt compile-all` — 0 errors.
> 4. `sbt scalafmtAll`.
> 5. `git add src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPRequestTracker.scala`
> 6. `git commit -m "chore(snap): replace Pekko Classic wildcard import with specific types in SNAPRequestTracker (§7d)"`
> After committing, add SHA to Cleared entries log and remove this section.
