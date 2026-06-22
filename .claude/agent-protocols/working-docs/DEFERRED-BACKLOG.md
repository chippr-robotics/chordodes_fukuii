# Fukuii Modernization — Deferred Backlog

**Last updated**: 2026-06-18 (Part 8 added — Classic TestKit, opaque types, memory audit, IO threading, ScalaFix expansion, dead code, braceless syntax, property-based testing, RLP modernization, test quality; sprint sequence updated with parallel housekeeping tracks)
**Purpose**: Single reference for all deferred cleanup work — completed items,
active deferred items, and follow-up sprint plans.

Active sprint plan: `/home/dev/.claude/plans/we-are-working-on-noble-whisper.md`

---

## scala3-cleanup-june Sprint — COMPLETE

All in-scope work is committed on `scala3-cleanup-june`. Summary:

| Commit | Phase | What |
|--------|-------|------|
| `b25d117b3` | prereq | Scala 3.3.8 LTS bump + scapegoat 3.3.6 |
| `003752df3` | prereq | June dep sync (enumeratum, scalatest, scalamock, cats-effect, fs2, etc.) |
| `655240c68` | 1a | Pre-fix mixed-selector and cats bare wildcard imports |
| `333aab3fc` | 1b | bytes/, crypto/, rlp/ wildcard migration + `-source:future` enabled |
| `3693906d1` | 2 | 730-file warning elimination (ScalaMock E008, open class, type wildcards, `.scalafix.conf`) |
| `a53c039fd` | 1c | 633-file main src/ wildcard migration (incremental compiler had masked these) |

**End state**: `sbt compile-all` → 0 errors, 134 warnings (all pre-existing Pekko Classic E165
from un-migrated actors — resolved in the Pekko migration follow-up sprint below).

**Items that were NOT brought in (intentionally):**
- `c77c2ebf7` (ResourceHealthMonitor) — new feature added on `june-sprint`; does not belong on a cleanup branch
- Pekko Classic→Typed migrations — scope was too broad and insufficiently planned; promoted to own sprint (Part 2 below)

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
- ~~#1 `BlockHeaderValidatorSkeleton.scala:218` unused implicit `_blockchainConfig`~~ — cleared
- ~~#2 `PeersClient.scala:326` unused param `_peer`~~ — cleared
- ~~#3 `extvm/VMClient.scala:22` unused constructor param~~ — cleared (C4 deleted extvm entirely)
- ~~#4/#5 `PathNodeStorage.scala` unused `hash` params~~ — cleared
- ~~#6 `ETHPackets.scala:106` E092 `@unchecked`~~ — cleared
- ~~E003 `with` in self-types (337 occurrences)~~ — cleared in W2-P3a + W2-P1
- ~~E198 unused test symbols~~ — addressed

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
| ~~`@annotation.unused timers`~~ | ~~`PeerManagerActor.Impl:272`~~ | ~~Impl receives `TimerScheduler[Command]` from `Behaviors.withTimers` but uses `classicSystem.scheduler` exclusively. Suppressed with `@annotation.unused` during C3b.~~ | ✅ DONE `4b101b612` — `Behaviors.withTimers` wrapper dropped entirely; `@annotation.unused timers: TimerScheduler[Command]` removed from Impl constructor; import dropped. `classicSystem.scheduler` private def stays — still used for `scheduleWithFixedDelay` (node-update, status-refresh) and `scheduleOnce` (connect retries). 61/61 PeerManager tests green. Full typed-timer migration deferred to network/P2P sprint. | — |
| ~~NET-01~~ | ~~`NetworkPeerManagerActor.scala:165,451,663`~~ | ~~`classicSystem.scheduler` for two fire-and-forget blacklist-delay `scheduleOnce` calls. HERALD verified by-design (2026-06-21).~~ | ✅ DONE `6b506a63f` (partial) — `@annotation.unused timers: TimerScheduler[Any]` removed from NPMA Impl constructor; `timers,` removed from `new Impl(ctx, timers, ...)` call; TimerScheduler import dropped. `private def scheduler = ctx.system.classicSystem.scheduler` stays — correct for the two fire-and-forget `AddToBlacklistCmd` delays. Full treatment (path b: typed timers) deferred to network/P2P sprint. | — |

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
- ~~`SyncPhase`~~ ✅ DONE `adf4e69ea` — 8-member single-line enum; `SyncPhase.*` imported at 5 call sites
- ~~`ForkId` message codes~~ ✅ DONE `adf4e69ea` — `ForkIdValidationResult` 3-member enum; 4 external callers updated
- ~~`Blacklist.BlacklistReason`~~ ❌ REJECTED — has 7 `final case class` subtypes (`EmptyBlockBodies`, `EmptyReceipts`, `InvalidReceipts`, `FastSyncRequestFailed`, `InvalidStateResponse`, `RegularSyncRequestFailed`, `BlockImportError`). Not a pure discriminant; cannot be an enum.
- ~~`Blacklist.BlacklistReasonType`~~ ❌ REJECTED — non-trivial behavior fields (`code: Int`, `name: String`) and mixin group traits (`FastSyncBlacklistGroup` etc.). Not a pure discriminant enum.
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

### ~~6a — extvm/ Dead Code Deletion~~ ✅ DONE (`a948fda1d`)

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

### ~~7a~~ ✅ Non-sealed Command ADT Consolidation (W2-P3d) — DONE

**What**: BCC and likely SRC/ARC/TNHC/SSC use `trait Command` (non-sealed) because message cases
are declared in `Messages.scala` across package boundaries — Scala 3 cannot seal across files.
Post-CAPSTONE, consolidate: move all message definitions for each coordinator into its own companion
object, then seal the trait. This enables exhaustiveness checking at every `match` site.

**Scope**: `snap/actors/Messages.scala` refactor — redistribute cases into coordinator companions.
Update all callers (SSC is the primary sender; it will need import updates). One commit per actor.

**Gate**: CAPSTONE complete (all coordinators Typed; SSC Typed — SNAP1 done).
**Priority**: Medium — correctness improvement (exhaustiveness checking catches missing cases at compile time).
**Agent**: MITHRIL + WRAITH (compile verification after each redistribution).

**Status: ✅ COMPLETE**

| Actor | Commit | Notes |
|-------|--------|-------|
| BCC — ByteCodeCoordinator | prior session | Command cases moved to companion; sealed |
| SRC — StorageRangeCoordinator | prior session | Command cases moved to companion; sealed |
| ARC — AccountRangeCoordinator | prior session | Command cases moved to companion; sealed |
| TNHC — TrieNodeHealingCoordinator | `04615ad43` | Command cases moved to companion; sealed |
| SSC — SNAPSyncController | prior session | Command companion from SNAP1; sealed in 7a |
| Format | `4e8b42263` | `sbt scalafmtAll` after all redistributions |

Verification: All 5 sealed `trait Command` confirmed in companion objects. `Messages.scala` → empty tombstone object. 173/173 coordinator specs ✅. testEssential: **3,600 / 0** ✅.

Gate for **7c** (Supervision) and **7e** (Design Review) now satisfied.

---

### ~~7b~~ ✅ EventStream → Topic[T] — DONE

**✅ DONE** — LOOM. 2 commits on `scala3-cleanup-june`:
- `849c0dcf0` — P1: `NewPendingTransaction` → `Topic[NewPendingTransaction]` (PTM, SubscriptionManager)
- `b35b35cf6` — P2: `NewBlockImported` → `Topic[NewBlockImported]` (BlockImporter, RegularSync, SyncController)

**Design:** `EventTopicsBuilder` trait introduced in `NodeBuilder.scala` providing `pendingTxTopic` and `blockTopic` as lazy vals; mixed into concrete node builders.

**Scope:** 13 files — wider than estimated (~5); `blockTopic` required threading through RegularSync and SyncController.

**Verification:** `compile-all` clean; `grep eventStream` → 0 (event bus fully retired for both types); SM 10/10, PTM 13/13, FM 5/5, SyncControllerSpec 84/84, RS+calibration 65/65; `scalafmtAll` clean. Left untouched: `LegacyTransactionHistoryServiceSpec.scala` (in-flight Typed migration, unrelated).

---

**R5 status: ✅ COMPLETE** — topology at `.local/docs/moderization-review-june/eventstream-topology.md`

**R5 findings:**
- 8 sites across 3 files, 2 event types, 1 consumer
- Zero `.toClassic.eventStream` bridges — CAPSTONE Phase 3 (`f5ece260a`) already completed the Classic→Typed lift
- All 8 sites already use `EventStream.Publish` / `EventStream.Subscribe`
- Topology: `BlockImporter` → `NewBlockImported` → `SubscriptionManager`; `PendingTransactionsManager` → `NewPendingTransaction` → `SubscriptionManager`
- Both relationships are cross-subsystem → direct typed channels architecturally wrong; both map cleanly to `Topic[T]`
- No `@SerializabilityTrait` needed (single JVM, no Pekko remoting)
- No FORGE or HERALD gates — application-layer events; `NewBlockImported` fires after consensus complete

**7b implementation — UNBLOCKED. Effort: Small.**
- Spawn 2 `Topic[T]` actors in `NodeBuilder` (line 971 confirmed)
- Pass `Topic[NewPendingTransaction]` ref to PTM, `Topic[NewBlockImported]` ref to `BlockImporter`
- Replace `eventStream.Subscribe` in `SubscriptionManager`
- Order: `NewPendingTransaction` first (2 producer sites), then `NewBlockImported` (2 publish sites in `BlockImporter`)

**What**: The Classic `context.system.eventStream` is still used for cross-cutting concerns
(transaction events, mining notifications, etc.) routed via `ctx.messageAdapter` / `toClassic.eventStream`
bridges (noted in Part 2 bridge table: PTM's `toClassic.eventStream` is the primary site).
Typed replacement is `pekko.actor.typed.pubsub.Topic[T]` — more efficient, fully type-safe,
no bridge needed.

**Research thread first (R5)**: Map all `eventStream.publish` and `eventStream.subscribe` sites across
the codebase. Categorize by message type and producer/consumer set. Determine which can become
`Topic[T]`, which can become direct parent→child typed messages, and which legitimately need a bus.

```bash
grep -rn "eventStream\.publish\|eventStream\.subscribe\|eventStream\.unsubscribe" \
  src/ --include="*.scala"
```

**Gate**: CAPSTONE complete (root flip removes the Classic eventStream as the natural integration point). ✅
**Priority**: Medium — removes last Classic-era cross-cutting coupling; improves message traceability.
**Agent**: HERALD (pub/sub topology) + LOOM (per-site migration).

---

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

### ~~7e~~ ✅ Typed API Design Review (Idiomatic Rewrite Pass) — DONE

**Status: ✅ COMPLETE** — findings at `.local/docs/moderization-review-june/typed-api-design-review.md`

**Accepted redesigns (3 items):**

| ID | Pattern | Finding | Priority | Effort |
|----|---------|---------|----------|--------|
| P4 | SSC idle-state catch-all (line 655) | `case other =>` suppresses exhaustiveness checking. Enumerate explicit per-case handlers; future additions require an explicit decision. | MED | M |
| P2 | HealingState extraction | 6 healing-specific vars (`trieWalkInProgress`, `healingServeRootRequestInFlight`, etc.) are phase-local; extract to `case class HealingState` behavior parameter to prevent bleeding across pivot refreshes. | LOW | S–M |
| P3 | CheckDownloadStagnation timer | ChainDownloader should push `DownloadStagnated` to SSC rather than SSC polling progress timestamps. | LOW | S |

**No changes (5 items):**
- P1 fire-and-forget chains: all correct — no ack semantics needed, no throughput risk
- P3 four 1-second dispatch timers: polling-by-design (SSC drives, coordinators execute)
- P5 eventStream: already fully Typed API; cross-subsystem relationships correctly use event bus

No FORGE or HERALD gates triggered. 7a gate (sealed ADTs) confirmed satisfied — `Messages.scala` empty tombstone; all 5 SNAP actors have sealed `Command` traits.

**What**: The mechanical migration preserves Classic-era logic design. Once the scaffolding is gone
and the artifact audit is clean, evaluate whether the code's internal design would be written
differently if it had been designed for Typed from the start. This is not a correctness pass —
it is a design improvement pass.

**Patterns to evaluate** (each is a candidate sub-thread):

**Fire-and-forget → ask with typed reply**
Classic actors used `!` for most communication because `?` (ask) was expensive and required
`Future`-wrapping. In Typed, `replyTo` ask is first-class and cheap. Coordinator→SSC notification
chains (e.g., `AccountRangeSyncComplete`, `ByteCodeSyncComplete`) that currently fire unacknowledged
could become typed request/response pairs, giving SSC the ability to apply back-pressure or confirm
receipt. Evaluate which notification chains would benefit from acknowledgment.

**Impl-class mutable state → functional state threading**
The mechanical migration puts all mutable state on `Impl` classes (SSC has ~58 fields, NPMA has 8+,
ARC has more). Idiomatic Typed design threads state through behavior parameters for actors with
clear phase transitions. This improves testability (no mutable state to reset between test cases)
and makes state transitions explicit and auditable. Evaluate per actor: which fields change together
on a state transition (those become a state parameter tuple), vs. which are persistent across all
phases (those stay on Impl). SNAPSyncController is the highest-value target — its `SyncPhase` enum
already models the phases; the question is whether the ~58 fields can be partitioned into per-phase
and cross-phase sets.

**Event bus pub/sub → direct parent/child typed channels**
After 7b (Topic[T] migration), evaluate whether any remaining pub/sub relationships would be
cleaner as direct typed parent→child channels. The event bus pattern from Classic was often used
to avoid direct actor references (which were untyped and fragile). With Typed ADTs, direct typed
refs are safe and more explicit. Coordinator workers receiving tasks directly from their coordinator
(rather than via a bus) is the primary target.

**Timer-driven polling → request/response with back-pressure**
Several coordinators use recurring 1-second timers (`RequestAccountRanges`, `RequestByteCodes`, etc.)
to poll for readiness and dispatch work. This is a Classic-era pattern (Classic actors couldn't
efficiently wait for a reply and then dispatch). In Typed, a coordinator could dispatch a task,
wait for typed acknowledgment from the worker, then dispatch the next — eliminating the polling
timer entirely and naturally applying back-pressure. Evaluate which dispatch loops are polling-by-design
vs. polling-because-Classic.

**Non-sealed ADT exhaustiveness cleanup** (depends on 7a complete)
After ADTs are sealed, the compiler will flag any `match` site missing a case. Review each
`Behaviors.unhandled` or `case other =>` that the compiler now requires explicitly — some represent
genuine design holes (missing handler for a real message), others are correct catch-alls.

**Output**: Per-pattern research report + prioritized implementation queue. Each pattern is a
separate sub-thread; not all patterns will have actionable findings for every actor.

**Gate**: 7d (artifact audit) complete. 7a (sealed ADTs) ideally complete for the exhaustiveness pass.
**Priority**: Medium-High — this is where the Typed migration delivers compounding design value beyond
the mechanical correctness improvements. The longer this is deferred, the more the Classic design
calcifies as "the way it works."
**Agent**: PRISM (design review across 8 lenses) + HERALD (wire-protocol design patterns) + LOOM
(implementation of accepted redesigns). Each accepted redesign is its own scoped implementation thread.

---

### ~~7e-P4~~ ✅ SSC Idle-State Catch-All Exhaustiveness — DONE

**What**: `SNAPSyncController` idle state has a blanket `case other => ...` at line 655 that
suppresses Scala 3 exhaustiveness checking for the sealed `Command` trait. Every future `Command`
addition silently falls through without a compile-time error. Enumerate explicit per-case handlers
(or explicit `case _: SpecificCommand => Behaviors.unhandled`) so the compiler catches missing cases.

**Scope**: `sync/snap/SNAPSyncController.scala` — idle-state receive block only. One LOOM session.
**Gate**: 7a complete (sealed `Command` trait — ✅ done).
**Priority**: MED — correctness guard; prevents silent no-op on new Commands in idle state.
**Effort**: M — requires reviewing every `Command` subtype and deciding idle-state behaviour for each.
**Agent**: LOOM (implementation) + EYE (verify 74/74 SNAPSync specs still pass).

**Status: ✅ COMPLETE**

Removed `case other => ctx.log.debug(...)` catch-all from `idle()` (lines 655–657). Added 59 explicit
handlers covering every `Command` variant. Decision table:

| Category | Treatment | Examples |
|----------|-----------|---------|
| Unexpected signals | `log.warn` + `Behaviors.unhandled` | `BootstrapComplete`, `AccountRangeSyncComplete`, `StateHealingComplete` |
| Stale timer/coordinator messages | `log.debug` + `Behaviors.unhandled` | `RetrySnapSyncStart`, `TuneRateTracker`, `ChainDownloaderProgress` |
| High-frequency request ticks | silent drop (`Behaviors.same`) | `RequestAccountRanges`, `RequestByteCodes`, progress deltas |

**Commit:** `1da94de11`. Compile: 0 errors, 1 pre-existing E165 (line 546, unrelated). VERIFY: `SNAPSyncControllerSpec` 69/69 ✅.

**Design gap flagged (tracked below as 7e-P4a):** `GetProgress` has no reply path in `idle()` —
ask-pattern callers will time out. `GetStatus` replies `NotSyncing` in idle; `GetProgress` should
reply with `progressMonitor.currentProgress` (same pattern as `completed()`). Separate task.

---

### ~~7e-P4a~~ ✅ GetProgress Missing Idle-State Handler (SSC) — DONE

**✅ DONE** — `74db726d1`. Replaced 7-line warn-and-drop block at SSC:658 with `replyTo ! progressMonitor.currentProgress`, matching `syncing()` at line 1775. Compile: 0 errors (pre-existing E165 unrelated). SNAPSyncControllerSpec: 69/69 ✅.

**What**: `GetProgress` (companion query to `GetStatus`) is handled in `syncing()`, `completed()`,
and `completedWithBackfill()` but has no handler in `idle()`. `GetStatus` correctly replies
`NotSyncing` in idle; `GetProgress` silently drops the message, causing ask-pattern callers to
time out. Fix: add `case GetProgress(replyTo) => replyTo ! progressMonitor.currentProgress` to
`idle()` — the same pattern already used in `completed()`.

**Scope**: `sync/snap/SNAPSyncController.scala` — `idle()` receive block only. XS effort (1 line + test).
**Gate**: P4 complete (✅ — idle() is now exhaustively enumerated, making this gap visible).
**Priority**: MED — any caller invoking `GetProgress` before sync starts will hang until ask timeout.
**Effort**: XS.
**Agent**: LOOM (1-line fix) + EYE (verify `SNAPSyncControllerSpec` idle-state `GetProgress` path).

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

### ~~8c~~ ✅ Memory / Resource Leak Audit — DONE

**Output:** `.local/docs/moderization-review-june/memory-leak-audit.md` — 4 HIGH / 4 MEDIUM / 3 LOW.

**Recommended first fixes:** H2 + H3 (single-line additions to `StdNode.shutdown()`) — immediate hang/data-loss risk on every graceful stop. H1 needs BEACON sign-off on eviction policy.

| ID | Severity | File | Issue | Status |
|----|----------|------|-------|--------|
| **H1** | HIGH | `consensus/engine/EngineApiService.scala:42–75` | **6×** `ConcurrentHashMap` fields (PRISM missed `acceptedChildrenByParent` line 74) — no eviction; multi-GB heap growth over days | ✅ CLASS A `4f5a678fa` + CLASS B `8911135d9` |
| **H2** | HIGH | `StdNode.scala:208–219` | Secondary `ActorSystem` for `PeriodicConsistencyCheck` created and discarded — never terminated on `shutdown()` | ✅ `4907406fe` |
| **H3** | HIGH | `StdNode.scala:238–265` | `EngineApiHttpServer` (its own `ActorSystem` + `IORuntime`) never stopped in `shutdown()` — leaves port 8551 bound after node death | ✅ `4907406fe` |
| **H4** | HIGH | `EthashDAGManager.scala:64–78` | `FileOutputStream` closed in `Try(...)` outside `finally` — leaks descriptor + leaves corrupt file on disk if write throws | ✅ `ef75a5608` — `Using` wrap + corrupt partial file deleted on throw |
| M1 | MEDIUM | `EthashDAGManager.scala:84–104` | `FileInputStream` with 3 exit paths that skip `close()` | ✅ `ef75a5608` — `Using` wrap; `Try[Array[Array[Int]]]` return contract preserved via `.flatten` |
| M2 | MEDIUM | `PeerDiscoveryManager.scala:84` | `Resource.allocated` release IO stored correctly but no `PostStop` handler — UDP socket leaks on actor crash | **DEFERRED** — needs actor migration context (W2-P2 network sprint) |
| M3 | MEDIUM | `FileUtils.scala:28` | `getReader` returns raw `BufferedSource` with no lifecycle contract | ✅ `4907406fe` — Scaladoc only; sole caller (`SSLContextFactory.scala:22–27`) already closes in `finally` |
| M4 | MEDIUM | `NodeBuilder.scala:1102` | Race window between `portForwarding.allocated` placeholder and real cleanup IO write | **DEFERRED** |
| L1 | LOW | `TrieNodeHealingCoordinator.scala:365` | `healAttempts` map unbounded when serve-root is slow | **DEFERRED** — batch with future SNAP cleanup |
| L2 | LOW | `StorageRangeCoordinator.scala:126` | `accountSubtaskCounters` entries never removed after account completes | **DEFERRED** — batch with future SNAP cleanup |
| L3 | LOW | `PeerRateTracker.scala:25` | Disconnected peers never evicted from `peers` map | **DEFERRED** — batch with future network cleanup |

---

### ~~8d~~ ✅ IO / Concurrency Threading Model Audit — DONE

**Overall risk: MEDIUM.** Dispatcher architecture is sound — sync, healing, account-trie, engine-api, and block-forger all have properly isolated bounded pools; four `blocking { }` sentinels in SNAP coordinators correctly placed. Two real problems; everything else vestigial imports, startup/shutdown `Await`s (by-design), or `Thread.sleep` on dedicated non-actor threads (by-design).

**Output:** `.local/docs/moderization-review-june/threading-model-audit.md`

**Findings:**

| ID | File | Issue | Severity | Gate | Status |
|----|------|-------|----------|------|--------|
| **A1** | `consensus/engine/EngineApiService.scala:561` | `Await.result(future, 3.seconds)` inside `forkchoiceUpdated`'s `IO { }` body (= `IO.delay`) — executes on CE3 compute pool; blocks compute thread up to 3s per CL invocation; threatens staking availability under load. Fix: `IO.fromFuture(IO(ask))` | **fix-now** | BEACON | tracked below as §8d-A1 |
| **B1** ✅ | `jsonrpc/JsonRpcBaseController.scala:41` | `EC.global` as implicit `ExecutionContext` — all `Future` combinators and Pekko asks in RPC services (incl. KeyStore file I/O) run on unbounded global pool. Fix: replace with `actorSystem.dispatcher` | defer | `276c77735` | tracked below as §8d-B1 |
| **B2** | `consensus/pow/PoWMiningCoordinator.scala` | FORGE-gated finding — see CHASE-QUEUE | defer | FORGE | CHASE-QUEUE |

---

### ~~8c-H1-A~~ ✅ EngineApiService CLASS A: Bounded LRU + TTL Eviction — DONE (`4f5a678fa`)

**Files**: `consensus/engine/EngineApiService.scala` — 4 payloadId-keyed build caches: `pendingPayloads`, `pendingPayloadRequests`, `pendingPayloadBlobsBundle`, `pendingPayloadReceipts`.

**Why CL-driven eviction is impossible**: The Engine API has no "discard payload" call; FCU never references `payloadId`. Eviction must be EL-self-driven.

**Implementation (Option 2 — shared timestamp map):** Added `pendingPayloadTimestamps: ConcurrentHashMap[ByteString, Long]` as a 5th eviction-index map. Helpers: `removePayloadEntry(id)` removes from all 5 atomically; `evictOldestIfAtCapacity()` synchronized on `evictionLock` — scans for smallest nanoTime when `size >= 64`. PUT site calls `evictOldestIfAtCapacity()` then timestamps before the 4 existing `.put()` calls (unchanged). GET site checks `nanoTime - insertedAt > PayloadTtlNs`; if stale, calls `removePayloadEntry` and returns `Left("Payload not available")` — naturally cleans up orphaned `pendingPayloadRequests` from V1/V2/V3 `getPayload` calls.

VERIFY: 16/16 tests pass.

**Gate**: None beyond BEACON review (already complete).
**Owner**: CONDUIT — touches `getPayload` accessor signatures (service lines 835–859).
**Effort**: S–M.

---

### ~~8c-H1-B~~ ✅ EngineApiService CLASS B: Finalized-Watermark Prune — DONE (`8911135d9`)

**Files**: `consensus/engine/EngineApiService.scala` — 2 hash-keyed correctness maps: `invalidBlocks`, `acceptedChildrenByParent`.

**Why TTL is unsafe**: `invalidBlocks` must not be evicted while any hash could still be referenced as an FCU head — early eviction = consensus fault.

**Safe trigger**: Prune entries for blocks at or below `forkChoiceState.finalizedBlockHash` height. This value is already parsed at service:432 and controller:686.

**`acceptedChildrenByParent` note**: Self-prunes on the invalidation path but leaks entries whose ancestor is never revealed invalid. Same finalized-watermark trigger applies.

**Implementation:** Insertion point — inside `case Right(())` in `forkchoiceUpdated`, after mempool purge and before payload-attributes validation (~line 532). This is the exact moment `forkChoiceState` (including `finalizedBlockHash`) has been committed. When `finalizedHash != zeroHash`, resolves its block number from `blockchainReader` (synchronous `Option[BlockHeader]`), then calls `removeIf` on both maps: `invalidBlocks` keys are invalid block hashes, pruned where `block.number <= finalizedNumber`; `acceptedChildrenByParent` keys are parent block hashes, pruned where `parent.number <= finalizedNumber`. Blocks above the watermark are untouched — an invalid block not yet finalized may still be an FCU head candidate.

VERIFY: `compile-all` — 0 errors. `testOnly *EngineApi*` — 16/16 ✅.

**Gate**: BEACON impl thread — consensus-correctness-touching; requires BEACON review before implementing.
**Owner**: BEACON.
**Effort**: S.

---

### ~~8c-H2/H3~~ ✅ StdNode.shutdown() Missing Teardown — DONE (`4907406fe`)

**Files**: `StdNode.scala:208–219` (H2) and `StdNode.scala:238–265` (H3)

**H2**: Secondary `ActorSystem` spawned for `PeriodicConsistencyCheck` is created and discarded — never terminated on `shutdown()`. On every graceful stop, this system's threads remain alive until the JVM exits.

**H3**: `EngineApiHttpServer` owns its own `ActorSystem` + `IORuntime`. Neither is stopped in `shutdown()`. Port 8551 remains bound after node death, preventing restart without `kill -9`.

**Fix**: Both are single-line additions to `StdNode.shutdown()` — call `.terminate()` on the consistency-check system and `.stop()` on the engine-API server/runtime. No logic changes.

**Gate**: None. BEACON awareness only (H3 is engine-api adjacent but the fix is lifecycle plumbing, not protocol logic).
**Priority**: fix-now / HIGH — affects every graceful node stop.
**Effort**: XS (2 lines total).
**Agent**: Any (LOOM or standalone).

---

### ~~8c-H4~~ ✅ EthashDAGManager Stream Safety — DONE (`ef75a5608`)

**File**: `EthashDAGManager.scala:64–78` (H4) and `64–104` (M1 alongside)

**H4**: `FileOutputStream` closed in `Try(...)` outside `finally` — on a write exception, the stream is not closed and the partially-written DAG file is left on disk uncorrupted. Next boot will read a corrupt DAG.

**M1**: `FileInputStream` at lines 84–104 has 3 exit paths that bypass `close()`.

**Fix**: Wrap both in `Using(new FileOutputStream(...)) { ... }` / `Using(new FileInputStream(...)) { ... }` (Scala `scala.util.Using`).

**Gate**: FORGE — EthashDAGManager is in the PoW/consensus path; stream-handling change needs FORGE sign-off even though it is pure I/O plumbing.
**Priority**: HIGH (H4) / MEDIUM (M1) — DAG corruption is unrecoverable without manual delete.
**Effort**: XS.
**Agent**: FORGE review → any implementer.

---

### ~~8c-M3~~ ✅ FileUtils.getReader Resource Contract — DONE (`4907406fe`)

**File**: `FileUtils.scala:28`

**Problem**: `getReader` returns a raw `BufferedSource` with no lifecycle contract — callers are responsible for calling `.close()` but nothing enforces this. Unclosed sources leak file descriptors.

**Fix**: Change return type to use `Using`-managed pattern or return `Resource[IO, BufferedSource]` / `Using.Manager`. Alternatively, change callers to wrap in `Using(FileUtils.getReader(...)) { src => ... }` at each call site.

**Gate**: None.
**Priority**: fix-now / MEDIUM — accumulates on long-lived nodes.
**Effort**: XS–S (depends on caller count).
**Agent**: Any.

---

### ~~8d-A1~~ ✅ EngineApiService Blocking Await on CE3 Compute Thread — DONE (`0a8ed3038`)

**File**: `consensus/engine/EngineApiService.scala:561`

**Problem**: `Await.result(future, 3.seconds)` nested inside `forkchoiceUpdated`'s `IO { }` body (`IO.delay`) — blocked CE3 compute thread up to 3s per CL invocation.

**Fix applied**:
- `IO { }` → `IO.defer { }` (defers evaluation; body must return `IO[A]`)
- `Await.result(ask(...), 3.seconds)` → `IO.fromFuture(IO(pendingTransactionsManager.ask[...]))` — no thread blocked
- All early-exit branches wrapped in `IO.pure(...)` to satisfy `IO.defer` contract
- `handleErrorWith` on the ask handles timeouts/failures (same empty-tx fallback as before)
- `import scala.concurrent.Await` removed (now unused)

**Verify**: 16/16 `EngineApiSpec` tests pass, `sbt compile-all` clean.

---

### ~~8d-B1~~ ✅ JsonRpcBaseController EC.global → actorSystem.dispatcher — DONE (`276c77735`)

**File**: `jsonrpc/JsonRpcBaseController.scala:41`

**Implementation** (10 files):

| File | Change |
|------|--------|
| `JsonRpcBaseController.scala` | `implicit def executionContext` → `abstract` |
| `JsonRpcController.scala` | Add `actorSystem: ActorSystem` field; `override implicit def executionContext = actorSystem.dispatcher` |
| `NodeBuilder.scala` | `JSONRpcControllerBuilder` self-type adds `ActorSystemBuilder`; passes `classicSystem` |
| `FaucetJsonRpcController.scala` | Add `actorSystem: ActorSystem` param; same override |
| `FaucetBuilder.scala` | `FaucetJsonRpcControllerBuilder` self-type adds `ActorSystemBuilder`; passes `system` |
| `JsonRpcControllerFixture.scala`, `FukuiiJRCSpec.scala`, `QaJRCSpec.scala` | Pass `system`/`testSystem` as last arg |
| `JsonRpcHttpServerSpec.scala`, `GraphQLHttpRouteSpec.scala` | Test-only mock stubs override with `EC.global` (mock framework intercepts all calls — no production path) |

**Verify**: `sbt compile-all` → 0 errors in jsonrpc files. `testOnly *JsonRpc*` blocked by pre-existing compile error in `NetworkPeerManagerActor.scala` (in-progress LOOM migration, unrelated).

---

### 8e — ScalaFix Ruleset Expansion + `noReturns` Ratchet Lock

**Work done (2026-06-18):**
- `DisableSyntax.noReturns = true` — **already in `.scalafix.conf`** (pre-done).
- `NoAutoTupling` — **already in `.scalafix.conf`** (pre-done).
- ~~C2 chore removes `return` from ~52 non-actor non-consensus sites~~ — **✅ DONE `9eb1f4e06`** (guard clauses, while-loop early exits, try-block returns, match-arm returns, complex multi-return methods; 19 files; 0 compile errors).
- ~~TNHC 4 (actual 11) returns~~ — **✅ DONE `7a48c5988`** (LOOM Phase 0, S3 TNHC thread).

**Remaining to lock the ratchet** (`sbt scalafixAll` not yet green): 40 deferred sites.

| Deferred category | File(s) | Count | Gate |
|-------------------|---------|-------|------|
| Classic actor — Wave 3 LOOM sprint | `sync/snap/SNAPSyncController.scala` | 33 | Wave 3 network/sync migration (SNAP1) |
| Consensus-critical — FORGE review | `vm/VM.scala`, `vm/OpCode.scala`, `vm/PrecompiledContracts.scala`, `ledger/BlockPreparator.scala`, `mpt/StackTrie.scala`, `consensus/validators/std/StdSignedTransactionValidator.scala` | 6 | FORGE sign-off per file |
| Consensus-path (ETH Engine API) — BEACON review | `consensus/engine/EngineApiController.scala:96` (`handleNewPayload`, malformed-payload decode `Left` branch), `consensus/engine/EngineApiController.scala:226` (`handleForkchoiceUpdated`, malformed-params decode `Left` branch) | 2 | BEACON sign-off (S3-D) |

**Full ratchet lock checklist:**
1. ~~C2 chore clears ~52 sites~~ ✅ DONE `9eb1f4e06`
2. ~~LOOM Phase 0 for TNHC clears 11 sites~~ ✅ DONE `7a48c5988`
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

### 8f — Dead Code Audit (Broader than extvm)

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
- ~~`FastSyncBranchResolverActor`~~ ✅ WIRED `ea60c4f29` — `FastSync.scala` `handleBlockHeaders` `ParentChainWeightNotFound` case now spawns the actor (binary search for true common ancestor) and transitions to `waitingForBranchResolution()`; `BranchResolvedSuccessful` resets cursors/queues; `BranchResolutionFailed` falls back to N-block rewind. 15/15 tests pass. testEssential 3,600/0 ✅.
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

**Current state**: 56 occurrences of `Thread.sleep` / `@Ignore` in test files.

```bash
grep -rn "Thread\.sleep\|@Ignore\b\|ignore\b" src/test/ --include="*.scala" | wc -l  # 56
```

**Why it matters**: `Thread.sleep` in tests is a primary cause of flakiness under load (CI
slower than dev machine → timeouts). `@Ignore` annotations silently hide untested behavior.

**Approach**: Part of R2 (test quality audit). Map all 56 occurrences:
- `Thread.sleep` → replace with `eventually(...)` from ScalaTest or Pekko's `TestProbe.expectMsg(duration)`
- `@Ignore` → determine if the test is permanently dead (delete) or blocked on missing infra (document why)

**Gate**: R2 audit ✅ COMPLETE (`test-quality-audit.md`, 303 lines). All 7 confirmed Tier B — see `thread-sleep-audit.md §B1–B5` for recipes.
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
| ~~**7a — ADT consolidation**~~ ✅ | ~~Seal Command traits: move Messages.scala cases into companion objects~~ DONE — `04615ad43` `4e8b42263`; 173/173; 3,600/0 | MITHRIL, WRAITH | ✅ |
| ~~**7b — EventStream**~~ ✅ | ~~R5 research → Topic[T] migration for eventStream pub/sub sites~~ DONE — `849c0dcf0` (NewPendingTransaction) + `b35b35cf6` (NewBlockImported); `EventTopicsBuilder` trait; 13 files; grep eventStream → 0 | HERALD, LOOM | ✅ |
| ~~**7e — Design review**~~ ✅ | ~~Typed API optimization~~ DONE — 3 accepted redesigns (P4 SSC idle catch-all, P2 HealingState extraction, P3 CD stagnation push); 5 no-change verdicts | PRISM, HERALD, LOOM | ✅ |
| **7c — Supervision** | Explicit Behaviors.supervise per actor with typed failure strategies | PRISM, LOOM | 7a done |
| **8b — Opaque types** | Domain value type safety: BlockNumber, Hash, Address, Balance | MITHRIL, FORGE | Part 3a done |
| **8i — RLP derivation** | Replace handwritten product-type RLP codecs with derivation | MITHRIL, FORGE, EYE | Part 3a + R7 done |
| **Scala 3 idioms** | Part 3a implicit→given (198 files) | MITHRIL + scalafix | After Pekko migration |
| ~~**Scala 3 idioms**~~ | Part 3b COMPLETE `c0a3612b4` — non-consensus; consensus deferred (forge gate) | MITHRIL | ✅ |
| **Scala 3 idioms** | Part 3c isInstanceOf audit | MITHRIL | Any sprint |
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
| **8f — Dead code audit** | Broader sweep beyond extvm; delete confirmed dead files | PRISM | ~2h research |
| **3d — enum polish** | Migrate `SyncPhase`, `BlacklistReason`, `ForkId` codes to enum | MITHRIL | ~1h per file |
| **3e — console→logging** | Replace 24 `println`/`System.out` calls with SLF4J | MITHRIL | ~1h |
| **8e — ScalaFix expansion** | Rules in .scalafix.conf ✅; C2 ✅ `9eb1f4e06`; TNHC ✅ `7a48c5988`; remaining: 7 consensus (FORGE) + 33 SSC (SNAP1) | FORGE / LOOM | gated |
| **8g — braceless config** | Add scalafmt braceless-prefer config (no mass rewrite) | MITHRIL | ~15 min |
| **8j — Thread.sleep** | Replace test timing sensitivity (56 occurrences) | EYE | ~2h |
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
| ~~**Scala 3 idioms**~~ | Part 3b COMPLETE `c0a3612b4` — non-consensus done; consensus deferred (forge gate) | MITHRIL | ✅ |
| **Scala 3 idioms** | Part 3c isInstanceOf audit | MITHRIL | Any sprint |
| **Dep upgrades** | Part 4a JLine 4.x | — | Dedicated sprint |
| **Dep upgrades** | Part 4e Jackson 3 → then 4b logstash | — | Ecosystem gate |
| **Network/sync Pekko** | S3→S4/S7→NET2→SNAP1→SNAP2→ROOT→CAPSTONE (see SPRINT-QUEUE.md) | LOOM, FORGE, HERALD | Active sprint |
| **→ CAPSTONE** | Root flip: ActorSystem[Nothing], bridge/adapter removal, Behavior[Any] narrowing | LOOM | All actors Typed |
| **7d — Artifact audit** | Post-CAPSTONE sweep: any surviving Classic patterns, adapter imports, raw schedulers | PRISM, HERALD | CAPSTONE merged |
| ~~**7a — ADT consolidation**~~ ✅ | ~~Seal Command traits: move Messages.scala cases into companion objects~~ DONE — `04615ad43` `4e8b42263`; 173/173; 3,600/0 | MITHRIL, WRAITH | ✅ |
| ~~**7b — EventStream**~~ ✅ | ~~R5 research → Topic[T] migration for eventStream pub/sub sites~~ DONE — `849c0dcf0` (NewPendingTransaction) + `b35b35cf6` (NewBlockImported); `EventTopicsBuilder` trait; 13 files; grep eventStream → 0 | HERALD, LOOM | ✅ |
| ~~**7e — Design review**~~ ✅ | ~~Typed API optimization~~ DONE — 3 accepted redesigns (P4 SSC idle catch-all, P2 HealingState extraction, P3 CD stagnation push); 5 no-change verdicts | PRISM, HERALD, LOOM | ✅ |
| **7c — Supervision** | Explicit Behaviors.supervise per actor with typed failure strategies | PRISM, LOOM | 7a done |
| **Scala 3.9** | Part 5a | — | 3.9 release gate |
| **Constitution** | Part 5c | — | After 5a |
| **extvm deletion** | Part 6a | WRAITH | Standalone sprint (grep verify first) |

---

## Part 9 — ETH Test Coverage (BEACON sprint)

Source: `eth-coverage-audit.md` (2026-06-20). Pre-existing gaps, not sprint-introduced.
Sprint verdict: adequate for modernization scope. Follow-up session when ready.

---

### G1 — PostMergeBlockHeaderValidator: zero unit test coverage (HIGHEST VALUE)

**File:** `src/main/scala/.../consensus/validators/PostMergeBlockHeaderValidator.scala`
**Gap:** Touched by scala3-cleanup-june (syntax-only changes). Zero test coverage at any
tier (testEssential / testStandard / testComprehensive).
**Fix:** Write a unit spec covering: valid post-merge header passes, parentHash mismatch
fails, mixHash non-zero fails, nonce non-zero fails, difficulty non-zero fails, withdrawals
root mismatch fails, excess blob gas validation (EIP-4844). Wiring already exists
(BlockHeaderValidatorSpec pattern).
**Agent:** BEACON
**Gate:** None — standalone test addition, no production code changes.
**Status:** DONE — `583aded58` (8 cases, all passing via `testOnly *PostMergeBlockHeader*`).

---

### ~~G2 — No ethereum/tests ETH vectors below testComprehensive (STRUCTURAL)~~ ✅ DONE `dbef878`

**What was done:** `EthSmokeSpec.scala` (44 lines) — 5 vectors (4 Berlin, 1 Istanbul). `EthSmoke` tag added to `Tags.scala`. `testEthSmoke` build alias added to `build.sbt` scoped to `IntegrationTest / EthSmoke`. All 5 vectors pass in 21s.

**Implementation notes (preserved for future extensions):**
- **Fork coverage gap** — no London/Cancun/EIP-4844 vectors exist locally (only Berlin + Istanbul in `src/it/resources/`; post-merge paths point at the CI-only `ets/tests` submodule). Spec follows guardrail: real verified vectors only. Extend with post-merge vectors once submodule is populated. → **researched in G2-R: see below**.
- **Tag conflict trap** — tagging with both `(IntegrationTest, EthSmoke)` causes zero tests to run: `commonSettings` injects `-l IntegrationTest` as a global exclusion. Fix: tag with `EthSmoke` only — tests are already in `IntegrationTest` config by directory.

---

### G2-R — Post-Merge EthSmoke Vector Candidates ✅ IMPLEMENTED `00166a555`

**Doc:** `.local/docs/moderization-review-june/eth-smoke-vector-candidates.md`
**Status:** Implemented. 5 JSON files committed under `src/it/resources/ethereum-tests/` (flat layout). 10 new vectors wired in `EthSmokeSpec` but `ignore`d pending G5. CI: 5 pass / 0 fail / 10 ignored / 18.7s.

**Corpus finding:** The `ethereum/tests` checkout regenerates vectors with only `Cancun` and `Prague` as `network` field values — pre-Cancun labels (`London`, `Paris`, `Shanghai`) exist in filenames but decode to `['Cancun', 'Prague']` internally. Feature-equivalent vectors per fork are available:

| Target Fork | Feature | Candidate File |
|-------------|---------|----------------|
| London | EIP-1559 basefee | `basefeeExample.json` |
| Merge/Paris | EIP-3675 | `mergeExample.json` |
| Shanghai | EIP-4895 withdrawals | `shanghaiExample.json` |
| Cancun | EIP-1153 tload | `tloadDoesNotPersistCrossTxn.json` |
| Cancun | EIP-4844 blobs | `blockWithAllTransactionTypes.json` |

Each file yields 2 vectors (`_Cancun` + `_Prague`) → 5 files = 10 new vectors. Access pattern: copy JSONs into `src/it/resources/ethereum-tests/`; use existing `smoke()` / classpath `runSingleTest` pattern (not `runTestFile` with `.claude/` path — breaks in CI).

**Critical caveat — `EthereumTestsAdapter.scala` is pre-merge-shaped:**
`TestBlockHeader` drops `baseFeePerGas`, `withdrawalsRoot`, `excessBlobGas`, `blobGasUsed`, `parentBeaconBlockRoot`. `TestBlock` ignores the `withdrawals` array. Circe silently discards unknown fields so files decode and run as smoke, but they are **not byte-exact header/withdrawal conformance** — executor checks balance/nonce/storage only, not state root. Vectors run shallow until the decoder is extended.

**Gate:** None — copy files + add `smoke()` calls. Extend `EthereumTestsAdapter` for byte-exact conformance as a separate follow-up.
**Skip:** Hive's 67 JSONs are GraphQL/RPC/genesis fixtures — not `BlockchainTest` format.

---

### G3 — Missing `osaka` case in TestConverter.networkToConfig() (TEST HARNESS)

**File:** `src/it/scala/.../ethtest/TestConverter.scala` — `networkToConfig()` method
**Gap:** Osaka (Sepolia's currently-active fork) has no case in the network→config mapping.
Any ethereum/tests vector tagged `Osaka` is silently unmappable; execution harness falls
through to default (pre-osaka config), producing wrong results without failing loudly.
**Production impact:** None — production fork dispatch (`EvmConfig.forTimestamp`) is intact.
Test harness only.
**Fix:** Add `case "Osaka" | "Prague" => sepoliaConfig.copy(...)` with the correct Osaka
fork block/timestamp. Verify against `OsakaOpCodes` activation in ETH genesis config.
**Agent:** BEACON
**Gate:** None — standalone test-harness fix.
**Status:** DONE — `ef5ad3376` (osaka case added to both fork-block and forkTimestamps match blocks; `IntegrationTest / compile` clean).

---

### ~~G4 — EthereumTestsSpec.runSingleTest() dead code (PARSE-ONLY TODO)~~ ✅ DONE `f4746250e`

**File:** `src/it/scala/.../ethtest/EthereumTestsSpec.scala`
**What changed:** `runSingleTest(resourcePath, testName)` — loads suite via `loadTestSuite`, looks up test by exact key, calls `executeTest()` on hit, returns `Left` with available names on miss. `runTestFile(filePath)` — reads from real filesystem path, decodes via circe `BlockchainTestSuite` decoder, maps each case through `executeTest()`. Both route through `EthereumTestExecutor.executeTest → EthereumTestHelper → BlockExecution.executeAndValidateBlock()`, using chainId=1 / forTimestamp / Berlin→Prague+Osaka — same path as batch subclasses. Signature change: original stub was `runSingleTest(testName, test: BlockchainTest)` (pre-supplied object); changed to `runSingleTest(resourcePath, testName)` to do actual suite lookup as the spec intended.
**Compile:** `sbt compile-all` → 0 errors. 54 insertions, 30 deletions.

---

### ~~G5 — EthereumTestsAdapter post-merge extension~~ ✅ DONE `12a79b7c3`

**Result:** 15/15 pass, 0 ignored. `sbt compile-all` → 0 errors.

**4 defects fixed:**
1. **`gasPrice` optional** — changed from required `String` to `Option[String]`; missing `gasPrice` on type-0x02/0x03 defaults to `BigInt(0)`. Unblocked 8 vectors.
2. **Genesis header fields** — added `withdrawalsRoot`, `baseFeePerGas`, `blobGasUsed`, `excessBlobGas`, `parentBeaconBlockRoot`, `requestsHash` to `TestBlockHeader`. Reconstructed genesis now selects correct `HeaderExtraFields` variant (`HefPostPrague` / `HefPostCancun` / `HefPostShanghai` / `HefPostOlympia` / `HefEmpty`); RLP item count matches; genesis hash aligns with `block[0].parentHash`. Unblocked shanghai vector.
3. **ETC Olympia EIP-2935 path fired on ETH vectors** — `networkToConfig` was defaulting to `NetworkType.ETC`, triggering the block-number EIP-2935 guard on ETH timestamp-fork vectors. Fix: set `NetworkType.ETH` for ETH test chains.
4. **EIP-4895 withdrawals dropped** — `TestBlock` ignored the `withdrawals` array; state-root mismatch on shanghai vector with non-empty withdrawals. Fix: added `TestWithdrawal` decoder, threaded withdrawals into `BlockBody`.

~~**Latent issue — FORGE assessed, fix required before Olympia activation:**~~

~~`BlockExecution.applyEip2935`~~ ✅ `bbc5f1df8` — account-existence gap **FIXED** (2026-06-21). 2 files, `scala3-cleanup-june`.

**What was done**: Removed `isActivationBlock` variable entirely (was only used in the `w1` branch condition; `blockchainReader.getBlockHeaderByHash` call removed with it). Changed `w1` condition from `if isActivationBlock && world.getCode(HistoryStorageAddress).isEmpty` to `if world.getCode(HistoryStorageAddress).isEmpty` — code-absence is now the sole deployment guard, matching `applyEip4788`. Self-heals any absent-account scenario on post-activation blocks. New regression test in `BlockHashHistorySpec`: runs `olympiaBlock + 1` on `emptyWorld` (no pre-seeded account), asserts code deployed + slot written — threw `IllegalStateException` at `getGuaranteedAccount` before fix, passes now. `sbt compile-all` → 0 errors; `scalafmtAll` clean; `testOnly *BlockHash* *BlockExecution* *Eip2935*` → 21/21; `BlockHashHistorySpec` → 6/6 (5 pre-existing + 1 new).

---

### Execution order

~~G3 → G1 → G4 → G2 → G2-R → G5~~ ✅ **G-SERIES COMPLETE.** 15/15 EthSmoke vectors passing.
All four are BEACON-only; no FORGE involvement unless ETC fork config is touched.

---

## PRISM Post-Capstone Artifact Audit

**Report:** `.local/docs/moderization-review-june/post-capstone-artifact-audit.md`
**Date:** 2026-06-21
**Summary:** 4 resolved, 38 intentional, 5 fix-now

### Resolved (4)

Pre-existing findings that are already addressed on `scala3-cleanup-june`. No action needed.

### Intentional (38)

Pervasive `toClassic` / `toTyped` bridges and `Behavior[Any]` occurrences throughout the codebase. PRISM confirmed all 38 are load-bearing interop at the `PeerEventBus` Classic subscriber boundary — not misuse. No regressions. Do not touch without a full PeerEventBus Typed migration (LOOM).

### Fix-Now (5) — routed to C13 / C14

| File | Line | Issue | Fix |
|------|------|-------|-----|
| `jsonrpc/AdminService.scala` | 10 | `o.a.p.actor.Actor` imported for `Actor.noSender` | → `ActorRef.noSender` |
| `jsonrpc/AkkaTaskOps.scala` | 3 | Same `Actor.noSender` misuse | → `ActorRef.noSender` |
| `nodebuilder/StdNode.scala` | 3 | Same `Actor.noSender` misuse | → `ActorRef.noSender` |
| `blockchain/sync/SyncController.scala` | 1507 | Classic `scheduler.scheduleOnce` inside Typed actor | → `ctx.scheduleOnce` or `Behaviors.withTimers` |
| `blockchain/sync/SyncController.scala` | 2140 | Same Classic scheduler (30-min delay) | → same fix |

All PRISM-gate — no migration work required. See CHORE-QUEUE C13/C14.

---

## ~~Regular Sync LCA Recovery — handleForkRecovery Precise Rollback~~ ✅ DONE (`0d290019e`)

**Audit:** HERALD (2026-06-21). **Source:** `post-capstone-artifact-audit.md` chain / CHASE-QUEUE cleared log.

### Background

Regular sync has three divergence-recovery paths (escalating):

| Path | Trigger | Action |
|------|---------|--------|
| A — stale-tip rewind | `HeaderRejectionRewindThreshold` = 3 consecutive rejections from distinct peers | `invalidateBlocksFrom(lastBlock - 128)` — blind 128-block rewind |
| B — UnknownBranch rewind | `BranchResolution.resolveBranch` returns `UnknownBranch` (parent not in canonical chain) | `InvalidateBlocksFrom(currentBlock - branchResolutionRequestSize)` — blind rewind |
| C — handleForkRecovery | `ForkDetectThreshold` = 5 consecutive `UnknownParent \| BlockImportFailed` on same block hash | `setCanonicalChainHead(currentBest - 128, ...)` — destructive 128-block canonical-chain rollback, iterates |

### Gap

Paths A and B use a blind fixed-size rewind. Path C (the most aggressive, `BlockImporter.scala:797`) iterates: it rolls back the canonical chain 128 blocks per `ForkDetectThreshold` cycle, permanently mutating chain index entries (and serving wrong data to peers) during recovery. On a fork deeper than 128 blocks it cycles repeatedly — each iteration takes minutes (5 strikes × peer response timeouts). It converges but has no concept of the LCA.

**ETC mainnet impact:** Low — MESS makes forks deeper than 128 blocks near-impossible. More relevant on Mordor.

### Fix

`FastSyncBranchResolverActor` already implements the full LCA search (recent-header scan + binary search fallback). One parameter generalization makes it reusable here:

1. **Generalize constructor**: `fastSync: ClassicActorRef` → `replyTo: ActorRef[BranchResolverResponse]` (one-line change)
2. **Add `ResolvingFork` behavior** to `BlockImporterLogic`: suspends import dispatch; waits for `BranchResolvedSuccessful(lca, _)` or `BranchResolutionFailed`
3. **Replace blind rollback** in `handleForkRecovery` (`BlockImporter.scala:797`): spawn branch resolver → on `BranchResolvedSuccessful` call `setCanonicalChainHead(lca, ...)` once, precisely, then `InvalidateBlocksFrom(lca + 1)`; on `BranchResolutionFailed` fall back to current 128-block blind rewind

No consensus code touched. No new network protocol. Binary search reuses existing `GetBlockHeaders` path.

**Dependency:** FastSyncBranchResolverActor wiring in `FastSync.scala` — ✅ `ea60c4f29`.
**Size:** S. **Gate:** None. **Agent:** LOOM (new behavior state) + HERALD pre-flight.

**Implementation:** `handleForkRecovery` (`BlockImporter.scala:797`) spawns `FastSyncBranchResolverActor` with a typed `replyTo` adapter; enters new `resolvingFork` suspended state. `BranchResolvedSuccessful(lca, _)` → `setCanonicalChainHead(lca)` + `InvalidateBlocksFrom(lca + 1)` (precise rollback). `BranchResolutionFailed` → original 128-block blind rewind (safe degradation). 46/46 targeted tests pass.
