# consensus/engine — ETH PoS Execution Engine

**Package:** `consensus/engine/`
**Gate:** `beacon` on ALL changes (ETH PoS, timestamp forks, blob transactions)
**Key files:** `EngineApiService.scala`, `EngineApiController.scala`, `EngineApiHttpServer.scala`, `JwtAuthenticator.scala`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Quality Fixes (beacon-reviewed)

#### `4f5a678fa` — H1-A: EngineApiService memory leak (8c batch)
- **What:** Unbounded message accumulation in EngineApiService; bounded queue with eviction
- **Cross-refs:** `api/jsonrpc.md` (same audit wave)

#### `8911135d9` — H1-B: EngineApiService secondary leak (8c batch)
- **What:** Secondary unbounded accumulation path plugged
- **Cross-refs:** `api/jsonrpc.md`

#### `0a8ed3038` — A1: IO.defer fix in EngineApiService (8d audit)
- **What:** Unsafe IO.pure wrapping deferred computation → IO.defer

#### `89d6aadb2` — S3-A/S3-F: Option guard + immutable var fields
- **What:** `pendingTransactionsManager != null` → `Option[ActorRef[...]]`; `var fields` JSON builder → `val`
- **Scope:** `EngineApiService.scala:525`, `EngineApiController.scala:704,712`, `EngineApiHttpServer.scala:169`

#### `6c8a07725` — S3-C: JwtAuthenticator return guard clause
- **What:** `return Left(...)` at :51 → `if/else` expression; §8e FORGE count 7→6

---

## Open

- `EngineApiController.scala:96,226` — `return IO.pure(...)` inside IO (S3-D, §8e BEACON gate)
- `Ordering.Iterable` deprecation warning — BEACON gate
