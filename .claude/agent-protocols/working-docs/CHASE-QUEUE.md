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

**Type codes:** `LOG` `WARN` `RETURN` `SENDER` `CLASSIC` `MUTABLE` `EXCEPT` `IMPLICIT` `ISINST` `DEAD` `NULL`

---

## Open entries

| File | Line(s) | Pattern | Type | Agent | Date |
|------|---------|---------|------|-------|------|
| `consensus/pow/PoWMiningCoordinator.scala` | — | Threading model finding (R9/8d B2): FORGE-gated. See `threading-model-audit.md §B2` for detail. FORGE review required before any fix. | MUTABLE | PRISM | 2026-06-21 |
| `metrics/MetricsAlreadyConfiguredError.scala` | whole file | Part 8f sweep: `case class MetricsAlreadyConfiguredError` has 1 grep hit (its own definition). Never thrown, never caught, never imported. Pure deletion — no call-site changes required. | DEAD | PRISM | 2026-06-22 |
| `ledger/LocalVM.scala` | whole file | Part 8f sweep: `object LocalVM extends VM[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]` — 1 grep hit (definition only). Not imported or referenced anywhere in main or test sources. Pure deletion — no call-site changes required. | DEAD | PRISM | 2026-06-22 |
| `blockchain/sync/AdaptiveSyncStrategy.scala` | whole file (193 lines) | Part 8f sweep: `AdaptiveSyncController`, `SyncStrategy`, `NetworkConditions`, `SyncResult` all unreferenced outside the file. No actor spawns it; no config wires it; no test covers it. Speculative-generality state machine predating the current sync architecture. Pure deletion — no call-site changes required. | DEAD | PRISM | 2026-06-22 |
| `network/discovery/StaticNodesLoader.scala` | whole file (95 lines) | Part 8f sweep: duplicate of `network/StaticNodesLoader.scala` (the production impl). Discovery version has weaker validation (prefix-only, no port/pubkey check) and is used only by `DiscoveryConfig`. Fix: redirect `DiscoveryConfig` to call `com.chipprbots.ethereum.network.StaticNodesLoader.load(datadir).map(_.toString).toSet` (1-line change), then delete file + migrate or delete `StaticNodesLoaderSpec`. | DEAD | PRISM | 2026-06-22 |
| `blockchain/sync/SyncControllerSpec.scala` | SyncStateAutoPilot | 7 pre-existing test failures: `SyncControllerSpec.SyncStateAutoPilot.run` throws `MatchError` on `GetHandshakedPeersCmd` because the test autopilot mock does not handle that message (introduced when NPMA shell was removed and callers were rewired). Unrelated to scala3-cleanup-june WRAITH/LOOM commits — confirmed reproducible on parent baseline. Fix: update `SyncStateAutoPilot` to handle `GetHandshakedPeersCmd` and return a plausible `HandshakedPeers` response. Cluster with SyncController spec cleanup. | CLASSIC | MITHRIL | 2026-06-22 |

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

---

## SNAP2 replyTo adapter dependency (S2 → SNAP2, 2026-06-21)

`FastSyncBranchResolverActor` now receives `replyTo: ActorRef[BranchResolverResponse]`. FastSync (`Behavior[Any]` core) currently passes `ctx.messageAdapter[BranchResolverResponse](identity)` as the replyTo and routes the response through its `Any` handler. When FastSync is narrowed to `Behavior[Command]` in the network/P2P sprint, that adapter and the replyTo pass will need replacing with a proper typed adapter or a direct Typed self-ref. Not a blocker now — the `identity` adapter is correct for `Behavior[Any]`. Flag at SNAP2 re-entry.

---

## E165 TestProbe pattern — test harness cleanup (S2 + S5, 2026-06-21/22)

**Corrected baseline (S5 EYE sweep 2026-06-22):** 777 unnarrowed `TestProbe` sites across 83 test files. Prior entry stated "5 in FastSyncBranchResolverSpec" — that file now has 0 unnarrowed sites (was cleaned in a prior session). The 777 sites are pervasive across SNAP coordinator and network test suites.

Highest-density files: `TrieNodeHealingCoordinatorSpec` (58), `ByteCodeCoordinatorSpec` (56), `AccountRangeCoordinatorSpec` (54), `StorageRangeCoordinatorSpec` (39), `PeerManagerSpec` (32).

Fix: narrow each `TestProbe` with a `[M]` type parameter matching the expected message type. Pattern selectors on `Any` from untyped probes produce E165 warnings in strict Scala 3. Touches the test harness across 83 specs — route to a dedicated test-harness cleanup sprint rather than inline fixes.

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

## scala3-cleanup-june: null-initialized actor session state (PRISM, 2026-06-22)

| File | Line(s) | Pattern | Type | Agent | Date |
|------|---------|---------|------|-------|------|
| `blockchain/sync/fast/FastSync.scala` | 343,363–364 | `var syncState: SyncState = null` + `var syncStateStorageActor: ActorRef = null` + `var syncStateScheduler: TypedActorRef[...] = null` — NPE risk if any Command arrives before `initSyncSession()`. Replace with `Option[T]`. | NULL | PRISM | 2026-06-22 |

---

## scala3-cleanup-june: dead if/else branches (PRISM, 2026-06-22)

| File | Line(s) | Pattern | Type | Agent | Date |
|------|---------|---------|------|-------|------|
| `network/NetworkPeerManagerActor.scala` | 325–327,689–692 | `if Capability.usesRequestId(...) then X else X` — both arms identical; condition has no effect. DEAD code. | DEAD | PRISM | 2026-06-22 |

---

## scala3-cleanup-june: throw-and-catch as control flow (PRISM, 2026-06-22)

Suggested new protocol name: **exception-as-control-flow** — covers `throw` inside a `try` block in the same method used as a non-error signal rather than a true error boundary. Recurs in `FastSync.expandTypedReceipts` (line 578) and likely in other receipt/codec paths. Fix: replace with `Either`/`Option` return.

| File | Line(s) | Pattern | Type | Agent | Date |
|------|---------|---------|------|-------|------|
| `blockchain/sync/fast/FastSync.scala` | 571–591 | `throw new RuntimeException(...)` caught 6 lines later in the same function — control-flow exception. Convert to `Either`. | EXCEPT | PRISM | 2026-06-22 |

---

## scala3-cleanup-june: global ExecutionContext import in actor (PRISM, 2026-06-22)

| File | Line(s) | Pattern | Type | Agent | Date |
|------|---------|---------|------|-------|------|
| `blockchain/sync/SyncController.scala` | 15 | `import scala.concurrent.ExecutionContext.Implicits.global` at file scope in an actor class — any `Future.map` will silently use the global pool instead of the actor dispatcher. Replace with `given ec: ExecutionContext = ctx.executionContext`. | IMPLICIT | PRISM | 2026-06-22 |

---

## Clearout Prompts

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| B1 | Batch B step 1 | P1 MITHRIL Seal RegularSyncCommand | Run after Batch A; compile-verify before P2 |
| B2 | Batch B step 2 | P2 MITHRIL SyncControllerSpec autopilot | Run after P1 compiles clean |
| B3 | Batch B step 3 | P3 WRAITH Delete 3 dead files | No compile dependency on P1/P2; safe to run after either |
| B4 | Batch B step 4 | P4 WRAITH DiscoveryConfig redirect + delete | Run after P3 compile-verified |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

### P1 — MITHRIL: Seal RegularSyncCommand (structural fix)

**Agent:** MITHRIL
**Files:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncProtocol.scala`
         `src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSync.scala`
**Prerequisite:** None. Pure structural refactor; no consensus touch.

**Prompt:**
> On branch `scala3-cleanup-june`, fix CHASE-QUEUE W17: `SyncProtocol.RegularSyncCommand`
> cannot be `sealed` because three direct subtypes live in a different source file.
>
> **Root cause:** `sealed trait RegularSyncCommand` is in `SyncProtocol.scala` but
> `FetcherStatusTick`, `PrintStatusTick`, and `ProgressProtocol` (which extend it) are
> defined in `RegularSync.scala`. Scala 3 requires all direct subtypes of a sealed trait
> to be in the same file → E112 if `sealed` is added as-is.
>
> **Fix:**
> 1. Read `SyncProtocol.scala` and `RegularSync.scala` to locate the 3 subtypes.
> 2. Move `FetcherStatusTick`, `PrintStatusTick`, and `ProgressProtocol` from
>    `RegularSync.scala` into `SyncProtocol.scala`, immediately after the
>    `RegularSyncCommand` trait definition.
> 3. Remove the now-moved definitions from `RegularSync.scala`.
> 4. Add `sealed` to `trait RegularSyncCommand` in `SyncProtocol.scala`.
> 5. Compile: `sbt compile-all` — must be 0 errors, E112 must not appear.
> 6. Run: `sbt testOnly *RegularSync* *SyncController*` — no new failures.
>
> **Current workaround (remove when sealed compiles):** `RegularSync.scala` has a
> `case _ => Behaviors.unhandled` fallthrough + `log.warning`. Once sealed, that arm
> is dead and the compiler will warn — delete it after sealing succeeds.

**Verification:** `sbt compile-all` 0 errors; sealed compiles; fallthrough arm removed; targeted tests pass

**Documentation updates when complete:**
- `working-docs/CHASE-QUEUE.md` — remove the W17 open entry from "Open entries" table
- Add to "Cleared entries log": `| RegularSyncCommand sealed | SyncProtocol.scala + RegularSync.scala | Cleared [date]: [SHA] — 3 subtypes moved to SyncProtocol.scala, sealed compiles, fallthrough arm deleted. |`
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA] | W17 — RegularSyncCommand sealed (subtypes moved to SyncProtocol.scala) |`
- `modernization-log/sync/regular.md` — add under "Quality Fixes":
  `#### [SHA] — W17: RegularSyncCommand sealed`
  `- **What:** FetcherStatusTick, PrintStatusTick, ProgressProtocol moved from RegularSync.scala → SyncProtocol.scala; sealed trait compiles; fallthrough arm deleted`

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. Fix or draft a full clearout prompt for any matching open items found in `SyncProtocol.scala` or `RegularSync.scala` while they are open.

**Rejection criteria:** Changing the semantics of the moved types; touching consensus code; adding new subtypes

---

### P2 — MITHRIL: Fix SyncControllerSpec SyncStateAutoPilot failures

**Agent:** MITHRIL
**Files:** `src/test/scala/com/chipprbots/ethereum/blockchain/sync/SyncControllerSpec.scala`
**Prerequisite:** None. Test-only fix. Pre-existing on parent baseline (not introduced here).

**Prompt:**
> On branch `scala3-cleanup-june`, fix the 7 pre-existing `SyncControllerSpec` failures.
>
> **Root cause:** `SyncStateAutoPilot.run` throws `MatchError` on `GetHandshakedPeersCmd`
> because the test autopilot mock does not handle that message. The message was added to
> `SyncController` when NPMA callers were rewired; the autopilot was not updated.
>
> **Fix:**
> 1. Read `SyncControllerSpec.scala` and find the `SyncStateAutoPilot` class/object.
> 2. Find the `run` method's match block.
> 3. Add a `GetHandshakedPeersCmd` case that returns a plausible `HandshakedPeers`
>    response (e.g., empty peers list or a minimal stub peer set).
>    The response type must match what `SyncController` expects when it sends that message.
> 4. Compile: `sbt compile-all` — 0 errors.
> 5. Run: `sbt testOnly *SyncControllerSpec` — 7 previously failing tests must now pass.
>    No new failures; total passing count must increase by exactly 7.
>
> Do NOT change any production source files.

**Verification:** 7 failures resolved; `sbt testOnly *SyncControllerSpec` passes all; no production edits

**Documentation updates when complete:**
- `working-docs/CHASE-QUEUE.md` — remove the SyncControllerSpec open entry from "Open entries" table
- Add to "Cleared entries log": `| SyncControllerSpec SyncStateAutoPilot | SyncControllerSpec.scala | Cleared [date]: [SHA] — GetHandshakedPeersCmd handler added to autopilot; 7 pre-existing failures resolved. |`
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA] | SyncControllerSpec — SyncStateAutoPilot GetHandshakedPeersCmd handler (7 pre-existing failures fixed) |`
- `modernization-log/sync/controller.md` — add under "Quality Fixes" (or create section if absent):
  `#### [SHA] — SyncControllerSpec: SyncStateAutoPilot GetHandshakedPeersCmd handler`
  `- **What:** 7 pre-existing MatchError failures fixed; autopilot now handles GetHandshakedPeersCmd → HandshakedPeers stub`

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. `SyncControllerSpec.scala` is a high-value file — scan for other open CHASE-QUEUE or DEFERRED-BACKLOG items in the spec while it is open and address or draft prompts for them.

**Rejection criteria:** Production file edits; changing SyncController logic; adding new test cases unrelated to the fix

---

### P3 — WRAITH: Delete 3 confirmed dead files

**Agent:** WRAITH
**Files (delete all three):**
- `src/main/scala/.../metrics/MetricsAlreadyConfiguredError.scala`
- `src/main/scala/.../blockchain/ledger/LocalVM.scala`
- `src/main/scala/.../blockchain/sync/AdaptiveSyncStrategy.scala` (193 lines)
**Prerequisite:** None — PRISM confirmed zero external references for all three.

**Prompt:**
> On branch `scala3-cleanup-june`, delete three confirmed dead files found in the Part 8f
> dead code sweep. Each has exactly 1 grep hit (its own definition). No call-site changes needed.
>
> For each file:
> 1. Confirm with `grep -rn "MetricsAlreadyConfiguredError\|LocalVM\b\|AdaptiveSyncStrategy\|AdaptiveSyncController\|SyncStrategy\b\|NetworkConditions\|SyncResult\b" src/ --include="*.scala"` — expect only the definition files themselves.
> 2. If confirmed, delete the file: `git rm path/to/File.scala`
> 3. After all three deletions: `sbt compile-all` — 0 errors (no callers means no broken imports).
> 4. Single commit covering all three: `Part 8f — delete 3 confirmed dead files`.
>
> Do NOT delete any test files for these classes. If test files exist for LocalVM or
> AdaptiveSyncStrategy, add them as DEAD entries to CHASE-QUEUE.md for a follow-on pass.

**Verification:** `sbt compile-all` 0 errors after deletion; grep confirms no remaining callers

**Documentation updates when complete:**
- `working-docs/CHASE-QUEUE.md` — remove the 3 DEAD entries from Open entries table
- Add to Cleared entries log: `| MetricsAlreadyConfiguredError + LocalVM + AdaptiveSyncStrategy | Part 8f dead code | Cleared [date]: [SHA] — 3 confirmed dead files deleted. |`
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA] | Part 8f — delete MetricsAlreadyConfiguredError + LocalVM + AdaptiveSyncStrategy |`
- `modernization-log/` — no entry needed (dead code deletion, not modernization)

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. After grep-confirming each file has no callers, check whether any other DEAD or NULL entries in CHASE-QUEUE are in the same packages — if so, add them to the deletion list or draft a follow-on prompt.

**Rejection criteria:** Deleting files with external callers; deleting test files in the same commit; bundling with unrelated changes

---

### P4 — WRAITH: Redirect DiscoveryConfig + delete duplicate StaticNodesLoader

**Agent:** WRAITH
**Files:**
- `src/main/scala/.../network/discovery/StaticNodesLoader.scala` (95 lines — delete)
- Caller: `DiscoveryConfig.scala` (update 1 call site)
**Prerequisite:** P3 compile-verified (not strictly required, but keeps the build clean between commits).

**Prompt:**
> On branch `scala3-cleanup-june`, fix the duplicate StaticNodesLoader identified in the
> Part 8f sweep. The `network/discovery/` version duplicates `network/StaticNodesLoader.scala`
> with weaker validation (prefix-only, no port/pubkey check).
>
> Steps:
> 1. Read both files to confirm they are functionally equivalent (same `.load(datadir)` API).
> 2. Find the one call site in `DiscoveryConfig.scala` that references the discovery version:
>    `grep -n "StaticNodesLoader" src/.../network/discovery/DiscoveryConfig.scala`
> 3. Update that import + call to use `com.chipprbots.ethereum.network.StaticNodesLoader`
>    (the production impl with proper validation).
> 4. Compile: `sbt compile-all` — 0 errors.
> 5. Delete the duplicate: `git rm src/.../network/discovery/StaticNodesLoader.scala`
> 6. Compile again: `sbt compile-all` — 0 errors.
> 7. Run: `sbt testOnly *DiscoveryConfig* *StaticNodesLoader*` — existing tests pass.
> 8. Single commit: `Part 8f — redirect DiscoveryConfig to network.StaticNodesLoader, delete duplicate`.
>
> If a `StaticNodesLoaderSpec` exists under `discovery/`, update its imports to reference
> `network.StaticNodesLoader` instead (or delete if it only tested the removed weaker impl).

**Verification:** `sbt compile-all` 0 errors; targeted tests pass; grep shows no remaining imports of the deleted file

**Documentation updates when complete:**
- `working-docs/CHASE-QUEUE.md` — remove the `discovery/StaticNodesLoader.scala` DEAD entry from Open entries
- Add to Cleared entries log: `| discovery/StaticNodesLoader.scala | Part 8f dead code | Cleared [date]: [SHA] — DiscoveryConfig redirected to network.StaticNodesLoader; duplicate deleted. |`
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA] | Part 8f — redirect DiscoveryConfig, delete discovery/StaticNodesLoader |`
- `modernization-log/network/discovery.md` — add under "Quality Fixes":
  `#### [SHA] — Part 8f: duplicate StaticNodesLoader deleted`
  `- **What:** DiscoveryConfig redirected to network.StaticNodesLoader (stricter validation); discovery/StaticNodesLoader.scala removed`

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. While `DiscoveryConfig.scala` is open, scan it and the surrounding `network/discovery/` package for other weak-validation or duplicate patterns. Draft a prompt for anything found.

**Rejection criteria:** Deleting the production `network/StaticNodesLoader.scala` (keep that one); skipping the compile step between redirect and deletion
