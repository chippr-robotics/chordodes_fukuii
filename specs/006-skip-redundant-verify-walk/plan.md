# Implementation Plan: Skip the Redundant Second Verification Walk on a Clean Post-SNAP Heal

**Branch**: `006-skip-redundant-verify-walk` (work branches from `staging`) | **Date**: 2026-06-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-skip-redundant-verify-walk/spec.md`

## Summary

On a restart with a persisted healing frontier but no completeness marker, the post-SNAP heal runs **two** full-trie walks before `StateHealingComplete`: a frontier **rebuild** walk (`FrontierRebuildComplete` callback, which only writes the completeness marker) and then a **verification** walk force-started by the dead-pulse watchdog because the rebuild left `verificationPassComplete=false`. The two are the *same* `rebuildFrontierBFS` traversal; on ETC mainnet that doubles a ~16-20h walk to ~30-40h for no completeness benefit. `forge` recovered the design intent (the verification walk guards `discoverMissingChildren`'s shallow inline discovery — PR #1321 BUG-1) and confirmed the rebuild walk **already fully recurses** into account-leaf storage roots, so the verification is genuinely redundant when the deciding walk was a clean full rebuild. The fix: in the `FrontierRebuildComplete` handler, when the rebuild was **genuinely clean** (`missingEmitted==0 && totalNodesHealed==0 && isComplete && !flushing && walkRoot==stateRoot`), set `verificationPassComplete=true` and `self ! HealingCheckCompletion`, declaring completion after one walk through the **single existing chokepoint** (byte-parity). This requires making the walk's outcome observable — `FrontierRebuildComplete` becomes a case class carrying `missingEmitted` (the walk's `frontierCount`) and `walkRoot` (for the explicit stale-root guard). Unconditional (no config); the dead-pulse watchdog stays as the built-in fallback. Consensus-adjacent; designed and adversarially verified under `forge` (no false-completion path found).

## Technical Context

**Language/Version**: Scala 3.3.8 LTS on JDK 25

**Primary Dependencies**: Apache Pekko (actors) — `TrieNodeHealingCoordinator` (the only file changed); RocksDB — `HealingFrontierStorage` (CF `'g'` completeness marker, reused unchanged).

**Storage**: No change. No new column family, no new record, no migration. The existing CF-`'g'` completeness marker is written via the same `markComplete()` it is today.

**Testing**: ScalaTest + Pekko TestKit (deterministic, no `Thread.sleep` per Principle III). Unit tests T-1…T-5 (see contracts): one-walk completion, never-false-complete-on-missing, stale-root safety, byte-parity, watchdog self-suppression. The never-false-complete (T-2), stale-root (T-3), and byte-parity (T-4) tests are the consensus-aligned core.

**Target Platform**: Linux server (barad-dûr ETC-mainnet node); chain ETC/Mordor (chain-ID 61, PoW, ECIP-1017). Sync-layer only; not applicable to ETH/Sepolia consensus paths.

**Project Type**: Single project — the `node` (main) module; an internal sync-orchestration change, no external API surface.

**Performance Goals**: On a restarted node whose trie is complete, reach `StateHealingComplete` after **one** full-trie walk instead of two — roughly **halving** post-SNAP completion (~16-20h instead of ~30-40h on ETC mainnet, SC-001). No new per-walk cost (the guard is five cheap local reads at one message handler).

**Constraints**: Byte-for-byte completion parity with the two-walk outcome (FR-002, the binding consensus invariant); never declare completion while a node is missing (FR-003/FR-008); the change is redeploy-gated and cannot help an in-flight walk; deterministic tests.

**Scale/Scope**: The change is confined to **one file** (`TrieNodeHealingCoordinator.scala`): one message-shape change (case object → case class) + its single sender, one guarded block in the `FrontierRebuildComplete` handler, plus deterministic tests. No config, no metrics, no storage, no wire change.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

- **I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)** — **PASS.** Consensus-*adjacent* (it changes *when* the completeness proof is trusted), not consensus-*critical*: `forge` confirmed it touches no EVM/gas/RLP/block-validation/reward/Ethash/ECIP-1017 code, and the verification traversal does no state writes. The single load-bearing property is **never-false-complete**, reduced by the D2 guard to five conjuncts, each excluding a specific failure mode (missing found, healed, outstanding work, async flush, **stale root**), with the dead-pulse watchdog as a fail-safe fallback (early completion can only *advance* completion when provably sound, never *prevent* it). Completion is declared through the *single existing* `HealingCheckCompletion`/`markComplete`/`StateHealingComplete` chokepoint — **no new completion site, no new marker set-point** — so byte-parity (FR-002) holds by construction. `forge`'s adversarial pass found **no false-completion path** (five attacks refuted: bounded-visited eviction, heal-during-walk, stale-root, storage-under-leaf, marker-trust). Designed + reviewed under `forge`; implementation + review continue under `forge` with byte-parity + never-false-complete validation.
- **II. Spec-Driven Development** — **PASS.** specify → plan → tasks → implement; artifacts under `specs/006-skip-redundant-verify-walk/`. A consensus-adjacent ADR will be recorded under `docs/adr/consensus/` (next id CON-012) capturing the clean-rebuild early-completion guard + the recovered Chesterton's-Fence rationale.
- **III. Test Discipline & Tiered Coverage** — **PASS (planned).** Deterministic TestKit tests T-1…T-5; no `Thread.sleep` (the T-1 `expectMsg(5.seconds)` bound is the regression detector, not a sleep); coverage ≥ 70%. The never-false-complete (T-2), stale-root (T-3), and parity (T-4) tests are the consensus core.
- **IV. Idiomatic, Formatted Scala 3** — **PASS (planned).** `scalafmt` (3.8.3, 120 cols) + `scalafix`. The change is small and additive: a case object → case class, its one sender, and one guarded `if` block.
- **V. Quality Gates** — **PASS (planned).** `sbt pp` before PR; CI green (`scalafmtCheckAll`, `compile-all`, Tier-1/Tier-2); `forge` review + a `HealingFrontierResume`/import sanity (no `MissingRootNode`) before merge.
- **VI. Security & Operational Safety** — **PASS.** No key/crypto/RPC/wire surface change; no config surface. Internal actor message only.
- **VII. Transparent Versioning & Decision Records** — **PASS (planned).** PATCH bump; `fix(snap):` (or `perf(snap):`) conventional commit; consensus-adjacent ADR linked from the plan.

**Result: all gates pass. No violations → Complexity Tracking not required.** Both design decisions were locked by the user at specify time (research D4 = explicit `walkRoot` guard; D5 = conservative `totalNodesHealed==0`, unconditional). Nothing flagged for sign-off.

## Project Structure

### Documentation (this feature)

```text
specs/006-skip-redundant-verify-walk/
├── plan.md              # This file
├── research.md          # Phase 0 — findings F-A..F-E + decisions D1..D5 + adversarial verdict
├── data-model.md        # Phase 1 — enriched message, clean-rebuild condition, reused marker
├── contracts/
│   └── internal-interfaces.md   # Phase 1 — C1..C6 (message, guard, chokepoint, watchdog, fence, no-config) + tests T-1..T-5
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit-specify) — all PASS
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT this command)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/
└── TrieNodeHealingCoordinator.scala   # FrontierRebuildComplete case object→class (carry missingEmitted + walkRoot);
                                        #   thread frontierCount (~:1563) + walkRoot via onComplete (~:614);
                                        #   guarded early completion in the handler (~:638-645) → self ! HealingCheckCompletion.
                                        #   HealingCheckCompletion (:889-912) and the dead-pulse watchdog (:1012-1027) UNTOUCHED.

src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/...
└── ...                                 # T-1..T-5 deterministic specs (mirror HealingFrontierResume / scoped-verification harness)

docs/adr/consensus/
└── CON-012-...                         # ADR for the clean-rebuild early-completion guard + Chesterton's-Fence rationale
```

**Structure Decision**: Single-project (the `node` main module). The change is internal to one actor — `TrieNodeHealingCoordinator` — plus deterministic tests and an ADR. No new module, no external interface, no config, no storage, no wire-protocol change. This is deliberately the smallest surface that eliminates the redundant walk.

## Complexity Tracking

> No Constitution Check violations — section intentionally empty. Both flagged decisions were user-locked at specify time (not complexity deviations).
