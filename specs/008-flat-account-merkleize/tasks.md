---
description: "Task list — Flat-Account Retention & Local State-Root Merkleization for SNAP Completion"
---

# Tasks: Flat-Account Retention & Local State-Root Merkleization for SNAP Completion

**Input**: Design documents from `specs/008-flat-account-merkleize/`
**Prerequisites**: plan.md, spec.md, research.md (forge YELLOW verdict), data-model.md, contracts/internal-contracts.md, quickstart.md

**Tests**: INCLUDED — this is consensus-critical ETC code; parity (computed root byte-equal to canonical) is a hard gate, so unit/integration/parity tests are mandatory, not optional.

**CONSENSUS PROTOCOL**: Every implementation task touching the SNAP finalize / state-root / coordinator path MUST be done by `forge` (ETC), compile-fixed by `wraith`, and validated by `eye`. The content-hash store gate and the `finalizeSnapSync` anchor guard MUST stay byte-untouched; the `computedRoot==header` check is additive only.

## Format: `[ID] [P?] [Story] Description`
- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3 (Setup, Foundational, Polish carry no story label)

## Path Conventions
Single SBT project, `main` module. Source under `src/main/scala/com/chipprbots/ethereum/`, tests under `src/test/scala/com/chipprbots/ethereum/`, config under `src/main/resources/conf/base/`.

---

## Phase 1: Setup

- [X] T001 Add config flag `flat-account-merkleize` (default `true` for fresh ETC SNAP) to `src/main/resources/conf/base/sync.conf`; parse it into `SNAPSyncConfig` in `src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`; mirror the default in `src/test/scala/.../TestSyncConfig.scala`.
- [X] T002 [P] Confirm/register the `FlatAccountStorage` RocksDB namespace and dependency-inject it into `AccountRangeCoordinator` and the finalize path in `src/main/scala/com/chipprbots/ethereum/db/storage/Storages.scala` (mirror how `FlatSlotStorage` is wired).
- [X] T003 [P] Create deterministic test fixtures in `src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/fixtures/`: (a) a small account-set with a KNOWN canonical state root for merkleize parity; (b) a perturbed-leaf variant (one stale balance/nonce/storageRoot) for the fail-closed test.

## Phase 2: Foundational (blocking prerequisites for all stories)

- [X] T004 Implement/confirm `FlatAccountStorage.putAccountsBatch(pairs)` and `seekFrom(startHash)` ascending full-keyspace enumeration in `src/main/scala/com/chipprbots/ethereum/db/storage/FlatAccountStorage.scala` (parity with `FlatSlotStorage.putSlotsBatch`/`seekStorageRange`). This is the C1 substrate both US2 (write) and US1 (read) depend on.
- [X] T005 [P] Write `src/test/scala/com/chipprbots/ethereum/db/storage/FlatAccountStorageSpec.scala`: write N accounts → `seekFrom(0x00..)` returns exactly N in strictly-ascending order, no gaps/dupes; overwrite replaces a key (C1 contract).

**Checkpoint**: flat-account store is writable + ordered-enumerable and unit-proven before any sync-path wiring.

---

## Phase 3: User Story 2 — Retain account leaves during download (Priority: P2)

**Goal**: every downloaded account leaf is retained and enumerable in ascending order (the substrate for local merkleization).
**Independent test**: after a SNAP account-download phase, enumerate retained leaves — count == served, strictly ascending; merkleizing them reproduces the fixture account-trie root.

- [X] T006 [US2] In `AccountRangeCoordinator.handleStoreAccountChunk` (`AccountRangeCoordinator.scala:~1397`), write each `(accountHash, accountRLP)` via `flatAccountStorage.putAccountsBatch(...)` alongside the existing `trie.update(...)`, gated by the `flat-account-merkleize` flag. Symmetric to `StorageRangeCoordinator`'s slot write.
- [X] T007 [US2] Add to `src/test/scala/.../AccountRangeCoordinatorSpec.scala`: after processing account ranges, retained-leaf count == accounts served and enumeration is ascending; flag OFF ⇒ no flat-account writes (legacy).
- [X] T008 [US2] [P] Test crash/restart retention in `AccountRangeCoordinatorSpec` (or a dedicated spec): retained leaves persist across a simulated restart (RocksDB-backed); document that coherence still comes from the US3 finalize re-fetch, not resume bookkeeping.

**Checkpoint**: leaves are durably retained and ordered — US1 can now compute a root from them.

---

## Phase 4: User Story 1 — Fresh SNAP completes with a self-verified coherent root (Priority: P1) 🎯 MVP (with US2)

**Goal**: finalize ONLY on a locally-computed root proven byte-equal to the canonical header root; never finalize an unverified/absent root (fail closed).
**Independent test**: fixture state → `computedRoot` == canonical root → finalize; perturbed leaf → `computedRoot != header` → does NOT finalize (fail closed); feature off vs on → never a different finalized root.

- [X] T009 [US1] In `SNAPSyncController.scala` finalize path, add a single-pass local merkleization: stream `FlatAccountStorage.seekFrom(0x00..)` ascending into one `StackTrie`, `update(accountHash, accountRLP)` per account, taking each `storageRoot` DIRECTLY off the leaf RLP (`Account.scala:32`, NOT re-derived from `FlatSlotStorage`); produce `computedRoot` via `StackTrie.hash()` (C3). DONE: pure `SNAPSyncController.computeLocalStateRoot(flatAccountStorage): Either[String, ByteString]` in the companion object (streams `seekFrom(0x00..00)` via fs2 `compile.fold` into one StackTrie — O(depth) memory, no full-keyspace materialization); fail-closed `Left` on empty store / seek error / merkleize throw.
- [X] T010 [US1] Gate finalize on `computedRoot == pivotHeader(R_final).stateRoot`, layered BEFORE the existing anchor guard (`SNAPSyncController.scala:4496-4507`, byte-untouched). On mismatch/any error: fail closed — do NOT finalize, do NOT partial-commit; fall back to retry on a fresher root or the existing `HealingRootUnservable → completeSnapSync` handoff (FR-003/FR-004). Content-hash store gate byte-untouched. DONE: gate inside `finalizeSnapSync`'s `Some(pivotHeader)` branch, before the anchor guard, gated `flatAccountMerkleize && approximateKeyCount > 0` (empty-store ⇒ skip ⇒ byte-identical legacy path); fail-closed escalates via `HealingImpossible` + `break()` (same primitive the anchor guard uses) BEFORE any commit. Anchor guard + content-hash gate byte-untouched (git diff zero in those regions).
- [X] T011 [US1] Add to `src/test/scala/.../SNAPSyncControllerSpec.scala`: (a) fixture → `computedRoot` == known canonical root; (b) perturbed-leaf fixture → `computedRoot != header` → node does NOT finalize (asserts fail-closed, no partial commit). DONE in focused new spec `src/test/scala/.../snap/FlatAccountMerkleizeSpec.scala` (real RocksDB-backed FlatAccountStorage; the merkleize core is state-free so tested directly without the actor): (a) canonical leaves → `computedRoot == canonicalStateRoot`; (b) perturbed → `computedRoot == perturbedStateRoot != header` ⇒ gate boolean `false` (fail closed); plus empty-store `Left`.
- [X] T012 [US1] [P] A/B parity unit test: with the flag off (legacy) vs on, the finalized root on the fixture is identical (SC-003) — enabling the feature never yields a *different* finalized root. DONE in `FlatAccountMerkleizeSpec` — feature-on StackTrie root `shouldBe` the legacy MPT canonical root byte-for-byte.

**Checkpoint**: the SAFETY MVP — the node can compute+verify a root and is structurally incapable of finalizing a wrong one. On a real advancing sync it would (without US3) fail closed on stale leaves — safe but not yet completing.

---

## Phase 5: User Story 3 — Coherent finalize when the pivot advances (Priority: P3)

**Goal**: under pivot advance, finalize on a single coherent state — every account's leaf reflects the FINAL pivot — so `computedRoot == final header` and the sync COMPLETES.
**Independent test**: force a pivot advance mid-download → finalize → `computedRoot` == FINAL pivot header root (not an intermediate).

- [ ] T013 [US3] Add a `finalizing` freeze latch to `AccountRangeCoordinator`: while latched, IGNORE `PivotRefreshed` (no `rootHash` re-tag, no re-enqueue). Follow the existing finalization-ignore precedent (`AccountRangeCoordinator.scala:929-930`). (C2)
- [ ] T014 [US3] Add the symmetric `finalizing` freeze latch to `StorageRangeCoordinator`: while latched, IGNORE `StoragePivotRefreshed` (`StorageRangeCoordinator.scala:~867`). (C2)
- [ ] T015 [US3] Implement the finalize freeze+re-fetch in `SNAPSyncController.scala`: pick a servable `R_final = networkBest − offset` (reuse `RequestRecentRoot`/probe machinery), send `BeginFinalizing(R_final)` to both coordinators, re-fetch the stale account ranges (ranges whose completion root ≠ `R_final`; full re-fetch fallback) via `GetAccountRange(R_final)` into `FlatAccountStorage` (overwrite stale leaves), and reconcile changed contracts' slots via `GetStorageRange(R_final)` into `FlatSlotStorage` for import coherence. Then run T009/T010.
- [ ] T016 [US3] Fail-closed path: if peers stop serving `R_final` before the re-fetch pass completes (worker proof-verify against `expectedRoot` fails — `AccountRangeWorker.scala:121-136`), do NOT finalize; `EndFinalizing`/abort, retry on a fresher `R_final` (bounded), else the existing handoff (FR-004).
- [ ] T017 [US3] [P] Coordinator latch tests in `AccountRangeCoordinatorSpec`/`StorageRangeCoordinatorSpec`: a `PivotRefreshed` delivered while `finalizing` does NOT change any task `rootHash` or the re-fetch target; after `EndFinalizing`, pivot-advance is honored again (C2).
- [ ] T018 [US3] Integration test (`src/it/scala/.../` or `SNAPSyncControllerItSpec`): simulate one+ pivot advances during account download, then finalize, and assert `computedRoot` == the FINAL pivot's canonical header state root and the node transitions to block-import (no re-snap loop), including an account changed across the advance.

**Checkpoint**: end-to-end completion under pivot advance — the full feature.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T019 [P] Observability (FR-010): structured logs/metrics for retention-active, `BeginFinalizing(R_final)`, re-fetch progress, and `computedRoot==header` pass/fail, in `SNAPSyncController.scala` + the coordinators, so an operator can confirm which path ran and whether verification passed.
- [ ] T020 [P] No-op verification tests: deferred-merkleization path is byte-unchanged with the flag effects; ETH/Sepolia sync path untouched (FR-008).
- [ ] T021 Consensus parity gate: `sbt testCrypto testMPT` and `sbt testEthereum` green with the feature ON (SC-005) — run by `eye`.
- [ ] T022 `sbt formatAll` (scalafix+scalafmt) + `sbt compile-all`; `wraith` clears any compile errors without altering consensus semantics; `eye` runs `testStandard`.
- [ ] T023 Fresh Mordor A/B end-to-end validation per `quickstart.md` (build fat-jar → quick-docker → force-recreate fukuii-secondary on a WIPED rocksdb, flag on): assert SNAP completes, `computedRoot==header` logged, block-import past pivot, no re-snap loop, plus the peers-stop-mid-pass fail-closed negative test (SC-001/002/004). Then A/B replay flag off vs on → identical/canonical root (SC-003).
- [ ] T024 [P] Characterize finalize merkleization wall-clock + allocation on the i5-4430 host (SC-006); note interaction with spec-007 hot-path work (report-and-record, not a gate).

---

## Dependencies & Execution Order

```
Setup (T001-T003)
   └─► Foundational (T004-T005)   [flat-account store usable + proven]
          └─► US2 (T006-T008)     [retain leaves]  ──┐
                 └─► US1 (T009-T012) [compute+verify+fail-closed]  ──┐
                        └─► US3 (T013-T018) [freeze+re-fetch coherence] ──► Polish (T019-T024)
```

- **US1 depends on US2** (cannot merkleize without retained leaves).
- **US3 depends on US1** (re-fetch feeds the same merkleize/verify) and on the latches.
- **Story independence note**: US2 is independently testable (write+enumerate). US1 is independently testable on fixtures (compute/verify/fail-closed) and on a real sync delivers the *safety* property even before US3 (it fails closed on stale leaves). US3 delivers *completion* under pivot advance. The spec's value-priority (US1=P1) and the build order (US2→US1→US3) differ because retention is a technical prerequisite for the P1 outcome.

## Parallel Opportunities

- Setup: T002 and T003 run in parallel after T001.
- Foundational: T005 (test) can be written in parallel with T004 once the API signature is fixed.
- US2: T008 [P] alongside T007.
- US1: T012 [P] alongside T011.
- US3: T017 [P] alongside T015/T016 (different files).
- Polish: T019, T020, T024 are [P] (independent files/areas).

## Implementation Strategy

- **MVP = US2 + US1** (retention + safe local compute/verify/fail-closed). This is shippable as a *safety* improvement on its own: the node becomes structurally incapable of finalizing a wrong/absent root, even if (without US3) it cannot yet complete under pivot advance.
- **Full feature = + US3** (freeze + targeted re-fetch) → end-to-end completion on a real advancing Mordor sync.
- Build incrementally, compiling/testing after each phase (small batches). Consensus tasks routed to `forge`; compile fallout to `wraith`; validation to `eye`. Do not open the PR until the fresh-Mordor A/B (T023) shows a verified completion and the parity suites (T021) are green. Commit/push only when the user asks.
