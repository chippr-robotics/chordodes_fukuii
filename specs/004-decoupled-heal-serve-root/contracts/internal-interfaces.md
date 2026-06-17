# Phase 1 Contracts: Decoupled Heal Serve-Root (internal interfaces)

This is an internal sync-orchestration change — no external/wire API. The "contracts" are the internal actor messages, the coordinator's root vars, the fetch-site selection, the config, and the (untouched) completion gate. Citations are `staging` HEAD; line numbers are anchors, not guarantees.

## C1 — Serve-root variable (coordinator state)

```
// TrieNodeHealingCoordinator — NEW field, alongside `private var stateRoot` (:40)
private var serveRoot: ByteString = stateRoot   // advancing servable root; == walk root until refreshed
```

Contract: `serveRoot` is read **only** at the fetch build site (C3). It is never read by the walk seeds, `isComplete`, `verificationPassComplete`, `markComplete`, or the scoped-verification `healedPathsRoot` tag. `stateRoot` (walk root) semantics are unchanged.

## C2 — `HealingServeRootRefresh` message (NEW)

```
// Messages.scala — TrieNodeHealingCoordinator message set (~:250)
final case class HealingServeRootRefresh(newServeRoot: ByteString)
```

Handler contract (in `TrieNodeHealingCoordinator`):
- Sets `serveRoot = newServeRoot`.
- Resets the FR-006 attempt counters (a serve-root advance is the legitimate retry trigger).
- Does **NOTHING ELSE**: MUST NOT mutate `stateRoot`, clear `pendingTasks`/frontier, reset `verificationPassComplete`, or re-seed the walk. (Contrast `HealingPivotRefreshed` `:629-701`, which does all of those and MUST NOT be reused for serve-root advance.)
- No-op when the feature is disabled.

## C3 — Fetch-root selection at the GetTrieNodes build site

```
// requestNextBatch, TrieNodeHealingCoordinator.scala:1078-1083
val request = GetTrieNodes(
  requestId = requestId,
  rootHash  = if (decoupledHealEnabled) serveRoot else stateRoot,   // <-- only changed line
  paths     = paths,
  responseBytes = responseBytes
)
```

Contract: the **only** behavioral fetch change. Everything downstream (response handling, content-hash check, store) is unchanged.

## C4 — Content-hash guardrail (UNCHANGED — must be preserved)

```
// handleResponse, TrieNodeHealingCoordinator.scala:1129-1137 (DO NOT WEAKEN)
val nodeHash = ByteString(keccak.digest(nodeData.toArray))
if (taskByHash.contains(nodeHash)) { /* store content-keyed (:1140), satisfy task */ }
else { /* drop (:1174): not stored, not counted, task stays pending */ }
```

Contract (the load-bearing safety invariant, FR-004/SC-004): a node returned by *any* serve root is accepted **iff** its keccak256 equals a requested task hash (the walk root's expectation). This is what makes decoupling safe despite path-addressed fetch (a serve root that resolves a path to a different node yields a hash mismatch → drop). No decoupled code path may bypass this.

## C5 — FR-006 attempt counter + surfacing (NEW)

```
private val healAttempts = mutable.Map.empty[ByteString, Int]   // bounded by pendingTasks
// on unsatisfied re-queue (:1184-1189): healAttempts(hash) += 1
// on HealingServeRootRefresh: healAttempts.clear()  (or decay)
// when healAttempts(hash) > decoupledHealMaxAttemptsNoRefresh: emit surfacing log + metric
```

Contract: surfacing is **observation only**. It MUST NOT call `HealingForceComplete` (`:614`) or let stagnation-abandon declare `StateHealingComplete` while any task is unsatisfied (SC-002). Completion remains gated solely on the walk finding zero missing against the walk root.

## C6 — Completion gate (UNCHANGED)

```
// HealingCheckCompletion (:748-806) → verificationPassComplete (set only in VerificationBFSComplete :812
//   after BFS vs stateRoot finds isComplete :1329) → markComplete (:766) → StateHealingComplete
```

Contract (FR-005/FR-007): completeness is decided purely by the walk against the fixed walk root. The serve root supplies node *bytes* only (content-verified at C4). Same final stored nodes ⇒ same walk result ⇒ same marker + same state root as the coupled path. **No new completion site, no new marker set-point.**

## C7 — Controller → coordinator serve-root push (SNAPSyncController + SyncController)

Flow (reusing the `RecentRoot` bootstrap template, `SyncController.scala:1482-1565`):
```
chain advance / serve-window aging
  └─ SNAPSyncController requests a servable root (RequestRecentRoot-style, networkBest−64)
       └─ SyncController PivotHeaderBootstrap → RecentRoot(block, stateRoot)
            └─ SNAPSyncController ! HealingServeRootRefresh(stateRoot)  → trieNodeHealingCoordinator
```
Contract: the healing requester must not contend with `StorageRecoveryActor` on `SyncController`'s single `recentRootRequester` (`:1484`) — use a distinct slot/tag. The push targets the coordinator with `HealingServeRootRefresh` (C2), never `HealingPivotRefreshed`.

## C8 — Config (NEW)

```
# sync.conf, snap-sync block
decoupled-heal-serve-root = true            # FR-008; off ⇒ coupled (fetch uses walk root)
decoupled-heal-max-attempts-no-refresh = N  # FR-006 surfacing threshold (planning to pick N)
```
Threaded through `SnapSyncConfig` (alongside `healHoldPivotOnStagnation`, `SNAPSyncController.scala:4923`) → `TrieNodeHealingCoordinator.props` (`:3364`,`:3429`) → constructor (`:39-61`).

## C9 — Metrics (NEW, FR-010)

Additive `app_`-prefixed gauges via `SNAPSyncMetrics`:
- walk root (label/short hash), current serve root (label/short hash),
- count of nodes healed via a serve root different from the walk root,
- count of tasks currently unservable (over the FR-006 threshold).

## Test contracts (deterministic, Pekko TestKit — no `Thread.sleep`)

| # | Asserts | Maps to |
| --- | --- | --- |
| T-1 | With feature on + `serveRoot` ≠ `stateRoot`, the `GetTrieNodes` request carries `serveRoot`; the walk seeds carry `stateRoot`. | FR-001/FR-002, C1/C3 |
| T-2 | `HealingServeRootRefresh` updates `serveRoot` only — `stateRoot`, frontier, `verificationPassComplete`, pendingTasks unchanged. | FR-001/FR-009, C2 |
| T-3 | A returned node whose keccak ≠ requested hash is dropped, not stored, not counted, task stays pending. | FR-004/SC-004, C4 |
| T-4 | A task no serve root satisfies: not completed; attempt counter increments; resets on `HealingServeRootRefresh`; surfaces past threshold; no `StateHealingComplete`. | FR-006/SC-002, C5 |
| T-5 | Same final healed state ⇒ identical completion outcome (StateHealingComplete + CF `g` marker + state root) via decoupled vs coupled (flag flip). | FR-007/SC-003 |
| T-6 | Feature off ⇒ fetch uses `stateRoot`; behavior byte-identical to today. | FR-008/SC-006, C3 |
| T-7 | Engagement + observability signals (walk root, serve root, cross-root heal count, unservable count) emitted. | FR-010, C9 |
