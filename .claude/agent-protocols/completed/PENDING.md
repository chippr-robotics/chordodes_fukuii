# Fukuii Modernization — Completed Pending Tasks

**Branch:** `scala3-cleanup-june`
**Archived:** 2026-06-22
**Source:** `working-docs/PENDING.md` — completed items extracted here. Open items remain there.

---

## Baseline (confirmed)

- [x] **testEssential baseline** — ✅ 3,601/0 (3499+26+11+65), 11:02. Baseline confirmed.

---

## Behavior[Any] Narrowing — Phase 2 ✅ COMPLETE

| # | Actor | Status |
|---|-------|--------|
| 1 | **NetworkPeerManagerActor** | ✅ `be305095f` |
| 2 | **FastSync** | ✅ `e41f50b7d` |
| 3 | **SyncController** | ✅ `2a2d77166`+`00cf1bed1`+`0d8adfd5c` |

testEssential post-Phase-2: ✅ passed.

**Remaining gate for PR:** 7 `SyncTest`-tagged `SyncControllerSpec` tests ✅ `ba7dcb14b` — all raw `SyncProtocol.*` sends wrapped across 3 spec files (43 total). 84/84 + 34/34. PR gate cleared.

---

## Chase-Queue Housekeeping — Cleared

- [x] **PivotBlockSelector UnsubscribeAllCmd** — DONE. Cleared entry added to CHASE-QUEUE; open entry removed. S4b (`e82f41cac`) fixed all 5 sites.
- [x] **PoWMiningCoordinator MUTABLE FORGE assessment** — DONE. Verdict: SAFE-AS-IS. See modernization-log/consensus/pow.md.

---

## Done (reference)

| Area | Completion |
|------|------------|
| Wave 1 (wildcard migration, `-source:future`, warnings) | ✅ 6 commits |
| Wave 2 P0–P3c (compiler warnings, Pekko faucet/jsonrpc/transactions/mining, given/using, extension methods, isInstanceOf, enums, Thread.sleep) | ✅ all committed |
| Network/sync Pekko migration: W1, W2, S1–S7, PLN, NET, NET2, S3, S4, SNAP1, SNAP2, ROOT, CAPSTONE | ✅ root flipped to `ActorSystem[Nothing]` |
| Phase 1 ADT narrowing: S1/S2/S4/S5/S6 + S3 coordinators sealed | ✅ `948a25008` |
| Phase 2 ADT narrowing: NET2/SNAP2/ROOT (`Behavior[Any]` → `Behavior[Command]`) | ✅ testEssential passed |
| Phase 3 shell removal: NPMA-callers + NPMA-shell-removal + test fixes | ✅ `36f8cdf49`+`e7d2587e0`+`039ca75e3` — shell deleted, test probes updated, testEssential running |
