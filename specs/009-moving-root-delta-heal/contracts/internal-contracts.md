# Internal Contracts: Moving-Root Delta Heal

**Feature**: `009-moving-root-delta-heal` | **Date**: 2026-06-27

No external/wire interface change — `GetTrieNodes`/`TrieNodes` are reused unchanged (fukuii's request framing already matches geth `NewSyncPath`). The contracts below are *internal* component boundaries the implementation and tests must honor.

## C1 — Single-root fetch + content gate

- The heal fetches every node via `GetTrieNodes(rootHash = healRoot, paths = …)` where `healRoot == stateRoot` (the completeness root). The content-hash gate (`keccak(node) == requested reference`) is byte-unchanged.
- **Guarantee under test**: with a peer serving root S and the heal root set to S, a served root node (empty-path) and served internal/leaf nodes all pass the gate and are persisted (no "non-matching hash" drops — the spec-004 wrong-axis failure does not occur).

## C2 — Seed-from-absent-root at heal start

- At heal start, if the heal root node is absent locally, the heal MUST enqueue it as a frontier task (empty-path) and fetch it — NOT hand off. (Mirrors `HealingPivotRefreshed`'s existing absent-root seed.)
- **Guarantee under test**: starting a heal with an absent root and a serving peer results in the root being fetched and discovery proceeding (not `HealingRootUnservable`); the handoff fires only after a bounded re-peg budget with no served root.

## C3 — Delta discovery

- On each healed node, `discoverMissingChildren` decodes it and enqueues ONLY hash-referenced children absent on disk; present subtrees are not re-requested. No O(total) trie walk seeds discovery.
- **Guarantee under test**: with a known missing-node delta and a peer serving the matching root, the heal requests exactly the delta (count ≈ delta, ≪ total), and the frontier drains to empty.

## C4 — Re-peg retains progress

- On stale-move, `HealingPivotRefreshed(newRoot)` sets `healRoot = newRoot`, clears ONLY in-memory frontier state (`pendingTasks`/`activeRequests`/`pendingHashSet`), resets `verificationPassComplete=false`, and re-seeds from `newRoot`. It MUST NOT delete persisted trie nodes.
- **Guarantee under test**: a re-peg mid-heal preserves all previously-persisted verified nodes (no re-download of completed subtrees); the post-re-peg still-missing count ≤ pre-re-peg.

## C5 — Sound completion gate

- Completion is declared ONLY when `isComplete` (frontier empty + no active requests) AND `verificationPassComplete` (a pruned descent from the current `healRoot` found zero absent reachable nodes). A re-peg during/after discovery forces a fresh descent (`verificationPassComplete=false`) against the new root before completion.
- **Guarantee under test**: a trie with a download-written present node whose child is absent does NOT reach completion on delta-discovery alone — the pruned descent finds the gap and re-enqueues it; completion is declared only once the descent is clean against the current root.

## C6 — Config gate + supersession

- A `sync.conf` key (e.g. `moving-root-delta-heal`, default on for ETC SNAP) gates C1–C5. OFF ⇒ byte-identical to today's spec-004 decoupled path. When ON: `decoupledHealServeRoot` (spec-004) and `beginFinalizeRefetch` (spec-008) are inert. No-op / unaffected for ETH/Sepolia.

## Consensus contract (cross-cutting)

The finalized state root MUST equal the canonical pivot-header `stateRoot` (anchor guard unchanged); every stored node MUST be content-verified (gate unchanged); the completion gate MUST be sound (no completion with any referenced-but-absent node). A parity failure (finalized root ≠ canonical) is a hard blocker.
