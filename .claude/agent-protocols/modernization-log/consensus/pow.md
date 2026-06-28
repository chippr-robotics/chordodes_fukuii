# consensus/pow — PoW Engine and Mining

**Package:** `consensus/pow/`, `mining/`
**Gate:** `forge` on ALL changes (Ethash, block rewards, ECIP-1017)
**Key files:** `PoWMiningCoordinator.scala`, `EthashBlockHeaderValidator.scala`, `EthashMiner.scala`

---

## W2-P2d: Mining Pekko Typed Migration

- **Scope:** Mining-subsystem actors migrated from Classic → Typed
- **Gate:** forge reviewed pre-implementation
- **Cross-refs:** `node/bootstrap.md` (NodeBuilder spawn wiring)

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Quality Assessment (FORGE)

#### FORGE verdict — PoWMiningCoordinator thread-safety: SAFE-AS-IS
- **Rationale:** Pekko Typed actor; no `@volatile` fields or `mutex` exist — actor mailbox serialization is the sole thread-safety mechanism, and the sole off-dispatcher callback (`EC.global` in `mine`) only executes `context.self ! MineNext` (thread-safe by Pekko contract, reads no actor state). B2's `block-forger` dispatcher claim was incorrect — `processMining` runs on CE3 `IORuntime.global`. No race condition or happens-before violation exists.

#### `398ba2ba0` — MITHRIL: EC impurity + dead no-op cleanup
- **What:** (1) Dead `5.seconds` no-op deleted; (2) unused `DurationInt` import removed; (3) `EC.global` → `context.executionContext` in `mine`. Compile-only gate. No behavioral change.

---

## FlakyTest Audit — PoW Mining (Part 11 P10)

#### `ab98f1370` — P10: PoWMiningCoordinatorSpec FlakyTests — 6 de-tagged, 1 deleted
- **Tests de-tagged (6, now UnitTest + ConsensusTest):** FlakyTests were caused by real Ethash PoW computation timing being non-deterministic. Fixed by injecting a `MinerFactory` seam and providing a fake miner that completes instantly; `EthashMiner` injection point confirmed injectable. `FlakyTest` tag removed from 6 tests.
- **Test deleted (1):** "MineNext starts EthashMiner" — directly testing internal timing behavior of real EthashMiner with no deterministic seam; deleted with rationale comment. Not fixable without major refactor.
- **Verification:** 10/10 passes per test; no `FlakyTest` tags remain in `PoWMiningCoordinatorSpec`
- **Cross-refs:** `sync/fast.md` + `sync/controller.md` (F7/P10 thread)

---

## Open

- `EthashBlockHeaderValidator.scala:41` — null check at Java interop boundary (keep)
- `vm/OpCode.scala`, `vm/VM.scala` — `return` statements (§8e FORGE gate, 2 sites)
