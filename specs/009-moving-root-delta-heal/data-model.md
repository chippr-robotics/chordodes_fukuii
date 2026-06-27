# Data Model: Moving-Root Delta Heal

**Feature**: `009-moving-root-delta-heal` | **Date**: 2026-06-27

No new chain/consensus data types. This feature changes the heal *control state* and how the existing content-addressed trie-node store is used. Entities are behavioral; concrete types live in `TrieNodeHealingCoordinator`.

## Entities

### Heal root (`stateRoot`)
- **Represents**: the SINGLE state root the heal currently completes toward AND fetches against (no separate walk/serve roots).
- **Source / movement**: a probe-confirmed **served** root, `head−64`, re-pegged to a fresh served root on every stale-move via `HealingPivotRefreshed(newStateRoot)`. Always equal to a canonical block header's `stateRoot`.
- **Invariant**: every `GetTrieNodes` request and every content-hash check uses this one root — so served nodes' hashes match the expected child references (the spec-004 wrong-axis fix).

### Missing-node delta (frontier)
- **Represents**: nodes referenced from the heal root but absent locally — discovered top-down by `discoverMissingChildren` (fetch → decode → enqueue only absent hash-referenced children). Shrinks toward zero.
- **Seed**: the heal root itself (enqueued as a frontier task even when absent locally — fetched by empty-path `GetTrieNodes`).

### Verified node
- **Represents**: a fetched node whose keccak matches the requested reference (content-hash gate, byte-untouched). Persisted permanently in the content-addressed node store; reusable under any nearby root (~99.9% shared) — the property that makes re-peg cheap and progress monotonic.

### Local fragment mosaic (cache)
- **Represents**: the per-range fragment nodes written during the non-deferred download. NOT a coherent root; used only as a cache that lets discovery prune already-present subtrees. "Present on disk" does NOT imply "subtree complete" (download-written, not heal-scheduled) — hence the pruned final descent.

### Stale-move trigger
- **Represents**: head advanced past `healRoot + ~margin`, or peers report `healRoot` unservable → request a fresh served root and re-peg.

### Completion gate
- **Represents**: completion is declared ONLY when (a) the delta frontier is empty (no pending/active requests) against the current heal root, AND (b) a **pruned descent** rooted at the current heal root finds zero absent reachable nodes. (b) is the genuine "full trie for this root present + content-verified" proof; (a) alone is unsound due to the download mosaic.

## Heal State Transitions

```
HealStart
   │  seed: enqueue (emptyPath, healRoot) even if absent locally  [Decision 1]
   ▼
DeltaDiscovery ── fetch node (GetTrieNodes vs healRoot) → content-verify → persist →
   │              discoverMissingChildren enqueues only absent children; prune present subtrees
   │   (head advances past healRoot+margin / peers unservable) ──► RePeg
   │   (frontier empties: pending==0 && active==0)              ──► PrunedDescent
   ▼
RePeg ── HealingPivotRefreshed(newServedRoot): set healRoot=newRoot, clear in-memory frontier ONLY
   │      (persisted verified nodes RETAINED), verificationPassComplete=false, re-seed from newRoot
   └────────────────────────────────────────────────────────────────► DeltaDiscovery (smaller delta)
   ▼  (from DeltaDiscovery when frontier empties)
PrunedDescent ── descend from current healRoot; skip durably-complete subtrees (O(delta));
   │              any absent reachable node → re-enqueue → back to DeltaDiscovery
   │   (re-peg during descent) ──► RePeg (verificationPassComplete reset; descent redone vs new root)
   ▼  (descent finds zero absent && walkRoot==current stateRoot)
Complete ── verificationPassComplete=true && isComplete → finalize on healRoot (== canonical header);
            anchor guard snapStateRoot==pivotHeader.stateRoot holds → block-import past pivot
```

## Validation Rules (from Requirements)

- FR-001: heal targets a served root; absent root node is fetched, not handed off (handoff = bounded last resort).
- FR-002: one root for completeness + fetch; content-hash gate byte-untouched and matches by construction.
- FR-003/SC-003: delta discovery for the frontier (no O(total) discovery walk); pruned O(delta) final descent for soundness.
- FR-004: re-peg retains all persisted verified nodes (no discard, no restart).
- FR-005: completion = empty frontier AND pruned-descent-zero-absent against the current root; re-peg resets the descent.
- FR-006: finalized root == canonical header root; deterministic.
- FR-010: spec-004 split + spec-008 freeze-re-fetch gated off when the flag is on.
