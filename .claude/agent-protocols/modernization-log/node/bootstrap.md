# node/bootstrap — Node Bootstrap

**Package:** `nodebuilder/`, `cli/`, `runtime/`, `forkid/`, `healthcheck/`, `metrics/`, `faucet/`
**Gate:** None (infrastructure)
**Key files:** `NodeBuilder.scala`, `StdNode.scala`

---

## CAPSTONE: Root Actor Flip

#### W3-ROOT/CAPSTONE commits — `ActorSystem[Nothing]` root
- **What:** NodeBuilder converted from Classic `ActorSystem` to Typed `ActorSystem[Nothing]`; this was the final barrier to a fully Typed main path
- **Cross-refs:** `sync/controller.md` (SyncController, last coordinated migration step)
- **Result:** Zero `extends Actor` in `src/main` (3 intentional Classic TCP bridges remain)

---

## Faucet Pekko Typed Migration (W2-P2a)

- **Scope:** Faucet actor migrated from Classic → Typed
- **Cross-refs:** `api/jsonrpc.md` (NodeBuilder spawn wiring)

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Resource Lifecycle Fixes (DEFERRED-BACKLOG 8c)

#### `4907406fe` — H2/H3: StdNode teardown fix
- **What:** `StdNode.close()` missing `.waitForShutdown()` call; shutdown race on actor system teardown plugged
- **Cross-refs:** `storage/db.md` (same commit covers RocksDB and FileUtils)

---

---

## Scala 3 Idioms

#### `c1ecd9706` — 3d-A: ServerStatus → enum
- **What:** `sealed trait ServerStatus` + 2 cases (`NotListening`, `Listening(address: InetSocketAddress)`) in `utils/NodeStatus.scala` → Scala 3 `enum`. No caller-file changes required; all 10 call sites across `jsonrpc/`, `network/`, `nodebuilder/`, and test/it scopes compiled and tested green. `isInstanceOf[ServerStatus.Listening]` in `ServerActorSpec` works unchanged on parameterised enum cases.

---

## Classic Interop — §8k-G (COMMITTED `2ef2b6637`, testEssential pending)

#### `2ef2b6637` — refactor(8k-G): NodeBuilder syncController type lifted (Cluster K)
- **What:** `syncController` field in `NodeBuilder.scala` lifted from `TypedActorRef[Any]` → `TypedActorRef[SyncController.Command]`. Spawn-site `.toClassic` for `syncController` removed (Cluster K — 3 sites: SyncController, NPMA, RPC registration).
- **Cross-refs:** `sync/controller.md` (Cluster C — OQ-5 sender() elimination), `api/jsonrpc.md` (Cluster L — jsonrpc service callers)

---

## Open

- `NodeBuilder.scala:236,1094` — `implicit` `ExecutionContext`/`IORuntime` → `given` candidates (§3a scope)
- `MockedMiner.Send` envelope cleanup — noted in CAPSTONE post-mortem; deferred (see DEFERRED-BACKLOG §9b prompt)
- `ProgressProtocol.ImportedBlock` in `runningRegularSyncBootstrap` — noted in ROOT Phase 3; **gate NOW OPEN** (§8k-F `b24515637` + §8k-G `2ef2b6637` both committed). Actionable as a LOOM task: check whether `ImportedBlock` handler in `runningRegularSyncBootstrap` can be tightened now that RegularSync is fully Typed.

---

## §ETH-T4-A: KZG trusted setup loaded at node startup (2026-06-25)

#### `02aaa05fc` — fix(eth): load KZG trusted setup at startup (EIP-4844)
- **File:** `src/main/scala/com/chipprbots/ethereum/Fukuii.scala`
- **What:** Added KZG initialization block after `ConfigValidator.validate` and before node construction. Calls `CKZG4844JNI.loadNativeLibrary()` then `CKZG4844JNI.loadTrustedSetupFromResource("/trusted_setup.txt", classOf[CKZG4844JNI])`. Startup failure logs an error but does not abort — the precompile will revert all calls if the setup is absent (safe degradation).
- **Guard:** `if Config.blockchains.blockchainConfig.forkTimestamps.cancunTimestamp.isDefined` — ETC/Mordor nodes skip this block entirely.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T4-A`, `consensus/vm.md §ETH-T4-A`
