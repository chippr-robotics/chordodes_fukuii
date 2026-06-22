# Fukuii Modernization — Pending Tasks

**Branch:** `scala3-cleanup-june`
**Public document — code-pattern observations only. No internal dev commentary.**

Quick-reference checklist of what genuinely remains. See `SPRINT-QUEUE.md` for full
sprint history and `DEFERRED-BACKLOG.md` for the authoritative backlog.

---

## Immediate (no gate)

- [x] **testEssential baseline** — ✅ 3,601/0 (3499+26+11+65), 11:02. Baseline confirmed. Phase 2 in progress.

---

## Behavior[Any] Narrowing — Phase 2

Three actors remain as `Behavior[Any]` and need sealed Command ADTs.
All gates are satisfied (CAPSTONE ✅, S4 ✅, S7 ✅, NET ✅, NET2 ✅).

| # | Actor | File | LOC | Complexity | Status |
|---|-------|------|-----|------------|--------|
| 1 | **NetworkPeerManagerActor** | `network/NetworkPeerManagerActor.scala` | 1,317 | Medium — shell+core, 2 `sender()` paths already wrapped | ✅ `be305095f` — Classic shell (`NetworkPeerManagerShell`) absorbs 9 legacy types, Typed core narrowed to `Behavior[Command]`. 131/131. |
| 2 | **FastSync** | `blockchain/sync/fast/FastSync.scala` | 1,415 | High — 7 `Behavior[Any]` states, PRH children via Classic | ✅ `e41f50b7d` (SNAP2-a) — ADT rebuilt (15 cases); 20 `Behavior[Any]` sites flipped; SyncController cross-file wrap (`private[sync]`); `FastSyncSpec` 3 test sites fixed. |
| 3 | **SyncController** | `blockchain/sync/SyncController.scala` | 2,183 | Very High — 11 named behaviors, `ctx.toClassic.sender()` widespread, `GetStatus/GetProgress` reply-to pattern | To do |

**Order:** NetworkPeerManagerActor → FastSync → SyncController (NET2 → SNAP2 → ROOT).
SyncController must come last — it is the parent of both FastSync and the SNAP coordinator tree.

**Note on SyncController:** After narrowing, the 7 `SyncTest`-tagged spec tests that currently
fail (pre-existing since SNAP2) need a fixing pass. Those tests are excluded from testEssential
so they don't block the baseline, but they gate a PR.

---

## Chase-Queue Housekeeping

- [x] **PivotBlockSelector UnsubscribeAllCmd** — DONE. Cleared entry added to CHASE-QUEUE; open entry removed. S4b (`e82f41cac`) fixed all 5 sites.

- [ ] **PoWMiningCoordinator MUTABLE** — FORGE-gated. Waiting for FORGE review before
  any fix. See `threading-model-audit.md §B2`. No estimated date.

---

## Test Hygiene

- [ ] **E165 TestProbe in FastSyncBranchResolverSpec** — 5 pre-existing E165 warnings
  (pattern selectors on `Any` from unnarrowed TestProbe type param). Defer to a
  dedicated spec-cleanup pass. Not a gate for any migration work.

---

## Externally Gated

- [ ] **R4 Scala 3.9 readiness** — gates on Scala 3.9 LTS release. Check
  https://endoflife.date monthly. When tagged, run the R4 spec in `WAVE3-RESEARCH-PLAN.md`.

---

## Done (reference)

| Area | Completion |
|------|------------|
| Wave 1 (wildcard migration, `-source:future`, warnings) | ✅ 6 commits |
| Wave 2 P0–P3c (compiler warnings, Pekko faucet/jsonrpc/transactions/mining, given/using, extension methods, isInstanceOf, enums, Thread.sleep) | ✅ all committed |
| Network/sync Pekko migration: W1, W2, S1–S7, PLN, NET, NET2, S3, S4, SNAP1, SNAP2, ROOT, CAPSTONE | ✅ root flipped to `ActorSystem[Nothing]` |
| Phase 1 ADT narrowing: S1/S2/S4/S5/S6 + S3 coordinators sealed | ✅ `948a25008` |
