# Quickstart / Validation Guide: Moving-Root Delta Heal

**Feature**: `009-moving-root-delta-heal` | **Date**: 2026-06-27

Validation runbook (not implementation). See [data-model.md](./data-model.md) and [contracts/internal-contracts.md](./contracts/internal-contracts.md).

## Prerequisites

- ETC consensus review by `forge` complete; implementation merged behind `moving-root-delta-heal` (default on for ETC SNAP).
- Clean compile (`sbt compile-all`) + format (`sbt formatCheck`). **Stop both barad-dûr sync nodes before any `sbt` build** (host-freeze risk).
- Validation host: barad-dûr Mordor secondary (`fukuii-secondary`, RPC 8547), **fresh rocksdb** (preserve `node.key`; mosaic datadirs are out of scope). `mordor.conf`: `checkpoint-sync-file=""`, `do-snap-sync=true`, `deferred-merkleization=false`, gate on. Provision `-Xmx ≥ 6g`.

## Unit / integration validation (before deploy)

```bash
# Single-root fetch + seed-absent-root + delta discovery + re-peg-retains + sound completion gate
sbt "node/testOnly *TrieNodeHealingCoordinatorSpec *SNAPSyncControllerSpec"

# Consensus parity — the hard gate
sbt testCrypto testMPT
sbt testEthereum
```

**Expected**: green; specifically — (C2) absent root is fetched not handed off; (C1) served nodes pass the content gate (no wrong-axis drops); (C3) only the delta is requested; (C4) re-peg retains persisted nodes; (C5) a download-written-present-but-incomplete subtree does NOT reach completion on delta-discovery alone (the pruned descent catches it).

## End-to-end validation (the real proof — fresh Mordor)

1. Stop both sync nodes; `sbt assembly` → quick-docker image → tag `chipprbots/fukuii:latest`.
2. Wipe `fukuii-secondary` rocksdb (keep `node.key`); flip `ops/barad-dur/fukuii-conf-1/base.conf` (and the mordor conf) to `deferred-merkleization=false`; gate on; `-Xmx ≥ 6g`.
3. `docker compose up -d --force-recreate fukuii-secondary`; fresh SNAP from genesis-pivot.
4. Success signature: download completes → heal SEEDS the (served) root and fetches it → **missing-node count shrinks** across rounds → on stale-move the heal **re-pegs** to a fresh served root and the missing count keeps shrinking (retained progress) → **pruned descent** confirms zero absent → SNAP **Completed**, finalize on a canonical header root → block-import **past the pivot** with **no re-snap loop** and **no O(total) BFS / multi-hour re-walk**.
5. Watch for the failure modes: heal not seeding (handoff fires immediately) → C2 regression; missing count not shrinking / nodes dropped → C1/C3 regression; completion declared with a gap → C5 soundness failure (consensus-critical — must not happen).

## A/B replay (parity, SC-005)

Run a fresh Mordor sync twice — gate OFF (spec-004 path) then ON. When each reaches a verified completion, the finalized state root MUST be **byte-identical** and equal to the canonical Mordor header `stateRoot` at the finalize height (cross-check a core-geth node at that block). The ON path must never finalize a *different* root than canonical.

## Success criteria mapping

| Criterion | Check |
|-----------|-------|
| SC-001 | Fresh Mordor SNAP completes (no re-snap loop) in 3/3 trials |
| SC-002 | Finalized root == canonical Mordor header root (core-geth cross-check) |
| SC-003 | No O(total) walk; only delta requested; pruned O(delta) descent |
| SC-004 | Completes even when the initial root is no longer served by the end (re-peg + retained progress) |
| SC-005 | Off vs on → never a different finalized root; `testCrypto`/`testMPT`/`testEthereum` green |
| SC-006 | Heal throughput + non-deferred build CPU/GC recorded on the i5-4430 (`-Xmx ≥ 6g`) |
