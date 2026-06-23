# network/peers — Peer Management

**Package:** `network/`
**Gate:** `herald` on all wire-protocol and peer-management changes
**Key files:** `NetworkPeerManagerActor.scala`, `PeerActor.scala`, `PeerEventBus.scala`, `ServerActor.scala`

Note: `ServerActor` and `RLPxConnectionHandler` intentionally remain Classic TCP bridges.

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-NET commits — NPMA Typed (Phase 1)
- **What:** `NetworkPeerManagerActor` core migrated from Classic `receive` → Typed `Behaviors.receive`
- **HERALD pre-flight:** HERALD-1 (NPMA peer-message routing)

#### W3-NET2 commits — NPMA Phase 2 + PeerActor Typed
- **What:** PeerActor fully Typed; NPMA Command ADT consolidated
- **Cross-refs:** `sync/fast.md` (PivotBlockSelector spawn wiring), `sync/controller.md`

---

## ADT Narrowing: Behavior[Any] → Behavior[Command]

#### `948a25008` — Phase 1: `sealed trait Command` across S1/S4/S5/S6 actors
- **Actors narrowed in this commit (NPMA-related):** `NetworkPeerManagerActor`, `PeerActor`
- **Cross-refs:** `sync/fast.md`, `sync/regular.md`, `sync/controller.md`

#### `04615ad43` — Phase 2: NPMA Command ADT consolidated
- **What:** Non-sealed commands consolidated; `PeerEventCmd` wraps external peer events
- **Note:** `trait Command` was initially unsealed (incorrect rationale); sealed in quality pass below

#### Phase 3 NPMA narrowing passes — `Behavior[Any]` → `Behavior[Command]`

---

## EventStream → Topic[T] (DEFERRED-BACKLOG 7b)

#### `849c0dcf0` — EventStream→Topic Phase 1
- **What:** `PeerEventBus` refactored from Classic `EventStream` to Pekko `Topic[T]`; `EventTopicsBuilder` trait introduced

#### `b35b35cf6` — EventStream→Topic Phase 2
- **What:** All subscribers migrated to typed `Topic[T]` subscriptions

---

## Command ADT Consolidation (DEFERRED-BACKLOG 7a)

#### `948a25008` — sealed trait baseline (Phase 1, same commit as ADT narrowing)

#### `04615ad43` — Part 7a Phase 2: Command ADT consolidated
- **What:** Per-actor `Command` hierarchies cleaned up; phantom `case object` commands removed

#### `4e8b42263` — Part 7a Phase 3: final ADT consolidation
- **What:** Remaining non-sealed Command traits sealed; `PeerEventCmd` wrapper verified correct

---

## Quality Fixes (CODEBASE-AUDIT / PRISM)

#### W12 + W1 + W16 + INFO-1 + INFO-7 commit — NPMA batch fix
- **W12:** `trait Command` sealed (`sealed trait Command`); exhaustiveness checking enabled
- **W1:** Dead branch `if Capability.usesRequestId(...)` — both arms identical; deleted
- **W16:** `catch { case _: Throwable => None }` in `handleGetStorageRanges` → `log.warn(...)` before `None`
- **INFO-1:** Unreachable `case _ => Behaviors.same` removed after sealing
- **INFO-7:** 60-second summary log → SLF4J `{}` placeholders

#### `13aa7585e` — W11: dead ETH69.BlockRangeUpdate inbound arms deleted
- **What:** Both `ETH69.BlockRangeUpdate` inbound arms (`:525` and `:874`) deleted; HERALD confirmed outbound-only
- **Cross-refs:** `sync/fast.md` (W5 comment), `network/peers.md` NPMA

#### W6 commit (`5e33435c8`) — Classic scheduler → Typed timers
- **What:** `scheduler.scheduleOnce { peerManagerActor ! ... }` (2 sites) → `timers.startSingleTimer(...)`; `DeferredBlacklistCmd` mailbox re-entry pattern

#### W2 — `val _ = peerWithInfo` silent None discard (4 SNAP handlers)
- **What:** By-design after HERALD confirm (None path reachable pre-handshake); `@annotation.unused` added
- **Source:** CODEBASE-AUDIT W2

---

## ETH/69 Inbound Type Fix (Part 14 §ETH-BRU)

#### `931c615dd` — fix: ETH/69 BlockRangeUpdate inbound type — ETH69→ETHPackets in PeerActor (Part 14 §ETH-BRU)
- **What:** `PeerActor.scala:551` match arm for inbound BlockRangeUpdate was matching `ETH69.BlockRangeUpdate` (an outbound-only type); the decoder actually emits `ETHPackets.BlockRangeUpdate`. The malformed-update validation arm and `BreachOfProtocol` disconnect guard were dead code — abusive ETH/Sepolia peers were not being disconnected. Fixed by changing the matched type to `ETHPackets.BlockRangeUpdate`; all validation logic and disconnect behavior preserved unchanged.
- **Root cause:** Sprint commit `13aa7585e` (W5/W11, NPMA migration) swept NPMA to `ETHPackets.BlockRangeUpdate` but did not sweep the two sibling inbound handlers in `PeerActor` and `BlockFetcher`. See `sync/regular.md` for BlockFetcher fix.
- **Verification:** 15/15 PeerActorSpec tests pass
- **Cross-refs:** `sync/regular.md` (BlockFetcher:486 sibling fix), F8 BEACON ETH-bias sweep (source finding)

---

## Open / Deferred

- W7: 900-line NPMA `Impl` with 9 responsibilities — Wave 3 LOOM gate (Network/P2P sprint)
- INFO-9: `GetHandshakedPeersCmd.replyTo: ActorRef` untyped — Network/P2P sprint
- `PeerRequestHandler` `ClassTag` unsound → `TypeTest[A,B]` — deferred
- 35 remaining Classic actors in devp2p/rlpx (Wave 3 network migration plan complete, implementation not started)
- §8a-retro batch 5: `PeerActorSpec` deferred — `TestActorRef` is Classic-only; migrate when PeerActor is Typed (Wave 3 network sprint)
