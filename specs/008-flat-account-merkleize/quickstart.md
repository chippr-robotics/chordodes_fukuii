# Quickstart / Validation Guide: Flat-Account Retention & Local State-Root Merkleization

**Feature**: `008-flat-account-merkleize` | **Date**: 2026-06-27

This guide proves the feature end-to-end. It is a validation runbook, not implementation. See [data-model.md](./data-model.md) and [contracts/internal-contracts.md](./contracts/internal-contracts.md) for details.

## Prerequisites

- ETC consensus review by `forge` complete; implementation merged behind the config gate (default-on fresh ETC SNAP).
- A clean compile: `sbt compile-all`. Format: `sbt formatCheck`.
- Validation host: the barad-dûr Mordor secondary (`fukuii-secondary`, RPC 8547). **Both barad-dûr sync nodes must be stopped before any `sbt` build** (host-freeze risk). The wedged node CANNOT be reused — its account leaves were never retained; a **fresh** rocksdb is required (preserve `mordor.checkpoint.gz` + `node.key`).

## Unit / integration validation (fast — run before deploy)

```bash
# Retained Account Store: ordered write+enumerate parity (C1)
sbt "node/testOnly *FlatAccountStorageSpec"

# Finalize merkleize + verify + fail-closed (C3); coordinator freeze latch (C2)
sbt "node/testOnly *SNAPSyncControllerSpec *AccountRangeCoordinatorSpec *StorageRangeCoordinatorSpec"

# Consensus parity — the hard gate (must be byte-for-byte)
sbt testCrypto testMPT
sbt testEthereum            # ethereum/tests compliance
```

**Expected**: all green; `FlatAccountStorageSpec` proves N-written == N-enumerated ascending; a perturbed-leaf fixture proves `computedRoot != header ⇒ no finalize`; feature-off vs feature-on produce an identical finalized root on the fixture.

## End-to-end validation (the real proof — fresh Mordor A/B)

1. Stop both sync nodes; build the fat-jar (`sbt assembly`) → quick-docker image → tag `chipprbots/fukuii:latest`.
2. Wipe `fukuii-secondary` rocksdb (keep checkpoint + node.key); `mordor.conf` with `checkpoint-sync-file=""`, `do-snap-sync=true`, `deferred-merkleization=false`, feature gate **on**.
3. `docker compose up -d --force-recreate fukuii-secondary`; fresh SNAP from genesis-pivot.
4. Watch for the success signature:
   - account download retains leaves (flat-account write active);
   - at finalize: a freeze on one `R_final`, a bounded re-fetch pass (~11 min on a healthy Mordor pool), `[finalize] computedRoot == header` (verified), SNAP **Completed**;
   - transition to regular block-import **past the pivot** with **no re-snap loop** and **no `MissingAccountNode`** stall.
5. **Negative/fail-closed check**: if peers stop serving `R_final` mid-pass or verification fails, confirm the node does **not** finalize and falls back (retry on fresher `R_final` or the existing handoff) — never a divergent root.

## A/B replay (parity, SC-003)

Run the same fresh Mordor sync twice — gate **off** (legacy) then **on**. Assert the finalized state root, when each path reaches a verified completion, is **byte-identical** and equals the canonical Mordor header `stateRoot` at the finalize height (cross-check against a core-geth node at the same block). The on-path must never finalize a *different* root than the canonical one; at worst it fails closed.

## Success criteria mapping

| Criterion | Check |
|-----------|-------|
| SC-001 | E2E completes (no re-snap loop) in 3/3 fresh Mordor trials |
| SC-002 | Finalized root == canonical Mordor header root (core-geth cross-check) |
| SC-003 | Feature off vs on → never a different finalized root (A/B replay) |
| SC-004 | 0 finalizes on unverified/absent root (incl. peers-stop-mid-pass negative test) |
| SC-005 | `testCrypto`/`testMPT`/`testEthereum` green with feature on |
| SC-006 | Finalize merkleization time + allocation recorded on the i5-4430 host |
