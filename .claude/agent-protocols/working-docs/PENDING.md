# Fukuii Modernization — Open Items

**Branch:** `scala3-cleanup-june`
**Completed items:** See `completed/PENDING.md` for archived done work.

---

## Chase-Queue Housekeeping — Open

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

## Clearout Prompts

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| A2 | Batch A (read-only) | P1 FORGE PoWMiningCoordinator | ✅ run with CODEBASE-AUDIT P1, DEFERRED P4, DEFERRED P6 |
| B3 | Batch B step 3 | P2 MITHRIL E165 TestProbe | Sequential after Batch A; can interleave with other Batch B steps if files don't overlap |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

### P1 — FORGE: PoWMiningCoordinator threading assessment

**Agent:** FORGE
**Files:** `src/main/scala/com/chipprbots/ethereum/consensus/pow/PoWMiningCoordinator.scala`
**Files also read:** `threading-model-audit.md §B2` (rationale)
**Prerequisite:** FORGE review gating — do not send to MITHRIL without FORGE verdict first.

**Prompt:**
> On branch `scala3-cleanup-june`, assess `PoWMiningCoordinator.scala` for thread-safety
> correctness.
>
> Read `threading-model-audit.md §B2` first for the prior finding context.
>
> Evaluate:
> 1. Are the `@volatile` fields correct-as-is, redundant, or masking a race?
> 2. Is `mutex.synchronized` providing correct happens-before guarantees for all access paths?
> 3. Is there any concurrently-callable method that accesses state outside the mutex?
> 4. What is the verdict: SAFE-AS-IS / REDUNDANT-VOLATILE-ONLY / TRUE-RACE-FIX-REQUIRED?
>
> If SAFE-AS-IS or REDUNDANT-VOLATILE-ONLY: note what MITHRIL may safely clean up
> (cosmetic `@volatile` removal, etc.) and whether a compile test is sufficient gate.
> If TRUE-RACE-FIX-REQUIRED: spec the minimal fix — do not implement it here.
>
> No code changes in this prompt. Report verdict + rationale only.

**Verification:** Written verdict returned; no source edits

**Documentation updates when complete:**
- `working-docs/PENDING.md` — remove the PoWMiningCoordinator MUTABLE open item from "Chase-Queue Housekeeping — Open"
- `completed/PENDING.md` — append under new "Chase-Queue Housekeeping — Cleared" heading:
  `- [x] **PoWMiningCoordinator MUTABLE FORGE assessment** — DONE. Verdict: [VERDICT]. See modernization-log/consensus/pow.md.`
- `modernization-log/consensus/pow.md` — move the open item out of "Open"; add under new "Quality Assessment (FORGE)" section:
  `#### FORGE verdict — PoWMiningCoordinator thread-safety: [VERDICT]`
  `- **Rationale:** [one-line from FORGE report]; follow-on: [none required / MITHRIL @volatile removal]`

**Rejection criteria:** Edits to source; assessment without reading threading-model-audit.md §B2

---

### P2 — MITHRIL: Fix E165 TestProbe warnings in FastSyncBranchResolverSpec

**Agent:** MITHRIL
**Files:** `src/test/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSyncBranchResolverActorSpec.scala`
**Prerequisite:** None. Low-risk test-only change.

**Prompt:**
> On branch `scala3-cleanup-june`, fix the 5 pre-existing E165 warnings in
> `FastSyncBranchResolverActorSpec.scala`.
>
> The warnings come from `TestProbe` instances without a narrowed type parameter — pattern
> selectors on `Any` trigger E165 in Scala 3. The fix is to add explicit type parameters
> to `TestProbe[...]` declarations where the probe receives a known message type.
>
> Steps:
> 1. Run: `grep -n "TestProbe\b" src/test/.../FastSyncBranchResolverActorSpec.scala`
>    to locate all 5 unnarrowed sites.
> 2. For each site, determine what message type the probe expects from context.
> 3. Add `TestProbe[MessageType]` type parameter.
> 4. Compile: `sbt compile-all` — confirm E165 count drops by exactly 5; no new errors.
> 5. Run: `sbt testOnly *FastSyncBranchResolverActorSpec` — all existing tests must pass
>    (pre-existing failures expected; confirm count is unchanged at pre-existing baseline).
>
> Do NOT change any production source files.

**Verification:** E165 count −5; test pass count unchanged from baseline

**Documentation updates when complete:**
- `working-docs/PENDING.md` — remove the E165 TestProbe open item from "Test Hygiene"
- `completed/PENDING.md` — append under new "Test Hygiene — Cleared" heading:
  `- [x] **E165 TestProbe in FastSyncBranchResolverSpec** — DONE. 5 probe type params narrowed. Commit: [SHA].`
- `modernization-log/sync/fast.md` — remove the E165 line from "Open / Deferred"; add under "Quality Fixes":
  `#### [SHA] — E165: TestProbe type params narrowed in FastSyncBranchResolverActorSpec`
  `- **What:** 5 unnarrowed TestProbe → TestProbe[MessageType]; E165 count −5, test baseline unchanged`

**Rejection criteria:** Production file edits; adding new test cases; changing test logic
