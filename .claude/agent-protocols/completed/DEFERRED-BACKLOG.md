# Fukuii Modernization — Completed Backlog Sections

**Branch:** `scala3-cleanup-june`
**Archived:** 2026-06-22
**Source:** `working-docs/DEFERRED-BACKLOG.md` — completed items extracted here.
**Active backlog:** See `working-docs/DEFERRED-BACKLOG.md` for open/deferred items.

---

## scala3-cleanup-june Sprint — COMPLETE

All in-scope work is committed on `scala3-cleanup-june`. Summary:

| Commit | Phase | What |
|--------|-------|------|
| `b25d117b3` | prereq | Scala 3.3.8 LTS bump + scapegoat 3.3.6 |
| `003752df3` | prereq | June dep sync (enumeratum, scalatest, scalamock, cats-effect, fs2, etc.) |
| `655240c68` | 1a | Pre-fix mixed-selector and cats bare wildcard imports |
| `333aab3fc` | 1b | bytes/, crypto/, rlp/ wildcard migration + `-source:future` enabled |
| `3693906d1` | 2 | 730-file warning elimination (ScalaMock E008, open class, type wildcards, `.scalafix.conf`) |
| `a53c039fd` | 1c | 633-file main src/ wildcard migration (incremental compiler had masked these) |

**End state**: `sbt compile-all` → 0 errors, 134 warnings (all pre-existing Pekko Classic E165
from un-migrated actors — resolved in the Pekko migration follow-up sprint below).

**Items that were NOT brought in (intentionally):**
- `c77c2ebf7` (ResourceHealthMonitor) — new feature added on `june-sprint`; does not belong on a cleanup branch
- Pekko Classic→Typed migrations — scope was too broad and insufficiently planned; promoted to own sprint (Part 2)

---

## Part 7a — Non-sealed Command ADT Consolidation ✅ DONE

**What**: BCC and SRC/ARC/TNHC/SSC used `trait Command` (non-sealed) because message cases
were declared in `Messages.scala` across package boundaries. Consolidated: moved all message
definitions for each coordinator into its own companion object, then sealed the trait.
Enables exhaustiveness checking at every `match` site.

**Scope**: `snap/actors/Messages.scala` refactor — redistributed cases into coordinator companions.
Updated all callers (SSC was the primary sender; import updates required).

| Actor | Commit | Notes |
|-------|--------|-------|
| BCC — ByteCodeCoordinator | prior session | Command cases moved to companion; sealed |
| SRC — StorageRangeCoordinator | prior session | Command cases moved to companion; sealed |
| ARC — AccountRangeCoordinator | prior session | Command cases moved to companion; sealed |
| TNHC — TrieNodeHealingCoordinator | `04615ad43` | Command cases moved to companion; sealed |
| SSC — SNAPSyncController | prior session | Command companion from SNAP1; sealed in 7a |
| Format | `4e8b42263` | `sbt scalafmtAll` after all redistributions |

Verification: All 5 sealed `trait Command` confirmed in companion objects. `Messages.scala` → empty tombstone object. 173/173 coordinator specs ✅. testEssential: **3,600 / 0** ✅.

---

## Part 7b — EventStream → Topic[T] ✅ DONE

2 commits on `scala3-cleanup-june`:
- `849c0dcf0` — P1: `NewPendingTransaction` → `Topic[NewPendingTransaction]` (PTM, SubscriptionManager)
- `b35b35cf6` — P2: `NewBlockImported` → `Topic[NewBlockImported]` (BlockImporter, RegularSync, SyncController)

**Design:** `EventTopicsBuilder` trait introduced in `NodeBuilder.scala` providing `pendingTxTopic` and `blockTopic` as lazy vals; mixed into concrete node builders.

**Scope:** 13 files — wider than estimated; `blockTopic` required threading through RegularSync and SyncController.

**Verification:** `compile-all` clean; `grep eventStream` → 0 (event bus fully retired for both types); SM 10/10, PTM 13/13, FM 5/5, SyncControllerSpec 84/84, RS+calibration 65/65; `scalafmtAll` clean.

**R5 research findings (from `eventstream-topology.md`):**
- 8 sites across 3 files, 2 event types, 1 consumer
- Zero `.toClassic.eventStream` bridges — CAPSTONE Phase 3 (`f5ece260a`) already completed the Classic→Typed lift
- All 8 sites used `EventStream.Publish` / `EventStream.Subscribe`
- `BlockImporter` → `NewBlockImported` → `SubscriptionManager`; `PendingTransactionsManager` → `NewPendingTransaction` → `SubscriptionManager`
- Both relationships are cross-subsystem → both mapped cleanly to `Topic[T]`
- No `@SerializabilityTrait` needed (single JVM, no Pekko remoting)
- No FORGE or HERALD gates — application-layer events; `NewBlockImported` fires after consensus complete

---

## Part 7e — Typed API Design Review ✅ DONE

**Status:** COMPLETE — findings at `.local/docs/moderization-review-june/typed-api-design-review.md`

**Accepted redesigns (3 items):**

| ID | Pattern | Finding | Priority | Effort |
|----|---------|---------|----------|--------|
| P4 | SSC idle-state catch-all (line 655) | `case other =>` suppresses exhaustiveness checking. Enumerate explicit per-case handlers; future additions require an explicit decision. | MED | M |
| P2 | HealingState extraction | 6 healing-specific vars (`trieWalkInProgress`, `healingServeRootRequestInFlight`, etc.) are phase-local; extract to `case class HealingState` behavior parameter to prevent bleeding across pivot refreshes. | LOW | S–M |
| P3 | CheckDownloadStagnation timer | ChainDownloader should push `DownloadStagnated` to SSC rather than SSC polling progress timestamps. | LOW | S |

**No changes (5 items):**
- P1 fire-and-forget chains: all correct — no ack semantics needed, no throughput risk
- P3 four 1-second dispatch timers: polling-by-design (SSC drives, coordinators execute)
- P5 eventStream: already fully Typed API; cross-subsystem relationships correctly use event bus

No FORGE or HERALD gates triggered. 7a gate (sealed ADTs) confirmed satisfied — `Messages.scala` empty tombstone; all 5 SNAP actors have sealed `Command` traits.

---

### Part 7e-P4 — SSC Idle-State Catch-All Exhaustiveness ✅ DONE

**What**: `SNAPSyncController` idle state had a blanket `case other => ...` at line 655 that
suppressed Scala 3 exhaustiveness checking for the sealed `Command` trait. Every future `Command`
addition silently fell through without a compile-time error.

**Scope**: `sync/snap/SNAPSyncController.scala` — idle-state receive block only.
**Gate**: 7a complete (sealed `Command` trait — done).

Removed `case other => ctx.log.debug(...)` catch-all from `idle()` (lines 655–657). Added 59 explicit
handlers covering every `Command` variant. Decision table:

| Category | Treatment | Examples |
|----------|-----------|---------|
| Unexpected signals | `log.warn` + `Behaviors.unhandled` | `BootstrapComplete`, `AccountRangeSyncComplete`, `StateHealingComplete` |
| Stale timer/coordinator messages | `log.debug` + `Behaviors.unhandled` | `RetrySnapSyncStart`, `TuneRateTracker`, `ChainDownloaderProgress` |
| High-frequency request ticks | silent drop (`Behaviors.same`) | `RequestAccountRanges`, `RequestByteCodes`, progress deltas |

**Commit:** `1da94de11`. Compile: 0 errors. VERIFY: `SNAPSyncControllerSpec` 69/69 ✅.

**Design gap found (tracked as 7e-P4a):** `GetProgress` had no reply path in `idle()` — ask-pattern callers would time out. `GetStatus` replies `NotSyncing` in idle; `GetProgress` should reply with `progressMonitor.currentProgress` (same pattern as `completed()`). Separate task.

---

### Part 7e-P4a — GetProgress Missing Idle-State Handler (SSC) ✅ DONE

**Commit:** `74db726d1`. Replaced 7-line warn-and-drop block at SSC:658 with `replyTo ! progressMonitor.currentProgress`, matching `syncing()` at line 1775. Compile: 0 errors. SNAPSyncControllerSpec: 69/69 ✅.

**What**: `GetProgress` was handled in `syncing()`, `completed()`, and `completedWithBackfill()` but had no handler in `idle()`. `GetStatus` correctly replied `NotSyncing` in idle; `GetProgress` silently dropped the message, causing ask-pattern callers to time out.

**Scope**: `sync/snap/SNAPSyncController.scala` — `idle()` receive block only. XS effort.
**Gate**: P4 complete (idle() is now exhaustively enumerated, making this gap visible).

---

## Part 8c — Memory / Resource Leak Audit ✅ DONE

**Output:** `.local/docs/moderization-review-june/memory-leak-audit.md` — 4 HIGH / 4 MEDIUM / 3 LOW.

**Recommended first fixes:** H2 + H3 (single-line additions to `StdNode.shutdown()`) — immediate hang/data-loss risk on every graceful stop. H1 needed BEACON sign-off on eviction policy.

| ID | Severity | File | Issue | Status |
|----|----------|------|-------|--------|
| **H1** | HIGH | `consensus/engine/EngineApiService.scala:42–75` | 6× `ConcurrentHashMap` fields (including `acceptedChildrenByParent` line 74) — no eviction; multi-GB heap growth over days | ✅ CLASS A `4f5a678fa` + CLASS B `8911135d9` |
| **H2** | HIGH | `StdNode.scala:208–219` | Secondary `ActorSystem` for `PeriodicConsistencyCheck` created and discarded — never terminated on `shutdown()` | ✅ `4907406fe` |
| **H3** | HIGH | `StdNode.scala:238–265` | `EngineApiHttpServer` (its own `ActorSystem` + `IORuntime`) never stopped in `shutdown()` — leaves port 8551 bound after node death | ✅ `4907406fe` |
| **H4** | HIGH | `EthashDAGManager.scala:64–78` | `FileOutputStream` closed in `Try(...)` outside `finally` — leaks descriptor + leaves corrupt file on disk if write throws | ✅ `ef75a5608` — `Using` wrap + corrupt partial file deleted on throw |
| M1 | MEDIUM | `EthashDAGManager.scala:84–104` | `FileInputStream` with 3 exit paths that skip `close()` | ✅ `ef75a5608` — `Using` wrap; `Try[Array[Array[Int]]]` return contract preserved via `.flatten` |
| M2 | MEDIUM | `PeerDiscoveryManager.scala:84` | `Resource.allocated` release IO stored correctly but no `PostStop` handler — UDP socket leaks on actor crash | **DEFERRED** — needs actor migration context (W2-P2 network sprint) |
| M3 | MEDIUM | `FileUtils.scala:28` | `getReader` returns raw `BufferedSource` with no lifecycle contract | ✅ `4907406fe` — Scaladoc only; sole caller (`SSLContextFactory.scala:22–27`) already closes in `finally` |
| M4 | MEDIUM | `NodeBuilder.scala:1102` | Race window between `portForwarding.allocated` placeholder and real cleanup IO write | **DEFERRED** |
| L1 | LOW | `TrieNodeHealingCoordinator.scala:365` | `healAttempts` map unbounded when serve-root is slow | **DEFERRED** — batch with future SNAP cleanup |
| L2 | LOW | `StorageRangeCoordinator.scala:126` | `accountSubtaskCounters` entries never removed after account completes | **DEFERRED** — batch with future SNAP cleanup |
| L3 | LOW | `PeerRateTracker.scala:25` | Disconnected peers never evicted from `peers` map | **DEFERRED** — batch with future network cleanup |

---

### 8c-H1-A — EngineApiService CLASS A: Bounded LRU + TTL Eviction ✅ DONE (`4f5a678fa`)

**Files**: `consensus/engine/EngineApiService.scala` — 4 payloadId-keyed build caches: `pendingPayloads`, `pendingPayloadRequests`, `pendingPayloadBlobsBundle`, `pendingPayloadReceipts`.

**Why CL-driven eviction is impossible**: The Engine API has no "discard payload" call; FCU never references `payloadId`. Eviction must be EL-self-driven.

**Implementation (shared timestamp map):** Added `pendingPayloadTimestamps: ConcurrentHashMap[ByteString, Long]` as a 5th eviction-index map. Helpers: `removePayloadEntry(id)` removes from all 5 atomically; `evictOldestIfAtCapacity()` synchronized on `evictionLock` — scans for smallest nanoTime when `size >= 64`. PUT site calls `evictOldestIfAtCapacity()` then timestamps before the 4 existing `.put()` calls (unchanged). GET site checks `nanoTime - insertedAt > PayloadTtlNs`; if stale, calls `removePayloadEntry` and returns `Left("Payload not available")`.

VERIFY: 16/16 tests pass.

---

### 8c-H1-B — EngineApiService CLASS B: Finalized-Watermark Prune ✅ DONE (`8911135d9`)

**Files**: `consensus/engine/EngineApiService.scala` — 2 hash-keyed correctness maps: `invalidBlocks`, `acceptedChildrenByParent`.

**Why TTL is unsafe**: `invalidBlocks` must not be evicted while any hash could still be referenced as an FCU head — early eviction = consensus fault.

**Safe trigger**: Prune entries for blocks at or below `forkChoiceState.finalizedBlockHash` height. This value is already parsed at service:432 and controller:686.

**Implementation:** Insertion point — inside `case Right(())` in `forkchoiceUpdated`, after mempool purge and before payload-attributes validation (~line 532). When `finalizedHash != zeroHash`, resolves its block number from `blockchainReader`, then calls `removeIf` on both maps: `invalidBlocks` keys pruned where `block.number <= finalizedNumber`; `acceptedChildrenByParent` keys pruned where `parent.number <= finalizedNumber`.

VERIFY: `compile-all` — 0 errors. `testOnly *EngineApi*` — 16/16 ✅.

---

### 8c-H2/H3 — StdNode.shutdown() Missing Teardown ✅ DONE (`4907406fe`)

**Files**: `StdNode.scala:208–219` (H2) and `StdNode.scala:238–265` (H3)

**H2**: Secondary `ActorSystem` spawned for `PeriodicConsistencyCheck` is created and discarded — never terminated on `shutdown()`. On every graceful stop, this system's threads remain alive until the JVM exits.

**H3**: `EngineApiHttpServer` owns its own `ActorSystem` + `IORuntime`. Neither is stopped in `shutdown()`. Port 8551 remains bound after node death, preventing restart without `kill -9`.

**Fix**: Both are single-line additions to `StdNode.shutdown()` — call `.terminate()` on the consistency-check system and `.stop()` on the engine-API server/runtime.

---

### 8c-H4 — EthashDAGManager Stream Safety ✅ DONE (`ef75a5608`)

**File**: `EthashDAGManager.scala:64–78` (H4) and `64–104` (M1 alongside)

**H4**: `FileOutputStream` closed in `Try(...)` outside `finally` — on a write exception, the stream is not closed and the partially-written DAG file is left on disk. Next boot reads a corrupt DAG.

**M1**: `FileInputStream` at lines 84–104 has 3 exit paths that bypass `close()`.

**Fix**: Wrapped both in `Using(new FileOutputStream(...)) { ... }` / `Using(new FileInputStream(...)) { ... }` (Scala `scala.util.Using`). Corrupt partial file deleted on throw.

---

### 8c-M3 — FileUtils.getReader Resource Contract ✅ DONE (`4907406fe`)

**File**: `FileUtils.scala:28` — `getReader` returns a raw `BufferedSource` with no lifecycle contract.

**Fix**: Added Scaladoc only — sole caller (`SSLContextFactory.scala:22–27`) already closes in `finally`. No production code change needed.

---

## Part 8d — IO / Concurrency Threading Model Audit ✅ DONE

**Overall risk: MEDIUM.** Dispatcher architecture is sound — sync, healing, account-trie, engine-api, and block-forger all have properly isolated bounded pools; four `blocking { }` sentinels in SNAP coordinators correctly placed. Two real problems; everything else vestigial imports, startup/shutdown `Await`s (by-design), or `Thread.sleep` on dedicated non-actor threads (by-design).

**Output:** `.local/docs/moderization-review-june/threading-model-audit.md`

| ID | File | Issue | Severity | Status |
|----|------|-------|----------|--------|
| **A1** | `consensus/engine/EngineApiService.scala:561` | `Await.result(future, 3.seconds)` inside `forkchoiceUpdated`'s `IO { }` body — executes on CE3 compute pool; blocks compute thread up to 3s per CL invocation; threatens staking availability under load | **fix-now** | ✅ `0a8ed3038` |
| **B1** | `jsonrpc/JsonRpcBaseController.scala:41` | `EC.global` as implicit `ExecutionContext` — all `Future` combinators and Pekko asks in RPC services (incl. KeyStore file I/O) run on unbounded global pool | defer | ✅ `276c77735` |
| **B2** | `consensus/pow/PoWMiningCoordinator.scala` | FORGE-gated finding | defer | CHASE-QUEUE |

---

### 8d-A1 — EngineApiService Blocking Await on CE3 Compute Thread ✅ DONE (`0a8ed3038`)

**File**: `consensus/engine/EngineApiService.scala:561`

**Problem**: `Await.result(future, 3.seconds)` nested inside `forkchoiceUpdated`'s `IO { }` body (`IO.delay`) — blocked CE3 compute thread up to 3s per CL invocation.

**Fix applied**:
- `IO { }` → `IO.defer { }` (defers evaluation; body must return `IO[A]`)
- `Await.result(ask(...), 3.seconds)` → `IO.fromFuture(IO(pendingTransactionsManager.ask[...]))` — no thread blocked
- All early-exit branches wrapped in `IO.pure(...)` to satisfy `IO.defer` contract
- `handleErrorWith` on the ask handles timeouts/failures (same empty-tx fallback as before)
- `import scala.concurrent.Await` removed (now unused)

**Verify**: 16/16 `EngineApiSpec` tests pass, `sbt compile-all` clean.

---

### 8d-B1 — JsonRpcBaseController EC.global → actorSystem.dispatcher ✅ DONE (`276c77735`)

**File**: `jsonrpc/JsonRpcBaseController.scala:41`

**Implementation** (10 files):

| File | Change |
|------|--------|
| `JsonRpcBaseController.scala` | `implicit def executionContext` → `abstract` |
| `JsonRpcController.scala` | Add `actorSystem: ActorSystem` field; `override implicit def executionContext = actorSystem.dispatcher` |
| `NodeBuilder.scala` | `JSONRpcControllerBuilder` self-type adds `ActorSystemBuilder`; passes `classicSystem` |
| `FaucetJsonRpcController.scala` | Add `actorSystem: ActorSystem` param; same override |
| `FaucetBuilder.scala` | `FaucetJsonRpcControllerBuilder` self-type adds `ActorSystemBuilder`; passes `system` |
| `JsonRpcControllerFixture.scala`, `FukuiiJRCSpec.scala`, `QaJRCSpec.scala` | Pass `system`/`testSystem` as last arg |
| `JsonRpcHttpServerSpec.scala`, `GraphQLHttpRouteSpec.scala` | Test-only mock stubs override with `EC.global` (mock framework intercepts all calls) |

---

## Part 9 — ETH Test Coverage ✅ DONE (BEACON sprint)

Source: `eth-coverage-audit.md` (2026-06-20). Pre-existing gaps, not sprint-introduced. All 5 items complete.

### G1 — PostMergeBlockHeaderValidator: zero unit test coverage ✅ DONE (`583aded58`)

**File:** `src/main/scala/.../consensus/validators/PostMergeBlockHeaderValidator.scala`
**Gap:** Touched by scala3-cleanup-june (syntax-only changes). Zero test coverage at any tier.

Wrote unit spec covering: valid post-merge header passes, parentHash mismatch fails, mixHash non-zero fails, nonce non-zero fails, difficulty non-zero fails, withdrawals root mismatch fails, excess blob gas validation (EIP-4844). Wiring follows `BlockHeaderValidatorSpec` pattern. 8 cases, all passing.

---

### G2 — No ethereum/tests ETH vectors below testComprehensive ✅ DONE (`dbef878`)

`EthSmokeSpec.scala` (44 lines) — 5 vectors (4 Berlin, 1 Istanbul). `EthSmoke` tag added to `Tags.scala`. `testEthSmoke` build alias added to `build.sbt` scoped to `IntegrationTest / EthSmoke`. All 5 vectors pass in 21s.

**Implementation notes:**
- **Fork coverage gap** — no London/Cancun/EIP-4844 vectors existed locally (only Berlin + Istanbul in `src/it/resources/`; post-merge paths point at the CI-only `ets/tests` submodule). Spec follows guardrail: real verified vectors only. Extended with post-merge vectors in G2-R.
- **Tag conflict trap** — tagging with both `(IntegrationTest, EthSmoke)` causes zero tests to run: `commonSettings` injects `-l IntegrationTest` as a global exclusion. Fix: tag with `EthSmoke` only — tests are already in `IntegrationTest` config by directory.

---

### G2-R — Post-Merge EthSmoke Vector Candidates ✅ DONE (`00166a555`)

**Doc:** `.local/docs/moderization-review-june/eth-smoke-vector-candidates.md`

Implemented 5 JSON files committed under `src/it/resources/ethereum-tests/` (flat layout). 10 new vectors wired in `EthSmokeSpec` but `ignore`d pending G5. CI: 5 pass / 0 fail / 10 ignored / 18.7s.

**Corpus finding:** The `ethereum/tests` checkout regenerates vectors with only `Cancun` and `Prague` as `network` field values. Feature-equivalent vectors per fork:

| Target Fork | Feature | Candidate File |
|-------------|---------|----------------|
| London | EIP-1559 basefee | `basefeeExample.json` |
| Merge/Paris | EIP-3675 | `mergeExample.json` |
| Shanghai | EIP-4895 withdrawals | `shanghaiExample.json` |
| Cancun | EIP-1153 tload | `tloadDoesNotPersistCrossTxn.json` |
| Cancun | EIP-4844 blobs | `blockWithAllTransactionTypes.json` |

**Critical caveat:** `EthereumTestsAdapter.scala` was pre-merge-shaped: `TestBlockHeader` dropped `baseFeePerGas`, `withdrawalsRoot`, `excessBlobGas`, `blobGasUsed`, `parentBeaconBlockRoot`. Fixed in G5.

---

### G3 — Missing `osaka` case in TestConverter.networkToConfig() ✅ DONE (`ef5ad3376`)

**File:** `src/it/scala/.../ethtest/TestConverter.scala` — `networkToConfig()` method

**Gap:** Osaka (Sepolia's currently-active fork) had no case in the network→config mapping. Any ethereum/tests vector tagged `Osaka` was silently unmappable; execution harness fell through to default (pre-osaka config), producing wrong results without failing loudly. Production fork dispatch (`EvmConfig.forTimestamp`) unaffected.

**Fix:** Added `case "Osaka" | "Prague" => sepoliaConfig.copy(...)` with the correct Osaka fork block/timestamp. `IntegrationTest / compile` clean.

---

### G4 — EthereumTestsSpec.runSingleTest() dead code ✅ DONE (`f4746250e`)

**File:** `src/it/scala/.../ethtest/EthereumTestsSpec.scala`

`runSingleTest(resourcePath, testName)` — loads suite via `loadTestSuite`, looks up test by exact key, calls `executeTest()` on hit, returns `Left` with available names on miss. `runTestFile(filePath)` — reads from real filesystem path, decodes via circe `BlockchainTestSuite` decoder, maps each case through `executeTest()`. Both route through `EthereumTestExecutor.executeTest → EthereumTestHelper → BlockExecution.executeAndValidateBlock()`. `sbt compile-all` → 0 errors. 54 insertions, 30 deletions.

---

### G5 — EthereumTestsAdapter post-merge extension ✅ DONE (`12a79b7c3`)

**Result:** 15/15 pass, 0 ignored. `sbt compile-all` → 0 errors.

**4 defects fixed:**
1. **`gasPrice` optional** — changed from required `String` to `Option[String]`; missing `gasPrice` on type-0x02/0x03 defaults to `BigInt(0)`. Unblocked 8 vectors.
2. **Genesis header fields** — added `withdrawalsRoot`, `baseFeePerGas`, `blobGasUsed`, `excessBlobGas`, `parentBeaconBlockRoot`, `requestsHash` to `TestBlockHeader`. Reconstructed genesis now selects correct `HeaderExtraFields` variant; genesis hash aligns with `block[0].parentHash`. Unblocked shanghai vector.
3. **ETC Olympia EIP-2935 path fired on ETH vectors** — `networkToConfig` was defaulting to `NetworkType.ETC`, triggering the block-number EIP-2935 guard on ETH timestamp-fork vectors. Fix: set `NetworkType.ETH` for ETH test chains.
4. **EIP-4895 withdrawals dropped** — `TestBlock` ignored the `withdrawals` array; state-root mismatch on shanghai vector with non-empty withdrawals. Fix: added `TestWithdrawal` decoder, threaded withdrawals into `BlockBody`.

**G-series execution order:** G3 → G1 → G4 → G2 → G2-R → G5 — all BEACON-only. 15/15 EthSmoke vectors passing.

---

### EIP-2935 Account-Existence Fix (follow-on) ✅ DONE (`bbc5f1df8`)

Found during G5: `BlockExecution.applyEip2935` had an account-existence gap. FORGE-assessed.

**What was done**: Removed `isActivationBlock` variable entirely. Changed `w1` condition from `if isActivationBlock && world.getCode(HistoryStorageAddress).isEmpty` to `if world.getCode(HistoryStorageAddress).isEmpty` — code-absence is now the sole deployment guard, matching `applyEip4788`. Self-heals any absent-account scenario on post-activation blocks.

New regression test in `BlockHashHistorySpec`: runs `olympiaBlock + 1` on `emptyWorld` (no pre-seeded account), asserts code deployed + slot written. `testOnly *BlockHash* *BlockExecution* *Eip2935*` → 21/21; `BlockHashHistorySpec` → 6/6 (5 pre-existing + 1 new).

---

## PRISM Post-Capstone Artifact Audit ✅ DONE

**Report:** `.local/docs/moderization-review-june/post-capstone-artifact-audit.md`
**Date:** 2026-06-21
**Summary:** 4 resolved, 38 intentional, 5 fix-now

### Resolved (4)

Pre-existing findings already addressed on `scala3-cleanup-june`. No action needed.

### Intentional (38)

Pervasive `toClassic` / `toTyped` bridges and `Behavior[Any]` occurrences throughout the codebase. PRISM confirmed all 38 are load-bearing interop at the `PeerEventBus` Classic subscriber boundary — not misuse. Do not touch without a full PeerEventBus Typed migration (LOOM).

### Fix-Now (5) — routed to C13 / C14

| File | Line | Issue | Fix |
|------|------|-------|-----|
| `jsonrpc/AdminService.scala` | 10 | `o.a.p.actor.Actor` imported for `Actor.noSender` | → `ActorRef.noSender` |
| `jsonrpc/AkkaTaskOps.scala` | 3 | Same `Actor.noSender` misuse | → `ActorRef.noSender` |
| `nodebuilder/StdNode.scala` | 3 | Same `Actor.noSender` misuse | → `ActorRef.noSender` |
| `blockchain/sync/SyncController.scala` | 1507 | Classic `scheduler.scheduleOnce` inside Typed actor | → `ctx.scheduleOnce` or `Behaviors.withTimers` |
| `blockchain/sync/SyncController.scala` | 2140 | Same Classic scheduler (30-min delay) | → same fix |

All PRISM-gate — no migration work required. See CHORE-QUEUE C13/C14.

---

## Regular Sync LCA Recovery — handleForkRecovery Precise Rollback ✅ DONE (`0d290019e`)

**Audit:** HERALD (2026-06-21). **Source:** `post-capstone-artifact-audit.md` chain / CHASE-QUEUE cleared log.

### Background

Regular sync has three divergence-recovery paths (escalating):

| Path | Trigger | Action |
|------|---------|--------|
| A — stale-tip rewind | `HeaderRejectionRewindThreshold` = 3 consecutive rejections from distinct peers | `invalidateBlocksFrom(lastBlock - 128)` — blind 128-block rewind |
| B — UnknownBranch rewind | `BranchResolution.resolveBranch` returns `UnknownBranch` | `InvalidateBlocksFrom(currentBlock - branchResolutionRequestSize)` — blind rewind |
| C — handleForkRecovery | `ForkDetectThreshold` = 5 consecutive `UnknownParent | BlockImportFailed` on same block hash | `setCanonicalChainHead(currentBest - 128, ...)` — destructive 128-block canonical-chain rollback, iterates |

### Gap

Path C (the most aggressive, `BlockImporter.scala:797`) iterates: rolls back the canonical chain 128 blocks per `ForkDetectThreshold` cycle, permanently mutating chain index entries during recovery. On a fork deeper than 128 blocks it cycles repeatedly — each iteration takes minutes (5 strikes × peer response timeouts). It converges but has no concept of the LCA.

**ETC mainnet impact:** Low — MESS makes forks deeper than 128 blocks near-impossible. More relevant on Mordor.

### Fix

`FastSyncBranchResolverActor` already implements the full LCA search (recent-header scan + binary search fallback). One parameter generalization makes it reusable:

1. **Generalize constructor**: `fastSync: ClassicActorRef` → `replyTo: ActorRef[BranchResolverResponse]` (one-line change)
2. **Add `ResolvingFork` behavior** to `BlockImporterLogic`: suspends import dispatch; waits for `BranchResolvedSuccessful(lca, _)` or `BranchResolutionFailed`
3. **Replace blind rollback** in `handleForkRecovery` (`BlockImporter.scala:797`): spawn branch resolver → on `BranchResolvedSuccessful` call `setCanonicalChainHead(lca, ...)` once, precisely, then `InvalidateBlocksFrom(lca + 1)`; on `BranchResolutionFailed` fall back to current 128-block blind rewind

No consensus code touched. No new network protocol. Binary search reuses existing `GetBlockHeaders` path.

**Dependency:** FastSyncBranchResolverActor wiring in `FastSync.scala` — ✅ `ea60c4f29`.
**Size:** S. **Gate:** None. **Agent:** LOOM (new behavior state) + HERALD pre-flight.

**Implementation:** `handleForkRecovery` (`BlockImporter.scala:797`) spawns `FastSyncBranchResolverActor` with a typed `replyTo` adapter; enters new `resolvingFork` suspended state. `BranchResolvedSuccessful(lca, _)` → `setCanonicalChainHead(lca)` + `InvalidateBlocksFrom(lca + 1)` (precise rollback). `BranchResolutionFailed` → original 128-block blind rewind (safe degradation). 46/46 targeted tests pass.
