# core/mpt — Merkle Patricia Trie

**Package:** `mpt/`
**Gate:** `forge` (state root computation is consensus-critical)
**Key files:** `MerklePatriciaTrie.scala`, `StackTrie.scala`, `package.scala`

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Scope:** All mpt/ files
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Scala 3 Idioms

#### `7cc9eda3a` — 3c: isInstanceOf → pattern match
- **What:** 1 site in `mpt/Node.scala:33` replaced; `MptNode.equals(Any)` converted from `isInstanceOf[MptNode] && asInstanceOf[MptNode]` pair to a type-binding match (`case other: MptNode`). Semantics identical. consensus/vm/crypto/domain had 0 hits (FORGE gate not triggered).

---

## Open

- `mpt/package.scala:19`, `MerklePatriciaTrie.scala:20` — RLP `given` instances (§3a scope)
- `StackTrie.scala:120` — `return` in hot trie walk (§8e FORGE gate)
