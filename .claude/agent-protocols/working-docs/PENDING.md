# Fukuii Modernization — Open Items

**Branch:** `scala3-cleanup-june`
**Completed items:** See `completed/PENDING.md` for archived done work.

---

## Chase-Queue Housekeeping — Open

---

## Test Hygiene

- [x] **E165 `expectMsgType[Any]` — COMPLETE** (`8cdf1290d`, 2026-06-22) — 20 sites → 0.
  **Remaining §8a-gated (not actionable until Classic TestKit migration):**
  - 777 Classic `TestProbe` without `[T]` — Classic TestProbe has no type param; fix = §8a migration to Typed ActorTestKit
  - 20 `fishForMessage` E165 sites (11 files) — `PartialFunction[Any, Boolean]` signature; fix = replace with `expectMessageType[T]` after §8a
  - Both counted in the intentional 333 E165 floor; see DEFERRED-BACKLOG §8a research prompt.

---

## Externally Gated

- [ ] **R4 Scala 3.9 readiness** — gates on Scala 3.9 LTS release. Check
  https://endoflife.date monthly. When tagged, run the R4 spec in `WAVE3-RESEARCH-PLAN.md`.

---

## Clearout Prompts

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| ~~A2~~ | ~~Batch A~~ | ~~P1 FORGE PoWMiningCoordinator~~ | ✅ DONE 2026-06-22 |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header. Batch A complete — no remaining prompts in this file.
