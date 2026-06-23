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

## Open

- `NodeBuilder.scala:236,1094` — `implicit` `ExecutionContext`/`IORuntime` → `given` candidates (§3a scope)
- `MockedMiner.Send` envelope cleanup — noted in CAPSTONE post-mortem; deferred
- `ProgressProtocol.ImportedBlock` in `runningRegularSyncBootstrap` — noted in ROOT Phase 3; deferred
