# Phase 0 Research: Decoupled Heal Serve-Root

Grounded in a `forge` impact analysis of the current `staging` tree (consensus-adjacent protocol). All file:line citations are from `staging` HEAD.

## Current-state findings (the coupling to break)

- **F-A — Walk root and fetch root are one var.** `TrieNodeHealingCoordinator` holds a single `private var stateRoot: ByteString` (`:40`). The BFS walk (`rebuildFrontierBFS`, seeded via `startVerificationBFS`/`startFrontierBFS` at `:803/:885/:698/:524`) and the missing-node fetch (`GetTrieNodes(rootHash = stateRoot, …)` at `:1078-1083`) both read it. `stateRoot` mutates only in `HealingPivotRefreshed` (`:645`). So walk-root == fetch-root today.
- **F-B — `GetTrieNodes` is path-addressed, not hash-addressed.** `SNAP.scala:553-562`: `(requestId, rootHash, paths: Seq[Seq[ByteString]], responseBytes)`. The node hash (`HealingEntry.hash`) is carried client-side only, for verification; it is never sent. A peer serving root `R` walks `R` down the nibble path and returns whatever node sits there under `R`.
- **F-C — Content verification already exists.** `handleResponse` (`:1129-1137`) computes `keccak256(returnedNode)` and accepts it only if it matches a requested task hash (`taskByHash.contains(nodeHash)`); otherwise it is dropped (`:1174`), not stored, not counted. The store is content-keyed (`:1140`).
- **F-D — Completeness is decided by the walk against the walk root only.** `HealingCheckCompletion` (`:748-806`) declares complete only when `verificationPassComplete` is true, which is set only in `VerificationBFSComplete` (`:812`) after a BFS against `stateRoot` finds `isComplete` (`:1329`). `markComplete()` (`:766`) is on that path. The fetch root is never read by the walk, `isComplete`, `verificationPassComplete`, or `markComplete`.
- **F-E — Hold-pivot has no held-root var.** `#1357` holds the root by *declining to mutate* `stateRoot` (`HealingResumeDispatch` handler `:703-724` touches neither `stateRoot` nor `verificationPassComplete`). Controller side: `SNAPSyncController.scala:1125-1141`.
- **F-F — Serve-root plumbing exists (for storage recovery).** `RequestRecentRoot` → `SyncController.scala:1482-1565` (`recentRootTarget(snapHeights, RecentRootMarginBlocks=64)`) → `RecentRoot(block, stateRoot)` → `StorageRecoveryActor` → `StoragePivotRefreshed`. Reusable as a *template*, not in place.

## Decisions

### D1 — Split `stateRoot` into a fixed walk root + an advancing serve root

- **Decision**: Keep `stateRoot` as the **walk root** (unchanged semantics, the completeness basis). Add a new `private var serveRoot: ByteString = stateRoot` used **only** at the fetch build site (`:1080`). The walk continues to seed from `stateRoot`.
- **Rationale**: F-A/F-D show the walk and completeness already key off `stateRoot`; introducing a separate `serveRoot` for the fetch is the minimal split that satisfies FR-001 (fixed walk root) and FR-002 (servable fetch root) with zero change to the completeness path → FR-007 parity by construction.
- **Alternatives**: (a) mutate `stateRoot` for the fetch and restore it — rejected, races the walk and breaks parity reasoning. (b) raw-hash fetch (root-independent) — rejected, the wire protocol is path-addressed (F-B) and matching core-geth/Besu is required.

### D2 — A new narrow `HealingServeRootRefresh` message; never reuse `HealingPivotRefreshed`

- **Decision**: Add `HealingServeRootRefresh(newServeRoot)` to the coordinator's message set; its handler sets `serveRoot = newServeRoot` and **does nothing else** (no frontier clear, no `verificationPassComplete` reset, no `stateRoot` mutation, no re-seed).
- **Rationale**: `HealingPivotRefreshed` (`:629-701`) deliberately mutates the walk root and resets the completeness state — exactly what FR-001/FR-009 must avoid. A dedicated message keeps the serve-root advance side-effect-free and makes the FR-009 "generalization of hold-pivot" explicit (hold the walk root even across genuine serve-window aging, because healing no longer depends on it being servable).
- **Alternatives**: reuse `HealingPivotRefreshed` with a flag — rejected, too easy to regress the walk-root hold.

### D3 — Serve-root source: reuse the `RecentRoot` bootstrap, pushed to the coordinator

- **Decision**: In `SNAPSyncController`, obtain an advancing servable root via the existing `RequestRecentRoot`→`RecentRoot` bootstrap (F-F) and push it to the coordinator as `HealingServeRootRefresh` on chain advance / serve-window aging. Add a distinct requester slot (or tag) so healing and storage-recovery do not contend on `SyncController`'s single `recentRootRequester`.
- **Rationale**: Reuses proven plumbing (`networkBest−64` header bootstrap), minimal new surface, no new wire protocol.
- **Alternatives**: a brand-new header-fetch path — rejected, duplicates `RecentRoot`.

### D4 — Serve-root selection policy: newest-servable default, with content-check as the safety net (oldest-servable noted as a liveness lever)

- **Decision**: Default the serve root to the **newest servable root** (`networkBest−64`, reusing `RecentRootMarginBlocks`). Rely on the existing content-hash check (F-C) to keep this **safe**, and on FR-006 retry-on-advance to keep it **live**. Document **oldest-still-servable** (closest to the walk root → maximal node sharing) as a tuning lever if shallow-gap liveness is ever observed.
- **Rationale**: The actual post-SNAP gap set is **deep** trie nodes (the upper trie walks with 0 frontier; gaps surface at L8/L9). Deep nodes of rarely-touched accounts are **identical across all recent roots**, so any servable root supplies them; newest-servable reuses existing plumbing with no extra selection logic. forge's shallow-node-divergence concern (nodes near the root change nearly every block) is real in principle but **moot for a deep gap set**, and FR-006 catches the rare non-shared node.
- **Alternatives**: oldest-still-servable as the default — defensible (maximizes sharing) but adds selection logic and is unnecessary for deep gaps; kept as a documented fallback. **This is a flagged decision for user sign-off** (correctness is identical under either due to F-C; only liveness for rare shallow gaps differs).

### D5 — FR-006 unservable-node handling: bounded retry on serve-root advance, surface-only, never force-complete

- **Decision**: Track a per-task attempt counter (side `mutable.Map[hash, Int]`, bounded by the pending-task set). Re-queue unsatisfied tasks as today (`:1184-1189`); **reset the counter on each `HealingServeRootRefresh`** (a serve-root advance is the legitimate retry trigger). After a bounded number of attempts **with no serve-root advance in between**, emit an observable surfacing signal (log + metric) — and **never** route to `HealingForceComplete` (`:614`) or let stagnation-abandon declare `StateHealingComplete` for this case.
- **Rationale**: FR-006 forbids both false completion and silent infinite retry. The content check (F-C) already guarantees a wrong/missing node can never be accepted (no false completion is even possible), so this is purely about *visibility* and *not abandoning*. Neutralizing the force-complete/abandon paths under decoupling preserves SC-002.
- **Alternatives**: force-complete after N attempts (today's escape-hatch instinct) — rejected, it would declare success with a real gap (consensus-unsafe).

### D6 — Config switch `decoupled-heal-serve-root`, default on, safe single-root fallback

- **Decision**: Add `sync.snap-sync.decoupled-heal-serve-root` (boolean). When **off**, `serveRoot` tracking is inert and the fetch uses `stateRoot` → byte-identical to today (SC-006). When **on**, the fetch uses `serveRoot`. Thread through `SnapSyncConfig` + both `props` sites (`:3364/:3429`) + constructor. Default **on** (mirrors `heal-hold-pivot-on-stagnation` and `scoped-heal-verification`; it is the fix for a currently-stuck node and composes with hold-pivot).
- **Rationale**: In-memory only; safe to flip without migration. Default-on is required for it to unblock the production node; the fallback (FR-008) keeps it correct.
- **Alternatives**: default off (opt-in) — rejected for the immediate use case, but the flag makes it trivially reversible.

## Consensus-safety conclusion (forge)

The single load-bearing guardrail is the **content-hash check at `:1137`**: because the fetch is path-addressed (F-B), a serve root may resolve a path to a different-content node, but that node fails the keccak match and is dropped — it can **never** be accepted under the walk root. Therefore: (1) wrong content is never stored (FR-004/SC-004, already enforced); (2) a still-missing node keeps the walk from finding zero → no false completion (FR-005/SC-002); (3) the completion outcome equals the coupled path for the same final stored nodes (FR-007). **This check MUST NOT be weakened or bypassed on any decoupled code path.** No EVM/gas/RLP/block-validation/Ethash/ECIP-1017 code is touched; PoW/block-number dispatch is unaffected; the change is chain-agnostic.

## Open decisions flagged for user sign-off (pre-/speckit-tasks)

1. **D4 serve-root selection** — newest-servable (default, reuses plumbing; safe; fine for deep gaps) vs oldest-still-servable (max sharing; better liveness for rare shallow gaps; more logic).
2. **D5 confirmation** — that the unservable-node response is surface-only (log/metric) with the force-complete/stagnation-abandon paths neutralized under decoupling (no `StateHealingComplete` with a known-missing node).
