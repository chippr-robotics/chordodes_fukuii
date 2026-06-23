# storage/ledger — Transaction Execution Context

**Package:** `ledger/`
**Gate:** `forge` on receipt/gas semantics
**Key files:** `BlockPreparator.scala`, `BlockExecutionContext.scala`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Open

- `ledger/BlockPreparator.scala:56` — `return` statement (§8e FORGE gate)
