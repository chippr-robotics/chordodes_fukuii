# Fukuii Codebase Audit — Post Phase 2 Narrowing (Open Items)

**Branch:** `scala3-cleanup-june`
**Completed sweep results + resolved prompts:** See `completed/CODEBASE-AUDIT.md`

---

### S5 — Test quality gaps (EYE)

**EYE sweep DONE 2026-06-22.** Baselines established: 3 known wall-clock files + `SnapServerLimitsSpec` borderline (in CHASE-QUEUE); 2 pre-existing `Thread.sleep` sites (NECESSARY); **777 unnarrowed `TestProbe` E165 sites / 83 files** (deferred to test-harness cleanup sprint — tracked in CHASE-QUEUE); 0 suppressed tests.

---

### DEFERRED — items with external gates or planned sprint scope

The following findings are tracked but blocked on external conditions. No prompt needed now.

| Finding | Gate | When to action |
|---------|------|----------------|
| **AkkaTaskOps dead methods** — `askFor`/`askForVia` (Classic) have zero callers; only `askForTyped` used | Minor cleanup; safe to delete now | Any sprint |

*(W7 ✅ DONE. INFO-13 ✅ DONE `913c22363`. INFO-14 ✅ DONE. S3-B/S3-E/INFO-8 ✅ DONE.)*

---

## Clearout Prompts

### Opportunistic Clearout Protocol (applies to every prompt below and in all working docs)

Every prompt that touches source files must apply this before committing:

1. **Scan open items while files are open.** Check `CHASE-QUEUE.md` open entries and all Clearout Prompt sections across working docs for items that appear in the *same files you're already modifying*. Assess each match:

   | Scope | Action |
   |-------|--------|
   | Same risk tier, no new files, < ~15 min extra | **Fix inline** — extend the current commit message to note it |
   | Same files, slightly larger but still bounded | **Append to this prompt** — add the fix steps below the current work and address before committing |
   | New files required, different risk tier, or > ~30 min extra | **Draft a new clearout prompt** — write a complete prompt entry (Agent / Files / Prompt / Verification / Documentation updates) in the appropriate working doc's Clearout Prompts section, using your current context. The next thread can run it without re-researching. Do NOT just add a CHASE-QUEUE entry — write the full prompt. |

2. **As you work, if you discover NEW actionable items** not in any log:
   - Apply the same inline / append-to-prompt / draft-new-prompt assessment.
   - If inline or append: fix it and note it in the commit.
   - If new prompt: draft the full clearout prompt entry in the appropriate working doc before finishing your current work. Your in-context knowledge of the finding is the most valuable part — capture it now.

3. **Never silently skip a finding.** Either fix it or draft a prompt for it. A fully drafted prompt in the queue is worth more than a brief CHASE-QUEUE entry with no actionable steps.

---

**Completed batches A–G:** All done 2026-06-22–24 — see `completed/CODEBASE-AUDIT.md`.

**Active run order — this file:**
| # | Prompt | Status |
|---|--------|--------|
| ~~G1~~ | ~~Behavior[Any] narrowing sprint~~ | ✅ DONE 2026-06-26 — zero `Behavior[Any]` confirmed in main sources |
| ~~G2~~ | ~~SNAP S3-E: mutable task types → immutable~~ | ✅ DONE 2026-06-26 — S3-B/S3-E/INFO-8 all resolved |
| ~~D1~~ | ~~INFO-13: RegularSync LoggingAdapter~~ | ✅ DONE `913c22363` |
| ~~D2~~ | ~~SNAP S3-E conversion~~ | ✅ DONE 2026-06-26 — task classes all `val` |
| ~~§8e-SNAP1~~ | ~~SNAPSyncController return clearout (62 sites)~~ | ✅ DONE ca1446e49 |
| POST-MIGRATION-SWEEP | Final Classic residue sweep | Gate: D1 (INFO-13) + AkkaTaskOps deletion |

**Final gate**: **POST-MIGRATION-SWEEP** — confirm zero non-TCP Classic residue; delete AkkaTaskOps dead methods.

---

### Gate-Lifting Prompts

These prompts satisfy the external gate conditions for D1 and D2. Run after the testEssential gate in Batch D.

---

#### ~~G1~~ — Behavior[Any] narrowing sprint ✅ DONE 2026-06-26

**Verified 2026-06-26:** `grep -rn "Behavior\[Any\]" src/main/ --include="*.scala" | grep -v "//"` → zero results. All actors in the network/P2P sprint have been narrowed to sealed `Behavior[Command]` ADTs. D1 gate lifted for the Behavior[Any] component.

---

#### ~~G2~~ — SNAP cleanup sprint ✅ DONE 2026-06-26

**Verified 2026-06-26:**
- S3-B: null `filePath` guard gone
- S3-E: `AccountTask`, `StorageTask` all fields `val` (case class immutable)
- INFO-8: `refreshFreshRootCache` removed (resolved during SNAP1 migration — function never existed in final form)

D2 gate lifted. S3-B/S3-E/INFO-8 rows removed from DEFERRED table.

---

### Deferred Sprint Prompts

These items have external gates and cannot be actioned until those gates open. Prompts are pre-drafted here so each sprint-opening thread can pick them up without re-researching.

---

#### D1 — ✅ DONE 913c22363

*(W7 ✅ DONE. INFO-14/BlockImporter ✅ DONE. INFO-13 ✅ DONE `913c22363`.)*

---

#### ~~D2~~ — SNAP Sprint: S3-E task types ✅ DONE 2026-06-26

S3-B ✅ DONE 2026-06-22. INFO-8 ✅ DONE (resolved during SNAP1 migration). S3-E ✅ DONE 2026-06-26 — all task class fields confirmed `val`.

---

#### §8e-SNAP1 — SNAPSyncController return statement clearout

**Gate:** SNAP1 migration (4-phase Pekko Typed migration of SNAPSyncController) ✅ DONE — commits `959584e17`–`4d6fcdf6a`. Gate lifted 2026-06-26.
**Agent:** MITHRIL
**Files:** `blockchain/sync/snap/SNAPSyncController.scala`

**Finding:** 62 non-annotated `return` statements remain in `SNAPSyncController.scala` after SNAP1 migration. The `noReturns` scalafix rule is ratcheted; these sites must be cleared to eliminate suppressions.

**Step 0 — Worktree setup (run from main checkout before anything else):**
```bash
git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/8e-snap1 -b wt/8e-snap1 scala3-cleanup-june
# All remaining steps run in: /media/dev/2tb/dev/fukuii/.claude/worktrees/8e-snap1
cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8e-snap1
```

**Pre-flight:**
```bash
grep -c "\breturn\b" src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala
```
Confirm count ~62. If 0: already done, stop.

**Step 1 — Categorize returns by pattern:**
```bash
grep -n "\breturn\b" src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala
```
Group by type:
- Early-return guards (`if condition → return value`): convert to if/else
- Return from `IO` context: `IO.pure(Left(...))` in if/else branch
- Return in for-comprehension body: convert to explicit `else` or `Option` flatMap
- Return inside `match` terminal branch: drop `return`, already terminal

**Step 2 — Apply in batches of ~15 sites:** For each batch:
1. Remove `return`, restructure as if/else or match branch
2. `sbt compile-all` — must be green before next batch
3. Do NOT change any SNAP protocol logic — only restructure control flow

**Step 3 — Verify scalafix clean:**
```bash
sbt scalafixAll
```
Must complete without `noReturns` violations.

**Step 4 — Verify tests:**
```bash
./local/scripts/fukuii-test SNAPSync
```

**MANDATORY final step:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala`
3. `git commit -m "chore(8e-snap1): SNAPSyncController — remove return statements, scalafix clean"`
4. `SHA=$(git rev-parse --short HEAD)`
5. Update run-order table: `~~§8e-SNAP1~~ ✅ DONE $SHA`
6. `git add .claude/` → `git commit -m "docs(8e-snap1): clearout — $SHA"`
7. **Merge back + teardown (from `/media/dev/2tb/dev/fukuii`):**
   ```bash
   cd /media/dev/2tb/dev/fukuii
   git merge --no-ff wt/8e-snap1
   git worktree remove .claude/worktrees/8e-snap1
   git branch -d wt/8e-snap1
   ```

**Rejection criteria:** Changing SNAP protocol message types or coordinator logic; leaving any `return` in place without `@nowarn` + DEFERRED rationale.

---

#### POST-MIGRATION-SWEEP — Final Classic Residue Verification

**Gate:** AkkaTaskOps deleted.
**Agent:** MITHRIL
**Status (verified 2026-06-26):** BRIDGE-A ✅ zero. BRIDGE-B ✅ one TCP-floor site (`RLPxConnectionHandler:323`, permanent). BRIDGE-C ✅ TCP-layer refs only. `Behavior[Any]` ✅ zero. W7 ✅ DONE. INFO-13 ✅ DONE `913c22363`. INFO-14 ✅ DONE. **AkkaTaskOps** ❌ open (`askFor`/`askForVia` dead methods).

**Step 0 — Worktree setup:**
```bash
git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/akka-dead -b wt/akka-dead scala3-cleanup-june
cd /media/dev/2tb/dev/fukuii/.claude/worktrees/akka-dead
```

**Step 1 — Delete AkkaTaskOps Classic dead methods:**
`askFor` at `:15` and `askForVia` at `:24` have zero callers. Delete both extension methods and their imports (`org.apache.pekko.pattern.ask`, `ActorRef.noSender`). `sbt compile-all` — must be clean.

**Step 2 — Final sweep greps:**
```bash
grep -rn "toClassic\.sender()" src/main/ --include="*.scala" | grep -v "//"          # must be 0
grep -rn "ctx\.self\.toClassic" src/main/ --include="*.scala" | grep -v "RLPxConn"   # must be 0
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala" | grep -v "//"               # must be 0
grep -rn "extends Actor\b" src/main/ --include="*.scala"                               # must be 3 (TCP floor)
grep -rn "LoggingAdapter\|classicSystem" src/main/ --include="*.scala"                # must be 0
```

**Step 3 — Commit + merge:**
```bash
sbt scalafmtAll
git add src/main/scala/com/chipprbots/ethereum/jsonrpc/AkkaTaskOps.scala
git commit -m "chore: delete AkkaTaskOps Classic dead methods (askFor/askForVia)"
SHA=$(git rev-parse --short HEAD)
# Update POST-MIGRATION-SWEEP status in this file; archive to completed/CODEBASE-AUDIT.md
git add .claude/
git commit -m "docs(post-migration-sweep): Classic residue confirmed zero — $SHA"
# Merge back (from /media/dev/2tb/dev/fukuii):
cd /media/dev/2tb/dev/fukuii
git merge --no-ff wt/akka-dead
git worktree remove .claude/worktrees/akka-dead
git branch -d wt/akka-dead
```
