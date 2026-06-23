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

## expectMsgType[Any] → concrete type (E165 batch 1)

#### `8cdf1290d` — Narrow 20 expectMsgType[Any] calls in 4 SNAP sync specs
- **What:** `expectMsgType[Any]` → `expectMsgType[ConcreteType]` in TrieNodeHealingCoordinatorSpec (6),
  ByteCodeCoordinatorSpec (4), AccountRangeCoordinatorSpec (4), StorageRangeCoordinatorSpec (6)
- **Types used:** `HealingStatistics`, `ByteCodeCoordinator.ByteCodeProgress`,
  `NetworkPeerManagerActor.SendMessage`, `StorageRangeCoordinator.SyncStatistics`
- **Files changed:** 4 test specs; 104 tests pass; compile clean
- **Blocker documented:** Classic Pekko `TestProbe` (`org.apache.pekko.testkit.TestProbe`)
  has no type parameter — `TestProbe[T]()` syntax is invalid for these files. The P4
  fix strategy's `TestProbe[T]()` is only valid for Typed TestProbe
  (`org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[T]`). The 777-site grep
  metric tracks Classic TestProbe declarations without brackets and cannot be reduced
  without migrating to Typed TestKit infrastructure. See PENDING.md and DEFERRED-BACKLOG P4.

---

## Pekko TestKit Migration — Batch 4 (SNAP coordinator/heal specs)

#### `5eae34c21` — §8a-retro batch 4: coordinator/heal specs → ActorTestKit (135 tests)
- **Root cause fixed:** `HealingTrieFixtures.coordinatorProps` returned `Props` via `PropsAdapter`. Under `ActorTestKitGuardian`, stopping the bridge sent classic `StopChild` to the guardian (which only accepts `TestKitCommand`) → `ClassCastException` → whole-system shutdown cascade across all 14 specs. Fix: replaced `coordinatorProps(...): Props` with `spawnCoordinator(...)(implicit testKit: ActorTestKit): ActorRef[Command]` via `testKit.spawn`.
- **Specs migrated:** `HealingTrieFixtures` (shared fixture) + 4 direct coordinator specs + 10 heal family specs (15 files total)
- **Verification:** 135 tests, 0 failures; `sbt compile-all` clean
- **Workaround still in place:** `ScalaTestWithActorTestKit(ConfigFactory.load())` — proper fix is E5b below
- **New pitfalls documented in pekko-typed-api.md:** P14 (bare `ScalaTestWithActorTestKit()` ctor doesn't load `application.conf`), P15 (`testKit.stop` is a no-op for classic workers spawned via `PropsAdapter`)
- **Cross-refs:** `sync/snap.md` (5eae34c21 entry)

---

## Testing Infrastructure — Deferred Items (E5b / E5c / E5d)

#### E5b — §8a-infra: `application-test.conf` (DEFERRED-BACKLOG Part 8)
- **Problem:** `ScalaTestWithActorTestKit()` bare ctor does not load `application.conf`; custom dispatchers like `sync-dispatcher` throw `ConfigurationException` at runtime. Current workaround: `ConfigFactory.load()` passed explicitly to every test class.
- **Fix:** Create `src/test/resources/application-test.conf`:
  ```hocon
  include "application.conf"
  pekko.actor.default-dispatcher.throughput = 1
  ```
  Then remove `ConfigFactory.load()` from all migrated test classes.
- **Status:** DEFERRED — parallel-safe, no prerequisite (but do before E5d)

#### E5c — §8a-infra-b: worker teardown leak audit (DEFERRED-BACKLOG Part 8)
- **Problem:** `testKit.stop(ref)` is a silent no-op for classic workers spawned via `PropsAdapter` (pekko-typed-api.md P15). Some coordinator/heal specs may have lingering workers.
- **Fix:** Replace `testKit.stop(bridge)` with `testKit.system.classicSystem.stop(bridge)` at all actor teardown sites; verify with `testKit.system.classicSystem.whenTerminated`.
- **Status:** DEFERRED — after E5b + F4 (§8a-retro batch 5)

#### E5d — §8a-retro batch 4b: TestProbe narrowing ~209 sites (DEFERRED-BACKLOG Part 8)
- **Problem:** ~209 `TestProbe()` sites (Classic, untyped) remain across migrated specs. These cannot be narrowed without upgrading to Typed `TestProbe[T]` from `ActorTestKit`.
- **Fix:** Per-spec pass replacing `TestProbe()` with `testKit.createTestProbe[ConcreteType]()` + corresponding `expectMessage[T]` calls.
- **Status:** DEFERRED — after E5b

---

## Open / Deferred

- **E165 `expectMsgType[Any]` — COMPLETE** (`8cdf1290d`) — 0 remaining. §8a-gated remainder:
  - 777 Classic `TestProbe` without `[T]` (requires ActorTestKit migration)
  - 20 `fishForMessage` PF[Any,Boolean] sites in 11 files (replace with `expectMessageType[T]` post-§8a)
  - Both in intentional 333 E165 floor; see DEFERRED-BACKLOG §8a research prompt.
- **Wall-clock assertions** — 3 known test files; S5 sweep (CODEBASE-AUDIT) not yet run
- **TestKit Batch 5** — DEFERRED-BACKLOG §8a-retro batch 5 (F4)
- **E5b** — `application-test.conf` infra fix — DEFERRED-BACKLOG Part 8
- **E5c** — worker teardown leak audit — DEFERRED-BACKLOG Part 8, after E5b + F4
- **E5d** — TestProbe narrowing ~209 sites — DEFERRED-BACKLOG Part 8, after E5b
- `PeerRequestHandler` `ClassTag` unsound → `TypeTest[A,B]` — deferred
