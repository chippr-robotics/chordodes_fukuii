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
| **INFO-13** — Classic `LoggingAdapter` in `RegularSync.scala:69` | §7c supervision sprint or next inline opportunity | Address inline when RegularSync is open |
| **AkkaTaskOps dead methods** — `askFor`/`askForVia` (Classic) have zero callers; only `askForTyped` used | Minor cleanup; safe to delete now | Any sprint |

*(W7 ✅ DONE — NPMA restructured to top-level class by LOOM migration, no inner class to extract. INFO-14 ✅ DONE — BlockImporter clean. S3-B/S3-E/INFO-8 ✅ DONE — see D2 gate note.)*

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
| D1 | INFO-13 only: `RegularSync.scala:69` Classic `LoggingAdapter` | Open — address inline during §7c supervision sprint |
| ~~D2~~ | ~~SNAP S3-E conversion~~ | ✅ DONE 2026-06-26 — task classes all `val` |
| §8e-SNAP1 | SNAPSyncController return clearout (62 sites) | UNBLOCKED — gate lifted (SNAP1 migration done) — see prompt below |
| POST-MIGRATION-SWEEP | Final Classic residue sweep | Gate: D1 (INFO-13) + AkkaTaskOps deletion |

**Final gate**: **POST-MIGRATION-SWEEP** — confirm zero non-TCP Classic residue; delete AkkaTaskOps dead methods; fix INFO-13.

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

#### D1 — INFO-13 only: RegularSync Classic LoggingAdapter

**Gate:** None — address inline during §7c supervision sprint when `RegularSync.scala` is open, or earlier if convenient.
**Agent:** LOOM or MITHRIL (single-file change)
**Files:** `blockchain/sync/regular/RegularSync.scala`

**Finding:** Line 7 imports `org.apache.pekko.actor.{Logging, LoggingAdapter}` and line 69 has:
```scala
val log: LoggingAdapter = Logging(ctx.system.classicSystem, classOf[RegularSyncImpl])
```

**Fix:** Remove the `LoggingAdapter` import and declaration. Add `with LazyLogging` to `RegularSyncImpl` (or the enclosing class). Replace all `log.xxx(...)` calls with SLF4J `logger.xxx(...)` per `logging-standards.md`. Grep for `log\.` in the file to catch all usages.

**Verification:** `sbt compile-all`. `grep -n "LoggingAdapter\|classicSystem" src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSync.scala` → 0 results.

**MANDATORY final step:**
1. `sbt scalafmtAll`
2. `git add blockchain/sync/regular/RegularSync.scala`
3. `git commit -m "chore(d1): RegularSync — replace LoggingAdapter with LazyLogging (INFO-13)"`
4. `SHA=$(git rev-parse --short HEAD)`
5. Remove `INFO-13` row from DEFERRED table; add `✅ DONE $SHA`
6. `git add .claude/` → `git commit -m "docs(d1): INFO-13 clearout — $SHA"`

*(W7 ✅ DONE. INFO-14/BlockImporter ✅ DONE. Only INFO-13 remains from the original D1 set.)*

---

#### ~~D2~~ — SNAP Sprint: S3-E task types ✅ DONE 2026-06-26

S3-B ✅ DONE 2026-06-22. INFO-8 ✅ DONE (resolved during SNAP1 migration). S3-E ✅ DONE 2026-06-26 — all task class fields confirmed `val`.

---

#### §8e-SNAP1 — SNAPSyncController return statement clearout

**Gate:** SNAP1 migration (4-phase Pekko Typed migration of SNAPSyncController) ✅ DONE — commits `959584e17`–`4d6fcdf6a`. Gate lifted 2026-06-26.
**Agent:** MITHRIL
**Files:** `blockchain/sync/snap/SNAPSyncController.scala`

**Finding:** 62 non-annotated `return` statements remain in `SNAPSyncController.scala` after SNAP1 migration. The `noReturns` scalafix rule is ratcheted; these sites must be cleared to eliminate suppressions.

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

**Rejection criteria:** Changing SNAP protocol message types or coordinator logic; leaving any `return` in place without `@nowarn` + DEFERRED rationale.

---

#### POST-MIGRATION-SWEEP — Final Classic Residue Verification

**Gate:** D1 (INFO-13) resolved + AkkaTaskOps deleted.
**Agent:** MITHRIL + WRAITH
**Status (verified 2026-06-26):** BRIDGE-A (`toClassic.sender`) ✅ zero sites. BRIDGE-B (`ctx.self.toClassic`) ✅ one site — `RLPxConnectionHandler:323` (TCP floor, permanent). BRIDGE-C (untyped ActorRef constructor params) ✅ remaining sites are TCP-layer connection refs in PeerManagerActor (intentional). `Behavior[Any]` ✅ zero. W7 ✅ DONE. INFO-14/BlockImporter ✅ DONE. **INFO-13/RegularSync** ❌ open (`LoggingAdapter:69`). **AkkaTaskOps** ❌ open (`askFor`/`askForVia` dead methods).

**Remaining work:**

1. **Delete AkkaTaskOps Classic dead methods** — `askFor` at `:15` and `askForVia` at `:24` have zero callers. Only `askForTyped` is used. Delete the two Classic extension methods and their imports (`org.apache.pekko.pattern.ask`, `ActorRef.noSender`). `sbt compile-all` — must be clean. Commit: `chore: delete AkkaTaskOps Classic dead methods (askFor/askForVia)`

2. **Fix INFO-13** — see D1 prompt above (`RegularSync.scala:69` LoggingAdapter).

3. **Final sweep greps** (run after INFO-13 fixed + AkkaTaskOps deleted):
```bash
grep -rn "toClassic\.sender()" src/main/ --include="*.scala" | grep -v "//"          # must be 0
grep -rn "ctx\.self\.toClassic" src/main/ --include="*.scala" | grep -v "RLPxConn"   # must be 0
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala" | grep -v "//"               # must be 0
grep -rn "extends Actor\b" src/main/ --include="*.scala"                               # must be 3 (TCP floor)
grep -rn "LoggingAdapter\|classicSystem" src/main/ --include="*.scala"                # must be 0
```

4. **Archive** this prompt to `completed/CODEBASE-AUDIT.md` when all greps pass.

**Commit:** `chore(post-migration-sweep): zero non-TCP Classic residue confirmed — $SHA`
