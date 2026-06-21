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
| `blockchain/sync/fast/PivotBlockSelector.scala` | all `UnsubscribeAllCmd` call sites (×4) | After Command ADT narrowing, `MessageClassifier` subscriptions will be registered under `blockHeadersAdapter.toClassic` (not `ctx.self.toClassic`). The four existing `UnsubscribeAllCmd(ctx.self.toClassic)` calls (`sendResponseAndCleanup`, `ElectionPivotBlockTimeout`, `votingProcess`, `idle`) will miss all adapter-keyed subscriptions — bus key mismatch, subscriptions silently linger until CAPSTONE watch fires asynchronously. LOOM must replace each with `UnsubscribeAllCmd(blockHeadersAdapter.toClassic)`. Same fix applies to per-peer `UnsubscribeCmd` in `runningPivotBlockElection`. | EXCEPT | HERALD | 2026-06-21 |
| `consensus/pow/PoWMiningCoordinator.scala` | — | Threading model finding (R9/8d B2): FORGE-gated. See `threading-model-audit.md §B2` for detail. FORGE review required before any fix. | MUTABLE | PRISM | 2026-06-21 |

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

| EngineApiService H1 MUTABLE | `consensus/engine/EngineApiService.scala:42–75` | Cleared 2026-06-21: BEACON review complete. 6 maps (PRISM missed `acceptedChildrenByParent` line 74). CLASS A (4 payloadId-keyed) → bounded LRU+TTL, CONDUIT owner (§8c-H1-A). CLASS B (2 hash-keyed) → finalized-watermark prune, BEACON impl (§8c-H1-B). No source files touched. | — | — | 2026-06-21 |
| IMPLICIT ×3 / P4a EXCEPT | various | Cleared 2026-06-21: (1) IMPLICIT ×3 — `*Enc extends MessageSerializableImplicit`/`RLPSerializable` subtype polymorphism + `ReceiptBloom*` wildcard collision — permanent deferrals (P3b); no fix planned. (2) P4a SSC `GetProgress` idle-state gap — fixed `74db726d1`. | — | — | 2026-06-21 |
| NPMA CLASSIC (NET-01) | `NetworkPeerManagerActor.scala:165,451,663` | Cleared 2026-06-21: HERALD verified by-design — `classicSystem.scheduler` is correct interop bridge for Classic `AddToBlacklistCmd.replyTo`; same `HashedWheelTimer` backing. Logged as NET-01 in DEFERRED-BACKLOG Part 2 NET group for LOOM cleanup when PMA migrates. | — | — | 2026-06-21 |
| `BlockExecution.applyEip2935` account-existence gap | `blockchain/ledger/BlockExecution.scala` | Cleared 2026-06-21: FORGE verdict — fix required before Olympia activation. `applyEip2935` else-branch writes storage unconditionally with no account-existence guard (unlike `applyEip4788`). Not a production sequential-execution risk but a Hive EIP-2935 compliance blocker and test-construction trap (`emptyWorld` + post-activation block → `IllegalStateException` in `getGuaranteedAccount`). Fix: drop `isActivationBlock &&` from `w1` branch condition; add `BlockHashHistorySpec` absent-account test. Full spec in DEFERRED-BACKLOG §G5 note. Owner: BEACON + FORGE review before commit. | — | FORGE | 2026-06-21 |
| PoWMining.scala MUTABLE (@volatile redundancy) | `consensus/pow/PoWMining.scala` — `minerCoordinatorRef`, `mockedMinerRef`, `minerSystem` | Cleared 2026-06-21: `551e9bf31` — removed `@volatile` from all 3 field declarations. `mutex.synchronized` already provides happens-before; removal is cosmetic, no logic change. scalafmtAll clean. Compile blocked by pre-existing `BytecodeRecoveryActor.scala:202` (unrelated); `testOnly *PoWMining* *Mining*` pending that fix. 1 file, 3 lines changed. | — | MITHRIL | 2026-06-21 |
| PoWMining.scala MUTABLE (startMiningProcess) | `consensus/pow/PoWMining.scala:103–132` | Cleared 2026-06-21: FORGE assessment — **SAFE AS-IS, no fix required.** `mutex.synchronized` wraps the entire compound check-then-act (both isEmpty guards and the write), so no race is possible. `startMiningProcess` is private and called exactly once sequentially at boot via `StdNode.start()`. A double-spawn would cause duplicate mining work only — no consensus violation (Ethash verification, ECIP-1017 rewards, state root, fork dispatch all unaffected). `AtomicBoolean` would be redundant. `@volatile` on the three fields is redundant given the synchronized block (cosmetic only — safe to remove in a MITHRIL pass). MITHRIL correctly flagged the pattern but the guard was already correct. | — | FORGE | 2026-06-21 |
| RegularSync divergence path EXCEPT | `BlockImporter.scala:797` `handleForkRecovery` | Cleared 2026-06-21: HERALD audit confirmed gap — three-path recovery (stale-tip rewind / UnknownBranch rewind / handleForkRecovery) all use blind 128-block rewind with no LCA knowledge. Will eventually converge (iterates 128 blocks per ForkDetectThreshold cycle) but destructively mutates canonical chain during recovery. MESS makes >128-block forks near-impossible on ETC mainnet. Fix spec: generalize `FastSyncBranchResolverActor.fastSync: ClassicActorRef` → `replyTo: ActorRef[BranchResolverResponse]`, add `ResolvingFork` behavior to `BlockImporterLogic`, replace 4-line blind rewind with actor spawn + response. Size S. Routed to DEFERRED-BACKLOG. | — | — | 2026-06-21 |

---

## SNAP2 replyTo adapter dependency (S2 → SNAP2, 2026-06-21)

`FastSyncBranchResolverActor` now receives `replyTo: ActorRef[BranchResolverResponse]`. FastSync (`Behavior[Any]` core) currently passes `ctx.messageAdapter[BranchResolverResponse](identity)` as the replyTo and routes the response through its `Any` handler. When FastSync is narrowed to `Behavior[Command]` in the network/P2P sprint, that adapter and the replyTo pass will need replacing with a proper typed adapter or a direct Typed self-ref. Not a blocker now — the `identity` adapter is correct for `Behavior[Any]`. Flag at SNAP2 re-entry.

---

## E165 TestProbe pattern in BranchResolverSpec (S2, 2026-06-21)

5 pre-existing E165 warnings in `FastSyncBranchResolverSpec` — pattern selectors on `Any` from `TestProbe` receive type. Not introduced by S2. Resolvable by narrowing the probe's type parameter, but touches the test harness pattern used across the entire spec. Defer to a dedicated test-harness cleanup pass.

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
