# storage/ledger — Transaction Execution Context

**Package:** `ledger/`
**Gate:** `forge` on receipt/gas semantics
**Key files:** `BlockPreparator.scala`, `BlockExecutionContext.scala`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

#### `64ab4786e` — §3i: BlockExecutionError hierarchy redesign (FORGE + BEACON approved)
- **What:** Replaced `sealed trait BlockExecutionError { val reason: Any }` with `sealed trait BlockExecutionError { def describe: String }`. Added `type ValidationError = BlockHeaderError | BlockError | OmmersError` union alias. Updated all 5 production call sites (`BlockExecution.scala`, `StdValidators.scala:76`, `ValidatorsExecutor.scala:106`, and 2 others) + 1 test call site: `.reason` → `.describe`.
- **Why:** `reason: Any` is an untyped escape hatch; the actual LUB of all stored values is the union type; `describe: String` is the only external contract callers need.
- **FORGE approval:** Confirmed no RLP encoding, JSON serialization, or pattern-match exhaustiveness impact.
- **BEACON approval:** All ETH-specific error variants (`PostMergeNonceError`, `MissingWithdrawalsRootError`, `BlockWithdrawalsRootError`, etc.) already extend `BlockHeaderError`/`BlockError` — the union is complete for both chains.
- **Verification:** 30 tests pass; `sbt compile-all` clean
- **Cross-refs:** `consensus/validators.md` (call sites in StdValidators/ValidatorsExecutor)

---

## §8e-FORGE: `return` → expression FORGE pass

#### `4544b8025` — §8e-FORGE: BlockPreparator `return` conversions (FORGE-reviewed, 2026-06-24)
- **`:56` (`payBlockReward` post-merge guard)** — CLEAR. `if isPostMerge then ws else { ECIP-1017 rewards }`. Reward logic untouched; byte-identical.
- **`:89` (`creditBaseFeeToTreasury` Olympia guard)** — CLEAR. Same guard→if/else pattern; ECIP-1111 treasury credit path unchanged.
- **`:733` (`recoverAuthority` chain-id guard)** — CLEAR. Converted to match `applyAuthorization:771` (identical check, already idiomatic) — proven byte-identical precedent.
- **Gate:** FORGE sign-off. **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8e-FORGE`

---

## §ETH-T2-A: `isPostMerge` → `isPoS` call-site update (2026-06-24)

#### `c470b3dac` — rename block-level PoS predicate to chain-agnostic vocabulary
- **File:** `ledger/BlockPreparator.scala` — PoW reward skip guard
- **What:** `block.header.isPostMerge` → `block.header.isPoS`. No logic change.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T2-A`

---

## §ETH-T3-LOG: Thread 3 treasury-zero log.error gate (BEACON, 2026-06-24)

#### `f868b75a8` — gate treasury-zero log.error to ETC chains only
- **What:** `creditBaseFeeToTreasury` logged `log.error` on every ETH/Sepolia block because `treasury-address=0` is the correct ETH configuration (base fee burns), not a misconfiguration. Added `networkType == NetworkType.ETC` guard so the error only fires for ETC chains where a non-zero treasury is expected.
- **Why:** ETH/Sepolia assumption audit Thread 3 — log.error false-alarm, no consensus impact.
- **Verification:** `sbt compile-all` — clean
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T3-LOG`

---

## §ETH-T4-C: EIP-4788 beacon roots contract deployment (2026-06-25)

#### `b934caffe` — fix(eth): deploy EIP-4788 beacon roots contract bytecode (code+nonce=1)
- **What:** `applyEip4788SystemCall` in `BlockExecution.scala` now deploys `HISTORY_STORAGE_CONTRACT_CODE` + `nonce=1` to `0x4242…` at the first Cancun block, eliminating state root divergence from canonical Sepolia caused by missing account code/nonce.
- **Gate:** BEACON sign-off. Guarded by `cancunTimestamp.isDefined` — never runs on ETC.
- **Tests:** 158-line `BeaconRootsSpec.scala` (new).
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T4-C`

---

## §ETH-T4-D: Blob base fee formula unification (2026-06-25)

#### `f6cf7fb9c` — fix(eth): unify blob base fee formula — deductBlobGas now uses BlobGasUtils.getBlobGasPrice
- **What:** Deleted `BlockPreparator.computeBlobBaseFee` / `fakeExponential` (only knew Cancun/Prague fractions). All 3 deduction call sites now use `BlobGasUtils.getBlobGasPrice(excess, ts, config)`, which handles EIP-7892 BPO1/BPO2 post-Osaka fractions. Single source of truth restored.
- **Gate:** BEACON sign-off. Blob paths unreachable on ETC (no `cancunTimestamp`).
- **Tests:** New `"deductBlobGas"` section in `BlockPreparatorSpec` (25/25 pass).
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T4-D`

---

## Open

_(no open items)_
