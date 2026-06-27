# Feature Specification: Flat-Account Retention & Local State-Root Merkleization for SNAP Completion

**Feature Branch**: `008-flat-account-merkleize`

**Created**: 2026-06-27

**Status**: Draft

**Input**: User description: "Retain account leaves during SNAP download (flat-account-write) so SNAP-from-scratch can complete against snapshot-root-only peers (core-geth) by merkleizing a coherent state root LOCALLY at finalize — instead of depending on the heal's GetTrieNodes path, which provably cannot reconcile an already-formed multi-lineage trie mosaic." (Ethereum Classic / Mordor; consensus-critical; forge protocol.)

## Context *(why this feature exists)*

A fresh node performing SNAP sync from scratch on Ethereum Classic against peers that serve state the way core-geth does — only their single *current* flat-snapshot root, not arbitrary historical roots — **never completes today**. As the network head advances during the long download, the node's sync pivot advances with it, and ranges that finished against earlier pivots are never re-reconciled, so the on-disk account trie becomes a multi-lineage "mosaic" with no single coherent root. The only mechanism that could reconcile it (state healing) needs to fetch the absent root node from peers, but peers cannot serve a node for a root they no longer index. The node therefore finalizes on an absent root, block-import immediately fails on a missing node, and the node re-snaps — forever. This was proven on a live Mordor node this session, and three in-place reconciliation approaches were each ruled out by review or live test. The root cause is an asymmetry: downloaded **storage slots are retained** in flat form, but downloaded **account leaves are discarded** after being streamed into a write-only trie builder — so the account state cannot be re-merkleized locally. This feature removes that asymmetry so the node can compute and self-verify a coherent state root locally, without depending on peers to serve historical trie nodes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Fresh SNAP completes with a self-verified coherent state root (Priority: P1)

A node operator starts a fresh node on Ethereum Classic whose only available peers serve state like core-geth (a single current flat-snapshot root). The node performs SNAP sync from scratch and, at the end, finalizes **only** on a state root it has computed locally and proven byte-for-byte equal to the canonical block-header state root. It then transitions to normal block-import and tracks the chain head. If it cannot compute and verify such a root, it does **not** finalize on an unverifiable or absent root — it fails closed (retries on a fresher servable pivot, or hands off via the existing safe path) rather than wedging in a re-snap loop.

**Why this priority**: This is the entire point of the feature and the non-negotiable safety property: *never finalize a state root that is not a verified, locally-present, canonical root*. Today the node finalizes on an absent root and wedges; this story makes completion both possible and safe.

**Independent Test**: Run a fresh SNAP on Mordor against core-geth peers; assert the node logs a locally-computed state root equal to the pivot header state root, finalizes, imports blocks past the pivot, and never enters a second SNAP cycle. Negative test: force verification to fail and assert the node does **not** finalize and instead falls back safely without corrupting state.

**Acceptance Scenarios**:

1. **Given** a fresh node syncing ETC against snapshot-root-only peers, **When** SNAP downloads complete, **Then** the node computes a state root locally, verifies it equals the canonical header state root, finalizes on it, and begins block-import past the pivot with no re-snap loop.
2. **Given** the node cannot compute a root equal to the header, **When** finalize is attempted, **Then** it refuses to finalize on the unverified/absent root and falls back to a safe path (retry on a fresher servable pivot, or the existing handoff) without corrupting or partially-committing state.

---

### User Story 2 - Account state retained for local root computation (Priority: P2)

During SNAP account-range download, the node retains every account leaf in a form that can be streamed back in canonical (ascending account-hash) order, so the complete account trie can be merkleized locally at finalize — without re-downloading the whole state and without asking peers for historical trie nodes.

**Why this priority**: This is the enabling capability behind User Story 1. Without retained account leaves, the node has nothing to merkleize locally at finalize (today the leaves are discarded after the streaming build, surviving only inside content-addressed trie nodes that cannot be enumerated without a coherent root).

**Independent Test**: After a SNAP account-download phase, enumerate all retained account leaves in order and assert they are complete and strictly ascending; merkleize them and assert the resulting account-trie root matches the expected root for a controlled fixture.

**Acceptance Scenarios**:

1. **Given** SNAP account ranges are downloaded, **When** the account-download phase completes, **Then** every account leaf is retrievable in strictly ascending account-hash order and the retained count equals the number of accounts served.
2. **Given** the retained account leaves, **When** they are merkleized locally, **Then** the computed account-trie root equals the canonical account-trie root for that state (fixture).

---

### User Story 3 - Coherent finalize when the pivot advances mid-download (Priority: P3)

On a long sync the network head advances and the node's sync pivot moves with it. The node still finalizes on a single coherent state consistent with **one** canonical block: every account's storage root reflects the final pivot, so the locally-computed state root matches that block's header.

**Why this priority**: Pivot advance is exactly what produces the incoherent mosaic today. Handling it is what makes completion robust on real networks. A short sync that never advances would complete with only User Stories 1–2, but every real ETC sync advances at least once, so this story is required for real-world completion.

**Independent Test**: Force a pivot advance partway through a Mordor sync; assert the final locally-computed state root equals the **final** pivot's header state root (not any intermediate one), i.e. storage that changed across the advance is reconciled to the final pivot.

**Acceptance Scenarios**:

1. **Given** the pivot advanced one or more times during download, **When** the node finalizes, **Then** the locally-computed state root equals the final pivot's canonical header state root.
2. **Given** an account whose storage changed between an earlier pivot and the final pivot, **When** the node finalizes, **Then** that account's contributed storage root reflects the final pivot — no stale storage contributes to the computed root.

---

### Edge Cases

- **Peers stop serving the chosen pivot before the node has what it needs**: fail closed — do not finalize an unverified root; retry on a fresher servable pivot or hand off via the existing safe path; never finalize partial/uncertain state.
- **Locally-computed root ≠ header state root**: never finalize; treat as a verification failure (the existing finalize anchor guard escalates); retry or fail closed.
- **Deferred-merkleization path (no inline trie build)**: the feature is inert (no-op); behavior is unchanged.
- **Node already carrying a wedged/mosaic state on disk from a prior version**: out of scope — those leaves were never retained; the operator re-syncs fresh or imports a checkpoint.
- **ETH/Sepolia syncs**: unaffected; the feature is ETC-scoped.
- **Crash/restart mid-download**: retained account state persists; resume does not lose retention; finalize still recomputes and re-verifies before completing.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The node MUST retain every downloaded account leaf during SNAP account-range download in a form that can be enumerated in ascending account-hash order at finalize. *(US2)*
- **FR-002**: At SNAP finalize, the node MUST compute the state root locally by merkleizing the complete retained account set — each account carrying its storage root — in a single canonical (ascending-hash) pass over the entire account keyspace. *(US1, US2)*
- **FR-003**: The node MUST verify the locally-computed state root is byte-for-byte equal to the canonical pivot block-header state root BEFORE finalizing, and MUST NOT finalize on any root that fails this check. This is an ADDITIONAL gate; the existing finalize anchor guard (persisted SNAP root equals pivot header state root) remains unchanged. *(US1)*
- **FR-004**: If a verified coherent root cannot be computed, the node MUST fail closed — it MUST NOT finalize on an unverified or absent root, MUST NOT partially commit state, and MUST fall back to a safe path (retry on a fresher servable pivot, or the existing unservable-root handoff). *(US1)*
- **FR-005**: When the sync pivot advances during download, the node MUST ensure the finalized state is coherent for a single canonical block — every account's contributed storage root MUST reflect the final pivot, with no stale storage contributing to the computed root. *(US3)*
- **FR-006**: The locally-computed state root and its verification MUST be byte-for-byte deterministic and match the governing ETC specification (the canonical chain). The content-hash verification used when storing trie/state data MUST remain unchanged. *(Consensus — NON-NEGOTIABLE)*
- **FR-007**: The feature MUST be controllable by configuration (enabled by default for fresh ETC SNAP) so it can be toggled off to reproduce prior behavior for A/B comparison and rollback. *(Validation)*
- **FR-008**: The feature MUST be a no-op on the deferred-merkleization path and MUST NOT alter ETH/Sepolia sync behavior. *(Scope)*
- **FR-009**: A crash/restart during download MUST NOT lose retained account state; on resume the node MUST still be able to compute and verify the coherent root at finalize. *(Reliability)*
- **FR-010**: Each new behavior — leaf retention, local merkleize-and-verify, and storage reconciliation under pivot advance — MUST be independently observable in logs/metrics so an operator can confirm which path executed and whether verification passed. *(Observability)*

### Key Entities

- **Account leaf**: the (account-hash, account-state) pair downloaded during SNAP account-range sync; the unit that must be retained for ordered enumeration at finalize.
- **Retained account store**: the persisted, ordered-enumerable collection of account leaves — the missing counterpart to the storage-slot collection the node already retains.
- **Locally-computed state root**: the state root the node merkleizes from retained account + storage data at finalize.
- **Canonical header state root**: the state root recorded in the pivot block's header; the authority against which the computed root is checked.
- **Final pivot**: the canonical block whose state the node finalizes on, which may have advanced from the initially selected pivot during a long download.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A fresh SNAP-from-scratch on ETC/Mordor against snapshot-root-only (core-geth) peers COMPLETES — the node finalizes on a locally-computed state root proven equal to the canonical header state root and begins importing blocks past the pivot, with zero re-snap cycles, in at least 3 of 3 consecutive trials.
- **SC-002**: The finalized state root is byte-for-byte identical to the canonical chain's state root at the finalize height, cross-checked against the reference client.
- **SC-003**: For every scenario, enabling vs disabling the feature never yields a *different* finalized state root — the outcome is either the same verified root or a fail-closed non-finalize, never a divergent root.
- **SC-004**: The node finalizes on an unverified or absent state root in zero trials (no false completions), including when peers stop serving mid-finalize.
- **SC-005**: Consensus test suites (crypto, MPT, ethereum/tests) pass byte-for-byte with the feature enabled.
- **SC-006**: The local-merkleization cost at finalize (wall-clock and allocation) is characterized on the CPU-constrained reference host and is bounded such that completion is reached within operational limits. *(Reported and recorded; not a hard gate.)*

## Assumptions

- The feature targets **fresh** syncs only; nodes with state already on disk from a prior version retained no account leaves and are out of scope (operator re-syncs or imports a checkpoint). Validation therefore re-syncs the Mordor node from scratch.
- Peers serve account/storage **ranges** for their current snapshot root (proven this session) even though they do not serve arbitrary historical trie nodes; the local-merkleize approach depends only on range serving plus retained local leaves.
- The specific mechanism that ensures each account's storage root reflects the final pivot (e.g. bounded delta re-fetch of changed accounts, pivot-advance coordination, or per-pivot slot versioning) is a design/plan decision; this spec requires the OUTCOME (coherent, byte-verified final state) and not a particular mechanism.
- `deferred-merkleization = false` is the operating mode for this feature; the deferred path is a documented no-op.
- Configuration default: enabled for fresh ETC SNAP; togglable for A/B comparison and rollback.
- This is consensus-critical work on the ETC domain and MUST follow the forge protocol; a parity failure (computed root not byte-equal to canonical) is a hard blocker regardless of throughput.

## Dependencies

- Builds on the existing retained storage-slot collection and inline (non-deferred) merkleization path.
- Coexists with the merged unservable-root handoff (the current fail-closed fallback) and the decoupled heal serve-root work.
- ETC consensus review via the `forge` specialist is mandatory before implementation and before merge.
