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
| ~~`consensus/pow/PoWMiningCoordinator.scala`~~ | ~~—~~ | ~~Threading model finding (R9/8d B2): FORGE-gated.~~ **CLEARED 2026-06-23 (F1, Item C): FORGE-confirmed SAFE AS-IS.** B2 (`EC.global` escaping actor dispatch) already remediated in current source — line 133 supplies `(context.executionContext)` for the `.foreach` continuation. Heavy PoW runs off-thread via `unsafeToFuture`; self-send `MineNext` sequenced through actor mailbox. No change required. | ~~MUTABLE~~ | PRISM | 2026-06-21 |
| ~~`ledger/BlockExecution.scala`~~ | ~~530, 538, 548~~ | ~~§3h: `val reason: Any` could be narrowed to `String`.~~ **CLEARED 2026-06-23 (F1, Item A): REJECT narrowing — `72a755efa`.** `reason` holds the LUB of three unrelated sealed error hierarchies (`BlockHeaderError`, `BlockError`, `OmmersError`) passed to `ValidationBeforeExecError`; consumers render via `.toString`. Narrowing breaks `StdValidators:76` / `ValidatorsExecutor:106`. Markers → `// §3h: FORGE-confirmed — Any required`. | ~~FORGE~~ | MITHRIL | 2026-06-22 |
| ~~`domain/Address.scala` `domain/UInt256.scala` `vm/Memory.scala` `vm/Stack.scala`~~ | ~~52, 171, 112, 85~~ | ~~§3h: `override def equals(that: Any)`.~~ **CLEARED 2026-06-23 (F1, Item B): CONFIRMED — `7c951fd44`.** All four are standard `java.lang.Object.equals` overrides; JVM provides no typed alternative. Markers → `// §3h: FORGE-confirmed — java.lang.Object.equals signature is fixed by JVM`. Comment-only, no logic change. | ~~FORGE~~ | MITHRIL | 2026-06-22 |
| `sync/SyncControllerSpec.scala` | 243 | [DisabledTest P9] "not change best block after receiving faraway block": test injects `PeerRequestHandler.ResponseReceived` directly to Typed FastSync actor via classic ref; `prhResultAdapter` is private (`FastSync.scala:158`) — no external injection path. Needs rewrite to drive FastSync via its Typed Command interface instead. | CLASSIC | EYE | 2026-06-23 |
| `sync/regular/RegularSyncSpec.scala` | 550 | [DisabledTest P9] "save fetched node": ScalaMock `stub[BranchResolution]` never intercepts `resolveBranch` calls under Scala 3; expectations always unsatisfied. Known Scala 3 reflection issue with ScalaMock stubs. Needs mock → explicit test double. | EXCEPT | EYE | 2026-06-23 |
| `jsonrpc/controllers/JsonRpcControllerSpec.scala` | 76, 127 | [DisabledTest P9] Two tests: json4s ScalaSig reflection failure under testEssential class-loading order. Pass in isolation; fail when json4s reflection state is polluted by an earlier suite. Needs json4s upgrade or test isolation via fork. | EXCEPT | EYE | 2026-06-23 |
| `jsonrpc/controllers/JsonRpcControllerEthSpec.scala` | 559, 852 | [DisabledTest P9] Two tests: same json4s ScalaSig reflection failure as JsonRpcControllerSpec above — test-ordering-sensitive under testEssential. | EXCEPT | EYE | 2026-06-23 |
| `jsonrpc/service/EthTxServiceSpec.scala` | 372 | [DisabledTest P9] "returns transaction by hash": `logIndex` expected 1 vs actual 0; topics asserted as `List` but production now returns `Vector`. Assertion drift from production type changes — update expected values and topic container type. | EXCEPT | EYE | 2026-06-23 |
| `sync/SyncController.scala` | 895-897 | [Production bug] `handleRegularSyncMsg` catch-all forwards **all** unhandled messages to RegularSync via `regularSync.tell(msg, ctx.toClassic.sender())`. Late-arriving `FastSync.Done` (sent after `syncSwitchDelay`) hits this path and crashes RegularSync with `ClassCastException: FastSync$Done$ cannot be cast to RegularSyncCommand`. FlakyTest candidate — seen in "start state download" (F7 scope). Fix: add explicit `case FastSync.Done => Behaviors.same` guard before the catch-all. | CLASSIC | EYE | 2026-06-23 |

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
| MetricsAlreadyConfiguredError + LocalVM + AdaptiveSyncStrategy | Part 8f dead code | Cleared 2026-06-22: `fa57df9b9` — 3 confirmed dead files deleted. grep-verified 0 callers each; no test files existed; sbt compile-all 0 errors. |
| DeltaSpikeGauge + Metrics.deltaSpike() | Part 8f dead code | Cleared 2026-06-22: `c6b3da4cb` — unused spike metric, 0 call sites, pattern superseded by counter/gauge. |
| discovery/StaticNodesLoader.scala | Part 8f dead code | Cleared 2026-06-22: `ff2fc219c` — DiscoveryConfig redirected to network.StaticNodesLoader (stricter validation: full pubkey + port check vs prefix-only); duplicate deleted. |
| Branch-wide dead-code audit | scala3-cleanup-june all deletions | Cleared 2026-06-22: 19 files audited (19 Scala files across 5 commits), verdicts: 17 DELETE-CORRECT, 1 DEFER (AdaptiveSyncStrategy already in DEFERRED-BACKLOG 9a), 1 NOT-DELETED (DumpChainActor class removed; companion object constants retained). New DEFERRED-BACKLOG entries: none (9a was pre-existing). Opportunistic: Versions.scalapb dead constant → scoped immediately as C20. |
| `project/Versions.scala` — orphaned `val scalapb` | `project/Versions.scala` | Cleared 2026-06-22 (C20): `Versions.scalapb = "0.11.20"` sole consumer was `project/scalapb.sbt` (deleted in `a948fda1d` extvm cleanup). No other reference in `build.sbt`, `Dependencies.scala`, or any source. Entire `project/Versions.scala` deleted (only val). `sbt compile-all` clean. |
| FastSync NULL vars (P8-Item1) | `blockchain/sync/fast/FastSync.scala:343,363–364` | Cleared 2026-06-22: `0c7d6781b` (W13+W14) — `var syncState/syncStateStorageActor/syncStateScheduler = null` replaced with `Option[T]`/`None` during G1 var-accumulator sweep. Pre-fixed before P8 session. |
| FastSync EXCEPT expandTypedReceipts (P8-Item2) | `blockchain/sync/fast/FastSync.scala:571–591` | Cleared 2026-06-22: `0c7d6781b` (W13) — `throw new RuntimeException` + try/catch replaced with `scala.util.Try(...).fold(...)`. Pre-fixed before P8 session. |
| NPMA DEAD identical if/else arms (P8-Item3) | `network/NetworkPeerManagerActor.scala:325–327,689–692` | Cleared 2026-06-22: `504b4ca16` (NPMA W1/W2/W12/W16) — dead branches removed during NPMA dead-branch/command-seal pass; `usesRequestId` predicate confirmed pure. Pre-fixed before P8 session. |
| SyncController IMPLICIT EC.global (P8-Item4) | `blockchain/sync/SyncController.scala:15` | Cleared 2026-06-22: `a5132aa80` (C2) — `import scala.concurrent.ExecutionContext.Implicits.global` removed; `given ec: ExecutionContext = ctx.executionContext` added. Pre-fixed before P8 session. |

---

## SNAP2 replyTo adapter dependency (S2 → SNAP2, 2026-06-21)

`FastSyncBranchResolverActor` now receives `replyTo: ActorRef[BranchResolverResponse]`. FastSync (`Behavior[Any]` core) currently passes `ctx.messageAdapter[BranchResolverResponse](identity)` as the replyTo and routes the response through its `Any` handler. When FastSync is narrowed to `Behavior[Command]` in the network/P2P sprint, that adapter and the replyTo pass will need replacing with a proper typed adapter or a direct Typed self-ref. Not a blocker now — the `identity` adapter is correct for `Behavior[Any]`. Flag at SNAP2 re-entry.

---

## E165 TestProbe pattern — test harness cleanup (S2 + S5, 2026-06-21/22)

**Corrected baseline (S5 EYE sweep 2026-06-22):** 777 unnarrowed `TestProbe` sites across 83 test files. Prior entry stated "5 in FastSyncBranchResolverSpec" — that file now has 0 unnarrowed sites (was cleaned in a prior session). The 777 sites are pervasive across SNAP coordinator and network test suites.

**Progress (§8a-retro batch 3, 2026-06-23):** 25 network/sync specs migrated to `ActorTestKit`; `PeerManagerSpec` (32 sites) migrated. Recount pending — run `grep -rn "org.apache.pekko.testkit.TestProbe\b" src/test/ --include="*.scala" | grep -v "\[" | wc -l` after batch 4 to update floor.

Remaining highest-density files (all §8a-retro batch 4 scope): `TrieNodeHealingCoordinatorSpec` (58), `ByteCodeCoordinatorSpec` (56), `AccountRangeCoordinatorSpec` (54), `StorageRangeCoordinatorSpec` (39) — 207 sites total; primary batch 4 target.

Fix: narrow each `TestProbe` with a `[M]` type parameter matching the expected message type. Pattern selectors on `Any` from untyped probes produce E165 warnings in strict Scala 3. The coordinator/heal specs require the `HealingTrieFixtures` PropsAdapter fix (§8a-retro batch 4) before they can be migrated. Route remaining non-coordinator files to a dedicated test-harness cleanup sprint after batch 4.

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

