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

## Open

- `ledger/BlockPreparator.scala:56` — `return` statement (§8e FORGE gate)
