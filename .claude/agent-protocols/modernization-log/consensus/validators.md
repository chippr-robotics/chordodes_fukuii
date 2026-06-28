# consensus/validators — Block/Tx Validators

**Package:** `consensus/validators/`, `eip1559/`, `mess/`
**Gate:** `forge` (ETC) / `beacon` (ETH) on ALL changes

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Scala 3 Idioms

#### `64ab4786e` — §3i: `.reason` → `.describe` call site updates
- **What:** `StdValidators.scala:76` and `ValidatorsExecutor.scala:106` updated from `.reason` to `.describe` following the `BlockExecutionError` hierarchy redesign (see `storage/ledger.md` for full context). No logic change — these are pure call-site updates following the renamed accessor.
- **Gate:** FORGE + BEACON both pre-approved (FORGE: no RLP/JSON impact; BEACON: ETH error variants extend correct base types)
- **Cross-refs:** `storage/ledger.md` (redesign), `ledger/BlockExecutionError.scala` (hierarchy)

---

## §8e-FORGE: `return` → expression FORGE pass

#### `4544b8025` — §8e-FORGE: StdSignedTransactionValidator `return` conversions (FORGE-reviewed, 2026-06-24)
- **`:65` + `:67` (`validateOlympiaTxTypes` ETH and Olympia guards)** — both CLEAR. Two sequential `Either`-returning guards → `if … else if … else { stx.tx match }`. No mutable state, no loop, no crypto; identical result.
- **Gate:** FORGE sign-off. **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8e-FORGE`

---

## §ETH-T1-A/B: Timestamp-aware EvmConfig in StdSignedTransactionValidator

#### `ed4db9df9` — fix(eth): use timestamp-aware EvmConfig in validateInitCodeSize (2026-06-24)
- **What:** `validateInitCodeSize` upgraded from 2-arg to 3-arg `EvmConfig.forBlock`. The 2-arg overload returns London-era config (eip3860Enabled=false) on ETH/Sepolia regardless of timestamp, silently accepting oversized `CREATE` initcode post-Shanghai.
- **Gate:** BEACON sign-off. ETC unaffected — `isShanghaiTimestamp` always false on ETC.
- **Tests:** 3 new in `StdSignedTransactionValidatorSpec` (ETH post/pre-Shanghai, ETC).
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T1-A`

#### `6f8f74708` — fix(eth): use timestamp-aware EvmConfig in validateGasLimitEnoughForIntrinsicGas (2026-06-24)
- **What:** Same 2-arg → 3-arg upgrade in `validateGasLimitEnoughForIntrinsicGas`. EIP-3860 word cost (2 gas/word) was excluded from intrinsic-gas floor post-Shanghai.
- **Test note:** The default test config has `byzantium-block-number = 4370000`; `forBlock` uses `maxBy((blockNum, priority))` so Byzantium at 4370000 beats any ETC fork at 0. Test config places `mystiqueBlockNumber = 5_000_000` (above Byzantium) to get `MystiqueFeeSchedule` at block 21M.
- **Gate:** BEACON sign-off. ETC unaffected.
- **Tests:** 2 new in `StdSignedTransactionValidatorSpec` (total 5 tests).
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T1-B`

---

## §ETH-T4-B: maxFeePerBlobGas validation for Type-3 transactions (2026-06-25)

#### `bd43ed49b` — fix(eth): validate maxFeePerBlobGas >= blobBaseFee for Type-3 transactions (EIP-4844)
- **What:** Added `validateMaxFeePerBlobGas` to `StdSignedTransactionValidator`; added `TransactionMaxFeePerBlobGasTooLow` error variant to `SignedTransactionValidator`. Type-3 transactions with `maxFeePerBlobGas < blobBaseFee` now rejected (previously silently accepted).
- **Gate:** BEACON sign-off. ETC unaffected — `BlobTransaction` is unreachable on all ETC/Mordor configs.
- **Tests:** 4 new in `StdSignedTransactionValidatorSpec`.
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §ETH-T4-B`

---

## Open

- EIP-2935 account-existence gap tracked in CHASE-QUEUE (FORGE + BEACON before Olympia)
