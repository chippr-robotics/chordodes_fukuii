# api/jsonrpc — JSON-RPC Layer

**Package:** `jsonrpc/` (~79 files: HTTP, IPC, GraphQL, serialization, controllers)
**Gate:** `conduit` on transport/method compliance; `beacon` on engine API methods
**Key files:** `EngineApiService.scala`, `JsonRpcHttpServer.scala`, `JsonRpcIpcServer.scala`, `JsonRpcController.scala`

---

## Pekko Classic → Typed Migration (W2-P2b)

- **Scope:** JSON-RPC HTTP/IPC/GraphQL server actors migrated from Classic → Typed
- **Cross-refs:** `node/bootstrap.md` (NodeBuilder spawn wiring)

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Memory and Resource Leak Audit (DEFERRED-BACKLOG 8c)

#### `4f5a678fa` — H1-A: EngineApiService primary memory leak
- **What:** Unbounded message accumulation in `EngineApiService`; bounded queue with eviction policy

#### `8911135d9` — H1-B: EngineApiService secondary leak
- **What:** Second unbounded accumulation path plugged in `EngineApiService`

---

## IO/Threading Audit (DEFERRED-BACKLOG 8d + 8d-J) — ALL ITEMS DONE

#### `0a8ed3038` — IO.defer fix in EngineApiService
- **What:** `IO.pure(unsafeComputation())` replaced with `IO.defer { IO.pure(unsafeComputation()) }` — computation deferred until IO evaluation

#### `276c77735` — B1: actorSystem.dispatcher as EC in RPC handler
- **What:** `Future` in JSON-RPC handler switched from global EC to `actorSystem.dispatcher`

#### (no commit) — A1: Await.result → IO.fromFuture in forkchoiceUpdated pending-tx fetch
- **What:** Verified 2026-06-24. `Await.result` on CE3 compute thread (threading-model-audit.md A1) was already fixed before the backlog entry was written. `IO.fromFuture` at lines 629–640 with source comment confirming intent. No code change needed.

#### §8d-J1 — AdminService.scala: IO { } → IO.blocking { } for file ops (2026-06-24)
- **Files:** `AdminService.scala` lines 335–361 (exportChain + importChain)
- **What:** Bare `IO { }` wrapping `FileOutputStream`/`FileInputStream` blocking loops replaced with `IO.blocking { }` — shifts to CE3 blocking pool, releases compute thread during long chain export/import operations

#### §8d-J2 — GraphQLSchema.scala: eliminate unsafeRunSync inside Sangria resolver (2026-06-24)
- **File:** `GraphQLSchema.scala` line 992
- **What:** `.unsafeRunSync()` on inner `IO` inside a Sangria resolver `flatMap` body (executing on Pekko-HTTP dispatcher thread) replaced by composing both IO calls in IO context before the single `.unsafeToFuture()` at the resolver boundary — no synchronous materialisation inside the Future chain

#### §8d-J3 — JsonRpcIpcServer.scala: model IPC timeout in IO (2026-06-24)
- **File:** `JsonRpcIpcServer.scala` line 102
- **What:** `responseF.unsafeRunTimed(awaitTimeout)` (using `IORuntime.global`, shared across HTTP/GraphQL/IPC) replaced with `IO.timeout(awaitTimeout)` + `unsafeRunSync()` — timeout modelled in IO; eliminates `IORuntime.global` contention on the IPC transport path

---

## Scala 3 Idioms

#### `b305ef41b` — 3d: FaucetStatus → enum
- **What:** `sealed trait FaucetStatus` + 2 case objects in `faucet/package.scala` → `enum FaucetStatus`
- **File:** `faucet/package.scala`
- **Call sites unchanged:** `FaucetStatus.FaucetUnavailable`, `FaucetStatus.WalletAvailable` — same qualified paths

---

## Quality Fixes

#### `8ef187dfb` — S3-G: JsonRpcIpcServer var serverSocket lifecycle
- **What:** `var serverSocket = uninitialized` → `Option[ServerSocket]`; `close()` is no-op before `run()`; thread body captures `val socket`

---

## P9 DisabledTest Fixes

#### `2803d192f` — §P9-JSON4S: re-enable 4 jsonrpc DisabledTests — fix json4s ScalaSig under testEssential
- **Root cause:** `JsonSerializers.formats` was missing `RpcErrorJsonSerializer`. `JsonRpcIpcServer.Serialization.write(errorResponse)` triggered json4s ScalaSig reflection on `JsonRpcError` (a Scala 3 case class lacking Scala 2 `ScalaSig` bytecode metadata), polluting the json4s reflection cache. Subsequent suites under `testEssential` hit the corrupted cache and failed with `MappingException: Can't find ScalaSig`.
- **Fix:** Added `RpcErrorJsonSerializer` to `JsonSerializers.formats` in `JsonSerializers.scala`. Production fix — not a test-only change.
- **Tests re-enabled (4):** `JsonRpcControllerSpec.scala:73`, `:124`; `JsonRpcControllerEthSpec.scala:556`, `:849`. `DisabledTest` tag removed; `UnitTest + RPCTest` tags remain.
- **Gate:** testEssential — 3,600 tests, 0 failures, 669s

#### `21d2a46f0` — §P9-TXRECEIPT: re-enable "calculate correct contract address"
- **What:** `EthTxServiceSpec.scala:369` — test was already correct (baseLogIndex drift had been fixed in a prior session); only the `DisabledTest` tag and stale `// TODO` comment remained. Both removed.
- **Result:** 24/24 `EthTxServiceSpec` tests pass

---

## Classic Interop — §8k-G (COMMITTED `2ef2b6637`, testEssential pending)

#### `2ef2b6637` — refactor(8k-G): OQ-5 kill — Typed ask for jsonrpc callers (Clusters C+E+L)
- **What:** Cluster L — `AkkaTaskOps.askFor[T]` Classic ask pattern eliminated in jsonrpc service callers. `SyncProtocol` commands (`GetStatus`, `ResetFastSync`, `RestartFastSync`) became `final case class` with `replyTo: ActorRef[T]`. Jsonrpc services (`SyncService.scala`, `PersonalService.scala`, etc.) updated to use Typed ask (`?`) with typed `replyTo`. NodeBuilder: `syncController` type lifted from `TypedActorRef[Any]` → `TypedActorRef[SyncController.Command]`; spawn-site `.toClassic` in NodeBuilder removed (Cluster K).
- **Deferred — Cluster E:** `externalAdapter.toClassic` at spawn sites (21 sites in FastSync, RegularSync, SyncController) — NOT the same as OQ-5. Per-child constructor param lift deferred to §8k-G2 (immediate: FastSync + NPMA) and per-child LOOM migrations. See DEFERRED-BACKLOG §8k-G2.
- **Mining cascade:** `MinedBlock` signature updated across `PoWMiningCoordinator`, `EthashMiner`, `Miner`, `MockedMiner` — replyTo lifted.
- **Scope:** 25 files changed (5 jsonrpc services, `SyncProtocol.scala`, `SyncController.scala`, `FastSync.scala`, `RegularSync.scala`, `NodeBuilder.scala`, 4 mining actors, 8 test files)
- **Docs:** Clearout commit pending (after testEssential passes)

---

## §NAMING-MICRO (partial) — EthSimulateService.scala (2026-06-25)

#### `440896c4e` — `isPreMerge` → `isPoW` in `EthSimulateService.scala` (local val + condition, ~line 351/355)
- **Cross-refs:** `consensus/engine.md §NAMING-MICRO` for full entry; `completed/DEFERRED-BACKLOG.md §NAMING-MICRO`

---

## §ETH-T10-A — `engine_getPayloadV5` + BlobsBundleV2 (2026-06-25)

#### `62bc47ac0` — feat(eth): BlobsBundleData adds cellProofsPerBlob for EIP-7594/PeerDAS
- **Files:** `EngineApiService.scala`
- **What:** `BlobsBundleData` inner class extended with `cellProofsPerBlob: Seq[Seq[ByteString]]`; `getPayloadBlobsBundle` stubs 128 empty cell-proof ByteStrings per blob pending real KZG backend

#### `b131a5ec7` — feat(eth): engine_getPayloadV5 with BlobsBundleV2 and Osaka fork gating
- **Files:** `EngineApiController.scala`, `EngineApiService.scala`, `EngineApiGetPayloadV5Spec.scala`, `src/test/resources/application.conf`
- **What:**
  - `engine_getPayloadV5` case in `handleRequest`
  - `handleGetPayload` extended with Osaka fork gating: V4 rejects Osaka payloads (`-38005`); V5 rejects pre-Osaka payloads (`-38005`); V5+ returns `BlobsBundleV2` with `proofs = bundle.cellProofsPerBlob.flatten`
  - `exchangeCapabilities` updated to include `"engine_getPayloadV5"`
  - `EngineApiGetPayloadV5Spec`: 3 tests covering V4/V5 fork rejection and V2 envelope shape
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T10-A`, `eth-sepolia-assumption-audit.md Thread 10`

---

## §ETH-T10-B — `engine_getBlobsV2` + `BlobAndProofV2` (2026-06-25)

#### `a40750ce6` — fix(eth): implement engine_getBlobsV2 — BlobAndProofV2 cell proofs for PeerDAS blob serving (T10-B)
- **Files:** `EngineApiDomain.scala`, `EngineApiController.scala`, `EngineApiService.scala`, `EngineApiGetBlobsV2Spec.scala`
- **What:**
  - `BlobAndProofV2(blob, cellProofs)` added to `EngineApiDomain`
  - `engine_getBlobsV2` dispatched in `handleRequest` → `handleGetBlobsV2` (returns `JNull` per versioned hash — no mempool blob index by hash; CL falls back to peer gossip)
  - `"engine_getBlobsV2"` added to `exchangeCapabilities`
  - `EngineApiGetBlobsV2Spec`: 4 tests (null per hash, empty list, single hash, capabilities)
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T10-B`, `eth-sepolia-assumption-audit.md Thread 10`

---

## Open / Deferred

- json4s Manifest synthesis warnings (68 hits) — externally gated on json4s 4.2.0-M5 release
- Additional jsonrpc IO boundary sites (CONDUIT scan) — DEFERRED-BACKLOG §8d (A1/B1/B2 all done; this is the remaining LOW-priority CONDUIT sweep)
- W3-P3a: `implicit val`/`implicit def` → `given`/`using` in jsonrpc/ (15 candidates) — DEFERRED-BACKLOG §3a
