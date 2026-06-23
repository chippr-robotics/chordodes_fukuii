# ext/rlp — RLP Submodule

**Package:** `rlp/`
**Gate:** `forge` for codec changes (wire format)
**sbt module:** `rlp`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Scope in this package:** All `*.scala` files in `rlp/`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## W2-P3a: Implicit → Given/Using

- **Scope:** RLP codec `implicit val`/`implicit def` codec instances — syntax-only migration; instances stay
- **Key files:** `ForkId.scala:108`, `RLPCodecs.scala:103,149`, `AuthResponseMessageV4.scala:18`, `ETHPackets.scala:56,59,71`, `MerklePatriciaTrie.scala:20`, `mpt/package.scala:19`
- **Gate:** HERALD for ETHPackets (wire-format codec); forge for ForkId
- **Status:** Open — in DEFERRED-BACKLOG §3a scope
