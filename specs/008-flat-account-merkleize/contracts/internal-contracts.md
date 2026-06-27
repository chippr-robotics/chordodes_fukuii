# Internal Contracts: Flat-Account Retention & Local State-Root Merkleization

**Feature**: `008-flat-account-merkleize` | **Date**: 2026-06-27

This feature exposes **no external/wire interface**. SNAP wire messages (`GetAccountRange`/`GetStorageRange`) are reused unchanged. The contracts below are *internal* component boundaries the implementation and its tests must honor.

## C1 — Retained Account Store write/read

- **Write** (during account download AND finalize re-fetch): `putAccountsBatch(pairs: Seq[(accountHash, accountRLP)])` persists each pair durably. Overwrites any existing value for a key (so a finalize re-fetch replaces a stale leaf).
- **Read** (at merkleization): `seekFrom(startHash) → Iterator[(accountHash, accountRLP)]` yields keys in **strictly ascending** 32-byte order across the full keyspace, no duplicates, no gaps relative to what was written.
- **Guarantee under test**: writing N accounts then `seekFrom(0x00..00)` returns exactly those N in ascending order (parity with `FlatSlotStorage.seekStorageRange`).

## C2 — Coordinator finalize latch

- **Begin**: a `BeginFinalizing(R_final)` signal sets the coordinator to `finalizing`; while set, `PivotRefreshed`/`StoragePivotRefreshed` are **ignored** (no `rootHash` re-tag, no re-enqueue).
- **End/abort**: an `EndFinalizing`/abort signal restores `normal`.
- **Guarantee under test**: a `PivotRefreshed` delivered while `finalizing` does not change any task's `rootHash` and does not alter the re-fetch target; after `EndFinalizing`, pivot-advance is honored again.

## C3 — Finalize merkleize + verify

- **Input**: the Retained Account Store (full keyspace, ascending) made coherent for `R_final`; the canonical `pivotHeader(R_final)`.
- **Process**: single `StackTrie` pass — for each `(accountHash, accountRLP)` in ascending order, `update(accountHash, accountRLP)`; the account's `storageRoot` is whatever the leaf's RLP carries (not recomputed for the root number).
- **Output**: `computedRoot: 32 bytes`.
- **Gate**: finalize iff `computedRoot == pivotHeader(R_final).stateRoot`. This check is layered **before** the existing `finalizeSnapSync` anchor guard (`snapStateRoot == pivotHeader.stateRoot`), which remains byte-unchanged. The content-hash store gate (`keccak(node)==hash`) is byte-unchanged.
- **Guarantee under test**: (a) for a fixture state, `computedRoot` equals the known canonical root; (b) if any leaf is stale/perturbed, `computedRoot != header` and the node does **not** finalize (fail closed); (c) feature-off vs feature-on never yields two *different* finalized roots.

## C4 — Configuration gate

- A `sync.conf` key (e.g. `flat-account-merkleize` / `local-merkleize-finalize`) defaults **on** for fresh ETC SNAP and gates C1–C3. Off ⇒ identical to today's legacy path (no flat-account write, today's finalize). No-op on the deferred-merkleization path; no ETH/Sepolia effect.

## Fail-closed contract (cross-cutting, FR-004)

Any of: peers stop serving `R_final` mid-re-fetch; `computedRoot != header`; merkleization or store error — MUST result in **no finalize, no partial commit**, and a fallback to (a) retry on a fresher servable `R_final`, or (b) the existing `HealingRootUnservable → completeSnapSync` handoff. Never finalize an unverified or absent root.
