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

## Open

- EIP-2935 account-existence gap tracked in CHASE-QUEUE (FORGE + BEACON before Olympia)
