# Fukuii Codebase Audit — COMPLETE

**Branch:** `scala3-cleanup-june`
**Closed:** 2026-06-27
**Archive:** `completed/CODEBASE-AUDIT.md`

All prompts complete. POST-MIGRATION-SWEEP done `82a1e3a43`. Classic residue confirmed zero
outside the three deliberate TCP bridges (`RLPxConnectionHandler:197,235` + `ServerActor`).

**Open items moved to:**
- `SPRINT-QUEUE.md` — Wave 3 Network/P2P sprint (35 remaining Classic actors in devp2p/rlpx)
- `DEFERRED-BACKLOG.md` — externally gated items (§8e BEACON/FORGE return sites, R4 Scala 3.9, dependency upgrades)
- `CHASE-QUEUE.md` — E165 TestProbe (777 sites, test-harness sprint), `SyncProtocol.RegularSyncCommand` seal (E112 cross-file block), `SyncControllerSpec.SyncStateAutoPilot` 7 pre-existing failures
