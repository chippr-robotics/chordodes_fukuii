# Phase 0 Research: Flat-Account Retention & Local State-Root Merkleization

**Feature**: `008-flat-account-merkleize` | **Date**: 2026-06-27 | **Reviewer**: `forge` (ETC consensus)

**Overall verdict**: **YELLOW — feasible with one named hard constraint.** Converges in-window on Mordor (the validation target). ETC mainnet at 86M-account scale remains genuinely YELLOW and must be *validated*, not assumed (peer pool must hold one servable root for the full re-fetch pass). Falls back to checkpoint import if a servable root cannot be held.

---

## Decision 1 — How finalize obtains each account's correct final-pivot leaf (the feasibility gate)

**Decision**: **Mechanism C — pivot-freeze + targeted re-fetch against one servable `R_final`**, with `FlatAccountStorage` retention as the streaming substrate. **Mechanism A (full re-fetch) is the in-window fallback and already fits.** The retained flat-account write is *necessary but not sufficient* — it is the substrate the re-fetch overwrites and the merkleizer streams; it is **not** itself the coherence mechanism.

**The pivotal fact**: an account leaf carries `storageRoot` **directly** as RLP field 3 (`Account.scala:32`, decoded `:47`). So the correct final-pivot `storageRoot` arrives *inside the re-fetched leaf*. The state-root computation reads `storageRoot` off the leaf — it does **not** re-merkleize per-account storage to obtain it. (Storage slots must still be made coherent on disk for later block import, but that is a separate concern from the state-root number.)

**Rationale (with Mordor numbers)**:
- The stale-leaf problem is real and **not storage-only**: `AccountRangeCoordinator.PivotRefreshed` (`:611-619`) re-tags only `pendingTasks`; completed ranges are never re-fetched. An account whose `{nonce,balance,storageRoot,codeHash}` changed across the pivot advance has a stale retained leaf, detectable only by re-reading the leaf (a storage-recompute-and-compare misses balance/nonce/code-only changes).
- Re-fetch into `FlatAccountStorage` **skips the inline trie build**, so it runs at raw `AccountRange` throughput (2000–6000/s) — the legacy 758/s figure included the per-range trie build. 2.71M / 4000 ≈ **11.3 min** (≈22.6 min worst-case at 2000/s) vs the ~28-min serve window (~128 blocks × 13 s). **Fits with margin.**
- Storage delta: only contracts whose `storageRoot` changed across ~128 blocks need slot re-fetch — a small minority of ~1.2M Mordor slots; bounded well under the remaining window margin.
- The single `StackTrie` pass is byte-exact: ascending-only `update` (`StackTrie.scala:66`) + canonical `hash()` (`:76-85`), a faithful go-ethereum `stacktrie.go` port. Fed every leaf in ascending keccak order it yields *the* canonical state root. The legacy inline path already computes this — just fragmented into 16 per-task tries (`finalizeTrie:1573-1599` explicitly returns the *claimed* root, "no single computed root"). The feature unfragments it.

**Alternatives considered**:
- **Mechanism A (full re-fetch)**: fits the window (above). Kept as the simple fallback; C is "A, but skip ranges whose completion root already equals `R_final`." In practice most of the 16 ranges are stale at finalize, so C ≈ A in volume — fine, since A already fits.
- **Mechanism B (delta-only re-fetch, identify changed set cheaply)**: **REJECTED.** Without `GetTrieNodes` there is no servable cheap way to enumerate the changed accounts — a `GetAccountRange` range-proof mismatch identifies a changed *range* not its leaves (resolving it = re-fetch the range = C); tx-bounding from the delta blocks is unsound (bodies/receipts backfill is partial during SNAP, and touched-accounts include SELFDESTRUCT/CREATE/coinbase/uncle-reward mutations a tx-sender/recipient scan misses). B degenerates to C or is unsound.
- **Per-pivot leaf/slot versioning (D)**: requires having fetched each leaf at `R_final` — that *is* the re-fetch; no saving.
- **Heal the mosaic (status quo)**: provably blocked (peers don't serve `GetTrieNodes` for the absent root) — the thing this feature exists to escape.
- **Checkpoint import**: real, already-operational, but a different feature (not local merkleization); remains the operational unblock today and the RED fallback.

## Decision 2 — Retain account leaves during download (the substrate)

**Decision**: write `flatAccountStorage.putAccountsBatch(...)` in `AccountRangeCoordinator.handleStoreAccountChunk` (`:1397-1399`), where `(accountHash, account)` pairs are already in hand (today only `trie.update(...)` consumes them). Symmetric to `StorageRangeCoordinator`'s `flatSlotStorage.putSlotsBatch` (`:602/684`).

**Rationale**: `FlatAccountStorage.putAccountsBatch` (`:50`) exists but has **zero production callers** — only the slot variant is wired (the smoking-gun asymmetry). `FlatAccountStorage.seekFrom(startHash)` (`:60-70`) gives strictly-ascending enumeration over the 32-byte keccak keyspace via the same `RocksDbDataSource.seekFrom` primitive `FlatSlotStorage.seekStorageRange` uses — so the full-keyspace ordered finalize pass is supported by the existing schema.

**Alternatives considered**: re-fetch *everything* at finalize with no retention (Mechanism A in its pure form) — works in-window but wastes the bandwidth of re-downloading unchanged leaves; retention makes C possible and reduces re-fetch to the stale fraction.

## Decision 3 — The named hard constraint: a real `finalizing` freeze latch

**Decision**: add a `finalizing` latch in **both** `AccountRangeCoordinator` and `StorageRangeCoordinator` that **ignores pivot-advance** (`PivotRefreshed`/`StoragePivotRefreshed`) for the duration of the finalize re-fetch.

**Rationale**: today those handlers are honored unconditionally and would re-tag the re-fetch mid-stream, re-introducing the very mosaic the feature eliminates → **RED without the latch**. Precedent exists — the account coordinator already ignores `PivotRefreshed` during async trie finalization (`AccountRangeCoordinator.scala:929-930`). The re-fetch verifies proofs against `R_final` (the worker snapshots `expectedRoot` per request — `AccountRangeWorker.scala:121-136`), so it only succeeds while peers still serve `R_final`; if they stop → fail closed (FR-004), retry on a fresher `R_final`.

## Decision 4 — The finalize gate (consensus safety)

**Decision**: after the single-pass merkleization, require `computedRoot == pivotHeader.stateRoot` for `R_final`'s block, as an **additional** check layered before the existing anchor guard (`SNAPSyncController.scala:4496-4507`, `snapStateRoot == pivotHeader.stateRoot`). On mismatch → do **not** finalize; fail closed (FR-003/FR-004). The content-hash store gate stays byte-untouched.

**Rationale**: makes completion safe-by-construction — a wrong root can never be finalized, so enabling the feature never produces a *divergent* finalized root (SC-003/SC-004), only the same verified root or a fail-closed non-finalize.

## Confirmations (file:line)

- **Add-site / ordered enumeration**: `AccountRangeCoordinator.scala:1397` (write), `FlatAccountStorage.scala:60` (`seekFrom`). Confirmed schema supports the ordered full-keyspace pass.
- **storageRoot source at finalize**: read off the leaf (`Account.scala:32`), **not** re-derived from `FlatSlotStorage` — corrects the spec's wording ("obtain storageRoot from FlatSlotStorage" is the wrong source for the root number). `StorageRangeCoordinator.commitAccountTrie:504-521` already computes-and-compares per-contract roots for storage coherence (mismatch logged, not fatal).
- **Single-pass canonical root**: `StackTrie` ascending-only builder + canonical `hash()` — confirmed faithful port.
- **Crash/restart**: `FlatAccountStorage`/`FlatSlotStorage` are RocksDB-backed (`Storages.scala:50-52`), persist across restart. Caveat: account restart logic (`:263-277`) treats completed ranges as done and relies on the (broken) heal reconciler — so resume alone does NOT yield a coherent flat set; coherence comes solely from the finalize-time freeze+re-fetch. The plan must not assume resume bookkeeping yields coherence.

## What would flip the verdict to RED

If peers cannot hold *any* single servable `R_final` for the ~11–23 min (Mordor) re-fetch pass — e.g. a 2-snap-peer pool that pivots faster than the pass completes (cf. peer-scarce collapse). Mordor healthy pool: the ~28-min window covers the ~11-min pass → GREEN-leaning. ETC mainnet (86M accounts, ~20-min+ budget, possibly thin pool): genuinely YELLOW — validate, do not assume; fall back to checkpoint import there if needed.
