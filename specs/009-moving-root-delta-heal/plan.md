# Implementation Plan: Moving-Root Delta Heal for SNAP Completion

**Branch**: `009-moving-root-delta-heal` | **Date**: 2026-06-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/009-moving-root-delta-heal/spec.md`

## Summary

Make a fresh ETC SNAP-from-scratch COMPLETE against snapshot-root-only peers (core-geth) by adopting the go-ethereum/besu healing architecture (established this session via a source comparison + a `GetTrieNodes`-retention probe that came back GREEN). Three coupled changes:
1. **Build the trie during download** (`deferred-merkleization=false`) so trie nodes are committed as ranges arrive — the heal has real local nodes to work from.
2. **Single moving heal root** — heal against ONE root used for both the completeness target and node fetches, re-pegged to a recent in-window root (`head−64`) on every stale-move. This replaces spec-004's wrong-axis split (a *fixed* completeness root + an *advancing* serve root, whose differing node hashes made the content-hash gate reject everything → `healed=0`).
3. **Delta-only top-down discovery** — fetch a node → decode → request only its hash-referenced children absent on disk; retire the O(total) `rebuildFrontierBFS` and the second verification walk. Completion = no node referenced from the current root is missing + no outstanding requests.

Because `GetTrieNodes` serves recent/committed roots with broader retention than `GetAccountRange` (the snapshot-layer limit that defeated spec-008's freeze-and-re-fetch), and content-addressed nodes are ~99.9% shared across nearby roots, the delta is small (O(10K–100K) nodes) and `pending` decreases **monotonically** across short serve windows — so the heal converges even though no single root stays served for the whole job. Supersedes spec-004's decoupling; retires spec-008's `beginFinalizeRefetch` (PR #1372 US3).

**Phase 0 (forge) verdict: GREEN with one named constraint** (`research.md`). It corrected two spec premises, both now applied: (1) **FR-001** — no local *coherent* root is needed; the heal walks the **served** `head−64` root and **fetches the root node itself if absent** (a root node is fetchable: `keccak==S`), using the local fragment mosaic only as a content-addressed cache. The fix is to **seed-from-absent-root at heal start** — copying what the re-peg path (`HealingPivotRefreshed:943-947`) already does; the live wall is that `StartTrieNodeHealing:632` hands off instead. (2) **FR-003/SC-003** — a final completeness descent is *required* for soundness (the download pre-populates fragments, so "present on disk" ≠ "subtree complete"), but **pruned to O(delta)** (spec-005 oracle); only the O(*total*) *first* BFS is retired. The two hardest pieces already exist (the re-peg path + `discoverMissingChildren`), so the change is largely: seed-absent-root, collapse the fetch root to `stateRoot` (the one-line spec-004 fix), route the staleness trigger to move the walk root, and keep the pruned descent.

## Technical Context

**Language/Version**: Scala 3.3.8 LTS, JDK 21 (CI also JDK 25)

**Primary Dependencies**: Apache Pekko (classic actors) for the SNAP heal coordinator + worker pool; RocksDB content-addressed node storage (trie nodes keyed by keccak-256); `TrieNodeHealingCoordinator`, `SNAPSyncController`; the inline merkleization path (`StackTrie`/`SnapHashTrie`, `AccountRangeCoordinator.finalizeTrie`); the recent-root request/response machinery (`RequestRecentRoot`/`RecentRoot`, from PR #1313); `MerklePatriciaTrie`; `GetTrieNodes` wire (`SNAP.scala`, `SnapServer`); `crypto` (keccak-256)

**Storage**: RocksDB. Trie nodes are content-addressed (keccak → RLP), so nodes verified+persisted under one root remain valid and reusable under any nearby root — the property that makes re-pegging cheap and progress monotonic

**Testing**: ScalaTest unit (`testEssential`); integration (`IntegrationTest`/`testStandard`); consensus compliance (`testCrypto`, `testMPT`, `testEthereum`); A/B replay (feature off vs on → identical finalized root); deterministic only (no `Thread.sleep`)

**Target Platform**: Linux JVM server. Reference/validation host is CPU-constrained (i5-4430, 4C/4T, 16 GB) — the non-deferred inline build was historically the CPU/GC bottleneck here, so adequate heap (`-Xmx ≥ 6g`) and an A/B throughput measurement are required

**Project Type**: Multi-network EVM client (single SBT project; root `main` module). This feature touches `main` (SNAP heal + finalize) only

**Performance Goals**: Parity is the hard gate (finalized root byte-equal to canonical). The heal delta is O(10K–100K) nodes (geth's measure on a complete Mordor download) at ~500–1000 nodes/s ≈ 10s–3min — and need not finish in one serve window (monotonic across re-pegs). The non-deferred inline-build CPU/GC cost is report-and-record (relate to spec-007 hot-path work)

**Constraints**: (1) byte-for-byte deterministic ETC state root — NON-NEGOTIABLE; (2) the content-hash store gate (`keccak(node)==requested hash`) stays byte-untouched — it now *matches* because walk root == fetch root; (3) the completion gate MUST be sound (no completion with gaps) under a moving root; (4) the finalized root MUST equal the canonical header root; (5) config-gated, default-on for ETC SNAP, togglable for A/B + rollback; (6) no ETH/Sepolia change; deferred path keeps a safe on-demand fallback

**Scale/Scope**: Mordor ~2.71M accounts / ~19M trie nodes (validation target). ETC mainnet (~86M accounts) must not regress; the moving-root delta heal is expected to scale (delta-bounded, not total-bounded) but is characterized, not necessarily proven at mainnet scale in v1. Scope = the SNAP heal strategy (`TrieNodeHealingCoordinator`) + heal-start/finalize wiring (`SNAPSyncController`) + the config + retiring the superseded paths

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Consensus Determinism Is Sacred (NON-NEGOTIABLE)** — This feature determines when the SNAP state is complete and finalizes a state root: consensus-critical on the ETC domain. Compliance: (a) designed + reviewed by `forge` BEFORE implementation (Phase 0 is a forge task; implementation follows the forge protocol); (b) the finalized root MUST be byte-for-byte the canonical header root; (c) the content-hash store gate is byte-untouched (and now matches by construction); (d) the completion gate's soundness (FR-005) is a forge-proven invariant before merge; (e) parity via `testCrypto`+`testMPT`+`testEthereum` + an A/B replay (feature off vs on → identical finalized root == canonical Mordor header root, cross-checked vs core-geth); (f) ETC-only — block-number/`forBlock` PoW path; no ETH path touched. **Status: PASS, contingent on forge Phase-0 GREEN + the completion-gate soundness proof + forge implementation sign-off.**
- **III. Test Discipline & Tiered Coverage** — Deterministic tests only; ≥70% coverage on new code; unit (delta discovery, re-peg, completion gate) + integration (heal-to-completion under a simulated moving root) + consensus suites for parity; A/B replay is the headline parity test. **Status: PASS (tests enumerated in Phase 1 + tasks).**
- **Scala 3 LTS + scalafmt/scalafix; `sbt pp` before PR; CI green to merge.** **Status: PASS.**
- **Spec-Driven flow + constitution binding** — follows `/speckit-specify → plan → tasks → implement`; the feasibility gate (forge Phase 0) is honored (RED → reshape, not push through). **Status: PASS.**

No constitution violations requiring Complexity Tracking at plan time — the feature *removes* machinery (the O(total) BFS, the second verification walk, the walk/serve split, the freeze-re-fetch) and adds a re-peg trigger + delta-driven scheduler, reusing the existing recent-root machinery. Re-evaluate after Phase 1 once forge fixes the design.

## Project Structure

### Documentation (this feature)

```text
specs/009-moving-root-delta-heal/
├── plan.md              # This file
├── research.md          # Phase 0 — forge: deferred-false-root + completion-gate soundness + edit plan
├── data-model.md        # Phase 1 — heal-root / delta / verified-node / completion-gate entities + state machine
├── quickstart.md        # Phase 1 — fresh-Mordor A/B validation runbook
├── contracts/           # Phase 1 — internal contracts (delta discovery, re-peg, completion gate)
├── checklists/
│   └── requirements.md  # spec quality checklist (done)
└── tasks.md             # Phase 2 — /speckit-tasks (NOT created here)
```

### Source Code (repository root)

```text
src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/
├── actors/
│   └── TrieNodeHealingCoordinator.scala   # single healRoot (replace spec-004 walk/serve split);
│                                          #   delta-only discoverMissingChildren as sole mechanism;
│                                          #   retire rebuildFrontierBFS + the verification BFS;
│                                          #   re-peg on stale-move; sound completion gate
└── SNAPSyncController.scala               # heal start on the built root; deferred-skip → last-resort only;
                                           #   retire beginFinalizeRefetch (spec-008 US3); finalize on canonical root

src/main/resources/conf/base/sync.conf     # config gate; deferred-merkleization=false authoritative for ETC SNAP
ops/barad-dur/fukuii-conf-1/base.conf       # flip deferred-merkleization=true → false for ETC runtime (uncommitted ops)

src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/
                                           # delta-discovery, re-peg-retains-progress, completion-gate-soundness,
                                           #   heal-to-completion-under-moving-root integration specs
```

**Structure Decision**: Single project, `main` module. The change is concentrated in `TrieNodeHealingCoordinator` (the heal strategy) and `SNAPSyncController` (heal start / finalize / retiring the superseded path), plus the config. It is net *subtractive* on machinery (removes two O(total) walks, the walk/serve split, and the freeze-re-fetch) while adding a re-peg trigger and a delta-driven completion gate that reuse existing recent-root plumbing.

## Complexity Tracking

> Re-evaluate after Phase 1. The feature reduces moving parts; the one genuinely new invariant is the moving-root completion gate (FR-005), whose soundness forge must prove in Phase 0 — recorded here if it requires non-obvious machinery.

| Added/changed machinery | Why Needed | Simpler Alternative Rejected Because |
|-------------------------|------------|-------------------------------------|
| Single moving `healRoot` + re-peg trigger (replaces spec-004's fixed-walk/advancing-serve split) | The fixed-walk/advancing-serve split makes fetched nodes' hashes mismatch the completeness root → content gate drops all → healed=0; geth/besu use one moving root | Keeping the spec-004 split (status quo) cannot heal against core-geth; freezing one root (spec-008) ages out of the serve window |
| Moving-root completion gate (`pending==0` against the latest healRoot, all nodes content-verified) | Re-pegging the completeness target requires a completion definition sound under root movement (equivalent to geth `scheduler.Pending()==0`) | The prior fixed-walk-root termination gate can't apply to a moving root; a second verification BFS is O(total) and unnecessary |
