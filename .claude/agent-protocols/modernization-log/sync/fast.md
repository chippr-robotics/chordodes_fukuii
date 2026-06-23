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
