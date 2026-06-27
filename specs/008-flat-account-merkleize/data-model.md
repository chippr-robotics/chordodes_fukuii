# Data Model: Flat-Account Retention & Local State-Root Merkleization

**Feature**: `008-flat-account-merkleize` | **Date**: 2026-06-27

This feature adds no new chain/consensus data types. It (a) starts *writing* an existing-but-unused flat store and (b) adds finalize-time control state. Entities below are described at the behavioral level; concrete types live in the implementation.

## Entities

### Retained Account Store (`FlatAccountStorage`)
- **Represents**: the durable, ordered-enumerable set of downloaded account leaves — the symmetric counterpart to the already-written `FlatSlotStorage`.
- **Key**: `accountHash` (32-byte keccak-256 of the account address), in its own RocksDB namespace.
- **Value**: `accountRLP` — the RLP-encoded account leaf.
- **Operations**: `putAccountsBatch(pairs)` (write during download and during finalize re-fetch); `seekFrom(startHash) → ascending iterator of (accountHash, accountRLP)` over the full 32-byte keyspace.
- **Lifecycle**: written as account ranges arrive (Decision 2); overwritten for stale ranges during the finalize re-fetch (Decision 1); streamed once at merkleization; persists across restart (RocksDB-backed).
- **Invariant at merkleization**: every key present is the **final-pivot** (`R_final`) leaf for that account (guaranteed by the freeze + re-fetch, not by download bookkeeping).

### Account Leaf
- **Represents**: a single account's state: `{nonce, balance, storageRoot, codeHash}` (RLP).
- **Key field for this feature**: `storageRoot` — the **authoritative** per-account storage root used by the state-root computation, read directly off the leaf (RLP field 3). The state-root number does **not** re-derive `storageRoot` from `FlatSlotStorage`.

### Frozen Finalize Root (`R_final`)
- **Represents**: the single servable root the finalize pass is computed against, plus its canonical block.
- **Source**: `R_final = networkBest − offset`, confirmed servable via the existing recent-root/probe machinery.
- **Authority**: `pivotHeader(R_final).stateRoot` is the value the locally-computed root must equal.

### Coordinator Finalize Latch
- **Represents**: a boolean control state in `AccountRangeCoordinator` and `StorageRangeCoordinator`.
- **States**: `normal` (honor pivot-advance) ↔ `finalizing` (ignore `PivotRefreshed`/`StoragePivotRefreshed`).
- **Rule**: while `finalizing`, pivot-advance is suppressed so the re-fetch against `R_final` is never re-tagged mid-stream (Decision 3).

### Locally-Computed State Root
- **Represents**: the root produced by the single ascending `StackTrie` pass over the Retained Account Store.
- **Gate**: must equal `pivotHeader(R_final).stateRoot` or the node fails closed (Decision 4).

## Finalize State Transitions

```
Downloading
   │  (downloads complete; decide to finalize)
   ▼
Finalizing.Freeze ── pick servable R_final; set both coordinators → finalizing (latch)
   │
   ▼
Finalizing.Refetch ── GetAccountRange(R_final) → FlatAccountStorage (stale ranges; full re-fetch fallback);
   │                    reconcile changed contracts' slots → FlatSlotStorage (for import coherence)
   │   (peers stop serving R_final before pass completes) ──► FailClosed
   ▼
Finalizing.Merkleize ── seekFrom(0x00..) → single StackTrie pass → computedRoot
   │                      (storageRoot taken from each leaf)
   ▼
Finalizing.Verify ── computedRoot == pivotHeader(R_final).stateRoot ?
   │                              │
   │  yes                        │ no
   ▼                             ▼
Finalized                     FailClosed ── do NOT finalize; retry on fresher R_final,
(persist root; release latch;             else existing HealingRootUnservable→completeSnapSync handoff
 begin block-import past pivot)            (never partial-commit, never divergent root)
```

## Validation Rules (from Requirements)

- FR-001: every downloaded account leaf is retained and enumerable in ascending order.
- FR-002/FR-005: at merkleization every account contributes its **final-pivot** leaf and `storageRoot`.
- FR-003: finalize is gated on `computedRoot == header.stateRoot`.
- FR-004: any failure to produce a verified root ⇒ fail closed (no partial commit).
- FR-006: computation byte-for-byte deterministic; content-hash + anchor gates byte-untouched.
- FR-008: no-op on deferred-merkleization; no ETH/Sepolia effect.
- FR-009: flat stores persist across restart; coherence still comes from the finalize re-fetch.
