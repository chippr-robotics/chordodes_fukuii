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
- **Deferred:** `RegularSyncSpec:552` "save fetched node" — ScalaMock `stub[BranchResolution]` never intercepts under Scala 3; replace with anonymous class double → §P9-SAVENODE (DEFERRED-BACKLOG Part 15)

---

## Open / Deferred

- INFO-13/14: Classic `LoggingAdapter` via `Logging(ctx.system.classicSystem, ...)` bridge — Network/P2P sprint
- `RegularSync.scala:228`: `log.warning(...)` Classic spelling → `log.warn(...)` — Network/P2P sprint
- RegularSyncSpec divergence path EXCEPT (LCA-less blind rewind) — HERALD audit done; fix spec deferred
- §P9-SAVENODE: RegularSyncSpec:552 ScalaMock stub → anonymous class double (DEFERRED-BACKLOG Part 15)
