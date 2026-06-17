# Phase 1 Data Model: Decoupled Heal Serve-Root

No new persisted schema. All new state is **in-memory** in `TrieNodeHealingCoordinator` (a restart falls back to coupled behavior / full re-walk, which is safe). The durable stores are reused unchanged: the content-addressed node store (RocksDB CF `n`, read via `multiGetNodes`, written content-keyed at `:1140`) and the completeness marker (CF `g`, `markComplete`/`isComplete`).

## Entities

### Walk root (`stateRoot`)
- **What**: the fixed state root the local completeness BFS is anchored to for a heal round; the sole basis of the completeness decision.
- **Representation**: the existing `private var stateRoot: ByteString` (`TrieNodeHealingCoordinator.scala:40`). **Semantics unchanged** by this feature — it is read by the walk seeds, `isComplete`, `verificationPassComplete`, `markComplete`, and the scoped-verification `healedPathsRoot` tag.
- **Lifecycle**: set at heal start; mutated only by `HealingPivotRefreshed` (genuine all-peers-stateless roll) as today. Hold-pivot keeps it stable across stagnation by not sending that message.
- **Invariant (FR-001)**: not changed by serve-window aging. A `HealingServeRootRefresh` MUST NOT touch it.

### Serve root (`serveRoot`) — NEW
- **What**: the currently-servable state root used to fetch missing nodes from peers; advances with the chain, decoupled from the walk root.
- **Representation**: new `private var serveRoot: ByteString = stateRoot` (initialized to the walk root for fallback parity).
- **Lifecycle**: updated only by the new `HealingServeRootRefresh(newServeRoot)` handler; read only at the fetch build site (`:1080`), and only when the feature is enabled.
- **Invariants**: (FR-002/FR-003) tracks a root within peers' serve window; (FR-011) O(1) to update, no growth; (SC-006) when the feature is off, `serveRoot` is unused and the fetch reads `stateRoot`.

### Missing node / heal task (content-addressed)
- **What**: a trie node the walk found absent locally, identified by its content hash; the unit of healing. Existing `HealingEntry(pathset, hash)` and the `pendingTasks`/`activeRequests` sets.
- **Key field**: `hash` (keccak256 of the node) — the walk-root's expectation. The fetch sends `pathset` under `serveRoot`; acceptance requires `keccak256(returned) == hash` (`:1137`). Identity is root-independent (this is what makes cross-root sourcing sound).
- **Invariant (FR-004/SC-004)**: a returned node is stored only if its content hash equals the requested task hash; mismatches are dropped, never stored, never counted as healed.

### Heal-attempt counter — NEW (FR-006)
- **What**: per-task count of fetch attempts that did not satisfy the task (timeout or content-mismatch), used to bound silent retry.
- **Representation**: a side `mutable.Map[ByteString /*task hash*/, Int]`, bounded by the pending-task set size (FR-011).
- **Lifecycle**: incremented on each unsatisfied re-queue (`:1184-1189`); **reset on each `HealingServeRootRefresh`** (a serve-root advance is the legitimate retry trigger); when a task exceeds a bounded threshold *with no serve-root advance in between*, emit a surfacing signal (log + metric). It MUST NOT trigger completion or abandonment.

### Serve window (conceptual)
- **What**: the bounded range of recent blocks/roots peers will serve snap data for (≈ most recent ~128 blocks on ETC). Not stored; informs how stale `serveRoot` may be before a refresh is needed. The refresh target reuses `RecentRootMarginBlocks = 64` (`SyncController.scala:89`).

### Config — NEW (FR-008)
- **`decoupled-heal-serve-root: Boolean`** (default `true`) in `sync.conf` snap-sync block → `SnapSyncConfig` → `TrieNodeHealingCoordinator.props`/constructor. Off ⇒ coupled behavior (fetch uses `stateRoot`).

## Relationships & state flow

```
chain advances ─► SNAPSyncController obtains servable root (RecentRoot bootstrap, networkBest−64)
                     └─► HealingServeRootRefresh(serveRoot) ─► coordinator: serveRoot := r  (walk root untouched)
walk (stateRoot) ─► finds missing node (pathset, hash) ─► fetch GetTrieNodes(rootHash = serveRoot, pathset)
   peer returns node under serveRoot ─► keccak256 == hash ?
        yes ─► store content-keyed ─► task satisfied ─► (later) walk re-reads by hash ─► 0 missing ─► markComplete
        no  ─► drop, re-queue, attempt++ ; on serveRoot refresh attempt:=0 ; over threshold w/o refresh ─► surface (FR-006)
completion gate: verificationPassComplete (walk vs stateRoot finds isComplete) ─ unchanged ─► StateHealingComplete
```

## Validation rules (from requirements)

| Rule | Source | Enforcement |
| --- | --- | --- |
| Walk root never changed by serve-window aging | FR-001 | `HealingServeRootRefresh` touches only `serveRoot` |
| Fetch uses a servable root, not the walk root | FR-002/FR-003 | fetch `rootHash = if (enabled) serveRoot else stateRoot` |
| Content-hash match before store | FR-004 | existing check at `:1137` (unchanged, must not weaken) |
| Completeness judged against fixed walk root | FR-005 | completion path reads `stateRoot` only (unchanged) |
| No false complete, no silent infinite retry | FR-006 | attempt counter + surface; force-complete/abandon neutralized |
| Byte-for-byte parity with coupled path | FR-007 | same stored nodes ⇒ same walk ⇒ same marker + state root |
| Config fallback to coupled | FR-008 | flag default-on; off ⇒ `stateRoot` fetch |
| Bounded memory | FR-011 | `serveRoot` O(1); counter bounded by pending set |
