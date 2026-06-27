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

## Classic Interop — §8k-E (COMPLETE)

#### `c42316b39` — refactor(8k-E): typed GetHandshakedPeersCmd replyTo in NPMA; docs `7bd607a87`
- **What:** `NPMA.GetHandshakedPeersCmd(replyTo: ActorRef)` → `ActorRef[HandshakedPeers]`. 15 `.toClassic` bridge sites removed across 10 files: `FastSync`, `SyncStateSchedulerActor`, `PeersClient`, `BlockBroadcaster`, `ChainDownloader`, `SNAPSyncController`, `PivotBlockSelector`, `FastSyncBranchResolverActor`, `SyncController`, `NetworkPeerManagerActor`.
- **Sites eliminated:** ~15 `handshakedPeersAdapter.toClassic` → pass `ActorRef[HandshakedPeers]` directly (Cluster B)

---

## Classic Interop — §8k-D (COMPLETE)

#### `93bcedb12` — refactor(8k-D): typed PeerEventBus subscriber protocol — ~27 bridge sites eliminated; docs `8748d6e35`
- **What:** `PeerEventBusActor.SubscribeCmd(subscriber: ActorRef)` → `ActorRef[PeerEvent]`. 21 files changed (HERALD initial count was 11; cascade subscribers in SNAP/sync actors added 10 more). Clusters A+M bridge sites removed.
- **PeerEventBus:** `extends ActorEventBus` binding stripped (routing logic untouched). Internal Classic registry preserved — only subscriber param type lifted.
- **BlockFetcher:** `subscribeAdapter` child actor removed; replaced with direct `messageAdapter[PeerEvent]`.
- **Left in place:** `PeerEventBusActor.scala:44` `.watch(peerEventBus.toClassic)` — specified as intentional.
- **Verification:** 10/10 `PeerEventBusActorSpec`; `RegularSyncSpec` 33/34 (1 pre-existing DisabledTest timeout, unrelated, tagged since `86c76fd4e`)

---

## Open / Deferred

- W7: 900-line NPMA `Impl` with 9 responsibilities — Wave 3 LOOM gate (Network/P2P sprint)
- ~~INFO-9: `GetHandshakedPeersCmd.replyTo: ActorRef` untyped~~ — closing in §8k-E (running)
- `PeerRequestHandler` `ClassTag` unsound → `TypeTest[A,B]` — deferred
- 35 remaining Classic actors in devp2p/rlpx (Wave 3 network migration plan complete, implementation not started)
- §8a-retro batch 5: `PeerActorSpec` deferred — `TestActorRef` is Classic-only; migrate when PeerActor is Typed (Wave 3 network sprint)

#### `8c23a294e` — §8k-G4b: RegisterChainWeightCalibrationTarget narrowed to TypedActorRef
- **What:** `RegisterChainWeightCalibrationTarget.target` + `RegisterChainWeightCalibrationTargetCmd.target` + `chainWeightCalibrationTarget` var changed from `ActorRef` → `TypedActorRef[SyncProtocol.CalibrateChainWeightFromPeer]`. Classic-shell forwarding path preserved the typed ref through translation.
- **Files:** `NetworkPeerManagerActor.scala`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8k-G4`, `modernization-log/sync/controller.md`

#### `8227b84dd` — §8k-G4d: GetHandshakedPeersCmd.replyTo narrowed to TypedActorRef[HandshakedPeers]
- **What:** `GetHandshakedPeersCmd.replyTo` field changed from `TypedActorRef[Any]` → `TypedActorRef[NetworkPeerManagerActor.HandshakedPeers]`. Classic-shell variant updated to match.
- **Files:** `NetworkPeerManagerActor.scala`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8k-G4`, `modernization-log/sync/controller.md`

---

## ETH69 Tier3 Accuracy — §ETH69-C + §ETH69-D (MITHRIL)

#### `2af49dcb1` — fix(sync): ETH69 Tier3 POW_SCALING rolling-median difficulty (G2)
- **What:** `BlockchainReader.resolveETH69ChainWeight` Tier3 rate changed from a 10K-block DB-lookup rolling average to a 1,000-entry in-memory ring buffer + rolling-median. Added `difficultyRingBuffer: ArrayDeque[BigInt]` (capacity 1,000), `recordBlockDifficulty(difficulty): Unit` (synchronized ring-buffer writer), `rollingMedianDifficulty: Option[BigInt]` (synchronized; None until full; averages two midpoints for even-length arrays). Dead code removed: `Tier3RollingWindow` constant + `rollingWindowDiff` method. Hooks added in `BlockExecution.scala` (live import) and `ChainImporter.scala` (offline/hive import). Cold-start window (< 1,000 entries) falls back to `head.difficulty`.
- **Effect:** Tier3 estimate variance under ETC flex-load oscillation (symmetric ±50% swing) collapses from ±50% to near-zero.
- **Tests:** 2 new tests in `ETH69OscillationChainWeightSpec`: variance < ±20% under oscillation; median of {500×2000 TH, 500×4000 TH} = 3000 TH exactly. 15/15 pass.
- **Files:** `BlockchainReader.scala`, `BlockExecution.scala`, `ChainImporter.scala`, `ETH69OscillationChainWeightSpec.scala`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH69-C`

#### (feature commit) — feat(telemetry): ETH69 Tier3 estimate-vs-actual TD logging on NewBlock (G2 instrumentation)
- **What:** Added `ETH69_TIER3_ACCURACY` debug log in `NetworkPeerManagerActor.updateChainWeight` — fires on every `ETHPackets.NewBlock` from an ETH69 peer, logging `prevTD`, `actualTD`, `delta`, `deltaPercent` for post-hoc audit of Tier3 POW_SCALING accuracy.
- **Files:** `NetworkPeerManagerActor.scala` (3 lines in `updateChainWeight` case branch).
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH69-D`

---

## ETH69 Archive-Node Static Detection (MITHRIL §ETH69-E)

#### `60c9fd4e5` — fix(sync): ETH69 archive-node monotonic-guard exemption (G3/G4)
- **What:** Two `mutable.Map` tracking fields (`consecutiveUnchangedProbes`, `lastProbeMaxBlock`)
  added to NPMA. `RefreshPeerBestBlocksTick` increments the counter per ETH69 peer when
  `maxBlockNumber` is unchanged. `updateMaxBlock` exempts static peers (counter ≥ 3) from the
  monotonic TD guard, allowing Tier3 overestimates to correct downward via DB_LOOKUP.
- **Key subtlety:** Mining peers never accumulate the counter — active block signals
  (`BlockRangeUpdate`, `NewBlock`) refresh `lastBlockSignalMs`, causing subsequent ticks to see
  `recentlySignaled = true` and skip the probe/counter entirely.
- **Tests:** 2 new tests in `NetworkPeerManagerSpec` (`TestSetupWithReader` trait pattern with
  lazy `newReaderHolder()` factory to avoid double-actor subscription interleaving).
- **Files:** `NetworkPeerManagerActor.scala`, `NetworkPeerManagerSpec.scala`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH69-E`, `sync/snap.md` (ETH69 chain-weight chain)


---

## Dead Code Removal — §8k-CQ1

#### `d4cc7a7fa` — refactor(8k-CQ1): remove `GetKnownNodes` Classic compat shim (2026-06-24)
- **What:** `KnownNodesManager.GetKnownNodes` (case object) + Scaladoc block deleted. This was a Classic-only bridge message used by the old `PeerManagerActor` to request the known-node set before NPMA migrated to Typed. The Typed replacement `GetKnownNodesReq(replyTo: ActorRef[KnownNodes])` has been in place since `05e0c003b` and is the live path.
- **CommonFakePeer.scala (src/it):** Bridge handler (`case GetKnownNodes => AskPattern.ask(...)`) removed along with its orphaned `implicit private val scheduler` and `implicit private val bridgeTimeout` vals (were only needed for the ask). The wrapping `lazy val knownNodesManager: ActorRef` and its remaining case (`case cmd: KnownNodesManager.Command =>`) are preserved — still passed to `PeerManagerActor.behavior` at lines 251 and 256.
- **Files:** `network/KnownNodesManager.scala` (5 lines deleted), `src/it/.../CommonFakePeer.scala` (17 lines deleted)
- **Verification:** `compile-all` 0 errors, 52/52 `KnownNodesManagerSpec` pass, `scalafmtAll` clean

---

## PeerManagerActor TCP PoisonPill — Floor Assessment (§8k-L)

#### Assessment only — no commit (2026-06-24)
- **What:** HERALD assessed the 3 `connection ! PoisonPill` sites in `PeerManagerActor.handleConnectionErrors`
  (lines 982, 986, 990) to determine if they are permanent TCP floor or migratable.
- **Verdict:** All 3 are **PERMANENT TCP FLOOR**. `connection: ActorRef` originates from the Pekko
  TCP extension (spawned by `akka.io.TcpManager`), received via `ServerActor.TcpEventBridge`
  as `sender()` on a Classic `Tcp.Connected` event. PeerManagerActor does not own this actor and
  has no Typed ref — `PoisonPill` is the correct stop mechanism.
- **TCP floor census:** Updated §8k-J expected count from 4 → **7** (+3 PoisonPill sites).
- **Files assessed:** `PeerManagerActor.scala:982,986,990`, `ServerActor.scala:186,198`
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8k-L`

---

## Test Fix — §8k-CQ2: PeerActorSpec:429 AlreadyConnected regression

#### `359692a3b` — fix(test): update PeerActorSpec AlreadyConnected assertion for 8k-H toClassic.parent removal (2026-06-24)
- **What:** `PeerActorSpec.scala:429` "should forward PeerClosedConnection with AlreadyConnected to parent" timed out after 8k-H removed all `context.toClassic.parent` sends from `PeerActor`. Test updated to observe the correct post-Typed behaviour: `PeerActor` stops on `AlreadyConnected`; `PeerManagerActor` detects this via death-watch (`watchWith(PeerTerminated)`), not a peer-sent message. Test now uses `watcherProbe.expectTerminated(peerUnderTest, 3.seconds)` instead of `parentProbe.expectMsg(PeerClosedConnection(...))`.
- **Finding:** `PeerClosedConnection` remains defined in the companion object but is sent nowhere in the current codebase. No new notification mechanism exists for pre-handshake disconnects — termination is the signal.
- **Verification:** 15/15 `PeerActorSpec` pass; `testEssential` baseline restored to 0 failures.
- **Cross-refs:** `completed/CHASE-QUEUE.md §8k-CQ2`, `completed/DEFERRED-BACKLOG.md §8k-CQ2`

---

## Test Fix — §8a-E6-PeerActor: PeerActorSpec 15/15 — Classic ActorSystem removed, stop() crash fixed

#### `189e413c9` — fix(§8a-E6): PeerActorSpec 15/15 — remove Classic ActorSystem, fix stop() crash (2026-06-27)
- **What:** Two independent bugs both caused `cannot create children while terminating or terminated` on `testKit.createTestProbe()`.
  - **Bug 1 — cake chain leak:** `NodeStatusSetup extends EphemBlockchainTestSetup → ScenarioSetup → StdTestMiningBuilder → ActorSystemBuilder` materialised a Classic `ActorSystem` as a side effect. Its teardown raced with the `ScalaTestWithActorTestKit`-managed system and killed it after the first test's setup, leaving 13/15 tests unable to create probes.
  - **Bug 2 — stop() routing:** Test 3 called `testKit.stop(conn1Probe.ref)` on a `TestProbe` living under `/system/testProbe-N`. `ActorTestKit.stop()` routes through the `/user` guardian which can only stop its direct children; stopping a `/system` actor threw `IllegalArgumentException`, crashed the guardian, and terminated the `ActorTestKit` for all subsequent tests.
- **Fixes:** `NodeStatusSetup` rewritten — `EphemDataSourceComponent` + `Storages.DefaultStorages` inlined directly, no `EphemBlockchainTestSetup` in the chain. `implicit override lazy val classicSystem` deleted from `TestSetup`. Test 3 rewrote `conn1`/`conn2` from raw `TestProbe` to user-actor proxies (`testKit.spawn(Behaviors.receiveMessage{...})`) with spy probes capturing forwarded messages.
- **Verification:** PeerActorSpec 15/15 ✅ · `scalafmtAll` clean · zero Classic imports
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8a-E6-PeerActor`, `working-docs/DEFERRED-BACKLOG.md §8a-E6 PeerActorSpec ✅`
