# sync/controller — Sync Orchestrator

**Package:** `SyncController.scala`, `SyncProtocol.scala`
**Gate:** None (orchestration); `herald` for peer-message routing; `forge`/`beacon` for chain-selection logic
**Key files:** `SyncController.scala`, `SyncProtocol.scala`

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-ROOT/CAPSTONE commits — SyncController + NodeBuilder root flip
- **What:** CAPSTONE: `ActorSystem[Nothing]` root; SyncController fully Typed; last `extends Actor` removed from main path
- **HERALD pre-flight:** HERALD-5
- **Cross-refs:** `node/bootstrap.md` (NodeBuilder ActorSystem flip), `sync/fast.md`, `network/peers.md`

---

## ADT Narrowing (Wave 3, Phase 1/2/3)

#### `948a25008` — Phase 1: SyncController sealed Command trait
- **Cross-refs:** `sync/fast.md`, `sync/regular.md`, `network/peers.md`

#### `2a2d77166` + `00cf1bed1` + `0d8adfd5c` — Phase 3: SyncController narrowing passes
- **What:** `Behavior[Any]` → `Behavior[Command]`; `WrappedExternal` adapters retyped

---

## Quality Fixes (CODEBASE-AUDIT)

#### `a5132aa80` — C2: EC.global removed from SyncController
- **What:** `import scala.concurrent.ExecutionContext.Implicits.global` deleted; `given ec = ctx.system.executionContext` added in `startSnapSync` only

#### `8a65bbdb7` — W3/W10/INFO-2/INFO-4/INFO-5/INFO-6/INFO-11 batch
- **What:** HandshakedPeers fallthrough guard; `withPostStop` → `receiveSignal`; system-property path normalization + BigInt error handling; stale `Behavior[Any]` docs fixed
- **Source:** CODEBASE-AUDIT W3/W10

#### `a73ce7922` — docs: stale Behavior[Any] refs + WrappedExternal comment
- **Source:** CODEBASE-AUDIT INFO-2/INFO-4/INFO-11

#### `8bd4ed3f1` — W9: versioned child names at `startRegularSyncForBootstrap`
- **What:** Fixed child names → `s"...-$bootstrapGeneration"` to prevent `InvalidActorNameException` on restart
- **Source:** CODEBASE-AUDIT W9

#### `fc1030410` — SyncControllerSpec: SyncStateAutoPilot GetHandshakedPeersCmd handler
- **What:** 7 pre-existing MatchError failures fixed; autopilot now handles `GetHandshakedPeersCmd(replyTo)` → `HandshakedPeers` stub. Test-only change — no production source edits.

---

## Open / Deferred

- W4: `ctx.self ! cmd` re-delivers wrapped Command (document invariant) — deferred
- W15: `unwrap returns Any` — Wave 3 LOOM gate (`WrappedExternal` elimination)
- INFO-9: `GetHandshakedPeersCmd.replyTo: ActorRef` untyped — Network/P2P sprint
