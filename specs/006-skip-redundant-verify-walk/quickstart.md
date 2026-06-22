# Quickstart & Validation: Skip the Redundant Second Verification Walk

How to validate the feature against the spec's success criteria. References [data-model.md](./data-model.md) and [contracts/internal-interfaces.md](./contracts/internal-interfaces.md) rather than duplicating them.

## Prerequisites

- Branch based on `staging` (carries specs 002/003/004; independent of spec 005 / PR #1364).
- Deterministic test runner: `sbt testEssential` / a targeted `sbt testOnly *HealingFrontierResume* *...*`. **Do NOT run while the barad-dûr node is active** (freezes the host — CLAUDE.md); run on CI or an idle host.
- No config or migration needed (unconditional, FR-009).

## What "done" looks like

| Success criterion | How it's proven |
| --- | --- |
| SC-001 — one walk not two (~halve completion) | Unit T-1: clean rebuild → `StateHealingComplete` within 5s (no ~6-min watchdog gap, no walk #2). Live: a restarted clean node logs `Full-state rebuild complete` then `StateHealingComplete` directly — NO intervening `Starting verification BFS` / fresh `Level 0`. |
| SC-002 — zero false completions | Unit T-2 (missing found → no early completion), T-3 (stale root → no early completion); no `MissingRootNode` at block import after an early completion. |
| SC-003 — byte-for-byte parity | Unit T-4: early-path completion → identical `StateHealingComplete` + identical `store.isComplete` marker vs the two-walk path. |
| SC-004 — no regression on non-clean paths | Unit T-2/T-5: a non-clean path still verifies; the watchdog still fires when early completion doesn't; no double-start. |
| SC-005 — no config / no migration | The feature is unconditional; there is no flag and no schema change to exercise. |

## Validation scenarios (unit / deterministic — ScalaTest/TestKit, no `Thread.sleep`)

Run the C-section test contracts T-1…T-5 (see [contracts](./contracts/internal-interfaces.md)). The correctness-critical ones:

1. **One-walk completion (T-1, FR-001 — the value)**: clean rebuild fixture → `expectMsg(5.seconds, StateHealingComplete)`; assert no second walk ran.
2. **Never-false-complete on missing (T-2, FR-003 — load-bearing)**: a rebuild that discovers a missing child → `pendingTasks > 0`, NO completion within 5s.
3. **Stale-root safety (T-3, FR-005 — load-bearing)**: pivot to root B while walk-1 (root A) in flight → stale `FrontierRebuildComplete(A)` must NOT complete.
4. **Byte-parity (T-4, FR-002/SC-003)**: early-path vs two-walk → identical completion message + marker.
5. **Watchdog suppression (T-5, FR-006)**: after early completion, `verificationPassComplete==true` so the dead-pulse branch cannot fire walk #2.

## Live validation (on the barad-dûr ETC node, read-only) — NEXT clean restart only

After deploy (NOT the in-flight walk — this cannot help a walk already running), on the next restart of a clean node:
- Logs show `[HEAL-RESTART] … re-running full-state BFS` → one `[HEAL-BFS]` sweep → `Full-state rebuild complete` → **directly** `StateHealingComplete` / `Starting regular sync`, with **no** intervening `Starting verification BFS` and no second fresh `[HEAL-BFS] Level 0`.
- Wall-clock: completion ~halves vs the two-walk sequence (~16-20h instead of ~30-40h on ETC mainnet).
- Contrast (regression signal): a `Starting verification BFS` after `Full-state rebuild complete` on a clean node means early completion did not fire (a guard conjunct was false) — investigate which, but the node still completes safely via the watchdog.

## Out of scope for validation here

- The shallow `discoverMissingChildren` path and the watchdog itself (unchanged; the fence stays — FR-007).
- Deferred-merkleization architecture; spec-005 subtree-complete records (independent).
- Widening the guard beyond `totalNodesHealed==0` (deferred until one clean live validation — D5).
