# Phase 1 Data Model: Skip the Redundant Second Verification Walk

No new persisted state, no schema change, no migration. The only "data" change is an enriched in-process actor message and a read-only completion condition. The persisted completeness marker is reused unchanged.

## Entities

### Rebuild walk outcome (enriched message) — CHANGED

- **What**: the signal that the full rebuild traversal finished, carrying enough of its result to decide completion soundly.
- **Today**: `FrontierRebuildComplete` is a **payload-free case object**; the handler cannot see what the walk found.
- **Change (D3/FR-004)**: make it a **case class** `FrontierRebuildComplete(missingEmitted: Long, walkRoot: ByteString)`:
  - `missingEmitted` = the rebuild's `frontierCount` (`:1563`) — the number of missing nodes the walk emitted. `0` ⇒ the walk found everything present.
  - `walkRoot` = the root the finished walk traversed (captured at the walk launch, `:614`).
- **Invariant**: the values reflect the walk that just finished, not the node's current state — which is exactly why `walkRoot` must be compared against the live `stateRoot` (the walk could be stale, F-E).

### Clean-rebuild completion condition — NEW (read-only)

- **What**: the conjunction under which early completion is sound (D2):
  `missingEmitted == 0 && totalNodesHealed == 0 && isComplete && !flushing && walkRoot == stateRoot`.
- **Operands** (all cheap local reads):
  - `missingEmitted == 0` — the deciding walk found nothing missing (from the enriched message).
  - `totalNodesHealed == 0` — nothing was healed (lifetime cumulative counter `:157/:1308`; conservative, D5).
  - `isComplete` — `pendingTasks.isEmpty && activeRequests.isEmpty` (`:1495`): no outstanding frontier or in-flight request.
  - `!flushing` — no async node-write/flush in flight (mirrors the `:890` gate; FR edge case).
  - `walkRoot == stateRoot` — the finished walk's root is still the node's current root (D4/FR-005; excludes the stale-root hazard F-E).
- **Rule (FR-001/FR-003)**: declare early iff ALL hold; otherwise behavior is exactly as today (the watchdog reaches completion via walk #2).

### Completeness marker (reused, unchanged)

- The basis for `StateHealingComplete` remains the existing CF-`'g'` completeness marker, written via the same `markComplete()` and routed through the single `HealingCheckCompletion` → `markComplete` (`:908`) → `StateHealingComplete` (`:912`) chokepoint (D1). This feature reaches that chokepoint one walk sooner; it does not change what the marker asserts or its bytes (`Array[Byte](1)`).

## State flow

```
restart, persisted frontier, no marker
  └─► rebuildFrontierBFS (WALK #1, onComplete → FrontierRebuildComplete(missingEmitted, walkRoot))
        │
        ├─ markComplete()                       (unchanged — already fires here today, :643)
        │
        ├─ CLEAN guard holds (D2)?
        │     YES ─► verificationPassComplete = true ; self ! HealingCheckCompletion
        │             └─► markComplete (:908) ; StateHealingComplete (:912) ; regular sync   ◄── ONE walk
        │     NO  ─► (do nothing new) ; node idle, verificationPassComplete=false
        │             └─► dead-pulse watchdog (:1012-1027) ─► startVerificationBFS (WALK #2)
        │                   └─► VerificationBFSComplete ─► verificationPassComplete=true
        │                         └─► HealingCheckCompletion ─► StateHealingComplete           ◄── TWO walks (today)
```

## Validation rules (from requirements)

| Rule | Source | Enforcement |
| --- | --- | --- |
| Declare early iff genuinely clean | FR-001 | D2 guard in the `FrontierRebuildComplete` handler |
| Byte-for-byte parity with the two-walk outcome | FR-002 | route via `self ! HealingCheckCompletion` (single chokepoint, no state writes) |
| Never declare early when not clean | FR-003 | any guard conjunct false ⇒ no early completion |
| Outcome observable at the decision point | FR-004 | `FrontierRebuildComplete` carries `missingEmitted` + `walkRoot` |
| Explicit current-root guard | FR-005 | `walkRoot == stateRoot` (not the incidental pivot-reseed property) |
| Watchdog fallback intact | FR-006 | watchdog `:1012-1027` unchanged; self-suppresses on `verificationPassComplete` |
| Verification still runs on non-rebuild paths | FR-007 | early path reachable only from the clean rebuild handler |
| No missing node treated as present | FR-008 | detection `:1616` independent of the visited set (F-C) |
| No migration; safe with/without frontier | FR-009 | no persisted-state change |
