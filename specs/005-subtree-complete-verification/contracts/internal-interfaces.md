# Phase 1 Contracts: Subtree-Complete Heal Verification (internal interfaces)

Internal sync-orchestration + storage change — no external/wire API. The contracts are the new storage record API, the verification pruning oracle, the SNAP/heal seeding, the (untouched) completion chokepoint, config, and metrics. Citations are `staging` HEAD; line numbers are anchors, not guarantees — locate by symbol.

## C1 — Subtree-complete record API (HealingFrontierStorage, CF `'g'`) — NEW

```
// HealingFrontierStorage.scala — additive; key prefix 0x01 ++ hash (33 bytes), value 0x01
def markSubtreeComplete(hash: ByteString): Unit              // write record (caller guarantees post-persist ordering)
def isSubtreeComplete(hash: ByteString): Boolean             // read one
def multiIsSubtreeComplete(hashes: Seq[ByteString]): Set[ByteString]  // batch, mirrors multiGetNodes chunk pattern
```
Contract: keys on the **bare keccak hash** with a non-32-byte prefix that cannot collide with a 32-byte node hash or the 21-byte `CompleteMarkerKey` (`:64-69`). `loadAll`'s frontier filter (`:48`) MUST be extended to exclude the new prefix. Records are additive, monotone, **never cleared** (root-independent, D1). No new CF.

## C2 — Verification pruning oracle (descend-and-stop) in `rebuildFrontierBFS`

```
// TrieNodeHealingCoordinator.rebuildFrontierBFS, per-child descent (~:1602)
// BEFORE enqueuing a present child X to descend:
if (prunedHealVerification && storageScheme == Hash && store.isSubtreeComplete(X)) {
   // verified leaf — DO NOT enqueue X's children; ++prunedSubtrees
} else {
   // descend X as today (read, decode, check its children)
   // if X's subtree closes with zero missing descendants -> store.markSubtreeComplete(X)
}
```
Contract (the load-bearing change, FR-001/FR-004): prune **iff** present AND recorded-complete AND Hash scheme AND flag on. A present-but-not-recorded node is **descended** (never pruned). `markSubtreeComplete(X)` is written only from a **genuine zero-missing closure** of X's descent — never from a "queue drained" that a `visitedLru` (`:1549`) eviction could fake (D2 guard). The set of `FrontierRebuilt` emissions is unchanged ⇒ byte-parity (D4/C5).

## C3 — Seed records on the heal write path (FR-003)

```
// heal: discoverMissingChildren — stage X into pendingSubtreeRecords when X is confirmed to have zero still-missing
//   DESCENDANTS: no missing/pending direct child AND every present child (incl. an account leaf's storageRoot) is
//   itself recorded subtree-complete (the inductive step, via multiIsSubtreeComplete). Write the staged records in
//   writeDurableSubtreeRecords, called ONLY AFTER mptStorage.persist() (D3 record-after-persist).
// completion: at the HealingCheckCompletion chokepoint, after a genuine global zero-missing closure and the
//   node-byte flush, markSubtreeComplete(stateRoot) (gated on prunedEnabled).
```
Contract: this is what makes the **first** verification O(missing) (FR-003). Records are seeded only for durably-committed, fully-present subtrees — established inductively (every present child recorded-complete first), never for a partially-fetched subtree.

> **REMOVED (unsound, forge review 2026-06-19): the former SNAP account-`StackTrie` fragment seed.** Recording an account-fragment root at `AccountRangeCoordinator` commit is unsound — the account leaves' storage tries are not yet downloaded (SNAP does accounts before storage), so the record could prune a still-missing storage node and falsely complete. The `AccountRangeCoordinator` seeding + threading were removed; sound *storage*-fragment seeding is a deferred follow-up. The heal-side inductive closure (above) never produces such a record.

## C4 — Crash-safety ordering (FR-006)

```
// Write order (mirror unpersistFrontier post-persist rule, :489-490/:510-511):
//   1. children node-bytes (CF 'n') committed in a WriteBatch
//   2. THEN markSubtreeComplete(parent) (CF 'g') via ordinary update() — WAL-ordered
//   3. THEN (only at terminal completion) markComplete() via updateSync() — fsync (RocksDbDataSource.scala:128)
```
Contract: a crash between any two stages leaves the later record absent ⇒ restart **descends** ⇒ safe (FR-006). The order "record before its subtree's bytes are durable" is structurally forbidden. The terminal completeness marker is fsync-durable (upgrade `markComplete()` at `:908/:643` to `updateSync`).

## C5 — Completion chokepoint (UNCHANGED — byte-parity)

```
// HealingCheckCompletion (:889-912): verificationPassComplete (set once, :953, after a 0-missing BFS)
//   -> markComplete (:906-911) -> StateHealingComplete (:912).  The walk does NO state writes (:1526-1527).
```
Contract (FR-005): the pruned walk routes through the SAME single chokepoint, emitting the SAME terminal messages. Given the invariant holds, it emits the SAME set of missing nodes (zero, for completion) ⇒ identical decision, identical marker bytes (`Array[Byte](1)`), identical state root. **No new completion site, no new marker set-point.**

## C6 — Fetched-node content-hash guardrail (UNCHANGED — FR-008)

```
// handleResponse (~:1290): keccak256(returnedNode) == requested task hash before store. PRESERVED VERBATIM.
```
Contract: spec 005 changes how **present** nodes are verified for *subtree presence*; it does NOT change how **fetched** nodes are trusted for *content*. The spec-004 guardrail is untouched.

## C7 — Config (NEW)

```
# sync.conf, snap-sync block (alongside scoped-heal-verification ~:165)
pruned-heal-verification = true     # FR-007; off ⇒ existing full-trie verification (byte-identical)
```
Threaded through `SnapSyncConfig` + `TrieNodeHealingCoordinator.props`/constructor + `SNAPSyncController`. Effective only under `storageScheme == Hash` (D5); Path scheme OR flag off OR no records ⇒ full walk (`startVerificationBFS` `:1873`, unchanged).

## C8 — Metrics (NEW, FR-009)

Additive `app_`-prefixed gauges via `SNAPSyncMetrics` (reuse the spec-003 scoped pattern, `:962`): pruned-subtree count, nodes-visited count, pruned-verification elapsed ms, plus an engagement flag.

## Test contracts (deterministic, ScalaTest/TestKit — no `Thread.sleep`)

| # | Asserts | Maps to |
| --- | --- | --- |
| T-1 | Verification with `isSubtreeComplete(X)` recorded does NOT descend X (no reads of X's children); visits O(missing), reaches `StateHealingComplete`. | FR-001/SC-001/SC-006, C2 |
| T-2 | A present node X with NO record AND a missing descendant: verification descends X, emits the missing node, does NOT complete. | FR-002/FR-004/SC-002, C2 |
| T-3 | Crash safety: children bytes committed but record absent ⇒ on restart the node is descended (not pruned), reconciles. Record written only post-`persist`. | FR-006/SC-004, C4 |
| T-4 | Parity: same final healed state ⇒ identical `StateHealingComplete` + identical CF `'g'` marker bytes + identical state root via pruned vs full walk (flag flip). | FR-005/SC-003, C5 |
| T-5 | Fresh-node seeding: after SNAP fragment commits + heal subtree closures recorded, the first verification prunes the recorded subtrees (no prior full walk). | FR-003/SC-001, C3 |
| T-6 | Fallback: flag off OR `storageScheme==Path` OR no records ⇒ full-trie verification, byte-identical to today. | FR-007/SC-005, C2/C7 |
| T-7 | Fetched node with `keccak256 != requested hash` is still dropped (guardrail intact); engagement + pruned/visited metrics emitted. | FR-008/FR-009, C6/C8 |
| T-8 | Never record-complete from an abandoned StackTrie fragment (reset) or a truncated (LRU-evicted) descent. | FR-006/D2 guard, C2/C3 |
