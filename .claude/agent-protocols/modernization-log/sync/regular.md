# sync/regular — Regular Sync

**Package:** `blockchain/sync/regular/`
**Gate:** None (sync infrastructure); `herald` for peer-message path changes
**Key files:** `RegularSync.scala`, `BlockImporter.scala`

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-S2/S3 commits — BlockImporter + RegularSync Typed
- **What:** `extends Actor` + `def receive` → `Behaviors.receive`; sealed Command ADT; explicit `replyTo`
- **HERALD pre-flight:** HERALD-1 (peer-message path), HERALD-4 (BlockImporter)
- **Cross-refs:** `sync/controller.md` (spawn wiring), `network/peers.md` (peer event flow)

---

## ADT Narrowing: Behavior[Any] → Behavior[Command]

#### `948a25008` — Phase 1: `sealed trait Command` added to RegularSync + BlockImporter
- **Cross-refs:** `sync/fast.md`, `sync/controller.md`, `network/peers.md`

#### `04615ad43` — Phase 2: RegularSync + BlockImporter narrowed
- **Cross-refs:** `network/peers.md` (primary actor for this commit)

---

## LCA Recovery

#### `0d290019e` — LCA fork recovery via FastSyncBranchResolverActor reuse
- **What:** `handleForkRecovery` in `RegularSync` reuses `FastSyncBranchResolverActor` logic
- **Result:** 46/46 targeted tests pass
- **Cross-refs:** `sync/fast.md` (FSBA implementation)

---

## Quality Fixes

#### W3-WormToBrainBar `31c51a7cc` — worm bar appended to RegularSync PrintStatusTick
- **What:** `WormToBrainBar.renderKnown/renderUnknown` wired in after `PrintStatusTick` log line
- **Cross-refs:** `sync/fast.md` (utility origin)

#### `8a65bbdb7` — W17: `case _ => Behaviors.same` → `Behaviors.unhandled` + `log.warning`
- **What:** Catch-all on non-sealed `RegularSyncCommand` replaced; `SyncProtocol.RegularSyncCommand` sealing deferred (E112 across files)
- **Note:** Structural seal (move subtypes to `SyncProtocol.scala`) tracked in CHASE-QUEUE
- **Source:** CODEBASE-AUDIT W17

#### `923b18ba7` — W17: RegularSyncCommand sealed
- **What:** `FetcherStatusTick`, `PrintStatusTick`, `ProgressProtocol` moved from `RegularSync.scala` → `SyncProtocol.scala`; `trait RegularSyncCommand` is now `sealed`; fallthrough `case _ => Behaviors.unhandled` arm deleted; `RegularSync.ProgressProtocol` type alias + val forwarding preserves all call sites in `BlockImporter`, `BlockFetcher`, `SyncController`, test utils without import changes
- **Result:** 31/31 `RegularSyncSpec` tests pass; 0 compile errors; E112 does not appear

---

#### `86c76fd4e` — P9: re-enabled RegularSyncSpec:522 "retry fetching node if validation failed"
- **What:** Removed `DisabledTest` tag. Test uses `WrongNodeDataPeersClientAutoPilot` (no ScalaMock) — passed as-is; tag was the only blocker.

---

## §P9-SAVENODE — RegularSyncSpec:552 "save fetched node" (Part 15)

#### `abe9dccc1` — test: re-enable "save fetched node" — replace ScalaMock stubs with explicit test doubles
- **Root cause:** `stub[BranchResolution]`, `stub[Blockchain]`, `stub[BlockchainReader]`, and `stub[StorageDataSource]` (ScalaMock) never intercept under Scala 3 — ScalaMock uses Scala 2 `ScalaSig` bytecode metadata for runtime proxy creation, which is absent from Scala 3 class files. `stub[BranchResolution].evaluateBranch(...)` always dispatched to the real (null) implementation.
- **Fix:** Replaced all 4 ScalaMock stubs with explicit anonymous-class implementations. Added `evaluateBranch` override handling the `PickedBlocks → importBlocks → tryImportBlocks` path — without it, the path NPE'd on `null.consensus`. Added missing `import io.iohk.ethereum.blockchain.sync.regular.BlockImporter.NotUsed` for `StateStorage`.
- **Result:** 33/34 `RegularSyncSpec` tests pass (1 pre-existing unrelated failure unaffected); `DisabledTest` tag removed.
- **Docs:** `203dc66a3` (CHASE-QUEUE + CODEBASE-AUDIT strikethrough); `dc5296f33` (§P9-SAVENODE section deleted from DEFERRED-BACKLOG)

---

## ETH/69 Inbound Type Fix (Part 14 §ETH-BRU)

#### `931c615dd` — fix: ETH/69 BlockRangeUpdate inbound type — ETH69→ETHPackets in BlockFetcher (Part 14 §ETH-BRU)
- **What:** `BlockFetcher.scala:486` match arm `AdaptedMessageFromEventBus(msg: ETH69.BlockRangeUpdate, _)` matched a type that the decoder never emits at runtime — peer-pushed chain-tip advances via `withPossibleNewTopAt` were silently dropped on ETH/Sepolia; head-following degraded to periodic re-probe only. Fixed by changing to `ETHPackets.BlockRangeUpdate`. Unused `ETH69` import removed.
- **BlockFetcherSpec.scala:298-305** — Rebuilt: test was constructing `ETH69.BlockRangeUpdate` directly (false-positive coverage masking the production gap); changed to `ETHPackets.BlockRangeUpdate` to exercise the real inbound path.
- **Verification:** 11/11 BlockFetcherSpec tests pass
- **Cross-refs:** `network/peers.md` (PeerActor:551 sibling fix), F8 BEACON ETH-bias sweep (source finding)

---

## §8a-retro batch 5 — RegularSyncSpec deferred

`RegularSyncSpec` uses a `Resource[IO, ActorSystem]` lifecycle that is load-bearing (Cats Effect error handling, cleanup ordering). Migration to `ScalaTestWithActorTestKit` would require restructuring the resource lifecycle. **Deferred to Wave 3** when `RegularSync` itself becomes a Typed actor and the test can be rewritten from scratch.

---

## Open / Deferred

- INFO-13/14: Classic `LoggingAdapter` via `Logging(ctx.system.classicSystem, ...)` bridge — Network/P2P sprint
- `RegularSync.scala:228`: `log.warning(...)` Classic spelling → `log.warn(...)` — Network/P2P sprint
- RegularSyncSpec divergence path EXCEPT (LCA-less blind rewind) — HERALD audit done; fix spec deferred
- ~~§P9-SAVENODE: RegularSyncSpec:552~~ — ✅ DONE 2026-06-23 (`abe9dccc1`)
- RegularSyncSpec (entire file) — Wave 3 migration gate (Resource[IO, ActorSystem] lifecycle)
