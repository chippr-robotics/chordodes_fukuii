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

## Open

- EIP-2935 account-existence gap tracked in CHASE-QUEUE (FORGE + BEACON before Olympia)
