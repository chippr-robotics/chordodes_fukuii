# Implementation Plan: Decoupled Heal Serve-Root

**Branch**: `004-decoupled-heal-serve-root` (work branched from `staging`) | **Date**: 2026-06-17 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-decoupled-heal-serve-root/spec.md`

## Summary

Split the single root the post-SNAP heal pins everything to (`TrieNodeHealingCoordinator.stateRoot`) into two roles: a **fixed walk root** that the local completeness BFS uses for the whole walk (a pure local read, needs no peers, so it runs to completion regardless of serve windows), and an **advancing serve root** used only to fetch the missing nodes from peers (refreshed to whatever is currently within the ~128-block snap serve window). Because trie nodes are content-addressed and every fetched node is already verified `keccak256(node) == requested hash` before storage (`TrieNodeHealingCoordinator.scala:1137`), fetching via a newer servable root safely supplies the deep nodes the walk found under the old walk root, while a path that resolves to a *different* node simply fails the hash check and is retried. The completeness decision is untouched — it remains the walk finding zero missing against the fixed walk root through the single existing `verificationPassComplete`/`markComplete` chokepoint — so the outcome is byte-for-byte identical to today (FR-007). Gated by `decoupled-heal-serve-root` (default `true`), composing with hold-pivot (`#1357`) as its durable generalization. Requires a build + one redeploy; the next walk then runs to completion.

## Technical Context

**Language/Version**: Scala 3.3.7 LTS on JDK 25

**Primary Dependencies**: Apache Pekko (actors) — `TrieNodeHealingCoordinator`, `SNAPSyncController`, `SyncController`; RocksDB (content-addressed node store CF `n` via `multiGetNodes`; completeness marker CF `g`); the SNAP wire protocol (`GetTrieNodes`/`TrieNodes`, path-addressed — `SNAP.scala:553`); the existing `RecentRoot`/`PivotHeaderBootstrap` plumbing in `SyncController` (`:1482-1565`) reused to source the advancing serve root.

**Storage**: No new persisted schema. The new `serveRoot` var and FR-006 attempt counters are **in-memory only** (a restart falls back to coupled behavior / a full re-walk — safe). Reuses the content-addressed node store and the CF `g` completeness marker unchanged.

**Testing**: ScalaTest + Pekko TestKit (deterministic, no `Thread.sleep` per Principle III). Unit tests T-1…T-7 (see contracts): decoupled fetch root, side-effect-free serve-root refresh, content-mismatch drop, unservable-node FR-006 behavior, decoupled-vs-coupled completion parity, off-fallback, observability. `forge`-aligned focus on the content-hash guardrail (FR-004) and byte-parity (FR-007).

**Target Platform**: Linux server (barad-dûr ETC-mainnet node, 16 GB host); chain ETC/Mordor (chain-ID 61, PoW, ECIP-1017). Sync-layer only; not applicable to ETH/Sepolia consensus paths.

**Project Type**: Single project — the `node` (main) module; an internal sync-orchestration change, no external API surface.

**Performance Goals**: Walk completion is no longer bounded by the ~28-min serve window (SC-005); serve-root refresh is O(1) (FR-011); no new per-block or per-node hot-path cost (the fetch-root selection is a single conditional; the content-hash check already runs today).

**Constraints**: Byte-for-byte completion parity with the coupled path (FR-007, the binding consensus invariant); content-hash integrity preserved on every fetched node (FR-004 — the load-bearing guardrail, MUST NOT be weakened); the walk root MUST stay fixed across serve-window aging (FR-001); no false completion and no silent infinite retry (FR-006); bounded memory (FR-011); deterministic.

**Scale/Scope**: ETC mainnet state trie ~90M+ nodes; observed gap sets tens-to-tens-of-thousands (deep L8/L9 storage); change confined to `TrieNodeHealingCoordinator`, `SNAPSyncController`, `SyncController` (serve-root push), `Messages`/`SnapSyncConfig`, and `sync.conf` — plus deterministic tests and `app_`-prefixed metrics.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

- **I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)** — **PASS.** Consensus-*adjacent* (sync orchestration), not consensus-*critical*: `forge` confirmed zero files under `vm/consensus/crypto/domain/ledger` are touched; ETC PoW / block-number fork dispatch / ECIP-1017 are unaffected; the change is chain-agnostic. The one load-bearing safety property is the existing content-hash check (`keccak256(node) == requested hash`, `:1137`) — preserved verbatim, it makes a path-addressed fetch from a *different* serve root **safe** (a wrong-content node fails the check and is dropped, never stored). Completeness is still decided solely by the walk against the fixed walk root through the single existing `verificationPassComplete`/`markComplete` site — **no new completion site, no new marker**. FR-007 byte-parity holds by construction (same final stored, content-verified nodes ⇒ same walk ⇒ same outcome). Designed under `forge` (impact analysis complete); implementation + review continue under `forge` with byte-parity validation. Determinism budget respected (no new per-block cost).
- **II. Spec-Driven Development** — **PASS.** specify → plan → tasks → implement; artifacts under `specs/004-decoupled-heal-serve-root/`. A consensus-adjacent ADR will be recorded under `docs/adr/consensus/` (sibling to spec 002's CON-009) capturing the walk-root/serve-root split and the content-hash-guardrail rationale.
- **III. Test Discipline & Tiered Coverage** — **PASS (planned).** Deterministic TestKit tests T-1…T-7; no `Thread.sleep`; statement coverage held ≥ 70%. The content-mismatch (T-3) and parity (T-5) tests are the consensus-aligned core.
- **IV. Idiomatic, Formatted Scala 3** — **PASS (planned).** `scalafmt` (3.8.3, 120 cols, Scala 3) + `scalafix`. The change is additive: a new `serveRoot` var, a new `HealingServeRootRefresh` message + handler, a one-line conditional at the fetch site, a bounded attempt-counter map, config plumbing, and metrics. No edits to the walk seeds, `isComplete`, `verificationPassComplete`, `markComplete`, or the `HealingPivotRefreshed` body.
- **V. Quality Gates** — **PASS (planned).** `sbt pp` before PR; CI green (`scalafmtCheckAll`, `compile-all`, Tier-1/Tier-2); `forge` review + ethereum/tests sanity before merge.
- **VI. Security & Operational Safety** — **PASS.** No key/crypto change; no RPC surface change (the new message is an internal actor message). The content-hash check is the integrity guard and is preserved.
- **VII. Transparent Versioning & Decision Records** — **PASS (planned).** PATCH version bump; conventional `feat(snap):` commit; consensus-adjacent ADR linked from the plan.

**Result: all gates pass. No violations → Complexity Tracking not required.** Two *design decisions* (not violations) are flagged for user sign-off before `/speckit-tasks`: research **D4** (serve-root selection policy — newest- vs oldest-servable) and **D5** (FR-006 surfacing is observation-only; force-complete/stagnation-abandon neutralized under decoupling).

## Project Structure

### Documentation (this feature)

```text
specs/004-decoupled-heal-serve-root/
├── plan.md              # This file
├── research.md          # Phase 0 — current-state findings (F-A..F-F) + decisions D1..D6 + flagged decisions
├── data-model.md        # Phase 1 — Walk root, Serve root, Missing node, attempt counter, serve window, config
├── quickstart.md        # Phase 1 — validation/run guide (unit T-1..T-7 + live)
├── contracts/
│   └── internal-interfaces.md   # Phase 1 — serveRoot var, HealingServeRootRefresh, fetch-site, content guardrail, completion gate, config, metrics, test contracts
├── checklists/
│   └── requirements.md  # Spec quality checklist (from /speckit-specify)
└── tasks.md             # Phase 2 — created by /speckit-tasks (NOT this command)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
├── actors/
│   ├── TrieNodeHealingCoordinator.scala   # NEW serveRoot var; HealingServeRootRefresh handler (serve-root only);
│   │                                       # fetch-site rootHash conditional (:1080); FR-006 attempt counter + surface;
│   │                                       # content-hash check (:1137) PRESERVED; walk seeds / completion gate UNTOUCHED
│   └── Messages.scala                      # NEW HealingServeRootRefresh(newServeRoot)
├── SNAPSyncController.scala                # serve-root acquisition (RecentRoot bootstrap reuse) → push HealingServeRootRefresh;
│                                           # SnapSyncConfig field; props plumbing (:3364,:3429)
src/main/scala/com/chipprbots/ethereum/blockchain/sync/
└── SyncController.scala                    # RecentRoot requester slot/tag so healing ≠ storage-recovery contention (:1482-1565)

src/main/resources/conf/base/sync.conf      # decoupled-heal-serve-root, decoupled-heal-max-attempts-no-refresh keys

src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/
└── ...                                     # T-1..T-7 deterministic TestKit specs

docs/adr/consensus/                          # ADR for the walk-root/serve-root split (consensus-adjacent)
```

**Structure Decision**: Single-project (the `node` main module). All changes are internal to the SNAP sync orchestration layer — the `TrieNodeHealingCoordinator` actor, its `Messages`, the `SNAPSyncController` serve-root push + `SnapSyncConfig`, the `SyncController` requester slot, and `sync.conf` — plus deterministic tests and `app_`-prefixed metrics. No new module, no external interface, no wire-protocol change.

## Complexity Tracking

> No Constitution Check violations — section intentionally empty. (The two flagged items in the Constitution Check are design-decision sign-offs, not complexity deviations.)
