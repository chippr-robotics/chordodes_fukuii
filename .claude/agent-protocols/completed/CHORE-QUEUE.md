# Chore Queue — Test-Wait Downtime Tasks

Use this when `testEssential` (~24 min) or `testStandard` is running and you want productive work
that won't conflict with the primary migration thread.

**Rule:** Never mix chore commits into migration commits. Use prefixes:
- `chore(cleanup):` — dead code, deletion
- `chore(quality):` — warnings, test fixes  
- `style(scala3):` — formatting, syntax config

Pick a task by how much time you have. Each is fully independent.

---

## 15–30 minutes

### C1 — Braceless scalafmt config ✅ DONE

`rewrite.scala3.convertToNewSyntax = true` committed. 393 files, 3125 ins / 3482 del — keyword-syntax only (`if (x) {` → `if x then {`, `while (x) {` → `while x do {`), zero logic impact.

`removeOptionalBraces = true` evaluated and **intentionally excluded** — triggered 945-file brace removal across the whole codebase, too aggressive for a single chore commit.

**Commit:** `style(scala3): enable braceless syntax preference in scalafmt config`  
**Ref:** DEFERRED-BACKLOG.md §8g

---

### C2 — Remove `return` from non-actor non-consensus files ✅ DONE (`9eb1f4e06`)
19 files, ~52 return sites eliminated across five transformation categories:
- Guard clauses → chained `if`/`else` (TuiLogSuppressor, TuiUpdater, StaticNodesLoader, StdNode, AccountTask, ByteCodeTask, SyncController, EthFilterService, FastSync — one `sys.exit` suppression preserved)
- While-loop early exits → `var result` + post-loop check (ChainImporter, `SNAPRequestTracker.compareUnsignedLexicographically`, `MerkleProofVerifier.compareNibbleSeqs` + `cmpBytes` + `validateStorageSlotsBasic`)
- Try-block returns → `Either` lifting with `var` outside try/finally (`CheckpointExporter.exportArchive`)
- Match-arm returns → `Either` lifting + `flatMap` chaining (StateValidator, CheckpointImporter, CheckpointDownloader, SnapServer, `MerkleProofVerifier.resolveEdgePath`)
- Complex multi-return methods → nested `flatMap` / `for` comprehension (`EthFilterService.getLogs`, `CheckpointExporter.walkTrie`, `ChainDownloader.handleHeaders`/`handleBodies`/`handleReceipts`/`findBestStoredHeader`, `MerkleProofVerifier.verifyRangeProofByReconstruction`)

`sbt compile-all` → 0 errors. `sbt scalafmtAll` applied.

44 sites remain deferred: SSC 33 (Wave 3 SNAP1 sprint), consensus 7 (FORGE gate). Full ratchet lock tracked in DEFERRED-BACKLOG.md §8e.

---

### C3 — Fix Part 1 quick warnings ✅ DONE (d77b9512f / bd2d691c3)

Both bucket-A sites cleared during the sprint (`validateGasLimit` implicit removed, ETHPackets uses `.toIndexedSeq`). `sbt compile-all` → 0 warnings, 0 errors. Ratchet step 2/4 complete.

---

### C3b — Promote unused/unchecked warnings to build errors ✅ DONE [ratchet step 4/4]

E198 (unused) and E092 (unchecked) promoted to build errors in `build.sbt`. Pre-promotion: 14 E198 warnings cleared first:
- 5 unused imports (BCC, SRC, TNHC adapter imports; SNAPSyncController ask; NPMA self-import)
- 2 unused `ec` private vals (BCC, SRC — LOOM migration leftovers; `pipeToSelf` replaced the Future continuations)
- 3 unused constructor params (PeerListHelper + FastSyncBranchResolverActor.apply) + all callers updated
- 3 test file cleanups (unused imports, dead default value)
- 1 `@annotation.unused timers` in `PeerManagerActor.Impl:272` — Impl receives `TimerScheduler[Command]` from `Behaviors.withTimers` but uses `classicSystem.scheduler` instead; suppressed now, real fix deferred to CAPSTONE/NET cleanup pass (see DEFERRED-BACKLOG.md Part 2)

VERIFY: `sbt clean compile-all` → 0 errors, 0 E198/E092. `testEssential` → 3,601 / 0 failures.

---

## 1 hour

### C4 — extvm/ dead code deletion ✅ DONE (`a948fda1d`)

18 files deleted, 1,423 deletions. All 3 pre-checks passed (no external references, `vm.mode=internal` in all configs, scalapb/protobuf confirmed extvm-exclusive).

Deleted: 11 Scala source files (`VMClient`, `VMServer`, `MessageHandler`, etc.) + `MessageHandlerSpec` + `VMClientSpec` + `msg.proto` + `VERSION` + `project/scalapb.sbt` (entire file — sbt-protoc plugin was extvm-only).

`build.sbt` / `Dependencies.scala` cleaned: PB.targets codegen block removed, `scalapb-runtime` dep removed, extvm coverage/scapegoat exclusions removed.

`sbt clean compile-all` → 0 errors. `sbt scalafmtAll` ✓. Side effect: Part 1 Warning #3 (`extvm/VMClient.scala:22`) now resolved.

---

### C5 — Console output → logging ✅ DONE (`6a3e2cd88`)

7 raw print sites replaced with SLF4J across 5 files:
- `Fukuii.scala:186` — `println(banner)` → `log.info(banner)`
- `App.scala:146` — `println(helpText)` → `log.info(helpText)`
- `CheckpointCli.scala:65` — `System.err.println(help)` → `log.warn(help.toString)`
- `CliLauncher.scala:10-11` — added `LoggerFactory` logger; `map(println)` → `map(s => logger.info(s))`; `System.err.println(help)` → `logger.warn(help.toString)`
- `BlockPreparator.scala:455` — `System.err.println(trace)` → `log.debug(trace)`

Deferred (by rule): 16 sites in `consensus/`, `vm/`, `crypto/` (FORGE gate). `Tui.scala:112,195` — JLine `PrintWriter` output for TUI display (not stdout).

`sbt compile-all` → 0 errors after every file. `sbt scalafmtAll` clean.
**Ref:** DEFERRED-BACKLOG.md §3e

---

## 2+ hours (research tasks — can span multiple wait windows)

### C6 — Dead code audit beyond extvm ✅ DONE (research — 325-line audit doc)

Output: `.local/docs/moderization-review-june/dead-code-audit.md`

**High-confidence dead (two follow-on C-series commits ready):**
- `FastSyncBranchResolverActor` + `FastSyncBranchResolver` — no production spawn in `src/main/`; actor only appears in its own spec; likely superseded by SNAP sync. Confirm with grep then delete.
- `MerkleProofVerifier.scala:497–569` — 4 private methods all `@unused`, no callers. Superseded traversal approach kept "for reference." XS effort.

**Deferred / not dead:**
- `PeerListSupportNg` — still mixed into 5 Classic actors; retire after those migrate to Typed
- `BlockchainHostActor` — confirmed live, spawned at `NodeBuilder.scala:458`
- `EthashMinerSpec @Ignore` — correctly suppressed (real DAG gen); not dead

**Routed out:**
- `BlockchainSpec` MPT proof-of-absence gap → FORGE
- `EthereumTestsSpec` block-replay TODO → actionable but outside C6 scope

**Category 6 note:** 5,914 comment lines in `src/main/` are all explanatory prose (algorithm rationale, reference-client cross-references). No commented-out code blocks.

**Ref:** DEFERRED-BACKLOG.md §8f

---

### C7 — Thread.sleep audit in tests ✅ DONE (audit only — no commits)

7 real `Thread.sleep` calls across 5 files (7 additional hits in comments/Scaladoc, not code). Bucket A: 0 — none were simple async waits replaceable by `eventually` or `expectMsg`. All 7 are Bucket B (deferred):

| File | Count | Fix required |
|------|-------|-------------|
| `SNAPSyncControllerSpec` | ×2 | Promise/CountDownLatch to replace injected latency gates in FakeStateValidator |
| `Discv4SyncResponderSpec` | ×1 | Inject `Clock` into `RateLimiter` |
| `EthMiningServiceSpec` | ×1 | CE3 `TestControl` / `TestClock` for hashrate timeout expiry |
| `SubscriptionManagerSpec` | ×1 | Test redesign — `source2.take(0)` always returns empty (logic bug + timing issue) |
| `KeyStoreImplSpec` | ×2 | Inject clock or change filename scheme for filesystem timestamp ordering |

Audit doc: `.local/docs/moderization-review-june/thread-sleep-audit.md` (gitignored)
**Ref:** DEFERRED-BACKLOG.md §8j

---

---

### DumpChainActor ✅ DONE (`bf032008e`) — post-CAPSTONE dead code pass

309 → 19 LOC. Class + props factory + unused imports + `Max*PerRequest` constants deleted. No spawn sites, no callers, no tests. Retained: `emptyEvm` + `emptyStorage` ByteString constants (live — used by FixtureProvider). E165: 6 → 1 in IntegrationTest module (5 cleared). 1 E165 remains: `CommonFakePeer.scala:166` — KnownNodesManager.Command pattern on `Any`; needs own thread.

---

### C8a ✅ FastSyncBranchResolverActor — WIRED (`ea60c4f29`)

Investigation confirmed zero production spawn sites. Actor was not dead — it was **unwired**. Fix: `FastSync.scala` `handleBlockHeaders` `ParentChainWeightNotFound` case now spawns `FastSyncBranchResolverActor` (binary search for true common ancestor) and transitions to new `waitingForBranchResolution()` behavior. `BranchResolvedSuccessful` resets all in-memory cursors/queues to confirmed ancestor; `BranchResolutionFailed` falls back to N-block rewind. 15/15 `FastSyncBranchResolver*` tests pass, 0 compile errors.

**testEssential (`ea60c4f29` stack — 2026-06-21):** 3,600 / 3,600 passed, 0 failures (main 3498 + rlp 26 + bytes 11 + crypto 65). Gate cleared.

**Ref:** DEFERRED-BACKLOG.md §8f

---

### C8b — MerkleProofVerifier dead private methods ✅ DONE (`cc7b58b3b`)

4 `@unused` private methods deleted from `MerkleProofVerifier.scala` (lines 497–569: `verifyProofRoot`, `verifyStorageSlotInProof`, `traverseStoragePath`, `validateStorageSlotsBasic`) — superseded traversal approach, no callers. Orphaned `import scala.annotation.unused` also removed. All remaining node-type imports (`BranchNode`, `ExtensionNode`, etc.) still referenced in live code. `sbt compile-all` → clean.

**Ref:** DEFERRED-BACKLOG.md §8f

---

### C9a — SNAPSyncControllerSpec Thread.sleep ×2 ✅ DONE (`e9638ac52`)

Both `Thread.sleep` calls in `FakeStateValidator` replaced with `Option[CountDownLatch]` gates. Root cause: the sleeps were dead at all 4 call sites (all used default `0L`; `if > 0` guard never fired). New pattern: validator calls `countDown()` on entry; test can `await()` the latch to know validation started — no wall-clock dependency. All 4 existing call sites unchanged (`None` default = no-op). VERIFY: `sbt "testOnly *SNAPSyncControllerSpec*"` → 69/69 PASS.

**Ref:** thread-sleep-audit.md §B1

---

### C9b — Discv4SyncResponderSpec Thread.sleep ×1 ✅ DONE (`ba9b9d463`)

Injected `clock: () => Long = () => System.nanoTime()` into `RateLimiter` (vendored scalanet). Test replaced `Thread.sleep(20)` with a `var fakeNanos` that advances 20ms explicitly — zero wall-clock dependency.

**Ref:** thread-sleep-audit.md §B2

---

### C10 — 8a-retro batch 1: consensus/mining test kit migration ✅ DONE (`0d65a85c4`)

5 test files migrated from Classic `TestKit` + `WithActorSystemShutDown` → `ScalaTestWithActorTestKit`. 27/27 tests green.

Files: `LegacyTransactionHistoryServiceSpec`, `ForkChoiceManagerSpec`, `PoWMiningSpec`, `WorkNotifierSpec`, `MockedMinerSpec`.

Pattern: `system.toClassic` for Classic TestProbe and Pekko HTTP; `testKit.spawn` replaces `classicSystem.spawnAnonymous` where Typed kit's user guardian disallows Classic adapter spawning (same pattern as `PoWMiningCoordinatorSpec`).

**Non-obvious:** `ForkChoiceManagerSpec` had no `afterAll` — actor system leaked after every test run. Fixed as side-effect.

**Ref:** DEFERRED-BACKLOG.md §8a

---

### C11 — 8a-retro batch 2: jsonrpc/ + graphql/ test kit migration ✅ DONE (`b5e11c0a4` + `722f316f2`)

20 specs migrated from Classic `TestKit` → `ScalaTestWithActorTestKit`. 275/275 tests green.

Key fix baked into fixture: `JsonRpcControllerFixture` now takes `ActorTestKit` as implicit parameter; `system.spawnAnonymous` → `actorTestKit.spawn` (Typed test kit blocks Classic adapter spawning — same pattern as C10).

Recurring patterns now documented in DEFERRED-BACKLOG §8a for remaining batches: `PatienceConfig` ambiguity (drop patience trait), `override def timeout` collision, `system.toTyped.scheduler` → `system.scheduler`.

**Ref:** DEFERRED-BACKLOG.md §8a

---

### C12 ✅ scalafmt residuals from 8a-retro batch 2 — DONE (`92db5815e`)

The 4 jsonrpc test files reported as "pre-existing uncommitted" had no actual diffs — already clean. `92db5815e` is the scalafmt residuals commit from batch 2, already at branch tip. No batch 3 pending.

**testEssential (`5dd96ad01` stack — 2026-06-21):** 3,600 / 3,600 passed, 0 failures. Exit 0. Log: `/media/dev/2tb/data/blockchain/fukuii/test-logs/testEssential-20260621-122654.log`. Branch `scala3-cleanup-june` is green.

**Ref:** DEFERRED-BACKLOG.md §8c-H1-A, §8c-H1-B

---

### C9c — KeyStoreImplSpec Thread.sleep ×2 ✅ DONE (`479adc62f`)

Injected `clock: () => ZonedDateTime = () => ZonedDateTime.now(ZoneOffset.UTC)` into `KeyStoreImpl`. Test uses a monotonic fake clock (tick counter advancing the seconds field) in a local `orderedStore` instance — removes two `Thread.sleep(10)` calls while preserving go-ethereum keystore filename interoperability.

**Ref:** thread-sleep-audit.md §B5

---

### C13 ✅ Actor.noSender → ActorRef.noSender (3 files) — DONE (`417165930`)

PRISM post-capstone finding. `fix(pekko): replace Actor.noSender with ActorRef.noSender (7d artifact cleanup)`

- `AdminService.scala`: `import o.a.p.actor.Actor` → `import o.a.p.actor.ActorRef`
- `AkkaTaskOps.scala`: `Actor` import removed (already had `ActorRef`)
- `StdNode.scala`: `import o.a.p.actor.Actor` → `import o.a.p.actor.ActorRef`
- All three call sites: `Actor.noSender` → `ActorRef.noSender`

**Ref:** DEFERRED-BACKLOG.md PRISM Post-Capstone Audit

---

### C15 — Fix SyncControllerSpec SyncStateAutoPilot (P2) ✅ DONE (`fc1030410`)

`SyncStateAutoPilot.run` threw `MatchError` on `GetHandshakedPeersCmd(replyTo)` because the autopilot
only handled the legacy `GetHandshakedPeers` case object. Added `case GetHandshakedPeersCmd(replyTo) => replyTo ! handshakedPeers; this` to the match block. No production source files modified. `sbt testOnly *SyncControllerSpec` → 84/84 succeeded, 0 failures (7 previously failing tests now pass). Test-file-only change.

---

### C14 ✅ SyncController Classic scheduler → Typed ctx.system.scheduler (2 sites) — DONE (`8d460a145`)

PRISM post-capstone finding. `fix(pekko): replace Classic scheduler with Typed ctx.system.scheduler in SyncController (7d)`

- Lines 1507 + 2140: `scheduler.scheduleOnce(delay)(effect)(ec)` → `ctx.system.scheduler.scheduleOnce(java.time.Duration.of…, () => effect, ctx.executionContext)`
- `FiniteDuration` → `java.time.Duration`: `30.seconds` → `Duration.ofSeconds(30)`, `30.minutes` → `Duration.ofMinutes(30)` (no new import needed)
- Scheduler helper method at line 357 retained — still passed to `SNAPSyncController` at line 1431; Scaladoc updated to reflect sole remaining use
- 91/91 `SyncController` tests pass

**Ref:** DEFERRED-BACKLOG.md PRISM Post-Capstone Audit

---

## Quick reference — pick by time available

| Time | Task | Risk | Commit type |
|------|------|------|-------------|
| 15 min | C1 braceless config | ✅ DONE | — |
| 45 min | C2 return cleanup  | ✅ DONE | — |
| 30 min | C3 two quick warnings | ✅ DONE | — |
| 30 min | C3b promote warnings to errors (ratchet 4/4) | ✅ DONE | — |
| 1h | C4 extvm deletion | ✅ DONE `a948fda1d` | — |
| 1h | C5 console→logging | ✅ DONE `6a3e2cd88` | — |
| 2h+ | C6 dead code research | ✅ DONE — `dead-code-audit.md` (325 lines) | — |
| 2h+ | C7 Thread.sleep fixes | ✅ DONE — audit only; 7 real calls, all Bucket B (clock injection required) | — |
| deferred | C8a FastSyncBranchResolverActor | ✅ WIRED `ea60c4f29` — testEssential 3600/0 ✅ | — |
| UNBLOCKED | C8b MerkleProofVerifier dead private methods | ✅ DONE `cc7b58b3b` | — |
| ~1h | C9a SNAPSyncControllerSpec Thread.sleep ×2 | ✅ DONE `e9638ac52` — CountDownLatch gates | — |
| 30 min | C9b Discv4SyncResponderSpec Thread.sleep ×1 | ✅ DONE `ba9b9d463` — `fakeNanos` var in RateLimiter | — |
| 1h | C9c KeyStoreImplSpec Thread.sleep ×2 | ✅ DONE `479adc62f` — monotonic fake clock, go-ethereum compat preserved | — |
| 2h | C10 8a-retro batch 1: consensus/mining TestKit migration | ✅ DONE `0d65a85c4` — 5 files, 27/27; ForkChoiceManagerSpec leak fixed | — |
| 3h | C11 8a-retro batch 2: jsonrpc/ + graphql/ TestKit migration | ✅ DONE `b5e11c0a4` + `722f316f2` — 20 specs, 275/275; PatienceConfig + spawnAnonymous patterns documented | — |
| 15 min | C13 `Actor.noSender` → `ActorRef.noSender` (3 files) | ✅ DONE `417165930` | — |
| 30 min | C14 SyncController Classic scheduler → Typed `ctx.system.scheduler` (2 sites) | ✅ DONE `8d460a145` | — |
| 10 min | C15 SyncControllerSpec autopilot GetHandshakedPeersCmd handler (P2) | ✅ DONE `fc1030410` | — |
