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

### 8d-CONDUIT — jsonrpc/ IO boundary scan ✅ DONE (2026-06-24)

CONDUIT audit of all 55 `jsonrpc/` files (including `graphql/`, `server/`, `mcp/`, `client/`, `serialization/` subdirs).

| Pattern | Hits |
|---------|------|
| `scala.concurrent.blocking` | 0 |
| `Await.result` / `Await.ready` | 0 |
| `ExecutionContext.global` / `Implicits.global` | 0 |
| `Thread.sleep` | 0 |
| `java.util.concurrent.Future.get()` | 0 |

`.get()` calls in `AdminService`, `EthMiningService`, `NetService` are `AtomicReference.get()` — wait-free lock-free reads. `McpPrompts.get()` is static prompt object method dispatch. All by-design.

**No code changes.** §8d fully closed — all sub-items (A1, B1, CONDUIT scan) resolved.

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

---

## Part 10: Test Suite Performance

### P7 — EYE/MITHRIL: Test timing audit + slow-test reduction ✅ DONE (run-order ~~D3~~)

**Agent:** EYE (timing profiler), MITHRIL (Thread.sleep replacement)
**Prerequisite:** testEssential gate passed. Run AFTER Batch D (G1/G2) so that any new test files
from the Behavior[Any] narrowing sprint are included in the timing baseline.

**Context:** testEssential baseline was ~24:22 (3,601 tests). Batch C cleanup (dead code deletion,
E165 expectMsgType narrowing, enum conversions) may have affected this. After Batch D the suite will
grow slightly (narrowing adds typed actor specs). This prompt captures the new baseline and identifies
actionable slow tests.

**Steps:**

1. **Capture new baseline:**
   ```bash
   cd /media/dev/2tb/dev/fukuii
   time .local/scripts/fukuii-test 2>&1 | tee /tmp/fukuii-test-timing.log
   ```
   Record total wall time from `time` output.

2. **Identify slow tests (>2s per test):**
   ```bash
   grep -E "\([0-9]+ seconds" /tmp/fukuii-test-timing.log | sort -t'(' -k2 -rn | head -20
   ```
   List the top 20 slowest individual tests.

3. **Assess Thread.sleep sites (2 known):**
   - `EthMiningServiceSpec.scala:302` — timeout window advance; check if `TestScheduler` can replace
   - `SubscriptionManagerSpec.scala:249` — topic propagation wait 200ms; check if `awaitAssert` with short poll replaces it
   For each: if replaceable with `TestScheduler` or `eventually(timeout(500.ms), interval(10.ms))`,
   fix inline. If requires Typed TestKit migration → defer to §8a.

4. **Assess wall-clock assertions (3 known + 1 borderline):**
   - `WorkNotifierSpec` L103–108 (`elapsed should be < 500L`) — can the upper bound be raised to reduce flakiness?
   - `MerkleProofVerifierPhase3Spec` L584–603 — already has generous bounds; record observed times
   - `TrieNodeHealingCoordinatorSpec` L316–327 (`elapsedMs should be < 5000L`) — record observed time
   - `SnapServerLimitsSpec` L89–90 — borderline; record whether it flaps
   If any bound is routinely met with <50% margin, either raise the bound or replace with a
   non-time-based assertion.

5. **Check for accidentally slow test infrastructure:**
   ```bash
   grep -rn "Thread\.sleep\|Await\.result\|blocking {" src/test/ --include="*.scala"
   ```
   Any new sites not in the known list → log to CHASE-QUEUE.

**Verification:** New baseline ≤ prior baseline (23 min target). All 3,601+ tests pass.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll` — if any test files were modified
2. `git add <specific test files changed>` — stage only modified files; skip if no source changes
3. `git commit -m "test(timing): P7 — replace wall-clock assertions, N fixes"` — omit if no source changes
4. `SHA=$(git rev-parse --short HEAD)` — capture SHA (or note "no source commit" if step 3 skipped)
5. Update run-order table: strikethrough D3 → `| ~~D3~~ | ~~Batch D~~ | ~~P7 EYE test timing audit~~ | ✅ DONE [date] — Xs baseline, N improvements, $SHA |`
6. Update `test-quality-log.md` with new baseline
7. Any Thread.sleep fixes → `completed/SPRINT-QUEUE.md` row with `$SHA`
8. `git add .claude/` → `git commit -m "docs(p7): clearout — $SHA"`

**Rejection criteria:** Weakening test assertions beyond 2× measured time; skipping tests to reduce count; modifying test logic (only timing assertions and sleep replacement are in scope)

---

## Part 11: Test Tag Audit

### P8 — EYE: SyncTest tag audit — rescue mis-tagged unit tests ✅ DONE (run-order ~~E1~~)

**Agent:** EYE (read, grep, verdict per test)
**Prerequisite:** None. Read-only — no code changes, only assessment and a verdict file.

**Context:** `SyncTest` is excluded from ALL tiers in `build.sbt:85`. The tag description says
"Tests for blockchain synchronisation." However, grep reveals ~50 tests across 8 files using this
tag, many of which look like pure unit tests (exponential backoff math, cache data structures, peer
selection logic) that don't require live sync or any actor timing. They were probably tagged
`SyncTest` because they live in sync-related packages, not because they actually need the exclusion.

Rescuing mis-labelled tests to `UnitTest` would immediately add them to `testEssential`.

**Files to audit:**
- `RetryStrategySpec.scala` — 12 tests: exponential backoff, delay caps, jitter, fluent config. Likely all pure unit.
- `PeersClientSpec.scala` — 5 tests: peer selection data structures (BestPeer, filter by block number).
- `CacheBasedBlacklistSpec.scala` — 5 tests: blacklist cache add/expire/remove/keys.
- `BlockchainHostActorSpec.scala` — 8 tests: actor serves block data using TestProbe. Actor-based but hermetic.
- `StateStorageActorSpec.scala` — 1 test: actor persists fast sync state.
- `StateSyncSpec.scala` — 2 tests: state sync to tries.
- `FastSyncSpec.scala` — 3 tests tagged `(UnitTest, SyncTest, FlakyTest)` + 1 tagged same. (FlakyTest root cause is P10.)
- `SyncControllerSpec.scala` — `FlakyTest` ones are P10. Remaining SyncTest-only tests assessed here.

**Steps:**
1. For each file above, read the test bodies. For each test, answer:
   - Does it require a live network connection or real peer handshake? → Keep `SyncTest`
   - Does it use real clock / wall-time sensitivity? → Keep `SyncTest` or add `FlakyTest`
   - Is it a pure function / data-structure test with TestProbe? → Candidate for `UnitTest` rescue
   - Is it tagged `SyncTest` AND `FlakyTest`? → Skip (P10 handles FlakyTest cases)

2. Produce a verdict table:
   ```
   | File | Test description | Current tags | Verdict | Reason |
   ```
   With verdicts: `RESCUE→UnitTest` / `KEEP SyncTest` / `REASSIGN→IntegrationTest` / `DEFER (P10)`.

3. For each `RESCUE` verdict: remove `SyncTest`, add `UnitTest` if not already present.
   - `SyncTest` appears in two patterns: `taggedAs (UnitTest, SyncTest)` and `taggedAs (UnitTest, SyncTest, FlakyTest)`
   - Only edit the `UnitTest, SyncTest` (no FlakyTest) ones in this prompt
   - Compile after each file: `sbt compile-all`

4. Run `testEssential` after all rescues to confirm the rescued tests pass in Tier 1.

**Verification:** `sbt compile-all` clean. Rescued tests appear in `testEssential` output and pass.
`testEssential` count increases by the number of rescued tests.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific test files modified>` — stage only the rescued/fixed test files
3. `git commit -m "test(p8): SyncTest audit — rescue N tests, delete M"`
4. `SHA=$(git rev-parse --short HEAD)` — capture exact SHA
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E1 → `| ~~E1~~ | ... | ✅ DONE [date] — N rescued, $SHA |`
6. Update `test-quality-log.md` with new testEssential count
7. Add CHASE-QUEUE entry: remaining SyncTest count and path to `-l SyncTest` removal (P8+P10 prerequisite)
8. `git add .claude/` → `git commit -m "docs(p8): clearout — $SHA"`

**Rejection criteria:** Rescuing any test that uses `Thread.sleep`, real wall-clock assertions, or
live network/peer connections. Rescue only hermetic tests.

---

### P9 — EYE/MITHRIL: DisabledTest audit — fix, wire, or delete ✅ DONE (run-order ~~E2~~)

**Agent:** EYE (assess each test), MITHRIL (implement fixes where needed)
**Prerequisite:** None. Can run parallel to P8.

**Context:** 9 tests across 5 files are tagged `DisabledTest`, which ADR-017 defines as
"temporarily disabled due to known issues — should be re-enabled." These are not dead code —
they are tests with a stated intent. But "temporarily" may have become permanent. Each needs
a verdict: Fix & enable / Delete (the test is wrong or the feature is gone) / Defer with
written reason and a GitHub issue link.

**Inventory (9 tests, 5 files):**

| File | Line | Test description |
|------|------|-----------------|
| `RegularSyncSpec.scala` | 522 | "retry fetching node if validation failed" |
| `RegularSyncSpec.scala` | 550 | "save fetched node" |
| `SyncControllerSpec.scala` | 243 | "not change best block after receiving faraway block" |
| `SyncControllerSpec.scala` | 434 | "re-enqueue block bodies when empty response is received" |
| `JsonRpcControllerSpec.scala` | 76 | (read to determine description) |
| `JsonRpcControllerSpec.scala` | 127 | (read to determine description) |
| `JsonRpcControllerEthSpec.scala` | 559 | (read to determine description) |
| `JsonRpcControllerEthSpec.scala` | 852 | (read to determine description) |
| `EthTxServiceSpec.scala` | 372 | (read to determine description) |

**Steps for each test:**
1. Read the test body (±20 lines around the listed line).
2. Run `git log -p --follow -S "DisabledTest" -- <file>` to find when/why it was disabled.
3. Attempt to compile and run the test alone: `sbt "testOnly *SpecName* -- -n DisabledTest"` — does it pass?
4. Verdict:
   - **FIX**: If the test fails with a specific error → fix the underlying issue, remove `DisabledTest`, add appropriate tier tag.
   - **DELETE**: If the feature under test was removed, renamed, or the test was clearly wrong → delete the test and note why.
   - **DEFER**: If fixing requires significant new implementation or blocked on an external gate → document the block, create a CHASE-QUEUE entry, leave `DisabledTest` tag but add a comment with the reason.

5. Commit fixed tests individually. Format: "test: re-enable <TestName> — <one-line fix>"

**Verification:** After each fix, `sbt compile-all` + `sbt "testOnly *SpecName*"` passes.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific test files modified>` — stage only the fixed/deleted test files
3. `git commit -m "test(p9): DisabledTest audit — fix N, delete M, defer K"` — one commit per test or per file is also fine (see step 5 in the prompt above)
4. `SHA=$(git rev-parse --short HEAD)` — capture the final commit SHA (or comma-separate multiple SHAs if committed individually)
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E2 → `| ~~E2~~ | ... | ✅ DONE [date] — N fixed, M deleted, $SHA |`
6. Add any DEFERred items to CHASE-QUEUE with `[DisabledTest]` prefix
7. `git add .claude/` → `git commit -m "docs(p9): clearout — $SHA"`

**Rejection criteria:** Re-enabling a test without understanding why it was disabled. Never remove
`DisabledTest` without verifying the test actually passes.

---

### P10 — EYE/MITHRIL: FlakyTest root cause audit — fix or delete ✅ DONE (run-order ~~E3~~)

**Agent:** EYE (diagnose root cause), MITHRIL (fix with deterministic patterns)
**Prerequisite:** P8 complete (so SyncTest+FlakyTest overlap is clear).

**Context:** 8 tests across 3 files are tagged `FlakyTest`. ADR-017 says "investigate and fix but
temporarily marked to avoid blocking CI." These are the tests most likely to contain real bugs —
race conditions, wall-clock sensitivity, or non-deterministic actor interactions. None of them run
in any tier. Fixing them is high-value: these cover sync state, PoW mining, and peer management.

**Inventory (8 tests, 3 files):**

| File | Line | Test description | Also tagged |
|------|------|-----------------|-------------|
| `FastSyncSpec.scala` | ~244 | (read to determine) | UnitTest, SyncTest |
| `FastSyncSpec.scala` | ~287 | (read to determine) | UnitTest, SyncTest |
| `FastSyncSpec.scala` | ~311 | (read to determine) | UnitTest, SyncTest |
| `FastSyncSpec.scala` | ~336 | "returns Syncing with state nodes progress" | UnitTest, SyncTest |
| `SyncControllerSpec.scala` | ~385 | (read to determine) | (check) |
| `SyncControllerSpec.scala` | ~470 | (read to determine) | (check) |
| `PoWMiningCoordinatorSpec.scala` | ~123 | "Miners mine recurrently" | UnitTest, ConsensusTest, SlowTest |
| `PoWMiningCoordinatorSpec.scala` | ~188 | "StopMining stops PoWMinerCoordinator" | UnitTest, ConsensusTest, SlowTest |

**Known root cause — SyncControllerSpec FlakyTests (identified in P9 thread, 2026-06-23):**

`SyncController.scala:895-897` — `handleRegularSyncMsg` forwards all unhandled messages to
`RegularSync` via `regularSync.tell(msg, ctx.toClassic.sender())`. When `FastSync.Done` arrives
late (after `syncSwitchDelay = 0.5s`, i.e. after SyncController has already transitioned to
`runningRegularSync`), it lands in this catch-all and is `tell`-forwarded to the RegularSync
classic child, which crashes with `ClassCastException: FastSync$Done$ cannot be cast to
RegularSyncCommand`.

**Fix (apply before diagnosing the tests):** Add a guard arm before the catch-all in
`handleRegularSyncMsg`:
```scala
case FastSync.Done => Behaviors.same  // late arrival after sync switch — ignore
```
Confirm the arm is placed BEFORE the `regularSync.tell` catch-all. Compile:
```bash
sbt compile-all
```
Then run the two SyncControllerSpec FlakyTests 5× to confirm the race is resolved before
proceeding with the remaining inventory.

**Steps for each test (one at a time, no parallel):**
1. Read the full test body.
2. `git log -p --follow -S "FlakyTest" -- <file>` to find when it was marked flaky and what comment was left.
3. Identify the root cause category:
   - **`Thread.sleep` / wall-clock assertion** → Replace with `TestScheduler` / `eventually` / `awaitAssert`
   - **Non-deterministic actor message ordering** → Add `TestProbe.expectMsgAllOf` or reorder assertions
   - **Race between actor startup and first message** → Add `awaitAssert` or `expectMsgType` with explicit timeout
   - **Real PoW computation timing** (PoWMiningCoordinatorSpec) → Inject a fake miner that succeeds immediately
   - **Test depends on external state** → Isolate with mocks or hermetic fixtures
4. Attempt the fix. Compile: `sbt compile-all`.
5. Run 10× to confirm not flaky: `for i in $(seq 10); do sbt "testOnly *SpecName*" && echo "PASS $i" || echo "FAIL $i"; done`
6. If not fixable without major refactor → verdict DELETE, with rationale written in test comment before removal.
   Never leave a flaky test enabled — either fix it or delete it.
7. Remove `FlakyTest` tag once confirmed stable (10/10 passes). Add correct tier tag.

**Special case — PoWMiningCoordinatorSpec:** These two tests involve real Ethash PoW computation,
which is inherently variable. The fix is almost certainly a fake/mock miner that completes
instantly, not a timing adjustment. Check if `EthashMiner` is injectable; if not, MITHRIL adds
a `MinerFactory` seam.

**Verification:** Fixed tests pass 10/10 in `testOnly`. No `FlakyTest` tags remain in the fixed files.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific test files modified>` — stage only fixed/deleted test files
3. `git commit -m "test(p10): FlakyTest audit — fix N, delete M"` — or per-test commits (format in step 5 above)
4. `SHA=$(git rev-parse --short HEAD)` — capture final commit SHA (comma-separate if multiple)
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E3 → `| ~~E3~~ | ... | ✅ DONE [date] — N fixed, M deleted, $SHA |`
6. If any tests also rescued from SyncTest → update P8 verdict table with those SHAs
7. Update `test-quality-log.md` test count after fixes land in testEssential
8. `git add .claude/` → `git commit -m "docs(p10): clearout — $SHA"`

**Rejection criteria:** Re-tagging a flaky test as `SlowTest` or `DisabledTest` to avoid fixing it.
A test must be either reliably passing or deleted — no half-measures.

---

### P11 — EYE: testStandard baseline + SlowTest tag audit ✅ DONE (run-order ~~E4~~)

**Agent:** EYE (run testStandard, assess SlowTest tag accuracy)
**Prerequisite:** P8, P9, P10 complete (so the test count is stable before capturing the baseline).

**Context:** `testStandard` (~30 min) adds `SlowTest` and `IntegrationTest` to the essential tier.
No baseline has ever been recorded for this tier. Additionally, some `SlowTest` tagged tests
appear mislabelled (e.g., `MiningSpec:10` — "KnownProtocols have unique names" — should not be
slow). This prompt captures the Standard baseline and audits SlowTest label accuracy.

**SlowTest inventory for label-accuracy check:**

| File | Tests | Why tagged SlowTest? | Likely correct? |
|------|-------|---------------------|-----------------|
| `DAGGenerationSpec.scala` | 7 | Ethash cache+DAG CPU computation | ✅ Yes — legitimately slow |
| `EthashNonceSearchSpec.scala` | 6 | PoW nonce search (CPU-bound) | ✅ Yes |
| `EthashMinerSpec.scala` | 2 | Mining valid blocks (actual PoW) | ✅ Yes |
| `PoWBlockHeaderValidatorSpec.scala` | 1 | Ethash header validation | Possibly — assess observed time |
| `PoWMiningCoordinatorSpec.scala` | ~7 | Mining coordinator w/ actor timing | Possibly — assess |
| `PoWMiningSpec.scala:71` | 1 | "not start miner when miningEnabled=false" | ❓ Likely mislabelled |
| `MiningSpec.scala:10,17` | 2 | "unique names" / "contain ethash" | ❌ Almost certainly mislabelled |
| `MerkleProofVerifierPhase3Spec.scala:573` | 1 | Quadratic growth regression check | ✅ Yes — 100-1000 acct comparison |

**Steps:**
1. Check system resources: `free -h && uptime` (load < 4.0 before starting).
2. Run testStandard and capture timing:
   ```bash
   cd /media/dev/2tb/dev/fukuii
   start_time=$(date +%s)
   .local/scripts/fukuii-test standard 2>&1 | tee /tmp/fukuii-teststandard-timing.log
   end_time=$(date +%s)
   echo "TOTAL_ELAPSED: $((end_time - start_time)) seconds" | tee -a /tmp/fukuii-teststandard-timing.log
   ```

3. After completion, identify the top 20 slowest tests:
   ```bash
   grep -E "\([0-9]+ seconds" /tmp/fukuii-teststandard-timing.log | sort -t'(' -k2 -rn | head -20
   ```

4. For each test tagged `SlowTest`: compare its actual observed time against the `SlowTest` definition
   (">100ms, <5 seconds"). If actual time is <100ms → `MISLABELLED` → remove `SlowTest`, add `UnitTest`.

5. For `MiningSpec:10,17` and `PoWMiningSpec:71` specifically: if observed time is <100ms →
   remove `SlowTest` tag and add `UnitTest`, which promotes them to `testEssential`.

6. Record the testStandard baseline in `fukuii/.local/docs/test-quality-log.md`.

**Verification:** All testStandard tests pass (0 failures). Baseline recorded.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll` — only if test files were modified
2. `git add <specific test files modified>` — stage only files with tag changes; skip if no source changes
3. `git commit -m "test(p11): promote N mislabelled SlowTest → UnitTest"` — omit if no source changes
4. `SHA=$(git rev-parse --short HEAD)` — capture SHA (note "no source commit" if step 3 skipped)
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E4 → `| ~~E4~~ | ... | ✅ DONE [date] — Xs wall time, N tests, M mislabelled fixed, $SHA |`
6. Update `test-quality-log.md` with testStandard baseline and timing
7. `git add .claude/` → `git commit -m "docs(p11): clearout — $SHA"`

**Rejection criteria:** Removing `SlowTest` from a test that actually takes >100ms. Observe the
time, don't guess. `DAGGenerationSpec` and `EthashNonceSearchSpec` must remain `SlowTest`.

---

## Part 12: Pre-Olympia Consensus Correctness Gate

### §G5 — BlockExecution.applyEip2935 account-existence gap (BEACON + FORGE) ✅ DONE (`bbc5f1df8`)

**Source:** CHASE-QUEUE `BlockExecution.applyEip2935` entry (cleared 2026-06-21, routed here)
**Branch:** Any post-Olympia-gated branch
**Risk:** MEDIUM — consensus-adjacent storage write; pre-Olympia correctness gap; Hive compliance blocker

**Background:**

`BlockExecution.applyEip2935` writes to `HistoryStorageAddress` storage without first
guaranteeing the account exists. The parallel method `applyEip4788` does create the account
if absent before writing. Currently masked on real ETC mainnet by deployment order (the
`HistoryStorageAddress` account pre-exists at activation block), but:

1. **Hive compliance:** EIP-2935 Hive tests construct `emptyWorld` + post-activation block;
   the absent-account path hits `getGuaranteedAccount` → `IllegalStateException` → test failure.
2. **Test construction trap:** Any `BlockHashHistorySpec` scenario starting from an empty world
   after the activation block will silently fail to write or throw.
3. **Olympia activation risk:** If activation block ordering or genesis conditions ever shift,
   the storage write silently fails or corrupts state (storage on a non-existent account).

**Current code pattern** (analogous to applyEip4788 — read both before touching either):
```bash
grep -n "applyEip2935\|applyEip4788\|HistoryStorageAddress\|BlockHashHistory" \
  src/main/scala/io/iohk/ethereum/blockchain/ledger/BlockExecution.scala
```

**Fix (FORGE + BEACON reviewed verdict — do not implement without confirmation):**
- Drop `isActivationBlock &&` from the `w1` branch condition so the account-existence guard
  runs on every post-activation block (not just the activation block itself)
- OR adopt the same "create if absent" guard pattern used in `applyEip4788`
- Exact approach must be confirmed with FORGE (ETC/Olympia) + BEACON (EIP-2935 spec)

**New test required:** `BlockHashHistorySpec` absent-account scenario:
```scala
// Test pattern: emptyWorld + post-activation block → storage write succeeds + account exists
// Verify: no IllegalStateException, HistoryStorageAddress account exists after call
// Mirrors: existing applyEip4788 test coverage pattern
```

**Gate condition:** BEACON review (EIP-2935 spec compliance) + FORGE review (ETC/Olympia
activation block semantics) BOTH required before any code change. This touches consensus
ledger logic and both chains are affected.

**Owner:** BEACON + FORGE — do not delegate to MITHRIL or WRAITH alone.

**Priority:** HIGH — Hive EIP-2935 compliance blocker for Olympia acceptance testing.
Handle before any Hive ETC Olympia test suite run.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../ledger/BlockExecution.scala src/test/scala/.../ledger/BlockHashHistorySpec.scala` — stage only the two files changed
3. `git commit -m "fix(ledger): applyEip2935 account-existence guard — match applyEip4788 pattern (Part 12 §G5)"`
4. `SHA=$(git rev-parse --short HEAD)` — capture exact SHA
5. `./local/scripts/fukuii-test` → confirm 3,595+ tests, 0 failures; record timing
6. Add to `CHASE-QUEUE.md` cleared entries log: `| BlockExecution.applyEip2935 Part 12 §G5 | Cleared [date]: $SHA — account-existence guard added; BlockHashHistorySpec absent-account test added |`
7. `git add .claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md .claude/agent-protocols/working-docs/CHASE-QUEUE.md` → `git commit -m "docs(part12-g5): clearout — $SHA"`
8. DELETE this section

---

## W2-P1 History — COMPLETE

### W2-P1 Commits

| Commit | What |
|--------|------|
| `94879ba59` | `NodeBuilder.scala`: `with`→`&` in self-type continuations |
| `82cd757ea` | `MiningBuilder` + `FaucetBuilder`: multi-line self-types |
| `8bfa0552a` | 8 files: `private[this]`/`protected[this]` → `private`/`protected` |
| `a211638f1` | Test deprecations: `expectNoMsg`, `left.get`, `json4s extract` |
| `a211638f1` | 5 files: inline `with` as type operator |
| `00e4ce34b` | 14 files: `= _`, infix operators, wildcards, `Ordering.Iterable`, E029 |
| `823732397` | `BootstrapDownload`: `new URL(String)` → `URI.create().toURL()` |

**Fixed in earlier phases (not part of W2-P1 sweep):**
- #1 `BlockHeaderValidatorSkeleton.scala:218` unused implicit `_blockchainConfig` — cleared
- #2 `PeersClient.scala:326` unused param `_peer` — cleared
- #3 `extvm/VMClient.scala:22` unused constructor param — cleared (C4 deleted extvm entirely)
- #4/#5 `PathNodeStorage.scala` unused `hash` params — cleared
- #6 `ETHPackets.scala:106` E092 `@unchecked` — cleared
- E003 `with` in self-types (337 occurrences) — cleared in W2-P3a + W2-P1
- E198 unused test symbols — addressed

---

## Part 2: Pekko Classic → Typed Migration — Subsystems 1–4 COMPLETE

| # | Subsystem | Actors | Files | Risk | Status |
|---|-----------|--------|-------|------|--------|
| 1 | `faucet/` | FaucetHandler + FaucetSupervisor | 7 | LOW | ✅ DONE — `551bccfaf` (post-rebase). Sealed Command ADT, two state behaviors, Classic→Typed adapter, Typed TestProbes. FaucetHandlerSelector deleted. |
| 2 | `jsonrpc/` | FilterManager + SubscriptionManager | ~8 | LOW-MED | ✅ DONE — `2ac71a58e` + `1309bb968` (post-rebase). ctx.messageAdapter bridges Classic eventStream. eventStream msgs confirmed local-only. 22/22 tests. |
| 3 | `transactions/` | PendingTransactionsManager + SignedTransactionsFilterActor | 10+18 | HIGH | ✅ DONE — `0be6dd776`. WrappedPeerEvent adapter, MailboxSelector.bounded(50000), toClassic.eventStream bridge, field-type updates in RegularSync/SyncController/BlockchainHostActor. |
| 4 | `consensus/pow/miners/` + `ommers/` | MockedMiner + OmmersPool | 19 | MED | ✅ DONE — `0aa837d5e`. OmmersPool: sealed Command ADT, immutable state via recursive `running()`, replyTo. MockedMiner: 4-state context.become → per-state Behaviors, pipeToSelf, context.scheduleOnce. FORGE-approved. Ommer ordering invariants preserved. |

### NET Group — Done Items

| Item | Location | Disposition |
|------|----------|-------------|
| `@annotation.unused timers` | `PeerManagerActor.Impl:272` | ✅ DONE `4b101b612` — `Behaviors.withTimers` wrapper dropped entirely; `@annotation.unused timers: TimerScheduler[Command]` removed from Impl constructor; import dropped. `classicSystem.scheduler` private def stays — still used for `scheduleWithFixedDelay` (node-update, status-refresh) and `scheduleOnce` (connect retries). 61/61 PeerManager tests green. Full typed-timer migration deferred to network/P2P sprint. |
| NET-01 | `NetworkPeerManagerActor.scala:165,451,663` | ✅ DONE `6b506a63f` (partial) — `@annotation.unused timers: TimerScheduler[Any]` removed from NPMA Impl constructor; `timers,` removed from `new Impl(ctx, timers, ...)` call; TimerScheduler import dropped. `private def scheduler = ctx.system.classicSystem.scheduler` stays — correct for the two fire-and-forget `AddToBlacklistCmd` delays. Full treatment (path b: typed timers) deferred to network/P2P sprint. |

---

## Part 3b — implicit class → extension methods — COMPLETE (2026-06-20)

**Commit**: `c0a3612b4` on `scala3-cleanup-june`

**Scope completed**: Non-consensus `src/main/` (excludes `consensus/`, `vm/`, `crypto/`, `domain/`).
29 files changed, all `AnyVal` implicit classes and Dec/RLP-codec implicit classes converted.

**Kept as `implicit class` (with reasons):**

| Class | File | Reason |
|-------|------|--------|
| All `*Enc extends MessageSerializableImplicit` | ETHPackets, SNAP, ETH69, WireProtocol | Subtype polymorphism — `new FooEnc(msg): MessageSerializable` in MessageDecoders |
| `SignedTransactionEnc extends RLPSerializable` | ETHPackets | `toBytes` used via trait inheritance in `domain/BlockBody` (excluded path) |
| `MptNodeEnc extends RLPSerializable` | MptNodeCodecs | `toBytes` used via trait inheritance in SNAP sync codec layer |
| `TxLogEntryRLPEnc` | ETHPackets | Name collision: `ReceiptCodecs` also has `extension (TxLogEntry) { def toRLPEncodable }` — ambiguous under wildcard import |
| `ReceiptBloomEnc` | ETHPackets | Same name-collision reason; also scoped-import disambiguation in `BlockchainHostActor` |
| `ReceiptBloomFreeEnc` | ETHPackets | Same as above |

---

## Part 3c — isInstanceOf / asInstanceOf audit — COMPLETE

✅ DONE `7cc9eda3a` — 1 site fixed (mpt/Node.scala); consensus/vm/crypto/domain had 0 hits

---

## Part 3d — Done Items

- `SyncPhase` ✅ DONE `adf4e69ea` — 8-member single-line enum; `SyncPhase.*` imported at 5 call sites
- `ForkId` message codes ✅ DONE `adf4e69ea` — `ForkIdValidationResult` 3-member enum; 4 external callers updated
- `Blacklist.BlacklistReason` ❌ REJECTED — has 7 `final case class` subtypes. Not a pure discriminant; cannot be an enum.
- `Blacklist.BlacklistReasonType` ❌ REJECTED — non-trivial behavior fields and mixin group traits. Not a pure discriminant enum.

---

## Part 3e — Console output → logging — COMPLETE

✅ DONE `c3fec6390` 2026-06-22 — 12 sites fixed (3 files); 8 intentional CLI/TUI calls preserved

---

## Part 3f — Manual synchronization outside actors — Audit COMPLETE (`cf33cfa87`)

5 sites in `src/main/`, all accounted for:

| # | File | Line | Bucket | Disposition |
|---|------|------|--------|-------------|
| 1 | `db/cache/MapCache.scala` | 19 | D | ✅ Fixed — `mutable.HashMap` → `TrieMap`; `this.synchronized` on update removed |
| 2 | `db/cache/MapCache.scala` | 30 | D | ✅ Fixed — same backing change; `this.synchronized` on get removed |
| 3 | `blockchain/sync/CombinedRecoveryScanner.scala` | 106 | D | Left as-is — `lock.synchronized` serializes compound multi-structure transaction across parallel `Future` workers; `ConcurrentHashMap` cannot substitute. Comment at line 104 documents this. |
| 4 | `consensus/pow/PoWMining.scala` | 106 | A | No-touch — **FORGE gate required**. Compound check-then-act on two `@volatile` fields; could become `AtomicBoolean` but FORGE must sign off. Logged in CHASE-QUEUE. |
| 5 | `blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala` | 1617 | D | Left as-is — `visitedLru.synchronized` on `LinkedHashMap`-backed bounded FIFO-eviction set; `ConcurrentHashMap` was the prior implementation and produced a silent correctness hole (comment at lines 1607–1614 documents why). |

No Bucket C violations (no actor-internal state accessed outside actor thread).

---

## Part 3g — StateValidator.scala Exception Swallowing — RESOLVED

**Source:** R0 audit Cat 5 (exception swallowing)
**File:** `src/main/scala/.../blockchain/sync/snap/StateValidator.scala`
**Status:** RESOLVED 2026-06-20 — disposition **log + swallow** (non-behavioral observability),
plus one safe conservative-flag improvement. Tests: `*StateValidator* *SNAP* *Trie*` 245/0.

**FORGE assessment:**
- **Q1 — Intentional fault tolerance?** Partly. The validator's contract: `Right(missing)` = "walk completed, here are nodes to heal"; `Left(error)` = "walk could not complete." The `collectAccounts` / leaf-decode / storage-walk silent `case _: Exception => ()` sites were an over-broad accidental catch-all.
- **Q2 — Correct behavior?** **Log + swallow** (not propagate). The walks run fire-and-forget inside a `Future`; the `Left` path triggers a full validation-retry / restart / dormant cycle. Propagating a transient decode/I-O fault would be more destructive. Two sites additionally **conservatively add the affected root to the missing set** to close the false-"intact" hole.
- **Q3 — MissingNodeException vs other exceptions distinction?** Now every catch site logs (WARN for non-missing/unexpected, DEBUG for ordinary missing).

**Sites changed (all in `snap/StateValidator.scala`):**
- `traverseForMissingNodes` HashNode (was 127): log WARN, still mark-as-missing.
- `validateAllStorageTries` account-traversal (was 58): log WARN before existing `Left`.
- `validateAllStorageTries` storage-walk (was 73): log WARN + **flag storageRoot for healing**.
- `collectAccounts` leaf decode (was 148): log WARN.
- `collectAccounts` branch terminator decode (was 170): log WARN.
- `collectAccounts` HashNode resolve (was 182-183): split — DEBUG (missing) / WARN (unexpected).
- `walkAccountTrieDFS` leaf decode (was 305): log WARN.

---

## Part 3h — `Any` in Type Signatures — COMPLETE

✅ DONE 2026-06-22 — MITHRIL pass complete. 15 sites documented `// Any:`, 7 FORGE-gated (markers added, logged in CHASE-QUEUE). 0 type changes (all remaining uses are intentional: Pekko messageAdapter, Micrometer gauge, Java interop, or FORGE-gated). `Behavior[Any]` sites: all 12 actors narrowed to `Behavior[Command]` ✅ DONE 2026-06-22.

---

## Part 3i — BlockExecutionError hierarchy redesign — COMPLETE

✅ DONE 2026-06-23 — `64ab4786e` — §3i MITHRIL+FORGE — BlockExecutionError hierarchy redesign: union type + `describe`

---

## Part 6a — extvm/ Dead Code Deletion — COMPLETE (`a948fda1d`)

18 files deleted, 1,423 deletions. All 3 pre-checks passed.

- `src/main/scala/.../extvm/` — 11 Scala source files
- `src/test/scala/.../extvm/` — MessageHandlerSpec, VMClientSpec
- `src/main/protobuf/extvm/msg.proto` + `src/main/resources/extvm/VERSION`
- `project/scalapb.sbt` — entire file (sbt-protoc plugin was extvm-exclusive)
- `build.sbt` / `Dependencies.scala` — PB.targets block, `scalapb-runtime` dep, extvm coverage/scapegoat exclusions removed

`sbt clean compile-all` → 0 errors. Side effect: Part 1 Warning #3 (`extvm/VMClient.scala:22`) now resolved.

---

## Part 7d — Post-CAPSTONE Classic Artifact Audit — DONE

Post-CAPSTONE Classic Artifact Audit was run 2026-06-21 — Report: `.local/docs/moderization-review-june/post-capstone-artifact-audit.md` — Summary: 4 resolved, 38 intentional, 5 fix-now (routed to C13/C14)

---

## Part 8a — Classic TestKit → ActorTestKit — Batches 1–4 COMPLETE

**8a-retro batch 1 — consensus/mining ✅ DONE** (`0d65a85c4`). 27/27 tests green.

| File | Key change |
|------|-----------|
| `LegacyTransactionHistoryServiceSpec` | Drop `TestKit` + `WithActorSystemShutDown` → `ScalaTestWithActorTestKit`; `system.toClassic` for Classic TestProbe (service still takes Classic ActorRef) |
| `ForkChoiceManagerSpec` | Same swap; fixes latent bug — original had no `afterAll` shutdown, leaking the actor system after every test run |
| `PoWMiningSpec` | Pure swap — TestKit was vestigial (no probes, no messaging) |
| `WorkNotifierSpec` | Swap + `system.toClassic` for Pekko HTTP's `Http()` (requires Classic system); drop explicit `BeforeAndAfterAll` (comes free from `ScalaTestWithActorTestKit`) |
| `MockedMinerSpec` | Swap + `system.toClassic` for Classic probes in `MinerSpecSetup`; `classicSystem.spawnAnonymous` → `testKit.spawn` |

**8a-retro batch 2 — jsonrpc/ + graphql/ ✅ DONE** (`b5e11c0a4` + `722f316f2`). 275/275 tests green.

| Commit | Files | Tests |
|--------|-------|-------|
| `b5e11c0a4` | DebugServiceSpec, DebugTracingServiceSpec, EthBlocksServiceSpec, EthInfoServiceSpec, EthMiningServiceSpec, EthProofServiceSpec, EthTxServiceSpec, EthUserServiceSpec, FukuiiServiceSpec, GasPriceOracleSpec | 143/143 |
| `722f316f2` | graphql/GraphQLServiceSpec, JsonRpcController{EthLegacyTransaction,Eth,Personal,}Spec, McpServiceSpec, PersonalServiceSpec, QAServiceSpec, TraceServiceSpec, TxPoolServiceSpec; modified: JsonRpcControllerFixture | 132/132 |

**Recurring pitfalls table:**

| Issue | Root cause | Fix |
|-------|-----------|-----|
| `PatienceConfig` ambiguity | `NormalPatience`/`LongPatience` abstract override conflicts with `ScalaTestWithActorTestKit.patience` | Drop patience trait from mixin; test kit default (10s) sufficient |
| `cannot create top-level actor from the outside` | Classic adapter `system.spawnAnonymous(...)` blocked by Typed test kit's custom user guardian | Thread `ActorTestKit` as implicit param into fixture; use `actorTestKit.spawn(...)` |
| `override` error on `def timeout` | `ActorTestKitBase` already declares `def timeout: Timeout` | Add `override` modifier |
| `system.toTyped.scheduler` invalid | After migration, `system` is already `ActorSystem[Nothing]` | Change to `system.scheduler` |
| No `afterAll` → resource leak | `WithActorSystemShutDown` was providing cleanup | `ScalaTestWithActorTestKit` handles shutdown automatically |
| `QAServiceSpec` — no Classic usage | Only `WithActorSystemShutDown` held the system | Clean removal; no `classicActorSystem` needed |

**8a-retro batch 3 — network/sync (G1-narrowed) ✅ DONE** (`12c23cf8a` + `a719520db`). 25 specs migrated.

| Commit | Files | Notes |
|--------|-------|-------|
| `12c23cf8a` | ByteCode/AccountRange/StorageRange/TrieNodeHealingWorkerSpec, StorageRecoveryActorSpec, BlockBroadcastSpec, SyncStateDownloaderStateSpec, CombinedRecoveryScanActorSpec, BlockFetcherStateSpec, SyncProgressMonitorSpec, ServerActorSpec, PeerEventBusActorSpec, NetworkPeerManagerActorHandshakeSpec, IORuntimeInitializationSpec | 14 specs, part 1 |
| `a719520db` | StateSyncSpec, StateNodeFetcherSpec, PivotHeaderBootstrapSpec, PivotBlockSelectorSpec, BytecodeRecoveryActorSpec, FastSyncSpec, FastSyncBranchResolverActorSpec, ChainDownloaderSpec, SNAPRequestTrackerSpec, SNAPFakePeerSpec, PeerManagerSpec; also NetworkPeerManagerFake | 11 specs + NPMAFake `GetHandshakedPeers`→`GetHandshakedPeersCmd(replyTo)` fix |

**New pitfalls discovered in batch 3:**

| Issue | Root cause | Fix |
|-------|-----------|-----|
| `system.stop(ref)` on kit-spawned actor | classic `StopChild` sent to Typed guardian → `ClassCastException` → system shutdown | `testKit.stop(typedRef)` |
| Missing named dispatchers | default kit config lacks `sync-dispatcher`, `account-trie-dispatcher`, etc. | `ScalaTestWithActorTestKit(ConfigFactory.load())` |
| `must.Matchers` conflicts with kit's `should.Matchers` | E164 on override | Drop `must.Matchers` mixin; use `should.*` throughout |
| `awaitCond(cond, max, interval, msg)` gone | Classic TestKit method, absent from Typed kit | `eventually(timeout(X), interval(Y)) { assert(cond, msg) }` with `Eventually` + `SpanSugar.*` |
| `adapter.*` needed for probe-as-typed-param | `TestProbe().ref` passed as typed param; adapter provides implicit conversion | Retain `import org.apache.pekko.actor.typed.scaladsl.adapter.*` in affected files |

**8a-retro batch 4 — 14 coordinator/heal specs ✅ DONE** (`5eae34c21`). 135 tests.
**8a-infra — application-test.conf ✅ DONE** (`8b9bef67d`)
**8a-infra-b — worker teardown leaks audit ✅ DONE** (`781c8e985`) — no leaks; workers are Typed `spawnAnonymous` children, stopped by hierarchy; 150/150 ×2
**8a-retro batch 4b — E165 TestProbe narrowing ✅ DONE** (`a193bc794`) — 14 specs, 141 tests, floor 92→65
**8a-infra-c — actorSelection worker-ref pattern replacement ✅ DONE** (`5f28e8ae6`) — 40/40 tests; see node/testing-infra.md

---

## Part 8d — Done Items

- ~~**B2**~~ ✅ CLEARED 2026-06-23 (FORGE F1 Item C) — confirmed SAFE AS-IS; `context.executionContext` already supplied; `MineNext` sequenced through actor mailbox. No change.
- ~~**B1**~~ ✅ CLEARED 2026-06-22 `a5132aa80` (C2) — `import scala.concurrent.ExecutionContext.Implicits.global` removed; `given ec` wired from `ctx.executionContext`.

---

## Part 8e — Done Items

- `C2` chore removes `return` from ~52 non-actor non-consensus sites — ✅ DONE `9eb1f4e06` (19 files; 0 compile errors)
- TNHC 4 (actual 11) returns — ✅ DONE `7a48c5988` (LOOM Phase 0, S3 TNHC thread)

---

## Part 8f — Dead Code Audit (Broader than extvm) — RESEARCH DONE (2026-06-22)

**PRISM sweep complete.** 4 high-confidence candidates identified (see CHASE-QUEUE.md DEAD entries 2026-06-22). No `FIXME`/`HACK`/`TODO` markers found. Deletion sprint pending.

**Known candidates beyond extvm:**
- `FastSyncBranchResolverActor` ✅ WIRED `ea60c4f29` — `FastSync.scala` `handleBlockHeaders` `ParentChainWeightNotFound` case now spawns the actor (binary search for true common ancestor) and transitions to `waitingForBranchResolution()`; `BranchResolvedSuccessful` resets cursors/queues; `BranchResolutionFailed` falls back to N-block rewind. 15/15 tests pass. testEssential 3,600/0 ✅.
- Test helpers with `@Ignore` annotations (56 occurrences in tests) — audit which are permanently dead

**Deletion sprint results:**
- `fa57df9b9` — MetricsAlreadyConfiguredError + LocalVM + AdaptiveSyncStrategy deleted
- `c6b3da4cb` — DeltaSpikeGauge deleted
- `ff2fc219c` — StaticNodesLoader deleted
- Branch-wide audit 2026-06-22 confirmed no further candidates

---

## Part 8k — Classic Interop Elimination — Done Clusters

### §8k-R1 — PRISM: Comprehensive classic-interop audit ✅ DONE 2026-06-23

**Output:** `.local/docs/classic-interop-audit.md` (535 lines, 14 clusters, bridge census ~130 prod + 2 test).
Root-cause breakdown: ~130 production bridge sites + 2 test `actorSelection` sites. Permanent floor: 4 TCP bridges. Eliminatable: ~126 production + 2 test.

### Done Cluster Summary

| Cluster | Sites | Status |
|---------|-------|--------|
| A — `messageAdapter.toClassic` (PeerEventBus subscriptions) | ~26 | ✅ DONE `93bcedb12` |
| B — `handshakedPeersAdapter.toClassic` | ~15 | ✅ DONE `c42316b39` |
| C — `ctx.toClassic.sender()` in SyncController/FastSync | ~27 | ✅ DONE `2ef2b6637` |
| D — `ctx.toClassic.actorOf(RegularSync)` | 2 | ✅ DONE `b24515637` |
| F — `ctx.self.toClassic` coordinator→worker + SSC→coordinator | ~15 | ✅ DONE (§8k-A + §8k-C) |
| G — `context.toClassic.parent` in PeerActor | 7 | ✅ DONE `222623960` |
| H — `ctx.spawn(...).toClassic` for PeerActor ref | 1 | ✅ DONE `222623960` |
| K — `peerEventBus.toClassic` + spawn `.toClassic` in NodeBuilder | 3 | ✅ DONE `2ef2b6637` |
| L — `AkkaTaskOps.askFor` (jsonrpc, ~18 call sites) | ~18 | ✅ DONE `2ef2b6637` |
| M — `peerEventBus.toClassic` watchWith in PEBA itself | 1 | ✅ DONE `93bcedb12` |
| N — `ctx.self.toClassic` / `fetcherReplyTo.toClassic` in BlockImporter | 4 | ✅ DONE `b24515637` |

### §8k-G — CONDUIT + MITHRIL: OQ-5 kill ✅ DONE `2ef2b6637`

**Completed:** 2026-06-23 · 25 files (17 main + 8 test)
**What was done:** SyncProtocol `GetStatus`/`ResetFastSync`/`RestartFastSync` gained typed `replyTo` fields. All `ctx.toClassic.sender()` sites in SyncController/FastSync/RegularSync replaced with `cmd.replyTo`. jsonrpc callers switched from Classic `?` ask to Typed ask pattern. NodeBuilder `syncController` field changed from Classic `ActorRef` to `TypedActorRef[SyncController.Command]`. Clusters C, K, L ✅ eliminated.

---

## Part 9 — Research Threads R3, R5, R8, R9 — DONE

| Thread | Status |
|--------|--------|
| **R3** ✅ | Jackson ecosystem gate (json4s 4.2.0 status) — gate nearly open (json4s M5-SNAPSHOT has Jackson 3; watch for M5 stable tag) |
| **R5** ✅ | EventStream pub/sub topology map — `eventstream-topology.md`; 8 sites / 2 event types / 1 consumer; all Typed already; 2 × `Topic[T]` migration ready; 7b UNBLOCKED |
| **R8** ✅ | Memory / resource retention audit — `memory-leak-audit.md`; 4H/4M/3L; H2+H3 fix-now (StdNode.shutdown), H4 DAG stream leak, H1 BEACON-gated; L1/L2 SNAP sprint, L3 NET sprint |
| **R9** ✅ | IO threading model audit — `threading-model-audit.md`; overall MEDIUM risk; A1 (EngineApiService Await on CE3 compute — fix-now, BEACON gate) + B1 (EC.global in JsonRpcBaseController — defer) + B2 (PoWMiningCoordinator — FORGE gate, CHASE-QUEUE) |

---

## Clearout Prompts — Done Rows

| # | Batch | What | Status |
|---|-------|------|--------|
| ~~A3~~ | ~~Batch A~~ | ~~P4 PRISM dead code audit~~ | ✅ DONE 2026-06-22 — 4 items in CHASE-QUEUE |
| ~~A4~~ | ~~Batch A~~ | ~~P6 EYE Thread.sleep audit~~ | ✅ DONE 2026-06-22 — 2 pre-existing (both NECESSARY) |
| ~~B4~~ | ~~Batch B step 4~~ | ~~P5 MITHRIL scalafmt config~~ | ✅ DONE 2026-06-22 — `34a55a025` — deferred settings documented |
| ~~C1~~ | ~~Batch C step 1~~ | ~~P1 MITHRIL isInstanceOf (83 instances)~~ | ✅ DONE 2026-06-22 — 1 site fixed (`7cc9eda3a`) |
| ~~C2~~ | ~~Batch C step 2~~ | ~~P2 MITHRIL enum candidates~~ | ✅ DONE 2026-06-22 — 4 types converted (`b305ef41b`) |
| ~~C3~~ | ~~Batch C step 3~~ | ~~P3 MITHRIL console→logging (28 sites)~~ | ✅ DONE 2026-06-22 — 12 sites fixed |
| ~~C4~~ | ~~Batch C step 4~~ | ~~P4 MITHRIL/EYE E165 sprint — expectMsgType[Any]~~ | ✅ DONE 2026-06-22 — `8cdf1290d` — 20 sites → 0 |
| ~~D3~~ | ~~Batch D~~ | ~~P7 EYE test timing audit~~ | ✅ DONE 2026-06-22 — 680s (11m 20s) baseline, 3,595 tests |
| ~~E3~~ | ~~Batch E~~ | ~~§3h — Any type signature cleanup~~ | ✅ DONE 2026-06-22 |
| ~~E4~~ | ~~Batch E~~ | ~~§8a-retro batch 3 — 25 network/sync specs~~ | ✅ DONE 2026-06-23 — `12c23cf8a` + `a719520db` |
| ~~E5~~ | ~~Batch E~~ | ~~§8a-retro batch 4 — 14 coordinator/heal specs~~ | ✅ DONE 2026-06-23 — `5eae34c21` |
| ~~E5b~~ | ~~Batch E~~ | ~~§8a-infra — create `application-test.conf`~~ | ✅ DONE 2026-06-23 — `8b9bef67d` |
| ~~E5c~~ | ~~Batch E~~ | ~~§8a-infra-b — audit + fix worker teardown leaks~~ | ✅ DONE 2026-06-23 — `781c8e985` |
| ~~E5d~~ | ~~Batch E~~ | ~~§8a-retro batch 4b — E165 TestProbe narrowing~~ | ✅ DONE 2026-06-23 — `a193bc794` |
| ~~E5e~~ | ~~Batch E~~ | ~~§8a-infra-c — MITHRIL: replace classic actorSelection~~ | ✅ DONE 2026-06-23 — `5f28e8ae6` |
| ~~F1~~ | ~~Batch F~~ | ~~§3i MITHRIL+FORGE — BlockExecutionError hierarchy redesign~~ | ✅ DONE 2026-06-23 — `64ab4786e` |

---

## Run-Order Table — Done Rows

| # | Batch | Prompt | Status |
|---|-------|--------|--------|
| ~~E1~~ | ~~Batch E~~ | ~~P8 EYE SyncTest tag audit~~ | ✅ DONE 2026-06-23 — 40 rescued, 36 kept SyncTest, `3aef474a9` |
| ~~E2~~ | ~~Batch E~~ | ~~P9 EYE/MITHRIL DisabledTest audit~~ | ✅ DONE 2026-06-23 — `86c76fd4e` — 2 fixed, 7 deferred |
| ~~E3~~ | ~~Batch E~~ | ~~P10 EYE/MITHRIL FlakyTest root cause~~ | ✅ DONE 2026-06-23 — `ab98f1370` — 11 de-tagged, 2 deleted |
| ~~E4~~ | ~~Batch E~~ | ~~P11 testStandard baseline + SlowTest audit~~ | ✅ DONE 2026-06-23 — 961s/3,579 tests; 6 SlowTest→UnitTest `edfb69f35` |

P8 — EYE: SyncTest tag audit — DONE — see completed/DEFERRED-BACKLOG.md
P9 — EYE/MITHRIL: DisabledTest audit — DONE — see completed/DEFERRED-BACKLOG.md
P10 — EYE/MITHRIL: FlakyTest root cause audit — DONE — see completed/DEFERRED-BACKLOG.md

~~### P6 — EYE: Thread.sleep audit~~ ✅ DONE 2026-06-22

**Result:** 2 pre-existing sites found — `EthMiningServiceSpec.scala:302` (timeout window advance, NECESSARY) and `SubscriptionManagerSpec.scala:249` (topic propagation wait, NECESSARY). Neither is flaky. No CHASE-QUEUE entries needed. Part 8j baseline: 2 sites, both intentional.

---

## Part 11 Run-Order — Done Rows

| # | Batch | Prompt | Status |
|---|-------|--------|--------|
| ~~E1~~ | ~~Batch E~~ | ~~P8 EYE SyncTest tag audit~~ | ✅ DONE 2026-06-23 — 40 rescued (15 RetryStrategy + 7 PeersClient + 6 Blacklist + 12 BlockchainHostActor), 36 kept SyncTest, `3aef474a9` |
| ~~E2~~ | ~~Batch E~~ | ~~P9 EYE/MITHRIL DisabledTest audit~~ | ✅ DONE 2026-06-23 — `86c76fd4e` — 2 fixed, 7 deferred (F6 CODEBASE-AUDIT) |
| ~~E3~~ | ~~Batch E~~ | ~~P10 EYE/MITHRIL FlakyTest root cause~~ | ✅ DONE 2026-06-23 — `ab98f1370` — 11 de-tagged, 2 deleted (F7 CODEBASE-AUDIT) |
| ~~E4~~ | ~~Batch E~~ | ~~P11 testStandard baseline + SlowTest audit~~ | ✅ DONE 2026-06-23 — 961s/3,579 tests; 6 SlowTest→UnitTest `edfb69f35`; 2 failures: DNS flaky (Mordor DNS) + BHA pre-existing (fixed `07e5d505f`) |
| ~~E5~~ | ~~Batch E~~ | ~~P12 Tag taxonomy + build target architecture review~~ | ✅ DONE 2026-06-24 — `55361ea6f` (build.sbt + Tags.scala) · `deb421392` (docs clearout) |

---

## Part 11b — Docs: migrate `fukuii-test-timing.md` → `test-quality-log.md` — COMPLETE 2026-06-23

`test-quality-log.md` created at `.local/docs/`, all content migrated, old file deleted,
DEFERRED-BACKLOG references updated, MEMORY.md + memory file renamed.

---

## Part 12 — P12: Tag taxonomy + build target architecture review ✅ DONE 2026-06-24

**Commits:** `55361ea6f` (build.sbt + Tags.scala) · `deb421392` (CODEBASE-AUDIT clearout + docs)

**Outcome:**
- **5 new `addCommandAlias` targets added to `build.sbt`:** `testConsensus` (284), `testRPC` (219), `testOlympia` (201), `testState` (63), `testSync` (84) — all met the ≥3 test threshold.
- **15 dead tag definitions removed from `Tags.scala`:** all 12 fork-specific tags (Homestead→Spiral), all 3 environment tags (MainNet/PrivNet/PrivNetNoMining), FastTest. `StressTest` and `ManualTest` marked "reserved for future use."
- **Workaround exclusions removed** (P8+P10 confirmed complete, FlakyTest=0, DisabledTest=0):
  - Global `(Test/testOptions)`: removed `-l FlakyTest` and `-l DisabledTest`
  - `testEssential`: now `-l SlowTest -l IntegrationTest` only
  - `testStandard`: now `-l BenchmarkTest -l EthereumTest` only
  - `testComprehensive`: bare `testOnly` + `IntegrationTest/testOnly` (no exclusions)
- **`-l SyncTest` removed** from all tiers — all 84 SyncTest tests carry `(UnitTest, SyncTest)` so they are correctly included via `UnitTest` in `testEssential`.
- **`test-tag-taxonomy.md`** written at `.local/docs/` — authoritative per-tag reference.
- **Verification:** `sbt compile-all` clean; `sbt testConsensus` 284 tests; `sbt testEssential` count increased as expected.

---

## §8e-FORGE — FORGE: consensus `return` → expression ✅ DONE 2026-06-24

**Commits:** `4544b8025` (code — 6 files, +229/-198) · `a44fc2a98` (docs clearout)

**Outcome:** 6 CLEAR (converted to `if/else`/`match`) + 9 DEFER (`// scalafix:ok DisableSyntax.return` with rationale). All 6 files resolved at the ratchet level — `sbt scalafixAll` sees 0 violations across these files.

| File | Sites | Decision | Notes |
|------|-------|----------|-------|
| `vm/VM.scala` | 1 (line 140) | DEFER | Early exit before tracer `onCallExit`; converting fires callback in abort case — observable behaviour change |
| `vm/OpCode.scala` | 1 (line 989) | CLEAR | Pure guard clause feeding tail expression; byte-identical |
| `vm/PrecompiledContracts.scala` | 7 real + 1 already suppressed | All DEFER | EIP-2537 BLS / EIP-4844 KZG crypto primitives; `try`-nested returns; precompile result flow. Conversion requires restructuring — byte-level risky for precompile result |
| `ledger/BlockPreparator.scala` | 3 (lines 56, 89, 733) | All CLEAR | Simple guard→if/else: ECIP-1017 rewards, ECIP-1111 treasury credit, chain-id auth check |
| `mpt/StackTrie.scala` | 4 (lines 223, 381 CLEAR · 120, 462 DEFER) | 2 CLEAR / 2 DEFER | DEFER: `return node` mixed with in-place mutation in MPT write path (state-root); `return` inside `while` loop comparator (key sort order) |
| `consensus/validators/std/StdSignedTransactionValidator.scala` | 2 (lines 65, 67) | All CLEAR | Sequential `Either`-returning guards → `if … else if … else { stx.tx match }`; byte-identical |

**Correction:** A prior archive stub (FORGE 2026-06-24 pre-run) incorrectly assessed `ledger/BlockPreparator.scala` and `mpt/StackTrie.scala` as already CLEAR (0 returns). Both had real `return` statements (3 and 4 respectively). The FORGE execution corrected this.

**Suppression mechanism note:** `// scalafix:ok DisableSyntax.return` is used — NOT `@nowarn`. `@nowarn` silences the Scala compiler, not scalafix. The correct per-site scalafix suppression is `// scalafix:ok <RuleName>`, matching the existing pattern at `PrecompiledContracts.scala:573`.

**Summary:** 2/6 files already clear (BlockPreparator, StackTrie). 4/6 files have 11 remaining real `return` sites. `JwtAuthenticator.scala` cleared in S3-C (pre-existing). Full FORGE instruction prompt preserved in git history via the §8e-FORGE section prior to this archive commit.

---

## §8e-BEACON — EngineApiController `return` → expression ✅ DONE 2026-06-24

**Commits:** `d78177bda` (code) · `de4f489b5` (docs clearout)

**Outcome:** 3 `return` sites cleared in `consensus/engine/EngineApiController.scala`. Task scoped 2 sites; a third pre-existing `return` in the priority-fee helper was also cleared as required to satisfy the `DisableSyntax.noReturns = true` ratchet lock.

| Site | Method | Conversion | Notes |
|------|--------|-----------|-------|
| `:96` | `handleNewPayload` | `return IO.pure(errResp)` → `decode match { case Left(e) => IO.pure(errResp); case Right(params) => <body> }` | Byte-identical error response: `PayloadStatusV1(Invalid, None, "malformed payload: $msg")` |
| `:226` | `handleForkchoiceUpdated` | Same pattern; tuple destructured in `Right((fcs, payloadAttrs))` pattern, eliminating `.toOption.get` | Byte-identical error response: JSON-RPC `-38003` code |
| `:447` | Priority-fee helper | `if receipts.isEmpty then return "0x0"` → `if/else` expression | Pure hex-string builder; zero consensus-logic change |

**Verify:** `grep -n "\breturn\b" EngineApiController.scala` → 0 code-level hits (7 English-word matches in comments/strings only). `sbt "testOnly *EngineApi*"` → 16/16 ✅. `sbt compile-all` → 0 errors.

---

## §8e-StackTrie — StackTrie DEFER re-assessment ✅ DONE 2026-06-24

**Commits:** `09307c5a7` (code) · docs clearout in this commit

**Outcome:** Both `// scalafix:ok DisableSyntax.return` DEFER sites from §8e-FORGE (`4544b8025`) re-assessed and cleared. `StackTrie.scala` is now fully return-free with no suppressions.

| Site | Method | Decision | Conversion |
|------|--------|----------|-----------|
| `:120` | `insert` Leaf exact-match | **CLEAR** | The `return node` short-circuited past a `throw` in the same scope — never fell through. Restructured inner `if/throw` into `if (exact) node else throw`, merged outer `if diff >= origKey.length` block into the existing `if/else-if/else` chain. Whole Leaf case is now a single expression. `node.value = value` mutation unchanged; byte-identical. |
| `:462` | `byteCompare` | **CLEAR** | Rewrote `return`-in-`while` as `var result` accumulator with loop guard `while result == 0 && i < n`. Final expression `if result != 0 then result else Integer.compare(...)`. Pure comparator; ordering semantics identical (first differing byte wins, else length). |

**Verify:** `grep -n "return\|scalafix" StackTrie.scala` → 0 code-level hits. `sbt compile-all` → 0 errors. See `modernization-log/core/mpt.md §8e-FORGE` for updated site-by-site log.

---

## §8d-A1 — EngineApiService `Await.result` on CE3 compute thread ✅ DONE 2026-06-24

**Resolution:** Verified as already fixed — no code change needed.

`EngineApiService.scala` contains zero `Await.result` calls. The pending-transaction fetch in
`forkchoiceUpdated` uses `IO.fromFuture` (lines 629–640), with a source comment confirming
the intent:
```
// Fetch pending transactions from the tx pool using IO.fromFuture so the
// CE3 compute thread is not blocked waiting for the actor response.
```

The fix predates the backlog entry (threading-model-audit.md, 2026-06-21). No BEACON review
required; the only threading change is in the IO bridge, not in consensus logic.

**B1+B2 context:** B1 (`actorSystem.dispatcher` EC) and B2 (additional IO boundary scan) were
cleared in earlier sessions. A1 completes the §8d trilogy.

---

## §8d-J — CONDUIT: jsonrpc IO boundary fixes ✅ DONE 2026-06-24

**Source:** §8d CONDUIT scan (2026-06-24). Three sites found uncovered after B1/B2/A1 closure.

### §8d-J1 — AdminService IO.blocking ✅ DONE 2026-06-24

**File:** `src/main/scala/com/chipprbots/ethereum/jsonrpc/AdminService.scala` lines 335–361
**Severity:** MEDIUM — long chain export/import parked a CE3 compute thread
**Fix:** `IO { ... }` → `IO.blocking { ... }` at both `FileOutputStream` write loop and `FileInputStream` read loop call sites. Shifts execution to the CE3 blocking pool, releasing the compute thread for the duration of file operations.
**Effort:** TRIVIAL (2-site keyword replacement, 1 file)

### §8d-J2 — GraphQLSchema unsafeRunSync in Sangria resolver ✅ DONE 2026-06-24

**File:** `src/main/scala/com/chipprbots/ethereum/jsonrpc/graphql/GraphQLSchema.scala` line 992
**Severity:** HIGH — `.unsafeRunSync()` parked a Pekko-HTTP/Sangria dispatcher thread
**Fix:** Composed both IO operations in IO context before the single `.unsafeToFuture()` at the resolver boundary. Eliminated synchronous materialisation inside the `Future`/`flatMap` body.
**Effort:** SMALL (1 file, resolver re-composition)

### §8d-J3 — JsonRpcIpcServer IORuntime scoping ✅ DONE 2026-06-24

**File:** `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/ipc/JsonRpcIpcServer.scala` line 102
**Severity:** MEDIUM — `responseF.unsafeRunTimed(awaitTimeout)` used `IORuntime.global` on per-connection `ClientThread`; runtime shared across HTTP/GraphQL/IPC paths
**Fix:** Replaced `unsafeRunTimed` with `IO.timeout(awaitTimeout)` + `unsafeRunSync()` — timeout modelled in IO, materialisation explicit. IORuntime contention eliminated on the IPC path.
**Effort:** SMALL (1 file, IO composition change)

---

## §8l-R1 — FORGE: VM tracer model research + spec verdict ✅ DONE 2026-06-24

**Commits:** `37c9d081b` (`.local/docs/vm-tracer-model.md`) · `5c2adeaaf` (DEFERRED-BACKLOG update)

**Spec verdict: SHOULD_FIRE**

`VM.create()` fires `onCallEnter` unconditionally for sub-creates (VM.scala:126-129) before the
EIP-3860 initcode-too-large check, then early-`return`s at line 143 without a matching
`onCallExit` (VM.scala:206-209). `CallTracer` and `VmTracer` treat enter/exit as a balanced
push/pop — the missing exit leaves a dangling frame that corrupts the trace tree.

The `scalafix:ok DisableSyntax.return` suppression at VM.scala:143 is **incorrect**. The early
`return` preserves the unbalanced emission; the suppression marks the symptom, not the fix.

**Reference client note:** core-geth fires *neither* `CaptureEnter` nor `CaptureExit` for
initcode-too-large because EIP-3860 is enforced in the parent opcode's dynamic-gas stage
(`gasCreateEip3860`, `gas_table.go:324`) before `evm.create()`'s deferred `captureBegin`/
`captureEnd` are reached. Fukuii already emits the enter — balancing it with an exit is the
minimal correct fix, not skipping both.

**VMTracer type:** `trait ExecutionTracer` — plain Scala 3 callback interface with default no-op
methods (modelled on Besu's `OperationTracer`). Threaded as `Option[ExecutionTracer]` constructor
param on `VM[W,S]`. Synchronous, not an actor. Four concrete impls: `StructLogTracer`,
`CallTracer`, `VmTracer`, `PrestateTracer`.

**Modernisation:** No structural change needed — not a LOOM/Pekko Typed candidate. Fix is purely
at the emission site in §8l-I.

**Next step:** §8l-I — implement balanced enter/exit in `VM.create()` (open in working-docs).

---

## §8c-M4 — VAULT: DataSource close cache invalidation ✅ DONE 2026-06-24 (by-design)

**Commit:** `07db4e902`

**Verdict: by-design — no code logic change.**

`RocksDbDataSource.close()` does not call `cache.invalidateAll()` and should not. The overlay
caches (`LruCache` in `CachedReferenceCountedStateStorage`, `MapCache` in `CachedNodeStorage`)
are owned by `DefaultStorages` — one abstraction tier above `DataSource`. Inserting cache
invalidation into `close()` would require `RocksDbDataSource` to depend upward on the storage
layer, inverting the layering. The `Cache` trait is not part of the `DataSource` contract.

The stale-cache scenario is real but narrowly scoped: only when a test calls
`dataSource.clear()` while a `CachedNodeStorage`/`CachedReferenceCountedStateStorage` backed
by that source is still alive. Production nodes never re-open a closed DB in the same JVM.
The fix belongs at the test fixture level: `afterEach { cache.clear(); dataSource.clear() }`.

**Changes:**
- `RocksDbDataSource.scala` — Scaladoc comment on `close()` explaining the layering rationale.
- `storage-rocksdb.md` — "DataSource close protocol" note added under Quality Findings.

---

## §8l-I — FORGE: VM.create() tracer balance fix ✅ DONE 2026-06-24

**Commit:** `bda0228a4`
**Gate:** §8l-R1 (done)
**Agent:** FORGE + BEACON sign-off (consensus-adjacent; tracer output only)
**Risk:** LOW — `onCallExit` is an observability hook; no gas/state-root/RLP/hash impact

**What was fixed:** `VM.create()` (VM.scala) emitted `onCallEnter` unconditionally before the
EIP-3860 initcode-too-large check, then early-`return`ed before the trailing `onCallExit` block,
leaving a dangling frame in `CallTracer`/`VmTracer` push/pop stacks. The `// scalafix:ok
DisableSyntax.return` suppression added in §8e-FORGE (`4544b8025`) masked the bug.

**Fix:** Converted the EIP-3860 abort arm from an early `return` to an expression arm so the
abort tuple flows through the trailing `tracer.foreach(_.onCallExit(...))` block. The
`scalafix:ok` suppression and associated DEFER comment at VM.scala:140-143 were removed.

**Tests added:** 2 regression tests in `CallTracerSpec`:
- Balanced frame assertion: one `onCallEnter` push / one `onCallExit` pop per failed CREATE
- Abort appears in parent `calls` with `InitCodeSizeLimit` error; no orphaned frame on the stack

**Verification:**
- `sbt "testOnly *CallTracer*"` — 10/10 PASS
- `sbt "testOnly *DebugTracingService*"` — 9/9 PASS
- BEACON sign-off: SAFE for ETH/Sepolia (tracer callback only; no consensus-result change)
- `grep -n "scalafix:ok" VM.scala` — suppression at former `:143` absent; remaining suppressions
  in `PrecompiledContracts.scala` (KZG/BLS/MODEXP crypto, unchanged) are unaffected

---

## §ETH-T3-LOG — Thread 3 treasury-zero log.error gate fix ✅ DONE 2026-06-24

**Commit:** `f868b75a8`
**File:** `ledger/BlockPreparator.scala`
**Source:** ETH/Sepolia assumption audit Thread 3 (EIP-1559 fee routing)

**Finding:** `BlockPreparator.creditBaseFeeToTreasury` correctly skips the treasury credit for
ETH/Sepolia (treasury-address = 0 in both chain configs = "burn" path). However the surrounding
`log.error` checking `treasuryAddress == Address(0)` fired unconditionally for every ETH/Sepolia
block since zero-treasury is the intended configuration, not a misconfiguration:
- Sepolia: `olympia-block-number=0` → active from genesis → error on every block
- ETH mainnet: `olympia-block-number=12965000` → active for all Engine API blocks (post-Merge)

**State transition was correct; only the log alarm was wrong.**

**Fix:** Added `&& blockchainConfig.networkType == com.chipprbots.ethereum.utils.NetworkType.ETC`
guard to the error branch. ETC chains with Olympia active but treasury-address=0 still get the
error (genuine misconfiguration). ETH/Sepolia chains are silent.

**Cross-refs:** `storage/ledger.md §ETH-T3-LOG`, `working-docs/DEFERRED-BACKLOG.md Part 10`

---

## §9b — RegularSync Divergence-Path Spec Fix ✅ DONE 2026-06-24

**Commits:** `0d290019e` (resolvingFork + FSBA wiring) · `69146a244` (divergence-path test)
**Gate:** §8k-F (`b24515637`, RegularSync Typed) — was the blocker.

**Context:** CHASE-QUEUE "RegularSync divergence path EXCEPT" — `BlockImporter.handleForkRecovery`
performs a blind 128-block rewind with no LCA. MESS makes >128-block forks near-impossible on ETC
mainnet, so this was latent-correctness risk. Gate was §8k-F.

**Completed work:**
- Items 1-3 (FSBA spawn + `resolvingFork` behavior + `blindRewind` fallback) — already in `0d290019e`
- Item 4 (divergence-path test) — written 2026-06-24: `"rewind canonical chain to resolver LCA on BranchResolvedSuccessful (divergence path)"` in `RegularSyncSpec.scala` (96 lines, `UnitTest + SyncTest`)
  - Spawns standalone `BlockImporter` via `PropsAdapter`
  - Sends `StartForkRecovery(BigInt(15))` then `BranchResolverMsg(BranchResolvedSuccessful(lca=10, peer))`
  - Asserts `InvalidateBlocksFrom(lca+1)` to fetcher and `setCanonicalChainHead(lca, ...)` via ScalaMock verify

**Side-finding:** Exposed 4 `testCaseT` status tests failing — root cause and fix in §9d.

**Verification:** `sbt compile-all` — 0 errors. `testOnly *RegularSyncSpec*` — 34 tests / 30 pass
(4 pre-existing status failures from §9d, resolved after §9d fix → 34/34).

---

## §9d — RegularSyncFixtures `getSyncStatus` Classic ask → Typed send ✅ DONE 2026-06-24

**Commit:** `69146a244`

**Root cause:** `RegularSyncFixtures.getSyncStatus` used the Classic `?` ask:
```scala
IO.fromFuture(IO((regularSync ? SyncProtocol.GetStatus).mapTo[SyncProtocol.Status]))
```
`SyncProtocol.GetStatus` is `final case class GetStatus(replyTo: TypedActorRef[Status])`. The `?`
operator passes the companion object (not an instance with `replyTo` set) and injects a Classic
`sender()` temp actor as the implicit reply address. The `Behavior[RegularSyncCommand]` handler
(`RegularSync.scala:154`) reads `msg.replyTo` — which is uninitialised — causing a
`ClassCastException` at runtime. 4 `testCaseT`-based status tests failed silently.

**Fix:**
```scala
val getSyncStatus: IO[SyncProtocol.Status] =
  IO {
    val probe = TestProbe()
    regularSync ! SyncProtocol.GetStatus(probe.ref.toTyped[SyncProtocol.Status])
    probe.expectMsgType[SyncProtocol.Status]
  }
```
Also removed `import org.apache.pekko.pattern.ask`, `import org.apache.pekko.util.Timeout`, and
the implicit `Timeout` value (all existed solely for the `?` pattern).

**Note:** `IO { ... }` (not `IO.fromFuture`) — `expectMsgType` is blocking-synchronous, so the
wrapping is `IO[Status]` directly, not `IO[Future[Status]]`.

**Verification:** `sbt compile-all` — 0 errors. `testOnly *RegularSyncSpec*` — **34/34 pass**
(was 30/34). `sbt scalafmtAll` — no reformats needed.

---

## §8k-G3 — Per-child typed messageAdapters ✅ DONE 2026-06-24

**Commit:** `a8cea433c`
**Agent:** MITHRIL
**Risk:** LOW — mechanical type substitution; behavior unchanged (WrappedExternal dispatch in SyncController.unwrap() unmodified)

**What was done:** SyncController's single universal `externalAdapter: TypedActorRef[Any]` replaced with per-child narrow adapters. Each child's constructor param narrowed from `ActorRef[Any]` to the specific type it actually sends.

**Child → adapter type mapping:**

| Child | Adapter type | Notes |
|-------|-------------|-------|
| `BytecodeRecoveryActor` | `TypedActorRef[RecoveryComplete.type]` | — |
| `StorageRecoveryActor` | `TypedActorRef[StorageRecoveryActor.SyncControllerMsg]` | Added `sealed trait SyncControllerMsg`; `RecoveryComplete` + `RequestRecentRoot` extend it |
| `CombinedRecoveryScanActor` | `TypedActorRef[CombinedScanComplete]` | — |
| `PivotHeaderBootstrap` | `TypedActorRef[PivotHeaderBootstrap.Reply]` | Added `sealed trait Reply`; `Completed` + `Failed` extend it |
| `FastSync` | `TypedActorRef[fast.FastSync.SyncControllerMsg]` | Added `sealed trait SyncControllerMsg`; `FallbackToSnapSync` + `Done` extend it |
| `ChainDownloader` | `TypedActorRef[snap.ChainDownloader.Done.type]` | — |
| `SNAPSyncController` | `TypedActorRef[SyncProtocol.SyncControllerReply]` | Unsealed marker trait (cross-file hierarchy); `SyncProtocol.HealingImpossible` and 6 SSC companion types all extend it. Completed in §8k-G3-SSC. |

**Files modified:** `SyncController.scala`, `BytecodeRecoveryActor.scala`, `StorageRecoveryActor.scala`, `CombinedRecoveryScanActor.scala`, `PivotHeaderBootstrap.scala`, `FastSync.scala`, `ChainDownloader.scala`

---

## §8k-G3-SSC — SNAPSyncController syncController param typed via SyncControllerReply ✅ DONE 2026-06-24

**Commit:** `79068ad11`
**Agent:** MITHRIL
**Risk:** LOW — marker trait + `extends` clauses only; SyncController.unwrap() dispatch unchanged

**Why deferred from §8k-G3:** SSC sends 7 types to syncController; one (`SyncProtocol.HealingImpossible`) is defined in a different package's companion. A `sealed trait` in SSC.scala cannot be extended from SyncProtocol.scala (sealed = same file in Scala 3), requiring an unsealed marker trait instead.

**What was done:**
- `SyncProtocol.scala` — added `trait SyncControllerReply` (unsealed); `HealingImpossible` now `extends SyncProtocolMsg with SyncControllerReply`
- `SNAPSyncController.scala` — 6 companion types (`Done`, `StartRegularSyncBootstrap`, `StartRegularSyncBootstrapByHash`, `FallbackToFastSync`, `SnapSyncFinalized`, `RequestHealingServeRoot`) all extend `SyncProtocol.SyncControllerReply`; both constructor sites (`apply` factory + `Impl` class) changed from `TypedActorRef[Any]` → `TypedActorRef[SyncProtocol.SyncControllerReply]`
- `SyncController.scala` — added `snapAdapter: TypedActorRef[SyncProtocol.SyncControllerReply]` via `ctx.messageAdapter`; SSC spawn site uses it instead of `externalAdapter`. `externalAdapter` retained — 8 other consumers remain (FCM, NPMA paths, healing replyTo paths); removal gated on §8k-G4.

**End state:** 0 `ActorRef[Any]` hits in production code under the sync package. All remaining hits are in comments.

---

## §9c — RegularSyncSpec Full Migration ✅ DONE 2026-06-24

**Commit:** `57d638d49`
**Agent:** LOOM
**Gate:** §8k-F (`b24515637` — `RegularSync.scala` fully Typed)

**Root cause of deferral:** `RegularSyncSpec` used a `Resource[IO, ActorSystem]` lifecycle (Cats Effect `ResourceFixtures` / `AsyncWordSpec`) with a shared Classic `ActorSystem` created in `beforeEach` and torn down in `afterEach`. `ScalaTestWithActorTestKit` extends the synchronous `TestSuite` and conflicts with `AsyncWordSpecLike` — mixing it in registered 0 tests. The correct approach is to own `ActorTestKit` directly without the ScalaTest base trait.

**Fix:** Per-test `ActorTestKit` owned by each fixture instance.

- `RegularSyncFixtures.scala`: Each fixture creates its own `ActorTestKit()` internally. `system: ActorSystem` derives from `testKit.system.classicSystem`. Dropped `_system: ActorSystem` constructor param from `RegularSyncFixture`, `OnTopFixture`, and `MissingStateNodeFixture` (was marked `@scala.annotation.unused` in an intermediate step — removed properly). Added `shutdownFixture()` method; `Resource.make(...)` callers use it for teardown.
- `RegularSyncSpec.scala`: Removed `beforeEach`/`afterEach` + `var testSystem`. Dropped `import org.apache.pekko.actor.ActorSystem`. Spawn sites use `testKit.spawn`; stop sites use `testKit.stop` (not `system.stop` — the latter sends `StopChild` to the guardian → `ClassCastException`). Updated 28 call sites: `new Fixture(testSystem)` → `new Fixture`.

**Key pitfall (§8a-retro batch 4):** `system.stop(importer)` on a testKit-guardian child sends `StopChild` to the guardian → `ClassCastException` + whole-system crash. Use `testKit.stop(importer)` instead.

**Step 13 (pre-migration-checklist.md):** `grep -n "ActorRef\b" RegularSyncSpec.scala | grep -v "typed\.\|ActorRef\["` — all 17 hits are Classic `AutoPilot.run(sender: ActorRef, ...)` signatures (load-bearing) and typed `ActorRef[T]` refs. Zero spawn-site slippage.

**Opportunistic — SyncProtocol.Status enum candidacy:** `sealed trait Status` with `case object NotSyncing`, `case object SyncDone`, and `case class Syncing(...)`. Mixed payload/singleton ADT — not a clean enum candidate (parameterised case). Weak candidate only; deferred.

**Verification:** `sbt "testOnly *RegularSyncSpec"` — **34/34 pass** (two consecutive runs, no guardian crash). `sbt compile-all` — 0 errors. `sbt scalafmtAll` — clean.

---

## §8k-G4 — Typed adapter narrowing + externalAdapter removal ✅ DONE 2026-06-24

Six sub-tasks narrowed every remaining `externalAdapter` consumer in `SyncController` to a typed per-child
adapter; the final task deleted `externalAdapter` itself. Together with §8k-G3 + §8k-G3-SSC, this closes
Cluster E entirely — zero `TypedActorRef[Any]` in production sync-package code, every child on a narrow
typed interface. Full Scala 3.3.8 + Pekko 1.6 Typed discipline in the sync layer.

### §8k-G4a + §8k-G4b — FCM.setListener + NPMA RegisterChainWeightCalibrationTarget

**Commit:** `8c23a294e` — "refactor(8k-G4a,4b): type FCM and NPMA calibration listeners via narrow TypedActorRef adapters"

**G4a — ForkChoiceManager.setListener:**
- `ForkChoiceManager.scala` — `listenerRef` type changed from `AtomicReference[Option[ActorRef]]` → `AtomicReference[Option[TypedActorRef[ForkChoiceManager.BeaconHead]]]`; `setListener` param changed accordingly.
- `SyncController.scala` — `fcm.setListener(externalAdapter.toClassic)` replaced with `fcmAdapter` via `ctx.messageAdapter[ForkChoiceManager.BeaconHead](WrappedExternal.apply)`.

**G4b — NPMA RegisterChainWeightCalibrationTarget:**
- `NetworkPeerManagerActor.scala` — `RegisterChainWeightCalibrationTarget.target` + `RegisterChainWeightCalibrationTargetCmd.target` + `chainWeightCalibrationTarget` var all changed from `ActorRef` → `TypedActorRef[SyncProtocol.CalibrateChainWeightFromPeer]`. Classic-shell forwarding path updated to preserve the typed ref.
- `SyncController.scala` — `cwAdapter` via `ctx.messageAdapter[SyncProtocol.CalibrateChainWeightFromPeer]` replaces `externalAdapter.toClassic` at the `RegisterChainWeightCalibrationTarget` call site.

---

### §8k-G4c — NPMA RegisterSnapSyncController relay

**Commit:** `0cd0a48a9` — "fix(8k-G4c): wire SNAP response relay through SyncController during recovery"

**What was found:** Investigation revealed that in the recovery state-machine, `SyncController` passes its own adapter as the `snapSyncController` to NPMA intentionally — NPMA routes incoming SNAP protocol responses (`AccountRangeResponse`, `ByteCodesResponse`, etc.) back through `SyncController`'s `WrappedExternal` dispatch during the window before a `SNAPSyncController` is spawned.

**Fix:** Replaced `externalAdapter.toClassic` with a typed `snapRelayAdapter: TypedActorRef[SNAPSyncController.Command]` via `ctx.messageAdapter`. Updated NPMA's `RegisterSnapSyncController` field to accept `TypedActorRef[SNAPSyncController.Command]` directly.

---

### §8k-G4c-extended — CalibrateChainWeightNow vs CalibrateChainWeightNowCmd

**Commit:** `b38c3197d` — "fix(8k-G4c-ext): SyncController sends CalibrateChainWeightNowCmd not Classic-shell variant"

**What was found:** Surfaced by the G4b loom run. `SyncController` sent `CalibrateChainWeightNow` (Classic-shell non-Cmd variant) at two sites; NPMA's `handleMessages` only matches `CalibrateChainWeightNowCmd` (Typed Command). Both sends were silently dropped — the calibration round-trip never completed.

**Fix:** Two `CalibrateChainWeightNow(...)` send sites in `SyncController.scala` replaced with `CalibrateChainWeightNowCmd(...)`.

---

### §8k-G4d — GetHandshakedPeersCmd replyTo narrowed

**Commit:** `8227b84dd` — "refactor(sync): §8k-G4d narrow GetHandshakedPeersCmd replyTo to TypedActorRef[HandshakedPeers]"

**What was done:**
- `NetworkPeerManagerActor.scala` — `GetHandshakedPeersCmd.replyTo` changed from `TypedActorRef[Any]` → `TypedActorRef[NetworkPeerManagerActor.HandshakedPeers]`. Classic-shell variant updated to match.
- `SyncController.scala` — `handshakedPeersAdapter` via `ctx.messageAdapter[NetworkPeerManagerActor.HandshakedPeers]` replaces `externalAdapter` at all 3 `GetHandshakedPeersCmd` call sites (healing-serve-root path line ~687; recovery `runningRecovery` line ~2049; recovery `recentRootRequester` line ~2076).

---

### §8k-G4e + §8k-G4-FINAL — PivotHeaderBootstrap.replyTo narrowed + externalAdapter deleted

**Commit:** `c948937e5` — "refactor(8k-G4e-final+G4-FINAL): remove externalAdapter — replace recovery SNAP path + delete val"

**G4e — PivotHeaderBootstrap.replyTo:**
- `PivotHeaderBootstrap.scala` — `replyTo` parameter narrowed from `TypedActorRef[Any]` → `TypedActorRef[PivotHeaderBootstrap.PivotBootstrapReply]` (sealed trait `PivotBootstrapReply` confirmed/added covering `Completed` + `Failed`). Both constructor sites updated.
- `SyncController.scala` — `pivotBootstrapAdapter` via `ctx.messageAdapter[PivotHeaderBootstrap.PivotBootstrapReply]` replaces `replyTo = externalAdapter` at both spawn sites (healing-serve-root line ~767; recovery-recent-root line ~2158). The `.toClassic` on the spawned ref is retained — PHB is still a Classic actor; only the reply-target is now typed.

**G4-FINAL — externalAdapter deleted:**
- `SyncController.scala` — `externalAdapter: TypedActorRef[Any]` val declaration and its associated INFO comment block deleted. `WrappedExternal` case class retained (used by all per-child adapters). All per-child adapter vals retained.

**End state:** 0 `ActorRef[Any]` hits in production code under the sync package. 0 `externalAdapter` references in SyncController. Cluster E fully closed.

---

### §8k-M — PivotHeaderBootstrap Classic→Typed migration ✅ DONE (Group ROOT/CAPSTONE)

**Status:** Already complete at time of §8k-J audit — task created in error.

**What was found (2026-06-25):** `PivotHeaderBootstrap.scala` is a `Behavior[Command]` with sealed Command ADT, `Behaviors.withTimers`, explicit `replyTo: ActorRef[Reply]` (sealed `Reply` trait covering `Completed` + `Failed`), Typed AskPattern for `peersClient`, and SLF4J `asyncLog` for off-thread safety. The migration was done as part of Group ROOT/CAPSTONE, predating the §8k-J audit.

**§8k-J attribution correction:** The 10 SyncController `.toClassic.tell` bridges attributed to PHB at §8k-J were bridges to FastSync (1), SnapSync/SSC (4), RegularSync (3), and recovery actors (2). The 5 FastSync bridges attributed to PHB do not exist — FastSync has no PHB references. The `ctx.self.toClassic` reference in the original PHB doc comment described SyncController's own classic-bridge adapter, not PHB's Classic status.

**Caller state (verified):**
- `SyncController.scala` — spawns PHB via `ctx.spawn(PivotHeaderBootstrap(...))` (not `ctx.toClassic.actorOf`); holds `TypedActorRef[PivotHeaderBootstrap.Command]`; `pivotBootstrapAdapter = ctx.messageAdapter[PivotHeaderBootstrap.Reply]` at all spawn sites.
- `FastSync.scala` — no PHB references at all (`fastSyncClassicSelf` removed).

**Cross-refs:** `modernization-log/sync/controller.md §8k-M`, `working-docs/DEFERRED-BACKLOG.md §8k-J` (attribution corrected)

---

## §9a — SyncStartupStrategy extraction ✅ DONE 2026-06-24

**Commit:** `3140db465` — "feat(sync): §9a SyncStartupStrategy — selectSyncMode pure function + wiring"

**Context:** `AdaptiveSyncStrategy.scala` (193 lines) was deleted in Part 8f as unintegrated dead
code. The design intent was sound: `SyncController.start()` selects sync mode from static config
booleans with no peer pre-flight. With `doSnapSync=true` but < 3 SNAP-capable peers, SNAP fails N
times before the reactive fallback triggers. This task extracted the decision logic as a pure function.

**What was done:**
- Added `SyncMode` enum (`Snap`, `Fast`, `Regular`) to `SyncController` companion object — `private[sync]` for testability
- Added `selectSyncMode(peerCount, snapCapablePeers, latencyMs, config)` pure function — downgrades SNAP→Fast only when `peerCount > 0 && snapCapablePeers < 3`; with 0 peers (initial startup) stays optimistic
- Wired into `start()`: computes `snapEnabled`/`fastEnabled` from the function (called with `(0, 0, 0L)` at startup — no behavioral change today, structure in place for future live-peer call sites)
- 5-branch match updated to use `snapEnabled`/`fastEnabled` instead of raw config booleans
- Early checks (`clearDoneOnStart`, `isFastSyncCoolingOff`) still use original `doSnapSync`/`doFastSync` — correct, those are operator config flags
- `latencyMs` suppressed with `@annotation.nowarn` — reserved for future latency-based heuristics

**New test file:** `SyncStartupStrategySpec.scala` — 6 tests via `AnyFunSuite + TestSyncConfig`:
0 peers → Snap (optimistic), 1 snap peer → Fast (downgrade), 3 snap peers → Snap, majority → Snap, fast-only config → Fast, regular fallback → Regular. All pass.

---

## R10 — ETH/Sepolia Assumption Audit ✅ ALL THREADS COMPLETE 2026-06-24

**Research thread:** DEFERRED-BACKLOG.md `| **R10** ✅ ALL DONE |`
**Audit doc:** `.local/docs/eth-sepolia-assumption-audit.md` — status markers added to all 10 threads

10-thread systematic hunt for ETC-first design assumptions leaking into ETH/Sepolia code paths.

### Thread outcomes

| Thread | Result | Backlog items |
|--------|--------|---------------|
| T1 — Fork dispatch (`forBlock` vs `forTimestamp`) | 3 gaps | §ETH-T1-A/B/C |
| T2 — PoW/PoS divergence guards | All correct; 1 rename | §ETH-T2-A |
| T3 — EIP-1559 fee routing | Bug fixed `f868b75a8` | (none — fixed inline) |
| T4 — CL integration (withdrawals, blobs, KZG) | 4 gaps | §ETH-T4-A/B/C/D |
| T5 — Chain ID / networkId leakage | Zero leakage | (none) |
| T6 — VM tracer abort-path completeness | 0 unbalanced paths; 2 hardening | §ETH-T6-A/B |
| T7 — Test coverage ratio ETC vs ETH | 5 missing test specs | §ETH-T7-A/B/C/D/E |
| T8 — Sepolia config completeness | All correct; 1 doc fix | (none) |
| T9 — SNAP sync ETH path | 4 gaps (pivot validation, RLP, StorageScheme, startup gate) | §ETH-T9-A/B/C/D |
| T10 — Engine API Osaka edge cases | 4 gaps (V5/V4 methods, requests) | §ETH-T10-A/B/C/D |

### Total backlog items generated
21 items across T1–T10: §ETH-T1-A/B/C · T2-A · T4-A/B/C/D · T6-A/B · T7-A/B/C/D/E · T9-A/B/C/D · T10-A/B/C/D

### Severity summary
| Severity | Count | Items |
|----------|-------|-------|
| HIGH | 6 | T1-A, T1-B, T4-A, T4-B, T9-A, T9-B |
| MEDIUM | 4 | T1-C, T4-C, T4-D, T9-C |
| LOW | 7 | T2-A, T6-A, T6-B, T9-D, T10-C, T10-D + §ETH-T7 test specs |
| MISSING tests | 5 | T7-A/B/C/D/E |

### Key finding
No ETC chain-ID hardcoding leaks into ETH code paths (T5 clean). The highest-risk gap is
T4-A (KZG trusted setup never loaded — point-evaluation precompile silently accepts invalid
proofs on all Sepolia blocks containing blob transactions).

### Commit
`1a65e6f13` — docs(r10): Thread 9 SNAP ETH path audit — add §ETH-T9-A/B/C/D; mark T9+T10 complete in R10 row

---

### §ETH69-C — MITHRIL: BlockchainReader Tier3 rolling-median difficulty ✅ DONE (`2af49dcb1`)

**Commits:** `2af49dcb1` (code — 4 files) · clearout in same session

**What was done:**
Replaced the `rollingWindowDiff` 10K-block DB-lookup rolling average in `BlockchainReader.resolveETH69ChainWeight`
(Tier3 POW_SCALING) with a 1,000-block in-memory ring buffer + rolling-median.

- **`BlockchainReader.scala`**: Added `difficultyRingBuffer: ArrayDeque[BigInt]` (capacity 1,000),
  `recordBlockDifficulty(difficulty): Unit` (synchronized ring-buffer writer), and
  `rollingMedianDifficulty: Option[BigInt]` (synchronized; returns None until buffer is full;
  averages two middle elements for even-length arrays — exact mean under symmetric bimodal oscillation).
  Tier3 rate line changed from `rollingWindowDiff(head, ourBestTD)` to
  `rollingMedianDifficulty.orElse(bestHeaderOpt.map(_.difficulty)).getOrElse(BigInt(1))`.
  Dead code removed: `Tier3RollingWindow` constant + `rollingWindowDiff` private method.
- **`BlockExecution.scala`**: Hook added after `blockchain.saveBlockState(...)` — calls
  `blockchainReader.recordBlockDifficulty(blockToExecute.header.difficulty)` for live import.
- **`ChainImporter.scala`**: Hook added after `blockchainWriter.save(block, ...)` — calls
  `blockchainReader.recordBlockDifficulty(block.header.difficulty)` for offline/hive import.
- **`ETH69OscillationChainWeightSpec.scala`**: 2 new tests added (tag: UnitTest, NetworkTest):
  1. "reduce Tier3 estimate variance to < ±20% under ±50% oscillation" — anchorNum=100 so
     10K-block gap dominates; alternating 1500/4500 TH (1000 entries); asserts oldErr > 0.20
     and newErr < 0.20.
  2. "average out alternating high/low difficulty to the true midpoint" — asserts median of
     {500×2000 TH, 500×4000 TH} = 3000 TH exactly.

**Test result:** 15/15 pass (`testOnly *ETH69Oscillation*`). `sbt compile-all` clean. `sbt scalafmtAll` clean.

**Effect:** Tier3 estimate variance under ETC flex-load oscillation (symmetric ±50% swing) collapses
from ±50% (point-in-time head difficulty) to near-zero (true mean of the oscillation window).
Cold-start window (buffer < 1,000 entries) falls back to head.difficulty (prior behaviour).

---

### §ETH69-D — MITHRIL: Tier3 accuracy telemetry ✅ DONE

**Commits:** feature + clearout in same session (prior to §ETH69-E session)

**What was done:**
Added `ETH69_TIER3_ACCURACY` debug log in `NetworkPeerManagerActor.updateChainWeight` — fires on
every `ETHPackets.NewBlock` from an ETH69 peer, logging `prevTD`, `actualTD`, `delta`,
`deltaPercent` for post-hoc audit of Tier3 POW_SCALING accuracy.

**Files changed:** `NetworkPeerManagerActor.scala` (3 lines in `updateChainWeight` case branch).

**Test result:** `sbt compile-all` clean. No new tests (observability-only change).

---

### §ETH69-E — MITHRIL: Archive-node monotonic-guard exemption ✅ DONE (`60c9fd4e5`)

**Commits:** `60c9fd4e5` (code — 2 files) · `b3f91c6a2` (clearout)

**What was done:**
Static peers (archive nodes, non-miners) produce an inflated Tier3 POW_SCALING estimate at
handshake because `maxBlockNumber` never advances. The monotonic guard in `updateMaxBlock`
blocked the downward correction once the DB resolved the real TD.

**Mechanism:**
- Two new `mutable.Map` fields: `consecutiveUnchangedProbes: Map[PeerId, Int]` and
  `lastProbeMaxBlock: Map[PeerId, BigInt]`.
- In `RefreshPeerBestBlocksTick` handler: when `!recentlySignaled` and peer is ETH69, compare
  `peerInfo.maxBlockNumber` vs `lastProbeMaxBlock.get(peerId)`. If unchanged, increment counter;
  if advanced, `remove` (reset). `lastProbeMaxBlock` updated unconditionally.
- `updateMaxBlock` reads `isPeerStatic = consecutiveUnchangedProbes.getOrElse(peerId, 0) >= 3`.
  `shouldUpdate = (isImprovement || isPeerStatic) && source != "COLD_START"`.
- Cleanup on `PeerDisconnected`: both maps `remove(peerId)`.
- Constant: `private[network] val StaticPeerProbeThreshold: Int = 3`.

**Key design subtlety:**
Mining peers keep `lastBlockSignalMs` fresh via `BlockRangeUpdate`/`NewBlock` signals, which
causes each `RefreshPeerBestBlocksTick` to see `recentlySignaled = true` and skip the probe
entirely. The counter therefore never increments for active mining peers — suppression is the
primary guard, not counter-reset via advancing `maxBlockNumber`.

Archive peers send no signals → `recentlySignaled = false` → probes fire → no responses
between ticks → counter accumulates → after 3 unchanged probes → static → correction allowed.

**Files changed:**
- `NetworkPeerManagerActor.scala`: 2 new mutable.Map fields, counter logic in tick handler,
  `isPeerStatic` + `shouldUpdate` in `updateMaxBlock`, disconnect cleanup, constant.
- `NetworkPeerManagerSpec.scala`: `TestSetupWithReader` trait (data + factory methods),
  `setupPeerOnHolder` helper, 2 new tests (archive peer + mining peer).

**Tests added (NetworkPeerManagerSpec):**
1. `"ETH69 archive peer: correct inflated Tier3 chainWeight after 3 consecutive unchanged probes"`:
   4 ticks without responses accumulate counter to 3; single `BlockHeaders(archiveProbeBlock)`
   triggers DB_LOOKUP → `actualTD` (500) replaces `inflatedTD` (9999).
2. `"ETH69 mining peer: active block signal suppresses tick probes — monotonic guard stays active"`:
   Tick 1 seeds; `BlockRangeUpdate` sets `lastBlockSignalMs`; ticks 2-3 suppressed
   (`expectNoMessage`); chainWeight stays at `inflatedTD`.

**Test result:** 20/20 `NetworkPeerManagerSpec` pass. `sbt compile-all` clean. `sbt scalafmtAll` clean.

**Effect:** Inflated Tier3 POW_SCALING handshake estimates for archive nodes self-correct after
N=3 `RefreshPeerBestBlocksTick` cycles (production default: ~150s × 3 = 7.5 min).

---

### §8k-R2 — PRISM: Post-migration spawn-site `.toClassic` slippage audit ✅ DONE 2026-06-24

**Commit:** `8e46a3f68` (docs-only)

**Result:** 0 new gaps found.

**Bridge census:** 44 `.toClassic` production sites remaining (down from ~130 baseline — 66% reduction).
All 44 remaining sites are legitimate bridges, gated architectural work (already in CHASE-QUEUE), or
by-design return conversions for downstream Classic ref storage.

**All 7 major spawn-site slippage entries confirmed CLEARED by §8k-G2 (`0435ac419`):**
FastSync, SNAPSyncController, ChainDownloader, CombinedRecoveryScanActor,
BytecodeRecoveryActor (×2 spawn paths), StorageRecoveryActor (×2 spawn paths),
PivotHeaderBootstrap (×5 spawn sites) — all now accept `TypedActorRef[T]` params at spawn.

**Remaining 44 sites classified:**
| Category | Count | Status |
|----------|-------|--------|
| Classic NPMA interop (`networkPeerManager: ActorRef`) | ~20 | LEGITIMATE — NPMA not yet Typed |
| TCP / JSON-RPC / Scheduler bridges | ~22 | LEGITIMATE — permanent interop |
| NPMA routing msgs (SyncController L1635, L1983) | 2 | GATED — CHASE-QUEUE (NPMA command ADT redesign) |
| ForkChoiceManager.setListener (SyncController L309) | 1 | GATED — CHASE-QUEUE (FCM API redesign) |

---

### §8k-B — PRISM: Post-CAPSTONE TCP floor verification (pre-CAPSTONE run) ✅ DONE 2026-06-24

**Commit:** `<docs-only>` (net zero code changes — see below)
**Executed:** Pre-CAPSTONE; CAPSTONE not yet merged. Created §8k-J to re-run after merge.

**Step 1 — TCP floor NOT achieved (CAPSTONE pending):**
- `grep -rn "\.toClassic" src/main/ --include="*.scala" | grep -v "//"` → **91 occurrences** (expected ≤4)
- All 91 are CAPSTONE-scope bridges (SyncController, PeerManagerActor, FastSync, SNAPSyncController, etc.)
- TCP-permanent floor (ServerActor + RLPxConnectionHandler) confirmed present.

**Step 2 — §8k-J created** in `working-docs/DEFERRED-BACKLOG.md`.

**Step 3 — 0 adapter imports removed:**
Attempted removal from 5 files where grep returned 0 `.toClassic`/`.toTyped` hits. All failed compile —
the adapter also provides `classicSystem.spawn()` (FaucetSupervisor, MockedMiner, PoWMining) and implicit
`ActorRef` ↔ `ActorRef[T]` conversions (RegularSync, FastSyncBranchResolverActor). All 5 imports restored.
Lesson: `grep "toClassic|toTyped"` is insufficient — use `sbt compile-all` to confirm adapter safety.

**Step 4 — §7d Artifact Audit (8-lens sweep):**

| Lens | Finding | Status |
|------|---------|--------|
| 1: `sender()` in Typed actors | Only TCP bridge + doc comments explaining elimination | ✅ Clean |
| 2: `context.actorOf` | Only RLPxConnectionHandler TCP bridge (×2 spawn sites) | ✅ Clean |
| 3: `context.system.scheduler` | Typed actors use Typed scheduler correctly; Classic actors also correct | ✅ Clean |
| 4: `Behavior[Any]` | 2 doc comments in StorageRecoveryActor + BytecodeRecoveryActor — CAPSTONE scope | ⚠️ CAPSTONE |
| 5: Unhandled catch-all | All are legitimate pattern arms (return types, ClassTag matching, etc.) | ✅ Clean |
| 6: Classic import leaks | PoisonPill in SyncController + PeerManagerActor (CAPSTONE targets); `org.apache.pekko.actor.*` wildcard in SNAPRequestTracker.scala | ⚠️ See note |
| 7: `Props.apply/Props()` | Only ServerActor + RLPxConnectionHandler TCP bridges | ✅ Clean |
| 8: `preStart/postStop` | Only RLPxConnectionHandler TCP bridge; AccountRangeWorker uses `postStopSignal` (Typed naming) | ✅ Clean |

**Lens 6 notable:** `SNAPRequestTracker.scala` imports `org.apache.pekko.actor.*` wildcard — should be narrowed.
Logged to CHASE-QUEUE as minor cleanup candidate.

**Step 5 — testEssential:** Not run — net zero code change; compile-all confirmed clean.

**Re-run prompt (after CAPSTONE):**
```
§8k-A through §8k-I are complete. Verify the TCP floor:

Step 1 — grep for any remaining .toClassic / .toTyped outside of:
  ServerActor.scala, RLPxConnectionHandler.scala (TCP I/O — permanent)
Step 2 — If found: identify which sprint was supposed to clear it and create
  a §8k-J follow-up entry in DEFERRED-BACKLOG.md.
Step 3 — Delete all `import org.apache.pekko.actor.typed.scaladsl.adapter`
  lines from files that no longer use the adapter. VERIFY with sbt compile-all after each removal.
Step 4 — Run §7d artifact audit sweep (grep commands in G4-pekko-design-scope.md).
Step 5 — Run testEssential — confirm baseline holds.
Step 6 — git commit -m "chore(8k-B): remove adapter imports — TCP floor verified (4 bridges)"
```

---

### §8k-L — HERALD: PeerManagerActor TCP PoisonPill ✅ DONE 2026-06-24

**Result:** All 3 sites are **PERMANENT TCP FLOOR**. No code change required or expected.

**File:** `src/main/scala/com/chipprbots/ethereum/network/PeerManagerActor.scala`
**Lines:** 982, 986, 990 — all `connection ! PoisonPill` inside `handleConnectionErrors`

| Line | Case | Verdict |
|------|------|---------|
| 982 | `MaxIncomingPendingConnections` | PERMANENT FLOOR |
| 986 | `IncomingConnectionAlreadyHandled` | PERMANENT FLOOR |
| 990 | `IncomingConnectionBlacklisted` | PERMANENT FLOOR |

**Why permanent:** `connection: ActorRef` in all three `ConnectionError` case classes originates from
the Pekko TCP extension's internal connection actor, received via `ServerActor`'s `TcpEventBridge`
(`sender()` on a Classic `Tcp.Connected` event → lifted into `TcpConnected(sender(), remote)` →
forwarded to PeerManagerActor as `HandlePeerConnectionCmd(connection, remoteAddress)`).
PeerManagerActor never spawns this actor; it has no ownership and no Typed ref. `PoisonPill` is the
correct stop mechanism and cannot be replaced with `ctx.stop()`.

**TCP floor census update:** §8k-J expected floor count updated from 4 → **7**
(+3 from PeerManagerActor `handleConnectionErrors` sites).

---

### §8k-J — PRISM: TCP floor verification post-CAPSTONE + post-§8k-K ✅ DONE 2026-06-25

**Commit:** `<docs-only>` (net zero code changes)
**Executed:** Post-CAPSTONE (phases 2a-2g) + post-§8k-K (`a6b0304e7`).

**Step 1 — 30 code `.toClassic` bridges remain** (down from 91 pre-CAPSTONE; ≤4 expectation was wrong).

Permanent floor confirmed (6 sites):
- `ServerActor.scala:70,77` — TCP bind + bridge spawn
- `RLPxConnectionHandler.scala:323` — TCP write ack
- `PeerManagerActor.scala:982,986,990` — `connection ! PoisonPill` on TCP-extension-owned actors (§8k-L)

Root cause for remaining 24 bridges:
1. ✅ **§8k-M RESOLVED** — PHB already Typed (Group ROOT/CAPSTONE; see §8k-M entry above). SyncController×10
   bridge to FastSync(1)/SnapSync-SSC(4)/RegularSync(3)/recovery actors(2); FastSync×5 are
   `fastSyncClassicSelf = pivotResultAdapter.toClassic` (self-ref for Classic-signature collaborators).
   Neither cluster is PHB-related.
2. **PeerEventBusActor callers pass Classic refs via implicit adapter** → adapter import removal blocked in 22+ files

Bridge clusters: SyncController(FastSync×1, SnapSync×4, RegularSync×3, recovery×2)=10, FastSync×5,
PeerManagerActor×4, PivotBlockSelector×2, BlockImporter×2, BytecodeRecovery+StorageRecovery×2,
PeerRequestHandler×1, SNAPSyncController×1, AkkaTaskOps×1, PeerEventBusActor×1, NodeBuilder×2.

**Step 3 — 0 adapter imports removable:** FastSyncBranchResolverActor and RegularSync both fail
compile without adapter — implicit `ClassicActorRef → ActorRef[PEBActor.Command]` conversion.
`MockedMiner`/`PoWMining`/`FaucetSupervisor` need adapter for `classicSystem.spawn()`.

**Step 4 — §7d 8-lens audit:**
Lenses 1, 2, 4, 7, 8: ✅ Clean. Lens 3: minor (5 Typed fetchers import Classic `Scheduler`).
Lens 5: pre-existing `Behaviors.same` silent drops (SSA/FSBRA) — CHASE-QUEUE. Lens 6: SNAPRequestTracker
wildcard — pre-existing CHASE-QUEUE item from §8k-B.

**Step 5 — testEssential:** Skipped — net zero code change; `sbt compile-all` confirmed clean.

**Primary unblocks:** FastSync, SNAPSyncController, RegularSync migrations (eliminate SyncController×10 + FastSync×5); PEB Typed migration (unblocks adapter import removal in 22+ files).

---

### §8k-CQ2 — MITHRIL: `PeerActorSpec:429` AlreadyConnected test regression ✅ FIXED 2026-06-24

**Commit:** `359692a3b`
**Branch:** `scala3-cleanup-june`
**File changed:** `src/test/scala/com/chipprbots/ethereum/network/p2p/PeerActorSpec.scala`

**Background:** 8k-H removed `context.toClassic.parent` sends from `PeerActor`. The test at line 429
("should forward PeerClosedConnection with AlreadyConnected to parent") used `PropsAdapter(PeerActor.apply(...))`
with a Classic `parentProbe.ref` as the parent and expected `PeerActor.PeerClosedConnection("127.0.0.1", AlreadyConnected)`
to arrive there. With the send path gone it timed out after 3 seconds. This was the sole `testEssential`
failure after P12 triage.

**Finding (MITHRIL research):** `PeerActor` never re-sends `PeerClosedConnection` via any new path.
`PeerManagerActor` detects peer death via Pekko death-watch (`watchWith(ref, PeerTerminated(ref))`),
not a message from the peer. The correct way to observe "PeerActor stopped after AlreadyConnected"
is therefore `expectTerminated`.

**Fix summary:**
- Test description: "should forward PeerClosedConnection with AlreadyConnected to parent"
  → "should stop when Disconnect(AlreadyConnected) is received during handshake"
- Removed `parentProbe.ref` as explicit parent
- Added `watcherProbe: TestProbe` calling `watcherProbe.watch(peerUnderTest)`
- `parentProbe.expectMsg(3.seconds, PeerClosedConnection(...))` → `watcherProbe.expectTerminated(peerUnderTest, 3.seconds)`

**Verification:** `compile-all` 0 errors; 15/15 `PeerActorSpec` pass; `scalafmtAll` clean.

---

### §ETH-T1-A — BEACON: `validateInitCodeSize` timestamp dispatch ✅ FIXED 2026-06-24

**Commit:** `ed4db9df9`
**Branch:** `scala3-cleanup-june`
**File changed:** `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala`

**Background:** `validateInitCodeSize` called the 2-arg `EvmConfig.forBlock(blockNumber, config)`.
On ETH/Sepolia this returns a London-era config with `eip3860Enabled = false` regardless of block
timestamp — the `spiralBlockNumber` guard in the 2-arg path never fires on ETH. Result: EIP-3860
initcode size cap (49152 bytes) was never enforced on Sepolia post-Shanghai, meaning oversized
`CREATE` initcode was silently accepted.

**Fix:** Changed call site and method signature to use the 3-arg overload:
`EvmConfig.forBlock(blockHeaderNumber, blockHeaderTimestamp, blockchainConfig)`. The 3-arg overload
applies timestamp-based fork overrides, setting `eip3860Enabled = true` when `isShanghaiTimestamp`
holds.

**ETC safety:** `isShanghaiTimestamp` returns false on ETC (no `shanghaiTimestamp` in `ForkTimestamps`),
so the 3-arg overload collapses to the same result as 2-arg for ETC — no behaviour change.

**Tests added:** 3 new tests in `StdSignedTransactionValidatorSpec`:
- ETH/Sepolia post-Shanghai: initcode > 49152 bytes → `TransactionInitCodeSizeError` ✅
- ETH/Sepolia pre-Shanghai: same initcode → accepted ✅
- ETC at any block: same initcode → accepted ✅

**Verification:** 3/3 new tests pass; `scalafmtAll` clean.

---

### §ETH-T1-B — BEACON: `validateGasLimitEnoughForIntrinsicGas` timestamp dispatch ✅ FIXED 2026-06-24

**Commit:** `6f8f74708`
**Branch:** `scala3-cleanup-june`
**File changed:** `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala`

**Background:** `validateGasLimitEnoughForIntrinsicGas` called the 2-arg `EvmConfig.forBlock`.
On ETH/Sepolia this returns a London-era config with `eip3860Enabled = false`, so the EIP-3860
initcode word cost (`G_initcode_word * ceil(len/32) = 2 * words`) was never included in the
intrinsic-gas floor check post-Shanghai. Under-gassed `CREATE` transactions could pass validation.

**Fix:** Changed call site and method signature to use the 3-arg overload, identical to §ETH-T1-A.

**Subtlety found during testing:** The test application.conf sets `byzantium-block-number = 4370000`.
`EvmConfig.forBlock` selects forks via `maxBy((blockNum, priority))` — a higher block number beats
a higher-priority entry at block 0. Activating ETC forks at block 0 didn't help; Byzantium at 4370000
always won. Fix: place `mystiqueBlockNumber = 5_000_000` (above Byzantium's 4370000) in the test's
`etcMystiqueConfig` so `MystiqueFeeSchedule` wins the selector at block 21M and provides the correct
`G_txdatanonzero = 16` and `G_initcode_word = 2` for the EIP-3860 word cost test.

**ETC safety:** Same as §ETH-T1-A — `isShanghaiTimestamp` false on ETC; no behaviour change.

**Tests added:** 2 new tests in `StdSignedTransactionValidatorSpec` (total 5 tests):
- ETH/Sepolia post-Shanghai with Mystique base: gasLimit = 56213 < intrinsic 56214 → rejected ✅
- ETC with Mystique base, no Shanghai timestamp: gasLimit = 56213 ≥ intrinsic 56200 → accepted ✅

**Verification:** 5/5 `StdSignedTransactionValidatorSpec` pass; 227/227 `testVM` pass; `scalafmtAll` clean.

---

### §ETH-T1-C — BEACON: `getStatelessValidTransactions` timestamp dispatch ✅ FIXED 2026-06-24

**Commit:** `89863ac80`
**Branch:** `scala3-cleanup-june`
**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala`
- `src/test/scala/com/chipprbots/ethereum/domain/SignedTransactionStatelessFilterSpec.scala` (new)

**Background:** `getStatelessValidTransactions` (the stateless mempool pre-filter running on incoming
p2p txs via `SignedTransactionsFilterActor` and `PendingTransactionsManager`) called the 2-arg
`EvmConfig.forBlock(olympiaBlockNumber, blockchainConfig)`. On ETH chains where
`spiralBlockNumber > olympiaBlockNumber` (`etcForksDisabled = true`), this resolves to
`LondonConfigBuilder` with `eip3860Enabled = false`. Contract-creation txs with gasLimit between
the London intrinsic gas (no EIP-3860 initcode word cost) and the Shanghai intrinsic gas
(`delta = ceil(initcode.length/32) * 2`) were false-admitted to the mempool and would fail at
block-execution.

**Key finding during analysis:** EIP-7623 calldata floor (Prague) is enforced in
`BlockPreparator.calcFloorDataGas`, not in `calcTransactionIntrinsicGas`, so the stateless path
cannot enforce it regardless of config version. The sole actionable discrepancy was EIP-3860.

**Decision:** Option 1 (latestForkTimestamp proxy). Derive the highest configured fork timestamp
from `blockchainConfig.forkTimestamps` (osaka → bpo2 → bpo1 → prague → cancun → shanghai,
first defined wins) and call the 3-arg `forBlock`. ETC keeps the existing 2-arg path unchanged.

**Fix:** Dispatches on `networkType == NetworkType.ETH` inside `getStatelessValidTransactions`.
ETH: derives `latestTimestamp` from `forkTimestamps` chain; calls 3-arg overload. ETC: unchanged.

**ETC safety:** ETC configs have no `forkTimestamps` defined (`shanghaiTimestamp = None` etc.),
so the ETH branch is dead code on ETC. The 2-arg path for ETC is structurally unchanged.

**Tests added:** 3 tests in `SignedTransactionStatelessFilterSpec` (new file):
- CREATE tx, gasLimit = 69384 (London intrinsic, no EIP-3860) → rejected post-fix ✅
- CREATE tx, gasLimit = 69448 (Shanghai intrinsic, EIP-3860 included) → admitted ✅
- CALL tx (non-creation), gasLimit = 37384 → admitted (EIP-3860 irrelevant) ✅

**Config used in tests:** `etcMystiqueConfig` base with `olympiaBlockNumber = 6_000_000`,
`spiralBlockNumber = 1e18` → `etcForksDisabled = true` → LondonConfigBuilder (reproduces the
ETH-style fork schedule that triggered the false-admission path).

**Verification:** 3/3 `SignedTransactionStatelessFilterSpec` pass.

---

## §ETH-T2-A — Rename `BlockHeader.isPostMerge` → `isPoS`, add `isPoW` ✅ DONE

**Commit:** `c470b3dac`
**Branch:** `scala3-cleanup-june`
**Date:** 2026-06-24
**Agent:** MITHRIL (mechanical rename)
**Risk:** LOW — pure rename, predicate logic unchanged

**What:**
- `BlockHeader.isPostMerge: Boolean = difficulty == 0 && baseFee.isDefined` → `isPoS`
- `isPoW: Boolean = !isPoS` added as companion
- All 4 call sites updated: `BlockPreparator.scala`, `OpCode.scala`, `VM.scala` (code + comment), `BlockExecutionSpec.scala` (test comment)
- `BlockchainConfig.isPostMerge(totalDifficulty: BigInt)` — left untouched (different semantic: chain-level TTD check, zero callers, not in scope)

**Why:** Block-level `isPostMerge` is ETH-specific terminology ("The Merge"). The chain-level pattern already uses chain-agnostic `isPoW`/`isPoS` vocabulary (`isPoWChain`, `isPostMergeChain`). Aligning the block-level predicate makes multi-chain intent clearer: `if header.isPoW then <pow behaviour>` reads more naturally than `if !header.isPostMerge then <pow behaviour>`.

**Verification:** `sbt compile-all` — clean. `testOnly *BlockPreparator* *BlockExecution* *VM* *OpCode*` — 384/384 pass. `scalafmtAll` — 1 reformatted (expected, new `isPoW` line).

**Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T2-A` (this); audit doc `.local/docs/eth-sepolia-assumption-audit.md` Thread 2. Follow-on: `§NAMING-A` (`35db7dc61`) completed the remaining `PostMerge*` renames that were intentionally left out of scope here — `BlockchainConfig.isPostMerge`, `PostMergeBlockHeaderValidator` → `PoSBlockHeaderValidator`, and `isPostMergeChain` → `isPoSChain`.

---

## §8k-K — SyncController child refs Classic→Typed; PeerRequestHandler dual-adapter bug ✅ DONE

**Commit:** `a6b0304e7`
**Branch:** `scala3-cleanup-june`
**Date:** 2026-06-25
**Agent:** LOOM

**What:**
- `StorageRecoveryActor.RequestRecentRoot.replyTo`: `ActorRef` → `TypedActorRef[StorageRecoveryActor.Command]`
- `StorageRecoveryActor`: `ctx.self.toClassic` → `ctx.self` (Classic compat shim removed)
- **PeerRequestHandler dual-adapter bug** (found during sprint): Pekko's `internalMessageAdapter` silently overwrites a prior registration of the same type `T` (filterNot + prepend on `_messageAdapters`). Two separate `msgAdapter`/`disconnectAdapter` registrations both resolved to the same `messageAdapterRef` with only the last registration's function surviving. Merged into a single `peerEventAdapter` covering both `MessageFromPeer` and `PeerDisconnected`.

**SyncControllerSpec fixes (88/88 pass):**
- `validateHeaderOnly` override returned `Left(HeaderPoWError)`; G5 `PivotBlockSelector` uses this for PoW backlink checks, causing exponential-backoff retries that exhausted the `eventually` window before `SelectionFailed` arrived. Fixed to `Right(BlockHeaderValid)` — only `validate()` (full block) must fail.
- `safeDownloadTarget` must exceed `bestBlockHeaderNumber`; Typed `FastSync` caps header fetches at `safeDownloadTarget` via `enqueueHeadersIfNeeded` (Classic version had no such guard).
- ETH69 G5 by-hash backlink probe handler added to peer mock (stores pivot header in canonical chain so `PivotBlockSelector`'s canonical-match check succeeds).

**Verification:** `sbt compile-all` — clean. `SyncControllerSpec` 88/88 pass. `scalafmtAll` — no changes.

**Cross-refs:** `modernization-log/sync/controller.md §8k-K`; continuation file `.local/docs/continuations/LOOM-SyncController.md`.

---

## §ETH-T4-A — KZG Point Evaluation Precompile (EIP-4844) ✅ FIXED 2026-06-25

**Commit:** `02aaa05fc`
**Branch:** `scala3-cleanup-june`
**Agent:** BEACON
**Risk (pre-fix):** HIGH — consensus divergence: every ETH/Sepolia block using precompile 0x0A accepted any well-formed KZG proof regardless of cryptographic validity

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala` — catch fix
- `src/main/scala/com/chipprbots/ethereum/Fukuii.scala` — KZG startup init
- `src/main/resources/trusted_setup.txt` — new (4163-line canonical mainnet KZG setup, c-kzg-4844 v0.4.0 format)
- `src/test/scala/com/chipprbots/ethereum/vm/KzgPointEvaluationSpec.scala` — new (4 tests)

**Root cause:**
`CKZG4844JNI.verifyKzgProof` (jc-kzg-4844:1.0.0) throws `IllegalStateException` when the
trusted setup is not loaded. The trusted setup was **never loaded** anywhere in the codebase.
The `catch { case _: Exception => }` block at `PrecompiledContracts.scala:793-799` swallowed
the exception and fell through to return the success output (`FIELD_ELEMENTS_PER_BLOB ++
BLS_MODULUS`) whenever the SHA-256/field checks passed — meaning any KZG proof that was
well-formed but cryptographically invalid was silently accepted. go-ethereum returns
`errBlobVerifyKZGProof` on verification failure, causing the precompile to revert.

**Fix (three parts):**
1. **Catch → revert**: `catch { case _: Exception => return None // scalafix:ok DisableSyntax.return }` — an unloaded library is never silently treated as a passing proof.
2. **Load trusted setup at startup**: `Fukuii.main` now calls `CKZG4844JNI.loadNativeLibrary()` and `CKZG4844JNI.loadTrustedSetupFromResource("/trusted_setup.txt", classOf[CKZG4844JNI])` inside a `if cancunTimestamp.isDefined` guard — ETC nodes are unaffected.
3. **Bundle canonical setup**: `src/main/resources/trusted_setup.txt` — mainnet KZG parameters in c-kzg-4844 v0.4.0 format (4096 G1 points + 65 G2 points). Source: `github.com/ethereum/c-kzg-4844@v0.4.0/src/trusted_setup.txt`. INCOMPATIBLE with jc-kzg-4844:2.x format (8259 lines); correct for jc-kzg-4844:1.0.0.

**Tests (4, all pass):**
- `KZGPointEvaluation valid proof returns FIELD_ELEMENTS_PER_BLOB ++ BLS_MODULUS` (SlowTest+VMTest) — vector from go-ethereum `core/vm/testdata/precompiles/pointEvaluation.json` `pointEvaluation1`
- `KZGPointEvaluation invalid proof (corrupted proof bytes) reverts` (SlowTest+VMTest)
- `KZGPointEvaluation wrong input length reverts` (UnitTest+VMTest)
- `KZGPointEvaluation wrong versioned hash version byte reverts` (UnitTest+VMTest)

**ETC safety:** `cancunTimestamp.isDefined` is false on all ETC/Mordor configs. The KZG setup is never loaded for ETC nodes. `PrecompiledContracts.KzgPointEvaluation` address 0x0A is not active in any ETC fork.

**Cross-refs:** `consensus/vm.md §ETH-T4-A`, `node/bootstrap.md §ETH-T4-A`, `working-docs/DEFERRED-BACKLOG.md Part 10`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 4c

---

## §ETH-T4-B — Type-3 maxFeePerBlobGas Validation (EIP-4844) ✅ FIXED 2026-06-25

**Commit:** `bd43ed49b`
**Branch:** `scala3-cleanup-june`
**Agent:** BEACON
**Risk (pre-fix):** MEDIUM — every Type-3 (blob) transaction with `maxFeePerBlobGas < blobBaseFee` was accepted as valid; go-ethereum returns `errTxBlobFeeCapTooLow` for these cases

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/SignedTransactionValidator.scala` — added `TransactionMaxFeePerBlobGasTooLow` error variant
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala` — added `validateMaxFeePerBlobGas` call in Type-3 validation chain
- `src/test/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidatorSpec.scala` — 4 new tests

**Root cause:**
`StdSignedTransactionValidator` validated blob count, blob hashes, and access lists for Type-3 transactions but omitted the `maxFeePerBlobGas >= blobBaseFee` check from EIP-4844 §3.2. A sender could post a blob transaction with `maxFeePerBlobGas = 1 wei` regardless of current blob gas pricing; fukuii would accept it into a block while go-ethereum would reject with `errTxBlobFeeCapTooLow`.

**Fix:**
Added `validateMaxFeePerBlobGas(stx, header)` to the Type-3 validation chain. Returns `TransactionMaxFeePerBlobGasTooLow(txMaxFee, blobBaseFee)` when `tx.maxFeePerBlobGas < BlobGasUtils.getBlobGasPrice(excessBlobGas, ts, config)`.

**Tests (4, all pass):** Accepted when equal, accepted when above, rejected (`TransactionMaxFeePerBlobGasTooLow`) when below, Type-0/1/2 skip blob fee validation.

**ETC safety:** `maxFeePerBlobGas` validation only runs for `BlobTransaction` (Type-3). ETC has no `cancunTimestamp`, so blob transactions are unreachable. No ETC behaviour change.

**Cross-refs:** `consensus/validators.md §ETH-T4-B`, `working-docs/DEFERRED-BACKLOG.md Part 10`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 4c

---

## §ETH-T4-C — EIP-4788 Beacon Roots Contract Deployment ✅ FIXED 2026-06-25

**Commit:** `b934caffe`
**Branch:** `scala3-cleanup-june`
**Agent:** BEACON
**Risk (pre-fix):** HIGH — ETH/Sepolia state roots diverged from canonical: EIP-4788 requires the `0x4242424242424242424242424242424242424242` system contract to have code and nonce=1 at the Cancun transition; fukuii was writing the beacon root to storage but leaving the account code-empty with nonce=0

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala` — `applyEip4788SystemCall` now deploys `HISTORY_STORAGE_CONTRACT_CODE` + nonce=1 if not already present
- `src/test/scala/com/chipprbots/ethereum/ledger/BeaconRootsSpec.scala` — new (158 lines, covers contract deployment, beacon root storage, and ring-buffer wrap-around)

**Root cause:**
`applyEip4788SystemCall` wrote the parent beacon block root to the ring-buffer at `0x4242…` but never initialised the account's code or nonce. The EIP specifies `BEACON_ROOTS_ADDRESS` must hold `HISTORY_STORAGE_CONTRACT_CODE` with `nonce=1` at deployment. Without this, the account's codehash was the empty-code hash, causing state root divergence from canonical Sepolia on every post-Cancun block.

**Fix:**
At block start for the first Cancun block (whenever the account has no code), deploy `HISTORY_STORAGE_CONTRACT_CODE` and set nonce=1 before the beacon root write.

**ETC safety:** `applyEip4788SystemCall` is guarded by `cancunTimestamp.isDefined` — never runs on ETC/Mordor configs.

**Cross-refs:** `storage/ledger.md §ETH-T4-C`, `working-docs/DEFERRED-BACKLOG.md Part 10`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 4c

---

## §ETH-T4-D — Blob Base Fee Formula Unification ✅ FIXED 2026-06-25

**Commit:** `f6cf7fb9c`
**Branch:** `scala3-cleanup-june`
**Agent:** BEACON
**Risk (pre-fix):** HIGH — `deductBlobGas` / `updateSenderAccountBeforeExecution` / balance pre-check computed blob base fee using a local `computeBlobBaseFee` that only knew Cancun (fraction 3338477) and Prague (fraction 5007716) update fractions; EIP-7892 BPO1 (8346193) and BPO2 (11684671) fractions were absent, causing burned-amount to diverge from `BlobGasUtils.getBlobGasPrice` on post-Osaka Sepolia blocks

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` — all 3 call sites of `computeBlobBaseFee` replaced with `BlobGasUtils.getBlobGasPrice(excessBlobGas, header.unixTimestamp, blockchainConfig)`; private `computeBlobBaseFee` and `fakeExponential` methods deleted
- `src/test/scala/com/chipprbots/ethereum/ledger/BlockPreparatorSpec.scala` — `"deductBlobGas"` test suite added (Prague config, 2-blob tx, verifies burned amount matches `BlobGasUtils.getBlobGasPrice * GAS_PER_BLOB * numBlobs`)

**Root cause:**
`BlockPreparator` had a private `computeBlobBaseFee(excess, ts)` that duplicated `fakeExponential` logic with only Cancun/Prague fractions. `BlobGasUtils.getBlobGasPrice(excess, ts, config)` is the canonical source of truth handling all BPO variants via `blockchainConfig.bpoSchedule`. Any post-Osaka Sepolia block caused the amount burned to diverge from the amount validated at the transaction level.

**Fix:**
Deleted `computeBlobBaseFee` and `fakeExponential`. All 3 deduction sites call `BlobGasUtils.getBlobGasPrice(excessBlobGas, header.unixTimestamp, blockchainConfig)` directly.

**Tests (25/25 pass including new):**
- New: `"deductBlobGas" — burns correct blob gas cost matching BlobGasUtils for a Prague block` — verifies sender balance reduction equals `BlobGasUtils.getBlobGasPrice * GAS_PER_BLOB * numBlobs`

**ETC safety:** Blob deduction paths are unreachable for ETC blocks (no `cancunTimestamp`). No ETC behaviour change.

**Cross-refs:** `storage/ledger.md §ETH-T4-D`, `working-docs/DEFERRED-BACKLOG.md Part 10`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 4c

---

## §ETH-T9-A — SNAP pivot header post-merge validation gate ✅ FIXED 2026-06-25

**Commit:** `4ac7e2842`
**Branch:** `scala3-cleanup-june`
**Agent:** BEACON
**Risk (pre-fix):** HIGH — a malicious peer could serve a malformed post-merge pivot header (e.g. `difficulty > 0`, `withdrawalsRoot = None` on Shanghai blocks) and SNAP sync would commit it with no validation, causing stateRoot divergence not discovered until block execution

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` — `isPostMergeChain` gate added in `BootstrapComplete` handler and `completePivotRefreshWithStateRoot`; on rejection: `startSnapSync()` / `return` respectively
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncControllerSpec.scala` — 4 new tests for the `PostMergeBlockHeaderValidator` pivot header gate (difficulty>0 rejected, withdrawalsRoot=None rejected, valid accepted, PoW confirmed rejected)

**Root cause:**
Both `BootstrapComplete` and `completePivotRefreshWithStateRoot` stored the peer-supplied pivot header into `appStateStorage` without calling any header validator. `PostMergeBlockHeaderValidator.validateHeaderOnly` checks difficulty==0, nonce==EmptyNonce, ommersHash==EmptyOmmers, withdrawalsRoot present for Shanghai+, and blobGas fields present for Cancun+. None of these were verified before committing pivot state.

**Fix:**
Added `if isPostMergeChain then { given bc: BlockchainConfig = ...; PostMergeBlockHeaderValidator.validateHeaderOnly(header) match { ... } }` before any state mutation in both storage paths. `isPostMergeChain` is `terminalTotalDifficulty.isDefined` — true for ETH/Sepolia, false for ETC.

**ETC safety:** `isPostMergeChain = false` for all ETC/Mordor configs (no TTD). Gate never fires on ETC; validator is never called. No ETC behaviour change.

**Tests (4/4 pass):**
- `"reject an ETH/Sepolia pivot header with difficulty > 0"` — difficulty=1 → isLeft
- `"reject an ETH/Sepolia Shanghai-era pivot header with withdrawalsRoot = None"` — HefEmpty → isLeft
- `"accept a valid ETH/Sepolia post-merge pivot header"` — valid Sepolia header → isRight
- `"confirm the ETH validator rejects PoW headers (ETC gate avoids calling it)"` — difficulty=10^16 → isLeft

**Cross-refs:** `sync/snap.md §ETH-T9-A`, `working-docs/DEFERRED-BACKLOG.md Part 10`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9

---

## §ETH-T9-B — BEACON: Gate BlockHeader RLP field-count on fork timestamp ✅ FIXED 2026-06-25

**Commit:** `4ac7e2842`
**Branch:** `scala3-cleanup-june`
**Agent:** BEACON
**Risk (pre-fix):** HIGH — a peer sending a Cancun-era block header encoded as a 17-item (Shanghai-shape) RLP was silently accepted with `blobGasUsed = None`; the decoder dispatched it to `HefPostShanghai` with no validation against the fork timestamp, producing a structurally inconsistent header that evaded `PostMergeBlockHeaderValidator` (which requires `difficulty == 0`, so PoW ETC headers were never checked)

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala` — added `validateFieldCount(header, config)` to companion object
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala` — `validateFieldCount` chained before `PostMergeBlockHeaderValidator` at both pivot acceptance sites (bootstrap `BootstrapComplete` handler and `completePivotRefreshWithStateRoot`)
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/SyncBlocksValidator.scala` — `validateFieldCount` pre-screens in `validateHeaderOnly` (fast sync header path)
- `src/test/scala/com/chipprbots/ethereum/domain/BlockHeaderFieldCountSpec.scala` — new (6 tests)

**Root cause:**
`BlockHeaderDec.toBlockHeader` dispatches on RLP item count alone — 15→`HefEmpty`, 16→`HefPostOlympia`, 17→`HefPostShanghai`, 20→`HefPostCancun`, 21+→`HefPostPrague`. Item count 18 or 19 throws; count 17 with a Cancun-active timestamp is silently decoded as `HefPostShanghai` with `blobGasUsed = None`. `PostMergeBlockHeaderValidator` only runs on PoS headers (`difficulty == 0`), leaving the PoW-era ETC code path and any PoW-forged ETH pivot header without the check.

**Fix:**
`BlockHeader.validateFieldCount(header, config)` — a pure, cheap gate:
- No-op for `NetworkType.ETC` (no timestamp forks on ETC).
- Returns `Left(msg)` if Cancun is active at `header.unixTimestamp` and `blobGasUsed.isEmpty`.
- Returns `Left(msg)` if Shanghai is active at `header.unixTimestamp` and `withdrawalsRoot.isEmpty`.
- Called at: both SNAP pivot acceptance sites (pre-screens before `PostMergeBlockHeaderValidator`), and `SyncBlocksValidator.validateHeaderOnly` (fast sync header validation, returns `HeaderUnexpectedError`).

**Tests (6, all pass):**
- ETC config → `Right(())` (no-op for any field shape regardless of timestamp)
- Pre-Shanghai ETH + `HefEmpty` → `Right(())`
- Shanghai-active ETH + `HefEmpty` (no `withdrawalsRoot`) → `Left` containing "withdrawalsRoot"
- Cancun-active ETH + `HefPostShanghai` (17-item, no `blobGasUsed`) → `Left` containing "blobGasUsed" (§ETH-T9-B motivating case)
- Cancun-active ETH + `HefPostCancun` (20-item, all fields) → `Right(())`
- Timestamp exactly at `CancunTs` boundary + Shanghai-shape → `Left` (boundary inclusive)

**ETC safety:** `config.networkType != NetworkType.ETH` short-circuits to `Right(())` immediately — zero behaviour change for ETC/Mordor nodes.

**Cross-refs:** `sync/snap.md §ETH-T9-B`, `working-docs/DEFERRED-BACKLOG.md I2`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9

---

### §NAMING-A — MITHRIL: Rename `PostMerge` → `PoS` throughout (terminology alignment)

**Commit:** `35db7dc61` — 2026-06-25
**Agent:** MITHRIL
**Risk:** LOW — pure rename, no logic change

**Files changed (10):**
- `git mv` renames: `consensus/engine/PostMergeBlockHeaderValidator.scala` → `PoSBlockHeaderValidator.scala`, `test/.../validators/PostMergeBlockHeaderValidatorSpec.scala` → `PoSBlockHeaderValidatorSpec.scala`
- Symbol renames in: `consensus/engine/PoSBlockHeaderValidator.scala`, `consensus/engine/TransitionBlockHeaderValidator.scala`, `consensus/validators/BlockHeaderValidator.scala`, `domain/BlockHeader.scala`, `utils/BlockchainConfig.scala`, `blockchain/sync/SyncController.scala`, `blockchain/sync/snap/SNAPSyncController.scala`, `test/.../validators/PoSBlockHeaderValidatorSpec.scala`, `test/.../sync/snap/SNAPSyncControllerSpec.scala`, `test/.../domain/BlockHeaderFieldCountSpec.scala`

**Rename map applied:**
- `PostMergeBlockHeaderValidator` → `PoSBlockHeaderValidator` (object + all refs + file)
- `validatePostMergeDifficulty/Nonce/Ommers` → `validatePoSDifficulty/Nonce/Ommers`
- `PostMergeNonceError` → `PoSNonceError`, `PostMergeOmmersError` → `PoSOmmersError`
- `isPostMergeChain` → `isPoSChain` (in `SyncController.scala` + `SNAPSyncController.scala`)
- `isPostMerge(totalDifficulty)` → `isPoS(totalDifficulty)` in `BlockchainConfig.scala`
- log string `"postMergeChain={}"` → `"isPoSChain={}"`

**Preserved (lower-priority local vars — ETH Merge event context, not consensus type):**
- `postMerge`/`preMerge` in `ETH69OscillationChainWeightSpec.scala:100-101`
- `postMergeHeader` local val in `BlockExecutionSpec.scala:688,698`

**Result:** `sbt compile-all` → 0 errors. `sbt scalafmtAll` → 1 file reformatted.

**Cross-refs:** `modernization-log/consensus/engine.md §NAMING-A`

---

## §ETH-T9-C — BEACON: Verify StorageScheme routing in SNAP coordinators ✅ FALSE POSITIVE 2026-06-25

**Commit:** N/A — no code change needed
**Agent:** BEACON
**Risk:** N/A — false positive confirmed; Explore agent audit could not see past file-read truncation limits

**Verdict:** WIRED — all three SNAP coordinators correctly dispatch on `storageScheme`. The Thread 9 Explore agent flagged the gap because both remaining dispatch sites were past its read-window truncation.

**Dispatch sites verified (2026-06-25):**
- `AccountRangeCoordinator.getOrCreateTaskStackTrie` (line 1570): `storageScheme match { case Hash => new SnapHashTrie(batch => mptStorage.storeRawNodes(batch)); case Path => new SnapPathTrie(...pns.writeAccountNode...) }`
- `StorageRangeCoordinator.getOrCreateAccountTrie` (line 490): `storageScheme match { case Hash => new SnapHashTrie(...); case Path => new SnapPathTrie(...pns.writeStorageNode...) }`
- `TrieNodeHealingCoordinator.processActiveResponse` (line 1373): `storageScheme match { case Hash => rawNodeBuffer += (nodeHash, nodeData.toArray); case Path => pathNodeStorageOpt.foreach(pns => ... pns.writeStorageNode / writeAccountNode) }`

**Cross-refs:** `sync/snap.md §ETH-T9-C`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9

---

## §ETH-T9-D — BEACON: Startup assertion — storageScheme must match chain type ✅ FIXED 2026-06-25

**Commit:** `TBD` — 2026-06-25
**Agent:** BEACON
**Risk (pre-fix):** MEDIUM — a misconfigured ETC node (`storage-scheme = path`) or ETH node (`storage-scheme = hash`) would start successfully and sync state into the wrong layout, failing silently until state verification

**Files changed:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala` — `loadSnapSyncConfig()` now validates `storageScheme` against `blockchainConfig.networkType` via `require()` before returning. All three SNAP startup paths in `SyncController` call `loadSnapSyncConfig()`, so all are covered.

**What was added (in `loadSnapSyncConfig()`):**
```scala
val networkType = configBuilder.blockchainConfig.networkType
val expectedScheme = if networkType == NetworkType.ETH then StorageScheme.Path else StorageScheme.Hash
require(
  config.storageScheme == expectedScheme,
  s"storageScheme=${config.storageScheme} does not match expected $expectedScheme " +
    s"for networkType=$networkType — check sync.snap-sync.storage-scheme in reference.conf"
)
```

**Placement rationale:** `loadSnapSyncConfig()` is a private helper already called at all three SNAP init sites (`startSnapSync()`, and two restart variants). Adding the assertion there covers all paths without repeating it or changing public APIs.

**Complement:** `SNAPSyncControllerImpl.checkStorageSchemeMismatch()` (separate, existing) detects DB-state vs config mismatch (path data + hash config). This assertion is a separate upstream gate: config vs chain type, at `require()` time before any actor is spawned.

**ETC safety:** `expectedScheme = StorageScheme.Hash` for ETC — the assertion trivially passes for any correct ETC node configuration. Zero behaviour change.

**VERIFY:** `sbt compile-all` → 0 errors, 67 pre-existing warnings. `sbt scalafmtAll` → 1 file reformatted.

**Cross-refs:** `sync/snap.md §ETH-T9-D`, `.local/docs/eth-sepolia-assumption-audit.md` Thread 9
