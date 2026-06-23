# node/testing-infra — Test Infrastructure

**Package:** `src/test/` utilities, spec base classes
**Gate:** None
**Key files:** TestKit migration utilities, `ActorTestKit`, spec base traits

---

## TestKit Migration (DEFERRED-BACKLOG 8a, Batches 1-2)

Migrating from `TestActorRef` / `TestKit` (Classic) to `ActorTestKit` (Typed).

- **Batch 1:** Core actor specs migrated to `ActorTestKit`
- **Batch 2:** Sync-subsystem specs migrated
- **Batch 3:** Pending — DEFERRED-BACKLOG §8a open

---

## ETH Test Coverage (DEFERRED-BACKLOG Part 9, G1-G5)

All 5 ETH coverage gaps closed:

#### `583aded58` — G1: ETH engine API integration baseline tests
- **What:** Initial ETH/Sepolia happy-path spec

#### `dbef878` — G2: ETH fork-transition tests
- **What:** Timestamp-based fork dispatch coverage (Sepolia forks)

#### `00166a555` — G2-R: G2 retroactive fixes
- **What:** Edge cases from G2 spec cleaned up

#### `ef5ad3376` — G3: blob transaction (EIP-4844) handling tests
- **What:** Blob tx RLP, type-3 transaction validation coverage

#### `f4746250e` — G4: withdrawal handling tests
- **What:** EIP-4895 withdrawal processing coverage

#### `12a79b7c3` — G5: multi-chain parity tests
- **What:** ETC vs ETH isolation — tests confirm chain paths don't cross-contaminate

---

## EIP-2935 Account-Existence Fix

#### `bbc5f1df8` — Account-existence check gap
- **What:** `BlockExecution.applyEip2935` missing account-existence guard; added
- **Note:** Fix is tested; FORGE + BEACON review required before Olympia activation

---

## Scala 3 Idioms

#### `b305ef41b` — 3d: SealEngineType → enum
- **What:** `sealed trait SealEngineType` + 2 plain `object` singletons in `testmode/SealEngineType.scala` → `enum SealEngineType` with `case NoProof` / `case NoReward`
- **File:** `testmode/SealEngineType.scala`
- **Call sites unchanged:** `SealEngineType.NoProof`, `SealEngineType.NoReward` — same qualified paths

---

## Open / Deferred

- **E165 TestProbe in `FastSyncBranchResolverSpec`** — 5 pre-existing E165 warnings; deferred to spec-cleanup pass (PENDING.md)
- **Wall-clock assertions** — 3 known test files; S5 sweep (CODEBASE-AUDIT) not yet run
- **TestKit Batch 3** — DEFERRED-BACKLOG §8a open
- `PeerRequestHandler` `ClassTag` unsound → `TypeTest[A,B]` — deferred
