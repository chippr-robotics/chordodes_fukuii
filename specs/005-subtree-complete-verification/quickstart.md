# Quickstart & Validation: Subtree-Complete Heal Verification

How to validate the feature end-to-end against the spec's success criteria. References [data-model.md](./data-model.md) and [contracts/internal-interfaces.md](./contracts/internal-interfaces.md) rather than duplicating them.

## Prerequisites

- Branch based on `staging` (carries spec 002/003/004). Default-on config: `sync.snap-sync.pruned-heal-verification = true`.
- Deterministic test runner: `sbt testEssential` / `sbt testStandard`. **Do NOT run while the barad-dûr node is active** (freezes the host — see CLAUDE.md); run on CI or an idle host.

## What "done" looks like

| Success criterion | How it's proven |
| --- | --- |
| SC-001 / SC-006 — fresh-node verification in minutes, cost ∝ missing not trie size | Live node: time from heal-frontier-drained to `StateHealingComplete` on a fresh node with seeded records; metric `app_…_pruned_verification_seconds` and pruned-vs-visited counts. |
| SC-002 — zero false completions | Unit T-2: a present parent with a missing descendant (no record) never completes; no `MissingRootNode` at block import after a pruned completion. |
| SC-003 — byte-for-byte parity | Unit T-4: pruned vs full-walk completion → identical state root + identical CF `'g'` marker bytes. |
| SC-004 — crash safety | Unit T-3 + T-8: record never precedes durable subtree; abandoned-fragment / truncated-descent never recorded. |
| SC-005 — safe fallback | Unit T-6: flag off / Path scheme / no records ⇒ full-trie verification, byte-identical. |

## Validation scenarios (unit / deterministic — ScalaTest/TestKit, no `Thread.sleep`)

Run the C-section test contracts T-1…T-8 (see [contracts](./contracts/internal-interfaces.md)). The correctness-critical ones:

1. **Prune-on-record (T-1, FR-001)**: record `isSubtreeComplete(X)`; assert the verification does not read X's children and reaches completion visiting O(missing).
2. **Never-false-prune (T-2, FR-002/FR-004 — the load-bearing safety test)**: present node X, no record, missing descendant beneath it → assert the walk descends X, emits the missing node, does NOT complete.
3. **Crash safety (T-3/T-8, FR-006)**: children committed, record absent ⇒ restart descends; assert a record is never written before its subtree's bytes are durable, and never from an abandoned/reset fragment or an LRU-evicted descent.
4. **Parity (T-4, FR-005/SC-003)**: one fixed healed state, reach completion pruned vs full (flag flip) → identical state root + identical CF `'g'` marker bytes.
5. **Fresh-node seeding (T-5, FR-003)**: seed records from SNAP fragment commits + heal subtree closures, then run the FIRST verification → assert it prunes the recorded subtrees with no prior full walk.
6. **Fallback (T-6, SC-005)**: flag off OR `storageScheme==Path` OR no records ⇒ full-trie verification, byte-identical.

## Live validation (on the barad-dûr ETC node, read-only)

After deploy, on a fresh post-SNAP node:
- The first completeness verification finishes in **minutes** (not a fresh `[HEAL-BFS] Level 0…` full ~16-20h walk); the engagement log + `app_…_pruned_subtrees` / `app_…_nodes_visited` metrics show most subtrees pruned.
- `StateHealingComplete` → regular sync follows, with **no** `MissingRootNode` error at subsequent block import (SC-002).
- Contrast: with the flag off (or Path scheme), the node still shows the full-trie walk — confirming the fallback path is intact.

Use `docker logs --tail N` (full-log read truncates on this node) and the metrics endpoint (`:9095`).

## Out of scope for validation here

- #2 persist BFS walk progress (restart resume) — separate follow-up spec.
- #3 SNAP range-boundary frontier seeding; #4 parallelism — follow-ups.
- StorageScheme.Path pruned verification — explicitly falls back to the full walk (D5).
