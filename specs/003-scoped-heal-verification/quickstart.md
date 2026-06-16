# Quickstart & Validation: Scoped Post-Heal Verification

This guide describes how to validate that the feature works end-to-end and meets the spec's success criteria. It references [data-model.md](./data-model.md) and [contracts/internal-interfaces.md](./contracts/internal-interfaces.md) rather than duplicating them. Implementation lives in `tasks.md` + the implementation phase.

## Prerequisites

- Branch `003-scoped-heal-verification` (based on staging ≥ `0.7.13`, which carries the hold-pivot livelock fix `#1357`).
- The deterministic test runner: `sbt testEssential` (Tier-1) / `sbt testStandard` (Tier-1+2). **Do not run while the barad-dûr node is active on the same host** (freezes it — see CLAUDE.md); run on CI or an idle host.
- Config keys (default-on): `sync.snap-sync.scoped-heal-verification = true`, `sync.snap-sync.scoped-heal-max-paths = 200000`.

## What "done" looks like

| Success criterion | How it's proven |
| --- | --- |
| SC-001 / SC-005 — verification in <1 min (≥95% faster) | Live node: time from last heal to `StateHealingComplete` via the scoped path; metric `app_snap_scoped_verification_seconds`. |
| SC-002 — zero false completions | Unit parity tests + no `MissingRootNode` at block import after a scoped completion. |
| SC-003 — 100% safe fallback | Unit tests for each fallback clause F1-F6 take the full-root path. |
| SC-004 — byte-for-byte parity | Unit test: scoped vs full-root completion → identical state root + identical CF `g` completeness marker. |

## Validation scenarios (unit / deterministic — Pekko TestKit, no `Thread.sleep`)

1. **Scope capture (V1 — the core correctness risk)**: drive the coordinator to heal N nodes; assert the healed-paths set equals exactly those N `(hash, pathset)` pairs (from the single heal site). Proves no healed subtree is omitted from the scope.
2. **Scoped completion (US1 / FR-002, FR-003)**: with the durable coverage marker set and a small healed set whose subtrees are clean, run the completion gate → assert it seeds the BFS from the healed paths (not the root), visits only those subtrees, and reaches `StateHealingComplete`.
3. **Gap below a healed node (US1 #2 / FR-006)**: heal a node whose subtree still has a deeper missing descendant → assert the scoped walk discovers it, emits it for healing, and does **not** declare completion until clean.
4. **Parity (US2 #3 / FR-007, SC-004)**: for one fixed healed state, reach completion via scoped and via full-root (config flip) → assert identical state root and identical CF `g` marker bytes.
5. **Fallback clauses (US2 / FR-005, FR-009, FR-011 / SC-003)** — each must take the full-root path:
   - F1 `scoped-heal-verification = false`
   - F2 coverage precondition unproven (no CF `g` marker)
   - F3 healed-paths set empty / lost to restart
   - F4 set size exceeds `scoped-heal-max-paths`
   - F5 pivot root changed mid-round (`healedPathsRoot` ≠ current root)
   - F6 fresh/zero-coverage node (subsumed by F2)
6. **Observability (US3 / FR-010)**: assert the scoped path emits the engagement log + the `app_snap_scoped_verification_*` metrics; with the feature disabled, assert the full-root path + "scoping disabled" log.

## Live validation (on the barad-dûr ETC node, read-only)

After deploy, when the node reaches the post-heal completion gate on a fully-covered trie with a small healed set, confirm:

- Log shows the scoped path engaged (engagement line from FR-010) and `StateHealingComplete` follows within seconds — **not** a fresh `[HEAL-BFS] Level 0` full re-walk.
- Metric `app_snap_scoped_verification_seconds` is sub-minute and `app_snap_scoped_verification_paths` matches the healed-gap count (~118 typical).
- Contrast signal: a `forced full-root` run (precondition unmet) still shows the multi-hour walk — confirming the fallback path is intact.

Use `docker logs --tail N` (the full-log read truncates on this node) and the metrics endpoint (`:9095`).

## Out of scope for validation here

- Persisting the healed-paths set to resume scoped after a restart (N1 — deferred; restart safely falls back to full-root).
- The `scoped-heal-max-paths = 200000` bound vs the 6 GB reference heap (N2 — validate with a real over-bound round before shipping; tracked as a task).
