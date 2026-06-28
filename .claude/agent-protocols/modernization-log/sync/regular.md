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

## §8a-retro batch 5 → §9c — RegularSyncSpec migration ✅ DONE 2026-06-24

#### `57d638d49` — test(9c): RegularSyncSpec — migrate Resource[IO, ActorSystem] lifecycle to per-test ActorTestKit; drop unused _system param from fixtures
- **What:** `ResourceFixtures` / `AsyncWordSpec` lifecycle replaced with per-fixture `ActorTestKit`. `ScalaTestWithActorTestKit` base avoided (conflicts with `AsyncWordSpecLike` — registers 0 tests). `_system: ActorSystem` param dropped from all three fixture classes; 28 call sites updated. Spawn sites → `testKit.spawn`; stop sites → `testKit.stop` (not `system.stop` — guardian `ClassCastException` pitfall per §8a-retro batch 4).
- **Key pitfall resolved:** `system.stop(child)` on a testKit-guardian child crashes the system via `StopChild` dispatch → `ClassCastException`. Use `testKit.stop(child)`.
- **Step 13 clean:** 0 unexpected `ActorRef` hits (all 17 are load-bearing AutoPilot `sender: ActorRef` signatures or typed refs).
- **Result:** 34/34 `RegularSyncSpec` tests pass. `sbt compile-all` clean.
- **Cross-refs:** `§9b` (divergence-path test, same spec), `§9d` (getSyncStatus ClassCastException fix)

---

## Classic Interop — §8k-F (COMPLETE)

#### `b24515637` — refactor(8k-F): RegularSync Classic→Typed migration — remove ctx.toClassic.actorOf + parent bridge (Clusters C/D/N); docs `806202cb9`
- **What:** `RegularSync.scala` fully migrated from Classic `extends Actor` / `def receive` to `Behaviors.receive`; sealed Command ADT; explicit `replyTo`. `ctx.toClassic.actorOf(RegularSync.props)` in `SyncController` + `NodeBuilder` (Cluster D) → Typed `ctx.spawn(RegularSync.behavior)`. Parent-bridge sites in `BlockImporter` (Cluster N) eliminated. `ctx.toClassic.sender()` OQ-5 reply sites in SyncController (Cluster C subset) cleaned.
- **Clusters resolved:** C (partial — SyncController OQ-5 actorOf call), D (`ctx.toClassic.actorOf` x2), N (`ctx.self.toClassic` + `fetcherReplyTo.toClassic` in BlockImporter x4)
- **INFO-13/14 resolved:** Classic `Logging(ctx.system.classicSystem, ...)` bridge removed; Typed `ctx.log` (SLF4J) used throughout.
- **`log.warning` resolved:** All Classic `log.warning(...)` spelling → `log.warn(...)` (SLF4J).
- **Verification:** 33/34 RegularSyncSpec pass (1 pre-existing divergence-path EXCEPT — unrelated, tracked in DEFERRED-BACKLOG §9b); `sbt compile-all` clean

---

## Open / Deferred

- ~~INFO-13/14: Classic `LoggingAdapter` via `Logging(ctx.system.classicSystem, ...)` bridge~~ — ✅ resolved in §8k-F (`b24515637`)
- ~~`RegularSync.scala:228`: `log.warning(...)` Classic spelling → `log.warn(...)`~~ — ✅ resolved in §8k-F (`b24515637`)
- ~~RegularSyncSpec divergence path EXCEPT (LCA-less blind rewind)~~ — ✅ DONE 2026-06-24 (§9b): divergence-path test written; `resolvingFork` / FSBA wiring confirmed correct
- ~~RegularSyncFixtures `getSyncStatus` broken — 4 status tests ClassCastException~~ — ✅ DONE 2026-06-24 (`69146a244`): Classic `?` ask replaced with `TestProbe` send; removed ask/Timeout imports; 34/34 pass (§9d)
- ~~§P9-SAVENODE: RegularSyncSpec:552~~ — ✅ DONE 2026-06-23 (`abe9dccc1`)
- ~~RegularSyncSpec (entire file) — Wave 3 migration gate now OPEN~~ — ✅ DONE 2026-06-24 (`57d638d49`): per-test ActorTestKit; 34/34 pass (§9c)
