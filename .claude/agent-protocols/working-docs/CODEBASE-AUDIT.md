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
| ~~**S3-B**~~ — ~~`SNAPSyncController:1286,1296` null `filePath`~~ | ~~SNAP cleanup sprint~~ | ✅ DONE 2026-06-22 — removed dead null guards; `contractStorageFile`/`uniqueCodeHashesFile` are `private val` initialized by `Files.createTempFile()`, never null |
| ~~**S3-E**~~ — ~~mutable `case class` task types (3 SNAP files: `AccountTask`, `ByteCodeTask`, `StorageTask`)~~ | ~~Profile allocation before converting 35+ mutation sites to `.copy()`~~ | ✅ DONE 2026-06-22 — all `var` constructor fields → `val`; 34 coordinator mutation sites converted to `.copy()`; 63/63 targeted tests pass |
| ~~**INFO-8**~~ — ~~`refreshFreshRootCache` 128 calls in actor loop~~ | ~~Monitor SNAP serve latency~~ | ✅ MONITORED 2026-06-22 — `refreshFreshRootCache` does not exist in SSC; 7 scattered `getBlockHeaderByNumber` calls, none in a loop; no latency concern |
| **INFO-9** — `GetHandshakedPeersCmd.replyTo` untyped | Network/P2P sprint upgrades this ref | Network/P2P sprint |
| ~~**INFO-10**~~ — ~~fragile adapter-pinning tuple in `FastSync:180–183`~~ | ~~Retire when FastSync narrowing is complete~~ | ✅ DONE 2026-06-22 |
| **INFO-13/14** — Classic `LoggingAdapter` in `RegularSync.scala` | Network/P2P sprint migrates `RegularSync` | Network/P2P sprint |
| **BRIDGE-A** — `ctx.toClassic.sender()` hub: FastSync×3, RegularSync×1, SyncController×24 | External callers (EthInfoService, JsonRpc, McpTools) adopt typed `replyTo` — SyncProtocol messages need `replyTo: ActorRef[_]` fields; SyncController forwarding refactored | POST-MIGRATION-SWEEP |
| **BRIDGE-B** — `ctx.self.toClassic` reply-target: SNAPSyncController×7, ByteCodeCoordinator, AccountRangeCoordinator, BlockImporter, StorageRecoveryActor, PeerActor | Collaborator worker actors accept `TypedActorRef` params instead of `ClassicActorRef` — resolves per-worker in Wave 4 | POST-MIGRATION-SWEEP |
| **BRIDGE-C** — Untyped `ActorRef` in Typed constructors: `fastSyncClassicSelf`, `peerEventBus: ActorRef`, `networkPeerManager: ActorRef`, `syncController: ActorRef`, `parentRef` in PivotBlockSelector + SyncStateSchedulerActor | Per-collaborator migration: PivotBlockSelector + SyncStateSchedulerActor in Wave 3 G1; remaining in Wave 4 | G1 (partial) / POST-MIGRATION-SWEEP (remainder) |

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
| ~~D1~~ | ~~Batch D~~ | ~~G1 — Start Behavior[Any] narrowing sprint (lifts D1 gate)~~ | ✅ DONE 2026-06-22 — sprint already complete, all 12 actors narrowed to Behavior[Command]; stale comments cleaned up |
| ~~D2~~ | ~~Batch D~~ | ~~G2 — Start SNAP cleanup sprint (lifts D2 gate)~~ | ✅ DONE 2026-06-22 — S3-B fixed (dead null guards removed); INFO-8 monitored (function never existed); S3-E deferred to D2 pending profiling (35+ mutation sites, 3 task types) |
| ~~D3~~ | ~~Batch D~~ | ~~P7 — EYE/MITHRIL test timing audit + slow-test reduction~~ | ✅ DONE 2026-06-22 — 680s baseline, 3,595 / 0 fail; Thread.sleep deferred to §8a |
| ~~E1~~ | ~~Batch E~~ | ~~D2 — SNAP S3-E: mutable task types → immutable (unlocked by G2)~~ | ✅ DONE 2026-06-22 |
| E2 | Batch E | CHASE-QUEUE P8 — G1-sweep PRISM items (FastSync + NPMA + SyncController) | Yes |
| E3 | Batch E | DEFERRED §3h — Any type signature cleanup (non-consensus sites) | Yes |
| E4 | Batch E | DEFERRED §8a-retro batch 3 — LOOM TestKit→ActorTestKit for G1 network/sync actors | Yes (test files only; E1/E2/E3 modify src/main/) |

**Global sequence across all files:**
- ~~**Batch A** (parallel, all read-only)~~ ✅ COMPLETE
- ~~**Batch B** (sequential, code changes)~~ ✅ COMPLETE
- ~~**Batch C** (sequential, larger sweeps)~~ ✅ COMPLETE
- ~~**Gate**~~ ✅ COMPLETE — 11:02 (662s), 3,595 / 0 fail (2026-06-22)
- ~~**Batch D** (parallel, after gate)~~ ✅ COMPLETE — G1 (all 12 Behavior[Any] → Behavior[Command]) ∥ G2 (S3-B fixed, INFO-8 monitored, S3-E → D2) ∥ P7 (timing baseline 680s)
- **Batch E** (parallel, all unblocked): CODEBASE-AUDIT D2 ∥ CHASE-QUEUE P8 ∥ DEFERRED §3h ∥ DEFERRED §8a-retro batch 3
- **Gate** (after Batch E): `./local/scripts/fukuii-test` testEssential
- **Final gate** (after G1 + G2 + Wave 4 collaborator migration): **POST-MIGRATION-SWEEP** — zero Classic residue verification + BRIDGE-A/B/C elimination

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

**MANDATORY final step — complete IN THIS ORDER (per actor migrated):**
1. `sbt scalafmtAll`
2. `git add <actor.scala> [<actor-spec.scala>]` — stage only the files you modified for this actor
3. `git commit -m "refactor(g1): narrow Behavior[Any] → Behavior[Command] — ActorName"`
4. `SHA=$(git rev-parse --short HEAD)` — capture exact SHA
5. Update DEFERRED table in this file for the D1 item corresponding to this actor: add `✅ DONE $SHA`
6. Add `#### $SHA — ActorName: Behavior[Command] narrowing` entry to `modernization-log/network/` or `modernization-log/sync/`
7. `git add .claude/` → `git commit -m "docs(g1): ActorName clearout — $SHA"`
8. When ALL actors complete: update run-order table strikethrough for D1 and update `network-sync-pekko-migration-plan.md` status to `COMPLETE — [final SHA]`

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

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific source files modified>` — stage only the files you changed
3. `git commit -m "chore(snap): G2 — <description of what was fixed>"`
4. `SHA=$(git rev-parse --short HEAD)` — capture exact SHA
5. Update run-order table: strikethrough D2 → `| ~~D2~~ | ~~Batch D~~ | ~~G2 — Start SNAP cleanup sprint~~ | ✅ DONE [date] — $SHA |`
6. Remove addressed rows (S3-B, S3-E, INFO-8) from the DEFERRED table; add `✅ DONE $SHA`
7. Add `#### $SHA — G2: <description>` entry to `modernization-log/sync/snap.md`
8. `git add .claude/` → `git commit -m "docs(g2): clearout — $SHA"`

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

**MANDATORY final step — complete IN THIS ORDER (inline with G1 — per actor):**
1. Source commit is handled by G1 MANDATORY step — no separate D1 source commit needed
2. `SHA=$(git rev-parse --short HEAD)` — use the G1 actor commit SHA
3. Remove W7 / W15 / INFO-9 / INFO-13/14 row from DEFERRED table as each actor is reached; add `✅ DONE $SHA`
4. Add `#### $SHA — D1: <item> fixed inline during ActorName migration` entry to `modernization-log/network/` or `modernization-log/sync/`
5. Doc commit is handled by G1 MANDATORY step — include these doc files in G1's `git add .claude/` step

---

#### D2 — SNAP Sprint: Convert mutable `case class` task types to immutable (S3-E)

**Gate:** None remaining — G2 research is complete. S3-B ✅ DONE 2026-06-22. INFO-8 ✅ MONITORED 2026-06-22 (function never existed). Only S3-E remains.
**Agent:** MITHRIL (conversion) + EYE (validation)
**Files:**
- `blockchain/sync/snap/AccountTask.scala` — 10 var fields, 12+ mutation sites in `AccountRangeCoordinator.scala`
- `blockchain/sync/snap/ByteCodeTask.scala` — 3 var fields, 6+ mutation sites in `ByteCodeCoordinator.scala`
- `blockchain/sync/snap/StorageTask.scala` — 4 var fields, 11+ mutation sites in `StorageRangeCoordinator.scala`
- `blockchain/sync/snap/actors/AccountRangeCoordinator.scala` — primary mutation site for AccountTask
- `blockchain/sync/snap/actors/ByteCodeCoordinator.scala` — primary mutation site for ByteCodeTask
- `blockchain/sync/snap/actors/StorageRangeCoordinator.scala` — primary mutation site for StorageTask

**G2 research findings (do not re-research):**
- `HealingTask` is already a `class` with `var` fields ✅ — correct, leave it
- No RLP serialization: task types do NOT participate in RLP encode/decode — no RLP round-trip check needed
- Instantiation density: 24 total across all three types (cold path → `case class` with `val` is correct target)
- Mutation pattern: all mutations are `task.pending = true/false`, `task.done = true`, `task.requeueCount = N`, `task.slots = ...`, `task.proof = ...` — convert to `task = task.copy(...)` at each site
- The mutation sites hold `task` in mutable local vars or mutable collections — `task` references are rebindable

**Prompt:**

**Step 1 — Confirm current state:**
```bash
grep -rn "case class.*\bvar \|^\s*var " \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/AccountTask.scala \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/ByteCodeTask.scala \
  src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/StorageTask.scala
```
If output is empty: S3-E is already done — mark complete and stop.

**Step 2 — Convert each task type:**
For each file (`AccountTask.scala`, `ByteCodeTask.scala`, `StorageTask.scala`):
1. Change all `var` fields to `val` — the case class remains a `case class`
2. Grep the corresponding coordinator(s) for `task.fieldName =` mutation sites
3. At each mutation site, replace `task.field = newVal` with `task = task.copy(field = newVal)`
4. Where `task` is a `val` in the coordinator, change it to `var task` so it can be rebound

Key mutation sites from G2 research:
- `AccountRangeCoordinator.scala`: `task.done = true` (×2), `task.pending = true/false` (×5), `task.requeueCount = 0` (×2), `task.pending = false; task.done = false` (×1)
- `ByteCodeCoordinator.scala`: `task.pending = false/true` (×6), `task.done = true` (×1), `active.task.pending = false` (×1 — needs `active = active.copy(task = active.task.copy(pending = false))`)
- `StorageRangeCoordinator.scala`: `task.pending = false/true` (×9), `task.done = true` (×3), `task.slots = ...` (×1), `task.proof = ...` (×1), `batchTasks.foreach(_.pending = true)` (×1 — needs `.map(_.copy(pending = true))` + reassignment)

**Step 3 — Verify:**
```bash
sbt compile-all
.local/scripts/fukuii-test only "*SNAPSyncController* *AccountRange* *ByteCode* *StorageRange*"
```
Confirm: no null/mutation compile errors; 72+ SNAPSyncController tests pass; coordinator tests pass.

**Opportunistic clearout:** The three coordinator files are large — while open, apply the inline clearout protocol for any CHASE-QUEUE items visible in those files.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <AccountTask.scala> <StorageTask.scala> <ByteCodeTask.scala> <*Coordinator.scala files>` — stage only modified source files
3. `git commit -m "chore(snap): S3-E — task types immutable, coordinator .copy() sites"`
4. `SHA=$(git rev-parse --short HEAD)` — capture exact SHA
5. Remove `S3-E` row from the DEFERRED table in this file; add `✅ DONE $SHA`
6. Add `#### $SHA — S3-E: task types immutable` entry to `modernization-log/sync/snap.md`
7. `git add .claude/` → `git commit -m "docs(d2-s3e): clearout — $SHA"`

---

---

#### POST-MIGRATION-SWEEP — Final Classic Bridge Elimination

**Gate:** Wave 3 (G1 + G2) complete AND Wave 4 collaborator migration complete (PivotBlockSelector, SyncStateSchedulerActor, PeerRequestHandler, all coordinator workers migrated to Typed signatures)
**Agent:** HERALD (topology audit first) + LOOM (per-site elimination) + MITHRIL (SyncProtocol replyTo refactor) + WRAITH (compile errors)
**Purpose:** The migration goal is zero Classic residue outside the three deliberate TCP bridges. All `.toClassic` calls, untyped `ActorRef` params, and `ctx.toClassic.sender()` sites in Typed actors are migration debt — they are correct transitional patterns now, but must be eliminated before the codebase is declared fully migrated.

---

**Step 0 — Research: run all greps before any changes**

```bash
cd /media/dev/2tb/dev/fukuii

# BRIDGE-A: Classic sender access (target: 0)
grep -rn "toClassic\.sender()" src/main/ --include="*.scala" | grep -v "//"

# BRIDGE-B: self as Classic ref (target: 0 outside TCP bridges)
grep -rn "ctx\.self\.toClassic\|context\.self\.toClassic" src/main/ --include="*.scala" \
  | grep -v "ServerActor\|RLPxConnectionHandler"

# BRIDGE-C: untyped ActorRef in Typed actor constructors (target: 0)
grep -rn ":\s*ActorRef\b[^[{]" src/main/ --include="*.scala" \
  | grep -v "TypedActorRef\|import\|//\|sealed\|type \|ServerActor\|RLPxConnectionHandler\|PeerEventBus"

# BRIDGE-D: Behavior[Any] in code (target: 0)
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala" | grep -v "//"

# BRIDGE-E: Classic LoggingAdapter (target: 0)
grep -rn "LoggingAdapter\|Logging(ctx\.system\|Logging(system\b" src/main/ --include="*.scala" | grep -v "//"

# BRIDGE-F sanity check: deliberate TCP bridges still present (expected: 3 hits)
grep -rn "extends Actor\b\|extends ClassicActor\b" src/main/ --include="*.scala"

# Total .toClassic conversions (for context — not all are bugs)
grep -rn "\.toClassic\b" src/main/ --include="*.scala" | grep -v "//\|classicSystem" | wc -l
```

Produce a triage table with current counts. If all BRIDGE-A/B/C/D/E return 0: migration is complete — record in all relevant `modernization-log/` files and archive this prompt to `completed/CODEBASE-AUDIT.md`.

---

**Step 1 — BRIDGE-A: Eliminate `ctx.toClassic.sender()` (add `replyTo` to SyncProtocol)**

This is the highest-risk change. Do NOT start until HERALD confirms the full call graph.

HERALD pre-flight: for every message type that currently uses `ctx.toClassic.sender()` as reply target (GetStatus, ResetFastSync, RestartFastSync, etc.), trace ALL send sites. Confirm whether they are Classic ask/tell or Typed tell. Determine whether the Classic callers (EthInfoService, JsonRpcBaseController, McpService, McpResources, NetService) can be changed to pass explicit `replyTo: ActorRef[StatusMsg]` fields, or whether a Classic bridge adapter layer is the more incremental path.

For each message type confirmed safe to add `replyTo`:
1. Add `replyTo: TypedActorRef[ResponseType]` to the message case class in `SyncProtocol.scala`
2. Update the handler: replace `ctx.toClassic.sender() ! response` with `replyTo ! response`
3. Update all Classic callers: wrap in a Typed ask using `AskPattern` or add an adapter in `SyncController`
4. `sbt compile-all` after each message type — do NOT batch multiple types

Commit per message type: `refactor(sync): add replyTo to SyncProtocol.X — BRIDGE-A`

---

**Step 2 — BRIDGE-B: Eliminate `ctx.self.toClassic` in Typed actors**

For each BRIDGE-B site (SNAPSyncController, ByteCodeCoordinator, AccountRangeCoordinator, BlockImporter, StorageRecoveryActor, PeerActor):
1. Identify the worker actor or message recipient that receives this Classic ref
2. Confirm that worker has been migrated to accept `TypedActorRef` (gate: Wave 4)
3. Replace `ctx.self.toClassic` with `ctx.self` (or appropriate `TypedActorRef` of the right message type)
4. Update the message constructor at the call site to use the Typed ref
5. `sbt compile-all` after each file

Commit per actor file: `refactor(sync): remove ctx.self.toClassic in ActorName — BRIDGE-B`

---

**Step 3 — BRIDGE-C: Remaining untyped `ActorRef` constructor params**

For each constructor that still takes `ClassicActorRef` where the actor is now fully Typed:
1. Change the param type to `TypedActorRef[MessageType]` (the concrete message type the actor sends to that ref)
2. Update the call site (usually in `SyncController` or `NodeBuilder`)
3. Remove any `.toClassic` conversion at the call site
4. `sbt compile-all`

Commit per collaborator: `refactor: type ActorRef param to TypedActorRef — ColaboratorName BRIDGE-C`

---

**Verification (final)**
```bash
# All BRIDGE-A/B/C/D/E greps must return 0
# BRIDGE-F must return exactly 3 (TCP bridges unchanged)
./local/scripts/fukuii-test essential   # full Tier 1 suite — 3621 tests, 0 failures
```

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific source files per collaborator>` — stage per-collaborator (commit per collaborator as described above)
3. `git commit -m "refactor: type ActorRef param to TypedActorRef — CollaboratorName BRIDGE-C"` — repeat per collaborator
4. `SHA=$(git rev-parse --short HEAD)` — capture final SHA (or note all SHAs if multiple commits)
5. Verify all BRIDGE greps return 0 (BRIDGE-F must still return exactly 3)
6. `./local/scripts/fukuii-test` → confirm 3,621+ tests, 0 failures
7. Mark BRIDGE-A, BRIDGE-B, BRIDGE-C rows in DEFERRED table as ✅ DONE $SHA
8. Add entries to `modernization-log/sync/`, `modernization-log/network/` per file touched
9. Archive this prompt section to `completed/CODEBASE-AUDIT.md` Post-Migration Target State, recording completion date + $SHA
10. `git add .claude/` → `git commit -m "docs(post-migration-sweep): clearout — $SHA"`

**Rejection criteria:** Any BRIDGE-A/B/C/D/E grep returning non-zero; any new `extends Actor` added; TCP bridges (`ServerActor`, `RLPxConnectionHandler`) altered; consensus files touched without FORGE/BEACON review.
