---
description: "Task list — Moving-Root Delta Heal for SNAP Completion"
---

# Tasks: Moving-Root Delta Heal for SNAP Completion

**Input**: Design documents from `specs/009-moving-root-delta-heal/`
**Prerequisites**: plan.md, spec.md, research.md (forge Phase-0 GREEN + 2 corrections), data-model.md, contracts/internal-contracts.md, quickstart.md

**Tests**: INCLUDED — consensus-critical ETC code; parity (finalized root byte-equal to canonical) is a hard gate.

**CONSENSUS PROTOCOL**: every implementation task touching the heal / state-root / completion-gate path MUST be done by `forge`, compile-fixed by `wraith`, validated by `eye`. The content-hash store gate (`TrieNodeHealingCoordinator.scala:1504-1519`) and the `finalizeSnapSync` anchor guard (`SNAPSyncController.scala:4496-4506`) MUST stay byte-untouched (the content gate now MATCHES by construction). The completion gate's soundness (the pruned descent, FR-005/Decision 5) is the load-bearing consensus invariant.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: parallelizable (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (Setup, Polish carry no story label)

## Path Conventions
Single SBT `main` module. Heal logic: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`. Controller: `.../snap/SNAPSyncController.scala`. Config: `src/main/resources/conf/base/sync.conf`. Tests: `src/test/scala/.../snap/actors/`.

---

## Phase 1: Setup

- [ ] T001 Add config flag `moving-root-delta-heal` (default `true` for ETC SNAP) to `src/main/resources/conf/base/sync.conf`; parse into `SNAPSyncConfig` in `SNAPSyncController.scala` (near `:5222`, alongside `deferred-merkleization`/`decoupled-heal-serve-root`); thread it to `TrieNodeHealingCoordinator.props` (~`:3482`); mirror the default in `src/test/scala/.../TestSyncConfig.scala` (or the snap spec's `SNAPSyncConfig()` default).
- [ ] T002 Confirm `deferred-merkleization=false` is authoritative for ETC SNAP runtime: `sync.conf:74` already `false`; document in `quickstart.md` that the ops file `ops/barad-dur/fukuii-conf-1/base.conf:354` must be flipped `true`→`false` for the live test (ops, out of PR). No code change beyond the confirmation note.
- [ ] T003 [P] Create deterministic test fixtures in `src/test/scala/.../snap/actors/` (or a fixtures helper): (a) a small local trie with a KNOWN missing-node delta + a peer-serving stub for the matching root; (b) a "download-present-but-incomplete subtree" fixture (a present interior node whose child is absent) for the completion-gate soundness test.

## Phase 2: Foundational

- [ ] T004 [P] Confirm and document (in `research.md`/code comment) the existing reusable machinery so US2/US3 build on it, not around it: `discoverMissingChildren` (`:2285-2417`, already geth-equivalent, invoked at `:1562`); the re-peg path `completePivotRefreshWithStateRoot`→`HealingPivotRefreshed` (`SNAPSyncController.scala:4028-4183`) which already moves the walk root to a canonical header root and already seeds an absent root (`TrieNodeHealingCoordinator.scala:943-947`); the pruned descent `startVerificationBFS` (`:2212`) + oracle (`:1878-1883`). No behavior change — this is the wiring map the next phases edit.

**Checkpoint**: flag wired; reusable machinery mapped.

---

## Phase 3: User Story 2 — Single served root + seed-absent-root + delta discovery (Priority: P2)

**Goal**: the heal fetches only the missing delta against ONE current served root; served nodes match the content gate; an absent root is fetched, not handed off.
**Independent test**: with a peer serving root S and the heal root = S, the absent root node is fetched and accepted, only the delta is requested, and the frontier drains.

- [ ] T005 [US2] Collapse the fetch root to the heal root: in `TrieNodeHealingCoordinator.requestNextBatch` (`:1446-1451`), under `moving-root-delta-heal`, set `rootHash = stateRoot` (drop the `if (decoupledHealServeRoot) serveRoot` selection). Completeness is already judged vs `stateRoot` (`:1445`), so fetch+gate now use ONE root and served nodes match by construction. (C1; the spec-004 wrong-axis fix.)
- [ ] T006 [US2] Seed-from-absent-root at heal start: in `StartTrieNodeHealing` (`:632`), under the flag, replace the absent-root `else` branch (`:715-744`, currently `HealingRootUnservable` handoff) with the same seed `HealingPivotRefreshed:943-947` already performs — enqueue `HealingEntry(Seq(emptyPath), root)`, add to `pendingHashSet`, `tryRedispatchPendingTasks()`. Root-PRESENT branch unchanged; persisted nodes NOT discarded. (C2)
- [ ] T007 [US2] Make `discoverMissingChildren` the SOLE discovery seed: under the flag, do not seed discovery from `rebuildFrontierBFS` (gate the `StartTrieNodeHealing` root-present BFS-discovery at `:707-712` to crash-restart only); confirm discovery drives to `pending==0` via `requestNextBatch`/`dispatchIfPossible`. (C3; retire the O(total) first BFS as discovery.)
- [ ] T008 [US2] Tests (`TrieNodeHealingCoordinatorSpec.scala`): (a) C1 — a served root node + internal nodes pass the content gate and persist (no "non-matching hash" drops) when fetch root == heal root; (b) C2 — heal start with an absent root + serving stub fetches the root (no `HealingRootUnservable` as default); (c) C3 — only the known delta is requested (count ≈ delta, ≪ total), frontier drains. Deterministic.

**Checkpoint**: the heal fetches the delta against one served root and makes progress (healed>0).

---

## Phase 4: User Story 3 — Moving root + sound completion (Priority: P3)

**Goal**: the heal re-pegs to a fresh served root on stale-move, retains all verified nodes, and declares completion only via a pruned descent that closes the download-mosaic gap.
**Independent test**: force the heal root to age out mid-heal → re-peg, retained progress, still completes; a download-present-but-incomplete subtree does NOT complete on delta-discovery alone.

- [ ] T009 [US3] Route the stale-move trigger to move the WALK root: in the `HealingServeRoot(blockNumber, rootOpt)` handler (`:636-651`), under the flag, emit `HealingPivotRefreshed(root)` (re-peg the heal root) instead of `HealingServeRootRefresh(root)` (serve-root only). Reuse the `maybeRequestHealingServeRoot` staleness math (`:3651-3654`, head advanced past `healRoot + margin*2`). (C4 trigger)
- [ ] T010 [US3] Re-peg retains persisted nodes: confirm `HealingPivotRefreshed` (`:920-930`) clears ONLY in-memory frontier (`pendingTasks`/`activeRequests`/`pendingHashSet`) and that `clearPersistedFrontier` (`:920`) touches only the optional frontier-mirror CF, never the trie-node CF. Add an assertion/guard so a re-peg can never delete trie nodes. (C4)
- [ ] T011 [US3] Keep the pruned final descent as the completion gate: ensure `HealingCheckCompletion` (`:1059-1097`) still requires `isComplete && verificationPassComplete`; `startVerificationBFS` (`:2212`) uses the spec-005 pruned oracle (`:1878-1883`, durably-complete subtrees skipped → O(delta)); `HealingPivotRefreshed:935` resets `verificationPassComplete=false` and the `walkRoot==stateRoot` guard (`:787-797`) excludes a stale completion against a new root. Do NOT retire the descent. (C5 — consensus soundness)
- [ ] T012 [US3] Tests: (a) C4 — a re-peg mid-heal preserves persisted verified nodes (no re-download of completed subtrees), post-re-peg missing ≤ pre; (b) C5 — a download-present-but-incomplete subtree does NOT reach completion on delta-discovery alone (the pruned descent finds the gap, re-enqueues, completion only after a clean descent vs the current root); (c) integration — heal completes under a simulated moving root where the initial root is no longer served by the end.

**Checkpoint**: the heal converges under a moving root and never declares a false completion.

---

## Phase 5: User Story 1 — Fresh SNAP completes end-to-end + supersession (Priority: P1) 🎯

**Goal**: tie it together — the heal seeds the served root, drives to a sound completion, finalizes on the canonical root, block-imports past the pivot; the superseded paths are inert.
**Independent test**: fresh SNAP (simulated served peers) heals to completion, finalizes on a state root == canonical header, imports past pivot, no re-snap loop.

- [ ] T013 [US1] End-to-end wiring: heal start (deferred=false) seeds the served heal root → delta discovery → pruned descent → completion → `finalizeSnapSync` on the canonical heal root (anchor guard `:4496-4506` unchanged; heal root is always a canonical header `stateRoot` via `completePivotRefreshWithStateRoot:4047`). Confirm `shouldSkipHealingAfterDownloads` (`:4850-4878`) does not skip healing on the non-deferred path (and correct its false comment at `:4876-4878`).
- [ ] T014 [US1] Supersede spec-004: when `moving-root-delta-heal` is ON, gate `decoupledHealServeRoot` OFF — `serveRoot` (`:376`), `HealingServeRootRefresh` (`:977-1012`), and the `requestNextBatch` serve-root selection (`:1448`) become inert. Keep the code one release behind the flag for A/B (FR-007). Make the `HealingRootUnservable` handoff (`SNAPSyncController.scala:1204-1214`) a BOUNDED last-resort (only after re-peg budget exhausted with no served root), never fail-open.
- [ ] T015 [US1] Retire spec-008 freeze-re-fetch: when the flag is ON, gate the `beginFinalizeRefetch`/`failFinalizeRefetch` trigger OFF (`grep beginFinalizeRefetch FinalizeRefetch` in `SNAPSyncController.scala`); keep spec-008 US1 (local-merkleize gate) + US2 (FlatAccountStorage retention) as safe foundation, not the completion path.
- [ ] T016 [US1] Tests: (a) integration — fresh-SNAP heal-to-completion + finalize-on-canonical-root with a simulated served peer; (b) flag OFF ⇒ legacy spec-004 path byte-unchanged; (c) A/B parity — off vs on → identical finalized root on the fixture (SC-005).

**Checkpoint**: the moving-root delta heal completes a (simulated) fresh SNAP end-to-end; one strategy is live by default.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T017 [P] Observability (FR-009): structured logs/metrics for missing-node count per round, current heal root + re-peg events, heal throughput (nodes/s), and outstanding requests, in `TrieNodeHealingCoordinator` — so an operator sees the delta shrinking and completion is real.
- [ ] T018 [P] No-op verification tests: ETH/Sepolia sync unaffected; flag OFF byte-identical to spec-004 (FR-008).
- [ ] T019 Consensus parity gate: `sbt testCrypto testMPT` + `sbt testEthereum` green with the flag ON (SC-005) — run by `eye`.
- [ ] T020 `sbt formatAll` + `sbt compile-all`; `wraith` clears compile fallout without altering consensus semantics; `eye` runs `testStandard`.
- [ ] T021 Fresh-Mordor A/B E2E per `quickstart.md` (deferred=false, flag ON, `-Xmx ≥ 6g`, WIPED rocksdb): assert heal seeds the served root → missing-node count SHRINKS → re-peg RETAINS progress → pruned descent → SNAP Completed → block-import past pivot, no re-snap loop, no O(total) BFS; plus the peers-stop-mid-heal behavior and the A/B replay (off vs on → identical/canonical finalized root) (SC-001/002/004/005).
- [ ] T022 [P] Characterize heal throughput (nodes/s, delta size) + the non-deferred inline-build CPU/GC on the i5-4430 host (SC-006); note the spec-007 hot-path interaction (report-and-record).

---

## Dependencies & Execution Order

```
Setup (T001-T003) ─► Foundational map (T004)
   └─► US2 (T005-T008)  [single root + seed-absent + delta discovery]  ──┐
          └─► US3 (T009-T012) [re-peg + sound pruned-descent completion] ──┐
                 └─► US1 (T013-T016) [end-to-end + supersession]  ──► Polish (T017-T022)
```

- **US3 depends on US2** (re-peg + completion build on the single-root delta heal).
- **US1 depends on US2+US3** (the end-to-end outcome ties them together + retires the old paths).
- **Coupling note**: this is essentially ONE coherent heal-strategy change. The MVP is US2+US3+US1 together (the moving-root delta heal); they are split by concern (fetch/discovery → moving-root/soundness → end-to-end/supersession) but ship as one mechanism. Each phase is independently *testable* even though completion needs all three.

## Parallel Opportunities

- Setup: T003 [P] alongside T001/T002; Foundational T004 [P].
- US2: T008 (tests) after T005-T007.
- US3: T012 (tests) after T009-T011.
- US1: T016 (tests) after T013-T015.
- Polish: T017, T018, T022 are [P].

## Implementation Strategy

- The change is **net-subtractive** on machinery (removes the O(total) first BFS as discovery, the walk/serve split, the freeze-re-fetch) and reuses the existing re-peg path + `discoverMissingChildren`. Build US2 → US3 → US1, compiling/testing after each phase (small batches). Consensus tasks → `forge`; compile fallout → `wraith`; validation → `eye`.
- **Do not open/flip the PR to ready until** the fresh-Mordor A/B (T021) shows a verified completion (heal seeds served root, delta shrinks, re-peg retains, completes, finalizes on canonical root, no re-snap) AND the parity suites (T019) are green. The completion-gate soundness test (T012b) is a mandatory gate — a false completion is a consensus failure. Commit/push only when the user asks.
