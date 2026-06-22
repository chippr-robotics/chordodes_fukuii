# Phase 1 Data Model: Subtree-Complete Heal Verification

The one new persisted artifact is the **subtree-complete record**, an additive sentinel in the existing `HealingFrontierStorage` column family (CF `'g'`). No new column family, no migration, no schema change to the node store (CF `'n'`). All other state is reused unchanged.

## Entities

### Subtree-complete record — NEW (the new completeness-proof unit)
- **What**: a durable fact "the entire subtree rooted at trie-node hash X is present and correct on disk." The basis that lets the verification prune (descend-and-stop) at X.
- **Representation**: a key in CF `'g'` with a non-32-byte prefix that cannot collide with a bare 32-byte node hash or the existing 21-byte `CompleteMarkerKey` — e.g. `0x01 ++ keccak(X)` (33 bytes) → value `0x01`. Keyed on the **bare keccak hash** (the same key `pendingHashSet`/the frontier use), NOT the ref-count-wrapped `StoredNode` value.
- **Invariants**:
  - **Root-independent / eternal (D1)**: nodes are content-addressed, so X's children are fixed by X's content; once X's subtree is proven present, the record is valid forever and across pivot rolls — it is **never cleared/invalidated** (unlike the global `isComplete` marker cleared at `:241`).
  - **Additive + monotone**: only ever written, never removed in normal operation (healing never deletes present nodes).
  - **Durability-ordered (D3/FR-006)**: a record for X is written **only after** every node in X's subtree is in a committed RocksDB `WriteBatch`. Absent record ⇒ verification descends ⇒ safe.
- **Lifecycle**: written (a) when a heal subtree closes with zero missing **descendants** — established inductively, every present child (incl. `storageRoot`) itself recorded-complete (D2), and (b) when the verification walk confirms the state root zero-missing at the completion chokepoint (D1). Read by the verification's per-child pruning oracle. (The SNAP account-fragment seed was dropped as unsound — see research.md D2 CORRECTION.)

### Present-and-complete node
- **What**: a node on disk whose subtree-complete record exists ⇒ a verified frontier leaf the verification prunes (does not descend).
- **Rule (FR-001/FR-004)**: prune **iff** present AND recorded-complete. Present-but-not-recorded ⇒ descend (the unsound assumption the full re-walk exists to catch today).

### Verification frontier
- **What**: the set of nodes the pruned verification actually visits — missing nodes, present-but-not-recorded nodes, and the ancestors leading to them. Sized **O(missing-frontier + ancestors)**, independent of the ~90M total (SC-006).

### Completeness proof (reused, semantics sharpened)
- The basis for `StateHealingComplete` remains "the verification finds zero missing against the root" through the single chokepoint (`HealingCheckCompletion` `:889-912`, `verificationPassComplete` `:953`, `markComplete` `:906-911`). Pruning only changes which present nodes are read to reach that conclusion (D4). The terminal `markComplete()` is upgraded to `updateSync` (fsync) for durability (D3).

### Config — NEW
- **`pruned-heal-verification: Boolean`** (default `true`) in `sync.conf` snap-sync block → `SnapSyncConfig` → `TrieNodeHealingCoordinator.props`/constructor. Off ⇒ existing full-trie verification (D6). Effective only under `storageScheme == Hash` (D5).

## Relationships & state flow

```
heal subtree closes 0-missing ─► record subtreeComplete(subtreeRoot)   (D2 heal-side seeding — makes the first
   (inductive: every present child, incl. storageRoot, already recorded)  verification O(missing); SNAP account-
verification confirms root 0-missing ─► record subtreeComplete(stateRoot)  fragment seed DROPPED as unsound)
        (record ALWAYS written AFTER the subtree's node-bytes are committed — D3)

verification walk (rebuildFrontierBFS, pruned):
  at each present child X:
    isSubtreeComplete(X) ?
       yes ─► PRUNE (verified leaf; do not descend; ++prunedCount)
       no  ─► descend X (read it, check its children) ; if X's subtree closes 0-missing ─► record subtreeComplete(X)
    child missing ─► emit FrontierRebuilt(X) for healing  (unchanged; spec-004 fetch + content-hash guardrail)
  walk finds 0 missing ─► VerificationBFSComplete ─► verificationPassComplete=true ─► markComplete (updateSync) ─► StateHealingComplete
```

## Validation rules (from requirements)

| Rule | Source | Enforcement |
| --- | --- | --- |
| Prune iff present AND recorded-complete | FR-001/FR-004 | oracle `isSubtreeComplete` at the per-child descent (`:1602`) |
| Present node trusted as subtree-complete only via a durable record | FR-002 | record in CF `'g'`; absent ⇒ descend |
| Invariant holds on a FRESH node's first verification | FR-003 | seed records during SNAP fragment commit + heal subtree closure (D2) |
| Crash never leaves record-without-durable-subtree | FR-006 | record written only post-`persist`; terminal marker fsync (D3) |
| Byte-for-byte parity with full walk | FR-005 | same chokepoint, same emitted-missing set, no state writes (D4) |
| Config + Path-scheme + no-records fallback | FR-007 | flag + `storageScheme==Hash` gate; else full walk (D5) |
| Fetched-node content-hash guardrail preserved | FR-008 | `keccak256(node)==requested hash` at `:1290` untouched |
