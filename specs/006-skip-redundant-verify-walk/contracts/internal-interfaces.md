# Phase 1 Contracts: Skip the Redundant Second Verification Walk (internal interfaces)

Internal sync-orchestration change — no external/wire API, no config, no storage schema. The contracts are the enriched actor message, the guarded early-completion route, the (unchanged) watchdog fallback, the preserved chokepoint, and the tests. Citations are `staging` HEAD; line numbers are anchors, locate by symbol.

## C1 — `FrontierRebuildComplete` message shape — CHANGED (D3/FR-004)

```
// TrieNodeHealingCoordinator (Messages/companion) — case object → case class
case class FrontierRebuildComplete(missingEmitted: Long, walkRoot: ByteString)
```
Contract: the rebuild walk's `onComplete` callback (`:614`) now passes `frontierCount` (`:1563`) as `missingEmitted` and the captured walk root as `walkRoot`. Every existing sender of `FrontierRebuildComplete` (grep — there is the single `:614` launch site) MUST be updated to the new shape. No other behavioral change to the walk kernel.

## C2 — Guarded early completion in the `FrontierRebuildComplete` handler (FR-001/FR-002/FR-003)

```
// TrieNodeHealingCoordinator, handler (~:638-645) — ADD after the existing markComplete():
case FrontierRebuildComplete(missingEmitted, walkRoot) =>
   verificationBFSRunning = false
   healingFrontierStorage.foreach(_.markComplete())          // UNCHANGED (already here today)
   if (missingEmitted == 0 && totalNodesHealed == 0 && isComplete && !flushing && walkRoot == stateRoot) {
     verificationPassComplete = true                          // suppresses the watchdog (FR-006)
     self ! HealingCheckCompletion                            // single chokepoint (D1) → StateHealingComplete
   }
   // else: unchanged — node idle, watchdog will force walk #2 as today
```
Contract (the load-bearing change): declare early **iff** all five conjuncts hold (D2). The `else` arm is byte-identical to today. `verificationPassComplete = true` is what makes the watchdog (C4) self-suppress, so there is no double-start. Routing via `self ! HealingCheckCompletion` (NOT a direct `StateHealingComplete`) is mandatory for byte-parity (C3).

## C3 — Completion chokepoint (UNCHANGED — byte-parity, FR-002)

```
// HealingCheckCompletion (:889-912): gate `verificationPassComplete || totalNodesHealed==0` →
//   markComplete (:908) → StateHealingComplete (:912). The walk does NO state writes.
```
Contract: the early path enters this *existing* gate with `verificationPassComplete=true`; it emits the SAME terminal messages, writes the SAME marker bytes, against the SAME state root as the two-walk path. No new completion site, no new marker set-point.

## C4 — Dead-pulse watchdog (UNCHANGED — fallback, FR-006)

```
// HealingStagnationCheck dead-pulse branch (:1012-1027): force-starts startVerificationBFS when
//   !trieWalkInProgress && !verificationBFSRunning && pendingTasks.isEmpty && activeRequests.isEmpty &&
//   recentHealed==0 && !pivotRefreshRequested && !verificationPassComplete
```
Contract: left intact. After an early completion `verificationPassComplete==true`, so this branch CANNOT fire (no walk #2). If early completion does NOT fire (any conjunct false), this branch force-starts walk #2 exactly as today — the built-in kill-switch. No change here.

## C5 — Chesterton's Fence preserved (FR-007)

```
// The verification walk still runs on every completion path that is NOT a clean full rebuild:
//   - inline-discovery completion (discoverMissingChildren drained) → HealingCheckCompletion → (verificationPassComplete false) → verification
//   - any rebuild that healed/found-missing → guard false → watchdog → verification
```
Contract: the early path is reachable ONLY from the clean `FrontierRebuildComplete` handler. The protection the verification walk was introduced for (BUG-1, discoverMissingChildren shallowness) is untouched.

## C6 — No config, no storage, no migration (FR-009)

Unconditional (D5); no new `sync.conf` key, no `SnapSyncConfig` field, no CF change. Safe on a node with or without a persisted frontier/marker.

## Test contracts (deterministic, ScalaTest/TestKit — no `Thread.sleep`)

| # | Asserts | Maps to |
| --- | --- | --- |
| T-1 | Clean 0-missing rebuild (childless/complete fixture, persistence on, marker absent) → `controller.expectMsg(5.seconds, StateHealingComplete)` after ONE walk; `store.isComplete == true`. The 5s bound is the regression detector (without the fix nothing reaches the controller until the ~6-min watchdog). | FR-001/SC-001, C2 |
| T-2 | Rebuild that discovers a missing child (Branch/Extension referencing one un-stored child via `TestMptStorage`) → `pendingTasks > 0`, NO `StateHealingComplete` within 5s. | FR-003/SC-002, C2 |
| T-3 | Stale-root pivot safety: while walk-1 (root A) in flight, `HealingPivotRefreshed(root B != A)`; the stale `FrontierRebuildComplete(walkRoot=A)` lands → NO early completion (`walkRoot != stateRoot`). | FR-003/FR-005/SC-002, C2 |
| T-4 | Byte-parity / no double-fire: clean fixture → `expectMsg(StateHealingComplete)` then `expectNoMessage(2.seconds)`; `store.isComplete` matches the two-walk outcome. | FR-002/SC-003, C2/C3 |
| T-5 | Watchdog self-suppression: after early completion `verificationPassComplete == true`, so the dead-pulse branch cannot fire walk #2. | FR-006/SC-004, C4 |

Per the host constraint, do NOT run `sbt` while the live barad-dûr ETC node is mid-walk; hand to `eye` to run `sbt testOnly *HealingFrontierResume*` (and the new specs) on CI or an idle host.
