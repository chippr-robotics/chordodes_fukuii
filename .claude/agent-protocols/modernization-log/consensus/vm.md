# consensus/vm — EVM Opcode Dispatch and Gas Metering

**Package:** `vm/`
**Gate:** `forge` (ETC opcodes) / `beacon` (ETH opcodes, Osaka EIPs) on ALL changes
**Key files:** `VM.scala`, `OpCode.scala`, `PrecompiledContracts.scala`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Open

- `vm/VM.scala:140`, `vm/OpCode.scala:989`, `vm/PrecompiledContracts.scala:271` — `return` statements (§8e FORGE gate)
- `vm/OpCode.scala` — infix/wildcard warnings (9 hits, FORGE gate)
