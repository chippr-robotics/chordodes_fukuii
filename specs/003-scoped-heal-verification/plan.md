# Implementation Plan: Scoped Post-Heal Verification

**Branch**: `003-scoped-heal-verification` | **Date**: 2026-06-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-scoped-heal-verification/spec.md`

## Summary

Scope the post-SNAP heal completion verification to re-traverse **only the subtrees rooted at the nodes healed this round**, instead of re-seeding the state root and re-walking the entire ~90M-node trie (~16-20h on slow storage). The approach **reuses the existing BFS traversal kernel (`rebuildFrontierBFS`) byte-for-byte** and only changes its level-0 content: instead of one seed (state root, empty path) it is seeded with the set of healed nodes' own `(hash, pathset)` pairs — which are already captured at the single heal site as `HealingEntry(pathset, hash)`. A small, actor-thread decision predicate at the completion gate (`HealingCheckCompletion`) chooses scoped vs full-root, and **falls back to the unchanged full-root verification** in every case where full-trie coverage is not durably proven. Completion is declared through the *same single* `verificationPassComplete` chokepoint, setting the *same* completeness marker and `StateHealingComplete` — so the outcome is byte-for-byte identical to today (FR-007). Gated by config `scoped-heal-verification` (default `true`, mirroring `heal-hold-pivot-on-stagnation`).

## Technical Context

**Language/Version**: Scala 3.3.7 LTS on JDK 25

**Primary Dependencies**: Apache Pekko (actors) for the healing coordinator; RocksDB (`HealingFrontierStorage` CF `g`, `BfsQueueStorage` CF `q`) for persisted frontier/marker and the BFS level queue; existing `MptStorage.multiGetNodes` for local trie reads.

**Storage**: No new persisted schema. Reuses the durable completeness marker in CF `g` (`HealingFrontierStorage.isComplete`) as the full-coverage precondition. The new healed-paths set is **in-memory only** (intentionally non-durable — a restart falls back to full-root).

**Testing**: ScalaTest + Pekko TestKit (deterministic, no `Thread.sleep` per Principle III). Unit tests for scope-capture completeness, the 6-clause fallback predicate, and scoped/full-root completion parity; a `forge`-aligned correctness focus on the byte-parity invariant (FR-007).

**Target Platform**: Linux server (barad-dûr ETC mainnet node, 16 GB host); chain ETC/Mordor (chain-ID 61, PoW, ECIP-1017). Not applicable to ETH/Sepolia consensus paths (sync-layer only).

**Project Type**: Single project — the `node` (main) module; an internal sync-orchestration change, no external API surface.

**Performance Goals**: Post-heal verification completes in **under 1 minute** for a typical ~100-200-node healed set on a full-coverage node (SC-001), a ≥95% wall-clock reduction vs the full-root baseline (SC-005). Traversal cost scales with the healed set, not the trie size.

**Constraints**: Byte-for-byte completion parity with full-root verification (FR-007, the binding consensus invariant); bounded healed-paths memory (`scoped-heal-max-paths`, default 200000, with full-root fallback on overflow — FR-011); deterministic; no network dependency (scoped verification is a pure local read).

**Scale/Scope**: ETC mainnet state trie ~90M+ trie nodes, ~18 levels deep; typical healed set tens-to-low-hundreds; the change is confined to `TrieNodeHealingCoordinator`, the `SNAPSyncController` completion handler, `Messages`/`SNAPSyncConfig`, and `sync.conf` (+ tests + metrics).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)** — **PASS.** This is consensus-*adjacent* (sync orchestration), not consensus-*critical*: it touches no EVM/gas/RLP/block-validation/reward/Ethash/signing code (verified: zero files under `vm/consensus/crypto/domain/ledger`). The post-heal verification is a pure local read (`multiGetNodes`) that never recomputes or rewrites a state root. FR-007 byte-parity holds by composition — `coverage(full) = coverage(rest, durable marker) ∧ coverage(healed subtrees, scoped clean walk)` — and both paths declare completion through the *single existing* `verificationPassComplete`-gated site, adding **no new completion site and no new marker set-point**. Designed under the `forge` protocol; implementation + review continue under `forge`, with byte-parity validation. Determinism budget respected (no new per-block hot-path cost).
- **III. Test Discipline & Tiered Coverage** — **PASS (planned).** Behavioral change ships with deterministic TestKit tests: scope-capture completeness (`heal N → set == those N pairs`), each fallback clause F1-F6, and scoped-vs-full-root completion parity. No `Thread.sleep`. Statement coverage held ≥ 70%.
- **IV. Idiomatic, Formatted Scala 3** — **PASS (planned).** All code passes `scalafmt` (3.8.3, 120 cols, Scala 3 dialect) and `scalafix`. The BFS multi-seed change is an additive overload; the single-seed signature stays a byte-identical thin wrapper.
- **V. Workflow / CI gates** — **PASS (planned).** `sbt pp` before PR; CI must be green (`scalafmtCheckAll`, `compile-all`, Tier-1/Tier-2 tests). Consensus-adjacent → `forge` review required before merge.

**Result: all gates pass. No violations → Complexity Tracking not required.**

## Project Structure

### Documentation (this feature)

```text
specs/003-scoped-heal-verification/
├── plan.md              # This file
├── research.md          # Phase 0 — 6 design decisions + consensus-safety + restart/pivot safety
├── data-model.md        # Phase 1 — Healed-path, Healed-paths set, Full-coverage precondition, Verification scope
├── quickstart.md        # Phase 1 — validation/run guide
├── contracts/
│   └── internal-interfaces.md   # Phase 1 — coordinator messages, BFS multi-seed, config, completion-gate predicate
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT this command)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
├── actors/
│   ├── TrieNodeHealingCoordinator.scala   # capture healed-paths at heal site; rebuildFrontierBFS multi-seed
│   │                                       # overload; startScopedVerification; HealingResumeDispatch interplay
│   └── Messages.scala                      # (if a new internal message/field is needed)
└── SNAPSyncController.scala                # HealingCheckCompletion useScoped predicate; SNAPSyncConfig fields

src/main/resources/conf/base/sync.conf      # scoped-heal-verification, scoped-heal-max-paths keys

src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/
└── ...                                     # scope-capture, fallback-clause, parity tests (deterministic TestKit)
```

**Structure Decision**: Single-project (the `node` main module). All changes are internal to the SNAP sync orchestration layer — the `TrieNodeHealingCoordinator` actor, its `Messages`, the `SNAPSyncController` completion gate + `SNAPSyncConfig`, and `sync.conf` — plus deterministic tests and `app_`-prefixed metrics. No new module, no external interface.

## Complexity Tracking

> No Constitution Check violations — section intentionally empty.
