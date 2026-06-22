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
