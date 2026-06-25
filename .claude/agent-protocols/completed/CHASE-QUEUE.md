# Chase Queue — Completed Clearout Prompts

Archived prompt blocks from `working-docs/CHASE-QUEUE.md` once executed and committed.
Completion record is in the **Cleared entries log** in `working-docs/CHASE-QUEUE.md`
and in `completed/SPRINT-QUEUE.md`.

---

### P1 — MITHRIL: Seal RegularSyncCommand ✅ DONE `923b18ba7` (2026-06-22)

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

**Result:** 31/31 `RegularSyncSpec` pass; 0 compile errors; E112 absent; fallthrough arm deleted.
`RegularSync.ProgressProtocol` type alias + val forwarding preserves all call sites without import changes.

---

### §8k-CQ1 — MITHRIL: Remove `GetKnownNodes` dead shim ✅ DONE `d4cc7a7fa` (2026-06-24)

**Agent:** MITHRIL
**Files:** `src/main/scala/com/chipprbots/ethereum/network/KnownNodesManager.scala` (lines 113–117)
           `src/it/scala/com/chipprbots/ethereum/sync/util/CommonFakePeer.scala` (lines 150–167)
**Prerequisite:** None. Pure dead-code removal; no consensus touch.

**Prompt:**
> On branch `scala3-cleanup-june`, remove the dead `GetKnownNodes` Classic compat shim from
> `KnownNodesManager`. The live Typed replacement (`GetKnownNodesReq(replyTo: ActorRef[KnownNodes])`)
> is already in place at line 105.
>
> **Root cause:** `case object GetKnownNodes` was a Classic-only bridge for `PeerManagerActor` to
> request the known-node set. `PeerManagerActor` migrated to Typed in `05e0c003b` — its new code
> uses `GetKnownNodesReq` directly. The `GetKnownNodes` case object has had zero callers since that
> commit.
>
> **Fix:**
> 1. In `KnownNodesManager.scala` delete the Scaladoc block (lines 113–116) and
>    `case object GetKnownNodes` (line 117).
> 2. In `CommonFakePeer.scala` delete: the stale `// Classic compat` comment (line 150),
>    `implicit private val scheduler` (lines 154–155), `implicit private val bridgeTimeout`
>    (lines 156–157), the stale comment at line 159, and the entire
>    `case GetKnownNodes => sender(); import AskPattern.*; .ask(...).foreach(...)` block
>    (lines 162–167). The `lazy val knownNodesManager` declaration and its single remaining
>    case (`case cmd: KnownNodesManager.Command =>`) are still needed and must be preserved.
> 3. `sbt compile-all` — 0 errors.
> 4. `sbt "testOnly *KnownNodesManager*"` — 52/52 pass.
> 5. `sbt scalafmtAll`.

**Result:** 2 files changed, 22 lines deleted. `compile-all` 0 errors. 52/52 `KnownNodesManagerSpec`
tests pass. `@annotation.nowarn("msg=Matchable")` retained (still needed for the remaining
`case cmd: KnownNodesManager.Command =>` match on `Any` in the Classic `Receive` bridge).

---

### §8k-CQ2 — MITHRIL: `PeerActorSpec:429` AlreadyConnected regression ✅ FIXED 2026-06-24

**Commit:** `359692a3b` — `fix(test): update PeerActorSpec AlreadyConnected assertion for 8k-H toClassic.parent removal`
**File:** `src/test/scala/com/chipprbots/ethereum/network/p2p/PeerActorSpec.scala`

**Root cause:** 8k-H commit `222623960` removed all `context.toClassic.parent` sends from `PeerActor`.
`PeerClosedConnection` is defined in the companion object but was never re-wired to any new path —
PeerManager detects peer death via `watchWith(ref, PeerTerminated(ref))` (death-watch), not a message
from the peer itself. The test expected the old Classic parent-send path which no longer exists.

**Fix:** Test renamed to "should stop when Disconnect(AlreadyConnected) is received during handshake".
`parentProbe.ref` removed as the explicit parent. New `watcherProbe: TestProbe` added; calls
`watcherProbe.watch(peerUnderTest)`. Assertion changed from
`parentProbe.expectMsg(3.seconds, PeerClosedConnection(...))` → `watcherProbe.expectTerminated(peerUnderTest, 3.seconds)`.
Behaviour under test (PeerActor terminates on AlreadyConnected) is preserved — only the observation
mechanism changed to match the Typed architecture.

**Verification:** `compile-all` 0 errors, 15/15 `PeerActorSpec` tests pass, `scalafmtAll` clean.
**testEssential impact:** Sole remaining failure from P12 triage — baseline now 0 failures.
