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

#### §3d residual — SyncProtocol.SyncStatus candidate

One remaining candidate not yet assessed:
```bash
grep -rn "SyncProtocol\.SyncStatus\|sealed.*SyncStatus\|case object.*SyncStatus" \
  src/main/ --include="*.scala"
```
If all subtypes are pure `case object` (no fields, no methods, no constructor params): migrate to `enum` in the same commit pattern as `SyncPhase` (`adf4e69ea`). If any subtype has fields → reject (add ❌ REJECTED note here). This is a 5-minute check + 15-minute migration if confirmed. Handle opportunistically when already in `sync/` files.

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

### 3h — `Any` in Type Signatures (type erasure cleanup)

**Added:** 2026-06-22 (birdseye-review G8 scope)
**Agent:** MITHRIL (non-consensus sites); FORGE-gated (vm/, consensus/, domain/)
**Risk:** LOW–MEDIUM (no consensus logic in most sites; vm/ sites need forge sign-off)

**Counts (pre-scan 2026-06-22):**
| Pattern | Count | Primary subsystems |
|---------|-------|-------------------|
| `: Any` in type signatures | ~~20~~ → 1 ungated remaining | 15 documented `// Any:`, 4 FORGE-gated (domain×2, vm×2), 1 per-scan GraphQLSchema (has `// cast:` comment) |
| `[Any]` generic parameter | ~~22~~ → 0 ungated remaining | all documented `// Any:` or FORGE-gated |
| `=> Any` return type | ~~2~~ → 0 ungated remaining | both documented `// Any:` |
| `Behavior[Any]` (Pekko) | ~~12~~ **0** | ✅ DONE 2026-06-22 — all 12 actors narrowed to Behavior[Command] |

**Note:** `Behavior[Any]` sites are Wave 3 scope (LOOM). Wave 3 is complete as of 2026-06-22 — all
12 actors narrowed to `Behavior[Command]`, stale Scaladoc comments updated. The remaining ~44
non-Pekko sites (`: Any` in type signatures, `[Any]` generics, `=> Any` returns) are the target of this item.

**Remediation pattern:**
- `def process(msg: Any)` → `def process(msg: Command)` (sealed ADT)
- `Map[String, Any]` → case class or `io.circe.Json` (circe already in codebase)
- `List[Any]` (mixed types) → sealed ADT with `List[A | B]`
- `def result: Any` → sealed trait / enum / generic `[T]`
- EXCEPTION: Intentional reflection sites → `// Any: reflection — no typed alternative` comment

**Gate:** G8-findings must categorize all sites. Wave 3 (Behavior[Any] removal) is DONE.
Remaining sites ~44. Add `DisableSyntax.noAny` to scalafix.conf after cleanup
(check R6-findings for rule availability).

**Scope prompt:** `birdseye-review/01-gap-analysis/G8-any-type-scope.md`

**✅ DONE 2026-06-22** — MITHRIL pass complete. 15 sites documented `// Any:`, 7 FORGE-gated (markers added, logged in CHASE-QUEUE). 0 type changes (all remaining uses are intentional: Pekko messageAdapter, Micrometer gauge, Java interop, or FORGE-gated). See G8 scope doc for post-fix baseline.


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

**What §8a unlocks (beyond the obvious):**

1. **777 Classic `TestProbe` without `[T]`** — `org.apache.pekko.testkit.TestProbe` has no type
   parameter. The 777-site grep metric (`TestProbe\b` without `[`) cannot be driven to zero without
   §8a. Once a test file is migrated to `ActorTestKit`, use
   `org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[T]` which accepts type params.

2. **20 `fishForMessage` E165 sites** (11 files: `StateSyncSpec`, `ChainWeightCalibrationSpec`,
   `CalibratePivotTDSpec`, `RegularSyncFixtures`, `PeerManagerSpec`, and 6 scoped-verification
   SNAP specs) — Classic `TestProbe.fishForMessage` takes `PartialFunction[Any, Boolean]`, making
   every `case Foo(...) =>` inside it an E165 selector on `Any`. These are part of the intentional
   333 E165 floor and cannot be fixed without §8a migration. After migration, replace
   `fishForMessage { case T => true }` with `expectMessageType[T]` on a Typed probe.

**Research prompt (before starting remaining §8a-retro batches):**
> Read `.claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md` §8a retro batch notes (lines ~525–553)
> for established patterns (PatienceConfig ambiguity, top-level-actor guardian, `system.toClassic`
> for HTTP/pekko-http). Then list the test files for the next migration wave:
> ```bash
> grep -rn "org.apache.pekko.testkit.TestKit" src/test/ --include="*.scala" -l | grep -v "ActorTestKit"
> ```
> For each file, identify: (a) which production actor it tests (must already be Typed), (b) any
> `fishForMessage` calls to replace with `expectMessageType`, (c) any `system.toClassic` needs
> (Pekko HTTP, Classic eventStream). Produce a migration plan per file before touching any code.

---

**8a-retro batch 3 — network/sync (G1-narrowed) ✅ DONE** (`12c23cf8a` + `a719520db`). 25 specs migrated.

| Commit | Files | Notes |
|--------|-------|-------|
| `12c23cf8a` | ByteCode/AccountRange/StorageRange/TrieNodeHealingWorkerSpec, StorageRecoveryActorSpec, BlockBroadcastSpec, SyncStateDownloaderStateSpec, CombinedRecoveryScanActorSpec, BlockFetcherStateSpec, SyncProgressMonitorSpec, ServerActorSpec, PeerEventBusActorSpec, NetworkPeerManagerActorHandshakeSpec, IORuntimeInitializationSpec | 14 specs, part 1 |
| `a719520db` | StateSyncSpec, StateNodeFetcherSpec, PivotHeaderBootstrapSpec, PivotBlockSelectorSpec, BytecodeRecoveryActorSpec, FastSyncSpec, FastSyncBranchResolverActorSpec, ChainDownloaderSpec, SNAPRequestTrackerSpec, SNAPFakePeerSpec, PeerManagerSpec; also NetworkPeerManagerFake | 11 specs + NPMAFake `GetHandshakedPeers`→`GetHandshakedPeersCmd(replyTo)` fix |

**New pitfalls discovered in batch 3 (added to established patterns table above):**

| Issue | Root cause | Fix |
|-------|-----------|-----|
| `system.stop(ref)` on kit-spawned actor | classic `StopChild` sent to Typed guardian → `ClassCastException` → system shutdown | `testKit.stop(typedRef)` |
| Missing named dispatchers | default kit config lacks `sync-dispatcher`, `account-trie-dispatcher`, etc. | `ScalaTestWithActorTestKit(ConfigFactory.load())` |
| `must.Matchers` conflicts with kit's `should.Matchers` | E164 on override | Drop `must.Matchers` mixin; use `should.*` throughout |
| `awaitCond(cond, max, interval, msg)` gone | Classic TestKit method, absent from Typed kit | `eventually(timeout(X), interval(Y)) { assert(cond, msg) }` with `Eventually` + `SpanSugar.*` |
| `adapter.*` needed for probe-as-typed-param | `TestProbe().ref` passed as typed param; adapter provides implicit conversion | Retain `import org.apache.pekko.actor.typed.scaladsl.adapter.*` in affected files |

**Side-find:** `GetHandshakedPeers` → `GetHandshakedPeersCmd(replyTo)` — pre-existing failures in `PivotBlockSelectorSpec` and `FastSyncBranchResolverActorSpec` uncovered during migration (production side already updated; test AutoPilots lagged behind).

**Remaining (blocked — see batches 4 and 5 below):**
- 14 coordinator/heal specs: `PropsAdapter` child-stop incompatible with Typed `ActorTestKitGuardian` (§8a-retro batch 4)
- 5 multi-system/`TestActorRef` specs: non-standard lifecycle or Classic-only testing APIs (§8a-retro batch 5)
- `WithActorSystemShutDown.scala`: still referenced by `PeerActorSpec` + `RLPxConnectionHandlerSpec`; delete after batch 5

---

#### §8a-infra-c — MITHRIL: replace classic `actorSelection` worker-ref pattern with Typed TestProbe injection in ByteCodeCoordinatorSpec + AccountRangeCoordinatorSpec

**Agent:** MITHRIL (Scala 3 / Typed modernization)
**Risk:** LOW — test files only; no production code changed
**Prerequisite:** §8a-retro batch 4 complete (`5eae34c21`)
**Gate:** Run any time after E5d.

**Background (E5c audit finding, 2026-06-23):**
Two coordinator specs obtain worker refs via `classicSystem.actorSelection(coordinator.path / "*").resolveOne(3.seconds)`, returning classic `org.apache.pekko.actor.ActorRef`. These refs are then used to:
- Send typed worker commands via classic `!` (e.g. `workerRef ! AccountRangeCoordinator.WorkerPeerDisconnected(...)`)
- Simulate worker death via `classicSystem.stop(workerRef)` (test scenario — intentional, not teardown)

The pattern works (the Typed workers are visible in the classic hierarchy), but it ties the test to `classicSystem.actorSelection` and an untyped ref. A cleaner approach:
- Replace `resolveWorkerChild` with a `TestProbe[W]` injected as the worker factory (if the coordinator accepts a worker-factory override), OR
- Convert to `toClassic`/`toTyped` ref bridging after spawn where both specs can hold a `ActorRef[Worker.Command]` directly

This is cosmetic test-quality work; the existing pattern is correct and not a source of leaks or flakiness.

**Files:**
- `ByteCodeCoordinatorSpec.scala` — `resolveWorkerChild` at line 71; `classicSystem.stop(workerRef)` at lines 743, 779
- `AccountRangeCoordinatorSpec.scala` — `resolveWorkerChild` at line 70; classic `!` sends at lines 365, 544, 591

**Step 1 — Read each coordinator's worker spawn API:**
Check `ByteCodeCoordinator` and `AccountRangeCoordinator` for whether a worker-factory override (`workerFactory: (context, ...) => ActorRef[Worker.Command]`) can be injected without modifying production behavior. If the factory is `private`, the injection approach requires a minimal production change (adding a `protected` hook); assess whether that's acceptable.

**Step 2 — If injection is viable:** Replace `resolveWorkerChild` with an injected `TestProbe[Worker.Command]` factory. The test controls the ref from spawn time, eliminating `actorSelection` entirely.

**Step 3 — If injection is not viable:** Document why and convert the `actorSelection` result to a Typed ref via `.toTyped[Worker.Command]` (after confirming the worker's `Command` supertype) so at least the send-side is typed.

**Verification:**
```bash
sbt compile-all
sbt "testOnly *ByteCodeCoordinatorSpec* *AccountRangeCoordinatorSpec*"
./local/scripts/fukuii-test
```

**Rejection criteria:**
- Changing production actor behavior or `private` visibility
- Introducing a worker-factory parameter that changes the non-test code path
- Any change to `src/main/` beyond a minimal `protected` hook if injection is chosen

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
| Classic actor — Wave 3 LOOM sprint | `sync/snap/SNAPSyncController.scala` | 36 | Wave 3 network/sync migration (SNAP1) |
| Consensus-critical — FORGE review | `vm/VM.scala`, `vm/OpCode.scala`, `vm/PrecompiledContracts.scala`, `ledger/BlockPreparator.scala`, `mpt/StackTrie.scala`, `consensus/validators/std/StdSignedTransactionValidator.scala` | 6 | FORGE sign-off per file |
| Consensus-path (ETH Engine API) — BEACON review | `consensus/engine/EngineApiController.scala:96` (`handleNewPayload`, malformed-payload decode `Left` branch), `consensus/engine/EngineApiController.scala:226` (`handleForkchoiceUpdated`, malformed-params decode `Left` branch) | 2 | BEACON sign-off (S3-D) |

**Full ratchet lock checklist:**
1. C2 chore clears ~52 sites ✅ DONE `9eb1f4e06`
2. LOOM Phase 0 for TNHC clears 11 sites ✅ DONE `7a48c5988`
3. FORGE reviews and clears 6 consensus sites (1 cleared: consensus/engine/JwtAuthenticator.scala — S3-C) ← add to relevant FORGE sessions
4. BEACON reviews and clears 2 ETH Engine API sites — `EngineApiController.scala:96` + `:226` (S3-D). Both are early-`return IO.pure(...)` decode-error guards inside large consensus-path method bodies; removing the `return` requires wrapping ~90 lines of post-decode body into the `Right`/`else` branch. Deferred from S3-A/S3-D/S3-F commit (2026-06-22): the byte-for-byte response behavior must be preserved across the re-indent; gated on a focused BEACON pass, not bundled with the low-risk Option/val changes.
5. Wave 3 SNAP1 migration sprint clears SNAPSyncController 36 sites ← gated on NET2
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

### 8k — Classic Interop Elimination: `.toClassic` / `actorSelection` / Classic Bridge Audit

**What**: Scala's type system is Typed's main advantage. The codebase currently has ~180 `.toClassic` / `.toTyped` bridge calls, 10 `actorSelection` sites (test-only), and 6 `classicSystem.actorOf` sites in production. These are necessary bridges while classic actors remain in the system, but every bridge site is a hole in the type lattice — an untyped `ActorRef` flowing where a `ActorRef[T]` could carry compile-time guarantees. The goal is to inventory all sites, understand WHY each exists (which classic actor is the root cause), and eliminate them in priority order as classic actors complete their LOOM migrations.

**Root-cause breakdown (§8k-R1 audit COMPLETE 2026-06-23 — full detail at `.local/docs/classic-interop-audit.md`):**

~130 production bridge sites + 2 test `actorSelection` sites. Permanent floor: 4 TCP bridges. Eliminatable: ~126 production + 2 test.

| Cluster | Sites | Root cause | Pre/Post-CAPSTONE | Sprint |
|---------|-------|-----------|-------------------|--------|
| A — `messageAdapter.toClassic` (PeerEventBus subscriptions) | ~26 | `PeerEventBusActor.SubscribeCmd(subscriber: ActorRef)` | Pre-CAPSTONE | §8k-D ✅ DONE 93bcedb12 |
| B — `handshakedPeersAdapter.toClassic` | ~15 | `NPMA.GetHandshakedPeersCmd(replyTo: ActorRef)` | Pre-CAPSTONE | §8k-E |
| C — `ctx.toClassic.sender()` in SyncController/FastSync | ~27 | OQ-5 Classic ask path from jsonrpc callers | Pre-CAPSTONE | §8k-G |
| D — `ctx.toClassic.actorOf(RegularSync)` | 2 | RegularSync has no `Behavior[Command]` | Pre-CAPSTONE | §8k-F |
| E — `externalAdapter.toClassic` in SyncController | ~29 | OQ-5 Classic ask path (same root as C) | Pre-CAPSTONE | §8k-G |
| F — `ctx.self.toClassic` coordinator→worker + SSC→coordinator | ~15 | Worker `coordinator: ActorRef` params untyped | **NOW** (MITHRIL) | §8k-A + §8k-C |
| G — `context.toClassic.parent` in PeerActor | 7 | PeerActor notifies PeerManager via Classic parent | Pre-CAPSTONE | §8k-H |
| H — `ctx.spawn(...).toClassic` for PeerActor ref | 1 | PeerManagerActor stores spawned child as Classic | Pre-CAPSTONE | §8k-H |
| I — TCP I/O bridge (RLPxConnectionHandler, ServerActor) | 4 | Akka TCP requires Classic `sender()` — **permanent** | N/A | — |
| J — `classicSystem.actorOf` bridge actors in NodeBuilder | 3 | KNM/PDM/PTM have Classic callers via legacy case objects | Pre-CAPSTONE | §8k-I |
| K — `peerEventBus.toClassic` + spawn `.toClassic` in NodeBuilder | 3 | SyncController/NPMA returned as Classic refs to callers | Pre-CAPSTONE | §8k-G/§8k-I |
| L — `AkkaTaskOps.askFor` (jsonrpc, ~18 call sites) | ~18 | Commands carry `replyTo: ActorRef` not `ActorRef[T]` | Pre-CAPSTONE | §8k-G |
| M — `peerEventBus.toClassic` watchWith in PEBA itself | 1 | PEBA internal Classic watch | Pre-CAPSTONE | §8k-D ✅ DONE 93bcedb12 |
| N — `ctx.self.toClassic` / `fetcherReplyTo.toClassic` in BlockImporter | 4 | RegularSync spawned Classic → BlockImporter props take Classic refs | Pre-CAPSTONE | §8k-F |

**Principle**: Each `.toClassic` call is a symptom, not the disease. The disease is an unconverted classic actor upstream. The fix strategy is: **migrate the upstream actor first (LOOM), then delete the bridge**. Bridges must never be removed before the upstream is converted — that produces a type error at the call site that blocks compilation.

---

#### §8k-R1 — PRISM: Comprehensive classic-interop audit ✅ DONE 2026-06-23

**Agent:** PRISM (read-only review, 8-lens analysis)
**Risk:** ZERO — research only, no code changed
**Gate:** Any time. Run before starting §8k-A.
**Output:** `.local/docs/classic-interop-audit.md` (535 lines, 14 clusters, bridge census ~130 prod + 2 test).

**Research prompt:**
```
You are auditing the fukuii codebase for all sites where Pekko Typed actors
bridge to the Classic system. The goal is to inventory every bridge pattern,
identify the root-cause classic actor, and produce a prioritized elimination
roadmap so the type lattice can be fully closed.

Step 0 — Read the migration progress context FIRST (do not skip):

  # Understand the current migration state before searching
  # a. What has been completed (actors already Typed):
  ls .claude/agent-protocols/completed/

  # Read these if present (they define what is DONE):
  cat .claude/agent-protocols/completed/SPRINT-QUEUE.md          # committed waves
  cat .claude/agent-protocols/completed/DEFERRED-BACKLOG.md      # completed deferred items
  cat .claude/agent-protocols/completed/CODEBASE-AUDIT.md        # completed audit sweeps

  # b. What modernization work has been done per subsystem:
  ls .claude/agent-protocols/modernization-log/

  # Read the INDEX file and any sync/, network/, node/ subdirectory files
  # relevant to actor migration (these list what was changed and when)
  cat .claude/agent-protocols/modernization-log/INDEX.md
  # Then: cat .claude/agent-protocols/modernization-log/network/*.md
  #       cat .claude/agent-protocols/modernization-log/sync/*.md

  # c. What is still in flight (current working queue):
  cat .claude/agent-protocols/working-docs/SPRINT-QUEUE.md       # active sprint tasks
  cat .claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md   # §8k section (this prompt)
  grep "Wave 3\|CAPSTONE\|S3\|S4\|NET2\|SNAP1\|SNAP2\|ROOT" \
    .claude/agent-protocols/working-docs/SPRINT-QUEUE.md         # migration sequence

  Synthesise: which actors are Typed NOW, which are still Classic, and
  in what order do the remaining Classic actors migrate? This is the
  framework for the elimination roadmap.

Step 1 — Inventory every bridge site:

cd /media/dev/2tb/dev/fukuii

# A. Classic subscriptions / message-adapter bridges
grep -rn "\.toClassic\b" src/main/ --include="*.scala" | grep -v "//.*toClassic"

# B. Classic sender/parent access
grep -rn "ctx\.toClassic\|context\.toClassic" src/main/ --include="*.scala" | grep -v "//.*toClassic"

# C. Classic actor spawns from Typed contexts
grep -rn "classicSystem\.actorOf\|ctx\.toClassic\.actorOf\|context\.toClassic\.actorOf" src/main/ --include="*.scala"

# D. actorSelection (test code — separate catalog)
grep -rn "actorSelection" src/test/ --include="*.scala" | grep -v "//.*actorSelection"

# E. Worker coordinator param types
grep -rn "coordinator.*ActorRef\b\|ActorRef.*coordinator" src/main/ --include="*.scala" | grep -v "typed"

Step 2 — For each site in A/B/C/E, identify:
  - Which classic actor is the terminal sink (the one receiving the classic ref)?
  - Is that classic actor already in the Wave 3 LOOM queue (SPRINT-QUEUE.md)?
  - If so: which LOOM sprint removes the bridge?
  - If not: it's a new gap — record it.

Step 3 — Produce the elimination table:

| File:line | Pattern | Root-cause classic actor | Elimination sprint | Blocker? |
|-----------|---------|--------------------------|-------------------|----------|
| ...       | toClassic | PeerEventBusActor | NET2 LOOM | YES |

Step 4 — Identify any sites that can be fixed NOW (pre-CAPSTONE):
  Criteria: the bridge exists only because a Typed actor passes its own ref
  to a child/worker that accepts a classic param. If we update the child's
  param type to `ActorRef[T]`, both the bridge AND the actorSelection
  workaround disappear. Check AccountRangeWorker and ByteCodeWorker
  `coordinator` parameter types specifically.

Step 5 — Output the full audit to `.local/docs/classic-interop-audit.md`.
  Sections: (1) inventory table, (2) root-cause mapping, (3) elimination order,
  (4) "fix now" candidates, (5) estimated bridge count at each LOOM sprint boundary.
```

**Expected output:** `.local/docs/classic-interop-audit.md` — ~100-200 rows.

---


#### §8k-E — MITHRIL: Lift `NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo: ActorRef)` to Typed

**Agent:** MITHRIL
**Risk:** LOW-MEDIUM — command ADT change in NPMA; 15 call sites updated across sync subsystem
**Gate:** §8k-D complete (PEBA subscriber protocol clean first; NPMA subscribes to PEBA at line 108)
**Bridge sites eliminated:** ~15 (Cluster B `handshakedPeersAdapter.toClassic` across FastSync, SyncStateSchedulerActor, PeersClient, BlockBroadcaster, ChainDownloader) + 1 (Cluster K NPMA spawn .toClassic in NodeBuilder if callers migrate)

**Background:**
`NPMA.GetHandshakedPeersCmd(replyTo: ActorRef)` is untyped. Typed callers must convert their
`messageAdapter` to `.toClassic` (15 sites). Changing to `GetHandshakedPeersCmd(replyTo: ActorRef[HandshakedPeers])`
removes all 15 sites and unblocks the NPMA spawn `.toClassic` in NodeBuilder.

**Steps:**
1. In `NPMA.Command` ADT: change `GetHandshakedPeersCmd(replyTo: ActorRef)` →
   `GetHandshakedPeersCmd(replyTo: ActorRef[HandshakedPeers])`.
2. Update the NPMA handler that replies with `HandshakedPeers`: `replyTo ! HandshakedPeers(...)` (already Typed tell).
3. Update the Classic shell absorption block (`NetworkPeerManagerShell`) that wraps `GetHandshakedPeersCmd`
   from external Classic callers — it can now forward directly since replyTo is Typed.
4. Remove `.toClassic` at all 15 Cluster B call sites. Each uses a `handshakedPeersAdapter: ActorRef[HandshakedPeers]`
   already — simply pass it directly: `GetHandshakedPeersCmd(replyTo = handshakedPeersAdapter)`.
5. `sbt compile-all` after each file.

**Verify:**
```bash
grep -rn "handshakedPeersAdapter\.toClassic\|toClassic.*GetHandshakedPeers" \
  src/main/ --include="*.scala"
# Expected: 0
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage NPMA + 7 Cluster B caller files
3. `git commit -m "refactor(8k-E): typed GetHandshakedPeersCmd replyTo in NPMA — remove ~15 .toClassic sites (Cluster B)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(8k-E): clearout — $SHA"`
5. **DELETE §8k-E**

---

#### §8k-F — LOOM: RegularSync full Typed migration

**Agent:** LOOM (one actor per session, follow pre-migration-checklist.md)
**Risk:** HIGH — RegularSync is a Classic actor with `Props`, `sender()`, `context.parent`, and timers.
         Full LOOM migration protocol mandatory.
**Gate:** §8k-E complete. HERALD pre-flight on BlockImporter props callers.
**Bridge sites eliminated:** ~15 (Cluster D: 2 × `ctx.toClassic.actorOf(RegularSync)` in SyncController;
         Cluster C: `ctx.toClassic.parent` at RegularSync:236; Clusters N: BlockImporter `.toClassic` sites)

**Background:**
`RegularSync` is the last major Classic actor in the sync subsystem. It is spawned via
`ctx.toClassic.actorOf(RegularSync.props(...))` by SyncController (2 sites), sends to its Classic
parent (`ctx.toClassic.parent ! WrappedSyncProtocol(...)` at RegularSync:236), and passes its own
`ctx.self.toClassic` / `broadcaster.toClassic` into `BlockImporter.Props` (Clusters N).

**LOOM pre-flight (mandatory — run before any edit):**
```bash
cd /media/dev/2tb/dev/fukuii/src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/

# sender() usages
grep -n "sender()\|context\.sender()" RegularSync.scala

# context.parent
grep -n "context\.parent\|context\.toClassic\.parent" RegularSync.scala

# timers
grep -n "context\.system\.scheduler\|timers\." RegularSync.scala

# worker/child spawns
grep -n "context\.actorOf\|context\.toClassic\.actorOf\|ctx\.spawn" RegularSync.scala
```

**Migration outline (LOOM fills in details):**
1. Create `RegularSync.Command` sealed trait (reuse existing `RegularSyncCommand` if already defined).
2. Convert `class RegularSync extends Actor { def receive = ... }` → `Behaviors.receive[RegularSyncCommand]`.
3. Replace `context.parent ! WrappedSyncProtocol(msg)` with typed parent ref injected at spawn via
   `SyncController` passing `ctx.self.narrow[WrappedSyncProtocol]`.
4. Replace `sender()` capture in ask handlers with `replyTo: ActorRef[T]` in commands.
5. In `SyncController`: change `ctx.toClassic.actorOf(RegularSync.props(...))` → `ctx.spawn(RegularSync.behavior(...))`.
6. In `BlockImporter.Props`: remove `supervisor: ActorRef` (Classic) → `supervisor: ActorRef[RegularSync.ProgressProtocol]`.
   Remove `broadcaster.toClassic` (Cluster N).
7. `sbt compile-all` after each phase.

**Verify:**
```bash
grep -rn "RegularSync\.props\|ctx\.toClassic\.actorOf.*RegularSync\|context\.toClassic\.parent" \
  src/main/ --include="*.scala"
# Expected: 0
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage RegularSync + BlockImporter + SyncController (2 spawn sites)
3. `git commit -m "refactor(8k-F): RegularSync Classic→Typed migration — remove ctx.toClassic.actorOf + parent bridge (Clusters C/D/N)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(8k-F): clearout — $SHA"`
5. **DELETE §8k-F**

**Rejection criteria:** Any change to consensus, mining, or domain code. Block validation logic must not move.

---

#### §8k-G — CONDUIT + MITHRIL: OQ-5 kill — migrate jsonrpc callers to Typed ask

**Agent:** CONDUIT (jsonrpc layer audit), then MITHRIL (implementation)
**Risk:** MEDIUM — ~74 bridge sites across SyncController + jsonrpc layer; touches live RPC path
**Gate:** §8k-F complete (RegularSync Typed — SyncController OQ-5 reply path must be clean first)
**Bridge sites eliminated:** ~56 (Clusters C + E: `ctx.toClassic.sender()` + `externalAdapter.toClassic` in SyncController/FastSync)
         + ~18 (Cluster L: `AkkaTaskOps.askFor` call sites in jsonrpc) = **~74 total**

**Background:**
`EthInfoService`, `NodeJsonRpcHealthChecker`, `McpResources`, `McpTools`, `FukuiiService` and others
use Classic `?` ask against `SyncController`'s Classic ref (OQ-5). This forces SyncController to
capture `ctx.toClassic.sender()` (~27 sites) and maintain `externalAdapter.toClassic` (~29 sites).
`AkkaTaskOps.askFor` is the shared adapter that carries an untyped `replyTo: ActorRef` at ~18 call sites.

**Steps:**
1. Add `replyTo: ActorRef[T]` to `SyncProtocol.GetStatus`, `ResetFastSync`, `RestartFastSync`
   (and any other commands in Clusters C/E that currently use `sender()`).
2. In SyncController: replace every `ctx.toClassic.sender()` with `cmd.replyTo ! response`.
   Replace every `externalAdapter.toClassic` with `cmd.replyTo` (Typed).
3. In jsonrpc callers: replace `Classic ?` ask on the SyncController classic ref with
   `AskPattern.ask[SyncProtocol.StatusResponse](syncControllerTyped, replyTo => GetStatus(replyTo))`.
4. Delete `AkkaTaskOps.askFor` (now unused) and its import sites.
5. In NodeBuilder: `classicSystem.spawn(SyncController(...)).toClassic` (Cluster K) → callers now
   hold the Typed ref directly; remove the `.toClassic` conversion.
6. `sbt compile-all` after each file group (jsonrpc callers can be done in parallel — independent files).

**Verify:**
```bash
grep -rn "ctx\.toClassic\.sender()\|externalAdapter\.toClassic\|AkkaTaskOps" \
  src/main/ --include="*.scala"
# Expected: 0
grep -rn "toClassic" src/main/scala/com/chipprbots/ethereum/jsonrpc/ --include="*.scala"
# Expected: 0
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage SyncController + FastSync + all jsonrpc caller files + AkkaTaskOps deletion
3. `git commit -m "refactor(8k-G): OQ-5 kill — typed ask in jsonrpc, remove ~74 .toClassic sites (Clusters C+E+L)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(8k-G): clearout — $SHA"`
5. **DELETE §8k-G**

---

#### §8k-H — MITHRIL: PeerActor parent notification → `watchWith` + typed Command

**Agent:** MITHRIL
**Risk:** LOW — PeerManagerActor is already Typed; change is localized to PeerActor and its PMA spawn site
**Gate:** §8k-G complete (PeerManagerActor changes coincide with other jsonrpc callers)
**Bridge sites eliminated:** 7 (Cluster G: `context.toClassic.parent ! PeerClosedConnection`) + 1 (Cluster H: `ctx.spawn(...).toClassic`)

**Background:**
`PeerActor` sends `PeerClosedConnection(id)` to its parent (`PeerManagerActor`) via
`context.toClassic.parent ! PeerClosedConnection(...)` (6 sites). PeerManagerActor spawns PeerActor
via `ctx.spawn(...)` so the parent IS Typed, but PeerActor uses the Classic parent path. There are
two clean fixes; (B) is preferred:

**(A)** Add `PeerClosedConnection` to `PeerManagerActor.Command` ADT; PeerActor sends via
       `context.toTyped[PeerManagerActor.Command] ! PeerClosedConnection(id)`.

**(B)** Use `watchWith` at the spawn site: `ctx.watchWith(peerRef, PeerClosed(id))`.
       PeerActor needs to stop (or throw) rather than send the notification; PMA receives `PeerClosed`
       on child termination. Eliminates all 6 active `context.toClassic.parent !` sends.
       Already used elsewhere in the codebase — preferred pattern.

Also: `PeerManagerActor.scala:1022` stores the spawned PeerActor as `ctx.spawn(...).toClassic`
(Cluster H). After (B), PMA no longer needs the Classic ref stored for `sender()` reply purposes.

**Steps:**
1. At PeerActor spawn site in PMActor: replace `ctx.spawn(behavior, id).toClassic` with `ctx.spawn(behavior, id)`.
   Use `ctx.watchWith(typedRef, PeerClosed(id))` to receive termination.
2. In PeerActor: remove all 6 `context.toClassic.parent ! PeerClosedConnection(...)` sends.
   PeerActor should simply stop (throw / return `Behaviors.stopped`) when it detects disconnection —
   the PMA watchWith will fire.
3. Remove `context.self.toClassic` at PeerActor:535 (stored in `Peer` case class as Classic ref).
   Update `Peer` to hold `ActorRef[PeerActor.Command]` instead.
4. `sbt compile-all` + `sbt "testOnly *PeerActor* *PeerManager*"`.

**Verify:**
```bash
grep -rn "context\.toClassic\.parent\|context\.self\.toClassic" \
  src/main/scala/com/chipprbots/ethereum/network/PeerActor.scala
# Expected: 0
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage PeerActor + PeerManagerActor + Peer case class
3. `git commit -m "refactor(8k-H): PeerActor watchWith — remove context.toClassic.parent sends (Clusters G+H)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(8k-H): clearout — $SHA"`
5. **DELETE §8k-H**

---

#### §8k-I — MITHRIL: NodeBuilder Classic bridge actor elimination

**Agent:** MITHRIL
**Risk:** MEDIUM — touches node bootstrap wiring in NodeBuilder; verify all callers still reach their target
**Gate:** §8k-G complete (PTM `AkkaTaskOps` migration done) + §8k-H complete (PeerManagerActor clean)
**Bridge sites eliminated:** 3 anonymous Classic bridge actors in NodeBuilder (Cluster J) + ~18 call sites
         in FilterManager/PersonalService/GraphQLSchema/TestService using `pendingTransactionsManager: ActorRef`

**Background:**
`NodeBuilder.scala` wires 3 anonymous Classic bridge actors (Cluster J) to service callers that still
use legacy Classic ask/tell patterns:
1. `knownNodesManager` bridge (line 210): PeerManagerActor sends `GetKnownNodes` to a Classic bridge
   which forwards as a Typed ask to `knownNodesManagerTyped`.
2. `peerDiscoveryManager` bridge (line 269): PeerManagerActor/StdNode send `GetDiscoveredNodesInfo`
   via Classic bridge.
3. `pendingTransactionsManager` bridge (line 547): FilterManager/PersonalService/GraphQLSchema/TestService
   use Classic `?` ask for `GetPendingTransactions`.

For each bridge, the callers need to switch to the existing Typed `*Req(replyTo: ActorRef[T])` variant
that is already present in the respective Typed actor's Command ADT.

**Steps:**
1. **KnownNodesManager bridge**: Find every `knownNodesManager.tell(GetKnownNodes, sender)` call site.
   Replace with `AskPattern.ask(knownNodesManagerTyped, KnownNodesManagerActor.GetKnownNodesReq(_))`.
   Delete the bridge actor at NodeBuilder:210.

2. **PeerDiscoveryManager bridge**: Find every `peerDiscoveryManager.tell(GetDiscoveredNodesInfo, sender)`
   / `GetRandomNodeInfo`. Replace with Typed ask to `peerDiscoveryManagerTyped`.
   Delete the bridge actor at NodeBuilder:269.

3. **PTM bridge**: FilterManager, PersonalService, GraphQLSchema, TestService — replace Classic
   `?` ask for `GetPendingTransactions` with `AskPattern.ask(pendingTransactionsManagerTyped, ...)`.
   Delete the bridge actor at NodeBuilder:547.

4. `sbt compile-all` after each bridge deletion.

**Verify:**
```bash
grep -rn "classicSystem\.actorOf" src/main/ --include="*.scala"
# Expected: 0 in NodeBuilder (only TCP bridges in ServerActor remain)
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. Stage NodeBuilder + caller files (PeerManagerActor, FilterManager, PersonalService, etc.)
3. `git commit -m "refactor(8k-I): delete 3 NodeBuilder Classic bridge actors — callers use Typed ask (Cluster J)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(8k-I): clearout — $SHA"`
5. **DELETE §8k-I**
6. After this commit, verify total `.toClassic` count = 4 (TCP permanent floor only):
   ```bash
   grep -rn "\.toClassic" src/main/ --include="*.scala" | grep -v "//.*toClassic"
   # Expected: 4 lines (ServerActor.TcpEventBridge + RLPxConnectionHandler ×2 + sa.toClassic in BlockFetcher)
   ```

---

#### §8k-B — Post-CAPSTONE: Final classic bridge verification sweep

**Agent:** PRISM (verification only)
**Risk:** LOW — read-only final check
**Gate:** §8k-I complete AND CAPSTONE merged. Run §7d artifact audit first (they overlap).

**Background:**
After §8k-A through §8k-I, only 4 intentional TCP permanent bridges should remain.
This sprint verifies that claim and deletes the `pekko.actor.typed.scaladsl.adapter` imports
that are no longer needed anywhere outside the TCP path.

**Expected state after §8k-A–I:**
- `grep -rn "\.toClassic" src/main/ --include="*.scala" | grep -v "//"` → 4 lines only (TCP)
- `grep -rn "toClassic\|toTyped" src/main/ --include="*.scala" | wc -l` → ≤ 4
- `import org.apache.pekko.actor.typed.scaladsl.adapter` → only in TCP-path files

**Prompt (run AFTER §8k-I + CAPSTONE):**
```
§8k-A through §8k-I are complete. Verify the TCP floor:

Step 1 — grep for any remaining .toClassic / .toTyped outside of:
  ServerActor.scala, RLPxConnectionHandler.scala (TCP I/O — permanent)
Step 2 — If found: identify which sprint was supposed to clear it and create
  a §8k-J follow-up entry in DEFERRED-BACKLOG.md.
Step 3 — Delete all `import org.apache.pekko.actor.typed.scaladsl.adapter`
  lines from files that no longer use the adapter.
Step 4 — Run §7d artifact audit sweep (grep commands in §7d).
Step 5 — Run testEssential — confirm baseline holds.
Step 6 — git commit -m "chore(8k-B): remove adapter imports — TCP floor verified (4 bridges)"
```

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
| **8k-B — Bridge elimination** | Post-CAPSTONE: remove all ~170 remaining `.toClassic`/`.toTyped` bridge calls now that every upstream actor is Typed | LOOM, PRISM | CAPSTONE + 7d done |
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
| ~~**Part 1**~~ | ~~Remaining 5 compiler warnings~~ | ~~WRAITH~~ | ✅ DONE `bd2d691c3` — 5 warnings cleared |
| ~~**6a — extvm deletion**~~ | ~~Delete `extvm/` (10 files) after grep-verify~~ | ~~WRAITH~~ | ✅ DONE `a948fda1d` — 18 files deleted (extvm/ + proto + sbt-protoc) |
| ~~**8f — Dead code audit**~~ | ~~research + deletion sprint~~ | ~~WRAITH~~ | ✅ DONE — `fa57df9b9` (MetricsAlreadyConfiguredError + LocalVM + AdaptiveSyncStrategy), `c6b3da4cb` (DeltaSpikeGauge), `ff2fc219c` (StaticNodesLoader); branch-wide audit 2026-06-22 confirmed no further candidates |
| ~~**3d — enum polish**~~ | ~~Migrate `SyncPhase`, `BlacklistReason`, `ForkId` codes to enum~~ | ~~MITHRIL~~ | ✅ DONE — `adf4e69ea` (SyncPhase + ForkIdValidationResult), `b305ef41b` (NetworkType/VmMode/FaucetStatus/SealEngineType), `c1ecd9706` (ServerStatus), `7f9c987cc` (PruningMode), `75a3d8c5d` (MiningMode). BlacklistReason/BlacklistReasonType ❌ REJECTED (case class subtypes). **`SyncProtocol.SyncStatus` still candidate** — see §3d residual note |
| ~~**3e — console→logging**~~ | ~~Replace 24 `println`/`System.out` calls with SLF4J~~ | ~~MITHRIL~~ | ✅ DONE `c3fec6390` 2026-06-22 — 12 sites fixed (3 files); 8 intentional CLI/TUI calls preserved |
| **8e — ScalaFix expansion** | Rules in .scalafix.conf ✅; C2 ✅ `9eb1f4e06`; TNHC ✅ `7a48c5988`; remaining: 7 consensus (FORGE) + 36 SSC (SNAP1) | FORGE / LOOM | gated |
| **8g — braceless config** ✅ `34a55a025` | Deferred settings documented in .scalafmt.conf; indent.defnSite + topLevelStatementBlankLines each trigger ~400-file reformats → gated for per-subsystem pass post-CAPSTONE | MITHRIL | done |
| **8j — Thread.sleep** | 2 live call sites (EthMiningServiceSpec:302, SubscriptionManagerSpec:249) — both NECESSARY; defer to §8a-retro (Typed TestKit enables proper replacement) | EYE | deferred to §8a |
| **8k-R1 — Classic interop audit** | PRISM: run §8k-R1 prompt — map every `.toClassic`/`actorSelection` to root-cause classic actor; confirm §8k-A scope; output `classic-interop-audit.md` | PRISM | any time |
| ~~**8k-A — Typed coordinator ref**~~ | ~~MITHRIL: update AccountRangeWorker + ByteCodeWorker `coordinator:` param from classic → typed `ActorRef[T]`; remove `.toClassic` at spawn sites~~ | ~~MITHRIL~~ | ✅ DONE — workers already use typed coordinator refs (`ActorRef[T.Command]`) |
| ~~**3f — manual sync**~~ | ~~Audit 5 `.synchronized` outside actors~~ | ~~PRISM~~ | ✅ DONE `cf33cfa87` — MapCache:19+30 fixed (TrieMap); CombinedRecoveryScanner + TNHC left as-is (documented); PoWMining FORGE-gated (CHASE-QUEUE) |
| **8a-retro** | Batches 1+2 DONE — **batch 3 (G1 network/sync actors)** needs TestKit→ActorTestKit migration; clearout prompt in §8a below | LOOM, EYE | ~3h |

### Research Threads (run before implementation; can overlap with primary track)

| Thread | Goal | Output doc | Agent |
|--------|------|-----------|-------|
| **R9** | §8k-R1: Classic interop inventory — map every `.toClassic`/`actorSelection`/bridge site to its root-cause classic actor and LOOM sprint | `classic-interop-audit.md` | PRISM |
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
| ~~C3~~ | ~~Batch C step 3~~ | ~~P3 MITHRIL console→logging (28 sites)~~ | ✅ DONE 2026-06-22 — 12 sites fixed |
| ~~C4~~ | ~~Batch C step 4~~ | ~~P4 MITHRIL/EYE E165 sprint — expectMsgType[Any]~~ | ✅ DONE 2026-06-22 — `8cdf1290d` — 20 sites → 0; 777 TestProbe metric + 20 fishForMessage sites §8a-gated (see §8a research prompt) |
| ~~D3~~ | ~~Batch D~~ | ~~P7 EYE test timing audit~~ | ✅ DONE 2026-06-22 — 680s (11m 20s) baseline, 3,595 tests, 0 code changes; both Thread.sleep sites defer to §8a; all wall-clock bounds safe. Note: captured pre-Batch-D; rerun after G1/G2 if test count grows. |
| ~~E3~~ | ~~Batch E~~ | ~~§3h — Any type signature cleanup~~ | ✅ DONE 2026-06-22 — 0 types changed; 15 sites documented `// Any:`; 7 FORGE-gated (vm/domain/ledger); compile clean |
| ~~E4~~ | ~~Batch E~~ | ~~§8a-retro batch 3 — 25 network/sync specs~~ | ✅ DONE 2026-06-23 — `12c23cf8a` (14 specs) + `a719520db` (11 specs + NPMAFake fix) |
| ~~E5~~ | ~~Batch E~~ | ~~§8a-retro batch 4 — 14 coordinator/heal specs (PropsAdapter fixture fix)~~ | ✅ DONE 2026-06-23 — `5eae34c21` (14 specs + HealingTrieFixtures to ActorTestKit, 135 tests) |
| ~~E5b~~ | ~~Batch E~~ | ~~§8a-infra — create `application-test.conf` (bare ctor fix + `throughput=1`)~~ | ✅ DONE 2026-06-23 — `8b9bef67d` |
| ~~E5c~~ | ~~Batch E~~ | ~~§8a-infra-b — audit + fix worker teardown leaks in coordinator/heal specs~~ | ✅ DONE 2026-06-23 — `781c8e985` — no leaks; workers are Typed `spawnAnonymous` children, stopped by hierarchy; 150/150 ×2 |
| ~~E5d~~ | ~~Batch E~~ | ~~§8a-retro batch 4b — E165 TestProbe narrowing in coordinator/heal specs (~209 sites)~~ | ✅ DONE 2026-06-23 — `a193bc794` (14 specs, 141 tests, floor 92→65) |
| E5e | Batch E | §8a-infra-c — MITHRIL: replace classic `actorSelection` worker-ref pattern with Typed injection in ByteCodeCoordinatorSpec + AccountRangeCoordinatorSpec | No — cosmetic; run after E5d |
| E6 | Batch E | §8a-retro batch 5 — multi-system + TestActorRef specs (3 assessable, 2 Wave 3 gate) | Partial — BlockFetcherSpec + PendingTxMgr + RegularSyncSpec assessable now; PeerActor + RLPx wait for Wave 3 |
| ~~F1~~ | ~~Batch F~~ | ~~§3i MITHRIL+FORGE — BlockExecutionError hierarchy redesign: union type + `describe`~~ | ✅ DONE 2026-06-23 — `64ab4786e` |

**Global sequence:** See CODEBASE-AUDIT.md Clearout Prompts header.

---

## Part 10: Test Suite Performance

### P7 — EYE/MITHRIL: Test timing audit + slow-test reduction

**Agent:** EYE (timing profiler), MITHRIL (Thread.sleep replacement)
**Prerequisite:** testEssential gate passed. Run AFTER Batch D (G1/G2) so that any new test files
from the Behavior[Any] narrowing sprint are included in the timing baseline.

**Context:** testEssential baseline was ~24:22 (3,601 tests). Batch C cleanup (dead code deletion,
E165 expectMsgType narrowing, enum conversions) may have affected this. After Batch D the suite will
grow slightly (narrowing adds typed actor specs). This prompt captures the new baseline and identifies
actionable slow tests.

**Steps:**

1. **Capture new baseline:**
   ```bash
   cd /media/dev/2tb/dev/fukuii
   time .local/scripts/fukuii-test 2>&1 | tee /tmp/fukuii-test-timing.log
   ```
   Record total wall time from `time` output.

2. **Identify slow tests (>2s per test):**
   ```bash
   grep -E "\([0-9]+ seconds" /tmp/fukuii-test-timing.log | sort -t'(' -k2 -rn | head -20
   ```
   List the top 20 slowest individual tests.

3. **Assess Thread.sleep sites (2 known):**
   - `EthMiningServiceSpec.scala:302` — timeout window advance; check if `TestScheduler` can replace
   - `SubscriptionManagerSpec.scala:249` — topic propagation wait 200ms; check if `awaitAssert` with short poll replaces it
   For each: if replaceable with `TestScheduler` or `eventually(timeout(500.ms), interval(10.ms))`,
   fix inline. If requires Typed TestKit migration → defer to §8a.

4. **Assess wall-clock assertions (3 known + 1 borderline):**
   - `WorkNotifierSpec` L103–108 (`elapsed should be < 500L`) — can the upper bound be raised to reduce flakiness?
   - `MerkleProofVerifierPhase3Spec` L584–603 — already has generous bounds; record observed times
   - `TrieNodeHealingCoordinatorSpec` L316–327 (`elapsedMs should be < 5000L`) — record observed time
   - `SnapServerLimitsSpec` L89–90 — borderline; record whether it flaps
   If any bound is routinely met with <50% margin, either raise the bound or replace with a
   non-time-based assertion.

5. **Check for accidentally slow test infrastructure:**
   ```bash
   grep -rn "Thread\.sleep\|Await\.result\|blocking {" src/test/ --include="*.scala"
   ```
   Any new sites not in the known list → log to CHASE-QUEUE.

**Verification:** New baseline ≤ prior baseline (23 min target). All 3,601+ tests pass.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll` — if any test files were modified
2. `git add <specific test files changed>` — stage only modified files; skip if no source changes
3. `git commit -m "test(timing): P7 — replace wall-clock assertions, N fixes"` — omit if no source changes
4. `SHA=$(git rev-parse --short HEAD)` — capture SHA (or note "no source commit" if step 3 skipped)
5. Update run-order table: strikethrough D3 → `| ~~D3~~ | ~~Batch D~~ | ~~P7 EYE test timing audit~~ | ✅ DONE [date] — Xs baseline, N improvements, $SHA |`
6. Update `test-quality-log.md` with new baseline
7. Any Thread.sleep fixes → `completed/SPRINT-QUEUE.md` row with `$SHA`
8. `git add .claude/` → `git commit -m "docs(p7): clearout — $SHA"`

**Rejection criteria:** Weakening test assertions beyond 2× measured time; skipping tests to reduce count; modifying test logic (only timing assertions and sleep replacement are in scope)

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

---

## Part 11: Full Test Suite Coverage Audit

**Goal:** 100% coverage of the test suite — no test permanently hidden behind an exclusion tag
without a documented verdict. Every excluded test gets one of: Fix & enable / Delete / Defer with
written reason. P7 covered only `testEssential`; this part closes the gap.

**Tag inventory at 2026-06-22:**
| Tag | Count | Currently excluded from | Intent (per ADR-017 / Tags.scala) |
|-----|-------|------------------------|----------------------------------|
| `SyncTest` | ~50+ tests, 8 files | ALL tiers | "blockchain synchronisation" — many are actually pure unit tests, mislabelled |
| `DisabledTest` | 9 tests, 5 files | ALL tiers | "temporarily disabled due to known issues — should be re-enabled" |
| `FlakyTest` | 8 tests, 3 files | ALL tiers | "investigate and fix but temporarily marked to avoid blocking CI" |
| `SlowTest` | ~30 tests, 8 files | testEssential only | Legitimately slow (PoW/DAG CPU) — some may be mislabelled |
| `StressTest` | unknown | ALL tiers | Long-running stress — not yet assessed |
| `ManualTest` | unknown | ALL tiers | Requires human verification — may be deletable or automatable |

**Run order — this section:**
| # | Batch | Prompt | Parallel-safe? |
|---|-------|--------|----------------|
| ~~E1~~ | ~~Batch E~~ | ~~P8 EYE SyncTest tag audit~~ | ✅ DONE 2026-06-23 — 40 rescued (15 RetryStrategy + 7 PeersClient + 6 Blacklist + 12 BlockchainHostActor), 36 kept SyncTest, `3aef474a9` |
| ~~E2~~ | ~~Batch E~~ | ~~P9 EYE/MITHRIL DisabledTest audit~~ | ✅ DONE 2026-06-23 — `86c76fd4e` — 2 fixed, 7 deferred (F6 CODEBASE-AUDIT) |
| ~~E3~~ | ~~Batch E~~ | ~~P10 EYE/MITHRIL FlakyTest root cause~~ | ✅ DONE 2026-06-23 — `ab98f1370` — 11 de-tagged, 2 deleted (F7 CODEBASE-AUDIT) |
| ~~E4~~ | ~~Batch E~~ | ~~P11 testStandard baseline + SlowTest audit~~ | ✅ DONE 2026-06-23 — 961s/3,579 tests; 6 SlowTest→UnitTest `edfb69f35`; 2 failures: DNS flaky (Mordor DNS) + BHA pre-existing (fixed `07e5d505f`) |
| E5 | Batch E | P12 Tag taxonomy + build target architecture review | Yes (read-only) |

---

### P8 — EYE: SyncTest tag audit — rescue mis-tagged unit tests

**Agent:** EYE (read, grep, verdict per test)
**Prerequisite:** None. Read-only — no code changes, only assessment and a verdict file.

**Context:** `SyncTest` is excluded from ALL tiers in `build.sbt:85`. The tag description says
"Tests for blockchain synchronisation." However, grep reveals ~50 tests across 8 files using this
tag, many of which look like pure unit tests (exponential backoff math, cache data structures, peer
selection logic) that don't require live sync or any actor timing. They were probably tagged
`SyncTest` because they live in sync-related packages, not because they actually need the exclusion.

Rescuing mis-labelled tests to `UnitTest` would immediately add them to `testEssential`.

**Files to audit:**
- `RetryStrategySpec.scala` — 12 tests: exponential backoff, delay caps, jitter, fluent config. Likely all pure unit.
- `PeersClientSpec.scala` — 5 tests: peer selection data structures (BestPeer, filter by block number).
- `CacheBasedBlacklistSpec.scala` — 5 tests: blacklist cache add/expire/remove/keys.
- `BlockchainHostActorSpec.scala` — 8 tests: actor serves block data using TestProbe. Actor-based but hermetic.
- `StateStorageActorSpec.scala` — 1 test: actor persists fast sync state.
- `StateSyncSpec.scala` — 2 tests: state sync to tries.
- `FastSyncSpec.scala` — 3 tests tagged `(UnitTest, SyncTest, FlakyTest)` + 1 tagged same. (FlakyTest root cause is P10.)
- `SyncControllerSpec.scala` — `FlakyTest` ones are P10. Remaining SyncTest-only tests assessed here.

**Steps:**
1. For each file above, read the test bodies. For each test, answer:
   - Does it require a live network connection or real peer handshake? → Keep `SyncTest`
   - Does it use real clock / wall-time sensitivity? → Keep `SyncTest` or add `FlakyTest`
   - Is it a pure function / data-structure test with TestProbe? → Candidate for `UnitTest` rescue
   - Is it tagged `SyncTest` AND `FlakyTest`? → Skip (P10 handles FlakyTest cases)

2. Produce a verdict table:
   ```
   | File | Test description | Current tags | Verdict | Reason |
   ```
   With verdicts: `RESCUE→UnitTest` / `KEEP SyncTest` / `REASSIGN→IntegrationTest` / `DEFER (P10)`.

3. For each `RESCUE` verdict: remove `SyncTest`, add `UnitTest` if not already present.
   - `SyncTest` appears in two patterns: `taggedAs (UnitTest, SyncTest)` and `taggedAs (UnitTest, SyncTest, FlakyTest)`
   - Only edit the `UnitTest, SyncTest` (no FlakyTest) ones in this prompt
   - Compile after each file: `sbt compile-all`

4. Run `testEssential` after all rescues to confirm the rescued tests pass in Tier 1.

**Verification:** `sbt compile-all` clean. Rescued tests appear in `testEssential` output and pass.
`testEssential` count increases by the number of rescued tests.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific test files modified>` — stage only the rescued/fixed test files
3. `git commit -m "test(p8): SyncTest audit — rescue N tests, delete M"`
4. `SHA=$(git rev-parse --short HEAD)` — capture exact SHA
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E1 → `| ~~E1~~ | ... | ✅ DONE [date] — N rescued, $SHA |`
6. Update `test-quality-log.md` with new testEssential count
7. Add CHASE-QUEUE entry: remaining SyncTest count and path to `-l SyncTest` removal (P8+P10 prerequisite)
8. `git add .claude/` → `git commit -m "docs(p8): clearout — $SHA"`

**Rejection criteria:** Rescuing any test that uses `Thread.sleep`, real wall-clock assertions, or
live network/peer connections. Rescue only hermetic tests.

---

### P9 — EYE/MITHRIL: DisabledTest audit — fix, wire, or delete

**Agent:** EYE (assess each test), MITHRIL (implement fixes where needed)
**Prerequisite:** None. Can run parallel to P8.

**Context:** 9 tests across 5 files are tagged `DisabledTest`, which ADR-017 defines as
"temporarily disabled due to known issues — should be re-enabled." These are not dead code —
they are tests with a stated intent. But "temporarily" may have become permanent. Each needs
a verdict: Fix & enable / Delete (the test is wrong or the feature is gone) / Defer with
written reason and a GitHub issue link.

**Inventory (9 tests, 5 files):**

| File | Line | Test description |
|------|------|-----------------|
| `RegularSyncSpec.scala` | 522 | "retry fetching node if validation failed" |
| `RegularSyncSpec.scala` | 550 | "save fetched node" |
| `SyncControllerSpec.scala` | 243 | "not change best block after receiving faraway block" |
| `SyncControllerSpec.scala` | 434 | "re-enqueue block bodies when empty response is received" |
| `JsonRpcControllerSpec.scala` | 76 | (read to determine description) |
| `JsonRpcControllerSpec.scala` | 127 | (read to determine description) |
| `JsonRpcControllerEthSpec.scala` | 559 | (read to determine description) |
| `JsonRpcControllerEthSpec.scala` | 852 | (read to determine description) |
| `EthTxServiceSpec.scala` | 372 | (read to determine description) |

**Steps for each test:**
1. Read the test body (±20 lines around the listed line).
2. Run `git log -p --follow -S "DisabledTest" -- <file>` to find when/why it was disabled.
3. Attempt to compile and run the test alone: `sbt "testOnly *SpecName* -- -n DisabledTest"` — does it pass?
4. Verdict:
   - **FIX**: If the test fails with a specific error → fix the underlying issue, remove `DisabledTest`, add appropriate tier tag.
   - **DELETE**: If the feature under test was removed, renamed, or the test was clearly wrong → delete the test and note why.
   - **DEFER**: If fixing requires significant new implementation or blocked on an external gate → document the block, create a CHASE-QUEUE entry, leave `DisabledTest` tag but add a comment with the reason.

5. Commit fixed tests individually. Format: "test: re-enable <TestName> — <one-line fix>"

**Verification:** After each fix, `sbt compile-all` + `sbt "testOnly *SpecName*"` passes.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific test files modified>` — stage only the fixed/deleted test files
3. `git commit -m "test(p9): DisabledTest audit — fix N, delete M, defer K"` — one commit per test or per file is also fine (see step 5 in the prompt above)
4. `SHA=$(git rev-parse --short HEAD)` — capture the final commit SHA (or comma-separate multiple SHAs if committed individually)
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E2 → `| ~~E2~~ | ... | ✅ DONE [date] — N fixed, M deleted, $SHA |`
6. Add any DEFERred items to CHASE-QUEUE with `[DisabledTest]` prefix
7. `git add .claude/` → `git commit -m "docs(p9): clearout — $SHA"`

**Rejection criteria:** Re-enabling a test without understanding why it was disabled. Never remove
`DisabledTest` without verifying the test actually passes.

---

### P10 — EYE/MITHRIL: FlakyTest root cause audit — fix or delete

**Agent:** EYE (diagnose root cause), MITHRIL (fix with deterministic patterns)
**Prerequisite:** P8 complete (so SyncTest+FlakyTest overlap is clear).

**Context:** 8 tests across 3 files are tagged `FlakyTest`. ADR-017 says "investigate and fix but
temporarily marked to avoid blocking CI." These are the tests most likely to contain real bugs —
race conditions, wall-clock sensitivity, or non-deterministic actor interactions. None of them run
in any tier. Fixing them is high-value: these cover sync state, PoW mining, and peer management.

**Inventory (8 tests, 3 files):**

| File | Line | Test description | Also tagged |
|------|------|-----------------|-------------|
| `FastSyncSpec.scala` | ~244 | (read to determine) | UnitTest, SyncTest |
| `FastSyncSpec.scala` | ~287 | (read to determine) | UnitTest, SyncTest |
| `FastSyncSpec.scala` | ~311 | (read to determine) | UnitTest, SyncTest |
| `FastSyncSpec.scala` | ~336 | "returns Syncing with state nodes progress" | UnitTest, SyncTest |
| `SyncControllerSpec.scala` | ~385 | (read to determine) | (check) |
| `SyncControllerSpec.scala` | ~470 | (read to determine) | (check) |
| `PoWMiningCoordinatorSpec.scala` | ~123 | "Miners mine recurrently" | UnitTest, ConsensusTest, SlowTest |
| `PoWMiningCoordinatorSpec.scala` | ~188 | "StopMining stops PoWMinerCoordinator" | UnitTest, ConsensusTest, SlowTest |

**Known root cause — SyncControllerSpec FlakyTests (identified in P9 thread, 2026-06-23):**

`SyncController.scala:895-897` — `handleRegularSyncMsg` forwards all unhandled messages to
`RegularSync` via `regularSync.tell(msg, ctx.toClassic.sender())`. When `FastSync.Done` arrives
late (after `syncSwitchDelay = 0.5s`, i.e. after SyncController has already transitioned to
`runningRegularSync`), it lands in this catch-all and is `tell`-forwarded to the RegularSync
classic child, which crashes with `ClassCastException: FastSync$Done$ cannot be cast to
RegularSyncCommand`.

**Fix (apply before diagnosing the tests):** Add a guard arm before the catch-all in
`handleRegularSyncMsg`:
```scala
case FastSync.Done => Behaviors.same  // late arrival after sync switch — ignore
```
Confirm the arm is placed BEFORE the `regularSync.tell` catch-all. Compile:
```bash
sbt compile-all
```
Then run the two SyncControllerSpec FlakyTests 5× to confirm the race is resolved before
proceeding with the remaining inventory.

**Steps for each test (one at a time, no parallel):**
1. Read the full test body.
2. `git log -p --follow -S "FlakyTest" -- <file>` to find when it was marked flaky and what comment was left.
3. Identify the root cause category:
   - **`Thread.sleep` / wall-clock assertion** → Replace with `TestScheduler` / `eventually` / `awaitAssert`
   - **Non-deterministic actor message ordering** → Add `TestProbe.expectMsgAllOf` or reorder assertions
   - **Race between actor startup and first message** → Add `awaitAssert` or `expectMsgType` with explicit timeout
   - **Real PoW computation timing** (PoWMiningCoordinatorSpec) → Inject a fake miner that succeeds immediately
   - **Test depends on external state** → Isolate with mocks or hermetic fixtures
4. Attempt the fix. Compile: `sbt compile-all`.
5. Run 10× to confirm not flaky: `for i in $(seq 10); do sbt "testOnly *SpecName*" && echo "PASS $i" || echo "FAIL $i"; done`
6. If not fixable without major refactor → verdict DELETE, with rationale written in test comment before removal.
   Never leave a flaky test enabled — either fix it or delete it.
7. Remove `FlakyTest` tag once confirmed stable (10/10 passes). Add correct tier tag.

**Special case — PoWMiningCoordinatorSpec:** These two tests involve real Ethash PoW computation,
which is inherently variable. The fix is almost certainly a fake/mock miner that completes
instantly, not a timing adjustment. Check if `EthashMiner` is injectable; if not, MITHRIL adds
a `MinerFactory` seam.

**Verification:** Fixed tests pass 10/10 in `testOnly`. No `FlakyTest` tags remain in the fixed files.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add <specific test files modified>` — stage only fixed/deleted test files
3. `git commit -m "test(p10): FlakyTest audit — fix N, delete M"` — or per-test commits (format in step 5 above)
4. `SHA=$(git rev-parse --short HEAD)` — capture final commit SHA (comma-separate if multiple)
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E3 → `| ~~E3~~ | ... | ✅ DONE [date] — N fixed, M deleted, $SHA |`
6. If any tests also rescued from SyncTest → update P8 verdict table with those SHAs
7. Update `test-quality-log.md` test count after fixes land in testEssential
8. `git add .claude/` → `git commit -m "docs(p10): clearout — $SHA"`

**Rejection criteria:** Re-tagging a flaky test as `SlowTest` or `DisabledTest` to avoid fixing it.
A test must be either reliably passing or deleted — no half-measures.

---

### P11 — EYE: testStandard baseline + SlowTest tag audit

**Agent:** EYE (run testStandard, assess SlowTest tag accuracy)
**Prerequisite:** P8, P9, P10 complete (so the test count is stable before capturing the baseline).

**Context:** `testStandard` (~30 min) adds `SlowTest` and `IntegrationTest` to the essential tier.
No baseline has ever been recorded for this tier. Additionally, some `SlowTest` tagged tests
appear mislabelled (e.g., `MiningSpec:10` — "KnownProtocols have unique names" — should not be
slow). This prompt captures the Standard baseline and audits SlowTest label accuracy.

**SlowTest inventory for label-accuracy check:**

| File | Tests | Why tagged SlowTest? | Likely correct? |
|------|-------|---------------------|-----------------|
| `DAGGenerationSpec.scala` | 7 | Ethash cache+DAG CPU computation | ✅ Yes — legitimately slow |
| `EthashNonceSearchSpec.scala` | 6 | PoW nonce search (CPU-bound) | ✅ Yes |
| `EthashMinerSpec.scala` | 2 | Mining valid blocks (actual PoW) | ✅ Yes |
| `PoWBlockHeaderValidatorSpec.scala` | 1 | Ethash header validation | Possibly — assess observed time |
| `PoWMiningCoordinatorSpec.scala` | ~7 | Mining coordinator w/ actor timing | Possibly — assess |
| `PoWMiningSpec.scala:71` | 1 | "not start miner when miningEnabled=false" | ❓ Likely mislabelled |
| `MiningSpec.scala:10,17` | 2 | "unique names" / "contain ethash" | ❌ Almost certainly mislabelled |
| `MerkleProofVerifierPhase3Spec.scala:573` | 1 | Quadratic growth regression check | ✅ Yes — 100-1000 acct comparison |

**Steps:**
1. Check system resources: `free -h && uptime` (load < 4.0 before starting).
2. Run testStandard and capture timing:
   ```bash
   cd /media/dev/2tb/dev/fukuii
   start_time=$(date +%s)
   .local/scripts/fukuii-test standard 2>&1 | tee /tmp/fukuii-teststandard-timing.log
   end_time=$(date +%s)
   echo "TOTAL_ELAPSED: $((end_time - start_time)) seconds" | tee -a /tmp/fukuii-teststandard-timing.log
   ```

3. After completion, identify the top 20 slowest tests:
   ```bash
   grep -E "\([0-9]+ seconds" /tmp/fukuii-teststandard-timing.log | sort -t'(' -k2 -rn | head -20
   ```

4. For each test tagged `SlowTest`: compare its actual observed time against the `SlowTest` definition
   (">100ms, <5 seconds"). If actual time is <100ms → `MISLABELLED` → remove `SlowTest`, add `UnitTest`.

5. For `MiningSpec:10,17` and `PoWMiningSpec:71` specifically: if observed time is <100ms →
   remove `SlowTest` tag and add `UnitTest`, which promotes them to `testEssential`.

6. Record the testStandard baseline in `fukuii/.local/docs/test-quality-log.md`.

**Verification:** All testStandard tests pass (0 failures). Baseline recorded.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll` — only if test files were modified
2. `git add <specific test files modified>` — stage only files with tag changes; skip if no source changes
3. `git commit -m "test(p11): promote N mislabelled SlowTest → UnitTest"` — omit if no source changes
4. `SHA=$(git rev-parse --short HEAD)` — capture SHA (note "no source commit" if step 3 skipped)
5. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E4 → `| ~~E4~~ | ... | ✅ DONE [date] — Xs wall time, N tests, M mislabelled fixed, $SHA |`
6. Update `test-quality-log.md` with testStandard baseline and timing
7. `git add .claude/` → `git commit -m "docs(p11): clearout — $SHA"`

**Rejection criteria:** Removing `SlowTest` from a test that actually takes >100ms. Observe the
time, don't guess. `DAGGenerationSpec` and `EthashNonceSearchSpec` must remain `SlowTest`.

---

### P11b — Docs: migrate `fukuii-test-timing.md` → `test-quality-log.md`

**COMPLETE 2026-06-23** — `test-quality-log.md` created at `.local/docs/`, all content migrated,
old file deleted, DEFERRED-BACKLOG references updated, MEMORY.md + memory file renamed.

**Agent:** Any (pure documentation — no compilation or test runs required)
**Parallel-safe:** YES — touches only `.local/docs/` and working docs
**Priority:** LOW — do before P12, since P12's final steps reference the old filename

**Context (2026-06-23):** After P11 we have two sources of test-suite knowledge:
`fukuii-test-timing.md` (tier baselines, slowest tests, wall-clock assertions, Thread.sleep
inventory) and ad-hoc findings scattered across CHASE-QUEUE, DEFERRED-BACKLOG, and sprint
notes. Consolidating them into a single curated `test-quality-log.md` gives future agents
one canonical place to check before making tag or tier decisions. The file stays in
`.local/docs/` (gitignored — machine-specific observations, not portable).

**What the new file must contain (in this order):**

```
# fukuii test-quality-log

## Tier baselines

### testEssential (Tier 1)
Table: Date | Wall time | Tests | Failures | Notes
(migrate from current fukuii-test-timing.md Current baseline table)

### testStandard (Tier 2)
Table: Date | Wall time | Tests | Failures | Notes
(migrate from current fukuii-test-timing.md testStandard baseline table)

### testComprehensive (Tier 3)
Table: Date | Wall time | Tests | Failures | Notes
(no entries yet — placeholder)

## Top slowest tests

### testEssential top 10 (2026-06-22 run)
(migrate from fukuii-test-timing.md "Slow tests >2s" table — add Spec class column)

### testStandard additions (2026-06-23 run, P11)
Any tests that appear in testStandard but not testEssential that take >5s
(derive from P11 log or leave as TODO if not available)

## Known flakes

Table: Spec | Test | Failure mode | Root cause | Status
- DnsDiscoverySpec | "should resolve enodes from Mordor DNS tree" | `9 >= 10` assertion | Live Mordor DNS peer count near threshold — network-dependent | OPEN: raise threshold or widen retry
- EthMiningServiceSpec | multiple | AskTimeoutException (20s/60s timeouts) | TestProbe never receives ask reply under JVM load | OPEN: tracked in §8j

## SlowTest audit history

Table: Date | Prompt | Spec | Test | Observed time | Decision | SHA
(populate from P11 2026-06-23 audit findings)

Rows to add:
- 2026-06-23 | P11 | MiningSpec | "have unique names" | 17ms | SlowTest→UnitTest | edfb69f35
- 2026-06-23 | P11 | MiningSpec | "contain ethash" | 0ms | SlowTest→UnitTest | edfb69f35
- 2026-06-23 | P11 | PoWMiningSpec | "use RestrictedPoWBlockGeneratorImpl..." | 56ms | SlowTest→UnitTest | edfb69f35
- 2026-06-23 | P11 | PoWMiningSpec | "start only one mocked miner...MockedPow" | 56ms | SlowTest→UnitTest | edfb69f35
- 2026-06-23 | P11 | PoWMiningSpec | "start only the normal miner...PoW" | 50ms | SlowTest→UnitTest | edfb69f35
- 2026-06-23 | P11 | PoWMiningSpec | "start only the normal miner...RestrictedPoW" | 40ms | SlowTest→UnitTest | edfb69f35
- 2026-06-23 | P11 | PoWMiningSpec | "use NoAdditionalPoWData..." | 202ms | kept SlowTest | — (actor system init on first test)
- 2026-06-23 | P11 | PoWMiningSpec | "not start a miner when miningEnabled=false" | 425ms | kept SlowTest | — (TestMiningNode init)

## Infrastructure traps

Patterns that inflate per-test timing and can cause mislabelling:

- **ScalaTestWithActorTestKit first-test overhead**: The first test in a class that extends
  `ScalaTestWithActorTestKit` pays Pekko actor system initialization (~150-250ms under warm JVM).
  Subsequent tests in the same class are much faster (40-60ms). Use `-oD` timing across the
  full class, not just the first test, when deciding whether to remove SlowTest.
- **TestMiningNode initialization**: Tests that call `startProtocol(new TestMiningNode())` pay
  heavy setup cost (~400ms) because `TestMiningNode extends StdNode with EphemBlockchainTestSetup`.
  This is not mining computation — it is node bootstrap overhead. Tests with this pattern are
  legitimately SlowTest even though no PoW occurs.
- **ScalaMock stub teardown noise**: Tests that spawn coordinator actors with mocked dependencies
  produce ERROR log lines after teardown when the coordinator calls a mock that has already been
  verified. These are NOT test failures — they are expected cleanup noise.

## How to measure: SlowTest calibration

To measure per-test timings before making tag decisions:
```bash
sbt "testOnly <fully.qualified.SpecClass> -- -oD"
```
The `-oD` flag makes ScalaTest print each test's duration in milliseconds.
Only remove SlowTest if the observed time is <100ms. Do not guess from suite-level totals.
DAGGenerationSpec and EthashNonceSearchSpec must always remain SlowTest (CPU-bound PoW).

## Wall-clock assertion inventory

(migrate from fukuii-test-timing.md — keep as-is)

## Thread.sleep inventory

(migrate from fukuii-test-timing.md — keep as-is)
```

**Steps:**

1. Read `.local/docs/fukuii-test-timing.md` in full.
2. Create `.local/docs/test-quality-log.md` using the structure above. Migrate all content
   from `fukuii-test-timing.md` into the appropriate sections. Do not truncate or summarise
   existing data — move it verbatim then add the new sections around it.
3. Delete `.local/docs/fukuii-test-timing.md`.
4. Update every reference to `fukuii-test-timing.md` in DEFERRED-BACKLOG.md — replace with
   `test-quality-log.md`. Do NOT rename `/tmp/fukuii-test-timing.log` references (lines that
   reference a temp log path, not the persistent doc). Affected lines: 1610, 1743, 1877, 1930,
   1940, 2102, 2103, 2104 (verify by grep — line numbers may shift after this prompt is
   inserted).
   ```bash
   grep -n "fukuii-test-timing\.md" .claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md
   ```
5. Update `~/.claude/projects/-media-dev-2tb-dev/memory/MEMORY.md`: change the
   `fukuii-test-timing.md` entry to point at `test-quality-log.md` and update the description.
6. Update `~/.claude/projects/-media-dev-2tb-dev/memory/fukuii-test-timing.md`: rename to
   `fukuii-test-quality-log.md`, update `name:`, `description:`, and body to reflect the new
   file and its expanded scope.
7. Update `~/.claude/projects/-media-dev-2tb-dev/memory/feedback_test_visibility.md`: change
   the `[[fukuii-test-timing]]` link to `[[fukuii-test-quality-log]]`.
8. Verify no remaining references to the old filename:
   ```bash
   grep -rn "fukuii-test-timing\.md" \
     .claude/agent-protocols/working-docs/ \
     ~/.claude/projects/-media-dev-2tb-dev/memory/
   ```
   Expected output: zero results.
9. Commit:
   ```bash
   git add .claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md
   git commit -m "docs(p11b): migrate fukuii-test-timing.md → test-quality-log.md"
   SHA=$(git rev-parse --short HEAD)
   ```
10. Update CODEBASE-AUDIT.md run-order table — add and immediately strike through:
    `| ~~P11b~~ | ~~Batch G~~ | ~~Docs: migrate fukuii-test-timing.md → test-quality-log.md~~ | ✅ DONE [date] — $SHA |`
11. Stage and commit docs:
    ```bash
    git add .claude/agent-protocols/working-docs/CODEBASE-AUDIT.md
    git commit -m "docs(p11b): clearout — $SHA"
    ```

**Rejection criteria:** Symlinking the old name to the new file — clean delete only. Truncating
or summarising existing data from `fukuii-test-timing.md` instead of migrating it verbatim.
Renaming `/tmp/fukuii-test-timing.log` references (temp paths, not the persistent doc).
Missing any of the 8 `fukuii-test-timing.md` references in DEFERRED-BACKLOG.md.

---

### P12 — MITHRIL: Tag taxonomy + build target architecture review + gaps

**Agent:** MITHRIL (read-only analysis → build.sbt edits for new targets)
**Prerequisite:** P8, P9, P10 complete (stable tag counts before auditing the architecture).
**Parallel-safe:** Yes (read-only except for new `addCommandAlias` additions).

**Architectural principle (read this first):**

> Test groupings must reflect reality, not hide failures. An exclusion tag is only legitimate
> when the test is *correctly categorised* as non-essential (too slow, genuine live-network
> requirement, compliance-only). An exclusion that exists because a test is *broken* is a
> workaround that buries technical debt. `testEssential` must include everything that is
> essential — if it's too slow for the essential gate, create a `testFast` tier for speed,
> not an exclusion that erases the test from CI entirely.

**Legitimate exclusions (keep):**
- `SlowTest` from `testEssential` — correct: too slow for daily commit gate; runs in Standard
- `BenchmarkTest` / `EthereumTest` from Essential+Standard — correct: 3-hour compliance suite
- `IntegrationTest` from `testEssential` — assess: may belong there if actor system tests are fast enough

**Workaround exclusions (must go to zero after P8–P10):**
- `SyncTest` from ALL tiers — workaround for broken/flaky sync tests; not a categorisation
- `DisabledTest` from ALL tiers — workaround for broken tests that need fixing or deleting
- `FlakyTest` from ALL tiers — workaround for non-deterministic tests that need fixing or deleting

**Context:** The tag system has grown organically. After P8–P10 clean up individual tests,
this prompt steps back and asks: is the *architecture* right? Four questions:

1. **Tag coverage gaps** — Which tags defined in `Tags.scala` have no corresponding `sbt`
   inclusion command? (`ConsensusTest`, `StateTest`, `RPCTest`, `OlympiaTest`, etc.)
2. **Tier completeness** — Are all tiers running the right submodule tests? (`testStandard`
   skips `rlp / test`, `bytes / test`, `crypto / test` — is that intentional?)
3. **Missing tiers / new build targets** — Should new tiers exist? What would `testConsensus`,
   `testRPC`, `testMining` enable? Should `testFast` exist as a <2-min subset of testEssential
   for pre-commit hooks, freeing `testEssential` to be truly comprehensive?
4. **Workaround exclusion removal** — Capstone of P8+P10. Remove every workaround exclusion
   from the tier definitions. `testEssential` must include all essential tests. Sync is
   essential. RPC is essential. State is essential. If a test in those domains is currently
   excluded because it's broken, fixing it (P8/P9/P10) is the prerequisite — not permanent
   exclusion.

**Known gaps to assess:**

| Issue | Current state | Proposed fix |
|-------|--------------|--------------|
| **`-l SyncTest` in ALL tiers** | Core sync logic has zero CI coverage — a workaround, not a design | **Remove from all tiers** once P8+P10 complete; sync tests belong in testEssential |
| `ConsensusTest` tag — no sbt target | Tests included in tiers but can't be run in isolation | Add `testConsensus` alias: `-n ConsensusTest` |
| `RPCTest` tag — no sbt target | Same problem for JSON-RPC layer tests | Add `testRPC` alias: `-n RPCTest` |
| `StateTest` tag — no sbt target | Same for state management | Add `testState` alias: `-n StateTest` |
| `OlympiaTest` tag — no sbt target | Fork-specific tests can't be run in isolation | Add `testOlympia` alias: `-n OlympiaTest` |
| `testStandard` skips rlp/bytes/crypto submodules | Possible coverage gap | Assess whether these submodule tests are covered elsewhere |
| `testMining` — no dedicated target | Ethash/PoW tests scattered across SlowTest+ConsensusTest | Consider adding `testMining` alias for CI on mining-heavy PRs |
| `StressTest` / `ManualTest` — defined but unused | No tests use them, no commands reference them | Assess: forward-declared or dead tag definitions? |
| `testAll` runs bare `test` without submodule isolation | `testAll` and `testComprehensive` overlap | Confirm no double-execution or gaps |

**Steps:**

0. **Exclusion legitimacy audit** — For every `-l TAG` in every tier definition, classify:
   ```
   testEssential excludes:  SlowTest | IntegrationTest | SyncTest | DisabledTest | FlakyTest
   testStandard excludes:   BenchmarkTest | EthereumTest | SyncTest | DisabledTest | FlakyTest
   testComprehensive excludes: SyncTest | FlakyTest | DisabledTest
   ```
   For each: answer "Is this exclusion *categorisation* (the test genuinely does not belong
   here) or *workaround* (the test belongs here but is broken)?"

   | Tag excluded | Type | Verdict after P8–P10 |
   |-------------|------|----------------------|
   | `SlowTest` from testEssential | Categorisation | Keep — legitimately slow |
   | `IntegrationTest` from testEssential | Assess | May belong; actor tests can be hermetic |
   | `BenchmarkTest` from Essential+Standard | Categorisation | Keep — 3h compliance suite |
   | `EthereumTest` from Essential+Standard | Categorisation | Keep — 3h compliance suite |
   | `SyncTest` from ALL tiers | **Workaround** | **Remove after P8+P10** |
   | `DisabledTest` from ALL tiers | **Workaround** | **Remove after P9 (no tests left disabled)** |
   | `FlakyTest` from ALL tiers | **Workaround** | **Remove after P10 (no tests left flaky)** |

   The end state: `testEssential` excludes ONLY `SlowTest`, `BenchmarkTest`, `EthereumTest`
   (legitimate speed categorisations), nothing else. `FlakyTest`, `DisabledTest`, `SyncTest`
   disappear from the exclusion lists because no tests carry those tags any more.

   If `IntegrationTest` from `testEssential` is also a workaround (actor tests that are actually
   fast and hermetic), reschedule those tests too.

1. **Tag usage census** — For each tag in `Tags.scala`, count actual usages:
   ```bash
   for tag in UnitTest FastTest IntegrationTest SlowTest EthereumTest BenchmarkTest StressTest CryptoTest RLPTest VMTest NetworkTest MPTTest StateTest ConsensusTest RPCTest DatabaseTest SyncTest FlakyTest DisabledTest ManualTest EthSmoke OlympiaTest; do
     count=$(grep -rn "taggedAs.*$tag\|$tag," src/test/ --include="*.scala" | grep -v "import\|object $tag\|//\|class.*Tag" | wc -l)
     echo "$tag: $count"
   done
   ```

2. **Submodule test coverage** — Run each submodule standalone and check counts:
   ```bash
   sbt "rlp / test" 2>&1 | grep "Tests:" | tail -1
   sbt "bytes / test" 2>&1 | grep "Tests:" | tail -1
   sbt "crypto / test" 2>&1 | grep "Tests:" | tail -1
   ```
   Confirm these are covered by `testEssential` (they are — lines 526-528) but NOT by
   `testStandard` (line 540 — only `testOnly` without submodule prefix). Document this gap.

3. **New build targets** — For each proposed new alias, verify the tag is actually used by
   enough tests to warrant a dedicated command (threshold: ≥3 tests). Propose the `addCommandAlias`
   line for each justified target.

4. **Tier gap fix proposal** — For `testStandard`, determine if adding submodule test runs is
   needed or if the existing `testEssential` coverage is sufficient. If a gap exists, propose
   adding `; rlp / test ; bytes / test ; crypto / test` to `testStandard`.

5. **Dead tag removal** — If `StressTest` or `ManualTest` have 0 usages, either:
   - Remove from `Tags.scala` if no near-term plan to use them (clean up dead definitions)
   - Add a comment in `Tags.scala` marking them as "reserved for future use" if there's a plan

6. **Write a tag taxonomy document** at `fukuii/.local/docs/test-tag-taxonomy.md`:
   ```markdown
   # Test Tag Taxonomy
   | Tag | Usage count | sbt command | Tier coverage | Notes |
   ```
   One row per tag. This becomes the authoritative reference for tagging new tests.

7. **Remove `-l SyncTest` from all tier definitions** (capstone step — only after P8+P10 complete):
   - Confirm zero SyncTest tests remain without a proper tier tag (UnitTest or IntegrationTest).
   - Edit `build.sbt` lines 525, 540, 557: remove `-l SyncTest` from each `testOnly` invocation.
   - Remove the build.sbt comment block explaining the exclusion (the workaround is gone).
   - Run `testEssential` to confirm the newly-included sync tests pass and the count is correct.
   - Commit: `"build: remove -l SyncTest exclusion — sync tests now in testEssential (P8+P10 complete)"`

8. **Add justified `addCommandAlias` entries** to `build.sbt` — place after the existing domain
   aliases. Include a comment explaining the purpose of each.

**Target end state for `testEssential` in `build.sbt`:**
```scala
// BEFORE (workarounds present):
testOnly -- -l SlowTest -l IntegrationTest -l SyncTest -l DisabledTest -l FlakyTest

// AFTER (only legitimate categorisations remain):
testOnly -- -l SlowTest -l BenchmarkTest -l EthereumTest
```
If `IntegrationTest` is also found to be a workaround (actor tests that are hermetic and fast),
it is removed too. If a `testFast` tier is warranted (e.g., pre-commit hook, <2 min), it is
added as a new alias — not as a reason to exclude essential tests from `testEssential`.

**Verification:** `sbt compile-all` clean after all `build.sbt` edits. `testEssential` passes
with sync tests included and a higher test count than the P7 baseline. `sbt testConsensus` (and
other new targets) each find ≥3 tests. Zero `-l SyncTest`, `-l DisabledTest`, `-l FlakyTest`
lines remain in any tier definition.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add build.sbt` → `git commit -m "build(p12): add domain test targets (testConsensus, testCrypto, testVM, testNetwork)"` — commit 1: new aliases only
3. `SHA1=$(git rev-parse --short HEAD)` — capture SHA for commit 1
4. `git add build.sbt <any test files re-tagged>` → `git commit -m "build(p12): remove workaround exclusions from testEssential — SyncTest/DisabledTest/FlakyTest"` — commit 2: exclusion removal (only after P8+P10 complete)
5. `SHA2=$(git rev-parse --short HEAD)` — capture SHA for commit 2
6. Update run-order table in `CODEBASE-AUDIT.md`: strikethrough E5 → `| ~~E5~~ | ... | ✅ DONE [date] — $SHA1 (aliases), $SHA2 (exclusion removal) |`
7. Write `test-tag-taxonomy.md` at `.local/docs/`
8. Update `test-quality-log.md` with new testEssential count and the clean exclusion list
9. Update MEMORY.md `test-quality-log.md` entry with final test count
10. `git add .claude/ .local/docs/test-tag-taxonomy.md .local/docs/test-quality-log.md` → `git commit -m "docs(p12): clearout — $SHA1 $SHA2"`

**Rejection criteria:** Removing a workaround exclusion before the underlying tests are fixed
(P8+P10 must be complete first). Adding a build target for a tag with 0–1 test usages.
Introducing a new exclusion that is a workaround — if a test is broken, fix or delete it,
do not exclude it. Tiers must reflect reality.

---

## Part 12: Pre-Olympia Consensus Correctness Gate

### §G5 — BlockExecution.applyEip2935 account-existence gap (BEACON + FORGE)

**Source:** CHASE-QUEUE `BlockExecution.applyEip2935` entry (cleared 2026-06-21, routed here)
**Branch:** Any post-Olympia-gated branch
**Risk:** MEDIUM — consensus-adjacent storage write; pre-Olympia correctness gap; Hive compliance blocker

**Background:**

`BlockExecution.applyEip2935` writes to `HistoryStorageAddress` storage without first
guaranteeing the account exists. The parallel method `applyEip4788` does create the account
if absent before writing. Currently masked on real ETC mainnet by deployment order (the
`HistoryStorageAddress` account pre-exists at activation block), but:

1. **Hive compliance:** EIP-2935 Hive tests construct `emptyWorld` + post-activation block;
   the absent-account path hits `getGuaranteedAccount` → `IllegalStateException` → test failure.
2. **Test construction trap:** Any `BlockHashHistorySpec` scenario starting from an empty world
   after the activation block will silently fail to write or throw.
3. **Olympia activation risk:** If activation block ordering or genesis conditions ever shift,
   the storage write silently fails or corrupts state (storage on a non-existent account).

**Current code pattern** (analogous to applyEip4788 — read both before touching either):
```bash
grep -n "applyEip2935\|applyEip4788\|HistoryStorageAddress\|BlockHashHistory" \
  src/main/scala/io/iohk/ethereum/blockchain/ledger/BlockExecution.scala
```

**Fix (FORGE + BEACON reviewed verdict — do not implement without confirmation):**
- Drop `isActivationBlock &&` from the `w1` branch condition so the account-existence guard
  runs on every post-activation block (not just the activation block itself)
- OR adopt the same "create if absent" guard pattern used in `applyEip4788`
- Exact approach must be confirmed with FORGE (ETC/Olympia) + BEACON (EIP-2935 spec)

**New test required:** `BlockHashHistorySpec` absent-account scenario:
```scala
// Test pattern: emptyWorld + post-activation block → storage write succeeds + account exists
// Verify: no IllegalStateException, HistoryStorageAddress account exists after call
// Mirrors: existing applyEip4788 test coverage pattern
```

**Gate condition:** BEACON review (EIP-2935 spec compliance) + FORGE review (ETC/Olympia
activation block semantics) BOTH required before any code change. This touches consensus
ledger logic and both chains are affected.

**Owner:** BEACON + FORGE — do not delegate to MITHRIL or WRAITH alone.

**Priority:** HIGH — Hive EIP-2935 compliance blocker for Olympia acceptance testing.
Handle before any Hive ETC Olympia test suite run.

**MANDATORY final step — complete IN THIS ORDER:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../ledger/BlockExecution.scala src/test/scala/.../ledger/BlockHashHistorySpec.scala` — stage only the two files changed
3. `git commit -m "fix(ledger): applyEip2935 account-existence guard — match applyEip4788 pattern (Part 12 §G5)"`
4. `SHA=$(git rev-parse --short HEAD)` — capture exact SHA
5. `./local/scripts/fukuii-test` → confirm 3,595+ tests, 0 failures; record timing
6. Add to `CHASE-QUEUE.md` cleared entries log: `| BlockExecution.applyEip2935 Part 12 §G5 | Cleared [date]: $SHA — account-existence guard added; BlockHashHistorySpec absent-account test added |`
7. `git add .claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md .claude/agent-protocols/working-docs/CHASE-QUEUE.md` → `git commit -m "docs(part12-g5): clearout — $SHA"`
8. DELETE this section

---

## Part 15: P9 DisabledTest Deferred — Resolution Prompts

Deferred during P9 audit (2026-06-23, commit `86c76fd4e`). Four targeted fixes listed below.
The `handleRegularSyncMsg` production bug (SyncController:895-897) is tracked under P10 (F7).

---

## Part 16: ETH69/ETH70 PoW Safety — Hardening (P1/P2)

**Source:** Wire Protocol audit 2026-06-23 — `.local/Wire-Protocol-Modernization/eth69-pow-safety-audit.md`
**P0 items** (G1 TD gate, G5 backlink) → `SPRINT-QUEUE.md §ETH69-A/B` — implement first.
**P1 items** (G2 Tier3 accuracy, G6 BRU) and **P2 items** (G3/G4 archive node) tracked here.
**Gate:** §ETH69-A and §ETH69-B must be complete before hardening items are useful.

---

### §ETH69-C — MITHRIL: BlockchainReader Tier3 rolling-window median (G2, P1)

**Agent:** MITHRIL
**Risk:** MEDIUM — changes Tier3 TD estimation formula; affects peer ranking and chainWeight accuracy
**Gate:** §ETH69-A complete (TD gate in place) + §ETH69-B complete
**File:** `src/main/scala/com/chipprbots/ethereum/domain/BlockchainReader.scala:217-221`

**Background:**
Tier3 `POW_SCALING` uses current head difficulty as the marginal rate in:
```scala
val rate = rollingWindowDiff(head, ourBestTD)  // 10K-block rolling avg, fallback to head.difficulty
val gap  = (latestBlock - ourBestNum).max(BigInt(0))
val estimatedTD = ourBestTD + rate * gap
```
Under flex-load difficulty oscillation (±50% swing observed in `ETH69OscillationChainWeightSpec.scala:142-156`),
this causes Tier3 to systematically overestimate or underestimate TD by 10-50%. Archive nodes
receiving an inflated Tier3 estimate are never corrected (monotonic guard + no NewBlock sent).
Peers arriving mid-oscillation trough are deprioritised unfairly.

**Steps:**
1. **Read** `BlockchainReader.scala:199-248` in full — understand `rollingWindowDiff`,
   `resolveETH69ChainWeight`, and what `head` / `ourBestTD` are.
2. **Read** `ETH69OscillationChainWeightSpec.scala` — understand existing oscillation test cases
   and what accuracy targets they assert.
3. **Implement** a 1,000-block rolling-median difficulty store:
   - On each new block import (hook into the existing block-update path), record
     `header.difficulty` in a ring buffer of size 1,000.
   - Expose `rollingMedianDifficulty: BigInt` as a new `BlockchainReader` method.
   - Replace `rollingWindowDiff(head, ourBestTD)` call in Tier3 with `rollingMedianDifficulty`
     (fallback to `head.difficulty` if buffer is not yet full).
4. **Add test cases** to `ETH69OscillationChainWeightSpec`:
   - Verify Tier3 estimate variance is < ±20% under 50% oscillation (rolling median dampens swings)
   - Verify rolling median correctly averages out high/low difficulty alternation
5. `sbt "testOnly *BlockchainReader* *ETH69*"` after changes.

**Verify:**
```bash
grep -n "rollingWindowDiff\|rollingMedian\|rate = " \
  src/main/scala/com/chipprbots/ethereum/domain/BlockchainReader.scala

sbt "testOnly *ETH69Oscillation*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../domain/BlockchainReader.scala src/test/.../ETH69OscillationChainWeightSpec.scala`
3. `git commit -m "fix(sync): ETH69 Tier3 POW_SCALING — rolling-median difficulty reduces estimate variance (G2)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(eth69-c): clearout — $SHA"`
5. **DELETE §ETH69-C**

---

### §ETH69-D — MITHRIL: Tier3 accuracy telemetry (P1)

**Agent:** MITHRIL
**Risk:** LOW — instrumentation only, no logic change
**Gate:** §ETH69-A complete
**File:** `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala:794-799`

**Background:**
When an ETH69 peer sends a `NewBlock`, `updateChainWeight` directly replaces the peer's
chainWeight with the NewBlock TD — no monotonic guard. This is the only moment where the
**actual** TD is revealed after a Tier3 estimate. Capturing the estimate-vs-actual delta
at this point provides the data needed to tune the Tier3 formula and detect systematic bias.

**Steps:**
1. **Read** `NetworkPeerManagerActor.scala:789-810` — understand `updateChainWeight` and how
   `NewBlock.totalDifficulty` and `initialPeerInfo.chainWeight` are accessible together.
2. **Add logging** at the point of NewBlock TD replacement (inside `updateChainWeight`):
   ```scala
   case newBlock: ETHPackets.NewBlock =>
     val prevTD      = initialPeerInfo.chainWeight.totalDifficulty
     val actualTD    = newBlock.totalDifficulty
     val delta       = actualTD - prevTD
     val deltaPercent = if (prevTD > 0) (delta * 100) / prevTD else BigInt(0)
     log.debug(
       "ETH69_TIER3_ACCURACY: peer={} prevTD={} actualTD={} delta={} deltaPercent={}%",
       initialPeerInfo.remoteStatus.bestHash,
       prevTD, actualTD, delta, deltaPercent
     )
     initialPeerInfo.copy(chainWeight = ChainWeight.totalDifficultyOnly(newBlock.totalDifficulty))
   ```
3. **No test change required** — this is debug logging only. Confirm it compiles.
4. `sbt compile-all` to confirm no errors.

**Verify:**
```bash
grep -n "ETH69_TIER3_ACCURACY\|deltaPercent" \
  src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala
# Must see the log line

sbt compile-all
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../network/NetworkPeerManagerActor.scala`
3. `git commit -m "feat(telemetry): ETH69 Tier3 estimate-vs-actual TD logging on NewBlock (G2 instrumentation)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(eth69-d): clearout — $SHA"`
5. **DELETE §ETH69-D**

---

### §ETH69-E — MITHRIL: Archive node monotonic guard exemption (G3/G4, P2)

**Agent:** MITHRIL
**Risk:** LOW — refinement to chainWeight update guard; no consensus impact
**Gate:** §ETH69-C complete (rolling-median in place provides more stable Tier3 before disabling guard)
**Files:** `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala:841-851, 335-366`

**Background:**
Non-mining peers (archive nodes, light-mode relayers) on ETH69 never emit NewBlock.
Their chainWeight is set at handshake via Tier3 POW_SCALING and can only be updated via
the periodic `RefreshPeerBestBlocksTick` path (every ~5 min, lines 335-366), which calls
`resolveETH69ChainWeight` and applies:
```scala
val isImprovement = cw.totalDifficulty > updated.chainWeight.totalDifficulty
if isImprovement && source != "COLD_START" then updated.withChainWeight(cw)
else updated
```
The monotonic guard (`isImprovement`) prevents downward corrections. If Tier3 overestimated
at handshake (e.g., peer arrived during a difficulty spike), the chainWeight is permanently
inflated for archive nodes. They are ranked higher than honest active peers in some contexts.

**Steps:**
1. **Read** `NetworkPeerManagerActor.scala:823-867` (`updateMaxBlock` function) in full.
2. **Read** `NetworkPeerManagerActor.scala:335-366` (`RefreshPeerBestBlocksTick` path).
3. **Implement archive node detection:** Track `lastMaxBlockNumber` per peer across
   consecutive `RefreshPeerBestBlocksTick` probes. If `maxBlockNumber` has not advanced
   in N consecutive probes (N=3, configurable), classify peer as "static" (not mining).
4. **Exempt static peers from the monotonic guard** in the Tier3 re-resolve path:
   ```scala
   val isPeerStatic = consecutiveUnchangedProbes(peerId) >= 3
   val shouldUpdate = (isImprovement || isPeerStatic) && source != "COLD_START"
   if shouldUpdate then updated.withChainWeight(cw)
   else updated
   ```
5. **Write tests** in NetworkPeerManagerActorSpec:
   - Mining peer (maxBlockNumber advances each probe) → monotonic guard active
   - Archive peer (maxBlockNumber unchanged 3× probes) → downward Tier3 correction allowed
   - Archive peer corrects from inflated Tier3 estimate to accurate actual TD

**Verify:**
```bash
grep -n "isImprovement\|consecutiveUnchanged\|isPeerStatic" \
  src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala

sbt "testOnly *NetworkPeerManager*"
./local/scripts/fukuii-test
```

**MANDATORY final steps:**
1. `sbt scalafmtAll`
2. `git add src/main/scala/.../network/NetworkPeerManagerActor.scala src/test/.../NetworkPeerManagerActorSpec.scala`
3. `git commit -m "fix(sync): ETH69 archive node Tier3 chainWeight — exempt static peers from monotonic guard (G3/G4)"`
4. `SHA=$(git rev-parse --short HEAD)` → `git commit -m "docs(eth69-e): clearout — $SHA"`
5. **DELETE §ETH69-E**
