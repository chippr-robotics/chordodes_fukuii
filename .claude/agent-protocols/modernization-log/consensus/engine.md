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

## §8e-BEACON: `return` → expression BEACON pass

#### `d78177bda` — §8e-BEACON: EngineApiController `return` conversions (BEACON-reviewed, 2026-06-24)
- **`:96` (`handleNewPayload` error guard)** — CLEAR. `return IO.pure(errResp)` → `decode match { case Left(e) => IO.pure(errResp); case Right(params) => <body> }`. Byte-identical error response: `PayloadStatusV1(Invalid, None, "malformed payload: $msg")`.
- **`:226` (`handleForkchoiceUpdated` error guard)** — CLEAR. Same pattern; tuple destructured in `Right((fcs, payloadAttrs))` pattern, eliminating `.toOption.get`. Byte-identical error response: JSON-RPC `-38003` code.
- **`:447` (priority-fee helper)** — CLEAR. `if receipts.isEmpty then return "0x0"` → `if/else` expression. Pure hex-string builder; zero consensus-logic change.
- **Verify:** 16/16 EngineApiSpec ✅. `grep -n "\breturn\b" EngineApiController.scala` → 0 code-level hits. **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8e-BEACON`

---

## §NAMING-A: Rename PostMerge → PoS (terminology alignment)

#### `35db7dc61` — refactor: rename PostMerge → PoS — align with BlockHeader.isPoS/isPoW canonical pattern (2026-06-25)
- **What:** Pure rename across 10 files (2 `git mv`, 8 symbol renames). `PostMergeBlockHeaderValidator` → `PoSBlockHeaderValidator`; `validatePostMerge*` → `validatePoS*`; `PostMergeNonceError`/`PostMergeOmmersError` → `PoSNonceError`/`PoSOmmersError`; `isPostMergeChain` → `isPoSChain`; `isPostMerge(totalDifficulty)` → `isPoS(totalDifficulty)` in `BlockchainConfig.scala`.
- **Why:** "PostMerge" conflates ETH's historical Merge event with the consensus type. ETC never had a merge. `BlockHeader.scala:85-86` already defines the canonical `isPoS`/`isPoW` vocabulary — all validators now align.
- **Preserved:** `postMerge`/`preMerge` local vals in `ETH69OscillationChainWeightSpec` and `postMergeHeader` in `BlockExecutionSpec` (those refer to the ETH Merge event specifically, not consensus type).
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §NAMING-A`, `consensus/validators.md`

---

## §NAMING-MICRO: Rename residual preMerge/postMerge identifiers (2026-06-25)

#### `440896c4e` — refactor: rename preMergeValidator→poWValidator, isPreMerge→isPoW, postMergeHeader→poSHeader — align 7 identifiers with BlockHeader.isPoS/isPoW vocabulary
- **What:** 7 identifier renames across 3 files. `TransitionBlockHeaderValidator`: `preMergeValidator` → `poWValidator` (field + 2 call sites). `EthSimulateService`: `isPreMerge` → `isPoW` (local val + condition). `BlockExecutionSpec`: `postMergeHeader` → `poSHeader` (2 occurrences).
- **Why:** These local vals/methods described the *consensus type of a block* (PoW vs PoS) using ETH-Merge event language. `§NAMING-A` (`35db7dc61`) established the `isPoS`/`isPoW` vocabulary from `BlockHeader.scala:85-86`; this follow-up aligns the remaining 7 identifiers that were deferred at the time.
- **Preserved:** `postMerge`/`preMerge` in comments, `ETH69OscillationChainWeightSpec` (historical ETC mining event), and all `ethereum-tests` canonical identifiers — unchanged.
- **Tests:** `testOnly *BlockExecution*` — 15/15 pass. No dedicated specs for `TransitionBlockHeaderValidator` or `EthSimulateService`.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §NAMING-MICRO`, `§NAMING-INV` (audit that sourced the list), `api/jsonrpc.md`

---

## Open

- `Ordering.Iterable` deprecation warning — BEACON gate

---

## §7f — ForkChoiceManager.setListener TypedActorRef (2026-06-27)

#### `456f12499` — §8k-G4a: ForkChoiceManager.setListener — TypedActorRef narrow adapter
- **What:** `AtomicReference[ActorRef]` → `AtomicReference[Option[TypedActorRef[ForkChoiceManager.BeaconHead]]]`. `SyncController` passes `ctx.messageAdapter[ForkChoiceManager.BeaconHead](WrappedExternal.apply)` as a narrow typed adapter. Removes the last non-TCP `.toClassic` conversion in the codebase.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §7f`, `sync/controller.md §8k-N`
