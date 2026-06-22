# Implementation Plan: Subtree-Complete Heal Verification

**Branch**: `005-subtree-complete-verification` (work branches from `staging`) | **Date**: 2026-06-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-subtree-complete-verification/spec.md`

## Summary

Make the post-SNAP heal completeness verification **O(missing-frontier)** instead of **O(whole trie)** by giving it a **descend-and-stop** pruning oracle: a durable, content-addressed **per-subtree-complete record** (in the existing `HealingFrontierStorage` CF `'g'`) lets the verification treat any present, recorded-complete node as a verified leaf and skip its subtree — matching geth/Nethermind/Besu and collapsing the ~16-20h full re-walk to minutes. The records are **root-independent and eternal** (nodes are content-addressed, so a node's children are fixed by its content — once a subtree is proven present the fact never changes and is never cleared), **additive/monotone**, and **seeded during the SNAP/heal write path** (per StackTrie fragment commit + per heal subtree closure) so the **first** verification on a fresh node is already cheap (FR-003). Crash-safety holds because a record is written only **after** its subtree's bytes are durably committed (mirror `unpersistFrontier`'s post-`persist` rule) and the terminal marker is fsynced — so any crash leaves the record absent and the node is safely **descended**, never falsely pruned. Completion is declared through the *same single* `verificationPassComplete`/`markComplete` chokepoint, so the outcome (decision + marker bytes + state root, and the walk does no state writes) is byte-for-byte identical to today (FR-005). Gated by `pruned-heal-verification` (default `true`), Hash-scheme only, with full-trie fallback. Consensus-adjacent; designed under `forge`.

## Technical Context

**Language/Version**: Scala 3.3.8 LTS on JDK 25

**Primary Dependencies**: Apache Pekko (actors) — `TrieNodeHealingCoordinator`, `AccountRangeCoordinator`, `SNAPSyncController`; RocksDB — `HealingFrontierStorage` (CF `'g'`, the subtree-complete records + completeness marker) and the content-addressed node store (CF `'n'`, via `multiGetNodes`); `RocksDbDataSource` (`update` WAL-ordered / `updateSync` fsync); `StackTrie`/`SnapHashTrie` (SNAP node construction).

**Storage**: One additive record class in the existing CF `'g'` (`0x01 ++ hash → 0x01`), keyed on the bare keccak hash. **No new column family, no migration.** Reuses the node store (CF `'n'`) and the completeness marker unchanged (terminal marker upgraded to fsync).

**Testing**: ScalaTest + Pekko TestKit (deterministic, no `Thread.sleep` per Principle III). Unit tests T-1…T-8 (see contracts): prune-on-record, never-false-prune, crash-safety/abandoned-fragment, pruned-vs-full parity, fresh-node seeding, fallback, guardrail+metrics. `forge`-aligned focus on the never-false-prune invariant (FR-002/FR-006) and byte-parity (FR-005).

**Target Platform**: Linux server (barad-dûr ETC-mainnet node, 16 GB host); chain ETC/Mordor (chain-ID 61, PoW, ECIP-1017). Sync-layer only; not applicable to ETH/Sepolia consensus paths.

**Project Type**: Single project — the `node` (main) module; an internal sync-orchestration + storage change, no external API surface.

**Performance Goals**: Fresh-node completeness verification in **under a few minutes** (≥99% reduction vs the ~16-20h full walk — SC-001/SC-005); verification cost scales with the missing-node count, not the ~90M trie size (SC-006). Per-node pruning check is a single CF `'g'` key probe (no RLP-decode, no keccak), removing the read+decode+keccak cost the full walk pays per present node.

**Constraints**: Byte-for-byte completion parity with the full-trie verification (FR-005, the binding consensus invariant); crash-safe never-false-prune invariant (FR-006); the spec-004 fetched-node content-hash guardrail preserved (FR-008); deterministic; Hash-scheme only (Path ⇒ full-walk fallback).

**Scale/Scope**: ETC mainnet state trie ~90M+ nodes, ~18 levels deep; the change is confined to `HealingFrontierStorage`, `TrieNodeHealingCoordinator` (verification pruning + heal-subtree seeding), `AccountRangeCoordinator` (SNAP-fragment seeding), `SNAPSyncController`/`SnapSyncConfig`, and `sync.conf` — plus deterministic tests and `app_`-prefixed metrics.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

- **I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)** — **PASS.** Consensus-*adjacent* (it changes the *completeness proof* the node trusts before executing blocks), not consensus-*critical*: `forge` confirmed it touches no EVM/gas/RLP/block-validation/reward/Ethash/ECIP-1017 code; the verification is a **pure local read that performs no state writes** (`TrieNodeHealingCoordinator.scala:1526-1527`), so it never recomputes or mutates the state root. The single load-bearing safety property is the **never-false-prune invariant**: a present node is pruned only when its subtree is *durably proven complete* (a record written strictly after the subtree's bytes are committed), with a descend-on-missing-record fallback — so byte-parity (FR-005) reduces to "emit the same set of missing nodes as the full walk," which holds by the invariant. Completion is declared through the *single existing* `verificationPassComplete`/`markComplete` chokepoint — **no new completion site, no new marker set-point**. The spec-004 fetched-node content-hash guardrail is preserved verbatim (FR-008). Designed under `forge`; implementation + review continue under `forge` with byte-parity + crash-safety validation. Determinism budget respected (no new per-block cost; the per-present-node read+decode+keccak cost is *removed*).
- **II. Spec-Driven Development** — **PASS.** specify → plan → tasks → implement; artifacts under `specs/005-subtree-complete-verification/`. A consensus-adjacent ADR will be recorded under `docs/adr/consensus/` (sibling to CON-009/CON-010) capturing the root-independent subtree-completeness record + the crash-safe record-after-persist ordering as the load-bearing safety mechanism.
- **III. Test Discipline & Tiered Coverage** — **PASS (planned).** Deterministic TestKit tests T-1…T-8; no `Thread.sleep`; coverage ≥ 70%. The never-false-prune (T-2), crash-safety (T-3/T-8), and parity (T-4) tests are the consensus-aligned core.
- **IV. Idiomatic, Formatted Scala 3** — **PASS (planned).** `scalafmt` (3.8.3, 120 cols) + `scalafix`. The change is additive: a new record API on `HealingFrontierStorage`, a pruning conditional + seeding calls in the coordinator, a SNAP-fragment seeding call, config plumbing, metrics, and a one-line `update`→`updateSync` upgrade on the terminal marker.
- **V. Quality Gates** — **PASS (planned).** `sbt pp` before PR; CI green (`scalafmtCheckAll`, `compile-all`, Tier-1/Tier-2); `forge` review + an ethereum/tests/import sanity (no `MissingRootNode`) before merge.
- **VI. Security & Operational Safety** — **PASS.** No key/crypto/RPC surface change; the new records are internal storage. The content-hash guardrail (the integrity guard for fetched nodes) is preserved.
- **VII. Transparent Versioning & Decision Records** — **PASS (planned).** PATCH bump; `feat(snap):` conventional commit; consensus-adjacent ADR linked from the plan.

**Result: all gates pass. No violations → Complexity Tracking not required.** Two *design decisions* (not violations) were confirmed by the user on 2026-06-19 and are now LOCKED (see research.md): **D2 = full seeding** (records seeded during SNAP fragment commits + heal subtree closures, so the *first* verification is O(missing) per FR-003) and **D6 = default-on** (`pruned-heal-verification = true`, with the FR-007 full-walk fallback). `/speckit-tasks` should treat both as decided.

## Project Structure

### Documentation (this feature)

```text
specs/005-subtree-complete-verification/
├── plan.md              # This file
├── research.md          # Phase 0 — findings F-A..F-F + decisions D1..D6 + flagged decisions
├── data-model.md        # Phase 1 — subtree-complete record, present-and-complete node, verification frontier, config
├── quickstart.md        # Phase 1 — validation/run guide (unit T-1..T-8 + live)
├── contracts/
│   └── internal-interfaces.md   # Phase 1 — record API, pruning oracle, seeding, crash ordering, chokepoint, config, metrics, tests
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT this command)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/db/storage/
└── HealingFrontierStorage.scala            # NEW markSubtreeComplete/isSubtreeComplete/multiIsSubtreeComplete
                                            #   (0x01++hash key, CF 'g'); extend loadAll filter

src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
├── actors/
│   ├── TrieNodeHealingCoordinator.scala    # rebuildFrontierBFS per-child pruning oracle (~:1602); markSubtreeComplete
│   │                                       #   on 0-missing closure; heal-subtree seeding (discoverMissingChildren ~:1949);
│   │                                       #   terminal markComplete -> updateSync; config gate. Chokepoint UNTOUCHED.
│   └── AccountRangeCoordinator.scala        # seed markSubtreeComplete(fragmentRoot) on durable StackTrie commit (~:1573);
│                                            #   skip abandoned/reset fragments
└── SNAPSyncController.scala                 # SnapSyncConfig field; props plumbing

src/main/resources/conf/base/sync.conf       # pruned-heal-verification key

src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/
└── ...                                      # T-1..T-8 deterministic specs

docs/adr/consensus/                           # ADR for the subtree-completeness record + crash-safe ordering
```

**Structure Decision**: Single-project (the `node` main module). All changes are internal to the SNAP sync + heal storage layer — `HealingFrontierStorage` (the record), `TrieNodeHealingCoordinator` (pruning + heal seeding + fsync upgrade), `AccountRangeCoordinator` (SNAP-fragment seeding), `SNAPSyncController`/`SnapSyncConfig`, and `sync.conf` — plus deterministic tests and `app_`-prefixed metrics. No new module, no external interface, no wire-protocol change, no new column family.

## Complexity Tracking

> No Constitution Check violations — section intentionally empty. (The two flagged items in the Constitution Check are design-decision sign-offs, not complexity deviations.)
