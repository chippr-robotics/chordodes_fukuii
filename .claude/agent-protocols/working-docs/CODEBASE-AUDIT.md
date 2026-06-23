# Fukuii Codebase Audit — Post Phase 2 Narrowing (Open Items)

**Branch:** `scala3-cleanup-june`
**Completed sweep results + resolved prompts:** See `completed/CODEBASE-AUDIT.md`

---

### S5 — Test quality gaps (EYE)

**Target greps:**
```bash
# Wall-clock assertions (3 known files — confirm no new ones)
grep -rn "should be < \|elapsed.*millis\|Duration.*toMillis" src/test/ --include="*.scala"

# Thread.sleep in tests (audit done in C7 — verify nothing new crept in)
grep -rn "Thread\.sleep" src/test/ --include="*.scala"

# TestProbe E165 — unnarrowed type params
grep -rn "TestProbe\b" src/test/ --include="*.scala" | grep -v "\[" | wc -l

# Tests with no assertions (heuristic: test body has no "should\|must\|assert\|verify")
grep -rn "\"should\|\"must\|\"it" src/test/ --include="*.scala" | wc -l
```

Surface: anything beyond the 3 known wall-clock files or 5 known E165 TestProbe sites.

**Results (EYE sweep 2026-06-22):**

#### Wall-Clock Assertions
- **Count:** 4 files (3 confirmed known + 1 borderline)
- **Known (confirmed):** `WorkNotifierSpec` L103–108 (`elapsed should be < 500L`); `MerkleProofVerifierPhase3Spec` L584–603 (`ms1000 should be < ms100 * 25L`); `TrieNodeHealingCoordinatorSpec` L316–327 (`elapsedMs should be < 5000L`)
- **Borderline:** `SnapServerLimitsSpec` L89–90 — `deadline should be <= before + 4001L` uses two consecutive `currentTimeMillis()` calls with a 1ms upper bound — fragile under JVM timer coarseness
- **Status:** 3 known confirmed. `SnapServerLimitsSpec` is already listed in the CHASE-QUEUE `wall-clock-assertion` section — no new routing needed.

#### Thread.sleep (live, non-comment)
- **Count:** 2 pre-existing
- **Files:** `EthMiningServiceSpec.scala:302` (sleep to advance timeout window); `SubscriptionManagerSpec.scala:249` (sleep 200ms for topic propagation)
- **Status:** PRE-EXISTING — no new sites.

#### TestProbe E165 (unnarrowed type params)
- **Count:** 777 unnarrowed sites across 83 files
- **Prior baseline stated:** "5 in FastSyncBranchResolverSpec" — **INCORRECT**: that file now has 0 unnarrowed sites (was cleaned). The 777 sites are pervasive across SNAP coordinator and network test suites.
- **Highest-density files:** `TrieNodeHealingCoordinatorSpec` (58), `ByteCodeCoordinatorSpec` (56), `AccountRangeCoordinatorSpec` (54), `StorageRangeCoordinatorSpec` (39), `PeerManagerSpec` (32)
- **Status:** BASELINE DISCREPANCY — CHASE-QUEUE updated from "5 in FastSyncBranchResolverSpec" → "777 sites / 83 files". Pre-existing debt; not introduced by current sprint. Defer to dedicated test-harness cleanup sprint.

#### Ignored/Pending Tests
- **Count:** 0 suppressed tests
- **Status:** CLEAN

---


---

### DEFERRED — items with external gates or planned sprint scope

The following findings are tracked but blocked on external conditions. No prompt needed now.

| Finding | Gate | When to action |
|---------|------|----------------|
| **W15** — `unwrap returns Any` in `SyncController` | Wave 3 LOOM gate: `WrappedExternal` eliminated when remaining Classic callers are migrated | Network/P2P sprint |
| **W7** — 900-line NPMA `Impl` extraction | Wave 3 LOOM gate: NPMA is primary target of next network migration sprint | Network/P2P sprint |
| ~~**W13**~~ — ~~exception-as-control-flow in `expandTypedReceipts`~~ | ~~Lower priority; no blocking correctness risk~~ | ✅ DONE 2026-06-22 |
| ~~**W14**~~ — ~~`var nextBehavior` accumulator (2 sites)~~ | ~~Lower priority FP cleanup~~ | ✅ DONE 2026-06-22 |
| **S3-B** — `SNAPSyncController:1286,1296` null `filePath` | SNAP cleanup sprint (profile allocation pressure first) | SNAP sprint |
| **S3-E** — mutable `case class` task types (4 SNAP files) | SNAP cleanup sprint; profile allocation before deciding `class` vs `val` | SNAP sprint |
| **INFO-8** — `refreshFreshRootCache` 128 calls in actor loop | Monitor SNAP serve latency; only fix if observed | SNAP sprint |
| **INFO-9** — `GetHandshakedPeersCmd.replyTo` untyped | Network/P2P sprint upgrades this ref | Network/P2P sprint |
| ~~**INFO-10**~~ — ~~fragile adapter-pinning tuple in `FastSync:180–183`~~ | ~~Retire when FastSync narrowing is complete~~ | ✅ DONE 2026-06-22 |
| **INFO-13/14** — Classic `LoggingAdapter` in `RegularSync.scala` | Network/P2P sprint migrates `RegularSync` | Network/P2P sprint |

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

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| ~~A1~~ | ~~Batch A~~ | ~~P1 EYE S5 scan~~ | ✅ DONE 2026-06-22 |
| ~~C4~~ | ~~Batch C~~ | ~~D4 — INFO-10 FastSync tuple investigation~~ | ✅ DONE 2026-06-22 |
| ~~C5~~ | ~~Batch C~~ | ~~D3 — MITHRIL W13 + W14~~ | ✅ DONE 2026-06-22 |
| D1 | Batch D | G1 — Start Behavior[Any] narrowing sprint (lifts D1 gate) | Parallel with G2 |
| D2 | Batch D | G2 — Start SNAP cleanup sprint (lifts D2 gate) | Parallel with G1 |

**Global sequence across all files:**
- ~~**Batch A** (parallel, all read-only)~~ ✅ COMPLETE
- **Batch B** (sequential, code changes): ~~CHASE-QUEUE P1~~ ✅ → CHASE-QUEUE P2 → CHASE-QUEUE P3 → CHASE-QUEUE P4 → DEFERRED P5
- **Batch C** (sequential, larger sweeps): DEFERRED P1 → DEFERRED P2 → DEFERRED P3 → **CODEBASE-AUDIT D4** → **CODEBASE-AUDIT D3** → DEFERRED P4 (E165 multi-session)
- **Gate**: `./local/scripts/fukuii-test` (testEssential) after all Batch B+C commits land
- **Batch D** (parallel, after gate): CODEBASE-AUDIT G1 ∥ CODEBASE-AUDIT G2
  - G1 completion unlocks: CODEBASE-AUDIT D1 (inline fixes during narrowing)
  - G2 completion unlocks: CODEBASE-AUDIT D2 (SNAP cleanup sprint)

---

### Gate-Lifting Prompts

These prompts satisfy the external gate conditions for D1 and D2. Run after the testEssential gate in Batch D.

---

#### G1 — Assess and continue Behavior[Any] narrowing sprint (lifts D1 gate)

**Prerequisite:** Read `.local/docs/moderization-review-june/network-sync-pekko-migration-plan.md` in full — it contains the CRITICAL CORRECTION (R2 re-survey 2026-06-21) that the actual work is `Behavior[Any]` → `Behavior[SealedCommand]` narrowing + `sender()` elimination, NOT Classic→Typed conversion. Also read `modernization-log/network/peers.md` and `modernization-log/sync/` files to understand what has already been committed.
**Agent:** LOOM (per-actor narrowing) + WRAITH (compile errors) + HERALD (any actor that touches peer message routing)

**Step 0 — Assess current state before touching anything:**
```bash
# How many Behavior[Any] remain in production sources?
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala" | grep -v "//"

# How many ctx.toClassic.sender() sites remain?
grep -rn "toClassic\.sender\(\)\|ctx\.toClassic\.actorOf" src/main/ --include="*.scala"

# Which actors still use extends Actor (should be 0)?
grep -rn "extends Actor\b" src/main/ --include="*.scala"
```

**If all three return 0 results:** The narrowing sprint is already complete. The D1 gate is lifted. Update D1 to "DONE" in the DEFERRED table, remove G1 from the run order, and document completion in `modernization-log/network/INDEX.md`. No further action.

**If results remain:** Proceed bottom-up, one actor per commit, picking up from wherever the sprint left off per the plan's ordering (leaf actors first: ChainDownloader, BytecodeRecoveryActor, StorageRecoveryActor; then mid-tier: SyncStateSchedulerActor, FastSyncBranchResolverActor; then: SyncController last — 13 `sender()` sites, highest risk):

1. Read the actor's handler and identify every message type it processes.
2. Confirm the `Command` sealed trait covers all inbound message types.
3. Change `Behavior[Any]` → `Behavior[Command]` in the actor's factory method and all spawn sites.
4. For any `ctx.toClassic.sender()` call: add an explicit `replyTo: ActorRef[ResponseType]` parameter to the inbound message and update all callers to pass it.
5. `sbt compile-all` after each actor — fix any `Behavior[Any]` leakage from message adapter types.
6. Run `./local/scripts/fukuii-test` for the actor's spec and any callers' specs.
7. Commit per actor: `refactor: narrow Behavior[Any] → Behavior[Command] for ActorName`.

Apply the Opportunistic Clearout Protocol while each file is open. While each actor file is open, check the DEFERRED table above for W15 (SyncController), W7 (NPMA), INFO-9, INFO-13/14 and apply D1 inline as those files are reached.

**Classic bridges that MUST stay Classic — do not touch:**
`TcpEventBridge` / `OutboundTcpBridge` / `InboundTcpBridge` inner classes in `ServerActor` and `RLPxConnectionHandler`; `PeerEventBus extends ClassicEventBus` inside `PeerEventBusActor`.

**Verification per actor:** `grep -n "Behavior\[Any\]" <file>.scala` returns 0 lines.
**Verification end of sprint:** `grep -rn "Behavior\[Any\]" src/main/ --include="*.scala"` → 0 results (excluding comments).

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/CODEBASE-AUDIT.md` run order table — change `| D1 | Batch D | G1 ...` to `| ~~D1~~ | ~~Batch D~~ | ~~G1 — Start Behavior[Any] narrowing sprint~~ | ✅ DONE [date] — sprint in progress / complete |`
- On sprint completion: update `network-sync-pekko-migration-plan.md` status from "RESEARCH ONLY" to "COMPLETE — [SHA]". Add entries to `modernization-log/network/` and `modernization-log/sync/` per actor narrowed. Update the DEFERRED table: move D1 items to "DONE" as each file is reached.

**Rejection criteria:** Touching Classic TCP bridge inner classes; changing consensus logic; mass-edit without per-actor compile verification.

---

#### G2 — Assess and continue SNAP cleanup sprint (lifts D2 gate)

**Prerequisite:** Read `modernization-log/sync/snap.md` to understand what has already been committed for SNAP. Also check `completed/` docs for any SNAP sprint entries.
**Agent:** MITHRIL (S3-B, S3-E) + EYE (validation) + VAULT if profiling reveals storage allocation issues

**Step 0 — Assess current state before touching anything:**
```bash
# S3-B: Is the null filePath guard still present?
grep -n "filePath != null\|filePath == null\|\.filePath\b" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala

# S3-E: Do mutable var fields still exist in task-type case classes?
grep -rn "case class.*\bvar \|^\s*var .*Task\b" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/ --include="*.scala"

# INFO-8: Is refreshFreshRootCache still called in a loop?
grep -n "refreshFreshRootCache\|getBlockHeaderByNumber" \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/SNAPSyncController.scala | head -20
```

**If all three return 0 results or show already-fixed patterns:** The SNAP cleanup sprint is complete. Update the DEFERRED table to mark S3-B, S3-E, INFO-8 as DONE. No further action.

**If results remain:** Pick up wherever the sprint left off:

**S3-B — null `filePath` guard (if still present):**
Read the grep output to confirm the exact current lines (originally 1286/1296 — may have shifted). Replace the `!= null` guard with `Option[Path]` threading from the point of origin. If `filePath` can legitimately be absent, make the field `Option[Path]` throughout. If it can't be absent at those call sites, add a require-style check that fails fast. Do not use `Option.apply(null)` — change the type.

**S3-E — mutable task-type `case class` fields (if still present):**
Identify which task files still have mutable fields. For each: check whether the task type appears in hot allocation paths (`grep -rn "new AccountTask\|new StorageTask"` etc. to estimate instantiation density). Hot path (high density) → `class` with `val` fields; cold path → `case class` with `val`. Run RLP round-trip check if they participate in serialization.

**INFO-8 — `refreshFreshRootCache` loop (conditional fix):**
Check `run-logs/` for SNAP serve latency data, or grep comments near the call site for any documented profiling result. Only fix if p99 serve latency exceeds 50ms or a profiling comment confirms it's hot. If not fixing: add `// INFO-8: monitored — no latency issue observed [date]` comment inline and mark INFO-8 as MONITORED in the DEFERRED table.

**Verification:** `sbt compile-all`. `./local/scripts/fukuii-test SNAPSync`. Confirm S3-B site has no null-propagation path.

**Opportunistic clearout:** While `SNAPSyncController.scala` is open (5,324 LOC), check for any CHASE-QUEUE or DEFERRED items in the file and apply the three-tier decision. Note the 33 deferred `return` statements (§8e ratchet) — separate gate, but log density if changed since last audit.

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/CODEBASE-AUDIT.md` run order table — change `| D2 | Batch D | G2 ...` to `| ~~D2~~ | ~~Batch D~~ | ~~G2 — Start SNAP cleanup sprint~~ | ✅ DONE [date] — sprint in progress / complete |`
- Remove addressed rows (S3-B, S3-E, INFO-8) from the DEFERRED table. Add entries to `modernization-log/sync/snap.md`.

**Rejection criteria:** Touching 33 `return` statements (separate §8e SNAP1 gate); changing SNAP protocol logic; skipping the current-state assessment in Step 0.

---

### Deferred Sprint Prompts

These items have external gates and cannot be actioned until those gates open. Prompts are pre-drafted here so each sprint-opening thread can pick them up without re-researching.

---

#### D1 — Network/P2P Sprint: Address deferred findings on migration

**Gate:** Network/P2P sprint begins (R1 plan in WAVE3-RESEARCH-PLAN.md is prerequisite)
**Agent:** LOOM (per-actor) + HERALD (topology check for eventStream items)
**Files:**
- `network/p2p/NetworkManager.scala` (W7 — 900-line NPMA Impl extraction)
- `blockchain/sync/SyncController.scala` (W15 — `unwrap` returns Any)
- `network/p2p/PeerManager.scala` (INFO-9 — `GetHandshakedPeersCmd.replyTo` untyped)
- `blockchain/sync/regular/RegularSync.scala` (INFO-13/14 — Classic `LoggingAdapter`)

**Prompt:**
At the start of the Network/P2P Pekko migration sprint, before migrating each actor, apply these deferred fixes inline during that actor's migration:

1. **W7 (NPMA extraction):** During `NetworkManager`/NPMA migration, extract the ~900-line `Impl` inner class to a top-level or companion class. Do not extract before migration — the migration thread owns this file.
2. **W15 (unwrap returns Any):** During `SyncController` migration, replace `unwrap` return type with the narrowed sealed type. `WrappedExternal` will be eliminated when its remaining Classic callers are migrated.
3. **INFO-9 (GetHandshakedPeersCmd.replyTo untyped):** During `PeerManager` migration, type the `replyTo` field as `ActorRef[GetHandshakedPeersResponse]` (or the appropriate typed response type). Grep callers of `GetHandshakedPeersCmd` first.
4. **INFO-13/14 (Classic LoggingAdapter in RegularSync):** During `RegularSync` migration, replace `ActorLogging` / `log: LoggingAdapter` with `Behaviors.withMdc` or SLF4J `LazyLogging` per `logging-standards.md`.

**Verification:** `sbt compile-all` after each actor migration. Confirm E165 count decreases per actor migrated.

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md while each actor file is open. The Network/P2P sprint will open many large files — any CHASE-QUEUE or DEFERRED items in those files should be addressed inline per the three-tier decision.

**MANDATORY final step — complete BEFORE closing thread:**
- No separate run order update — D1 runs inline during G1; the G1 MANDATORY step handles row `D1` strikethrough.
- Remove W7, W15, INFO-9, INFO-13/14 rows from the DEFERRED table as each actor is reached.
- Add a `#### [SHA] — description` entry to `modernization-log/network/` and `modernization-log/sync/` for each actor migrated.

---

#### D2 — SNAP Sprint: Address deferred S3-B, S3-E, INFO-8 findings

**Gate:** SNAP cleanup sprint (profile allocation pressure first — do not start S3-E before profiling)
**Agent:** MITHRIL (S3-B, S3-E) + EYE validation
**Files:**
- `blockchain/sync/snap/SNAPSyncController.scala` (S3-B lines 1286/1296; INFO-8)
- 4 SNAP task-type files (S3-E — identify with `grep -rn "case class.*var " src/main/scala/blockchain/sync/snap/`)

**Prompt:**

**S3-B — Null `filePath` at SNAPSyncController:1286,1296:**
Read lines 1280–1310 of `SNAPSyncController.scala`. Both sites construct a path with a potentially null segment. Replace with `Option[Path]` parameter threading or validated path construction that fails fast rather than propagating null. This is not consensus-critical — MITHRIL handles it.

**S3-E — Mutable `case class` task types (4 files):**
First, run the profiler to check allocation pressure under SNAP sync load — if these task objects are hot, prefer `val` fields in a regular `class`; if cold, convert to immutable `case class`. After profiling, update each of the 4 files. Grep: `grep -rn "case class.*\bvar " src/main/scala/blockchain/sync/snap/ --include="*.scala"` to confirm the 4 files.

**INFO-8 — `refreshFreshRootCache` 128 calls in actor loop (conditional):**
Check SNAP serve latency logs first. If no latency spike is observed in staging/Mordor testing, skip this item — the 128 calls may be cache hits with negligible overhead. Only action if `serve` latency exceeds 50ms p99 in profiling. If actioned: cache the lookup result at the start of the serve loop.

**Verification:** `sbt compile-all`. Run SNAP-specific tests: `./local/scripts/fukuii-test SNAPSync`. Confirm S3-B has no null-propagation paths; confirm S3-E test types serialize correctly under RLP round-trip.

**Opportunistic clearout:** While `SNAPSyncController.scala` is open, check for other CHASE-QUEUE items in that file. At 5,052 LOC it is the most likely file to have additional inline-fixable findings.

**MANDATORY final step — complete BEFORE closing thread:**
- No separate run order update — D2 runs inline during G2; the G2 MANDATORY step handles row `D2` strikethrough.
- Remove S3-B, S3-E, INFO-8 rows from the DEFERRED table as each is addressed. Add entries to `modernization-log/sync/snap-sync-controller.md`.

---

#### D3 — MITHRIL Session: W13 + W14 (no external gate — lowest priority)

**Gate:** Any available MITHRIL session (no sprint dependency — can slot into any cleanup thread)
**Agent:** MITHRIL
**Files:**
- `blockchain/sync/` — find `expandTypedReceipts` (W13): `grep -rn "expandTypedReceipts" src/main/ --include="*.scala"`
- Find `var nextBehavior` (W14): `grep -rn "var nextBehavior" src/main/ --include="*.scala"`

**Prompt:**

**W13 — Exception-as-control-flow in `expandTypedReceipts`:**
Read the method. It uses `try/catch` to control normal flow rather than to handle exceptional conditions. Refactor to return `Either[DecodeError, List[TypedReceipt]]` (or equivalent) and propagate the result to callers. Check callers to confirm they can receive an `Either`. This is a readability/FP idiom fix, not a consensus change — no FORGE review needed unless the method touches state root computation.

**W14 — `var nextBehavior` accumulator (2 sites):**
Read both sites. Replace the mutable accumulator with a tail-recursive helper or `foldLeft` over the input. Confirm the replacement produces identical output by running the existing tests for the enclosing actor.

**Verification:** `sbt compile-all`. Run `./local/scripts/fukuii-test` for the enclosing actor/spec.

**Opportunistic clearout:** While these files are open, check for other CHASE-QUEUE MUTABLE or ISINST entries in the same files and apply the inline-fix tier if bounded.

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/CODEBASE-AUDIT.md` run order table — change `| C5 | Batch C | D3 ...` to `| ~~C5~~ | ~~Batch C~~ | ~~D3 — MITHRIL W13 + W14~~ | ✅ DONE [date] |`
- Remove W13 and W14 rows from the DEFERRED table. Add entries to the relevant `modernization-log/` subsystem file.

---

#### D4 — FastSync Narrowing Complete: Retire INFO-10 adapter-pinning tuple

**Gate:** FastSync Pekko narrowing is complete (FastSync migration thread closes)
**Agent:** LOOM (or MITHRIL if narrowing is already done)
**Files:**
- `blockchain/sync/fast/FastSync.scala` lines 180–183

**Prompt:**
Read `FastSync.scala` lines 175–195. The tuple at 180–183 pins an adapter to a specific message order — this is fragile and was deferred until the narrowing removed the dependency. With FastSync fully narrowed, read the tuple usage, confirm the adapter is no longer load-bearing, and remove it. Replace with a direct typed message send or a named case class if the tuple was packing multiple fields.

**Verification:** `sbt compile-all`. Run `./local/scripts/fukuii-test FastSync`.

**Opportunistic clearout:** With FastSync migration complete, scan the file for any remaining `sender()`, untyped `ActorRef`, or `context.become` that narrowing may have missed.

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/CODEBASE-AUDIT.md` run order table — change `| C4 | Batch C | D4 ...` to `| ~~C4~~ | ~~Batch C~~ | ~~D4 — INFO-10 FastSync tuple investigation~~ | ✅ DONE [date] |`
- Remove INFO-10 row from the DEFERRED table. Add entry to `modernization-log/sync/fast-sync.md`.
