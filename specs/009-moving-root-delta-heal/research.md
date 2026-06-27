# Phase 0 Research: Moving-Root Delta Heal

**Feature**: `009-moving-root-delta-heal` | **Date**: 2026-06-27 | **Reviewer**: `forge` (ETC consensus)

**Overall verdict**: **GREEN with one named constraint.** The design is feasible and fits fukuii's existing machinery better than the three prior failed attempts — the two hardest pieces already exist: the re-peg path (`completePivotRefreshWithStateRoot` → `HealingPivotRefreshed`, which already moves the walk root to a canonical header root and already seeds an absent root) and delta discovery (`discoverMissingChildren`, already geth-equivalent, already invoked per healed node). Two spec premises were corrected (Decisions 1 and 5 below).

---

## Decision 1 — The heal walks the SERVED root; it does NOT need a local coherent root (corrects FR-001)

**Decision**: The moving-root delta heal targets a probe-confirmed **served** root (`head−64`) and uses the local mosaic nodes only as a content-addressed **cache**. It does NOT require a local coherent root at heal start. **FR-001 is re-worded accordingly.**

**Rationale / evidence**:
- The non-deferred (`deferred-merkleization=false`) path produces a **mosaic, not a coherent root**: `AccountRangeCoordinator.handleStoreAccountChunk` commits per-task *fragment* roots that explicitly "do NOT match the pivot's claimed root" (`:1440-1452`); `finalizeTrie()` returns the *claimed* `stateRoot` verbatim, computing nothing (`:1573-1599`, comment `:1587-1589`); `PivotRefreshed` re-tags only `pendingTasks`, never re-fetching `completedTasks` (`:611-619`). So after any mid-download advance, no single header root equals the on-disk trie. The in-code claim that the non-deferred path "writes the root" (`SNAPSyncController.scala:4876-4878`) is **factually wrong** (it writes fragment roots).
- This does **not** sink the design, because **a root node is fetchable**: `GetTrieNodes(rootHash=S, paths=[[emptyPath]])` returns S's root node, whose `keccak == S`, so the content gate (`TrieNodeHealingCoordinator.scala:1519`) accepts it. geth's model is exactly this: `trie.Sync` is seeded with the root hash; `Missing()` returns the root itself first if absent; it is fetched from a peer that serves S; local nodes are a cache that lets `Missing()` prune present subtrees.
- **fukuii already does this on the re-peg path**: `HealingPivotRefreshed:943-947` enqueues `HealingEntry(Seq(emptyPath), newStateRoot)` when `!isNodeInStorage(newStateRoot)`. The bug is that `StartTrieNodeHealing:632` does **not** (its absent-root `else` branch `:715-744` hands off to `HealingRootUnservable` instead of seeding). The contradiction between these two sites is the live wall.

**Fix**: change `StartTrieNodeHealing`'s absent-root branch to **seed the root as a frontier task** (as `HealingPivotRefreshed:943-947` already does) and fetch it against the served root S; never hand off as the default. The heal root must be S = a served `head−64` root, never the mosaic's claimed pivot root.

**Alternatives considered**: requiring a local coherent root (would force a freeze/rebuild — spec-008's failed approach; unnecessary). Keeping the absent-root handoff as default (the current wall — healed=0).

## Decision 2 — Single heal root for completeness AND fetch (the spec-004 wrong-axis fix)

**Decision**: Use one root (`stateRoot`) for both the completeness target and the node fetch. Replace `requestNextBatch`'s `rootHash = if (decoupledHealServeRoot) serveRoot else stateRoot` (`:1446-1451`) with `rootHash = stateRoot`.

**Rationale**: completeness is *already* judged against the single walk root `stateRoot` (`:77-78`, `:1445`); the only defect is that the *fetch* targeted `serveRoot` while the content gate expected walk-root child hashes — so served nodes' keccak ≠ the expected hash → dropped → healed=0. Collapsing fetch to `stateRoot` makes the gate match **by construction**. Under spec 009, `stateRoot` IS the moving served root.

**Alternatives considered**: keeping the spec-004 split (cannot heal — the live failure).

## Decision 3 — Re-peg the single root on stale-move, reusing existing machinery

**Decision**: When the head advances past `healRoot + ~64` (or peers report it unservable), re-peg `stateRoot` to a fresh served root via the EXISTING `completePivotRefreshWithStateRoot` → `HealingPivotRefreshed(newStateRoot)` path (`SNAPSyncController.scala:4028-4183`). Concretely: route the staleness trigger (`maybeRequestHealingServeRoot` math, `:3651-3654`) and the `HealingServeRoot(blockNumber, rootOpt)` handler (`:636-651`) to emit `HealingPivotRefreshed(root)` (move the walk root) instead of `HealingServeRootRefresh(root)` (move serve root only) when the flag is on.

**Rationale**: the re-peg is already wired and already targets a canonical header `stateRoot` (`:4047`); `HealingPivotRefreshed:920-930` clears only in-memory frontier state, never deletes persisted trie nodes (`clearPersistedFrontier:920` touches only the optional frontier-mirror CF, not the node CF) — so re-pegging retains all verified nodes. Content-addressed nodes are ~99.9% shared across nearby roots, so the post-re-peg delta only shrinks.

## Decision 4 — `discoverMissingChildren` is the sole DISCOVERY mechanism (already geth-equivalent)

**Decision**: Make top-down `discoverMissingChildren` (`:2285-2417`) the sole frontier-discovery mechanism; retire `rebuildFrontierBFS` (`:1810-2134`) as a discovery seed (keep it gated only for crash-restart frontier rebuild).

**Rationale**: `discoverMissingChildren` already decodes each healed node and enqueues ONLY absent hash-referenced children (`:2311-2381`), invoked per healed node (`:1562`) — it is the geth `scheduler.Missing()` / besu `getChildRequests()` analogue. Seeding it from the root (Decision 1) + draining via `requestNextBatch`/`dispatchIfPossible` drives it to `pending==0`. Nothing new is needed here.

## Decision 5 — Keep a PRUNED final descent for completion soundness (corrects FR-003/SC-003)

**Decision**: Completion = (a) delta-discovery drained `pendingTasks`/`activeRequests` to empty against `healRoot_N`, AND (b) a **pruned descent walk** rooted at `healRoot_N` reads every reachable node and finds zero absent. **Keep the final descent; retire only the O(total) FIRST BFS. FR-003/SC-003 are re-worded.**

**Rationale (the named constraint — consensus safety)**: pure `pending==0` from delta-discovery alone is **unsound in fukuii** (unlike geth). `discoverMissingChildren` does NOT descend into a child that is already present on disk (`:2329/2346/2379` add to `presentChildren`, not descended; documented gap at `:956-957`, `:1100-1102`). In geth this is safe because the healer is the SOLE writer (present ⟹ fully scheduled). In fukuii the **download** wrote mosaic fragments BEFORE the heal, so "present on disk" does NOT imply "subtree complete" — a fragment node can have grandchildren from a different lineage that are absent. Pure delta-discovery would stop at the present node and declare `pending==0` with a real hole = **consensus-unsafe false completion**.
- The current code already encodes the sound gate: `HealingCheckCompletion` (`:1059-1097`) requires `isComplete && verificationPassComplete`; `verificationPassComplete` is set only after a descent BFS finds zero missing (`VerificationBFSComplete:1134-1138`, `startVerificationBFS:2212-2243`). Re-peg sets `verificationPassComplete=false` (`HealingPivotRefreshed:935`) + the `walkRoot==stateRoot` guard (`:787-797`) excludes a stale completion against a new root — so a re-peg forces a fresh descent before completion.
- **Cost**: use the spec-005 PRUNED oracle (`:1878-1883`, `:2224-2233`) — durably-recorded-complete subtrees are pruned, so the descent is O(missing-frontier), not O(total).
- Block-import then succeeds: `finalizeSnapSync` anchor guard (`:4496-4506`) requires `snapStateRoot == pivotHeader.stateRoot`; the heal root is always a canonical header `stateRoot` (`:4047`); the descent proved the whole trie for it is on disk → import finds every node locally.

**Re-wording**: FR-003 keeps "no O(total) walk / no O(total) verification walk" but MUST allow a **pruned O(delta) final descent**. SC-003 → "no O(total) walk; a pruned O(delta) descent is required for soundness against the pre-populated download mosaic."

## Decision 6 — Config + supersession; consensus safety

- New gate `moving-root-delta-heal=true` (`base/sync.conf` + `SNAPSyncConfig` near `:5222`, passed to `TrieNodeHealingCoordinator.props`). ON = single-root + seed-absent-root + re-peg-via-HealingPivotRefreshed + pruned final descent. OFF = byte-identical to today (spec-004 path) for A/B (FR-007).
- `deferred-merkleization` already `false` in `base/sync.conf:74`; flip the ops file `ops/barad-dur/fukuii-conf-1/base.conf:354` (`true`→`false`) for the live test (ops, out of PR).
- Supersede spec-004 (`decoupledHealServeRoot` OFF when the new flag is on: `serveRoot`, `HealingServeRootRefresh`, the `requestNextBatch` serve-root selection all inert — keep one release for A/B, remove in follow-up). Retire spec-008 `beginFinalizeRefetch` (gate its trigger OFF; keep US1/US2 as safe foundation). `HealingRootUnservable` handoff → bounded last-resort fallback (FR-001/FR-008), never fail-open.
- **Consensus safety — confirmed no rule change**: content-hash gate (`:1504-1519`) byte-untouched (now matches by construction); anchor guard (`:4496-4506`) byte-untouched; deterministic (fixed decode order; pure local descent; finalized root == canonical header). Hard gate before merge: crypto+MPT+ethereum-tests byte-for-byte + A/B replay (finalized root == canonical Mordor header `stateRoot`, cross-check core-geth).

## Residual risk (empirical, for the live A/B)
Does pruned-descent + delta-fetch converge inside core-geth's serve cadence on a live Mordor pool? The herald probe pre-cleared GetTrieNodes serving (broader than GetAccountRange); the non-deferred inline build's CPU/GC cost on the 4-core host (historic bottleneck) needs `-Xmx ≥ 6g` and an A/B throughput measure. These are SC-006 report-and-record, not parity gates.
