# Fukuii Modernization — Deferred Backlog

**Last updated**: 2026-06-18 (Part 8 added — Classic TestKit, opaque types, memory audit, IO threading, ScalaFix expansion, dead code, braceless syntax, property-based testing, RLP modernization, test quality; sprint sequence updated with parallel housekeeping tracks)
**Purpose**: Single reference for all deferred cleanup work — completed items,
active deferred items, and follow-up sprint plans.

Active sprint plan: `/home/dev/.claude/plans/we-are-working-on-noble-whisper.md`

---

## Part 1: Deferred — Remaining Compiler Warnings

**Status: W2-P1 sweep COMPLETE (7 commits, 2026-06-21). ~507 non-E165 → 87 non-E165 remaining.**

### W2-P1 Commits

| Commit | What |
|--------|------|
| `94879ba59` | `NodeBuilder.scala`: `with`→`&` in self-type continuations |
| `82cd757ea` | `MiningBuilder` + `FaucetBuilder`: multi-line self-types |
| `8bfa0552a` | 8 files: `private[this]`/`protected[this]` → `private`/`protected` |
| `a211638f1` | Test deprecations: `expectNoMsg`, `left.get`, `json4s extract` |
| `a211638f1` | 5 files: inline `with` as type operator |
| `00e4ce34b` | 14 files: `= _`, infix operators, wildcards, `Ordering.Iterable`, E029 |
| `823732397` | `BootstrapDownload`: `new URL(String)` → `URI.create().toURL()` |

**Fixed in earlier phases (not part of W2-P1 sweep):**
- #1 `BlockHeaderValidatorSkeleton.scala:218` unused implicit `_blockchainConfig` — cleared
- #2 `PeersClient.scala:326` unused param `_peer` — cleared
- #3 `extvm/VMClient.scala:22` unused constructor param — cleared (C4 deleted extvm entirely)
- #4/#5 `PathNodeStorage.scala` unused `hash` params — cleared
- #6 `ETHPackets.scala:106` E092 `@unchecked` — cleared
- E003 `with` in self-types (337 occurrences) — cleared in W2-P3a + W2-P1
- E198 unused test symbols — addressed

### Remaining 87 Non-E165 Warnings (all externally gated)

| Count | Cause | Gate |
|-------|-------|------|
| 68 | `json4s extract[T]` Manifest synthesis (16 files) | json4s 4.x upgrade (DEFERRED-BACKLOG §4e) |
| 9 | `OpCode.scala` — infix ops + wildcard in `vm/` | FORGE gate |
| 2 | RocksDB `ClockCache` deprecated | Library upgrade |
| 2 | diffx `DiffMatcher` | Library upgrade |
| 1 | Guava `CacheBuilder` | Library upgrade |
| 1 | `EngineApiService.scala:581` `Ordering.Iterable` | BEACON gate |
| 1 | web3j `Admin` deprecated | Library upgrade |
| 1 | `TrieNodeHealingCoordinator` inside Pekko library boundary | Unfixable |
| 1 | `PeerRequestHandler` `ClassTag` unsound type-test | Needs `TypeTest[A,B]` (non-trivial) |

**E165 floor: 333** (intentional Pekko Classic bridges — not touched, expected permanent).

---

## Part 2: Pekko Classic → Typed Migration (follow-up sprint)

**Status**: Deferred from scala3-cleanup-june — needs subsystem-scoped plan, not actor-by-actor.
**Spec**: `pekko-typed-migration-p2.md`
**Agent**: LOOM (`.claude/agents/loom.md`)
**Prerequisite**: scala3-cleanup-june merged to staging first (wildcard migration complete).

### Why Subsystem Scoping

Actor-by-actor migration creates half-migrated packages: callers hold Classic `ActorRef` while
callees are Typed, requiring Classic→Typed adapters at every boundary. Migrating a whole subsystem
at once keeps callers and callees coherent in a single commit.

### Subsystem Order (lowest risk → highest)

| # | Subsystem | Actors | Files | Risk | Status |
|---|-----------|--------|-------|------|--------|
| 1 | `faucet/` | FaucetHandler + FaucetSupervisor | 7 | LOW | ✅ DONE — `551bccfaf` (post-rebase). Sealed Command ADT, two state behaviors, Classic→Typed adapter, Typed TestProbes. FaucetHandlerSelector deleted. |
| 2 | `jsonrpc/` | FilterManager + SubscriptionManager | ~8 | LOW-MED | ✅ DONE — `2ac71a58e` + `1309bb968` (post-rebase). ctx.messageAdapter bridges Classic eventStream. eventStream msgs confirmed local-only. 22/22 tests. |
| 3 | `transactions/` | PendingTransactionsManager + SignedTransactionsFilterActor | 10+18 | HIGH | ✅ DONE — `0be6dd776`. WrappedPeerEvent adapter, MailboxSelector.bounded(50000), toClassic.eventStream bridge, field-type updates in RegularSync/SyncController/BlockchainHostActor. |
| 4 | `consensus/pow/miners/` + `ommers/` | MockedMiner + OmmersPool | 19 | MED | ✅ DONE — `0aa837d5e`. OmmersPool: sealed Command ADT, immutable state via recursive `running()`, replyTo. MockedMiner: 4-state context.become → per-state Behaviors, pipeToSelf, context.scheduleOnce. FORGE-approved. Ommer ordering invariants preserved. |

### Remaining Classic→Typed Bridges (require network/P2P sprint to remove)

The actors in P2a–P2d are **genuinely Typed** (no Classic code inside them). However, three
structural bridges remain because the root `ActorSystem` in `NodeBuilder` is still Classic:

| Bridge | Location | Why it exists | Removed when |
|--------|----------|---------------|--------------|
| `system.spawn(...)` via `adapter._` | `NodeBuilder.OmmersPoolBuilder`, `MockedMiner.spawn` | Classic root can't spawn Typed children natively | Root flipped to `ActorSystem[Nothing]` |
| `system.toTyped.scheduler` | `PoWBlockCreator`, `PoWMining` | Typed ask needs a Typed Scheduler | Same — root flip |
| `MockedMiner.Send(msg, replyTo)` envelope | `PoWMining.sendMiner/askMiner`, callers | External `MockedMinerProtocol` API kept stable to avoid breaking `Mining` and `QAService` | When `Mining` API is absorbed into `Command` ADT — MEDIUM priority; `PoWMining` is production mining code, not test-only |
| `ctx.messageAdapter` / `toClassic.eventStream` | PTM (`transactions/`, P2c) | `eventStream` is Classic; Typed actors subscribe via adapter bridge | Root flip + eventStream modernization |

**These are intentional scaffolding, not shortcuts.** The actors are real Typed. The bridges
stack at the spawn boundary and will be eliminated together when the network/P2P sprint
migrates the remaining Classic actors and allows the root system to be flipped.

**The migration is functionally complete, not architecturally complete.** Wave 2 is not
the end of Pekko modernization — it is the first half. The second half is the network/P2P
sprint below.

### NET Group — Deferred Items

| Item | Location | Issue | Fix | Gate |
|------|----------|-------|-----|------|
| `@annotation.unused timers` | `PeerManagerActor.Impl:272` | Impl receives `TimerScheduler[Command]` from `Behaviors.withTimers` but uses `classicSystem.scheduler` exclusively. Suppressed with `@annotation.unused` during C3b. | ✅ DONE `4b101b612` — `Behaviors.withTimers` wrapper dropped entirely; `@annotation.unused timers: TimerScheduler[Command]` removed from Impl constructor; import dropped. `classicSystem.scheduler` private def stays — still used for `scheduleWithFixedDelay` (node-update, status-refresh) and `scheduleOnce` (connect retries). 61/61 PeerManager tests green. Full typed-timer migration deferred to network/P2P sprint. | — |
| NET-01 | `NetworkPeerManagerActor.scala:165,451,663` | `classicSystem.scheduler` for two fire-and-forget blacklist-delay `scheduleOnce` calls. HERALD verified by-design (2026-06-21). | ✅ DONE `6b506a63f` (partial) — `@annotation.unused timers: TimerScheduler[Any]` removed from NPMA Impl constructor; `timers,` removed from `new Impl(ctx, timers, ...)` call; TimerScheduler import dropped. `private def scheduler = ctx.system.classicSystem.scheduler` stays — correct for the two fire-and-forget `AddToBlacklistCmd` delays. Full treatment (path b: typed timers) deferred to network/P2P sprint. | — |

### Network/P2P Sprint — Pekko Migration Completion Gate

**This sprint completes the modernization.** It is not optional cleanup.

`network/` and `sync/` actors (~20 files, ~25k LOC) are the core P2P/sync engine. They
must be migrated in their own sprint (R1 research → implementation) because:
- They are deeply coupled to the wire protocol (HERALD review required per subsystem)
- SNAPSyncController alone is 5,052 LOC
- Migration atomicity requires grouping by caller coherence, not individual actors

**After all network/sync actors are migrated:**
1. Flip `NodeBuilder.system` → `ActorSystem[Nothing]` — removes `adapter._` bridges
2. Remove `system.toTyped.scheduler` shims in `PoWBlockCreator`, `PoWMining`
3. Absorb `MockedMinerProtocol` into `MockedMiner.Command` ADT; clean `PoWMining` API
4. Replace `toClassic.eventStream` bridges with native Typed `EventStream`

**Sequence:** Run R1 from `WAVE3-RESEARCH-PLAN.md` to produce the migration plan, then
implement per the R1 output. See SPRINT-QUEUE.md Part 6 for the kickoff spec.

### Pre-flight Before Each Subsystem

```bash
# Verify no remaining ._ imports at migration start
grep -rn "import .*\._" src/main/scala/ --include="*.scala" | wc -l  # must be 0

# eventStream audit (run before jsonrpc and transactions)
grep -rn "eventStream\.publish\|eventStream\.tell\|eventStream\.subscribe" \
  src/main/scala/ --include="*.scala"
```

### Thread Format

Each subsystem = one implementation thread:
> "Use the LOOM agent to migrate the `[subsystem]` subsystem to Pekko Typed on
> scala3-cleanup-june. Files: [list]. Read pekko-typed-migration-p2.md and the LOOM agent
> brief. For transactions/ and OmmersPool: run FORGE first. After migration: `sbt compile-all`.
> One commit per subsystem."

### Rejection Criteria

- `grep -rn "import .*\._" src/main/ | wc -l` > 0 at migration start
- PTM eventStream types cross network boundary → full `@SerializabilityTrait` pre-flight required
- Any file under `consensus/`, `vm/`, `crypto/`, `domain/` touched → invoke FORGE before proceeding
- `sbt testEssential` drops below 3,601 tests

---

## Part 3: Scala 3 Modernization (separate sprint — do not mix with Pekko)

### 3a — implicit → given/using

**Spec**: `scala-implicit-to-given.md`
**Agent**: MITHRIL (after `sbt scalafix GivenUsing`)
**Blast radius**: 198 files, 522 `implicit val/def` declarations

Hotspot files (highest density — start here):
1. `jsonrpc/McpJsonMethodsImplicits.scala` — 32 implicits
2. `utils/Picklers.scala` — 21
3. `jsonrpc/JsonMethodsImplicits.scala` — 21
4. `jsonrpc/EthBlocksJsonMethodsImplicits.scala` — 19

**Prerequisite**: Add `GivenUsing` to `.scalafix.conf` BEFORE running. Must run AFTER
Pekko migration sprint (actor files will also be touched by GivenUsing).

### 3b — implicit class → extension methods — COMPLETE (2026-06-20)

**Commit**: `c0a3612b4` on `scala3-cleanup-june`

**Scope completed**: Non-consensus `src/main/` (excludes `consensus/`, `vm/`, `crypto/`, `domain/`).
29 files changed, all `AnyVal` implicit classes and Dec/RLP-codec implicit classes converted.

**Kept as `implicit class` (with reasons):**

| Class | File | Reason |
|-------|------|--------|
| All `*Enc extends MessageSerializableImplicit` | ETHPackets, SNAP, ETH69, WireProtocol | Subtype polymorphism — `new FooEnc(msg): MessageSerializable` in MessageDecoders |
| `SignedTransactionEnc extends RLPSerializable` | ETHPackets | `toBytes` used via trait inheritance in `domain/BlockBody` (excluded path) |
| `MptNodeEnc extends RLPSerializable` | MptNodeCodecs | `toBytes` used via trait inheritance in SNAP sync codec layer |
| `TxLogEntryRLPEnc` | ETHPackets | Name collision: `ReceiptCodecs` also has `extension (TxLogEntry) { def toRLPEncodable }` — ambiguous under wildcard import |
| `ReceiptBloomEnc` | ETHPackets | Same name-collision reason; also scoped-import disambiguation in `BlockchainHostActor` |
| `ReceiptBloomFreeEnc` | ETHPackets | Same as above |

**Remaining scope** (consensus/vm/crypto/domain/ — requires forge/beacon review gate):
- `OmmersSeq`, `Receipt`, `ForkId` codec classes in `consensus/`
- Additional domain codecs in `domain/`
- These are deferred to a consensus-reviewed sprint.

### 3c — isInstanceOf / asInstanceOf audit

**Count**: 83 instances across network decoders and JSON-RPC marshalling
**Risk**: MODERATE — bypasses type safety
**Approach**: `grep -rn "isInstanceOf\|asInstanceOf" src/main/ --include="*.scala"`
Audit hot paths; replace with pattern matching / algebraic data types.

### 3d — sealed trait → enum (recommended polish — elevated)

**Count**: ~50 pure `case object` hierarchies out of 146 sealed traits
**Risk**: LOW
**Constraint**: NEVER migrate hierarchies with `case class` subtypes.
**Priority**: LOW-MEDIUM — elevated from "optional" because high-value candidates exist:
- `SyncPhase` ✅ DONE `adf4e69ea` — 8-member single-line enum; `SyncPhase.*` imported at 5 call sites
- `ForkId` message codes ✅ DONE `adf4e69ea` — `ForkIdValidationResult` 3-member enum; 4 external callers updated
- `Blacklist.BlacklistReason` ❌ REJECTED — has 7 `final case class` subtypes (`EmptyBlockBodies`, `EmptyReceipts`, `InvalidReceipts`, `FastSyncRequestFailed`, `InvalidStateResponse`, `RegularSyncRequestFailed`, `BlockImportError`). Not a pure discriminant; cannot be an enum.
- `Blacklist.BlacklistReasonType` ❌ REJECTED — non-trivial behavior fields (`code: Int`, `name: String`) and mixin group traits (`FastSyncBlacklistGroup` etc.). Not a pure discriminant enum.
- `SyncProtocol.SyncStatus` equivalents — still candidate; verify subtypes are pure `case object` before migrating

Enum promotes exhaustiveness checking and derives `ordinal`, `values`, `fromOrdinal` for free.
**Parallel-safe**: Can be done file-by-file, no actor migration gate. Good housekeeping task.

### 3g — StateValidator.scala Exception Swallowing (FORGE — RESOLVED)

**Source:** R0 audit Cat 5 (exception swallowing)
**File:** `src/main/scala/.../blockchain/sync/snap/StateValidator.scala` (note: actual path is
`snap/`, not `state/` as originally recorded)
**Status:** RESOLVED 2026-06-20 — disposition **log + swallow** (non-behavioral observability),
plus one safe conservative-flag improvement. Tests: `*StateValidator* *SNAP* *Trie*` 245/0.

**FORGE assessment (answers to the three questions):**

**Q1 — Intentional fault tolerance?** Partly. The validator's contract with its caller
(`SNAPSyncController`) is: `Right(missing)` = "walk completed, here are nodes to heal";
`Left(error)` = "walk could not complete." When `Right(Seq.empty)` is returned the caller
declares state **"COMPLETE — all tries intact"** (`SNAPSyncController.scala:1508`) and finishes
sync. The HashNode resolve handlers (`traverseForMissingNodes`) deliberately treat an unreadable
node as missing → conservatively correct. But the `collectAccounts` / leaf-decode / storage-walk
silent `case _: Exception => ()` sites could omit a subtree from the missing set and produce a
**false "intact" signal on corrupt state** — that part was an over-broad accidental catch-all,
not deliberate policy.

**Q2 — Correct behavior?** **Log + swallow** (not propagate). The walks run fire-and-forget
inside a `Future`; the `Left` path triggers a full validation-retry / restart / dormant cycle
(`SNAPSyncController.scala:1462-1500`). Propagating a transient decode/I-O fault there would be
*more* destructive than the current behavior (needless pivot restart). Logging at WARN preserves
control flow while making masked faults visible. Two sites additionally now **conservatively add
the affected root to the missing set** (storage-walk line ~73; idempotent — healing re-fetch of an
already-present root is a no-op) to close the false-"intact" hole without changing the Left/Right
contract.

**Q3 — MissingNodeException vs other exceptions distinction?** The distinction was real but the
non-missing branch lacked observability. A `StorageException` (corrupt DB) or decode failure could
arrive and be indistinguishable from an ordinary missing node, or silently dropped. Now every
catch site logs (WARN for non-missing/unexpected, DEBUG for ordinary missing) so the two are
separable in operations. `StackOverflowError` is not catchable by `case _: Exception` and is not a
concern here — the heal walks (`walkAccountTrieDFS`/`walkStorageTrieDFS`) are already iterative
(explicit stack) specifically to avoid it.

**Sites changed (all in `snap/StateValidator.scala`):**
- `traverseForMissingNodes` HashNode (was 127): log WARN, still mark-as-missing (intentional).
- `validateAllStorageTries` account-traversal (was 58): log WARN before existing `Left`.
- `validateAllStorageTries` storage-walk (was 73): log WARN + **flag storageRoot for healing**
  (was silent drop).
- `collectAccounts` leaf decode (was 148): log WARN.
- `collectAccounts` branch terminator decode (was 170): log WARN.
- `collectAccounts` HashNode resolve (was 182-183): split — DEBUG (missing) / WARN (unexpected).
- `walkAccountTrieDFS` leaf decode (was 305): log WARN.

No consensus/byte-level behavior change: validator output set is unchanged except the storage-walk
site, which can only *add* an already-idempotent heal target. Compile: `sbt compile-all` clean.

---

### 3e — Console output → logging

**Count**: 28 `println`/`System.out`/`System.err` calls
**Priority**: Opportunistic — fix when already touching a file.
**Replace with**: `ctx.log.info(...)` (Typed actors) or SLF4J logger.

### 3f — Manual synchronization outside actors

**Count**: 20 `.synchronized`/`.wait()`/`.notify()` outside actor boundaries
**Priority**: Address where overlapping with Pekko migration; audit remainder separately.

**Audit COMPLETE** (`cf33cfa87`) — 5 sites in `src/main/`, all accounted for:

| # | File | Line | Bucket | Disposition |
|---|------|------|--------|-------------|
| 1 | `db/cache/MapCache.scala` | 19 | D | ✅ Fixed — `mutable.HashMap` → `TrieMap`; `this.synchronized` on update removed |
| 2 | `db/cache/MapCache.scala` | 30 | D | ✅ Fixed — same backing change; `this.synchronized` on get removed |
| 3 | `blockchain/sync/CombinedRecoveryScanner.scala` | 106 | D | Left as-is — `lock.synchronized` serializes compound multi-structure transaction (dedup sets + mutable accumulators + fsync) across parallel `Future` workers; `ConcurrentHashMap` cannot substitute. Comment at line 104 documents this. |
| 4 | `consensus/pow/PoWMining.scala` | 106 | A | No-touch — **FORGE gate required**. Compound check-then-act on two `@volatile` fields; could become `AtomicBoolean` but FORGE must sign off. Logged in CHASE-QUEUE. |
| 5 | `blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala` | 1617 | D | Left as-is — `visitedLru.synchronized` on `LinkedHashMap`-backed bounded FIFO-eviction set; `ConcurrentHashMap` was the prior implementation and produced a silent correctness hole (comment at lines 1607–1614 documents why). |

No Bucket C violations (no actor-internal state accessed outside actor thread).

---

## Part 4: Dependency Upgrades (blocked or deferred)

### 4a — JLine 3.x → 4.x

**Current pin**: `3.30.13`
**Target**: `4.1.x`
**Why deferred**: JLine 4.x is not drop-in compatible. Two files need refactoring:
- `console/TuiRenderer.scala` — `AttributedString`, `AttributedStyle`
- `console/Tui.scala` — `Terminal`, `TerminalBuilder`

Terminal dimension APIs standardised in 4.x (`Sized` interface, `Size.of()` factory).
Panama FFM replaced signal handling.

**Prerequisite**: Dedicated jline-upgrade sprint; assess TUI rendering test impact.

### 4b — Logstash-Logback-Encoder 8.x → 9.x

**Current pin**: `8.1`
**Target**: `9.0` (October 2025, latest)
**Why deferred**: 9.0 requires Jackson 3 exclusively (jackson-bom:3.0.1). Also bumps minimum Java to 17. 8.1 is the last Jackson 2 release.

**Prerequisite**: 4e (Jackson 2→3) first.

### 4c — Kanela-Agent 1.x → 2.x

**Why deferred**: Complete rewrite (AspectJ → ByteBuddy). Requires Kamon 2.8.1+ and
custom instrumentation recompile. Runtime compatibility cannot be verified by compile alone.

**Prerequisite**: Kamon 2.8.1+ confirmed stable; dedicated sprint with changelog review.

### 4e — Jackson 2.x → 3.x (transitive)

**Status**: BLOCKED — gate nearly open as of 2026-06-20 (R3 re-check, Wave 3)

**Sole blocker: json4s.** Library verdicts as of 2026-06-20:

| Library | Gate | Version checked | Finding |
|---------|------|----------------|---------|
| json4s | **GATE NEARLY OPEN** | `4.2.0-M5-SNAPSHOT` (repo-ref HEAD, 2026-06-20) | Jackson 3 already in snapshot: `"tools.jackson.core" % "jackson-databind" % "3.2.0"`; source code imports `tools.jackson.databind.*` (Jackson 3 namespace). Jackson 2 was present at M4 (Jun 11 2026). Transition from Jackson 2 → 3 happened between M4 and M5-SNAPSHOT. Latest stable is still `4.1.1` (Jackson 2). Gate opens on M5 release. |
| circe | **NON-BLOCKER** (confirmed) | `0.14.15` (repo-ref) | circe-core has no Jackson dependency; no jackson module in circe repo. `circe-jackson` is a separate project not on fukuii's classpath. |
| sangria / sangria-circe | **NON-BLOCKER** (confirmed) | `4.2.18` / `1.3.2` (repo-ref) | Zero Jackson references in sangria build or sangria-circe. Depends only on circe-core. |

**Gate condition**: `json4s 4.2.0-M5` (or later) published as a milestone or stable artifact. Verify at the release tag: `project/Dependencies.scala` must reference `tools.jackson.core` (Jackson 3), not `com.fasterxml.jackson.core` (Jackson 2).

**Watch**: https://github.com/json4s/json4s/tags — last tagged release `4.2.0-M4` (Jun 11 2026); repo HEAD is at `M5-SNAPSHOT`. Re-check weekly until M5 tag appears. When it does: update fukuii pin from `4.0.7` → `4.2.0-M5` (or first stable).

**Alternative unblock path (permanent fix)**: Migrate `jsonrpc/` from `json4s-native` to circe. circe is already on the classpath via `sangria-circe`. Scope: ~79 files, `JValue → io.circe.Json`. Non-trivial but eliminates the Jackson gate permanently and removes the `json4s` dependency entirely.

---

## Part 5: Blocked (gate conditions)

### 5a — Scala 3.9 Upgrade

**Spec**: `scala-39-upgrade.md`
**Gate**: Scala 3.9.x LTS appears on endoflife.date with LTS designation
**Action when unblocked**: Bump `scalaVersion` in `build.sbt`; update scapegoat to
matching version; run `sbt compile-all`; fix new warnings/errors.

### 5b — Virtual Threads / Ox Evaluation

**Spec**: `virtual-threads-evaluation.md`
**Status**: Research only — low priority
**Reference**: `.claude/virtuslab/scala-skill/direct-style-scala/SKILL.md`
Post-Typed migration: evaluate replacing Actor mailboxes with Ox `supervised` scopes.

### 5c — Constitution v1.2.0

**Spec**: `constitution-amendment-v1.2.0.md`
**Status**: Blocked on Scala 3.9 upgrade (5a).

---

## Part 6: Tech Debt Deletion

### 6a — extvm/ Dead Code Deletion ✅ DONE (`a948fda1d`)

18 files deleted, 1,423 deletions. All 3 pre-checks passed.

- `src/main/scala/.../extvm/` — 11 Scala source files
- `src/test/scala/.../extvm/` — MessageHandlerSpec, VMClientSpec
- `src/main/protobuf/extvm/msg.proto` + `src/main/resources/extvm/VERSION`
- `project/scalapb.sbt` — entire file (sbt-protoc plugin was extvm-exclusive)
- `build.sbt` / `Dependencies.scala` — PB.targets block, `scalapb-runtime` dep, extvm coverage/scapegoat exclusions removed

`sbt clean compile-all` → 0 errors. Side effect: Part 1 Warning #3 (`extvm/VMClient.scala:22`) now resolved.

---

---

## Part 7: Post-CAPSTONE Typed API Maturity

These items gate on CAPSTONE complete (all actors Typed, ActorSystem[Nothing] root, adapter.* removed).
The mechanical migration preserves Classic-era design decisions. Once the scaffolding is gone, these
three phases evaluate and improve the code as a native Typed system.

### 7c — Typed Supervision Hierarchy

**What**: The mechanical migration preserved default stop-on-failure supervision from the Classic
system root. Pekko Typed supports explicit `Behaviors.supervise(...).onFailure[ExceptionType](strategy)`
per actor. A full supervision design would specify restart/stop/escalate per failure class per actor,
matching the system's fault-tolerance requirements.

**Scope**: Audit every actor's failure modes. Apply explicit supervision at spawn sites for actors
where restart-on-failure is safe (coordinator workers, peer workers) vs. stop-and-alert for actors
where restart could cause state corruption (SSC, NPMA).

**Gate**: CAPSTONE complete + 7a done (sealed ADTs make failure typing cleaner).
**Priority**: Low — the current behavior is safe (default stop is conservative); explicit supervision
is a correctness/resilience improvement, not a bug fix.
**Agent**: PRISM (review) + LOOM (implementation per subsystem).

---

### 7d — Post-CAPSTONE Classic Artifact Audit

**What**: A systematic sweep of the entire codebase for Classic-era patterns that survived the
mechanical migration. Even with every actor Typed, logic originally written for Classic may leave
behind structural artifacts.

**Sweep categories**:

```bash
# 1. Any remaining extends Actor / ActorLogging
grep -rn "extends Actor\b\|ActorLogging\|import org.apache.pekko.actor.Actor\b" \
  src/main/ --include="*.scala"

# 2. Any remaining sender() calls
grep -rn "sender()" src/main/ --include="*.scala"

# 3. adapter.* imports (should be zero after root flip)
grep -rn "typed.scaladsl.adapter\|toClassic\|toTyped" src/main/ --include="*.scala"

# 4. Remaining PropsAdapter usage
grep -rn "PropsAdapter" src/main/ --include="*.scala"

# 5. Behavior[Any] remaining (should be zero post-CAPSTONE)
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala"

# 6. Raw context.system.scheduler.scheduleOnce without stored Cancellable
grep -rn "scheduler\.scheduleOnce\|scheduler\.schedule\b" src/main/ --include="*.scala"

# 7. Classic ActorRef types on Typed actor fields
grep -rn "ActorRef\b" src/main/ --include="*.scala" | grep -v "typed\.ActorRef\|// "

# 8. Dead-letter / unhandled message review — identify Behaviors.unhandled call sites
grep -rn "Behaviors\.unhandled\|case other =>" src/main/ --include="*.scala"
```

**Output**: `post-capstone-artifact-audit.md` — catalog of every hit, categorized as:
- `resolved` (expected zero — confirm)
- `intentional` (document why it remains)
- `fix-now` (feeds directly into 7e queue)

**Gate**: CAPSTONE commit merged.
**Priority**: High — run this before declaring the Typed migration "complete". Artifacts missed here
become permanent technical debt.
**Agent**: PRISM (8-lens review of findings) + HERALD (any wire-protocol artifacts).

---

### 7e — Design Review Summary

Completed portions (P4, P4a) → `completed/DEFERRED-BACKLOG.md`. Open items below.

---

### 7e-P2 — HealingState Extraction (SSC)

**What**: Six SSC fields are semantically phase-local to the healing phase:
`trieWalkInProgress`, `healingServeRootRequestInFlight`, and four related vars.
Currently on `Impl`, they bleed across pivot refreshes if a pivot rotation occurs while healing.
Extract to `case class HealingState(...)` threaded as a behavior parameter into the healing
behavior function, making the phase boundary explicit and eliminating the cross-phase bleed risk.

**Scope**: `sync/snap/SNAPSyncController.scala` — `Impl` field extraction + healing behavior split.
**Gate**: 7a complete (✅ done). Recommend HERALD pre-flight for pivot-rotation interaction.
**Priority**: LOW — latent correctness risk on pivot rotation during heal; not currently triggered.
**Effort**: S–M — field identification is complete (7e audit); refactor is mechanical but touches
healing entry/exit paths that are test-covered (74/74 SNAPSync specs must remain green).
**Agent**: LOOM (implementation) + EYE (verify baseline).

---

### 7e-P3 — ChainDownloader Stagnation Push (SSC)

**What**: SSC currently polls `ChainDownloader` progress timestamps to detect download stagnation
(`CheckDownloadStagnation` timer). Invert the relationship: `ChainDownloader` pushes a
`DownloadStagnated` message to SSC when it detects stagnation internally, eliminating the
polling timer and making stagnation detection reactive.

**Scope**: `sync/snap/ChainDownloader.scala` + `sync/snap/SNAPSyncController.scala` — stagnation
detection path only.
**Gate**: 7a complete (✅ done).
**Priority**: LOW — polling timer works correctly; this is a design improvement (back-pressure,
reduced coupling).
**Effort**: S — stagnation logic is isolated; no consensus path touched.
**Agent**: LOOM (implementation) + HERALD (verify no wire-protocol interaction) + EYE (verify baseline).

---

---

## Part 8: Additional Modernization Gaps

These gaps were identified during the Pekko migration sprint and the post-CAPSTONE plan design
session. They are not captured in earlier parts but belong in the full modernization roadmap.
Each is scoped with a gate condition and a "parallel-safe" flag — parallel-safe items can run
during the 24-minute `testEssential` wait without blocking the primary migration thread.

---

### 8a — Classic TestKit → ActorTestKit Migration

**Scope**: 99 test files still use `org.apache.pekko.testkit.TestKit` (Classic) vs. 14 already
using `org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit` (Typed).

```bash
grep -rn "TestKit\|TestActorRef\|ActorSystem(" src/test/ --include="*.scala" -l | wc -l  # 99
grep -rn "ActorTestKit\|BehaviorTestKit\|TestInbox" src/test/ --include="*.scala" -l | wc -l  # 14
```

**Why it matters**: Classic TestKit creates a full Classic `ActorSystem` per test suite, incurring
startup overhead. Typed `ActorTestKit` is lighter, provides `BehaviorTestKit` for deterministic
unit testing (no real actor system needed for simple behaviors), and catches protocol violations
at compile time via ADT message types.

**Strategy**: Migrate per-actor in the same LOOM thread that migrates the production actor.
Do not defer all 99 files to a single later sprint — that creates a massive tangled changeset.
Each LOOM session: after migrating the actor, migrate its test file(s) from `TestKit` to
`ActorTestKit`/`BehaviorTestKit` in the same commit.

**For already-migrated actors** (faucet, jsonrpc, transactions, consensus/mining) whose tests
were not converted: address in a dedicated test-cleanup sprint (8a-retro).

**8a-retro batch 1 — consensus/mining ✅ DONE** (`0d65a85c4`). 27/27 tests green.

| File | Key change |
|------|-----------|
| `LegacyTransactionHistoryServiceSpec` | Drop `TestKit` + `WithActorSystemShutDown` → `ScalaTestWithActorTestKit`; `system.toClassic` for Classic TestProbe (service still takes Classic ActorRef) |
| `ForkChoiceManagerSpec` | Same swap; fixes latent bug — original had no `afterAll` shutdown, leaking the actor system after every test run |
| `PoWMiningSpec` | Pure swap — TestKit was vestigial (no probes, no messaging) |
| `WorkNotifierSpec` | Swap + `system.toClassic` for Pekko HTTP's `Http()` (requires Classic system); drop explicit `BeforeAndAfterAll` (comes free from `ScalaTestWithActorTestKit`) |
| `MockedMinerSpec` | Swap + `system.toClassic` for Classic probes in `MinerSpecSetup`; `classicSystem.spawnAnonymous` → `testKit.spawn` (Typed test kit's custom user guardian disallows top-level spawning via Classic adapter — same fix `PoWMiningCoordinatorSpec` uses) |

**Non-obvious finding:** `ForkChoiceManagerSpec` had no `afterAll` — actor system leaked after every test run. Silent pre-existing resource leak, not a test logic error. Fixed as a side-effect of the migration.

**8a-retro batch 2 — jsonrpc/ + graphql/ ✅ DONE** (`b5e11c0a4` + `722f316f2`). 275/275 tests green (143 + 132).

| Commit | Files | Tests |
|--------|-------|-------|
| `b5e11c0a4` | DebugServiceSpec, DebugTracingServiceSpec, EthBlocksServiceSpec, EthInfoServiceSpec, EthMiningServiceSpec, EthProofServiceSpec, EthTxServiceSpec, EthUserServiceSpec, FukuiiServiceSpec, GasPriceOracleSpec | 143/143 |
| `722f316f2` | graphql/GraphQLServiceSpec, JsonRpcController{EthLegacyTransaction,Eth,Personal,}Spec, McpServiceSpec, PersonalServiceSpec, QAServiceSpec, TraceServiceSpec, TxPoolServiceSpec; modified: JsonRpcControllerFixture (ActorTestKit implicit param; `system.spawnAnonymous` → `actorTestKit.spawn`) | 132/132 |

**Recurring patterns (apply to remaining batches):**

| Issue | Root cause | Fix |
|-------|-----------|-----|
| `PatienceConfig` ambiguity | `NormalPatience`/`LongPatience` abstract override conflicts with `ScalaTestWithActorTestKit.patience` | Drop patience trait from mixin; test kit default (10s) sufficient |
| `cannot create top-level actor from the outside` | Classic adapter `system.spawnAnonymous(...)` blocked by Typed test kit's custom user guardian | Thread `ActorTestKit` as implicit param into fixture; use `actorTestKit.spawn(...)` |
| `override` error on `def timeout` | `ActorTestKitBase` already declares `def timeout: Timeout` | Add `override` modifier |
| `system.toTyped.scheduler` invalid | After migration, `system` is already `ActorSystem[Nothing]` | Change to `system.scheduler` |
| No `afterAll` → resource leak | `WithActorSystemShutDown` was providing cleanup | `ScalaTestWithActorTestKit` handles shutdown automatically |
| `QAServiceSpec` — no Classic usage | Only `WithActorSystemShutDown` held the system | Clean removal; no `classicActorSystem` needed |

**Gate**: Per-actor gate = that actor's LOOM migration is complete.
**Parallel-safe**: No — test file migration must follow actor migration. Not a housekeeping task.
**Priority**: HIGH — should be embedded in each LOOM thread, not deferred.
**Agent**: LOOM (test migration paired with production migration per actor).

---

### 8b — Opaque Types for Domain Value Concepts

**Problem**: Core domain concepts are represented as raw primitive types throughout the codebase.
`BigInt` is used for both block numbers and balances; `ByteString` is used for hashes, state roots,
addresses, and arbitrary byte payloads. There are zero `opaque type` declarations.

```bash
# Zero opaque types:
grep -rn "opaque type" src/main/ --include="*.scala"  # → 0 results

# Raw BigInt used for block concept in protocol messages:
grep -rn "blockNumber: BigInt\|stateRoot: ByteString\|peerId: " \
  src/main/ --include="*.scala" | wc -l  # ~161 occurrences
```

**High-value opaque type candidates**:
| Concept | Current type | Opaque type |
|---------|-------------|-------------|
| Block number | `BigInt` | `opaque type BlockNumber = BigInt` |
| Block hash / state root | `ByteString` | `opaque type Hash = ByteString` |
| Account address | `ByteString` | `opaque type Address = ByteString` |
| Peer ID | `PeerId` (already a type alias?) | Seal as opaque in domain |
| Storage key | `BigInt` | `opaque type StorageKey = BigInt` |
| Balance / nonce | `BigInt` | `opaque type Balance = BigInt`, `opaque type Nonce = BigInt` |

**Why it matters**: Without opaque types, passing a block hash where a state root is expected
compiles silently. Opaque types enforce semantic distinctness at compile time with zero runtime cost.

**Approach**: Research thread first (R6) — map all `BigInt`/`ByteString` usages by semantic role.
Introduce opaque types in `domain/` starting with the highest-confusion pairs (`BlockNumber`/`Balance`
and `Hash`/`Address`). Each opaque type is a one-file change; callers need trivial `.value` unwraps
at boundaries.

**Gate**: None — but do after Part 3a (given/using) since implicit conversions interact.
**Parallel-safe**: YES — each opaque type introduction is file-scoped with clear blast radius. Good
housekeeping task during test waits for specific domain files.
**Priority**: MEDIUM — correctness improvement. Prevents entire class of type confusion bugs.
**Agent**: MITHRIL (Scala 3 type design) + FORGE (for domain/ and consensus/ intersections).

---

### 8e — ScalaFix Ruleset Expansion + `noReturns` Ratchet Lock

**Work done (2026-06-18):**
- `DisableSyntax.noReturns = true` — **already in `.scalafix.conf`** (pre-done).
- `NoAutoTupling` — **already in `.scalafix.conf`** (pre-done).
- C2 chore removes `return` from ~52 non-actor non-consensus sites — **✅ DONE `9eb1f4e06`** (guard clauses, while-loop early exits, try-block returns, match-arm returns, complex multi-return methods; 19 files; 0 compile errors).
- TNHC 4 (actual 11) returns — **✅ DONE `7a48c5988`** (LOOM Phase 0, S3 TNHC thread).

**Remaining to lock the ratchet** (`sbt scalafixAll` not yet green): 40 deferred sites.

| Deferred category | File(s) | Count | Gate |
|-------------------|---------|-------|------|
| Classic actor — Wave 3 LOOM sprint | `sync/snap/SNAPSyncController.scala` | 33 | Wave 3 network/sync migration (SNAP1) |
| Consensus-critical — FORGE review | `vm/VM.scala`, `vm/OpCode.scala`, `vm/PrecompiledContracts.scala`, `ledger/BlockPreparator.scala`, `mpt/StackTrie.scala`, `consensus/validators/std/StdSignedTransactionValidator.scala` | 6 | FORGE sign-off per file |
| Consensus-path (ETH Engine API) — BEACON review | `consensus/engine/EngineApiController.scala:96` (`handleNewPayload`, malformed-payload decode `Left` branch), `consensus/engine/EngineApiController.scala:226` (`handleForkchoiceUpdated`, malformed-params decode `Left` branch) | 2 | BEACON sign-off (S3-D) |

**Full ratchet lock checklist:**
1. C2 chore clears ~52 sites ✅ DONE `9eb1f4e06`
2. LOOM Phase 0 for TNHC clears 11 sites ✅ DONE `7a48c5988`
3. FORGE reviews and clears 6 consensus sites (1 cleared: consensus/engine/JwtAuthenticator.scala — S3-C) ← add to relevant FORGE sessions
4. BEACON reviews and clears 2 ETH Engine API sites — `EngineApiController.scala:96` + `:226` (S3-D). Both are early-`return IO.pure(...)` decode-error guards inside large consensus-path method bodies; removing the `return` requires wrapping ~90 lines of post-decode body into the `Right`/`else` branch. Deferred from S3-A/S3-D/S3-F commit (2026-06-22): the byte-for-byte response behavior must be preserved across the re-indent; gated on a focused BEACON pass, not bundled with the low-risk Option/val changes.
5. Wave 3 SNAP1 migration sprint clears SNAPSyncController 33 sites ← gated on NET2
6. After all above: run `sbt scalafixAll` to confirm 0 violations → ratchet locked

**Other rules to evaluate enabling (unchanged from original plan):**
```
LeakingImplicitClassVal  # implicit class vals that escape scope
OrganizeImports          # import grouping/deduplication
ExplicitResultTypes      # explicit return types on public defs (enable gradually)
```
These are lower priority and unblocked — add one at a time, fix violations, commit together.

**Gate**: Full ratchet lock gated on LOOM S3 (TNHC) + FORGE (consensus files) + BEACON (ETH Engine API S3-D) + Wave 3 (SNAP).
Partial progress (C2) is safe to run any time.
**Agent**: MITHRIL (rule evaluation); FORGE (consensus files); BEACON (Engine API S3-D); LOOM (TNHC + SNAPSyncController).

---

### 8f — Dead Code Audit (Broader than extvm) ✅ RESEARCH DONE (2026-06-22)

**PRISM sweep complete.** 4 high-confidence candidates identified (see CHASE-QUEUE.md DEAD entries 2026-06-22). No `FIXME`/`HACK`/`TODO` markers found. Deletion sprint pending.

**Part 6a** covers `extvm/` (10 files). Additional dead code likely exists beyond it.

**Sweep**:
```bash
# Private methods never referenced outside their file
# (approximate — look for `private def` that doesn't appear in the rest of the file)
grep -rn "private def \w\+" src/main/ --include="*.scala" | \
  awk -F: '{print $1, $3}' | sort | head -40

# @Ignored tests (56 occurrences — how many are permanently dead?)
grep -rn "@Ignore\b\|ignore\b\|pending\b" src/test/ --include="*.scala" -l

# Imports never used (compile warns, but sweep for any suppressed)
grep -rn "@nowarn.*unused\|@SuppressWarnings.*unused" src/main/ --include="*.scala"

# TODO/FIXME markers (how many reference removed functionality?)
grep -rn "TODO\|FIXME\|HACK\|XXX\b" src/ --include="*.scala" | wc -l
```

**Known candidates beyond extvm**:
- `FastSyncBranchResolverActor` ✅ WIRED `ea60c4f29` — `FastSync.scala` `handleBlockHeaders` `ParentChainWeightNotFound` case now spawns the actor (binary search for true common ancestor) and transitions to `waitingForBranchResolution()`; `BranchResolvedSuccessful` resets cursors/queues; `BranchResolutionFailed` falls back to N-block rewind. 15/15 tests pass. testEssential 3,600/0 ✅.
- Test helpers with `@Ignore` annotations (56 occurrences in tests) — audit which are permanently dead

**Output**: `dead-code-audit.md` — file list, confidence level (definitely dead / possibly dead / uncertain).
Each "definitely dead" file gets a deletion PR.

**Gate**: None — standalone sweep any sprint.
**Parallel-safe**: YES — research only. File-by-file deletion commits are lightweight.
**Priority**: LOW — cleanup only, no functional impact.
**Agent**: PRISM (8-lens review of findings) + FORGE (for any files in consensus/).

---

### 8g — Braceless Scala 3 Syntax

**What**: Scala 3 supports optional braces (indentation-based syntax). The codebase is almost
entirely brace-based (Scala 2 style). Migrating to braceless is purely cosmetic — no behavior
change — but aligns with idiomatic Scala 3 style for new code.

**Scope assessment**:
```bash
# Files still using def foo(...) {  procedure-syntax (should be zero after scalafmt)
grep -rn "def \w\+.*) {$" src/main/ --include="*.scala" | wc -l

# Files using braceless already (if any were written braceless during migration)
grep -rn "^\s\+then\b\|^\s\+do\b" src/main/ --include="*.scala" | wc -l
```

**Recommendation**: LOW PRIORITY. Do NOT do a mass braceless migration — it creates massive diffs
that obscure semantic changes in code review. Instead: adopt braceless for all NEW code written
during subsequent sprints, and opportunistically convert small files touched for other reasons.

Add scalafmt config to prefer braceless in new files:
```
rewrite.scala3.convertToNewSyntax = true
rewrite.scala3.removeOptionalBraces = true
```

**Partial work committed (2026-06-18, style(scala3): enable braceless syntax preference in scalafmt config):**
- `convertToNewSyntax = true` — APPLIED. 392 source files, keyword-syntax only (`if (x) {` → `if x then {`, `while (x) {` → `while x do {`). 3,125 ins / 3,482 del, zero logic impact.
- `removeOptionalBraces = true` — **DEFERRED**. Triggered 945-file brace removal — too aggressive for a single chore commit; would bury semantic changes in code review. Needs its own dedicated pass, scoped per-subsystem or gated post-CAPSTONE.

**Remaining work**: Enable `removeOptionalBraces = true` in `.scalafmt.conf` and apply subsystem by subsystem (e.g. `jsonrpc/` first, then `network/`, etc.) rather than a single 945-file sweep.

**Gate**: After CAPSTONE (new-code policy; don't distract migration diffs with style churn).
**Parallel-safe**: YES per-file, but mass conversion creates large diffs — scope per-subsystem.
**Priority**: LOW — style only. Scalafmt config change is the high-value step; mass rewrite is not.
**Agent**: MITHRIL (config change + selective file conversions).

---

### 8h — Property-Based Testing Expansion

**Current state**: 589 ScalaCheck usages already exist in the test suite — the codebase has
`forAll` / `Gen.*` / `Arbitrary` in use. However coverage is uneven.

**Gaps to fill**:
1. **RLP codec round-trip properties**: Every RLP-encodeable domain type should have a
   `forAll { value => decode(encode(value)) == value }` property test. Missing for several
   new message types added in ETH68/69/70 work.
2. **Domain type invariant properties**: `Block`, `BlockHeader`, `Transaction` have
   invariants (e.g., `gasUsed <= gasLimit`) that should be property-tested, not just
   unit-tested with fixed examples.
3. **SNAP protocol message codecs**: `AccountRangePacket`, `StorageRangesPacket`, `ByteCodesPacket`
   — check if fuzz-tested with boundary values (empty ranges, maximum-size ranges, malformed keys).
4. **Cryptographic operations**: `keccak256`, `recoverPublicKey` — check property coverage.

**Approach**: R2 (test quality audit) should enumerate existing ScalaCheck coverage. Add missing
properties during the sprint that migrates the relevant actor (natural pairing — you're already
touching the code and understand the invariants).

**Gate**: R2 (test quality audit) ✅ COMPLETE — `test-quality-audit.md` documents existing ScalaCheck coverage; unblocked.
**Parallel-safe**: YES — individual property test additions are file-scoped.
**Priority**: MEDIUM — catches codec correctness bugs that unit tests miss.
**Agent**: EYE (test validation) + HERALD (for wire-protocol message codecs).

---

### 8i — RLP Typeclass Derivation Modernization

**Current state**: 183 `implicit val`/`def` RLP encoder/decoder instances across the codebase.
These are all handwritten `RLPList`/`RLPValue` construction. Scala 3's `given`/`using` derivation
can replace many of these with automatic derivation for product types.

```bash
grep -rn "RLPImplicit\b\|implicit.*RLP\|implicit.*Encoder\|implicit.*Decoder" \
  src/main/ --include="*.scala" | wc -l  # 183 instances
```

**Two categories**:
1. **Product type codecs** (simple ADTs): Can use Magnolia/Shapeless-style derivation via
   `deriving` clause or `given RLPEncoder[MyType] = RLPEncoder.derived`. Eliminates boilerplate.
2. **Custom-layout codecs** (ETH wire protocol, RLP spec compliance): MUST remain handwritten.
   ETH encoding has non-trivial layout (e.g., empty byte string for zero vs. single-byte for 1).
   Do NOT auto-derive these — FORGE review required.

**Approach**: Research thread (R7) to map which codecs are safe to derive vs. must remain manual.
Part 3a (implicit→given) should be done first — converts the instances to `given` syntax before
considering derivation.

**Gate**: Part 3a (implicit→given) complete. R7 research thread complete.
**Parallel-safe**: NO — codec changes are high-risk (wire protocol correctness). Full test suite
run required after each change.
**Priority**: MEDIUM — reduces maintenance burden for new message types; reduces RLP boilerplate.
**Agent**: MITHRIL (derivation design) + FORGE (any codec touching consensus message layout) +
EYE (full codec test suite after each change).

---

### 8j — Test Quality: Thread.sleep and Timing Sensitivity

**Current state**: 56 occurrences of `Thread.sleep` / `@Ignore` in test files (original baseline). **EYE baseline 2026-06-22**: combined grep now 49 lines (36 `@Ignore`/ignore + 13 `Thread.sleep` raw hits). Live `Thread.sleep` call sites: **2** (comments-only in 3 other files). Prior audit count of 56 reflected a different codebase state.

```bash
grep -rn "Thread\.sleep\|@Ignore\b\|ignore\b" src/test/ --include="*.scala" | wc -l  # 56
```

**Why it matters**: `Thread.sleep` in tests is a primary cause of flakiness under load (CI
slower than dev machine → timeouts). `@Ignore` annotations silently hide untested behavior.

**Approach**: Part of R2 (test quality audit). Map all 56 occurrences:
- `Thread.sleep` → replace with `eventually(...)` from ScalaTest or Pekko's `TestProbe.expectMsg(duration)`
- `@Ignore` → determine if the test is permanently dead (delete) or blocked on missing infra (document why)

**Gate**: R2 audit ✅ COMPLETE (`test-quality-audit.md`, 303 lines). All 7 confirmed Tier B — see `thread-sleep-audit.md §B1–B5` for recipes. **EYE baseline verified 2026-06-22**: 2 live call sites (SubscriptionManagerSpec:249, EthMiningServiceSpec:302). 11 comment-only references (PoWMiningCoordinatorSpec ×3 comments, PendingTransactionsManagerSpec ×4 Scaladoc anti-patterns, SNAPSyncControllerResumeSpec ×1, GcPressureSamplerSpec ×1). No new FLAKY sites beyond the 2 live calls.
**Parallel-safe**: YES — each test file fix is independent. Good downtime task during long test runs.
**Priority**: LOW-MEDIUM — prevents CI flakiness as the test suite grows.
**Agent**: EYE (test validation after fixes).

---

## Recommended Sprint Sequence (post scala3-cleanup-june)

Two tracks run in parallel: **Primary** (blocking, sequential) and **Housekeeping** (parallel-safe,
fills test-wait downtime ~24 min per `testEssential` run). Housekeeping tasks have no gate
dependencies on each other and can be picked up in any order when the primary track is waiting.

### Primary Track (blocking — sequential)

| Sprint | Work | Agents | Gate |
|--------|------|--------|------|
| **Pekko migration** | Part 2: faucet → jsonrpc → transactions → consensus/mining | LOOM, FORGE | scala3-cleanup-june merged |
| **Network/sync Pekko** | S3→S4/S7→NET2→SNAP1→SNAP2→ROOT→CAPSTONE (see SPRINT-QUEUE.md) | LOOM, FORGE, HERALD | Part 2 complete |
| **→ CAPSTONE** | Root flip: ActorSystem[Nothing], bridge/adapter removal, Behavior[Any] narrowing | LOOM | All actors Typed |
| **7d — Artifact audit** | Post-CAPSTONE sweep: surviving Classic patterns, adapter imports, raw schedulers | PRISM, HERALD | CAPSTONE merged |
| **7a — ADT consolidation** ✅ | Seal Command traits: move Messages.scala cases into companion objects DONE — `04615ad43` `4e8b42263`; 173/173; 3,600/0 | MITHRIL, WRAITH | ✅ |
| **7b — EventStream** ✅ | R5 research → Topic[T] migration for eventStream pub/sub sites DONE — `849c0dcf0` (NewPendingTransaction) + `b35b35cf6` (NewBlockImported); `EventTopicsBuilder` trait; 13 files; grep eventStream → 0 | HERALD, LOOM | ✅ |
| **7e — Design review** ✅ | Typed API optimization DONE — 3 accepted redesigns (P4 SSC idle catch-all, P2 HealingState extraction, P3 CD stagnation push); 5 no-change verdicts | PRISM, HERALD, LOOM | ✅ |
| **7c — Supervision** | Explicit Behaviors.supervise per actor with typed failure strategies | PRISM, LOOM | 7a done |
| **8b — Opaque types** | Domain value type safety: BlockNumber, Hash, Address, Balance | MITHRIL, FORGE | Part 3a done |
| **8i — RLP derivation** | Replace handwritten product-type RLP codecs with derivation | MITHRIL, FORGE, EYE | Part 3a + R7 done |
| **Scala 3 idioms** | Part 3a implicit→given (198 files) | MITHRIL + scalafix | After Pekko migration |
| **Scala 3 idioms** | Part 3b COMPLETE `c0a3612b4` — non-consensus; consensus deferred (forge gate) | MITHRIL | ✅ |
| **Scala 3 idioms** | ~~Part 3c isInstanceOf audit~~ ✅ DONE `7cc9eda3a` — 1 site in mpt/Node.scala; 0 in consensus/vm/crypto/domain | MITHRIL | ✅ |
| **Dep upgrades** | Part 4a JLine 4.x | — | Dedicated sprint |
| **Dep upgrades** | Part 4e Jackson 3 → then 4b logstash | — | Ecosystem gate |
| **Scala 3.9** | Part 5a | — | 3.9 release gate |
| **Constitution** | Part 5c | — | After 5a |

### Housekeeping Track (parallel-safe — fill test-wait downtime)

These tasks are independent of the primary track. Any can be started when `testEssential`
(~24 min) or `testStandard` (~30 min) is running and there's an unblocked file to fix.
No actor migration gate. Commit individually; do not bundle with primary-track migration commits.

| Task | Work | Agents | Effort |
|------|------|--------|--------|
| **Part 1** | Remaining 5 compiler warnings | WRAITH | ~30 min |
| **6a — extvm deletion** | Delete `extvm/` (10 files) after grep-verify | WRAITH | ~1h |
| **8f — Dead code audit** ✅ research done | 4 candidates in CHASE-QUEUE (DEAD 2026-06-22); deletion sprint pending | WRAITH | ~30 min deletions |
| **3d — enum polish** | Migrate `SyncPhase`, `BlacklistReason`, `ForkId` codes to enum | MITHRIL | ~1h per file |
| **3e — console→logging** | Replace 24 `println`/`System.out` calls with SLF4J | MITHRIL | ~1h |
| **8e — ScalaFix expansion** | Rules in .scalafix.conf ✅; C2 ✅ `9eb1f4e06`; TNHC ✅ `7a48c5988`; remaining: 7 consensus (FORGE) + 33 SSC (SNAP1) | FORGE / LOOM | gated |
| **8g — braceless config** ✅ `34a55a025` | Deferred settings documented in .scalafmt.conf; indent.defnSite + topLevelStatementBlankLines each trigger ~400-file reformats → gated for per-subsystem pass post-CAPSTONE | MITHRIL | done |
| **8j — Thread.sleep** | Replace test timing sensitivity (2 live call sites — baseline verified EYE 2026-06-22) | EYE | ~30 min |
| **3f — manual sync** | Audit 5 `.synchronized` outside actors | PRISM | ~1h |
| **8a-retro** | Migrate already-completed actors' tests from TestKit → ActorTestKit | LOOM, EYE | ~3h |

### Research Threads (run before implementation; can overlap with primary track)

| Thread | Goal | Output doc | Agent |
|--------|------|-----------|-------|
| **R0** | Full codebase completeness audit (mandatory gate before Wave 2) | `codebase-completeness-audit.md` | PRISM, MITHRIL |
| **R1** | Network/sync Pekko migration plan (22 actors) | `network-sync-pekko-migration-plan.md` | HERALD, LOOM |
| **R2** | Test quality audit (Thread.sleep, coverage gaps, ignored tests) | `test-quality-audit.md` | PRISM, EYE |
| **R3** ✅ | Jackson ecosystem gate (json4s 4.2.0 status) | Part 4e updated — gate nearly open (json4s M5-SNAPSHOT has Jackson 3; watch for M5 stable tag) | general-purpose |
| **R4** | Scala 3.9 readiness (periodic — when 3.9 LTS appears) | Update `scala-39-upgrade.md` | MITHRIL, WRAITH |
| **R5** ✅ | EventStream pub/sub topology map | ✅ DONE — `eventstream-topology.md`; 8 sites / 2 event types / 1 consumer; all Typed already; 2 × `Topic[T]` migration ready; 7b UNBLOCKED | HERALD, LOOM |
| **R6** | Opaque type domain analysis (map BigInt/ByteString semantic roles) | Feeds 8b implementation | MITHRIL, FORGE |
| **R7** | RLP codec derivation safety analysis (safe-to-derive vs must-stay-manual) | Feeds 8i implementation | MITHRIL, FORGE |
| **R8** ✅ | Memory / resource retention audit | ✅ DONE — `memory-leak-audit.md`; 4H/4M/3L; H2+H3 fix-now (StdNode.shutdown), H4 DAG stream leak, H1 BEACON-gated; L1/L2 SNAP sprint, L3 NET sprint | PRISM, VAULT |
| **R9** ✅ | IO threading model audit (blocking calls on actor dispatchers) | ✅ DONE — `threading-model-audit.md`; overall MEDIUM risk; A1 (EngineApiService Await on CE3 compute — fix-now, BEACON gate) + B1 (EC.global in JsonRpcBaseController — defer) + B2 (PoWMiningCoordinator — FORGE gate, CHASE-QUEUE) | PRISM, HERALD, VAULT |

| Sprint | Work | Agents | Gate |
|--------|------|--------|------|
| **Pekko migration** | Part 2: faucet → jsonrpc → transactions → consensus/mining | LOOM, FORGE | scala3-cleanup-june merged |
| **Warning cleanup** | Part 1 remaining 5 warnings | WRAITH | Any sprint |
| **Scala 3 idioms** | Part 3a implicit→given (198 files) | MITHRIL + scalafix | After Pekko migration |
| **Scala 3 idioms** | Part 3b COMPLETE `c0a3612b4` — non-consensus done; consensus deferred (forge gate) | MITHRIL | ✅ |
| **Scala 3 idioms** | ~~Part 3c isInstanceOf audit~~ ✅ DONE `7cc9eda3a` — 1 site in mpt/Node.scala; 0 in consensus/vm/crypto/domain | MITHRIL | ✅ |
| **Dep upgrades** | Part 4a JLine 4.x | — | Dedicated sprint |
| **Dep upgrades** | Part 4e Jackson 3 → then 4b logstash | — | Ecosystem gate |
| **Network/sync Pekko** | S3→S4/S7→NET2→SNAP1→SNAP2→ROOT→CAPSTONE (see SPRINT-QUEUE.md) | LOOM, FORGE, HERALD | Active sprint |
| **→ CAPSTONE** | Root flip: ActorSystem[Nothing], bridge/adapter removal, Behavior[Any] narrowing | LOOM | All actors Typed |
| **7d — Artifact audit** | Post-CAPSTONE sweep: any surviving Classic patterns, adapter imports, raw schedulers | PRISM, HERALD | CAPSTONE merged |
| **7a — ADT consolidation** ✅ | Seal Command traits: move Messages.scala cases into companion objects DONE — `04615ad43` `4e8b42263`; 173/173; 3,600/0 | MITHRIL, WRAITH | ✅ |
| **7b — EventStream** ✅ | R5 research → Topic[T] migration for eventStream pub/sub sites DONE — `849c0dcf0` (NewPendingTransaction) + `b35b35cf6` (NewBlockImported); `EventTopicsBuilder` trait; 13 files; grep eventStream → 0 | HERALD, LOOM | ✅ |
| **7e — Design review** ✅ | Typed API optimization DONE — 3 accepted redesigns (P4 SSC idle catch-all, P2 HealingState extraction, P3 CD stagnation push); 5 no-change verdicts | PRISM, HERALD, LOOM | ✅ |
| **7c — Supervision** | Explicit Behaviors.supervise per actor with typed failure strategies | PRISM, LOOM | 7a done |
| **Scala 3.9** | Part 5a | — | 3.9 release gate |
| **Constitution** | Part 5c | — | After 5a |
| **extvm deletion** | Part 6a | WRAITH | Standalone sprint (grep verify first) |

---

## Clearout Prompts (Unblocked Items)

Items below are actionable now on `scala3-cleanup-june` without external gates.
Each prompt can run independently. Commit individually.

**Run order — this file:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|---------------|
| ~~A3~~ | ~~Batch A~~ | ~~P4 PRISM dead code audit~~ | ✅ DONE 2026-06-22 — 4 items in CHASE-QUEUE |
| ~~A4~~ | ~~Batch A~~ | ~~P6 EYE Thread.sleep audit~~ | ✅ DONE 2026-06-22 — 2 pre-existing (both NECESSARY) |
| ~~B4~~ | ~~Batch B step 4~~ | ~~P5 MITHRIL scalafmt config~~ | ✅ DONE 2026-06-22 — `34a55a025` — deferred settings documented; indent.defnSite + topLevelStatementBlankLines both trigger mass reformats, gated for per-subsystem pass |
| ~~C1~~ | ~~Batch C step 1~~ | ~~P1 MITHRIL isInstanceOf (83 instances)~~ | ✅ DONE 2026-06-22 — 1 site fixed (`7cc9eda3a`); consensus/vm/crypto/domain had 0 hits |
| ~~C2~~ | ~~Batch C step 2~~ | ~~P2 MITHRIL enum candidates~~ | ✅ DONE 2026-06-22 — 4 types converted (`b305ef41b`) |
| C3 | Batch C step 3 | P3 MITHRIL console→logging (28 sites) | Run after C2 |
| C4 | Batch C step 4 | P4 MITHRIL/EYE E165 sprint (777 sites / 83 files) | Run after C1-C3; multi-session sprint |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

### P1 — MITHRIL: isInstanceOf → pattern match (Part 3c, 83 instances)

**Agent:** MITHRIL
**Files:** Non-consensus `src/main/` (skip `consensus/`, `vm/`, `crypto/`, `domain/` — FORGE gate)
**Prerequisite:** None for non-consensus files.

**Prompt:**
> On branch `scala3-cleanup-june`, address Part 3c: replace `isInstanceOf[T]` with
> pattern matching in non-consensus source files.
>
> Steps:
> 1. Inventory: `grep -rn "isInstanceOf\[" src/main/ --include="*.scala" | grep -v "consensus/\|vm/\|crypto/\|domain/"` — count and list files.
> 2. For each occurrence, replace:
>    ```scala
>    // Before
>    if x.isInstanceOf[Foo] then ...
>    // After
>    x match { case _: Foo => true; case _ => false }
>    // or inline: x match { case _: Foo => doThing(); case _ => () }
>    ```
>    Use the cleaner form that fits the context (val guard, if-expression, match block).
> 3. After each file: `sbt compile-all` — 0 errors.
> 4. At the end: `sbt testOnly` for any spec in the same package.
> 5. Do NOT touch `consensus/`, `vm/`, `crypto/`, or `domain/` — those require FORGE review.
>    Add any occurrences found there to CHASE-QUEUE.md as ISINST entries.

**Verification:** `grep -rn "isInstanceOf\[" src/main/ --include="*.scala" | grep -v "consensus/\|vm/\|crypto/\|domain/"` → 0 results

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/DEFERRED-BACKLOG.md` run order table — change `| C1 | Batch C step 1 | P1 MITHRIL isInstanceOf...` to `| ~~C1~~ | ~~Batch C step 1~~ | ~~P1 MITHRIL isInstanceOf (83 instances)~~ | ✅ DONE [date] — N sites fixed |`
- `working-docs/DEFERRED-BACKLOG.md` — mark Part 3c row in Housekeeping Track with ✅ and commit SHA
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA(s)] | Part 3c — isInstanceOf → pattern match (non-consensus, N instances) |`
- `modernization-log/[subsystem].md` for each touched subsystem — add under "Scala 3 Idioms":
  `#### [SHA] — 3c: isInstanceOf → pattern match`
  `- **What:** N sites in [package] replaced; consensus/vm/crypto/domain deferred (FORGE gate)`

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. While reading each file for `isInstanceOf`, scan for other CHASE-QUEUE ISINST, NULL, or MUTABLE entries in the same file and address them inline or draft a prompt.

**Rejection criteria:** Touching consensus/vm/crypto/domain files; changing behavior (match arms must preserve identical semantics); bundling with other unrelated changes

---

### P2 — MITHRIL: Enum remaining candidates (Part 3d) ✅ DONE `b305ef41b` 2026-06-22

**Agent:** MITHRIL
**Files:** Files containing `SyncPhase`, `BlacklistReason`, `ForkId` — confirm with grep first
**Prerequisite:** None. `SyncPhase` and `ForkId` are already partially done (check first).

**Result:** 4 types converted. `SyncPhase` and `ForkId` already converted in prior parts.
Converted: `NetworkType`, `VmConfig.VmMode`, `FaucetStatus`, `SealEngineType`.
Skipped: `MiningMode` (consensus/pow — FORGE gate), `ServerStatus`/`PruningMode` (valid enum
candidates but wider refactor scope than this batch), everything in consensus/vm/domain (FORGE gate).

**Prompt:**
> On branch `scala3-cleanup-june`, address Part 3d: migrate remaining sealed trait + case
> object hierarchies to Scala 3 `enum` where the pattern is a pure value enumeration.
>
> Steps:
> 1. Run: `grep -rn "sealed trait\|sealed abstract class" src/main/ --include="*.scala" | grep -v "Command\|Response\|Event\|Message\|Protocol"` — list candidates.
> 2. For each candidate: confirm it is a pure value set (case objects only, no state, no constructor args that vary). If yes → enum candidate. If has varied constructor args → skip.
> 3. For confirmed candidates, rewrite to:
>    ```scala
>    enum MyEnum:
>      case ValueA
>      case ValueB(field: Type)
>    ```
> 4. Check for companion object `.values`, `withName`, ordering dependencies — migrate those.
> 5. After each file: `sbt compile-all` — 0 errors.
> 6. Run targeted spec: `sbt testOnly *[FileName]*`

**Verification:** `sbt compile-all` clean; targeted tests pass

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/DEFERRED-BACKLOG.md` run order table — change `| C2 | Batch C step 2 | P2 MITHRIL enum candidates...` to `| ~~C2~~ | ~~Batch C step 2~~ | ~~P2 MITHRIL enum candidates~~ | ✅ DONE [date] — N types converted |`
- `working-docs/DEFERRED-BACKLOG.md` — mark Part 3d row with ✅ and commit SHA(s)
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA(s)] | Part 3d — enum migration (N types converted) |`
- `modernization-log/[subsystem].md` for each file touched — add under "Scala 3 Idioms":
  `#### [SHA] — 3d: [TypeName] → enum`
  `- **What:** sealed trait + N case objects → Scala 3 enum`

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. For each file touched, scan for `isInstanceOf` (P1 overlap), `println` (P3 overlap), or CHASE-QUEUE entries in the same file — fix inline or draft prompts.

**Rejection criteria:** Converting Command/Response/Protocol traits (those are ADTs, not enums); adding behavior to enum cases beyond simple fields; touching consensus-critical types without FORGE review

---

### P3 — MITHRIL: console → SLF4J logging (Part 3e, 28 printlns)

**Agent:** MITHRIL
**Files:** `src/main/` (all packages)
**Prerequisite:** None.

**Prompt:**
> On branch `scala3-cleanup-june`, address Part 3e: replace `println` / `System.out.println`
> calls in production code with SLF4J logging.
>
> Steps:
> 1. Inventory: `grep -rn "println\|System\.out\." src/main/ --include="*.scala"` — count (expect ~28).
> 2. For each occurrence:
>    - Identify the appropriate log level (most `println` → `log.info`; error/warning context → `log.warn`/`log.error`)
>    - Replace with the class's existing logger. If the class has no logger, add:
>      ```scala
>      private val log = LoggerFactory.getLogger(getClass)
>      ```
>      (or use Pekko `ActorLogging` / the project's preferred `logging-standards.md` pattern)
>    - Read `.claude/agent-protocols/logging-standards.md` before starting — use the correct logger acquisition pattern for the class type (Actor vs plain class)
> 3. After each file: `sbt compile-all` — 0 errors.

**Verification:** `grep -rn "println\|System\.out\." src/main/ --include="*.scala"` → 0 results

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/DEFERRED-BACKLOG.md` run order table — change `| C3 | Batch C step 3 | P3 MITHRIL console→logging...` to `| ~~C3~~ | ~~Batch C step 3~~ | ~~P3 MITHRIL console→logging (28 sites)~~ | ✅ DONE [date] — N sites fixed |`
- `working-docs/DEFERRED-BACKLOG.md` — mark Part 3e row with ✅ and commit SHA
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA] | Part 3e — console → SLF4J (28 println sites) |`
- No modernization-log entry needed for this housekeeping change

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. While reading each file to replace `println`, scan for `isInstanceOf` (P1) and enum candidates (P2) in the same file — fix inline if small or draft follow-on prompts.

**Rejection criteria:** Removing meaningful log output (preserve the message content); changing log levels in a way that would silence errors; touching test files (`src/test/` printlns are acceptable in tests)

---

### P4 — MITHRIL/EYE: E165 test harness cleanup sprint (777 sites / 83 files)

**Agent:** MITHRIL (type parameter additions), EYE (compile + test verification per batch)
**Files:** `src/test/` — 83 files with unnarrowed TestProbe instances
**Prerequisite:** Batch C P1–P3 complete. Multi-session sprint; commit in batches of ~10 files.

**Context:** EYE S5 sweep (2026-06-22) found 777 unnarrowed TestProbe sites across 83 test files.
Prior "5 in FastSyncBranchResolverSpec" was stale — that file is clean. Highest-density files:
`TrieNodeHealingCoordinatorSpec` (58), `ByteCodeCoordinatorSpec` (56), `AccountRangeCoordinatorSpec` (54),
`StorageRangeCoordinatorSpec` (39), `PeerManagerSpec` (32).

**Prompt:**
> On branch `scala3-cleanup-june`, work through the E165 TestProbe cleanup sprint.
>
> E165 = pattern selectors on `Any` from unnarrowed `TestProbe` type parameters.
> Fix: add `TestProbe[ExpectedMessageType]` wherever the probe's `expectMsg` call
> reveals what message type it receives.
>
> Strategy (for 777 sites across 83 files — work in batches of ~10 files per session):
> 1. Start with highest-density files (list above) — they have established patterns.
> 2. For each file:
>    a. `grep -n "TestProbe\b" path/to/Spec.scala | grep -v "\["` — list unnarrowed sites
>    b. For each site, read surrounding `expectMsg`/`expectMsgType`/`fishForMessage` to
>       determine the expected message type
>    c. Add `TestProbe[MessageType]` type parameter
>    d. `sbt compile-all` after each file — confirm E165 count decreasing
> 3. Commit each batch: `E165 batch N — TestProbe type params (N files, N sites)`
> 4. Do NOT change any production source files or any test logic.
> 5. If a probe receives heterogeneous message types with no common supertype, leave as
>    `TestProbe[Any]` and add `// E165: heterogeneous` comment.

**Verification (per batch):** `grep -rn "TestProbe\b" src/test/ --include="*.scala" | grep -v "\[" | wc -l` decreasing each batch

**MANDATORY final step — complete after EACH batch commit AND when sprint is fully done:**
- **Per batch:** `working-docs/PENDING.md` — update Test Hygiene item with running count (e.g., "777→N remaining")
- `modernization-log/node/testing-infra.md` — add entry per batch:
  `#### [SHA] — E165 batch N: TestProbe type params (N files, N sites)`
  `- **What:** [list files]; E165 count −N`
- **When fully complete:** remove item from `PENDING.md` Test Hygiene; add to `completed/PENDING.md`; mark run order table row: `| ~~C4~~ | ~~Batch C step 4~~ | ~~P4 MITHRIL/EYE E165 sprint~~ | ✅ DONE [date] — 777→0 sites |`

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. While working through each test file, check for Thread.sleep (P6 DONE — 2 known sites), wall-clock assertions (CHASE-QUEUE wall-clock-assertion section), or missing test coverage for the class under test — draft prompts for anything new found.

**Rejection criteria:** Production file edits; test logic changes; leaving heterogeneous probes without the `// E165: heterogeneous` comment

---

### P5 — MITHRIL: braceless scalafmt config (Part 8g, ~15 min)

**Agent:** MITHRIL
**Files:** `.scalafmt.conf`
**Prerequisite:** None. Config-only change; no mass rewrite.

**Prompt:**
> On branch `scala3-cleanup-june`, address Part 8g: add the braceless-prefer configuration
> to `.scalafmt.conf` so that new code written with braceless syntax formats correctly.
>
> This is a config addition only — do NOT run a mass-rewrite of existing files.
>
> Steps:
> 1. Read `.scalafmt.conf` to find the current config structure.
> 2. Add the braceless preference setting (Scala 3 indentation-based syntax):
>    ```
>    runner.dialect = Scala3
>    indent.defnSite = 2
>    newlines.topLevelStatementBlankLines = [{ blanks { before = 1 } }]
>    ```
>    (Adjust if already partially configured — only add what's missing.)
> 3. Run: `sbt scalafmtAll` — must complete without errors. Diff should be small (config line only; no source reformat should happen from this change alone).
> 4. Compile: `sbt compile-all` — 0 errors.

**Verification:** `sbt scalafmtAll` clean; `sbt compile-all` clean; diff is `.scalafmt.conf` only

**MANDATORY final step — complete BEFORE closing thread:**
- `working-docs/DEFERRED-BACKLOG.md` run order table — change `| B4 | Batch B step 4 | P5 MITHRIL scalafmt config...` to `| ~~B4~~ | ~~Batch B step 4~~ | ~~P5 MITHRIL scalafmt config~~ | ✅ DONE [date] — config updated |`
- `working-docs/DEFERRED-BACKLOG.md` — mark Part 8g row with ✅ and commit SHA
- `completed/SPRINT-QUEUE.md` — append row: `| [SHA] | Part 8g — braceless scalafmt config added |`

**Opportunistic clearout:** Apply the protocol in CODEBASE-AUDIT.md. While `.scalafmt.conf` is open, check if any other scalafmt/scalafix configuration gaps (from the Part 1 warning cleanup or `.scalafix.conf` expansion in §8e) can be added in the same commit.

**Rejection criteria:** Triggering mass source reformatting; modifying any `.scala` source files; using an incompatible scalafmt version option

---

---

## Part 9: Dead Code — Deferred Wiring Candidates

Items identified during dead-code sweeps where the verdict was DEFER rather than DELETE.
See `agent-protocols/dead-code-review.md` for the full assessment protocol.

### 9a — SyncStartupStrategy extraction (from deleted AdaptiveSyncStrategy)

**Context:** `AdaptiveSyncStrategy.scala` (193 lines) was deleted in Part 8f as
unintegrated dead code. However, the design logic it contained addresses a real
gap: `SyncController.start()` selects sync mode from static config booleans with
no peer-count or latency pre-flight. If `doSnapSync=true` but fewer than 3 peers
are available, SNAP attempts and fails N times before reactive fallback triggers.

**What should be built:** A lightweight pure function:
```scala
def selectSyncMode(peerCount: Int, snapCapablePeers: Int, latencyMs: Long,
                   config: SyncConfig): SyncMode
```
This is NOT the full `AdaptiveSyncController` class (mutable state, strategy objects).
It is the decision logic only — extracted, tested as a pure function, wired into
`SyncController.start()` at the sync mode selection branch (~line 1387).

**Wiring point:** `SyncController.start()` — the 5-branch pattern-match on
`(isSnapSyncDone, isFastSyncDone, doSnapSync, doFastSync)` config booleans.
Add a pre-flight check: if `doSnapSync && snapCapablePeers < 3`, downgrade to
`doFastSync` mode rather than attempting SNAP and waiting for reactive failure.

**Benefit:** Reduces day-1 sync latency on low-peer-count or high-latency networks.
Turns "wait for N failures then fallback" into "check conditions upfront, start on
the right mode immediately."

**Prerequisite:** None. Not consensus-critical. Candidate for a standalone task
after the current sprint queue clears.

**Agent:** Sonnet (pure function + SyncController wiring, not consensus-critical)

---

~~### P6 — EYE: Thread.sleep audit~~ ✅ DONE 2026-06-22

**Result:** 2 pre-existing sites found — `EthMiningServiceSpec.scala:302` (timeout window advance,
NECESSARY) and `SubscriptionManagerSpec.scala:249` (topic propagation wait, NECESSARY). Neither
is flaky. No CHASE-QUEUE entries needed. Part 8j baseline: 2 sites, both intentional.
