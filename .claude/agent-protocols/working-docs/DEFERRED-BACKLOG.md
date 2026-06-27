# Fukuii Modernization — Deferred Backlog

**Last updated**: 2026-06-28
**Purpose**: Active deferred items only. Completed section detail lives in `completed/DEFERRED-BACKLOG.md`.

Active sprint plan: `/home/dev/.claude/plans/we-are-working-on-noble-whisper.md`

---

## Completed Sections

| Section | Description | Commit(s) | Date | Detail |
|---------|-------------|-----------|------|--------|
| Part 2 — subsystems 1–4 | Pekko migration: faucet, jsonrpc, transactions, OmmersPool+MockedMiner | `551bccfaf` `2ac71a58e` `1309bb968` `0be6dd776` `0aa837d5e` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| Part 2 — NET group | NPMA classicSystem.scheduler noted; actorSelection test item resolved | `4b101b612` `6b506a63f` | 2026-06-21 | completed/DEFERRED-BACKLOG.md |
| §3b | implicit class → extension methods | `c0a3612b4` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §3c | isInstanceOf/asInstanceOf audit (1 fixed, 82 intentional) | `7cc9eda3a` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §3d — SyncPhase/ForkId | Sealed trait → enum (SyncPhase + ForkIdValidationResult) | `adf4e69ea` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §3e | Console output → logging (12 fixed, 8 CLI preserved) | `c3fec6390` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §3f | Manual synchronization audit (20 sites; 1 FORGE-gated in CHASE-QUEUE) | `cf33cfa87` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §3g | StateValidator.scala exception swallowing | — | 2026-06-20 | completed/DEFERRED-BACKLOG.md |
| §3h | `Any` in type signatures (15 sites; 7 FORGE-gated deferred) | `cc63882fa` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §3i | BlockExecutionError hierarchy redesign (union type + `describe`) | `64ab4786e` `d4344962f` | 2026-06-23 | completed/DEFERRED-BACKLOG.md |
| §6a | extvm/ dead code deletion | `a948fda1d` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §7a | ADT consolidation | `04615ad43` `4e8b42263` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §7b | EventStream pub/sub topology map + migration | `849c0dcf0` `b35b35cf6` | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §7d | Post-CAPSTONE classic artifact audit (8-lens sweep) | docs only | 2026-06-21 | completed/DEFERRED-BACKLOG.md |
| §7e-P4/P4a | Design review: heal path completeness | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §8a batches 1–5 + §8a-infra-b/c | Classic TestKit → ActorTestKit migration (coordinator/heal specs, E165 floor) | `0d65a85c4` `b5e11c0a4` `722f316f2` `12c23cf8a` `a719520db` `5eae34c21` `5ff14017b` `8b9bef67d` `781c8e985` `a193bc794` `5f28e8ae6` | 2026-06-21–23 | completed/DEFERRED-BACKLOG.md |
| §8c H2/H3/H4/M1/M3/M4 | Memory/resource leak audit (H-series + most M-series) | `4907406fe` `ef75a5608` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §8d | IO threading model follow-up (BEACON EngineApiService; CONDUIT jsonrpc) | `0a8ed3038` `276c77735` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §8d-J | CONDUIT jsonrpc IO boundary scan (zero findings — no code changes) | docs only | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §8e | ScalaFix noReturns ratchet — ALL CLEAR (`ca1446e49` SNAP1 was final site) | `9eb1f4e06` `7a48c5988` `4544b8025` `09307c5a7` `d78177bda` `5a60b00ec` `ca1446e49` | 2026-06-22–27 | completed/DEFERRED-BACKLOG.md |
| §8f | Dead code audit: MetricsAlreadyConfiguredError, LocalVM, AdaptiveSyncStrategy, DeltaSpikeGauge, StaticNodesLoader | `fa57df9b9` `c6b3da4cb` `ff2fc219c` | 2026-06-22 | completed/DEFERRED-BACKLOG.md |
| §8k | Classic bridge elimination (Clusters A–N, B sprint, J PRISM audit) — TCP floor confirmed | `791c0211f`…`68035cb85` `35db7dc61` | 2026-06-23–25 | completed/DEFERRED-BACKLOG.md |
| §8l | VM tracer model (R1 research + implementation) | `37c9d081b` `5c2adeaaf` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9a | SyncStartupStrategy extraction | `3140db465` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9b | RegularSync divergence-path spec fix + LCA recovery test | `0d290019e` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9c | RegularSyncSpec full migration | `57d638d49` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| §9d | RegularSyncFixtures getSyncStatus Classic ask → Typed send | `69146a244` | 2026-06-24 | completed/DEFERRED-BACKLOG.md |
| Part 10 — P7 | Test suite timing audit + slow-test reduction (3,595/0 baseline, 680s) | `edfb69f35` | 2026-06-22–23 | completed/DEFERRED-BACKLOG.md |
| Part 11 — P8–P12 | Full test coverage audit: SyncTest rescue, DisabledTest, FlakyTest, SlowTest, tag taxonomy | multiple | 2026-06-22–24 | completed/DEFERRED-BACKLOG.md |
| §R3 | Jackson ecosystem gate research | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R5 | EventStream pub/sub topology map | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R6 | Opaque type domain analysis (`.local/docs/opaque-type-domain-analysis.md`) | docs only | 2026-06-25 | completed/DEFERRED-BACKLOG.md |
| §8b L1–H2 | Opaque types (ByteString/BigInt): TxHash, BloomFilter, BlobVersionedHash, StorageKey A/B/C, CodeHash, BlockHash, TrieRoot | `dc17d24ef` `d8a9f3905` `cb29e34aa` `c7c394a4c` `9ea57b007` `328508bd3` `c98b61064` `7fb117918` `29cbe38e0` | 2026-06-25–26 | completed/DEFERRED-BACKLOG.md |
| §R8 | Memory/resource retention audit | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R9 | IO threading model audit | — | 2026-06 | completed/DEFERRED-BACKLOG.md |
| §R10 | ETH/Sepolia assumption audit (10 threads, all complete) | multiple | 2026-06-24–25 | completed/DEFERRED-BACKLOG.md |
| §R11 / Part 16 | ETC-only artifact sweep + ETH sprint items (T1–T10, all threads) | `e0cebcd72` `6b2b41e49` `d1a7073bf` `6be73300f` `2277555c7` `e701281a0` `9591ff9e1` + ETH sprint commits | 2026-06-24–26 | completed/DEFERRED-BACKLOG.md |
| §G5 | BlockExecution.applyEip2935 account-existence guard + BlockHashHistorySpec absent-account test | `e7352b206` | 2026-06-21 | completed/DEFERRED-BACKLOG.md |
| Known Pre-existing Failures | KzgPointEvaluationSpec JVM SIGSEGV + post-rebase SNAP/heal staging feature gap | `202a814e3` `3aefb0da4` | 2026-06-27 | completed/DEFERRED-BACKLOG.md |
| §7c | Pekko supervision hierarchy: 6 STOP-AND-ALERT actors + 49-actor restart strategy sweep | `d28a803f7` `d3399f562` `429b8678b` `fbce2cc28` `a0f7fcb40` `b1aefaaba` (merge `--no-ff`) | 2026-06-27 | completed/DEFERRED-BACKLOG.md |
| §7f | ForkChoiceManager.setListener — TypedActorRef narrow adapter; last non-TCP `.toClassic` removed | `456f12499` | 2026-06-27 | completed/DEFERRED-BACKLOG.md |
| §8a-E6b | ChainWeightCalibrationSpec — Typed rewrite: `GetHandshakedPeersCmd` + `CalibrateChainWeightNowCmd`; all Classic imports replaced | `3c4b15543` | 2026-06-27 | completed/DEFERRED-BACKLOG.md |
| §8g | Braceless Scala 3 syntax — `removeOptionalBraces = true`, full 957-file sweep + one `()` fix | `84aa43575` | 2026-06-28 | completed/DEFERRED-BACKLOG.md |

---

## Part 1: Deferred — Remaining Compiler Warnings

**Status: W2-P1 sweep COMPLETE. ~507 non-E165 → 87 non-E165 remaining (all externally gated).**

**Earlier commits:** see `completed/DEFERRED-BACKLOG.md` W2-P1 History section.

### Remaining 87 Non-E165 Warnings

| Count | Cause | Gate |
|-------|-------|------|
| 68 | `json4s extract[T]` Manifest synthesis (16 files) | json4s 4.x upgrade (§4e) |
| 9 | `OpCode.scala` — infix ops + wildcard in `vm/` | FORGE gate |
| 2 | RocksDB `ClockCache` deprecated | Library upgrade |
| 2 | diffx `DiffMatcher` | Library upgrade |
| 1 | Guava `CacheBuilder` | Library upgrade |
| 1 | `EngineApiService.scala:661` `Ordering.Iterable` (tx sort key) | BEACON gate — not a correctness concern (T10 verdict 2026-06-24) |
| 1 | web3j `Admin` deprecated | Library upgrade |
| 1 | `TrieNodeHealingCoordinator` inside Pekko library boundary | Unfixable |
| 1 | `PeerRequestHandler` `ClassTag` unsound type-test | Needs `TypeTest[A,B]` (non-trivial) |

**E165 floor: 333** (intentional Pekko Classic bridges — permanent).

---

## Part 2: Pekko Classic → Typed Migration (Network/P2P Sprint)

**Status**: Subsystems 1–4 DONE (see completed). NET group DONE (see completed). Remaining: network/P2P sprint.
**Spec**: `pekko-typed-migration-p2.md` | **Agent**: LOOM

### Remaining Classic→Typed Bridges (require network/P2P sprint to remove)

| Bridge | Location | Why it exists | Removed when |
|--------|----------|---------------|--------------|
| `system.spawn(...)` via `adapter._` | `NodeBuilder.OmmersPoolBuilder`, `MockedMiner.spawn` | Classic root can't spawn Typed children natively | Root flipped to `ActorSystem[Nothing]` |
| `system.toTyped.scheduler` | `PoWBlockCreator`, `PoWMining` | Typed ask needs a Typed Scheduler | Same — root flip |
| `MockedMiner.Send(msg, replyTo)` envelope | `PoWMining.sendMiner/askMiner`, callers | External `MockedMinerProtocol` API kept stable | When `Mining` API is absorbed into `Command` ADT |
| `ctx.messageAdapter` / `toClassic.eventStream` | PTM (`transactions/`, P2c) | `eventStream` is Classic | Root flip + eventStream modernization |

### Network/P2P Sprint — Migration Completion Gate

`network/` and `sync/` actors (~20 files, ~25k LOC) must be migrated in their own sprint (R1 research → implementation):
- Deeply coupled to the wire protocol (HERALD review required per subsystem)
- SNAPSyncController alone is 5,052 LOC
- Migration atomicity requires grouping by caller coherence

**After all network/sync actors migrated:**
1. Flip `NodeBuilder.system` → `ActorSystem[Nothing]`
2. Remove `system.toTyped.scheduler` shims in `PoWBlockCreator`, `PoWMining`
3. Absorb `MockedMinerProtocol` into `MockedMiner.Command` ADT
4. Replace `toClassic.eventStream` bridges with native Typed `EventStream`

**Sequence:** Run R1 from `WAVE3-RESEARCH-PLAN.md` → implement per R1 output → see SPRINT-QUEUE.md Part 6.

### Pre-flight Before Each Subsystem

```bash
grep -rn "import .*\._" src/main/scala/ --include="*.scala" | wc -l  # must be 0
grep -rn "eventStream\.publish\|eventStream\.tell\|eventStream\.subscribe" src/main/scala/ --include="*.scala"
```

### Rejection Criteria

- `grep -rn "import .*\._" src/main/ | wc -l` > 0 at migration start
- PTM eventStream types cross network boundary → full `@SerializabilityTrait` pre-flight required
- Any file under `consensus/`, `vm/`, `crypto/`, `domain/` touched → invoke FORGE or BEACON before proceeding
- `sbt testEssential` drops below 3,601 tests

---

## Part 3: Scala 3 Modernization

**Sections §3b–§3i all COMPLETE** — see completed table above.

### 3a — implicit → given/using

**Spec**: `scala-implicit-to-given.md` | **Agent**: MITHRIL (after `sbt scalafix GivenUsing`)
**Blast radius**: 198 files, 522 `implicit val/def` declarations

Hotspot files (highest density — start here):
1. `jsonrpc/McpJsonMethodsImplicits.scala` — 32 implicits
2. `utils/Picklers.scala` — 21
3. `jsonrpc/JsonMethodsImplicits.scala` — 21
4. `jsonrpc/EthBlocksJsonMethodsImplicits.scala` — 19

**Prerequisite**: Add `GivenUsing` to `.scalafix.conf` BEFORE running. Must run AFTER Pekko migration sprint.

### 3d — sealed trait → enum (residual note)

`SyncProtocol.Status.Syncing` ❌ REJECTED (2026-06-25) — `case class` with three constructor params; cannot be an enum. `Blacklist.BlacklistReason` and `BlacklistReasonType` also REJECTED (non-pure discriminants). No further candidates identified.

---

## Part 4: Dependency Upgrades (blocked or deferred)

### 4a — JLine 3.x → 4.x

**Current pin**: `3.30.13` | **Target**: `4.1.x`
**Why deferred**: JLine 4.x is not drop-in compatible. Two files need refactoring:
- `console/TuiRenderer.scala` — `AttributedString`, `AttributedStyle`
- `console/Tui.scala` — `Terminal`, `TerminalBuilder`

**Prerequisite**: Dedicated jline-upgrade sprint; assess TUI rendering test impact.

### 4b — Logstash-Logback-Encoder 8.x → 9.x

**Current pin**: `8.1` | **Target**: `9.0`
**Why deferred**: 9.0 requires Jackson 3 exclusively (jackson-bom:3.0.1). Also bumps minimum Java to 17.
**Prerequisite**: 4e (Jackson 2→3) first.

### 4c — Kanela-Agent 1.x → 2.x

**Why deferred**: Complete rewrite (AspectJ → ByteBuddy). Requires Kamon 2.8.1+ and custom instrumentation recompile.
**Prerequisite**: Kamon 2.8.1+ confirmed stable; dedicated sprint with changelog review.

### 4e — Jackson 2.x → 3.x (transitive)

**Status**: BLOCKED — json4s is the sole blocker.

| Library | Gate | Finding |
|---------|------|---------|
| json4s | **GATE NEARLY OPEN** | `4.2.0-M5-SNAPSHOT` already imports `tools.jackson.databind.*` (Jackson 3). Latest stable is still `4.1.1` (Jackson 2). Gate opens on M5 release. |
| circe | **NON-BLOCKER** | No Jackson dependency in circe-core. |
| sangria / sangria-circe | **NON-BLOCKER** | Zero Jackson references. |

**Gate condition**: `json4s 4.2.0-M5` (or later) published. Verify at release tag: must reference `tools.jackson.core` (Jackson 3), not `com.fasterxml.jackson.core` (Jackson 2).

**Watch**: https://github.com/json4s/json4s/tags — last tagged release `4.2.0-M4` (Jun 11 2026).

**Alternative unblock**: Migrate `jsonrpc/` from `json4s-native` to circe (~79 files, `JValue → io.circe.Json`). Eliminates the Jackson gate permanently.

---

## Part 5: Blocked (gate conditions)

### 5a — Scala 3.9 Upgrade

**Gate**: Scala 3.9.x LTS appears on endoflife.date with LTS designation
**Action when unblocked**: Bump `scalaVersion` in `build.sbt`; update scapegoat; run `sbt compile-all`.

### 5b — Virtual Threads / Ox Evaluation

**Status**: Research only — low priority
**Reference**: `.claude/virtuslab/scala-skill/direct-style-scala/SKILL.md`

### 5c — Constitution v1.2.0

**Gate**: 5a (Scala 3.9 upgrade).

---

## Part 7: Post-CAPSTONE Typed API Maturity

**§7a, §7b, §7d, §7e-P4/P4a all DONE** — see completed table above.

### 7c — Typed Supervision Hierarchy ✅ DONE 2026-06-27

**See `completed/DEFERRED-BACKLOG.md §7c` for full detail.**

49 actors audited (zero prior `Behaviors.supervise` wrappers). Implemented via shared `wt/7c-sprint` worktree (P0 → D → E1 ‖ A → B → C → E3 → merge):
- **P0** `d28a803f7` — `alert-wrapper-protocol.md` (new protocol)
- **D** `d3399f562` — 6 STOP-AND-ALERT actors: `ctx.watchWith` + `CriticalActorAlerter`; parents stop on failure
- **A** `429b8678b` — 10 Group-A infrastructure actors: `restartWithBackoff` / `restart`
- **B** `fbce2cc28` — SNAP workers (`restart.withLimit(5,1m)`), coordinators (backoff 1s/10s), 11 sync-support actors
- **C** `a0f7fcb40` — BlockFetcher ghost-child rationale (RF-2 Option A); `BlockImporter` `restartWithBackoff` (E1: both chains idempotent ✅)
- **E3** `b1aefaaba` — `SyncStateSchedulerActor` `restartWithBackoff(5s,60s,0.3,max=2)` replacing unbounded restart

---

### 7e-P2 — HealingState Extraction (SSC)

**What**: Six SSC fields are semantically phase-local to the healing phase (`trieWalkInProgress`, `healingServeRootRequestInFlight`, and four related vars). Currently on `Impl`, they bleed across pivot refreshes if a pivot rotation occurs while healing. Extract to `case class HealingState(...)` threaded as a behavior parameter.

**Scope**: `sync/snap/SNAPSyncController.scala` — `Impl` field extraction + healing behavior split.
**Gate**: 7a ✅ done. HERALD pre-flight recommended for pivot-rotation interaction.
**Priority**: LOW — latent correctness risk on pivot rotation during heal; not currently triggered.
**Agent**: LOOM + EYE.

---

### 7e-P3 — ChainDownloader Stagnation Push (SSC)

**What**: SSC currently polls `ChainDownloader` progress timestamps to detect download stagnation (`CheckDownloadStagnation` timer). Invert: `ChainDownloader` pushes a `DownloadStagnated` message to SSC when it detects stagnation internally.

**Scope**: `sync/snap/ChainDownloader.scala` + `sync/snap/SNAPSyncController.scala` — stagnation detection path only.
**Gate**: 7a ✅ done.
**Priority**: LOW — polling timer works correctly; this is a design improvement (back-pressure, reduced coupling).
**Agent**: LOOM + HERALD + EYE.

---

### 7f — ForkChoiceManager.setListener ✅ DONE 2026-06-27

**See `completed/DEFERRED-BACKLOG.md §7f` for full detail.**

Implemented ahead of gate as part of §8k-G4a. `ForkChoiceManager.listenerRef` is now
`AtomicReference[Option[TypedActorRef[ForkChoiceManager.BeaconHead]]]`. `SyncController` passes
`ctx.messageAdapter[ForkChoiceManager.BeaconHead](WrappedExternal.apply)` — a narrow typed adapter.
Last non-TCP `.toClassic` removed.
- **`456f12499`** — §8k-G4a: ForkChoiceManager.setListener TypedActorRef narrow adapter

---

## Part 8: Additional Modernization Gaps

### 8a — Classic TestKit → ActorTestKit Migration

**Batches 1–5 + §8a-infra-b/c DONE** — see completed table above. **E165 floor now 65 unnarrowed sites.**

**Remaining (Batch E6):** PeerActorSpec + RLPxConnectionHandlerSpec — **wait for Wave 3** (net/P2P sprint). RegularSyncSpec ✅ DONE `57d638d49`. BlockFetcherSpec + PendingTxMgrSpec ✅ DONE `5ff14017b`.


**Research prompt for remaining §8a-retro batches:**
> List test files still using Classic TestKit:
> ```bash
> grep -rn "org.apache.pekko.testkit.TestKit" src/test/ --include="*.scala" -l | grep -v "ActorTestKit"
> ```
> For each file: (a) which production actor it tests (must already be Typed), (b) `fishForMessage` → `expectMessageType` replacements, (c) any `system.toClassic` needs (Pekko HTTP, Classic eventStream).

---

### 8b — Opaque Types for Domain Value Concepts

**L1–L3, M1–M4, H1–H2 DONE** — commits `dc17d24ef`/`d8a9f3905`/`cb29e34aa`/`c7c394a4c`/`9ea57b007`/`328508bd3`/`c98b61064`/`7fb117918`/`29cbe38e0`. Detail: `completed/DEFERRED-BACKLOG.md §8b`.

**Remaining HIGH tier (H3–H8):** Gate on H2 met. FORGE + BEACON required for all.

| Candidate | Raw type | Files | Gate |
|-----------|----------|-------|------|
| `Difficulty` (H3) | `BigInt` | ~24 | FORGE + BEACON |
| `TotalDifficulty` (H4) | `BigInt` | ~26 | FORGE + BEACON; gate: H3 |
| `GasAmount` (H5) | `BigInt` | ~39 | FORGE + BEACON; gate: H4 |
| `GasPrice` (H6) | `BigInt` | ~25 | FORGE + BEACON; gate: H5 |
| `BlockNumber` (H7) | `BigInt` | ~124 | FORGE + BEACON; gate: H6 — largest, fork dispatch |
| `ChainId` (H8) | `BigInt` | ~27 | FORGE + BEACON; gate: H7 — signing layer, last |

**Caveats:**
- `UInt256` and `Address` are hand-rolled wrappers — do not introduce competing types.
- `ECDSASignature(r, s, v: BigInt)` — crypto domain, leave as-is.

---

#### §8b-H3 — `Difficulty`: BigInt → opaque type

**Files:** `domain/Difficulty.scala` (new), `BlockHeader.scala`, Ethash mining layer, difficulty calculator (~24 files)
**Agent:** MITHRIL + FORGE + BEACON | **Gate:** H2 done ✅.

**Prompt:**
```
Use MITHRIL to implement `opaque type Difficulty = BigInt`, then ask FORGE and BEACON to review before committing.

Context: `BlockHeader.difficulty` is PoW-specific on ETC. Post-merge ETH stores the field as prevRandao — not used for PoW. Cross-chain field on BlockHeader. ~24 files.
Reference: `.local/docs/opaque-type-domain-analysis.md` §8b-H3.

Step 0 — Worktree setup (run from main checkout — parallel-safe, starts immediately):
  git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/8b-h3 -b wt/8b-h3 scala3-cleanup-june
  cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8b-h3

Pre-flight: confirm `sbt compile-all` is clean.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/Difficulty.scala`:
  opaque type Difficulty = BigInt
  object Difficulty:
    val Zero: Difficulty = BigInt(0)
    def apply(v: BigInt): Difficulty = v
    extension (d: Difficulty)
      def value: BigInt = d
      def +(other: Difficulty): Difficulty = d + other
      def compare(other: Difficulty): Int = d.compare(other)
    given rlpCodec: RLPCodec[Difficulty] = summon[RLPCodec[BigInt]].xmap(Difficulty.apply, _.value)
    given Ordering[Difficulty] = Ordering.by(_.value)

Step 2 — Update `BlockHeader.scala`: `difficulty: BigInt` → `Difficulty`. Use `sbt compile` between files (BlockHeader has 50+ dependents).
Step 3 — Update Ethash mining + difficulty-bomb/adjustment sites. Wrap return values with `Difficulty(...)`, unwrap with `.value` at arithmetic boundaries.
Step 4 — `sbt compile-all` — must be clean.
Step 5 — FORGE review: confirm ETC Ethash difficulty calculation semantics preserved (ECIP-1099, bomb removal).
Step 6 — BEACON review: confirm ETH post-merge `difficulty=0` / prevRandao not broken.
Step 7 — `sbt "testOnly *BlockHeader* *Ethash* *Difficulty*"`.
Step 8 — `git commit -m "feat(8b-H3): Difficulty opaque type (BigInt) — BlockHeader + Ethash sites, FORGE+BEACON reviewed"`
Step 9 — Merge back (from /media/dev/2tb/dev/fukuii):
  cd /media/dev/2tb/dev/fukuii
  git merge --no-ff wt/8b-h3
  git worktree remove .claude/worktrees/8b-h3
  git branch -d wt/8b-h3
```

---

#### §8b-H4 — `TotalDifficulty`: BigInt → opaque type

**Files:** `domain/TotalDifficulty.scala` (new), `ChainWeight.scala`, `BlockchainReader.scala`, MESS weight sites (~26 files)
**Agent:** MITHRIL + FORGE + BEACON | **Gate:** H3 committed.

**Prompt:**
```
Use MITHRIL to implement `opaque type TotalDifficulty = BigInt`, then ask FORGE and BEACON to review before committing.

Context: `ChainWeight.totalDifficulty` drives MESS (Modified Exponential Subjective Scoring) on ETC — consensus-critical for ETC chain-selection. ETH stores TD for historical sync only.
Reference: `.local/docs/opaque-type-domain-analysis.md` §8b-H4.

Step 0 — Gate check + worktree setup (H3 must be merged to scala3-cleanup-june first):
  grep "opaque type Difficulty" /media/dev/2tb/dev/fukuii/src/main/scala/com/chipprbots/ethereum/domain/Difficulty.scala
  # If grep succeeds, H3 is merged. Create worktree:
  git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/8b-h4 -b wt/8b-h4 scala3-cleanup-june
  cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8b-h4

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/TotalDifficulty.scala`:
  opaque type TotalDifficulty = BigInt
  object TotalDifficulty:
    val Zero: TotalDifficulty = BigInt(0)
    def apply(v: BigInt): TotalDifficulty = v
    extension (td: TotalDifficulty)
      def value: BigInt = td
      def +(d: Difficulty): TotalDifficulty = td + d.value
      def compare(other: TotalDifficulty): Int = td.compare(other)
    given rlpCodec: RLPCodec[TotalDifficulty] = summon[RLPCodec[BigInt]].xmap(TotalDifficulty.apply, _.value)
    given Ordering[TotalDifficulty] = Ordering.by(_.value)

Step 2 — Update `ChainWeight.scala`: `totalDifficulty: BigInt` → `TotalDifficulty`. MESS comparisons go through `Ordering[TotalDifficulty]`.
Step 3 — Update `BlockchainReader.scala`: TD retrieval sites — wrap with `TotalDifficulty(...)`.
Step 4 — Check all MESS callers: `grep -rn "totalDifficulty\|ChainWeight" src/main/ --include="*.scala"`.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — FORGE review: confirm MESS weight comparison semantics preserved (critical for ETC chain selection).
Step 7 — BEACON review: confirm ETH total-difficulty storage + terminal TD semantics unchanged.
Step 8 — `sbt "testOnly *ChainWeight* *MESS* *BlockchainReader*"`.
Step 9 — `git commit -m "feat(8b-H4): TotalDifficulty opaque type (BigInt) — ChainWeight + MESS sites, FORGE+BEACON reviewed"`
Step 10 — Merge back (from /media/dev/2tb/dev/fukuii):
  cd /media/dev/2tb/dev/fukuii
  git merge --no-ff wt/8b-h4
  git worktree remove .claude/worktrees/8b-h4
  git branch -d wt/8b-h4
```

---

#### §8b-H5 — `GasAmount`: BigInt → opaque type

**Files:** `domain/GasAmount.scala` (new), `BlockHeader.scala` (gasLimit/gasUsed), `Transaction.scala` (gasLimit) (~39 files). VM `ProgramState` gas counter NOT in scope.
**Agent:** MITHRIL + FORGE + BEACON | **Gate:** H4 committed.

**Prompt:**
```
Use MITHRIL to implement `opaque type GasAmount = BigInt` for gasLimit/gasUsed fields, then ask FORGE and BEACON to review before committing.

Context: `gasLimit`/`gasUsed` on BlockHeader and `gasLimit` on Transaction are consensus-critical. EIP-1559 adjusts gasTarget on ETH (gasLimit/2); ETC uses a fixed gas model. STOP at `ProgramState.scala` — VM gas counter stays as BigInt.
Reference: `.local/docs/opaque-type-domain-analysis.md` §8b-H5.

Step 0 — Gate check + worktree setup (H4 must be merged to scala3-cleanup-june first):
  grep "opaque type TotalDifficulty" /media/dev/2tb/dev/fukuii/src/main/scala/com/chipprbots/ethereum/domain/TotalDifficulty.scala
  git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/8b-h5 -b wt/8b-h5 scala3-cleanup-june
  cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8b-h5

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/GasAmount.scala`:
  opaque type GasAmount = BigInt
  object GasAmount:
    val Zero: GasAmount = BigInt(0)
    def apply(v: BigInt): GasAmount = v
    extension (g: GasAmount)
      def value: BigInt = g
      def +(other: GasAmount): GasAmount = g + other
      def -(other: GasAmount): GasAmount = g - other
      def <(other: GasAmount): Boolean = g < other
      def <=(other: GasAmount): Boolean = g <= other
      def compare(other: GasAmount): Int = g.compare(other)
    given rlpCodec: RLPCodec[GasAmount] = summon[RLPCodec[BigInt]].xmap(GasAmount.apply, _.value)
    given Ordering[GasAmount] = Ordering.by(_.value)

Step 2 — Update `BlockHeader.scala`: `gasLimit: BigInt` and `gasUsed: BigInt` → `GasAmount`. Use `sbt compile` between files.
Step 3 — Update `Transaction.scala`: `gasLimit: BigInt` → `GasAmount`.
Step 4 — STOP: do not touch `ProgramState.scala` — VM gas counter stays BigInt.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — FORGE review: confirm ETC block gas limit and transaction gas semantics unchanged.
Step 7 — BEACON review: confirm ETH EIP-1559 gasTarget computation (`gasLimit / 2`) still correct after wrapping.
Step 8 — `sbt "testOnly *BlockHeader* *Transaction* *Gas*"`.
Step 9 — `git commit -m "feat(8b-H5): GasAmount opaque type (BigInt) — BlockHeader.gasLimit/gasUsed + Transaction.gasLimit, FORGE+BEACON reviewed"`
Step 10 — Merge back (from /media/dev/2tb/dev/fukuii):
  cd /media/dev/2tb/dev/fukuii
  git merge --no-ff wt/8b-h5
  git worktree remove .claude/worktrees/8b-h5
  git branch -d wt/8b-h5
```

---

#### §8b-H6 — `GasPrice`: BigInt → opaque type

**Files:** `domain/GasPrice.scala` (new), `Transaction.scala` (gasPrice/maxFeePerGas/maxPriorityFeePerGas), `BlockHeader.baseFeePerGas`, fee-calculation sites (~25 files)
**Agent:** MITHRIL + FORGE + BEACON | **Gate:** H5 committed.

**Prompt:**
```
Use MITHRIL to implement `opaque type GasPrice = BigInt` for gas price and EIP-1559 fee fields, then ask FORGE and BEACON to review before committing.

Context: `gasPrice` (legacy/EIP-2930), `maxFeePerGas`/`maxPriorityFeePerGas` (EIP-1559 ETH), `BlockHeader.baseFeePerGas` (EIP-1559 ETH). ETC uses legacy gasPrice only. Fee burn on ETH is consensus-critical.
Reference: `.local/docs/opaque-type-domain-analysis.md` §8b-H6.

Step 0 — Gate check + worktree setup (H5 must be merged first):
  grep "opaque type GasAmount" /media/dev/2tb/dev/fukuii/src/main/scala/com/chipprbots/ethereum/domain/GasAmount.scala
  git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/8b-h6 -b wt/8b-h6 scala3-cleanup-june
  cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8b-h6

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/GasPrice.scala`:
  opaque type GasPrice = BigInt
  object GasPrice:
    val Zero: GasPrice = BigInt(0)
    def apply(v: BigInt): GasPrice = v
    extension (gp: GasPrice)
      def value: BigInt = gp
      def *(units: GasAmount): BigInt = gp * units.value   // fee = price * units → raw BigInt
      def compare(other: GasPrice): Int = gp.compare(other)
      def min(other: GasPrice): GasPrice = if gp <= other then gp else other
    given rlpCodec: RLPCodec[GasPrice] = summon[RLPCodec[BigInt]].xmap(GasPrice.apply, _.value)
    given Ordering[GasPrice] = Ordering.by(_.value)

Step 2 — Update `Transaction.scala`: `gasPrice`, `maxFeePerGas`, `maxPriorityFeePerGas` → `GasPrice`. Also `BlockHeader.baseFeePerGas: Option[BigInt]` → `Option[GasPrice]`.
Step 3 — Update fee-calculation sites: `fee = gasPrice * gasUsed` → use extension `*(units: GasAmount): BigInt`.
Step 4 — `sbt compile-all` — must be clean.
Step 5 — FORGE review: confirm ETC legacy transaction fee unchanged; `baseFeePerGas` is `None` on ETC.
Step 6 — BEACON review: confirm ETH EIP-1559 baseFee burn byte-perfect: `baseFeePerGas.value * gasUsed.value`.
Step 7 — `sbt "testOnly *Transaction* *GasPrice* *BaseFee*"`.
Step 8 — `git commit -m "feat(8b-H6): GasPrice opaque type (BigInt) — Transaction fee fields + BlockHeader.baseFeePerGas, FORGE+BEACON reviewed"`
Step 9 — Merge back (from /media/dev/2tb/dev/fukuii):
  cd /media/dev/2tb/dev/fukuii
  git merge --no-ff wt/8b-h6
  git worktree remove .claude/worktrees/8b-h6
  git branch -d wt/8b-h6
```

---

#### §8b-H7 — `BlockNumber`: BigInt → opaque type

**Files:** `domain/BlockNumber.scala` (new), `BlockHeader.scala` (number field), all fork-dispatch sites (~124 files — largest sweep)
**Agent:** MITHRIL + FORGE + BEACON | **Gate:** H6 committed. **Largest sweep — use `sbt compile` between every file.**

**Prompt:**
```
Use MITHRIL to implement `opaque type BlockNumber = BigInt`, then ask FORGE and BEACON to review before committing. Largest §8b sweep (~124 files) — use `sbt compile` between every file; `sbt compile-all` only at the end.

Context: `BlockHeader.number` and ETC `forBlock(blockNumber: BigInt)` fork-dispatch calls are consensus-critical. DO NOT change `forBlock()`/`forTimestamp()` signatures — wrap only the stored `number` field; pass `.value` at fork-dispatch call sites.
Reference: `.local/docs/opaque-type-domain-analysis.md` §8b-H7.

Step 0 — Gate check + worktree setup (H6 must be merged first; largest sweep — allocate full session):
  grep "opaque type GasPrice" /media/dev/2tb/dev/fukuii/src/main/scala/com/chipprbots/ethereum/domain/GasPrice.scala
  git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/8b-h7 -b wt/8b-h7 scala3-cleanup-june
  cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8b-h7

Read §8b-H7 in analysis doc for the full 124-file site inventory.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/BlockNumber.scala`:
  opaque type BlockNumber = BigInt
  object BlockNumber:
    val Genesis: BlockNumber = BigInt(0)
    def apply(v: BigInt): BlockNumber = v
    def apply(v: Long): BlockNumber = BigInt(v)
    extension (bn: BlockNumber)
      def value: BigInt = bn
      def toLong: Long = bn.toLong
      def +(n: Long): BlockNumber = bn + n
      def -(n: Long): BlockNumber = bn - n
      def compare(other: BlockNumber): Int = bn.compare(other)
      def <(other: BlockNumber): Boolean = bn < other
      def <=(other: BlockNumber): Boolean = bn <= other
      def >(other: BlockNumber): Boolean = bn > other
    given rlpCodec: RLPCodec[BlockNumber] = summon[RLPCodec[BigInt]].xmap(BlockNumber.apply, _.value)
    given Ordering[BlockNumber] = Ordering.by(_.value)

Step 2 — Update `BlockHeader.scala`: `number: BigInt` → `BlockNumber`.
Step 3 — Fix callers in batches (`sbt compile` after each batch):
  Batch A: `domain/` files
  Batch B: `blockchain/` storage + reader
  Batch C: `sync/` files
  Batch D: `network/` files
  Batch E: `jsonrpc/` files
  Batch F: fork-config files — pass `.value` into `forBlock(bn.value)`, do NOT change dispatch signatures
Step 4 — `sbt compile-all` — must be clean.
Step 5 — FORGE review: confirm ETC `forBlock(bn.value)` dispatch receives a BigInt — semantics preserved.
Step 6 — BEACON review: confirm ETH BlockHeader.number field encoding unchanged.
Step 7 — `sbt "testOnly *BlockHeader* *Block*"`.
Step 8 — `sbt testVM testCrypto` — VM opcode block-number reads.
Step 9 — `git commit -m "feat(8b-H7): BlockNumber opaque type (BigInt) — BlockHeader + all callers (~124 files), FORGE+BEACON reviewed"`
Step 10 — Merge back (from /media/dev/2tb/dev/fukuii):
  cd /media/dev/2tb/dev/fukuii
  git merge --no-ff wt/8b-h7
  git worktree remove .claude/worktrees/8b-h7
  git branch -d wt/8b-h7
```

---

#### §8b-H8 — `ChainId`: BigInt → opaque type

**Files:** `domain/ChainId.scala` (new), `Transaction.scala` (EIP-155 signing), `ECDSASignature.scala`, fork-config chain-ID fields (~27 files)
**Agent:** MITHRIL + FORGE + BEACON | **Gate:** H7 committed. **HIGHEST RISK — signing layer. Both FORGE and BEACON sign-off mandatory before commit.**

**Prompt:**
```
Use MITHRIL to implement `opaque type ChainId = BigInt`, then get mandatory FORGE AND BEACON review before committing. Touching the signing layer — a byte-encoding mismatch invalidates all transactions.

Context: EIP-155 recovery: `v = 2 * chainId + 35 or 36`. ETC chainId=61, ETH chainId=1/11155111.
Reference: `.local/docs/opaque-type-domain-analysis.md` §8b-H8.

Step 0 — Gate check + worktree setup (H7 must be merged first; HIGHEST RISK — allocate dedicated session):
  grep "opaque type BlockNumber" /media/dev/2tb/dev/fukuii/src/main/scala/com/chipprbots/ethereum/domain/BlockNumber.scala
  git -C /media/dev/2tb/dev/fukuii worktree add .claude/worktrees/8b-h8 -b wt/8b-h8 scala3-cleanup-june
  cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8b-h8

Read `ECDSASignature.scala` + Transaction EIP-155 signing paths in full before touching any file.

Step 1 — Create `src/main/scala/com/chipprbots/ethereum/domain/ChainId.scala`:
  opaque type ChainId = BigInt
  object ChainId:
    val ETC: ChainId = BigInt(61)
    val ETH: ChainId = BigInt(1)
    val Mordor: ChainId = BigInt(63)
    val Sepolia: ChainId = BigInt(11155111)
    def apply(v: BigInt): ChainId = v
    extension (cid: ChainId)
      def value: BigInt = cid
      def recoveryV(isOdd: Boolean): BigInt = 2 * cid + (if isOdd then 36 else 35)  // EIP-155
    given rlpCodec: RLPCodec[ChainId] = summon[RLPCodec[BigInt]].xmap(ChainId.apply, _.value)

Step 2 — Update fork config chain-ID fields: wrap with `ChainId(...)`.
Step 3 — Update `ECDSASignature.scala`: EIP-155 `v` computation — use `chainId.recoveryV(isOdd)`. Verify `2 * 61 + 35 = 157` / `2 * 61 + 36 = 158` unchanged with a unit test.
Step 4 — Update `Transaction.scala`: `chainId: Option[BigInt]` → `Option[ChainId]`. Unwrap with `.value` into arithmetic only.
Step 5 — `sbt compile-all` — must be clean.
Step 6 — FORGE review (mandatory): verify ETC EIP-155 signing (chainId=61): v=157 or 158. Recovery computation byte-identical.
Step 7 — BEACON review (mandatory): verify ETH EIP-155 and EIP-2718 typed tx chainId encoding unchanged.
Step 8 — `sbt "testOnly *Transaction* *ECDSA* *ChainId* *Sign*"`.
Step 9 — `sbt testVM testCrypto` — full crypto stack.
Step 10 — `git commit -m "feat(8b-H8): ChainId opaque type (BigInt) — EIP-155 signing layer, FORGE+BEACON reviewed"`
Step 11 — Merge back (from /media/dev/2tb/dev/fukuii):
  cd /media/dev/2tb/dev/fukuii
  git merge --no-ff wt/8b-h8
  git worktree remove .claude/worktrees/8b-h8
  git branch -d wt/8b-h8
```

---

### 8c — Memory/Resource Leak Audit

**H2/H3 DONE** `4907406fe`, **H4+M1 DONE** `ef75a5608`, **M3/M4 DONE (by-design)** — see completed table.

**Remaining deferred (no prompt yet, VAULT gate):**
- M2 — EngineApiService hash-keyed maps with finalized-watermark prune (BEACON + CONDUIT)
- L1/L2/L3 — lower-priority lifecycle items identified in R8 audit

See `completed/DEFERRED-BACKLOG.md §8c` for full M2/L1/L2/L3 context.

---

### 8e — ScalaFix Ruleset Expansion + `noReturns` Ratchet Lock

**ALL SITES CLEARED — see completed table.**

**Full ratchet lock checklist:**
1. ~~C2 chore~~ ✅ `9eb1f4e06`
2. ~~LOOM Phase 0 TNHC~~ ✅ `7a48c5988`
3. ~~FORGE: 6 consensus sites~~ ✅ `4544b8025` (6 CLEAR + 9 DEFER `scalafix:ok`)
4. ~~StackTrie `:120`+`:462` DEFER re-assessment~~ ✅ `09307c5a7` (both CLEAR)
5. ~~BEACON: 3 ETH Engine API sites~~ ✅ `d78177bda`
6. ~~BEACON: 2 remaining ETH Engine API sites~~ ✅ `5a60b00ec` (EngineApiController return → expression — S3-D BEACON cleared)
7. ~~SNAPSyncController 36 sites~~ ✅ `ca1446e49` (return statements removed, scalafix clean — SNAP1 cleared without waiting for Wave 3 LOOM migration)
8. ~~Run `sbt scalafixAll` → 0 violations → ratchet locked.~~

---

**Other rules to evaluate enabling (unblocked, lower priority):**
```
LeakingImplicitClassVal  # implicit class vals that escape scope
OrganizeImports          # import grouping/deduplication
ExplicitResultTypes      # explicit return types on public defs (enable gradually)
```

---

### 8i — RLP Typeclass Derivation Modernization

**Current state**: 183 `implicit val`/`def` RLP encoder/decoder instances. Two categories:
1. **Product type codecs** (simple ADTs) — can use Magnolia/Shapeless-style derivation via `given RLPEncoder[MyType] = RLPEncoder.derived`.
2. **Custom-layout codecs** (ETH wire protocol) — MUST remain handwritten.

**Gate:** Part 3a (implicit→given) complete. R7 research thread complete.
**Parallel-safe:** NO — codec changes are high-risk (wire protocol correctness). Full test suite run required after each change.
**Priority:** MEDIUM — reduces maintenance burden for new message types.
**Agent:** MITHRIL (derivation design) + FORGE (consensus message layout) + EYE (full codec test suite after each change).

---

### 8j — Test Quality: Thread.sleep and Timing Sensitivity

**EYE baseline 2026-06-22:** 2 live `Thread.sleep` call sites (SubscriptionManagerSpec:249, EthMiningServiceSpec:302). 11 comment-only references. No new FLAKY sites.

**Status:** Both live call sites are NECESSARY. Deferred to §8a-retro (Wave 3 test migration will naturally address the surrounding test structure).
**Priority:** LOW-MEDIUM — prevents CI flakiness as test suite grows.
**Agent:** EYE.

---

## Recommended Sprint Sequence

### Primary Track (blocking — sequential)

| Sprint | Work | Gate |
|--------|------|------|
| **Network/sync Pekko** | Part 2: S3→S4/S7→NET2→SNAP1→SNAP2→ROOT→CAPSTONE (see SPRINT-QUEUE.md) | scala3-cleanup-june merged |
| **→ CAPSTONE** | Root flip: `ActorSystem[Nothing]`, bridge/adapter removal, `Behavior[Any]` narrowing | All actors Typed |
| ~~**7c — Supervision**~~ | ~~§7c-P0 → §7c-D → §7c-A → §7c-B → §7c-C/E~~ | ✅ DONE 2026-06-27 |
| **8b — Opaque types (H3–H8)** | Difficulty → TotalDifficulty → GasAmount → GasPrice → BlockNumber → ChainId | Gate met (H2 done) ✅ NEXT |
| **8i — RLP derivation** | Replace handwritten product-type RLP codecs | Part 3a + R7 done |
| **3a — implicit→given** | 198 files, 522 declarations (scalafix GivenUsing) | After Pekko migration |
| **4e Jackson 3** | json4s 4.2.0-M5 gate | json4s M5 release |
| **4a JLine 4** | TUI refactor | Dedicated sprint |
| **5a Scala 3.9** | Version bump | 3.9 LTS release |
| **5c Constitution** | v1.2.0 | After 5a |

### Housekeeping Track (parallel-safe — fill test-wait downtime)

| Task | Work | Effort |
|------|------|--------|
| **8e SNAP1** | Clear 36 SSC sites (gated on NET2 Wave 3) | Wave 3 sprint |
| ~~**8g braceless**~~ | ~~`removeOptionalBraces` per-subsystem passes~~ | ✅ DONE `84aa43575` 2026-06-28 |
| **8a retro batch E6** | PeerActorSpec + RLPxConnectionHandlerSpec (wait Wave 3) | Wave 3 |
| ~~**7f FCM setListener**~~ | ~~ForkChoiceManager typed callback — remove last non-TCP `.toClassic`~~ | ✅ DONE `456f12499` 2026-06-27 |
| **8j Thread.sleep** | 2 live sites (both NECESSARY — revisit in Wave 3 test migration) | Wave 3 |

### Research Threads

| Thread | Goal | Gate |
|--------|------|------|
| **R4** | Scala 3.9 readiness (periodic — when 3.9 LTS appears) | 3.9 LTS release |
| **R7** | RLP codec derivation safety analysis (safe-to-derive vs must-stay-manual) | Part 3a done |

---

## Clearout Prompts

### Active

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| E6 | Batch E | §8a-retro batch E6 — PeerActorSpec + RLPxConnectionHandlerSpec | Gate: Wave 3 network/P2P sprint |
| ~~7f~~ | ~~—~~ | ~~§7f — ForkChoiceManager.setListener typed callback (MITHRIL)~~ | ✅ DONE `456f12499` 2026-06-27 |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

