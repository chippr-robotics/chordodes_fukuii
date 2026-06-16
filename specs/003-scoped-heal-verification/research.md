# Phase 0 Research: Scoped Post-Heal Verification

**Feature**: `003-scoped-heal-verification` | **Branch**: `003-scoped-heal-verification`
(based on staging `v0.7.13`) | **Spec**: [spec.md](./spec.md)

This feature scopes the post-SNAP heal completion verification to re-traverse ONLY the subtrees
rooted at the nodes healed this round, instead of re-seeding the state ROOT and re-walking the whole
~90M-node trie (~16-20h on slow storage), with a **mandatory full-root fallback** whenever the
rebuild walk did not establish full-trie coverage. Byte-for-byte completeness parity with the
full-root verification (**FR-007**) is the binding consensus invariant.

All file:line citations are against
`src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`
unless otherwise noted (henceforth `TNHC`).

---

## R1 — Healed-path capture

**Decision**: Accumulate a bounded, per-round in-memory set `healedPathsThisRound:
mutable.LinkedHashMap[ByteString, HealingEntry]` (keyed by node hash, value = the full
`HealingEntry(pathset, hash)`) populated at the **single point where a node is confirmed healed**:
the hash-matched branch of `handleResponse` (`TNHC:1063-1088`), immediately beside
`totalNodesHealed += 1` (`TNHC:1081`) and the existing `discoverMissingChildren(nodeData,
task.pathset)` call (`TNHC:1088`). Each entry stores the **same `task.pathset`** the coordinator
already holds for that node — no new derivation.

**Rationale**: The healed node's trie path is already materialized and authoritative at this exact
site. `HealingEntry.pathset` (`TNHC:68`) is the GetTrieNodes path encoding, defined in
`Messages.scala:233-237`:

- Account-trie node: `Seq(compact_path)` — one HP-encoded (HexPrefix) nibble path from the **state
  root** to the node.
- Storage-trie node: `Seq(accountHash32, compact_storage_path)` — the 32-byte account hash plus one
  HP-encoded path from that account's **storage root** to the node.

This is exactly the `(hash, pathset)` pair that `rebuildFrontierBFS` already seeds and expands
(`TNHC:1434` enqueues `(startHash, startPathset, isStor)`; `TNHC:1339-1340` decodes
`HexPrefix.decode(pathset.last)` to recover the nibble prefix and extends it per child). So a healed
node's `pathset` is a drop-in BFS seed that re-anchors the walk at the healed node and lets it extend
into the healed subtree using the identical child-path arithmetic. `isStorage` is recoverable from
`pathset.size > 1` (the same test `discoverMissingChildren` uses at `TNHC:1608`).

The capture point is correct because `handleResponse` is the only path that increments
`totalNodesHealed` for network-served nodes (`TNHC:1081`); the crash-recovery rebuild and the
verification walk do not "heal" — they classify. Capturing at the heal site means the set is exactly
"nodes whose bytes this coordinator wrote to storage this round," which is precisely the set whose
subtrees the scoped verification must re-confirm.

**Alternatives considered**:
- *Derive paths by diffing the persisted frontier CF `g` before/after the round* — rejected: CF `g`
  is keyed by hash with the pathset as value (`HealingFrontierStorage:30-35`), and entries are
  deleted on heal-flush (`TNHC:375,396`), so a post-round read cannot recover healed paths; it holds
  only the still-outstanding set.
- *Capture at `discoverMissingChildren` / `queueNodes`* — rejected: those record DISCOVERED
  (still-missing) nodes, not HEALED nodes. The scope must be the nodes whose bytes are now present,
  so their subtrees can be re-walked from local storage.
- *Re-derive the path from the node hash at verification time* — impossible: the trie is
  content-addressed by hash; a hash does not carry its path. The path must be captured when known.

---

## R2 — Full-coverage precondition

**Decision**: The scoped path is authorized ONLY when the persisted completeness marker proves a
prior full-trie walk ran clean against the **current** `stateRoot`. Concretely, the precondition is:

> `healingFrontierStorage` is defined AND `store.isComplete == true`
> (`HealingFrontierStorage:61`) at the moment the completion gate fires.

The marker is set at exactly two clean-walk completion sites and is the project's existing,
already-consensus-reviewed "the rebuild walk traversed the full trie with 0 frontier outside the
healed regions" proof:

1. `FrontierRebuildComplete` (`TNHC:510-517`): the **full-state** rebuild BFS
   (`startFrontierBFS(root, emptyPath, isStor=false, …)`, seeded at the state root with the empty
   path, `TNHC:486`) walked the entire trie to a 0-size frontier and called `store.markComplete()`.
2. The `verificationPassComplete == true` arm of `HealingCheckCompletion` (`TNHC:725-730`): a
   verification BFS classified every locally-held node from the root and found `isComplete`
   (`pendingTasks ∪ activeRequests` empty, `TNHC:1244-1245`), then `store.markComplete()`.

What proves coverage: `rebuildFrontierBFS` is a level-order BFS from a single seed
(`TNHC:1433-1516`). Reaching the `while (levelStart < levelEnd)` exit (`TNHC:1439`) means every level
drained to empty — i.e. every reachable node was classified `Some` (present, children enqueued) or
`None` (missing → emitted as frontier, `TNHC:1342-1345`). A clean full-root walk that produced 0
frontier and left `pendingTasks ∪ activeRequests` empty therefore proves: **every node reachable
from the state root is present in local storage.** That is the load-bearing fact spec Assumption 1
relies on and the precise basis FR-004a requires.

The marker is consensus-adjacent and already guarded: it is cleared on any invalidation —
`HealingForceComplete` (`TNHC:582`, via `clearPersistedFrontier` → `store.clearComplete()`,
`TNHC:232`) and a differing-root `HealingPivotRefreshed` (`TNHC:608`). A same-root
`HealingPivotRefreshed` is an early-return no-op that preserves the marker (`TNHC:594-598`). So
`isComplete == true` at gate time implies the marker has NOT been invalidated since it was set
against the current root.

**Restart interaction**: the marker persists in CF `g`
(`Namespaces.HealingFrontierNamespace`, `HealingFrontierStorage:30`). On restart,
`StartTrieNodeHealing` (`TNHC:417-488`) reads it: a complete-and-empty snapshot
(`store.isComplete && loaded.isEmpty`, `TNHC:446-458`) skips the full-state walk and runs
verification directly. **However, the in-memory `healedPathsThisRound` set does NOT survive
restart** (it is actor field state, like `pendingTasks`/`pendingHashSet`). Therefore a restart that
lands in this arm has the precondition (marker) but NOT a scope (healed set) — see R4: this case MUST
fall back to full-root verification. The precondition alone is necessary but not sufficient; a valid
scope is independently required.

**Alternatives considered**:
- *Treat "verification BFS just ran clean once" (in-memory `verificationPassComplete`) as the
  precondition* — rejected: it does not survive restart and is reset on every differing-root pivot
  (`TNHC:621`); the durable marker is the canonical, restart-safe proof and is already the gate the
  full-root path uses.
- *Re-derive coverage by re-walking* — defeats the purpose (that IS the full-root walk).

---

## R3 — Scoped seeding

**Decision**: Generalize the single-seed walk to a multi-seed walk. Today `startFrontierBFS`
(`TNHC:1530-1571`) and `startVerificationBFS` (`TNHC:1583-1587`) take one `(root, rootPath)` and call
`rebuildFrontierBFS(root, Seq(rootPath), …)`, which clears the queue and enqueues exactly one entry
(`TNHC:1433-1434`). The minimal change:

1. Add an overload `rebuildFrontierBFS(seeds: Seq[(ByteString, Seq[ByteString], Boolean)], …)` that
   replaces the single `queue.enqueueBatch(Seq((startHash, startPathset, isStor)))` (`TNHC:1434`)
   with `queue.enqueueBatch(seeds.map { case (h, ps, stor) => (h.toArray, ps.map(_.toArray), stor) })`
   and calls `markIfNew(h)` for **each** seed hash (replacing the single `markIfNew(startHash)` at
   `TNHC:1287`). Everything downstream — level expansion (`TNHC:1439-1516`), child-path arithmetic
   (`TNHC:1356-1409`), frontier emission via `FrontierRebuilt` (`TNHC:1472`), the bounded visited set
   (`TNHC:1279-1286`), backpressure (`TNHC:1471`) — is **unchanged**. The seeds are just the BFS
   level-0 frontier instead of `{root}`.
2. Keep the existing single-seed signature as a thin wrapper over the multi-seed one (so the
   full-root / crash-recovery / pivot-reseed callers are byte-identical).
3. Add `startScopedVerification(seeds: Seq[HealingEntry])` (sibling of `startVerificationBFS`) that
   maps each `HealingEntry` to `(hash, pathset, pathset.size > 1)` and launches the multi-seed walk
   with the `() => selfRef ! VerificationBFSComplete` onComplete, reusing `verificationBFSRunning`,
   the shared `bfsQueue`, and the same `effectiveParallelism` clamp (`TNHC:1546-1552`).

Because each seed's `pathset` already encodes its full path-to-node (R1), the per-child nibble
extension at `TNHC:1358-1363,1376-1381` produces correct descendant paths starting from each healed
node — the walk recurses fully into each healed subtree (FR-003) and emits any deeper missing node
exactly as the full-root walk would for that subtree (`TNHC:1342-1345`).

**Rationale**: This reuses the entire proven traversal kernel; the only new behavior is the level-0
content. The visited set, frontier emission, FrontierRebuilt → queueNodes flow, completeness gating
(`VerificationBFSComplete` → `HealingCheckCompletion`) are untouched, which is what makes parity
(R5/FR-007) tractable to argue.

**Alternatives considered**:
- *A separate scoped-walk method* — rejected: duplicates ~250 lines of consensus-critical traversal,
  doubling the surface that could diverge from the full-root walk. A shared kernel with a different
  seed set is strictly safer for FR-007.
- *Seed the verification with the state root but prune to healed subtrees* — rejected: pruning logic
  is exactly the kind of "narrow the scope and risk skipping an un-walked region" US2 forbids; it
  re-reads the whole upper trie anyway (no speedup) and adds a skip-bug surface.

---

## R4 — Fallback conditions

**Decision**: The completion gate uses the scoped path ONLY when ALL of the following hold;
otherwise it falls back to the existing full-root verification (`startVerificationBFS(stateRoot,
emptyPath)`, `TNHC:741`), with no semantic change to that path. Fall back when ANY of:

| # | Condition | Detection site | Why |
|---|-----------|----------------|-----|
| F1 | Scoping disabled by config | `!snapSyncConfig.scopedHealVerification` (R6) | FR-008 — operator force-conservative |
| F2 | Full-coverage precondition unproven | `healingFrontierStorage` undefined OR `!store.isComplete` (R2) | FR-005 — no basis to trust the rest of the trie |
| F3 | Healed-paths set empty / lost on restart | `healedPathsThisRound.isEmpty` | FR-005/edge "restart mid-verification" — no scope; in-memory set did not survive restart |
| F4 | Healed-paths set exceeds the bound | `healedPathsThisRound.size > maxScopedHealedPaths` (R6) | FR-011 — bounded memory; large set ⇒ scoped ≈ full anyway |
| F5 | Pivot root changed during the round | the set is recorded against a root ≠ current `stateRoot` | FR-009 — never verify against a stale root |
| F6 | Zero-coverage / fresh node | subsumed by F2 (no marker ⇒ no precondition) | FR-005/edge "zero-coverage" |

F5 detail: the set must carry the root it was recorded against. Two sub-cases:
- A **differing-root** `HealingPivotRefreshed` (`TNHC:599-661`) already clears coordinator round
  state and the marker; the scoped set MUST be cleared there too (it is now stale). After the clear,
  F2 (no marker until a fresh clean walk) AND F3 (empty set) both independently force fallback —
  belt and suspenders.
- A **same-root** `HealingPivotRefreshed` (`TNHC:594-598`) is a no-op; the set and marker stay valid
  — no fallback needed, which is the hold-pivot stability the spec relies on (spec Assumption 3,
  `#1357`).

A guard at the gate (`recordedRoot == stateRoot`) is the final, explicit F5 check independent of the
refresh handlers, so even a missed clear cannot verify against a stale root.

**Rationale**: Each fallback maps 1:1 to a spec FR/edge; the conditions are all cheap, local boolean
reads on the actor thread at the gate. Crucially, fallback is to the **unchanged** existing
full-root verification — the conservative path is the one already validated in production, so a
fallback can never be less safe than today.

**Alternatives considered**:
- *Persist the healed-paths set to survive restart* — rejected for this increment: it adds a new
  durable schema (consensus-adjacent) for marginal benefit (a restart mid-heal is rare and the
  fallback is correct, just slower). The complete-and-empty restart arm already skips the full-state
  rebuild and runs verification; the only cost of F3-fallback is that this one verification is
  full-root rather than scoped. Deferred behind `[NEEDS CLARIFICATION: persist healed-paths set?]` —
  not required for correctness.
- *On F4 (over-bound), heal-then-scope a truncated set* — rejected: a truncated scope can skip a
  healed subtree (false complete). Over-bound MUST fall back to full-root (FR-011).

---

## R5 — Consensus parity (FR-007)

**Decision / argument**: A scoped verification that (a) confirms the full-coverage precondition (R2)
and (b) finds 0 missing in all healed subtrees yields a byte-identical completion decision, state
root, and completeness marker as the full-root walk. The argument:

**The completion decision is a pure function of two facts**, computed at the same chokepoint for both
paths:
1. *Coverage*: "every node reachable from `stateRoot` is present in local storage."
2. *Cleanliness*: `isComplete` (`pendingTasks ∪ activeRequests` empty, `TNHC:1244-1245`) at the
   set-point.

The full-root walk establishes (1)∧(2) by walking the WHOLE trie clean. The scoped path establishes
the SAME (1)∧(2) by **composition**:
- The precondition (R2 marker) establishes (1) for the entire trie **as of the clean rebuild/verify
  walk**, i.e. every node then-reachable was present.
- Between that clean walk and the scoped verification, the ONLY mutations to local state are the heal
  writes of this round — `storeRawNodes` in the flush path (`TNHC:372,394`), keyed by content hash.
  A content-addressed write cannot make a previously-present node absent (writes are additive; the
  trie is immutable/append-only per node hash). So the set of nodes that could have a newly-reachable
  missing descendant is exactly the set of healed nodes (a healed branch/extension/leaf may reference
  a child not yet present). **The healed-paths set is exactly that set** (R1).
- The scoped walk re-walks every healed subtree to completion (R3/FR-003) and finds 0 missing
  (FR-004b). Therefore no healed node has a missing descendant. Combined with the precondition (the
  rest of the trie was already proven fully present and is unchanged), **every node reachable from
  `stateRoot` is present** — identical to (1) from the full-root walk.
- (2) is read at the same gate (`HealingCheckCompletion`, `isComplete`) for both paths.

Both paths then route through the **single completion chokepoint** `HealingCheckCompletion`'s
`verificationPassComplete` arm (`TNHC:715-731`): same `store.markComplete()` (`TNHC:726`), same
`flushRawNodesSync()` (`TNHC:716`), same `snapSyncController ! StateHealingComplete` (`TNHC:731`).
The scoped path sets `verificationPassComplete = true` via the **same** `VerificationBFSComplete`
handler (`TNHC:745-754`) the full-root path uses — only the seed set differed.

**The state root is never recomputed or rewritten by either path.** Verification is a pure local READ
that classifies present/missing (`mptStorage.multiGetNodes`, `TNHC:1325`); it writes nothing to state
storage. The healed bytes are content-addressed and identical regardless of which verification
discovered/confirmed them. Therefore the post-completion state root is byte-identical (it is
`stateRoot` itself, unchanged), and the persisted marker is the same 1-byte sentinel at the same key
(`HealingFrontierStorage:55,69`).

**The exact invariant** (mirrors spec 002 VR-1): *the marker may be SET only when (1) coverage holds
for the full trie AND (2) `isComplete`.* The scoped path establishes (1) by precondition-∘-scoped-clean
composition; the full-root path establishes (1) directly. Same predicate, same set-point, same gate.

**How it could be violated, and how the design prevents it**:
- *V1 — scope misses a healed node.* If a healed node were absent from `healedPathsThisRound`, its
  subtree wouldn't be re-walked → a deeper gap could be missed → false complete. Prevented by
  capturing at the single heal site (R1): the set is exactly `{nodes for which totalNodesHealed was
  incremented}`.
- *V2 — precondition stale (rest of trie changed since the clean walk).* Prevented because the only
  inter-walk mutations are this round's content-addressed heal writes (additive), and any
  differing-root pivot clears the marker (R2) → fallback (F2).
- *V3 — scope verified against a stale root.* Prevented by F5 (root-tag guard) + the differing-root
  refresh clearing both marker and set.
- *V4 — scoped walk declares clean while a healed subtree still has an outstanding gap.* Prevented by
  FR-006 / the existing gate: a missing node emits `FrontierRebuilt` → `queueNodes` →
  `pendingTasks.nonEmpty` → `isComplete == false` (`TNHC:755-761`), so `verificationPassComplete` is
  NOT set and completion is not declared until a re-run finds 0 missing.
- *V5 — restart drops the set but keeps the marker.* Prevented by F3: an empty/lost set forces
  full-root verification; the marker alone never authorizes scoped completion.

---

## R6 — Configuration

**Decision**: Add a boolean switch `scoped-heal-verification` mirroring
`heal-hold-pivot-on-stagnation` exactly, plus a bound key.

- `sync.conf` (`src/main/resources/conf/base/sync.conf`, in the `snap-sync` block beside
  `heal-hold-pivot-on-stagnation` at line 155): `scoped-heal-verification = true` and
  `scoped-heal-max-paths = 200000`.
- `SNAPSyncConfig` field (`SNAPSyncController.scala:4781` region):
  `scopedHealVerification: Boolean = true` and `scopedHealMaxPaths: Int = 200000`.
- Parse (`SNAPSyncController.scala:4907-4910` region, the same `hasPath`-guarded idiom):
  `scopedHealVerification = if (snapConfig.hasPath("scoped-heal-verification"))
  snapConfig.getBoolean("scoped-heal-verification") else true`, likewise for the int.
- Thread the two values into `TrieNodeHealingCoordinator.props` / constructor (mirroring how
  `healingFrontierStorage`, `healingTraversalParallelism`, etc. are threaded, `TNHC:1794-1839`).

**Recommended default: ENABLED (`true`) with mandatory fallback.**

**Justification**: The fallback (R4/FR-005) makes "enabled" provably no less safe than the full-root
path — every case where the precondition is unproven, the scope is empty/lost/over-bound, or the
root changed routes to the **unchanged** full-root verification. The feature's entire value
(SC-001/SC-005: multi-hour → sub-minute on the critical path to a synced node) only accrues when it
is on by default; opt-in would leave every production node paying the full re-walk. This mirrors the
project's prior consensus-adjacent default choice for `heal-hold-pivot-on-stagnation = true`
(`SNAPSyncController.scala:4781`, sync.conf:155), which is the immediately-upstream fix this feature
builds on (spec Assumption 3). The bound (`scoped-heal-max-paths = 200000`) is generous against the
typical ~100-200 healed nodes (spec SC-001) yet bounds the in-memory set's worst case (200K ×
`HealingEntry` ≈ tens of MB, far under the frontier high-water budget of 100K outstanding entries the
node already tolerates, sync.conf:141). `[NEEDS CLARIFICATION: confirm 200000 bound vs heap budget on
the 6 GB reference host — recommend validating against a real over-bound round before shipping.]`

**Alternatives considered**:
- *Opt-in (`false`) until production-validated* — defensible (consensus-adjacent), but the mandatory
  fallback already provides the safety opt-in would buy, and shipping dark forfeits SC-005 on every
  node. Recommend enabled; the switch lets an operator force-conservative instantly if a regression
  is suspected (US3 AS2).
- *Reuse `state-validation-enabled`* (sync.conf:160) — rejected: that gate controls WHETHER the
  controller validates state at all after healing (`SNAPSyncController.scala:4911`); it is orthogonal
  to HOW the coordinator's pre-completion verification is scoped.

---

## Consensus-safety analysis

This change is **consensus-adjacent**, not consensus-critical: it touches no EVM, gas, RLP, block,
reward, or signing code, and it never recomputes or rewrites a state root. It changes only *which
nodes the pre-completion verification reads* before the coordinator declares `StateHealingComplete`.
The binding invariant is FR-007 (byte-for-byte parity of the completion decision, state root, and
marker). The safety case rests on four pillars:

1. **Single completion chokepoint, unchanged.** Both scoped and full-root paths declare completion
   only through `HealingCheckCompletion`'s `verificationPassComplete` arm (`TNHC:715-731`), setting
   the same marker and sending the same `StateHealingComplete`. The scoped path adds NO new
   completion site (cf. spec 002's discipline of a single set-point).
2. **Verification is a pure local read.** No state write occurs during verification
   (`multiGetNodes`, `TNHC:1325`); the only writes are content-addressed heal flushes, which are
   additive and cannot remove a present node. So a clean-walk coverage proof for the unchanged regions
   remains valid across the round.
3. **Composition is sound and bounded.** coverage(full trie) = coverage(rest-of-trie, by
   precondition) ∧ coverage(healed subtrees, by scoped clean walk). The healed-paths set is exactly
   the set of nodes whose presence changed (R1), so no region with a possibly-new gap is excluded.
4. **Fail-safe default.** Every uncertainty (no marker, lost/empty/over-bound set, root change,
   disabled) falls back to the existing, already-validated full-root walk (R4). The optimization can
   only ever do LESS work than full-root on a provably-already-complete remainder; it can never
   declare completion the full-root walk would not.

Residual consensus risk is confined to V1 (scope-capture completeness). It is mitigated by capturing
at the single authoritative heal site (`TNHC:1063-1088`) and is directly testable: a unit test that
heals N nodes and asserts `healedPathsThisRound.size == N` with the exact `(hash, pathset)` pairs.

## Restart / pivot-change safety

- **Restart mid-round (in-memory set lost).** `healedPathsThisRound` is actor field state and does
  NOT persist (like `pendingTasks`/`pendingHashSet`). On restart, `StartTrieNodeHealing`
  (`TNHC:417-488`) may land in the complete-and-empty arm (marker set, frontier empty, `TNHC:446-458`)
  and run verification — but with an empty scope, F3 forces **full-root** verification. Correct (just
  slower for that one pass); no false completion is possible because the marker alone never authorizes
  scoped completion (V5).
- **Differing-root pivot refresh** (`HealingPivotRefreshed`, `newStateRoot != stateRoot`,
  `TNHC:599-661`): already clears round state and the marker (`clearPersistedFrontier` →
  `clearComplete`, `TNHC:608,232`). The scoped set MUST be cleared here too. Afterward, F2 (no marker)
  and F3 (empty set) both force fallback until a fresh clean walk re-establishes the precondition
  against the NEW root. Never verifies against a stale root (FR-009).
- **Same-root pivot refresh** (`TNHC:594-598`): early-return no-op; marker, frontier, and scoped set
  all preserved. This is the hold-pivot stability (`#1357`, spec Assumption 3) that keeps the scoped
  set valid for the duration of a heal round, which is why scoping is usable in the common case.
- **`HealingForceComplete`** (`TNHC:576-588`): abandons the round, clears the marker
  (`clearPersistedFrontier`, `TNHC:582`); the scoped set is irrelevant (completion is declared
  WITHOUT verification on the abandonment path, which sets NO marker via the verified arm — it
  bypasses `HealingCheckCompletion`). The scoped set MUST be cleared here for hygiene; it changes no
  decision.
- **Force-roll / watchdog paths** (`TNHC:807-812,830-831,844-855`): these re-run verification or
  request a refresh but do not by themselves invalidate the current root; the scoped gate's F2/F5
  guards (live reads at gate time) remain authoritative regardless of which path armed the gate.

## Open items / [NEEDS CLARIFICATION]

- **N1**: Persist the healed-paths set to make a restart-mid-verification resume scoped rather than
  fall back to full-root (R4 F3)? Deferred — correctness is unaffected (fallback is correct); it is a
  performance-only enhancement adding consensus-adjacent durable schema. Recommend deferring past this
  increment.
- **N2**: Confirm `scoped-heal-max-paths = 200000` against the 6 GB reference-host heap budget with a
  real over-bound round before shipping (R6).
