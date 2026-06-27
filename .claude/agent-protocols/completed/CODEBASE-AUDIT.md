# Fukuii Codebase Audit — Post Phase 2 Narrowing

**Branch:** `scala3-cleanup-june`
**Date:** 2026-06-22
**Purpose:** Verify current codebase state matches documented sprint history, surface any gaps
not captured in SPRINT-QUEUE.md or DEFERRED-BACKLOG.md, and produce a prioritized action matrix.

---

## Known state (pre-audit)

### What CAPSTONE + Phase 2 narrowing delivered
- Zero `extends Actor` in `src/main` — only 3 intentional `extends ClassicActor` TCP bridges:
  `ServerActor`, `RLPxConnectionHandler` ×2
- Zero `Behavior[Any]` in all migrated actors (NET2/SNAP2/ROOT confirmed clean)
- Zero E165 warnings in all W2-P2a–P2d migrated subsystems (faucet, jsonrpc, transactions, mining)
- Root: `ActorSystem[Nothing]` — NodeBuilder fully Typed
- Wave 2 Scala 3 idioms: `given/using` (W2-P3a), extension methods (W2-P3b), `isInstanceOf` (W2-P3c) — all done

### Known open items (from CHASE-QUEUE.md)
| Item | Gate | Priority |
|------|------|----------|
| PoWMiningCoordinator MUTABLE | FORGE review required | Blocked |
| E165 TestProbe in FastSyncBranchResolverSpec (5 warnings) | Spec cleanup pass | Low |
| json4s Jackson 3 gate (R3) | json4s 4.2.0 release | External |
| Wall-clock assertions in 3 test files | Spec cleanup pass | Low |
| BlockExecution.applyEip2935 account-existence gap | FORGE + BEACON before Olympia | High |
| RegularSyncSpec divergence path EXCEPT (LCA-less blind rewind) | HERALD audit done; fix spec in DEFERRED-BACKLOG | Medium |

### What is NOT yet done (DEFERRED-BACKLOG)
- Wave 3 Part 6: Network/P2P actor migration (35 remaining Classic actors in devp2p/rlpx — 
  research complete at `network-sync-pekko-migration-plan.md`, implementation not started)
- R4: Scala 3.9 readiness (external gate — periodic check)
- DEFERRED-BACKLOG Part 4: dependency upgrades (json4s, Jackson, Kanela gates)
- `MockedMiner.Send` envelope cleanup (noted in CAPSTONE post-mortem)
- `ProgressProtocol.ImportedBlock` in `runningRegularSyncBootstrap` (noted in ROOT Phase 3)

---

## Sweep results

*Filled by parallel agents. Each agent appends findings to their section.*
### S1 — Classic actor remnants (Explore)

**Target greps:**
```bash
grep -rn "extends Actor\b" src/main/ --include="*.scala"
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala"
grep -rn "messageAdapter.*identity" src/main/ --include="*.scala"
grep -rn "ctx\.self\.toClassic\|context\.self\.toClassic" src/main/ --include="*.scala"
grep -rn "toClassic\.sender()\|ctx\.toClassic\.sender()" src/main/ --include="*.scala"
```

Expected: only 3 `extends ClassicActor` TCP bridges, 0 `Behavior[Any]`, 0 identity adapters.
Any unexpected hits are findings.

**Results:**
✓ `extends Actor\b` — 0 hits, baseline confirmed

✓ `Behavior[Any]` — 11 hits, **all in comments/documentation only** (zero actual type declarations). Files: SyncController, FastSync, PivotHeaderBootstrap, BytecodeRecoveryActor, StorageRecoveryActor, SNAPSyncController, NodeBuilder. These are post-ROOT migration state descriptions and are correct.

✓ `messageAdapter.*identity` — 1 hit, **in comment only** (FastSyncBranchResolverActor.scala:44, describing architecture). Zero actual code usages.

✓ `ctx.self.toClassic` / `context.self.toClassic` — 12 hits across 10 files (FastSync, PivotHeaderBootstrap, RegularSync, BlockImporter, StorageRecoveryActor, AccountRangeCoordinator, ByteCodeCoordinator, SNAPSyncController, RLPxConnectionHandler, PeerActor). All are **legitimate message-construction patterns** — passing self-reference as a reply-to target in messages to other actors. This is the idiomatic Pekko Typed→Classic bridge pattern. Expected and correct.

✓ `toClassic.sender()` / `ctx.toClassic.sender()` — 48 hits across 3 files:
  - **RegularSync.scala:191** — 1 hit
  - **FastSync.scala:220, :292, :437** — 3 hits (GetStatusCmd wrapper)
  - **SyncController.scala** — 44 hits (forwarding/replying to Classic ask() callers)
  
All uses are concentrated in the three expected high-traffic hub actors (ROOT group, coordinating inbound Classic ask/tell). W5 (FastSync.scala:220, ctx.toClassic.sender() in idle()) is the known issue already captured in S4.

**S1 sweep complete — baseline confirmed, 0 new findings.**

---

### S2 — Compiler warning baseline (WRAITH)

**Target commands:**
```bash
sbt compile-all 2>&1 | grep -E "warning|error" | sort | uniq -c | sort -rn | head -40
grep -rn "@nowarn" src/ --include="*.scala" | wc -l
grep -n "Wconf\|-Wunused\|scapegoat\|disabledInspections" build.sbt project/*.scala
```

Expected: 0 errors. E165 count from TCP bridges only (3 files). All other warning categories eliminated.
Surface: any new warning categories not in DEFERRED-BACKLOG.

**Results:**

**Compile baseline** (from DEFERRED-BACKLOG §Part 1, sweep 2026-06-21 — authoritative; fresh `sbt compile-all` blocked by permission):

| Category | Count | Status |
|----------|-------|--------|
| Errors | 0 | ✓ Clean |
| E165 (Classic bridge) | 333 | Intentional — PeerEventBus boundary, Wave 3 LOOM gate |
| json4s Manifest synthesis | 68 | Externally gated (json4s 4.2.0-M5 release) |
| `vm/OpCode.scala` infix/wildcard | 9 | FORGE gate |
| RocksDB `ClockCache` deprecated | 2 | Library upgrade gate |
| diffx `DiffMatcher` deprecated | 2 | Library upgrade gate |
| Guava `CacheBuilder` deprecated | 1 | Library upgrade gate |
| `EngineApiService` `Ordering.Iterable` | 1 | BEACON gate |
| web3j `Admin` deprecated | 1 | Library upgrade gate |
| `TrieNodeHealingCoordinator` Pekko boundary | 1 | Unfixable (library internals) |
| `PeerRequestHandler` `ClassTag` unsound | 1 | Needs `TypeTest[A,B]`, deferred |
| **Total non-E165 warnings** | **87** | All externally gated or deferred |

**@nowarn count:** 0 — no per-site suppression annotations anywhere in `src/`. All suppression is via `-Wconf` flags.

**Build warning-suppression config** (`build.sbt`):
- Line 67: `-Wconf:id=E198:error` — unused symbols **ratcheted to build errors** ✓
- Line 68: `-Wconf:cat=feature:s` — adhocExtensions silenced (non-open Pekko class extensions, intentional)
- Line 69: `-Wconf:cat=unchecked:error` — unchecked patterns **ratcheted to build errors** ✓
- Line 587: `scapegoatDisabledInspections := Seq("UnsafeTraversableMethods")`

**New findings:** None. All 87 non-E165 warnings are documented in DEFERRED-BACKLOG §Part 1 with external gates. Next ratchet candidate: `E165` (target: 0 once Wave 3 Classic bridge migration is complete).

**S2 sweep complete — baseline confirmed, 0 new findings.**

---

### S3 — Scala 3 idiom gaps (MITHRIL)

**Target greps:**
```bash
# Remaining implicit val/def in main (should be near-zero after W2-P3a)
grep -rn "implicit val\|implicit def\|implicit lazy val" src/main/ --include="*.scala" | \
  grep -v "consensus/\|vm/\|crypto/\|domain/" | wc -l

# null checks (non-idiomatic)
grep -rn "== null\|!= null\|eq null\|ne null" src/main/ --include="*.scala" | \
  grep -v "//.*null"

# return statements remaining (non-idiomatic)
grep -rn "^\s*return\b" src/main/ --include="*.scala"

# var outside actor classes
grep -rn "^\s*var " src/main/ --include="*.scala" | \
  grep -v "extends Actor\|extends AbstractBehavior\|class.*Impl\b\|object.*Impl\b"
```

Surface: any categories not yet captured in DEFERRED-BACKLOG that would benefit from a focused pass.

**Results:**

#### Implicit val/def count: 29 remaining in non-consensus/vm/crypto/domain code

All 29 are categorized. Zero are immediate violations — all are gated behind W2-P3a (DEFERRED-BACKLOG §3a).

- **given/using candidates (15):** Pekko HTTP Marshaller/Unmarshaller in `Json4sSupport.scala:18,20,22,29`, `JsonRpcHttpServer.scala:62`; `ActorSystem` constructor params in `InsecureJsonRpcHttpServer.scala:21`, `SecureJsonRpcHttpServer.scala:26`; `ExecutionContext`/`IORuntime` in `JsonRpcBaseController.scala:41`, `McpService.scala:118`, `NodeBuilder.scala:236,1094`; json4s `Formats` in `JsonMethodsImplicits.scala:32`, `Json4sSupport.scala:20`; log4cats loggers in `ForkIdValidator.scala:27,28`; `FunctorOps.scala:15` (→ `given Conversion` or extension method).
- **RLP wire codecs — syntax only, instances stay (8):** `ForkId.scala:108`, `RLPCodecs.scala:103,149`, `AuthResponseMessageV4.scala:18`, `ETHPackets.scala:56,59,71` (HERALD gate), `MerklePatriciaTrie.scala:20`, `mpt/package.scala:19`.
- **Config threading candidate (1):** `EthTxService.scala:52` — `implicit val blockchainConfig: BlockchainConfig` → `using`.
- **JsonEncoder derivation (3):** `JsonEncoder.scala:35,39`, `JsonMethodCodec.scala:11`.

All 29 in §3a scope. No new findings.

#### Null checks: 34 hits total

- **Java interop boundaries — keep (13):** `BootstrapDownload.scala:66` (ZipInputStream), `RocksDbDataSource.scala:298` (RocksDB iterator), `LoggingMailbox.scala:41` (Pekko poll), `MessageCodec.scala:199` (Java exception), `TryWithResources.scala:10,21`, `StaticNodesLoader.scala:100,101`, `DnsDiscovery.scala:327` (JNDI), `ExternalIPDetector.scala:187` (BufferedReader EOF), `NetService.scala:143`, `ApacheHttpClientStreamClient.scala:140,197`, `PeerTelemetry.scala:116`, `Metrics.scala:119`.
- **Already tracked in S4/other findings (8):** `FastSync.scala:1425` (S4 C1), `EngineApiService.scala:103`, `EthashBlockHeaderValidator.scala:41` (FORGE), `PeerManagerActor.scala:469`, `SnapPathTrie.scala:100,122,136,142`, `ByteCodeCoordinator.scala:661,664,687` (Scala 3 `String | Null` union — correct), `MerkleProofVerifier.scala:411`.
- **🆕 New findings (2):**
  - `consensus/engine/EngineApiService.scala:525` — `pendingTransactionsManager != null` plain field guard. Should be `Option[ActorRef[PendingTransactionsManager.Command]]`. LOW, no gate.
  - `blockchain/sync/snap/SNAPSyncController.scala:1286,1296` — `info.filePath != null`. Config/info field should be `Option[Path]`. LOW, defer to SNAP cleanup sprint.

#### Return statements: 46 hits total

- **Already in DEFERRED-BACKLOG §8e (38 hits):** `SNAPSyncController.scala` (33 returns, SNAP1 gate), `vm/VM.scala:140`, `vm/OpCode.scala:989`, `vm/PrecompiledContracts.scala:271`, `ledger/BlockPreparator.scala:56`, `mpt/StackTrie.scala:120` (all FORGE gate).
- **🆕 Missing from §8e ratchet-lock checklist (4 hits, 3 files):**
  - `consensus/engine/JwtAuthenticator.scala:51` — `return Left("Invalid JWT signature")`. Guard clause → trivial `if ... then Left(...) else`. **FORGE gate.** Add to §8e.
  - `consensus/engine/EngineApiController.scala:96,226` — `return IO.pure(...)` inside IO computation. **BEACON gate.** Add to §8e.

#### Var declarations: 315+ hits — mostly expected

Expected: loop counters in hot byte-level loops (`EthashUtils`, `HexPrefix`, `Blake2bCompression`), iterator sentinels, `EthSimulateService.scala` (intentional imperative simulation), actor `Impl` bodies in non-`Impl`-named files (all correct Typed actor state vars).

- **🆕 Mutable SNAP task types (not in DEFERRED-BACKLOG):** `AccountTask.scala`, `StorageTask.scala`, `ByteCodeTask.scala`, `HealingTask.scala` are `case class` definitions with `var` fields (`pending`, `done`, `slots`, `proof`, `nodeData`). Making them immutable with `copy` semantics is safer; measure allocation pressure in the hot sync path first. Related to 7e-P2 but broader scope. LOW-MEDIUM.
- **🆕 `var fields` JSON builders (LOW):** `consensus/engine/EngineApiController.scala:704,712` and `EngineApiHttpServer.scala:169` — `var fields: List[...]` response builder should be `val`. No gate.
- **🆕 `var serverSocket = uninitialized` (LOW):** `jsonrpc/server/ipc/JsonRpcIpcServer.scala:35` — Scala 3 `uninitialized` is correct syntax; lifecycle enforcement is absent. Document or enforce via `Option`. No gate.

#### New findings table

| Category | Count | Files | Already in DEFERRED-BACKLOG? | Recommended action |
|----------|-------|-------|------------------------------|-------------------|
| Remaining `implicit val/def` (non-consensus) | 29 | 17 | YES §3a | No action until P3a sprint |
| `EngineApiService.scala:525` null field guard | 1 | `consensus/engine/EngineApiService.scala` | NO | LOW: `Option[ActorRef[...]]`; add to CHASE-QUEUE |
| `SNAPSyncController.scala:1286,1296` null `filePath` | 2 | `SNAPSyncController.scala` | NO | LOW: `Option[Path]`; defer to SNAP cleanup sprint |
| `JwtAuthenticator.scala:51` return (missing from §8e) | 1 | `consensus/engine/JwtAuthenticator.scala` | NO | Add to §8e FORGE checklist |
| `EngineApiController.scala:96,226` returns (missing from §8e) | 2 | `consensus/engine/EngineApiController.scala` | NO | Add to §8e BEACON checklist |
| Mutable `case class` task types in SNAP | 4 files | `AccountTask`, `StorageTask`, `ByteCodeTask`, `HealingTask` | NO (related 7e-P2) | LOW-MEDIUM: profile allocation first; defer to SNAP sprint |
| `var fields` JSON builder | 3 hits | `EngineApiController.scala`, `EngineApiHttpServer.scala` | NO | LOW: `val`; no gate |
| `var serverSocket = uninitialized` | 1 | `JsonRpcIpcServer.scala:35` | NO | LOW: document lifecycle or `Option`; no gate |

**S3 sweep complete — 8 new findings. All LOW or LOW-MEDIUM. Highest value: add JwtAuthenticator + EngineApiController returns to §8e FORGE/BEACON checklist (3 sites closing the 40-site total gap); log EngineApiService:525 null guard in CHASE-QUEUE.**

---

### S4 — Quality sweep on recently migrated actors (PRISM)

#### CRITICAL — fix before further migration

| # | File | Location | Issue | Owner |
|---|------|----------|-------|-------|
| C1 | `FastSync.scala` | :343 + :363–364 | `private var syncState`, `syncStateStorageActor`, `syncStateScheduler` all null-initialized. Stale message arriving before `initSyncSession()` → NPE. Fix: `Option[SyncSession]` inner class holding all 13 post-init fields. | MITHRIL |
| C2 | `SyncController.scala` | :15 | `ExecutionContext.Implicits.global` imported at file scope. Import may be dead (no `Future` visible in body); if any `Future` chain is added without an explicit EC, it silently binds to the global pool. Verify, then remove; if a Future is needed add `given ec: ExecutionContext = ctx.executionContext`. | WRAITH |
| C3 | `FastSync.scala` | :795 | `sys.exit(1)` on max-pivot-update-failure exhaustion — bypasses Pekko supervisor chain, `CoordinatedShutdown` hooks, and log flushes. Fix: define `FatalError(reason: String) extends Command`; send to `ctx.self`; handler returns `Behaviors.stopped`. | MITHRIL |

#### WARNINGS — logic & correctness

| # | File | Location | Issue | Owner |
|---|------|----------|-------|-------|
| W1 | `NetworkPeerManagerActor.scala` | :325–327, :689–692 | Dead branch: both arms of `if Capability.usesRequestId(...)` produce identical `ETHPackets.GetBlockHeaders(...)` expressions. Delete the if/else; keep only the shared body. | WRAITH |
| W2 | `NetworkPeerManagerActor.scala` | :863, :929, :1014, :1054 | `val _ = peerWithInfo` discards `Option[PeerWithInfo]` silently in all 4 SNAP server handlers (`handleGetAccountRange`, `handleGetStorageRanges`, `handleGetByteCodes`, `handleGetTrieNodes`). Unhandshaked peers still reach handler logic. Replace with explicit `peerWithInfo match { case None => Behaviors.same; case Some(p) => ... }` guard. | MITHRIL |
| W3 | `SyncController.scala` | :650–651 | `HandshakedPeers` message falls through to catch-all when `healingServeRootBootstrap.isDefined` and is forwarded to SNAP child which doesn't expect it. Add `case WrappedExternal(NetworkPeerManagerActor.HandshakedPeers(_)) => Behaviors.same` before the catch-all. | WRAITH |
| W4 | `SyncController.scala` | :899 | `ctx.self ! cmd` re-delivers the wrapped `Command`, not the unwrapped payload. Works now; any future state processing `cmd` directly silently drops the restart signal. Document the invariant or restructure. | MITHRIL |
| W5 | `FastSync.scala` | :220 | `ctx.toClassic.sender()` in `idle()`. For any Typed caller, `sender()` resolves to `ActorRef.noSender` → reply to dead letters. Verify `GetStatusCmd` is only sent via Classic bridge; if Typed callers exist, add `replyTo: ActorRef[...]` field. | HERALD |
| W6 | `NetworkPeerManagerActor.scala` | :419–432, :631–639 | `scheduler.scheduleOnce { peerManagerActor ! ... }` at two sites executes on Classic `HashedWheelTimer` thread, not actor mailbox. Replace both with `timers.startSingleTimer(TimerKey, message, delay)` per `pekko-typed-api.md P7`. | LOOM |
| W16 | `NetworkPeerManagerActor.scala` | :936–943, :972 | `catch { case _: Throwable => None }` in `handleGetStorageRanges` silently swallows MPT traversal errors for both account-root lookup and trie `find`. Storage range responses return empty on any trie corruption with no observable signal. Log at `warn` before returning `None`. | MITHRIL |
| W17 | `RegularSync.scala` | :262 | `case _ => Behaviors.same` catch-all silently drops any `RegularSyncCommand` subtype not matched above. `Command` type alias is not sealed (lines 34–36), so a new subtype addition is swallowed without a compile-time warning. Seal `SyncProtocol.RegularSyncCommand` and remove the catch-all. | MITHRIL |

#### WARNINGS — structure

| # | File | Location | Issue | Owner |
|---|------|----------|-------|-------|
| W7 | `NetworkPeerManagerActor.scala` | :146–1082 | ~900-line `Impl` with 9 distinct responsibilities. Extract SNAP server handlers (`handleGet*`, `refreshFreshRootCache`, `isStateRootFresh`) into a companion `SnapServer` object as first pass. | LOOM |
| W8 | `FastSync.scala` | :330–365 | 13 `private var` fields only valid post-`initSyncSession()`. Two-phase lifecycle not enforced by types — same fix as C1. | MITHRIL |
| W9 | `SyncController.scala` | :2240–2272 | `startRegularSyncForBootstrap()` spawns children with fixed names `"peers-client-bootstrap"` and `"regular-sync-bootstrap"`. Prior instance not fully stopped → `InvalidActorNameException`. Add `syncGeneration: Int` counter, append to both names. | LOOM |
| W10 | `SyncController.scala` | :304–324 | `withPostStop` is a full `BehaviorInterceptor` whose `aroundReceive` is pure identity. Replace with `Behaviors.receiveSignal { case (ctx, PostStop) => ...; Behaviors.same }` — eliminates 20 lines. | MITHRIL |
| W11 | `NetworkPeerManagerActor.scala` | :497–505, :847–848 | Two `BlockRangeUpdate` arms (ETHPackets path + ETH69 legacy path at :847–848, flagged "Phase 3 cleanup") with identical bodies. Verify reachability of :847–848; delete if dead. | HERALD |

#### WARNINGS — Scala FP

| # | File | Location | Issue | Owner |
|---|------|----------|-------|-------|
| W12 | `NetworkPeerManagerActor.scala` | :48 | `trait Command` is **not sealed**. In-code comment claims `PeerEvent` subtypes require it unsealed — this is factually incorrect: `PeerEventCmd` already wraps them as a named `Command` member. Unseal is unjustified; compiler cannot exhaustiveness-check `handleMessages`. Seal it — `pekko-typed-api.md P3`. | MITHRIL |
| W13 | `FastSync.scala` | :571–591 | `expandTypedReceipts` throws `RuntimeException` and catches it 6 lines later as control flow. Return `Either[String, Seq[TypedTransaction]]` and fold at the call site. | MITHRIL |
| W14 | `FastSync.scala` | :1306, :1543 | `var nextBehavior: Behavior[Command] = Behaviors.same` accumulates return value across imperative branches at two sites. Refactor each branch to return directly. | MITHRIL |
| W15 | `SyncController.scala` | :413–417 | `unwrap` returns `Any`, erasing type safety at the receive boundary. Unhandled external types silently forward to Classic child as `ClassCastException`. Track as ROOT-c follow-up when `WrappedExternal` is eliminated. | LOOM |

#### INFO (14 items — defer, no action required now)

| File | Location | Issue |
|------|----------|-------|
| `NetworkPeerManagerActor.scala` | :555–558 | `case _ => Behaviors.same` becomes unreachable after W12 (sealing `Command`). Remove at the same time. |
| `SyncController.scala` | class Scaladoc | Doc still says `Behavior[Any]`; type is `Behavior[Command]` since ROOT-b. |
| `SyncController.scala` | :296–303 | Second stale `Behavior[Any]` reference. Update both in same docs pass. |
| `FastSync.scala` | :67 | Factory method doc says `Behavior[Any]`. Correct to `Behavior[Command]`. |
| `SyncController.scala` | :1283 | `Paths.get(System.getProperty(...))` — path traversal risk if property set by untrusted source. Validate with `.normalize().toAbsolutePath()` + prefix check. |
| `SyncController.scala` | :1558–1571 | `System.getProperty("fukuii.seed-chain-weights")` fed to `BigInt(tdStr.trim)` with no error handler. Malformed property → `NumberFormatException` crashes actor. Wrap in `Try(...).toOption`. |
| `NetworkPeerManagerActor.scala` | :297–305 | 60-second summary log uses Scala string interpolation instead of SLF4J `{}` placeholders. See `logging-standards.md`. |
| `NetworkPeerManagerActor.scala` | :895–909 | `refreshFreshRootCache` walks up to 128 `getBlockHeaderByNumber` calls in actor loop. Info-only unless SNAP serve latency observed. |
| `NetworkPeerManagerActor.scala` | :51–52 | `GetHandshakedPeersCmd.replyTo: ActorRef` (untyped). Post-capstone: upgrade to `ActorRef[HandshakedPeers]`. Defer to Network/P2P sprint. |
| `FastSync.scala` | :180–183 | `val _ = (pivotFailedAdapter, ...)` fragile adapter-pinning tuple. Retire when FastSync narrowing is complete. |
| `SyncController.scala` | :121 | `WrappedExternal(msg: Any)` carries no documentation of which concrete types are routed through it. Add comment block. |
| `FastSync.scala` | :1149–1165, :1201 | 🪱 emoji in `log.info` (`worm-to-brain-bar`) and `printStatus`. Non-UTF-8 CI containers → garbled output or non-deterministic test failures. Replace with ASCII. |
| `RegularSync.scala` | :67 | Classic `LoggingAdapter` obtained via `Logging(ctx.system.classicSystem, ...)` bridge. Replace with `LoggerFactory.getLogger(...)` when Network/P2P sprint migrates this subsystem. |
| `RegularSync.scala` | :228 | `log.warning(...)` uses Classic `LoggingAdapter` spelling; SLF4J spells it `log.warn(...)`. Fix in same migration pass. |

*Migration guard passes clean — no new `extends Actor` or `ActorLogging` introduced. PRISM pass: 3 critical / 17 warning / 14 info. C2 corrected (import may be dead, not active bug); W2/W6/W9/W11/W12/W14 updated with additional line refs or corrected rationale; W16/W17 new; INFO count raised from 12 to 14 (RegularSync logging bridge entries added).*

---

### S5 — Test quality gaps (EYE, 2026-06-22)

| Category | Count | Status |
|----------|-------|--------|
| Wall-clock assertions | 3 known confirmed + 1 borderline (`SnapServerLimitsSpec` L89–90 upper bound) | Pre-existing in CHASE-QUEUE; no new routing needed |
| Thread.sleep (live) | 2 pre-existing (`EthMiningServiceSpec:302`, `SubscriptionManagerSpec:249`) | PRE-EXISTING — no new sites |
| TestProbe E165 unnarrowed | 777 sites / 83 files — prior "5 in FastSyncBranchResolverSpec" INCORRECT (file now 0) | CHASE-QUEUE baseline corrected; defer to test-harness sprint |
| Ignored/pending tests | 0 | CLEAN |

Highest-density E165 files: `TrieNodeHealingCoordinatorSpec` (58), `ByteCodeCoordinatorSpec` (56), `AccountRangeCoordinatorSpec` (54), `StorageRangeCoordinatorSpec` (39), `PeerManagerSpec` (32).

---


---

## Action matrix

| Finding | File | Severity | Owner | Gate | Resolution sprint |
|---------|------|----------|-------|------|-------------------|
| **C1**: null var session state (13 post-init vars, NPE risk) | `FastSync.scala:343,:363–364` | Critical | MITHRIL | None | immediate |
| **C2**: `ExecutionContext.Implicits.global` at file scope | `SyncController.scala:15` | Critical | WRAITH | None | immediate |
| **C3**: `sys.exit(1)` bypasses supervisor chain | `FastSync.scala:795` | Critical | MITHRIL | None | immediate |
| **W1**: dead branch — both `Capability.usesRequestId` arms identical | `NetworkPeerManagerActor.scala:325–327,:689–692` | High | WRAITH | None | next-sprint |
| **W3**: `HandshakedPeers` falls through to catch-all in healing state | `SyncController.scala:650–651` | High | WRAITH | None | next-sprint |
| **W6**: `Classic scheduler.scheduleOnce` inside Typed actor (2 sites) | `NetworkPeerManagerActor.scala:419–432` | High | LOOM | None | next-sprint |
| **W12**: `trait Command` not sealed — exhaustiveness checking disabled | `NetworkPeerManagerActor.scala:48` | High | MITHRIL | None | next-sprint |
| **W16**: `catch { case _: Throwable => None }` silently swallows MPT errors | `NetworkPeerManagerActor.scala:936–943,:972` | High | MITHRIL | None | next-sprint |
| **W17**: `case _ =>` catch-all on non-sealed `RegularSyncCommand` | `RegularSync.scala:262` | High | MITHRIL | None | next-sprint |
| **W2**: `val _ = peerWithInfo` silently discards Option (4 SNAP handlers) | `NetworkPeerManagerActor.scala:863,:929,:1014,:1054` | Medium | MITHRIL | HERALD verify intent | next-sprint |
| **W4**: `ctx.self ! cmd` re-delivers wrapped Command (invariant gap) | `SyncController.scala:899` | Medium | MITHRIL | None | next-sprint |
| **W5**: `ctx.toClassic.sender()` in `idle()` — dead letters for Typed callers | `FastSync.scala:220` | Medium | HERALD | HERALD verify callers | next-sprint |
| **W8**: 13 post-init `private var` fields — two-phase lifecycle unenforced (same root as C1) | `FastSync.scala:330–365` | Medium | MITHRIL | Covered by C1 fix | next-sprint |
| **W9**: fixed actor names at `startRegularSyncForBootstrap` — duplicate name on restart | `SyncController.scala:2240–2272` | Medium | LOOM | None | next-sprint |
| **W10**: `withPostStop` full `BehaviorInterceptor` identity wrap (20 lines) | `SyncController.scala:304–324` | Low | MITHRIL | None | next-sprint |
| **W11**: dead `BlockRangeUpdate` arm at :847–848 (Phase 3 cleanup comment) | `NetworkPeerManagerActor.scala:847–848` | Low | HERALD | HERALD verify reachability | next-sprint |
| **W13**: exception-as-control-flow in `expandTypedReceipts` | `FastSync.scala:571–591` | Low | MITHRIL | None | deferred |
| **W14**: `var nextBehavior` accumulator (2 sites) | `FastSync.scala:1306,:1543` | Low | MITHRIL | None | deferred |
| **W15**: `unwrap` returns `Any` — type erasure at receive boundary | `SyncController.scala:413–417` | Low | LOOM | Wave 3 LOOM gate (WrappedExternal elimination) | deferred |
| **W7**: 900-line `Impl` with 9 responsibilities | `NetworkPeerManagerActor.scala:146–1082` | Medium | LOOM | Wave 3 LOOM gate | deferred |
| **S3-A**: `EngineApiService:525` null field guard (Option gap) | `consensus/engine/EngineApiService.scala:525` | Low | BEACON | BEACON review | next-sprint |
| **S3-B**: `SNAPSyncController:1286,1296` null `filePath` (Option gap) | `SNAPSyncController.scala:1286,:1296` | Low | LOOM | SNAP cleanup sprint | deferred |
| **S3-C**: `JwtAuthenticator.scala:51` return missing from §8e checklist | `consensus/engine/JwtAuthenticator.scala:51` | Low | FORGE | FORGE gate (§8e) | next-sprint |
| **S3-D**: `EngineApiController.scala:96,226` returns missing from §8e checklist | `consensus/engine/EngineApiController.scala:96,:226` | Low | BEACON | BEACON gate (§8e) | next-sprint |
| **S3-E**: mutable `case class` task types in SNAP (var fields, 4 files) | `AccountTask`, `StorageTask`, `ByteCodeTask`, `HealingTask` | Low–Medium | LOOM | SNAP cleanup sprint (profile allocation first) | deferred |
| **S3-F**: `var fields` JSON response builder (3 sites) | `EngineApiController.scala:704,:712`; `EngineApiHttpServer.scala:169` | Low | BEACON | None | next-sprint |
| **S3-G**: `var serverSocket = uninitialized` lifecycle gap | `jsonrpc/server/ipc/JsonRpcIpcServer.scala:35` | Low | CONDUIT | None | next-sprint |
| **INFO-1**: `case _ =>` unreachable after W12 seal | `NetworkPeerManagerActor.scala:555–558` | Info | MITHRIL | After W12 | next-sprint |
| **INFO-2**: Scaladoc says `Behavior[Any]`, type is `Behavior[Command]` (class + :296–303) | `SyncController.scala` class + :296–303 | Info | MITHRIL | None | next-sprint |
| **INFO-3**: Factory method doc says `Behavior[Any]` | `FastSync.scala:67` | Info | MITHRIL | None | next-sprint |
| **INFO-4**: `WrappedExternal` has no concrete-type doc comment | `SyncController.scala:121` | Info | MITHRIL | None | next-sprint |
| **INFO-5**: `Paths.get(System.getProperty(...))` path-traversal risk | `SyncController.scala:1283` | Info | WRAITH | None | next-sprint |
| **INFO-6**: `BigInt(tdStr.trim)` no error handler — `NumberFormatException` crash | `SyncController.scala:1558–1571` | Info | WRAITH | None | next-sprint |
| **INFO-7**: 60-second summary log uses string interpolation not SLF4J `{}` | `NetworkPeerManagerActor.scala:297–305` | Info | MITHRIL | None | next-sprint |
| **INFO-8**: `refreshFreshRootCache` 128 `getBlockHeaderByNumber` calls in actor loop | `NetworkPeerManagerActor.scala:895–909` | Info | LOOM | SNAP serve latency profiling | deferred |
| **INFO-9**: `GetHandshakedPeersCmd.replyTo: ActorRef` (untyped) | `NetworkPeerManagerActor.scala:51–52` | Info | HERALD | Network/P2P sprint | deferred |
| **INFO-10**: `val _ = (pivotFailedAdapter, ...)` fragile adapter-pinning tuple | `FastSync.scala:180–183` | Info | MITHRIL | FastSync narrowing complete | deferred |
| **INFO-11**: `WrappedExternal(msg: Any)` undocumented concrete types | `SyncController.scala:121` | Info | MITHRIL | None | next-sprint |
| **INFO-12**: 🪱 emoji in `log.info` — non-UTF-8 CI containers may produce garbled output | `FastSync.scala:1149–1165,:1201` | Info | MITHRIL | None | next-sprint |
| **INFO-13**: Classic `LoggingAdapter` via `Logging(ctx.system.classicSystem, ...)` bridge | `RegularSync.scala:67` | Info | LOOM | Network/P2P sprint | deferred |
| **INFO-14**: `log.warning(...)` Classic spelling vs SLF4J `log.warn(...)` | `RegularSync.scala:228` | Info | LOOM | Network/P2P sprint | deferred |

---

## Resolution prompt series

Findings are grouped by owner cluster and ordered: **Immediate** → **Next-sprint** → **Deferred**.

---

### 1. IMMEDIATE — MITHRIL: FastSync critical fixes (C1 + C3 + W8)

**STATUS: ✅ DONE** — `SyncSession` case class introduced; `FatalError` Command + `CoordinatedShutdown` replacing `sys.exit(1)`.

**Agent:** MITHRIL
**Files:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala`
**Findings addressed:** C1, C3, W8

**Prompt:**
> On branch `scala3-cleanup-june`, fix three critical findings in `FastSync.scala`.
>
> **C1 + W8 — Null-initialized two-phase lifecycle (lines 330–365)**
> `syncState`, `syncStateStorageActor`, `syncStateScheduler` and 10 other vars are all
> `null`-initialized. A stale message arriving before `initSyncSession()` produces an NPE in
> the actor mailbox with no recovery path.
> Fix: introduce a `case class SyncSession` holding all 13 post-init fields. Replace the 13
> `private var` declarations with a single `private var session: Option[SyncSession] = None`.
> `initSyncSession()` constructs and sets `session`. Every handler that reads a session field
> must first extract it: `session match { case None => Behaviors.same; case Some(s) => ... }`.
> Preserve all existing method signatures and behavior semantics exactly.
>
> **C3 — `sys.exit(1)` at line 795**
> `sys.exit(1)` bypasses the Pekko supervisor chain, `CoordinatedShutdown` hooks, and log
> flushes. Fix: add `case class FatalError(reason: String) extends Command` to the sealed ADT.
> Replace `sys.exit(1)` with `ctx.self ! FatalError("max pivot update attempts exceeded")`.
> Add a handler in each active state: `case FatalError(reason) => ctx.log.error(...); Behaviors.stopped`.
> If the JVM must exit (original intent), call `CoordinatedShutdown(ctx.system).run(...)` from
> the handler instead of `sys.exit`.
>
> After changes:
> ```
> sbt compile-all        # must be 0 errors
> sbt scalafmtAll
> sbt testOnly *FastSync*
> ```
> One commit: `fix(pekko): enforce typed session lifecycle and replace sys.exit — FastSync C1/C3/W8`

**Verification:** `sbt compile-all` → 0 errors; `sbt testOnly *FastSync*` → all pass (expect 34/35; the pre-existing 60s "does not crash" timeout is a baseline failure unrelated to these changes)
**Rejection criteria:** Any new test failure beyond the pre-existing 1; any `null` remaining in the 13 fields; `sys.exit` still present

---

### 2. IMMEDIATE — WRAITH: SyncController EC.global (C2)

**STATUS: ✅ DONE** (`a5132aa80`) — import removed; `given ec = ctx.executionContext` added in `startSnapSync` only; import was not fully dead.

**Agent:** WRAITH
**Files:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala`
**Findings addressed:** C2

**Prompt:**
> On branch `scala3-cleanup-june`, fix the `ExecutionContext.Implicits.global` import in
> `SyncController.scala:15`.
>
> `import scala.concurrent.ExecutionContext.Implicits.global` at file scope silently routes any
> `Future.map`/`flatMap`/`foreach`/`onComplete` without an explicit EC to the unbounded global
> thread pool, bypassing the actor's dedicated dispatcher.
>
> Fix steps:
> 1. Remove the import at line 15.
> 2. Run `sbt compile-all` — surface every "No implicit ExecutionContext" error.
> 3. For each error site: if the `Future` is created from a Pekko ask or `pipeToSelf`, the EC
>    is usually the actor system's dispatcher (`ctx.system.executionContext`) or should be passed
>    explicitly. Add `given ec: ExecutionContext = ctx.system.executionContext` at the relevant
>    scope, or thread the EC explicitly at each site.
> 4. If the import turns out to be dead (no errors after removal), the commit message should say so.
>
> After changes:
> ```
> sbt compile-all        # must be 0 errors
> sbt scalafmtAll
> sbt testOnly *SyncController*
> ```
> One commit: `fix(pekko): remove EC.global import from SyncController — C2`

**Verification:** `sbt compile-all` → 0 errors; `sbt testOnly *SyncController*` → 84/84
**Rejection criteria:** Any remaining `ExecutionContext.Implicits.global` import in the file; any new test failure

---

### 3. NEXT-SPRINT — MITHRIL: NPMA Command sealing + W1 + W16 + W17 (NetworkPeerManagerActor + RegularSync)

**STATUS: ✅ DONE** — W12/W1/W16/INFO-1/INFO-7 applied; W2 used `@annotation.unused` (by-design: None path reachable when SNAP request arrives before ETH-status exchange completes). **W17 deviation:** `SyncProtocol.RegularSyncCommand` cannot be sealed — subtypes in RegularSync.scala extend it across files → E112. Replaced `case _ => Behaviors.same` with `Behaviors.unhandled` + `log.warning`. Structural seal (move subtypes to SyncProtocol.scala) tracked in CHASE-QUEUE.

**Agent:** MITHRIL
**Files:**
- `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/RegularSync.scala`

**Findings addressed:** W1, W2 (after HERALD verify), W12, W16, W17, INFO-1, INFO-7

**Prompt:**
> On branch `scala3-cleanup-june`, fix the MITHRIL-owned warnings in `NetworkPeerManagerActor.scala`
> and `RegularSync.scala`. One commit per file.
>
> **NetworkPeerManagerActor.scala — commit 1:**
>
> W12 (line 48): `trait Command` is not sealed. The in-code comment claims `PeerEvent` subtypes
> require it unsealed — this is incorrect: `PeerEventCmd` already wraps them as a named `Command`
> member. Add `sealed` to `trait Command`. Then run `sbt compile-all` — any "match may not be
> exhaustive" warning is a real bug; fix each exhaustiveness gap before committing.
>
> INFO-1 (lines 555–558): after W12 is sealed, the `case _ => Behaviors.same` catch-all at this
> location becomes unreachable. Remove it in the same commit.
>
> W1 (lines 325–327, 689–692): Both arms of `if Capability.usesRequestId(...)` produce identical
> `ETHPackets.GetBlockHeaders(...)` expressions. Delete the `if/else`; keep only the shared body.
>
> W16 (lines 936–943, 972): `catch { case _: Throwable => None }` in `handleGetStorageRanges`
> silently swallows MPT traversal errors. Before returning `None`, add:
> `log.warn("MPT traversal error in handleGetStorageRanges: {}", e.getMessage)`.
>
> INFO-7 (lines 297–305): the 60-second summary log uses Scala string interpolation. Per
> `logging-standards.md`, change to SLF4J `{}` placeholder format.
>
> W2 (lines 863, 929, 1014, 1054): `val _ = peerWithInfo` silently discards `Option[PeerWithInfo]`.
> **First confirm with HERALD** that all 4 SNAP server handler call sites (`handleGetAccountRange`,
> `handleGetStorageRanges`, `handleGetByteCodes`, `handleGetTrieNodes`) are only reached after a
> successful handshake. If HERALD confirms: replace each `val _ = peerWithInfo` with
> `peerWithInfo match { case None => log.debug(...); Behaviors.same; case Some(p) => ... }`.
> If HERALD says the None path is unreachable by design, add a comment and leave as-is.
>
> After NetworkPeerManagerActor changes:
> ```
> sbt compile-all          # 0 errors
> sbt scalafmtAll
> sbt testOnly *NetworkPeer* *PeerManager*
> ```
> Commit: `fix(pekko): seal Command, log swallowed errors, remove dead branches — NPMA W1/W2/W12/W16/INFO-1/INFO-7`
>
> **RegularSync.scala — commit 2:**
>
> W17 (line 262): `case _ => Behaviors.same` catch-all silently drops any `RegularSyncCommand`
> subtype not matched above. The type alias on lines 34–36 is not sealed; a new subtype addition
> would be swallowed without a compile-time warning. Seal `SyncProtocol.RegularSyncCommand` (in
> `SyncProtocol.scala`). Then remove the catch-all from `RegularSync.scala:262`.
>
> After RegularSync changes:
> ```
> sbt compile-all          # 0 errors — any exhaustiveness warnings are real bugs, fix them
> sbt scalafmtAll
> sbt testOnly *RegularSync*
> ```
> Commit: `fix(pekko): seal RegularSyncCommand and remove catch-all drop — RegularSync W17`

**Verification:** `sbt compile-all` → 0 errors; targeted tests all pass
**Rejection criteria:** Any new test failure; `sealed` not added; `catch { case _: Throwable => None }` logging not added; `case _ => Behaviors.same` remaining in RegularSync

---

### 4. NEXT-SPRINT — LOOM: NPMA scheduler thread fix + W9 (NetworkPeerManagerActor + SyncController)

**STATUS: ✅ DONE** (`5e33435c8` NPMA, `8bd4ed3f1` SyncController) — W6: per-peer case class timer keys (`LaggingPeerBlacklistTimerKey`, `TdProxyGapBlacklistTimerKey`) + `DeferredBlacklistCmd` mailbox re-entry; Classic scheduler removed. W9: reused existing `bootstrapGeneration: Long`, versioned both child names.

**Agent:** LOOM
**Files:**
- `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala`
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala`

**Findings addressed:** W6, W9

**Prompt:**
> On branch `scala3-cleanup-june`, fix two LOOM-owned Pekko Typed API warnings.
>
> **W6 — NetworkPeerManagerActor.scala lines 419–432 (and :631–639 if present)**
> `scheduler.scheduleOnce { peerManagerActor ! ... }` executes on the Classic
> `HashedWheelTimer` thread, not the actor mailbox. The actor already uses `withTimers`.
> Per `pekko-typed-api.md` preference P7:
> - Define a new private sealed `TimerKey` hierarchy (one `case object` per schedule site).
> - Replace each `scheduler.scheduleOnce(delay) { peerManagerActor ! Msg }` with
>   `timers.startSingleTimer(TimerKey, Msg, delay)` and handle `Msg` in the receive block.
>
> **W9 — SyncController.scala lines 2240–2272 (`startRegularSyncForBootstrap`)**
> Children are spawned with fixed names `"peers-client-bootstrap"` and `"regular-sync-bootstrap"`.
> If a prior instance is not fully stopped when this method is called again, Pekko throws
> `InvalidActorNameException`. Fix: add a `syncGeneration: Int` counter field to `Impl`
> (initialized to 0, incremented on each call). Append the counter to both child names:
> `s"peers-client-bootstrap-$syncGeneration"` and `s"regular-sync-bootstrap-$syncGeneration"`.
>
> After changes:
> ```
> sbt compile-all          # 0 errors
> sbt scalafmtAll
> sbt testOnly *NetworkPeer* *SyncController*
> ```
> One commit per file.
> NPMA commit: `fix(pekko): replace Classic scheduler.scheduleOnce with Typed timers — NPMA W6`
> SyncController commit: `fix(pekko): versioned child names to prevent InvalidActorNameException — SyncController W9`

**Verification:** `sbt compile-all` → 0 errors; 84/84 SyncController tests pass; NPMA targeted tests pass
**Rejection criteria:** Classic `scheduler.scheduleOnce` still present at W6 sites; fixed child name strings still in W9 location

---

### 5. NEXT-SPRINT — WRAITH: SyncController HandshakedPeers fallthrough + input validation (W3, INFO-5, INFO-6)

**STATUS: ✅ DONE** (`8a65bbdb7` correctness, `a73ce7922` docs) — W3/W10/INFO-5/INFO-6 applied; W10 used `BehaviorSignalInterceptor` (equivalent to receiveSignal, agent's choice). Note: 7 pre-existing `SyncControllerSpec.SyncStateAutoPilot` failures exist on baseline (MatchError on `GetHandshakedPeersCmd` — test autopilot doesn't handle this message from NPMA migration); unrelated to this commit. Tracked in CHASE-QUEUE.

**Agent:** WRAITH
**Files:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/SyncController.scala`
**Findings addressed:** W3, W4 (document invariant), W10, INFO-2, INFO-4, INFO-5, INFO-6, INFO-11

**Prompt:**
> On branch `scala3-cleanup-june`, fix multiple medium/low-severity findings in `SyncController.scala`.
> One commit for correctness fixes; one commit for documentation/style.
>
> **Correctness commit:**
>
> W3 (lines 650–651): When `healingServeRootBootstrap.isDefined`, a `HandshakedPeers` message
> falls through to the catch-all and is forwarded to the SNAP child, which does not expect it.
> Add an explicit arm immediately before the catch-all:
> `case WrappedExternal(NetworkPeerManagerActor.HandshakedPeers(_)) => Behaviors.same`
> Adjust the wrapper type to match whatever wraps at that location in the actual source.
>
> INFO-5 (line 1283): `Paths.get(System.getProperty(...))` is a path-traversal risk if the
> property is controlled by an untrusted source. Wrap as:
> `val raw = Paths.get(System.getProperty(...)).normalize().toAbsolutePath()`
> and validate that `raw` starts with the expected base directory before use.
>
> INFO-6 (lines 1558–1571): `BigInt(tdStr.trim)` is called with no error handler; a malformed
> system property crashes the actor with `NumberFormatException`. Wrap in `Try(BigInt(tdStr.trim)).toOption`
> and handle the `None` case (log a warning and use a safe default or skip the update).
>
> W10 (lines 304–324): `withPostStop` is a full `BehaviorInterceptor` whose `aroundReceive`
> is a pure identity. Replace with:
> `Behaviors.receiveSignal { case (ctx, PostStop) => ...; Behaviors.same }`
> This eliminates 20 lines and avoids the interceptor allocation.
>
> Correctness commit: `fix(pekko): stop HandshakedPeers fallthrough, harden system-property inputs — SyncController W3/W10/INFO-5/INFO-6`
>
> **Docs commit:**
>
> INFO-2 (class Scaladoc and lines 296–303): Update `Behavior[Any]` to `Behavior[Command]`.
> INFO-4 / INFO-11 (line 121): Add a comment block to `WrappedExternal` listing the concrete
> message types currently routed through it.
>
> Docs commit: `docs(pekko): fix stale Behavior[Any] refs and document WrappedExternal — SyncController INFO-2/INFO-4/INFO-11`
>
> After all changes:
> ```
> sbt compile-all          # 0 errors
> sbt scalafmtAll
> sbt testOnly *SyncController*
> ```

**Verification:** `sbt compile-all` → 0 errors; 84/84 pass
**Rejection criteria:** W3 fallthrough not guarded; INFO-5 path not normalized; INFO-6 `BigInt(...)` still unprotected

---

### 6. NEXT-SPRINT — HERALD: Verify W5 and W11 caller intent (pre-flight for MITHRIL follow-up)

**STATUS: ✅ DONE**

Verdicts:
- **W5 — by-design, Classic-only.** `GetStatusCmd` is a private self-message; `ctx.toClassic.sender()` is always a Classic ask-temp actor (EthInfoService/NodeJsonRpcHealthChecker/McpTools/McpResources all reach SyncController via Classic bridge). `sender()` is never `noSender`. MITHRIL action: comment at line 220 only.
- **W11 — dead code. Both arms deleted.** The ETH69.BlockRangeUpdate arms at `:525` and `:874` (both tagged "Phase 3 cleanup") are unreachable — `ETH69.BlockRangeUpdate` is outbound-only (used by BlockBroadcast and NPMA for sends); the decoders only produce `ETHPackets.BlockRangeUpdate` on inbound. Both arms deleted; the `ETH69.BlockRangeUpdate` type itself stays (needed for outbound sends).

**Agent:** HERALD
**Files:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala:220`
- `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala:847–848`

**Findings addressed:** W5, W11

**Prompt:**
> On branch `scala3-cleanup-june`, answer two caller-intent questions. Read-only — no edits.
>
> **W5 — `FastSync.scala:220` `ctx.toClassic.sender()` in `idle()`**
> `GetStatusCmd` is sent to `FastSync` at this site. For any Typed caller, `sender()` resolves
> to `ActorRef.noSender` so the reply goes to dead letters silently.
> Determine: Is `GetStatusCmd` ever sent from a Typed actor (e.g., via `!` or `AskPattern`
> with a `replyTo` field)? Or is it always sent from Classic actors via Classic `ask`/`tell`?
> Check `SyncController.scala` and `EthInfoService.scala` for all `GetStatusCmd` send sites.
> If any Typed caller exists: MITHRIL needs to add a `replyTo: ActorRef[StatusMsg]` field to
> `GetStatusCmd` and update the idle handler. If Classic-only: document this constraint in a
> comment at the call site and mark W5 by-design.
>
> **W11 — `NetworkPeerManagerActor.scala:847–848` dead `BlockRangeUpdate` arm**
> There is a "Phase 3 cleanup" comment marking a second `BlockRangeUpdate` arm at these lines.
> Determine: Is the arm at :847–848 still reachable, or is it dead code superseded by the arm
> at :497–505? Trace the condition under which each arm is reached. If :847–848 is dead: confirm
> MITHRIL can delete it. If still reachable: clarify why both arms exist with identical bodies.
>
> Report findings as two clearly labeled sections: W5-verdict and W11-verdict. No code changes.

**Verification:** Written findings reported to user; no compile step needed
**Rejection criteria:** No concrete verdict on each finding; speculation without reading the actual call sites

---

### 6b. NEXT-SPRINT — MITHRIL: W5 comment + W11 dead arm deletion

**STATUS: ✅ DONE** (`13aa7585e`) — W5: 4-line by-design comment at FastSync.scala:219; W11: both dead `ETH69.BlockRangeUpdate` inbound arms deleted from NPMA (`:525` and `:874`); collateral: 2 test cases in `NetworkPeerManagerSpec` updated to inject `ETHPackets.BlockRangeUpdate` (the real inbound type). 25/25 NPMA tests pass. 6 pre-existing FastSync failures unrelated.

**Agent:** MITHRIL
**Files:**
- `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala:220`
- `src/main/scala/com/chipprbots/ethereum/network/NetworkPeerManagerActor.scala:525, :874`

**Findings addressed:** W5 (by-design), W11 (dead code)

**Prompt:**
> On branch `scala3-cleanup-june`, apply two small fixes based on HERALD verdict from Prompt 6.
> One commit covers both files.
>
> **W5 — `FastSync.scala:220` — by-design comment**
>
> HERALD confirmed: `GetStatusCmd` is a private self-message constructed by FastSync from the
> `WrappedSyncProtocol(SyncProtocol.GetStatus)` arm, capturing `ctx.toClassic.sender()`.
> All external callers reach this path via Classic bridge, so `sender()` is always a valid
> Classic ask-temp actor, never `noSender`. No replyTo field is needed.
>
> Add a by-design comment immediately above or at line 220:
> ```scala
> // By-design Classic bridge: sender() is always a Classic ask-temp actor here.
> // GetStatusCmd is constructed internally from WrappedSyncProtocol(GetStatus);
> // all external callers reach it via a Classic bridge ref. Safe as long as
> // SyncController is never exposed as a Typed ActorRef[Command] to callers.
> ```
>
> **W11 — `NetworkPeerManagerActor.scala` — delete both dead ETH69.BlockRangeUpdate arms**
>
> HERALD confirmed: two `ETH69.BlockRangeUpdate` match arms (at `:525` and `:874`, both tagged
> with "Phase 3 cleanup" comments) are unreachable. `ETH69.BlockRangeUpdate` is outbound-only;
> inbound decoders (`ETH69MessageDecoder`, `ETH70MessageDecoder`) only produce
> `ETHPackets.BlockRangeUpdate`. The reachable arm at `:872` (ETHPackets.BlockRangeUpdate)
> handles the only real case.
>
> Delete both dead arms at `:525` and `:874`. Do NOT delete the `ETH69.BlockRangeUpdate` type
> itself — it is still used for outbound sends in `BlockBroadcast` and `NetworkPeerManagerActor`.
>
> After changes:
> ```
> sbt compile-all          # 0 errors — any exhaustiveness warning is a real bug
> sbt scalafmtAll
> sbt testOnly *FastSync* *NetworkPeer* *PeerManager*
> ```
> Commit: `fix(pekko): W5 by-design comment, delete dead ETH69.BlockRangeUpdate arms — W5/W11`

**Verification:** `sbt compile-all` → 0 errors; targeted tests pass; comment present at FastSync:220; both dead arms gone from NPMA
**Rejection criteria:** Dead arms still present; `ETH69.BlockRangeUpdate` type deleted (must stay); compile errors after deletion

---

### 7. NEXT-SPRINT — MITHRIL: FastSync docs + emoji cleanup (INFO-3, INFO-12)

**STATUS: ✅ DONE** (`3c6be4512`) — INFO-3: Scaladoc corrected `Behavior[Any]` → `Behavior[Command]` at line 64. INFO-12: 3 emoji sites replaced with ASCII (`>`, `|`, `[WORM-TO-BRAIN]`). NOTE: worm/brain branding is being restored via Prompt 7b — integrated properly across FastSync, RegularSync, and SNAPSync with consistent ASCII-safe encoding.

**Agent:** MITHRIL
**Files:** `src/main/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSync.scala`
**Findings addressed:** INFO-3, INFO-12

**Prompt:**
> On branch `scala3-cleanup-june`, two small fixes in `FastSync.scala`. Single commit.
>
> INFO-3 (line 67): Factory method Scaladoc says `Behavior[Any]`. Correct to `Behavior[Command]`.
>
> INFO-12 (lines 1149–1165, 1201): `log.info` and `printStatus` contain a worm emoji
> (`worm-to-brain-bar`). Non-UTF-8 CI containers may produce garbled output or non-deterministic
> test failures. Replace each emoji occurrence with an ASCII equivalent (e.g., `[WORM-TO-BRAIN]`
> or `[==>]`).
>
> After changes:
> ```
> sbt compile-all          # 0 errors
> sbt scalafmtAll
> ```
> Commit: `docs(pekko): fix Behavior[Any] doc and replace emoji in logs — FastSync INFO-3/INFO-12`

**Verification:** `sbt compile-all` → 0 errors; no emoji characters in log lines
**Rejection criteria:** Emoji still present; Scaladoc still says `Behavior[Any]`

---

### 7b. BRANDING — MITHRIL: WormToBrainBar shared utility + sync integration

**STATUS: ✅ DONE** (4 commits on scala3-cleanup-june)

Not a PRISM finding — added as a branding follow-up to Prompt 7's ASCII replacement.

| Commit | Target | Change |
|--------|--------|--------|
| `c37154287` | `WormToBrainBar.scala` (new) | Shared utility — `renderKnown` + `renderUnknown`; emoji confined to exactly 2 `val` definitions |
| `cae6e0ab5` | `SyncProgressMonitor.scala` | `wormChasesBrainBar` deleted; `formattedString` cleaned; `wormBlock` added; second `log.info` in `logProgress()` |
| `31c51a7cc` | `RegularSync.scala` | Worm bar appended after `PrintStatusTick` conventional line |
| `c8a1ddbfc` | `FastSync.scala` | Inline bar deleted (−21 lines); shared utility wired in |

All rejection criteria pass: `compile-all` clean; no emoji in `fast/` or `src/test/`; `formattedString` has no `$bar` prefix; `WormToBrainBar.scala` has exactly 2 emoji definitions.

---

### 8. NEXT-SPRINT — BEACON: EngineApiService null guard + var fields (S3-A, S3-D, S3-F)

**STATUS: ✅ DONE** (`89d6aadb2`) — S3-A: `Option[ActorRef[...]]` replacing null guard (NodeBuilder + EngineApiServiceSpec updated); S3-F: all 3 `var fields` sites replaced with immutable `val` construction. S3-D deferred: `return IO.pure(...)` at :96/:226 sit inside ~126-line methods; refactor would re-indent ~90 lines of byte-sensitive Engine API response code — logged in DEFERRED-BACKLOG §8e as BEACON gate items.

**Agent:** BEACON
**Files:**
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiService.scala:525`
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiController.scala:96,:226,:704,:712`
- `src/main/scala/com/chipprbots/ethereum/consensus/engine/EngineApiHttpServer.scala:169`

**Findings addressed:** S3-A, S3-D, S3-F

**Prompt:**
> On branch `scala3-cleanup-june`, fix three LOW-severity findings in the Engine API files.
> These are non-consensus structural improvements; BEACON review is required because the files
> are in the consensus path.
>
> **S3-A — `EngineApiService.scala:525` null field guard**
> `pendingTransactionsManager != null` guards a plain field. Change the field type from
> `ActorRef[PendingTransactionsManager.Command]` to
> `Option[ActorRef[PendingTransactionsManager.Command]]`, initializing to `None`.
> Update all access sites to use `.foreach(...)` or `match` instead of the null check.
>
> **S3-F — `EngineApiController.scala:704,712` and `EngineApiHttpServer.scala:169` var fields**
> `var fields: List[...]` response builder should be `val fields: List[...]` (or constructed
> via a functional fold). These are local response-building variables, not actor state.
> Replace `var` + mutation with `val` + immutable list construction.
>
> **S3-D — `EngineApiController.scala:96,226` return statements (add to §8e BEACON checklist)**
> Two `return IO.pure(...)` sites inside IO computations. These should be refactored to use
> `if/else` expressions returning `IO[A]` directly (no `return` keyword). Log these in
> `DEFERRED-BACKLOG.md §8e` as the BEACON gate items for the `noReturns` ratchet lock.
> Fix them in this commit if straightforward; otherwise add the §8e entry and defer.
>
> After changes:
> ```
> sbt compile-all          # 0 errors
> sbt scalafmtAll
> sbt testOnly *EngineApi*
> ```
> Commit: `fix(engine): Option guard for PTM ref, val fields, remove return in IO — S3-A/S3-D/S3-F`

**Verification:** `sbt compile-all` → 0 errors; 16/16 EngineApiSpec pass
**Rejection criteria:** `!= null` still present at S3-A site; `var fields` still present at S3-F sites; FORGE review triggered (these are application-layer, not consensus-layer changes)

---

### 9. NEXT-SPRINT — FORGE: JwtAuthenticator return to §8e (S3-C)

**STATUS: ✅ DONE** (`6c8a07725`) — `return Left(...)` at :51 replaced with `if/else`; DEFERRED-BACKLOG §8e FORGE count 7→6.

**Agent:** FORGE
**Files:** `src/main/scala/com/chipprbots/ethereum/consensus/engine/JwtAuthenticator.scala:51`
**Findings addressed:** S3-C

**Prompt:**
> On branch `scala3-cleanup-june`, fix the `return` statement at `JwtAuthenticator.scala:51`
> and record it in the `DEFERRED-BACKLOG.md §8e` FORGE checklist.
>
> `return Left("Invalid JWT signature")` is a guard clause. Replace with:
> ```scala
> if !signatureValid then Left("Invalid JWT signature")
> else // ... remaining logic
> ```
> No behavior change. This closes one of the 7 FORGE-gated `§8e` ratchet items.
>
> Update `DEFERRED-BACKLOG.md §8e` checklist: mark this file as fixed, update the FORGE
> deferred count from 7 to 6 (or to whatever the new count is).
>
> After changes:
> ```
> sbt compile-all          # 0 errors
> sbt scalafmtAll
> sbt testOnly *Jwt* *Engine*
> ```
> Commit: `fix(engine): remove return guard clause in JwtAuthenticator — S3-C (§8e -1)`

**Verification:** `sbt compile-all` → 0 errors; `DEFERRED-BACKLOG.md §8e` FORGE count decremented
**Rejection criteria:** `return` still present; §8e not updated

---

### 10. NEXT-SPRINT — CONDUIT: JsonRpcIpcServer var serverSocket lifecycle (S3-G)

**STATUS: ✅ DONE** (`8ef187dfb`) — `Option[ServerSocket]` replacing `uninitialized`; `close()` is now a no-op before `run()`; thread body captures `val socket`; `serverSocket = None` in `close()`.

**Agent:** CONDUIT
**Files:** `src/main/scala/com/chipprbots/ethereum/jsonrpc/server/ipc/JsonRpcIpcServer.scala:35`
**Findings addressed:** S3-G

**Prompt:**
> On branch `scala3-cleanup-june`, address the `var serverSocket = uninitialized` lifecycle gap
> at `JsonRpcIpcServer.scala:35`.
>
> `var serverSocket = uninitialized` is valid Scala 3 syntax, but the field has no explicit
> lifecycle enforcement. If the server fails to start, consumers may attempt to use `serverSocket`
> before it is assigned.
>
> Read the full file to understand the initialization sequence, then choose the safest fix:
> - If `serverSocket` is always assigned before any caller can reach it: add a `require(serverSocket != null)` guard in the methods that use it and add a comment documenting the invariant.
> - If assignment is conditional: change to `Option[ServerSocket]` with explicit `.getOrElse(throw ...)` at each use site.
>
> After changes:
> ```
> sbt compile-all          # 0 errors
> sbt scalafmtAll
> ```
> Commit: `fix(jsonrpc): enforce serverSocket lifecycle — JsonRpcIpcServer S3-G`

**Verification:** `sbt compile-all` → 0 errors
**Rejection criteria:** `uninitialized` field still accessible before initialization without a guard

---

## Post-Migration Target State

**Purpose:** Defines what "fully done" looks like. This section is the acceptance criteria for the migration — not completed yet, but set here so the audit record captures the intent.

The migration goal is **zero Classic residue outside the three deliberate TCP bridges** (`ServerActor`, `RLPxConnectionHandler`×2). Every `.toClassic` conversion, untyped `ActorRef` param, and `ctx.toClassic.sender()` call in the current codebase is migration debt, not final design.

### Classic bridge inventory (as of 2026-06-22 sweep)

| Category | Count | Current locations | Resolves when |
|----------|-------|-------------------|---------------|
| **BRIDGE-A** — `ctx.toClassic.sender()` hub pattern | ~28 sites | FastSync×3, RegularSync×1, SyncController×24 | All external callers adopt typed `replyTo` protocol |
| **BRIDGE-B** — `ctx.self.toClassic` reply-target | 15 sites | SNAPSyncController×7, ByteCodeCoordinator, AccountRangeCoordinator, BlockImporter, StorageRecoveryActor, PeerActor×1, RLPxConnectionHandler×1 (intentional) | Worker actors (PeerRequestHandler, coordinators) accept `TypedActorRef` params |
| **BRIDGE-C** — Untyped `ActorRef` in collaborator constructor params | ~12 actors | `peerEventBus: ActorRef`, `networkPeerManager: ActorRef`, `fastSyncClassicSelf`, `syncController: ActorRef`, `parentRef: ClassicActorRef` in PivotBlockSelector + SyncStateSchedulerActor | Each collaborator migrated in Wave 3/4 |
| **BRIDGE-D** — `Behavior[Any]` in production code | 0 in code (11 in comments) | Comments only — ✅ already clean | N/A |
| **BRIDGE-E** — Classic `LoggingAdapter` | 2 sites | `RegularSync.scala:67`, `:228` | RegularSync migrated (Network/P2P sprint) |
| **BRIDGE-F** — `extends Actor` / `extends ClassicActor` | 3 intentional | `ServerActor`, `RLPxConnectionHandler`×2 | **Never removed** — deliberate TCP bridges |

### Target clean-state grep (all must return 0 results except BRIDGE-F)

```bash
cd /media/dev/2tb/dev/fukuii

# BRIDGE-A: Classic sender access
grep -rn "toClassic\.sender()" src/main/ --include="*.scala" | grep -v "//"
# Expected: 0

# BRIDGE-B: self as Classic ref (exclude TCP bridges)
grep -rn "ctx\.self\.toClassic\|context\.self\.toClassic" src/main/ --include="*.scala" \
  | grep -v "ServerActor\|RLPxConnectionHandler"
# Expected: 0

# BRIDGE-C: untyped ActorRef in non-bridge Typed actors
grep -rn ": ActorRef[^[\s\)]" src/main/ --include="*.scala" \
  | grep -v "TypedActorRef\|import\|//\|sealed\|type \|ServerActor\|RLPxConnectionHandler\|PeerEventBus"
# Expected: 0

# BRIDGE-D: Behavior[Any] in code (not comments)
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala" | grep -v "//"
# Expected: 0

# BRIDGE-E: Classic logging bridge
grep -rn "LoggingAdapter\|Logging(ctx\.system\|Logging(system\b" src/main/ --include="*.scala" | grep -v "//"
# Expected: 0

# BRIDGE-F: deliberate TCP bridges (should be exactly 3)
grep -rn "extends Actor\b\|extends ClassicActor\b" src/main/ --include="*.scala"
# Expected: ServerActor + RLPxConnectionHandler×2 only
```

### Resolution sprint mapping

| Bridge category | Resolution sprint | Tracking |
|-----------------|-------------------|---------|
| BRIDGE-A | Post-migration caller-migration (add `replyTo` to SyncProtocol messages; SyncController refactor) | `working-docs/CODEBASE-AUDIT.md` POST-MIGRATION-SWEEP |
| BRIDGE-B | Per-collaborator: each worker actor migrated in Wave 4 (PeerRequestHandler, coordinator workers) | `working-docs/CODEBASE-AUDIT.md` POST-MIGRATION-SWEEP |
| BRIDGE-C | Per-collaborator: PivotBlockSelector, SyncStateSchedulerActor, NPMA in Wave 3 G1 | `working-docs/CODEBASE-AUDIT.md` G1 / D1 |
| BRIDGE-D | Already clean | N/A |
| BRIDGE-E | RegularSync Network/P2P sprint | `working-docs/CODEBASE-AUDIT.md` D1 / INFO-13/14 |
| BRIDGE-F | Never removed | N/A |

---

## Resolution Prompt Series — June 2026 sprint (all DONE)

**Branch:** `scala3-cleanup-june`  
**Closed:** 2026-06-27

| # | Prompt | Owner | Commit | Status |
|---|--------|-------|--------|--------|
| 1 | FastSync critical fixes (C1 + C3 + W8) | MITHRIL | — | ✅ DONE |
| 2 | SyncController EC.global (C2) | WRAITH | `a5132aa80` | ✅ DONE |
| 3 | NPMA Command sealing + W1/W16/W17 | MITHRIL | — | ✅ DONE |
| 4 | NPMA scheduler thread fix + W9 | LOOM | `5e33435c8`, `8bd4ed3f1` | ✅ DONE |
| 5 | SyncController HandshakedPeers fallthrough + INFO-5/6 | WRAITH | `8a65bbdb7`, `a73ce7922` | ✅ DONE |
| 6 | HERALD: verify W5 + W11 caller intent | HERALD | — | ✅ DONE |
| 6b | W5 comment + W11 dead arm deletion | MITHRIL | `13aa7585e` | ✅ DONE |
| 7 | FastSync docs + emoji cleanup (INFO-3, INFO-12) | MITHRIL | `3c6be4512` | ✅ DONE |
| 7b | WormToBrainBar shared utility + sync integration | MITHRIL | `c37154287`–`c8a1ddbfc` | ✅ DONE |
| 8 | EngineApi null guard + var fields (S3-A, S3-D, S3-F) | BEACON | `89d6aadb2` | ✅ DONE |
| 9 | JwtAuthenticator return → §8e (S3-C) | FORGE | `6c8a07725` | ✅ DONE |
| 10 | JsonRpcIpcServer var serverSocket lifecycle (S3-G) | CONDUIT | `8ef187dfb` | ✅ DONE |
| G1 | Behavior[Any] narrowing sprint | MITHRIL | — | ✅ DONE 2026-06-26 |
| G2 | SNAP cleanup sprint (S3-B, S3-E, INFO-8) | MITHRIL | — | ✅ DONE 2026-06-26 |
| D1 | INFO-13: RegularSync LoggingAdapter | LOOM | `913c22363` | ✅ DONE |
| D2 | SNAP S3-E task types | LOOM | — | ✅ DONE 2026-06-26 |
| §8e-SNAP1 | SNAPSyncController return clearout (62 sites) | MITHRIL | `ca1446e49` | ✅ DONE |
| POST-MIGRATION-SWEEP | Final Classic residue + AkkaTaskOps deletion | MITHRIL | `82a1e3a43` | ✅ DONE 2026-06-27 |

**Final state:** Zero Classic residue outside TCP floor (`RLPxConnectionHandler:197,235` — permanent, `ClassicActor` alias). `AkkaTaskOps` Classic extension block deleted. All BRIDGE-A/B/C categories resolved for this sprint scope. BRIDGE-E (RegularSync logging) deferred to Wave 3 Network/P2P sprint.

---

## §8a-E6b — ChainWeightCalibrationSpec test rewrite ✅ DONE 2026-06-27

**Commit:** `3c4b15543`

**Audit finding:** `ChainWeightCalibrationSpec` imported and asserted on Classic-layer message
types (`GetHandshakedPeers`, `CalibrateChainWeightNow`) but the production path now sends only
Typed commands (`GetHandshakedPeersCmd`, `CalibrateChainWeightNowCmd`). The mismatch was masked
until the Typed actor rollout completed. `fishForMessage` throws `AssertionError` on any message
not in its partial function — all 18 tests failed.

**Fix scope:**
- Dropped Classic imports; added `GetHandshakedPeersCmd` + `CalibrateChainWeightNowCmd`
- `fishForMessage` partial function updated to Typed cases
- All 10 `expectMsg(CalibrateChainWeightNow)` → `expectMsg(CalibrateChainWeightNowCmd)`

**Result:** 18/18 pass.

---
