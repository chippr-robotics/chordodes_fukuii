# Fukuii Modernization — Wave 2 Sprint Queue

**Branch:** `scala3-cleanup-june`
**Last updated:** 2026-06-19 (SNAP1 ✅ — SNAPSyncController Classic→Typed migration complete. Phases 1–4 done. Commit `4d6fcdf6a`. 3,491/0 testEssential. OQ-5 (GetStatus/GetProgress) deferred to SyncController migration scope.)
**Gate:** R0 audit (`codebase-completeness-audit.md`) must be reviewed before starting W2-P1.
**Authoritative backlog:** `DEFERRED-BACKLOG.md`

Thread prompts for Wave 2 implementation. One thread per entry. Sequential within groups.

---

## ⚠️ Pre-flight for every thread

```bash
# Verify branch is correct
git branch --show-current   # must be: scala3-cleanup-june

# Verify clean compile baseline before starting
sbt compile-all             # must be: 0 errors
```

---

## W2-P0 — Audit Fix-Now Items ✅ COMPLETE

**Commits (post-rebase SHAs):** `308a64fc6` (A — duplicate imports), `13b883dbc` (B — HealingTask), `5f54c98a0` (C — E029), `89f49d315` (tooling: fukuii-test script)
**14/14 targeted tests passed.**

### Task A — E198 Duplicate Metric Imports (trivial, ~10 min, WRAITH)

**Files:**
- `src/main/scala/.../metrics/EngineApiMetrics.scala`
- `src/main/scala/.../metrics/PoWMiningMetrics.scala`

Remove 13 duplicate `import io.micrometer.core.instrument.Gauge` lines — one occurrence per file
is correct, the rest are redundant. Verify with:
```bash
grep -n "import io.micrometer.core.instrument.Gauge" \
  src/main/scala/com/chipprbots/ethereum/metrics/EngineApiMetrics.scala \
  src/main/scala/com/chipprbots/ethereum/metrics/PoWMiningMetrics.scala
```
Post-fix: `sbt compile-all` → E198 count decreases by 13.
Commit: `fix(scala3): remove duplicate Gauge imports in metrics files`

---

### Task B — HealingTask Case Class Mutability (MITHRIL, ~30 min)

Locate the file:
```bash
grep -rn "case class HealingTask\|class HealingTask" src/main/ --include="*.scala"
```

**Problem:** `var` fields in a `case class` break structural equality and `copy()` semantics.
Any caller using `.copy(field = newVal)` silently gets stale state from the original vars.

**Fix decision (MITHRIL reads the file first):**
- If vars assigned once at construction → convert to `val`
- If vars mutated after construction → convert to a regular `class` (drop `case`)
- If mutation needed AND equality semantics matter → split into immutable key + mutable wrapper

Identify all mutation sites and all `.copy()` callers before changing anything.

Post-fix: `sbt compile-all`. Run `sbt testEssential` only if HealingTask has direct test coverage
(`grep -rn "HealingTask" src/test/ --include="*.scala"`).
Commit: `fix(scala3): resolve case class mutability in HealingTask`

---

### Task C — E029 Non-Exhaustive Capability Matches (HERALD → WRAITH, ~1h)

**Risk:** Runtime crash — `MatchError` thrown when an unknown-Capability peer connects.

**Files:**
- `src/main/scala/.../blockchain/sync/SyncStateSchedulerActor.scala:103`
- `src/main/scala/.../network/p2p/messages/BlockBroadcast.scala:84`

**Step 1 — HERALD review (mandatory before any edit):**
Invoke HERALD to read both files at the flagged lines and answer:
- Is `Capability` a sealed type? If so, add the missing cases explicitly rather than a wildcard.
- If not sealed: is a `MatchError` correct behavior (reject peer), or should an unknown capability
  be logged and ignored?
- For SyncStateSchedulerActor: does the match gate sync state transitions or just filter peer lists?
- For BlockBroadcast: does the match gate broadcast eligibility?

**Step 2 — Fix (WRAITH, after HERALD decision):**
Typical fix when capability should be ignored gracefully:
```scala
case unknown =>
  log.debug(s"Ignoring unknown capability: $unknown")
  <appropriate no-op value for the return type>
```
If sealed and exhaustive cases are available: add the missing case arms instead.

Post-fix:
```bash
sbt compile-all   # E029 count drops by 2
sbt scalafmtAll
sbt testEssential # required — touched sync/network files
```
Commit: `fix(network): handle unknown Capability in sync + broadcast (E029 exhaustive match)`

---

## W2-P1 — Remaining Compiler Warnings ✅ COMPLETE

**Commit (post-rebase):** `bd2d691c3` — All 6 warnings eliminated. `sbt compile-all` → 0 errors, E165 (Pekko Classic) only.

**Files:**
- `consensus/validators/BlockHeaderValidatorSkeleton.scala:218` (unused `_blockchainConfig`)
- `blockchain/sync/PeersClient.scala:326` (unused `_peer` in `adaptMessageForPeer`)
- `extvm/VMClient.scala:22` (unused constructor param `_externalVmConfig`)
- `db/storage/PathNodeStorage.scala:57,103` (unused `hash` params — assess blast radius first)
- `network/p2p/messages/ETHPackets.scala:106` (E092 `@unchecked` workaround)

**Read before starting:**
- `DEFERRED-BACKLOG.md` Part 1 (warning detail + fix descriptions)

**Kickoff prompt:**
> Read `DEFERRED-BACKLOG.md` Part 1 on branch `scala3-cleanup-june`.
> Fix all 6 remaining compiler warnings. For PathNodeStorage #4/#5: grep for all callers of
> `writeAccountNode` and `writeStorageNode` to assess blast radius before removing the param.
> For ETHPackets E092: replace `case indexed: IndexedSeq[RLPEncodeable @unchecked]` with
> `encodables.toIndexedSeq` per the fix description. After all fixes: `sbt compile-all` →
> expect 0 errors, warnings = E165 count only (no new warnings). One commit: `fix(scala3): eliminate remaining compiler warnings`.

**Verification:** `sbt compile-all` → 0 errors, warnings consist only of E165 Pekko Classic
**Rejection:** Do NOT touch consensus/ files — BlockHeaderValidatorSkeleton is in consensus/validators/
but the fix (removing an unused param) does not change consensus behavior; no FORGE needed.

---

## W2-P2a — Faucet Subsystem (Pekko Typed) ✅ COMPLETE

**Commit (post-rebase):** `551bccfaf` — 7 files: FaucetHandler (sealed Command ADT, two state behaviors), FaucetSupervisor (Classic→Typed adapter + nested onFailure supervision), FaucetRpcService (AskPattern + IO.fromFuture), FaucetBuilder, deleted FaucetHandlerSelector. Tests: ScalaTestWithActorTestKit, Typed TestProbes. 14/14 tests passed, 0 faucet E165 warnings remaining.

**Files:**
- `faucet/FaucetHandler.scala` — 3-state machine: unavailable → initialized → processing
- `faucet/FaucetSupervisor.scala` — BackoffSupervisor wrapper
- `faucet/FaucetRoute.scala` or `faucet/Faucet.scala` — spawning site (check for ActorRef)

**Key pattern:**
```scala
// FaucetSupervisor: BackoffSupervisor → Behaviors.supervise
// Before (Classic):
BackoffSupervisor.props(BackoffOpts.onFailure(FaucetHandler.props(...), ...))
// After (Typed):
Behaviors.supervise(FaucetHandler())
  .onFailure[Exception](SupervisorStrategy.restartWithBackoff(minBackoff, maxBackoff, randomFactor))
```

**Read before starting:**
- `pekko-typed-migration-p2.md` (FaucetHandler migration guide)
- `/media/dev/2tb/dev/fukuii/.claude/agents/loom.md` (LOOM agent brief)
- `/media/dev/2tb/dev/scala/pekko/actor-typed/` BackoffSupervisor → `Behaviors.supervise` equivalents

**Kickoff prompt:**
> Use the LOOM agent to migrate the `faucet/` subsystem to Pekko Typed on `scala3-cleanup-june`.
> Files: FaucetHandler.scala, FaucetSupervisor.scala, and their spawning site.
> Read `pekko-typed-migration-p2.md` (FaucetHandler section), `DEFERRED-BACKLOG.md` Part 2,
> and the LOOM agent brief. Key: BackoffSupervisor → `Behaviors.supervise(...).onFailure`.
> After migration: `sbt compile-all` → E165 count decreases by ~2. `sbt scalafmtAll`.
> One commit: `refactor(pekko): migrate faucet subsystem to Typed actors`.

**Verification:** `sbt compile-all` → E165 count lower than baseline; no new errors
**Rejection:** Stop if eventStream is discovered in FaucetHandler (unexpected) — audit first.

---

## W2-P2b — JsonRpc Subsystem (Pekko Typed) ✅ COMPLETE

**Commits (post-rebase):** `2ac71a58e` (migration), `1309bb968` (scalafmt pass) — FilterManager (Behaviors.setup closure, ctx.scheduleOnce, future.foreach(replyTo ! _)), SubscriptionManager (ctx.messageAdapter bridging Classic eventStream to Typed commands, PostStop cleanup), EthFilterService + JsonRpcWsServer (AskPattern), NodeBuilder (system.spawn). ManualTime in FilterManagerSpec, Behaviors.ignore stubs in 3 dependent fixtures. 22/22 targeted tests passed. eventStream messages (NewBlockImported, NewPendingTransaction) confirmed local-only.

## W2-P2b — JsonRpc Subsystem (Pekko Typed) [archived spec]

**Prerequisite:** W2-P2a committed (establishes pattern; not a hard dep, but keeps history clean)

**Files:**
- `jsonrpc/FilterManager.scala` — 8-arm dispatcher with `sender()` replies, `scheduleOnce`
- `jsonrpc/SubscriptionManager.scala` — `eventStream.subscribe/unsubscribe`, `sender()` replies
- Any `EthFilterService` or WS route that holds a Classic `ActorRef` to these actors

**EventStream pre-flight (run before starting):**
```bash
grep -rn "eventStream\.subscribe\|eventStream\.tell\|eventStream\.publish" \
  src/main/scala/com/chipprbots/ethereum/jsonrpc/ --include="*.scala"
```
Identify message types passed to `eventStream.subscribe`. Are they already Typed-compatible?

**Key pattern for SubscriptionManager eventStream:**
```scala
// Typed eventStream:
ctx.system.eventStream.tell(EventStream.Subscribe[MessageType](ctx.self))
ctx.system.eventStream.tell(EventStream.Publish(event))
```
If message types are not sealed/case classes compatible with Typed, use the `toClassic.eventStream`
bridge as a temporary measure and document for follow-up.

**Read before starting:**
- `pekko-typed-migration-p2.md` (FilterManager and SubscriptionManager sections)
- `/media/dev/2tb/dev/scala/pekko/actor-typed/` EventStream.Subscribe API

**Kickoff prompt:**
> Use the LOOM agent to migrate the `jsonrpc/` filter subsystem to Pekko Typed on `scala3-cleanup-june`.
> Files: FilterManager.scala, SubscriptionManager.scala, and their caller sites in EthFilterService.
> Read `pekko-typed-migration-p2.md`, `DEFERRED-BACKLOG.md` Part 2, and the LOOM agent brief.
> FIRST: run the eventStream pre-flight grep to map SubscriptionManager's message types.
> After migration: `sbt compile-all`, `sbt scalafmtAll`. E165 count should drop by ~4 (2 actors
> + any adapter imports). One commit: `refactor(pekko): migrate jsonrpc filter subsystem to Typed actors`.

**Verification:** `sbt compile-all` → E165 count lower; no new errors
**Rejection:** Stop if SubscriptionManager eventStream types cross node boundaries — run full
`@SerializabilityTrait` pre-flight and surface to user before continuing.

---

## W2-P2c — Transactions Subsystem (Pekko Typed) ✅ COMPLETE

**Commit:** `0be6dd776` — PTM (sealed Command ADT, GetPendingTransactionsReq(replyTo), WrappedPeerEvent adapter, toClassic.eventStream.publish bridge), STFA (MailboxSelector.bounded(50000) at spawn site), EngineApiService + BlockImporter (typed sends), RegularSync + SyncController + BlockchainHostActor (field type update only). Fixed runtime bug: PTM was subscribing context.self directly to peerEventBus — corrected to context.messageAdapter[PeerEvent](WrappedPeerEvent.apply). 197/197 targeted tests passed.

**Agent:** LOOM + FORGE
**Estimated time:** ~4h
**Prerequisite:** W2-P2b committed; ⚠️ FORGE review BEFORE implementation

**Files:**
- `transactions/PendingTransactionsManager.scala` — 30+ callers, `eventStream.publish`, child spawn
- `transactions/SignedTransactionsFilterActor.scala` — `BoundedMessageQueueSemantics` child of PTM

**FORGE review (mandatory first step):**
> Invoke FORGE agent to assess impact of migrating PendingTransactionsManager from Pekko Classic
> to Typed. Scope: eventStream.publish message types (are any consensus-critical?), the 30+
> caller sites, and whether `SignedTransactionsFilterActor`'s `BoundedMessageQueueSemantics`
> affects transaction ordering or mempool correctness.

**Serialization pre-flight (run before impl, after FORGE):**
```bash
grep -rn "eventStream\.publish\|eventStream\.tell" \
  src/main/scala/com/chipprbots/ethereum/transactions/ \
  src/main/scala/com/chipprbots/ethereum/blockchain/ \
  --include="*.scala"
```
Capture all published message types. If any are used for cross-node coordination, add
`@SerializabilityTrait` annotation and use `CircePekkoSerializer` for new codecs.

**Typed bounded mailbox replacement:**
```scala
// Classic: RequiresMessageQueue[BoundedMessageQueueSemantics]
// Typed: pass MailboxSelector to spawn site
context.spawn(SignedTransactionsFilterActor(), "stfa",
  MailboxSelector.bounded(capacity))
```

**Kickoff prompt:**
> ⚠️ FORGE REVIEW REQUIRED FIRST.
> Invoke the FORGE agent to assess PendingTransactionsManager migration impact on
> transaction pool correctness and any consensus-adjacent eventStream types.
> After FORGE sign-off: Use LOOM to migrate the `transactions/` subsystem on `scala3-cleanup-june`.
> Files: PendingTransactionsManager.scala, SignedTransactionsFilterActor.scala.
> Run the serialization pre-flight grep before writing any code.
> Read `pekko-typed-migration-p2.md` (PTM + STFA sections) and `DEFERRED-BACKLOG.md` Part 2.
> After migration: `sbt compile-all`, `sbt scalafmtAll`.
> One commit: `refactor(pekko): migrate transactions subsystem to Typed actors`.

**Verification:** `sbt compile-all` → E165 count lower; `sbt testEssential` → 3,519 tests pass
**Rejection criteria:**
- FORGE objects → do not proceed until FORGE sign-off
- eventStream.publish types found crossing network boundary → PSH pre-flight required
- PTM callers > 35 (unexpected growth) → re-scope, surface to user

---

## W2-P2d — Consensus/Mining Subsystem (Pekko Typed) ✅ COMPLETE

**Commit:** `0aa837d5e` — 19 files. OmmersPool: sealed Command ADT, immutable state via recursive `running()`, explicit replyTo. MockedMiner: 4-state `context.become` → per-state Behaviors, `pipeToSelf`, `context.scheduleOnce`. FORGE-approved; ommer ordering invariants preserved. E165 = 0 across all 7 actors migrated in P2a–P2d.

**What was NOT eliminated (see DEFERRED-BACKLOG.md Part 2 bridge table):**
- `system.spawn` via `adapter._` — root `NodeBuilder` system is still Classic; cannot flip until network/P2P sprint
- `system.toTyped.scheduler` — same root dependency
- `MockedMiner.Send(msg, replyTo)` envelope — `PoWMining.sendMiner/askMiner` still wraps the Typed ref; cleanup is a concrete task in the network/P2P sprint (see Part 6 below)

**Agent:** LOOM + FORGE
**Estimated time:** ~3h
**Prerequisite:** W2-P2c committed; ⚠️ FORGE review BEFORE implementation

---

## W2-P3a — implicit → given/using ✅ COMPLETE

**Commits:** `3d1591049` (E003 Step 1) + `7210311bb` (GivenUsing Step 2)
**testEssential:** 3,593 / 0 (exit 0)

**Step 1 (`3d1591049`):** 31 `self: X with Y` → `self: X & Y` replacements across 4 files. Zero E003 warnings remain.

**Step 2 (`7210311bb`):** 334 `implicit val/lazy val/def` → `given` across 83 main-source files + 2 test files. Notable manual fixups scalafix couldn't auto-handle:
- **Anonymous givens missing types** — `DiscoveryServiceBuilder.scala`: added explicit type annotations to 2 anonymous local givens (ambiguous given search)
- **`import X.*` doesn't pull givens** — fixed 9 call sites to use `import X.{given, *}`: `BlockBodiesStorage`, `BlockHeadersStorage`, `FaucetJsonRpcController`, `WalletRpcClient`, `JsonRpcController`, `GenesisBlockResponseSpec`, `PicklerOlympiaSpec`
- **`given` is implicitly `final`** — reverted 3 overrideable instances back to `implicit val/lazy val` to preserve subclass override chains: `JsonMethodsImplicits.formats`, `PeerDiscoveryManagerBuilder.ioRuntime`, `PortForwardingBuilder.ioRuntime`

---

## W2-P3b — implicit class → extension methods ✅ COMPLETE

**Commit:** `c0a3612b4` — 29 files changed (210 ins / 216 del)
**Validation:** `sbt compile-all` → 0 errors. `testOnly *ETH* *SNAP* *Wire* *Transaction*` → 204/204.

All AnyVal implicit classes and Dec/codec implicit classes converted to extension methods across non-consensus `src/main/`. Call-site fixes: `BlockBody.scala`, `EthTxService.scala`, `GraphQLSchema.scala`, `MessageDecoders.scala`, + 3 test files.

**Kept as `implicit class` (documented in DEFERRED-BACKLOG.md — subtype/trait constraints):**
- All `*Enc extends MessageSerializableImplicit` — subtype polymorphism required
- `SignedTransactionEnc`, `MptNodeEnc` — `toBytes` from `RLPSerializable` trait used at call sites
- `TxLogEntryRLPEnc`, `ReceiptBloomEnc`, `ReceiptBloomFreeEnc` — name collision with `ReceiptCodecs` extensions under wildcard import

---

## W2-P3c — isInstanceOf / asInstanceOf Audit ✅ COMPLETE

### Part 1 — Non-consensus pass ✅ DONE (`4459619c9`)

All 9 non-consensus occurrences replaced — none deferred:

| File | Occurrences | Fix |
|------|-------------|-----|
| `ConsensusImpl.scala` | 4 (2 sites × guard+cast) | `MPTError(reason: MissingNodeException)` nested type pattern — covers all subtypes |
| `PoWMining.scala` | 1 | `withBlockGenerator: explicit match { case pg: PoWBlockGenerator => ... }` + IAE |
| `TestMiningBuilder.scala` | 1 | `buildMining() match { case tm: TestMining => tm }` + RuntimeException |
| `BlockBody.scala` | 3 | Each `items(N).asInstanceOf[RLPList]` → pattern match + descriptive exception |
| `BlockHeader.scala` | 1 | `toRLPEncodable match { case rl: RLPList => rl }` — invariant documented in original comment |
| `Block.scala` | 4 | Same RLPList pattern for all 4 Shanghai+ decode items |

`sbt compile-all` → 0 errors. `testOnly *Consensus*` → 23/23. `testOnly *Block* *Mining*` → 264/278 (14 failures = pre-existing baseline on `BlockchainHostActorSpec` + `PivotBlockSelectorSpec`, unrelated).

### Part 2 — Consensus/domain pass ✅ DONE (`62fa642d7`, BEACON)

5 of 8 replaced — all `isInstanceOf` guards paired with `asInstanceOf` casts → nested `SignedTransaction(blobTx: BlobTransaction, _)` destructuring (4 sites) + `stx @ SignedTransaction(_: BlobTransaction, _)` binding (1 site, `stx.hash` also needed). 2 left as-is (lines 567 + 599 — already idiomatic typed `case` matches, not `isInstanceOf`). `Transaction` is sealed with 5 subtypes; `BlobTransaction` is the only blob-data carrier — replacements semantically complete. `compile-all` ✓, `testOnly *Engine*` 16/16 ✓, `testOnly *Blob*` 59/59 ✓.

---

## End-of-Wave-2 Validation

After all W2 threads are complete:

```bash
sbt clean && sbt compile-all   # full clean compile — 0 errors, 0 warnings
sbt formatAll                  # no diffs
sbt testEssential              # 3,601 tests, 0 failures
```

---

## Wave 2 State Assessment — What's Real and What Remains

**Wave 2 Pekko migration is functionally complete but architecturally incomplete.**

All 7 actors are genuinely Typed (no Classic code inside any of them). However, the root
`NodeBuilder` system is still a Classic `ActorSystem`. This means:

| What's done | What's deferred |
|-------------|-----------------|
| 7 actors with sealed Command ADTs, Behaviors.receive, immutable state, replyTo | Root `ActorSystem` still Classic — flip requires network/P2P sprint |
| 0 E165 warnings remaining | 4 co-existence bridges in NodeBuilder/PoWMining/PTM |
| Pekko Typed patterns throughout the migrated subsystems | `MockedMiner.Send` envelope in `PoWMining` — production code with incomplete API absorption |

**This is not a shortcut or a bandaid.** Pekko explicitly supports this co-existence
transition pattern. The bridges are load-bearing scaffolding, not hacks. But the modernization
is **not complete** until the root is flipped.

**The completion gate is the network/P2P sprint (Wave 3 Part 6).** That sprint:
1. Migrates ~20 remaining Classic actors (`SyncController`, `PeerActor`, `PeerManagerActor`,
   `RLPxConnectionHandler`, `SNAPSyncController`, `RegularSync`, etc.)
2. Flips `NodeBuilder.system` from Classic `ActorSystem` to `ActorSystem[Nothing]`
3. Removes all 4 co-existence bridges simultaneously
4. Absorbs `MockedMiner.Send` envelope into the `Command` ADT

**This sprint is not optional.** It is the second half of the Pekko modernization. R1 in
`WAVE3-RESEARCH-PLAN.md` produces the migration plan for it.

---

## Part 6 — Network/P2P Pekko Migration (Wave 3 Completion Sprint)

**Status:** R1 research COMPLETE → `network-sync-pekko-migration-plan.md` (2026-06-17)
**This completes the Pekko modernization.** 35 Classic actors, 25,944 LOC, ~32–37 days.
**Full plan:** `.local/docs/moderization-review-june/network-sync-pekko-migration-plan.md`

### Group execution order

| Order | Group | Actors | LOC | Days | Agent | Gate | Status |
|-------|-------|--------|-----|------|-------|------|--------|
| 1 | **W1** | SNAP workers ×4 (AccountRangeWorker, StorageRangeWorker, ByteCodeWorker, TrieNodeHealingWorker) | 517 | 1.0 | LOOM | None | ✅ DONE `77bde2a25` |
| 2 | **W2** | Network utilities ×3 (KnownNodesManager, PeerStatisticsActor, ServerActor) | 316 | 1.0 | LOOM | None | ✅ DONE `f1c348dfe` — 10 files; TcpEventBridge Classic child for ServerActor (TCP Connected sender capture); ctx.messageAdapter PeerEventBus bridge into PeerStatisticsActor; Classic bridge in NodeBuilder/CommonFakePeer for GetKnownNodes case object; PeerManagerActor peerStatistics field type + AskPattern fix |
| 3 | **S1** | Sync recovery atoms ×3 (CombinedRecoveryScanActor, BytecodeRecoveryActor, StorageRecoveryActor) | 697 | 1.5 | LOOM | None | ✅ DONE `ece78c90f` — 11 tests pass; Behavior[Any] + named scanning/downloading; withTimers abandon; watchWith coordinator crash; ctx.self.toClassic as sender() replacement for RequestRecentRoot flow; SyncController 4 spawn sites → context.spawn(...).toClassic (no body surgery) |
| 4 | **S2** | Fast sync leaf ×2 (StateStorageActor, FastSyncBranchResolverActor) | 398 | 0.5 | LOOM | None | ✅ DONE — StateStorageActor `9f20ec92b`, FastSyncBranchResolverActor `22bbdb926` (PLN session) |
| 5 | **PLN** | PeerListSupportNg → Typed-compatible peer-list helper (shared infrastructure) | ~200 | 1.5 | HERALD+LOOM | None | ✅ DONE `22bbdb926` — PeerListHelper.scala (179 LOC): stateful class, explicit constructor args, peerEventBus.tell(msg, peerDisconnectedAdapter.toClassic) for sender() resolution; PeerListSupportNg untouched; FastSyncBranchResolverActor migrated as proof-of-concept (Behavior[Any] — PeerRequestHandler context.parent hardcoding; same pattern as BytecodeRecoveryActor/StorageRecoveryActor); S2 COMPLETE |
| 6 | **S6** | ChainDownloader | 897 | 1.0 | LOOM | PLN | ✅ DONE `581376757` — Behavior[Any], both SyncController + SNAPSyncController spawn sites updated, PeerListHelper wired, 16 tests pass. ⚠️ Metric note: E165 = unmatchable-Any (rises with Behavior[Any]). Track E003 (Classic actor deprecation) for migration progress. |
| 7 | **S5** | Regular sync ×3 (BlockBroadcasterActor, BlockImporter, RegularSync) | 1,120 | 2.0 | LOOM | W1 + PLN | ✅ DONE `5d29511d4` — BlockBroadcasterActor: Behavior[BroadcasterMsg]; BlockImporter+RegularSync: Behavior[Any] (mixed Classic+Typed sources); PeerListHelper replaces PeerListSupportNg; 19 Classic actors remain. 69 pre-existing jsonrpc failures fixed `92584a07b`. Baseline: 3,519 / 0 failures. |
| 7 | **NET** | Network core ×6 | 3,479 | 4–5 | HERALD+LOOM | W2 + HERALD-1 + HERALD-2 | ✅ DONE. RLPxCH `f8a127870`, PeerActor `e6ccc5ac1`, PEA `59f7a1f11`, PDM `81eb751f3`, PMA `05e0c003b`+`0e9952f06` (shell+core; 8 sender→replyTo), BHA `8ef6a4601` (pure Behavior[Command]; messageAdapter subscription; 3 spawn sites; test wrapping). Baseline 3,621/0. |
| 8 | **S3** ✅ | SNAP coordinators ×4 (ByteCodeCoordinator, StorageRangeCoordinator, AccountRangeCoordinator, TrieNodeHealingCoordinator) | 6,454 | 4.0 | HERALD+LOOM | W1, NET, HERALD-5 | ✅ 4/4. BCC `4f214db16`+`86930f9b1`. SRC `368c03560`+`0b43de007`. ARC `9a57e9624`+`6ea84b48d`. TNHC `7a48c5988`+`691dde27f` — 11 returns (not 10), RestartResumeVerification/RestartFullRebuild Commands for @volatile marshal, asyncLog for 10 off-thread BFS sites. Baseline 3,601/0. **NET2 unblocked.** |
| 9 | **S7** ✅ | PeersClient + PeerRequestHandler | 662 | 1.5 | HERALD+LOOM | NET | ✅ DONE `4d7797cae` — shell+core; Classic shell captures `sender()` for Request[?]; Typed core owns PeerListHelper + id-keyed PRH spans; `behavior()` factory added to PRH companion; PeerListHelper `updateEthRate()` added. 3,601 / 0 (exit 0, 674s). |
| 10 | **S4** ✅ | SyncStateSchedulerActor + PivotBlockSelector | 987 | 2.0 | HERALD+LOOM | NET + PLN | ✅ DONE `df703539e` — shell+core (Behavior[Any]); `activeHandlers: Map[PeerId, ClassicRef]` replaces `sender()`; `fiberLog` for CE IO-fiber logging (critical bug: `ctx.log` on compute thread → UnsupportedOperationException, silently swallowed by pipeToSelf, stalling sync); 5/5 StateSyncSpec. 3,593 / 0 (exit 0, 705s). |
| 11 | **NET2** | NetworkPeerManagerActor | 1,442 | 2.0 | HERALD+LOOM | NET, S3 ADT defined, HERALD-3 | ✅ DONE `5b8762900` — shell+core split; Impl class (8 var + 3 mutable coll); withTimers ×3; 2 ask-path replies at shell; snapSyncControllerOpt stays Option[ActorRef] (Classic). 184/184 targeted, 3,601/0 testEssential. |
| 12 | **SNAP1** ✅ | SNAPSyncController (5,178 LOC, 6 named behaviors) | 5,178 | 10–14d | HERALD+LOOM | W1, S3, S6, NET, NET2, HERALD-4 | ✅ DONE `4d6fcdf6a` — sealed Command ADT; Impl class (58 var fields); 13 keyed timers; `.orElse` → helper calls; `aroundReceive` inlined; 4 coordinators retyped; NPMA SNAP routing wrapped; ARC/NPMA test specs updated (3 sites). OQ-5 (GetStatus/GetProgress) deferred → SyncController scope. 3,491/0 testEssential. |
| 13 | **SNAP2** | FastSync | 1,415 | 2.5 | LOOM | S2, S4, S7 | ✅ DONE — Phase 1 `df59c326c` (sealed Command ADT), Phase 2 `501255dfd` (Classic shell + `Behavior[Any]` core, shell+core pattern as S4/S7; PeerListSupportNg→PeerListHelper; 7 context.become→behaviors; raw scheduler→withTimers; Classic PRH children via ctx.toClassic.actorOf, watchWith→RequestTerminated; 11 log.warning→log.warn; syncController explicit). Phase 3 no-op: shell preserves Classic `FastSync.props`, SyncController `context.actorOf` unchanged. Phase 4: FastSync targeted 34/35 (1 fail = pre-existing "does not crash" 60s timeout, verified identical on baseline `4d6fcdf6a` — NOT a regression); testEssential **3,593 / 0** (3491 main + 26 rlp + 11 bytes + 65 crypto), exit 0. P11 clean (core has no Future/IO/pipeToSelf; single `ctx.log` captured as SLF4J `log` member at init). |
| 14 | **ROOT** ✅ | PivotHeaderBootstrap + SyncController | 2,244 | 3.0 | LOOM | ALL above | ✅ DONE — PivotHeaderBootstrap → `Behavior[Command]` (sealed inbound ADT; `replyTo` Classic ref for Completed/Failed; `Behaviors.withTimers`; `asyncLog` in off-thread `peersClient ?` callbacks). SyncController → `Behavior[Any]` (Impl class; 11 named behavior factories replace 23 `context.become`; 7 `sender()`→`ctx.toClassic.sender()` + stored-sender slots; `watchWith` + 5 termination markers replace `context.watch`/`Terminated`; 8 schedulers → `withTimers`/Classic-tell `scheduleOnce`; 34 `log.warning`→`log.warn`; `withPostStop` interceptor for FCM clearListener). **OQ-5 RESOLVED:** `SyncProtocol.GetStatus` kept as-is (no replyTo) — reply via `ctx.toClassic.sender()` (RegularSync idiom); `syncController` stays Classic `ActorRef` to all callers via `.toClassic`; NO caller changes (EthInfoService/NodeJsonRpcHealthChecker/McpTools/McpResources). NodeBuilder: `system.spawn(SyncController(...)).toClassic`. Tests via `TestActorRef(PropsAdapter(...))`. Commits `e9ed984b7`+`aa166423d`+marker-fix. testEssential **3,593/0**. ⚠️ 7 SyncControllerSpec fast-sync choreography tests (`SyncTest`-tagged, excluded from testEssential) fail — **pre-existing from SNAP2**, verified identical on parent `501255dfd`; NOT a ROOT regression. Only CAPSTONE remains. |
| — | **CAPSTONE** ✅ | Root flip: `ActorSystem[Nothing]`, bridge removal, MockedMinerProtocol cleanup | — | 1.0 | LOOM | ROOT | ✅ **COMPLETE**. Done: Phase 1 `b392fe8d9`, 2a `9e73fd918` (PEA), 2b `d58034056` (PMA), 2c `8603eebae` (PRH), **2d** `5b5b4da60` (PeersClient shell + PeerListSupportNg), 2e `8f1a777bc` (SSA), 2f `86aac19b6` (FastSync), 2g `81153462c` (NPMA), Phase 3 `f5ece260a` (Typed EventStream.Publish), fixes `78f771702`+`bc2a7a2fc`, FLOW agent `f97dfc583`, P12/P13 `560008b58`, post-mortem `e6a29a7ab`. **Phase 2d**: removed Classic `PeersClient extends Actor` shell; `Request` now carries Typed `replyTo: ActorRef[ResponseMessage]` (public commands ARE the Command ADT); `FetchRequest`/`PivotHeaderBootstrap` Classic `?` ask → Typed `AskPattern`; 5 fetcher consumers (`Headers`/`Bodies`/`BodiesSlice`/`StateNode`/`BlockFetcher`) + `RegularSync` `peersClient: ActorRef` → `ActorRef[PeersClient.Command]`; 7 SyncController spawn sites `actorOf(props)` → `ctx.spawn(behavior)`, `PoisonPill` → `ctx.stop`; orphaned self-typed `PeerListSupportNg` trait body removed (only `PeerWithInfo` data class kept). Test reply mechanism: Typed ask sets no Classic `sender()` → all `peersClient.reply`/`lastSender` replaced by capturing `Request.replyTo`. **Zero `extends Actor` in src/main**; only 3 intentional `extends ClassicActor` TCP bridges remain (ServerActor, RLPxConnectionHandler ×2). Targeted: PeersClient/PivotHeaderBootstrap/FetchRequest 26/26, BlockFetcher/StateNodeFetcher 17/17, RegularSync 31/31, SyncController 84/84. |

### HERALD pre-flight sessions (mandatory before their groups)

| Session | Before group | Scope |
|---------|-------------|-------|
| HERALD-1 | NET | PeerActor, RLPxConnectionHandler — RLPx auth handshake → Typed, Snappy restart semantics | ✅ PASS — No Behavior[Any] for either; TCP adapter for RLPx; clean ADT for PeerActor; helloAckPending/helloWriteAcknowledged must be awaitInitialHello() params; migration order: RLPxConnectionHandler → PeerActor → PeerManagerActor |
| HERALD-2 | NET | PeerManagerActor — peerFactory → typed spawn, PeerActor supervision | ✅ CONDITIONAL (Large) — 4 constraints: (1) PeerActor must be fully migrated first; (2) PeerEventBusActor subscriber → either migrate PEA first or interpose Classic bridge; (3) PeerId.fromRef identity must be verified for Typed actor paths before writing Terminated handler; (4) 8 sender() → replyTo paths (GetPeers, DisconnectPeerById, AddToBlacklistRequest, RemoveFromBlacklistRequest, AddMaintainedPeer, AddTrustedPeer, RemoveTrustedPeer, SetMaxPeers). 9 mutable fields + connectedPeers threading = Large. NetworkPeerManagerActor is a separate actor above (needs HERALD-3). |
| HERALD-3 | NET2 | NetworkPeerManagerActor — SNAP code subscriptions, RegisterSnapSyncController typed ref | ✅ CONDITIONAL (Medium) — 1 state (handleMessages only), 8 var fields + 3 mutable collections, 2 sender() paths (GetHandshakedPeers→HandshakedPeers, PeerInfoRequest→PeerInfoResponse). 6 constraints: (1) Classic shell required — SNAPSyncController uses Classic ask; shell captures sender() + injects replyTo Commands into Typed core (same shell+core split as PMA); (2) SNAP ref stays Option[ActorRef] (Classic) — no SNAPSyncController.Command ADT yet, fire-and-forget via classicRef.tell(msg, ActorRef.noSender); (3) 8 var fields go on Impl class (not behavior params) — use Behaviors.setup { ctx => new Impl(ctx,...).handleMessages(Map.empty) } pattern; (4) RegisterSnapSyncController + RegisterChainWeightCalibrationTarget arrive via Classic shell as fire-and-forget Commands, Impl fields updated on Typed core; (5) 3 scheduler calls in constructor body → Behaviors.withTimers or context.system.classicSystem.scheduler with explicit ec; (6) 2 ask-path replies enriched at shell (capture sender(), wrap as replyTo Command), Typed core calls replyTo ! reply directly. |
| HERALD-4 | SNAP1 | SNAPSyncController — 6 named behaviors, 11 sender(), ~58 vars, 13 timers, 2 .orElse partials, 1 aroundReceive | ✅ CONDITIONAL (X-Large) — 4 hard constraints (C1: .orElse→helpers; C2: aroundReceive inline; C3: GetStatus+GetProgress+replyTo; C4: raw scheduler L4058→keyed timer). Estimated 10–14 days. Full report: HERALD-4-SSC-preflight.md |
| HERALD-5 | S3 | AccountRangeCoordinator, TrieNodeHealingCoordinator — SNAP request/response routing in Typed | ✅ CONDITIONAL — complexity: ARC=Large (2 states, 6 sender() paths), BCC=Medium (1 state, 1 sender()), SRC=Large (1 state, 1 sender(), NPM direct), TNHC=X-Large (1 state, 1 sender(), NPM direct, 10 return violations, @volatile). Migration order: ByteCode→Storage→AccountRange→TrieNodeHealing. Pre-migration commits required: (1) fix 1 return in ByteCodeCoordinator, (2) fix 10 returns in TrieNodeHealingCoordinator — both standalone commits before LOOM. 12 constraints: (1) SSC stays Classic ActorRef at S3 time — all snapSyncController ! Msg are Classic tells; (2) NPMA stays Classic ActorRef at S3 time — SRC+TNHC send directly to it, no adapter; (3) SSC spawns migrated coordinators via .toClassic adapter (already proven pattern from W1 workers); (4) 9 replyTo fields to add in Messages.scala (6 for ARC, 1 each for BCC/SRC/TNHC); (5) ARC requires 2 Typed behaviors — receive→initial, finalizing→second (handles GetProgress+file info, drops all else); (6) TNHC @volatile verificationBFSRunning → drop volatile, plain closure var (all Future→actor comms are already via actor messages); (7) fix 10 TNHC return violations in standalone pre-migration commit; (8) fix 1 BCC return violation in standalone pre-migration commit; (9) no coordinator→coordinator messaging; (10) OneForOneStrategy dropped from all 4 — ARC/BCC: workers use their own SupervisorStrategy.stop, coordinator handles Terminated; SRC/TNHC: no children, omit supervision; (11) async Futures capture context.self before launch (already done via val selfRef = self); (12) HealingStagnated is coordinator→SSC tell, not an inbound Command — do not add it to TNHC Command ADT. |

### SNAP1 extra pre-condition
SNAPSyncController requires a **SPECKIT specify session** to define its Typed ADT (sealed Command + sealed Response) BEFORE LOOM starts. ADT must be reviewed and committed first.

### End of sprint validation
```bash
grep -rn "extends Actor\b" src/main/scala --include="*.scala"  # must be EMPTY
sbt pp   # compile-all + scalafmt + testEssential — all green
```

---

## Parallel Housekeeping Track

These tasks run **independently** during `testEssential` (~24 min) and `testStandard` (~30 min)
wait times. No gate dependency on the primary sprint groups above. Pick any file from the list
when the primary thread is blocked on tests. Commit separately from migration commits.

| Task | What | Where | Effort |
|------|------|-------|--------|
| ~~Part 1 warnings~~ ✅ C3 | ~~5 remaining compiler warnings~~ — cleared `bd2d691c3` | DEFERRED-BACKLOG.md §Part 1 | — |
| ~~Part 3d enum~~ ✅ `adf4e69ea` | `SyncPhase` (8-member → single-line enum, `SyncPhase.*` imported at 5 call sites) + `ForkIdValidationResult` (3-member, 4 external callers updated). `BlacklistReason` (7 final case class subtypes) + `BlacklistReasonType` (non-trivial `code`/`name` fields + mixin groups) rejected — not pure discriminants. `testOnly *Sync* *Peer* *Network* *ForkId*` → 406/406. | DEFERRED-BACKLOG.md §3d | — |
| ~~Part 3e console→log~~ ✅ C5 | ~~Replace 24 println/System.out/System.err in main src~~ — 7 sites replaced `6a3e2cd88`; 16 deferred (consensus/vm/crypto) + 2 Tui JLine | DEFERRED-BACKLOG.md §3e | — |
| ~~Part 6a extvm~~ ✅ C4 | ~~Delete extvm/ (10 files)~~ — 18 files deleted `a948fda1d` | DEFERRED-BACKLOG.md §6a | — |
| ~~Part 8e ScalaFix~~ ✅ C2 | ~~Add noReturns + NoAutoTupling to .scalafix.conf~~ — config done; `9eb1f4e06` clears ~52 violations (44 deferred: SSC+consensus) | DEFERRED-BACKLOG.md §8e | — |
| ~~Part 8f dead code~~ ✅ C6 | ~~Grep-sweep beyond extvm~~ — audit done (`dead-code-audit.md` 325 lines); C8a/C8b **DEFERRED to post-CAPSTONE** — dead code audits mid-implementation produce false positives | DEFERRED-BACKLOG.md §8f | — |
| ~~Part 8g braceless~~ ✅ C1 | ~~Add scalafmt braceless-prefer config~~ — `convertToNewSyntax = true` committed (393 files) | DEFERRED-BACKLOG.md §8g | — |
| ~~Part 8j Thread.sleep~~ ✅ C7 | ~~Fix timing-sensitive tests (56 occurrences)~~ — audit done; 7 real calls, all Bucket B (clock injection or test redesign); no commits | DEFERRED-BACKLOG.md §8j | — |
| ~~Part 3f manual sync~~ ✅ `cf33cfa87` | 5 sites audited: `MapCache.scala:19+30` → `TrieMap` (Bucket D, fixed); `CombinedRecoveryScanner:106` + `TrieNodeHealingCoordinator:1617` left as-is (Bucket D — compound transactions / LinkedHashMap FIFO correctness documented in comments); `PoWMining:106` no-touch (Bucket A — FORGE gate; logged in CHASE-QUEUE). No Bucket C violations. | DEFERRED-BACKLOG.md §3f | — |

**Rule**: Never mix housekeeping commits into primary-track migration commits. Separate commits
with clear messages (`chore(cleanup):`, `test(quality):`, `style(scala3):`). This keeps
migration diffs readable for review.
