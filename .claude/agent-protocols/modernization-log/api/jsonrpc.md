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

## IO/Threading Audit (DEFERRED-BACKLOG 8d)

#### `0a8ed3038` — A1: IO.defer fix in EngineApiService
- **What:** `IO.pure(unsafeComputation())` replaced with `IO.defer { IO.pure(unsafeComputation()) }` — computation deferred until IO evaluation

#### `276c77735` — B1: actorSystem.dispatcher as EC in RPC handler
- **What:** `Future` in JSON-RPC handler switched from global EC to `actorSystem.dispatcher`

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

## Open / Deferred

- json4s Manifest synthesis warnings (68 hits) — externally gated on json4s 4.2.0-M5 release
- B2 (IO/threading audit): additional IO boundary sites — DEFERRED-BACKLOG §8d
- W3-P3a: `implicit val`/`implicit def` → `given`/`using` in jsonrpc/ (15 candidates) — DEFERRED-BACKLOG §3a
