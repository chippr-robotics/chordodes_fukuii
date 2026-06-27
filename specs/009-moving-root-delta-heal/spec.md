# Feature Specification: Moving-Root Delta Heal for SNAP Completion

**Feature Branch**: `009-moving-root-delta-heal`

**Created**: 2026-06-27

**Status**: Draft

**Input**: User description: "Moving-root delta heal — complete SNAP-from-scratch on ETC against snapshot-root-only peers (core-geth) by adopting go-ethereum/besu's proven healing architecture (build-the-trie-during-download + single moving heal root + delta-only top-down discovery), replacing fukuii's self-inflicted heal defects. Supersedes spec-004's decoupled walk-root/serve-root split; retires spec-008's freeze-and-full-re-fetch. ETC/Mordor; consensus-critical; forge protocol."

## Context *(why this feature exists)*

A fresh node performing SNAP sync from scratch on Ethereum Classic against core-geth peers does not complete today. This session established — via a fukuii-vs-go-ethereum-vs-besu source comparison and a probe of go-ethereum source — that geth and besu **do** complete against the *same* peers, and that the obstruction is **not** the peers: in hash scheme, core-geth serves `GetTrieNodes` for any recent (within ~128 blocks) or ever-committed state root, with **broader** retention than `GetAccountRange` (the ~4–8 minute window that defeated prior fukuii attempts was a snapshot-layer limit specific to range queries, which trie-node queries bypass). fukuii's failures are self-inflicted: (a) when the trie is not built during download, the heal has no local root to walk from and aborts; (b) the prior heal kept a *fixed* completeness root while fetching against a *different* advancing root, so verified-by-hash nodes never matched and nothing healed; (c) the prior heal re-walked the entire local trie (and a second verification walk) instead of discovering only the missing delta; (d) a later attempt re-fetched the entire account *leaf* set against one frozen root, which cannot finish before that root ages out of the range-serve window. This feature adopts the geth/besu architecture that demonstrably works: build the trie during download so a coherent root is on disk at heal start, heal against a **single root that tracks the moving network head**, and discover **only the missing delta** top-down — so the heal converges within the serve window and the sync completes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Fresh SNAP completes via the moving-root delta heal (Priority: P1)

A node operator starts a fresh node on Ethereum Classic whose only peers serve state like core-geth (current snapshot root only, moving every few minutes). The node performs SNAP sync from scratch and, at the end, **heals to a verified canonical state root and transitions to normal block-import**, tracking the chain head — with no infinite re-snap loop and no multi-hour full-trie re-walk.

**Why this priority**: This is the entire objective — today the node never completes against these peers. Everything else is in service of this outcome.

**Independent Test**: Run a fresh SNAP on Mordor against core-geth peers; assert the heal reports shrinking missing-node counts, reaches zero against a recent root, the node finalizes on a state root equal to the canonical header state root, and it imports blocks past the pivot with no second SNAP cycle.

**Acceptance Scenarios**:

1. **Given** a fresh node syncing ETC against snapshot-root-only peers, **When** account/storage download completes, **Then** the heal runs to completion (missing-node count reaches zero against a recent root), the node finalizes on a state root equal to the canonical header state root, and begins block-import past the pivot with no re-snap loop.
2. **Given** the network head advances during the heal, **When** the current heal root ages toward the edge of what peers serve, **Then** the heal continues to completion (it does not stall on an unservable root) and still finalizes on a canonical root.

---

### User Story 2 - Heal fetches only the missing delta against a single served root (Priority: P2)

The heal discovers missing nodes by walking **top-down from one current root**, fetching a node, decoding it, and requesting **only** its hash-referenced children that are absent locally — never a walk of the entire local trie, and never a second verification walk. Every node it requests is verified by content hash against **that same root's** child references, so served nodes match and are accepted.

**Why this priority**: This is the core mechanism fix. The prior heal kept a fixed completeness root but fetched against a different advancing root, so the (correct) content-hash check rejected every node and nothing healed. Unifying to a single root makes the heal actually make progress; delta-only discovery keeps the work small enough to finish in a serve window.

**Independent Test**: With a controlled local trie missing a known set of interior/leaf nodes and a peer serving the matching root, assert the heal requests exactly the missing nodes (not the whole trie), each served node is accepted (hash matches), and the missing set drains to zero — with no full-trie scan performed.

**Acceptance Scenarios**:

1. **Given** a locally-built trie with a known missing-node delta and a peer serving the matching root, **When** the heal runs, **Then** it requests only nodes in the delta (work is O(delta), not O(total)), every served node passes the content-hash check, and the delta drains to zero.
2. **Given** a node already present and correct on disk, **When** the heal traverses its parent, **Then** that subtree is not descended or re-requested (already-complete subtrees are pruned).

---

### User Story 3 - The heal tracks the moving head and accumulates progress monotonically (Priority: P3)

As the network head advances, the heal **re-pegs its single root to a fresh recent root** (so every request targets a root peers still serve) **without discarding** already-verified nodes — because content-addressed nodes are shared across nearby roots, the still-missing set only shrinks. Progress therefore accumulates across many short serve windows; the heal never depends on one root surviving for the whole job.

**Why this priority**: This is the convergence-under-churn property. It is what lets the heal finish even though no single root is served long enough to complete in one window, and what prevents the "asked a peer for a root it no longer holds → empty reply → starve" failure.

**Independent Test**: Force the heal root to age out mid-heal; assert the heal re-pegs to a newer served root, retains all previously-verified nodes (no re-download of completed subtrees), the still-missing count after the re-peg is ≤ before, and the heal still completes.

**Acceptance Scenarios**:

1. **Given** the heal is mid-progress and its root ages out of what peers serve, **When** the stale-move is detected, **Then** the heal re-pegs to a fresh recent root, keeps all already-persisted verified nodes, and resumes from the (smaller) remaining delta — not from scratch.
2. **Given** repeated re-pegs over the course of a heal, **When** completion is evaluated, **Then** it is declared only when there are no nodes referenced-but-missing from the current root and no outstanding requests — a gate equivalent to "the full trie for that root is present locally."

---

### Edge Cases

- **Heal root ages out mid-fetch**: re-peg to a fresh recent root; retained verified nodes carry over; never starve on the aged root.
- **Trie not built during download (deferred path)**: the heal has no local root to walk from; the node falls back to the existing on-demand handoff as a genuine last resort (not the default), and this is surfaced, not silent.
- **A served node fails the content-hash check** (wrong root / corrupt): it is dropped and re-requested against the current root; never stored.
- **Peers transiently stop serving**: heal pauses/retries; it must not declare completion while any referenced node is missing.
- **Existing on-disk fragment-mosaic datadir (from a prior version)**: out of scope — the moving-root delta heal assumes a trie built during a fresh download; such datadirs need a fresh sync or checkpoint import.
- **ETH/Sepolia syncs**: unaffected.
- **Crash/restart mid-heal**: persisted verified nodes survive; the heal resumes by re-discovering only the still-missing delta against a current root (no full re-walk).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The heal MUST target a root that peers serve (a recent head-relative root); local trie nodes are used as a content-addressed **cache** where present, and the root node itself MUST be **fetched if absent locally** — it MUST NOT be skipped or handed off as the default. A local *coherent* root is NOT required at heal start (the non-deferred download produces a content-shared fragment mosaic, not a single coherent root; that is sufficient as cache). The existing on-demand handoff is preserved only as a bounded last resort if no served root can be obtained. *(US1, US2 — corrected per Phase-0 Decision 1)*
- **FR-002**: The heal MUST use a **single** root for both its completeness target and its node fetches. Every fetched node MUST be verified by content hash against that same root's child references; nodes that do not match MUST be dropped, never stored. The content-hash verification MUST remain unchanged. *(US2 — Consensus)*
- **FR-003**: The heal MUST discover missing nodes by **top-down delta traversal** (fetch a node → decode → request only its hash-referenced children that are absent locally), and MUST NOT perform an O(total) walk of the entire local trie for discovery. A **final completeness descent** against the current heal root IS required for soundness before declaring completion (because the download pre-populates fragment nodes, "present on disk" does not imply "subtree complete"), but it MUST be **pruned to O(delta)** — durably-recorded-complete subtrees are skipped — never an O(total) walk. *(US2 — corrected per Phase-0 Decision 5)*
- **FR-004**: When the current heal root ages out of what peers serve (head advances past it, or peers report it unservable), the heal MUST re-peg to a fresh recent served root and resume, **without discarding** already-persisted verified nodes. *(US3)*
- **FR-005**: Completion MUST be declared only when no node referenced from the current heal root is missing locally and no requests are outstanding — a gate provably equivalent to "the full trie for that root is present locally." Re-pegging the root MUST NOT weaken this guarantee. *(US3 — Consensus)*
- **FR-006**: The finalized state root MUST be byte-for-byte equal to the canonical block-header state root at the finalize height, and the heal output MUST be byte-for-byte deterministic and ETC-spec compliant. *(Consensus — NON-NEGOTIABLE)*
- **FR-007**: The new healing behavior MUST be controllable by configuration (enabled by default for ETC SNAP) so it can be toggled off to reproduce prior behavior for A/B comparison and rollback. *(Validation)*
- **FR-008**: The feature MUST NOT alter ETH/Sepolia sync behavior. On the deferred-build path it MUST preserve a safe fallback (the on-demand handoff) rather than a wrong or partial completion. *(Scope)*
- **FR-009**: Heal progress MUST be observable: the still-missing-node count per round, the current heal root and re-peg events, heal throughput, and outstanding requests — so an operator can confirm the delta is shrinking and completion is real. *(Observability)*
- **FR-010**: This feature SUPERSEDES the prior fixed-completeness-root / advancing-serve-root split and the O(total) heal walks, and RETIRES the prior freeze-and-full-re-fetch completion path; those code paths MUST be removed or gated off so only one healing strategy is live by default. *(Scope)*

### Key Entities

- **Heal root**: the single state root the heal is currently completing toward and fetching against; re-pegged to a fresh recent root as the head moves.
- **Missing-node delta**: the set of nodes referenced from the heal root but absent locally — discovered top-down, shrinking toward zero.
- **Verified node**: a fetched node whose content hash matches the requested reference; persisted permanently and shared across nearby roots.
- **Stale-move trigger**: the condition (head advanced past the heal root, or peers report it unservable) that causes a re-peg.
- **Completion gate**: the condition that the heal trie for the current root has no referenced-but-missing nodes and no outstanding requests.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A fresh SNAP-from-scratch on ETC/Mordor against core-geth peers COMPLETES — the heal's missing-node count reaches zero against a recent root, the node finalizes on a state root equal to the canonical header state root, and it imports blocks past the pivot with zero re-snap cycles, in at least 3 of 3 consecutive trials.
- **SC-002**: The finalized state root is byte-for-byte identical to the canonical chain's state root at the finalize height (cross-checked against the reference client).
- **SC-003**: The heal performs no O(total) walk of the local trie; a **pruned O(delta) final completeness descent** is required for soundness against the pre-populated download mosaic. Total nodes requested over the heal is on the order of the missing delta, not the full trie (orders of magnitude smaller than total node count).
- **SC-004**: The heal completes even when the head advances repeatedly during it (the heal root is re-pegged and progress is retained) — demonstrated by a run in which the initial root is no longer served by the end.
- **SC-005**: With the feature disabled, behavior matches the prior strategy; enabling vs disabling never yields a *different* finalized state root (same verified canonical root, or a safe non-completion — never a divergent root). Consensus suites (crypto, MPT, ethereum/tests) pass byte-for-byte with the feature enabled.
- **SC-006**: Heal throughput and the cost of building the trie during download (CPU and GC) are characterized on the constrained reference host; the end-to-end fresh-Mordor sync completes within operational limits with adequate heap. *(Reported and recorded.)*

## Assumptions

- Peers serve `GetTrieNodes` for recent and committed roots with retention at least as broad as range queries (established from go-ethereum source for hash-scheme core-geth this session); the moving-root heal targets only roots within that served window.
- The trie is built during download (non-deferred merkleization is the operating mode); the deferred path is a documented last-resort fallback, not the target.
- The feature targets **fresh** syncs; existing fragment-mosaic datadirs from prior versions are out of scope (re-sync or checkpoint import).
- Re-pegging reuses the existing recent-root request machinery already present for recovery; no new wire protocol is introduced (the `GetTrieNodes` request framing is already correct).
- Validation is on Mordor against core-geth peers, with a fresh re-sync; adequate JVM heap is provisioned for the non-deferred build.
- This is consensus-critical ETC work and MUST follow the forge protocol; a parity failure (finalized root not byte-equal to canonical) is a hard blocker regardless of throughput.

## Dependencies

- Builds on the inline (non-deferred) trie build during SNAP download and the existing recent-root request/response machinery.
- Supersedes the decoupled walk-root/serve-root healing split and the O(total) heal walks; retires the freeze-and-full-re-fetch completion path.
- ETC consensus review via the `forge` specialist is mandatory before implementation and before merge.
