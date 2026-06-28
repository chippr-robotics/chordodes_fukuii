# core/utils — Utilities

**Package:** `utils/`
**Gate:** None (no consensus logic)
**Note:** ~60 reverse dependencies — changes here have high blast radius

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Scope:** All utils/ files
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## W2-P3a: Implicit → Given/Using (Open)

- `FunctorOps.scala:15` — candidate for `given Conversion` or extension method
- Full scope: DEFERRED-BACKLOG §3a

---

## Scala 3 Idioms

#### `b305ef41b` — 3d: NetworkType → enum
- **What:** `sealed trait NetworkType` + 2 case objects → `enum NetworkType` with companion `fromString` factory; `VmConfig.VmMode` sealed trait + 2 case objects → `enum VmMode` inside `object VmConfig`
- **Files:** `utils/BlockchainConfig.scala`, `utils/Config.scala`
- **Call sites unchanged:** `NetworkType.ETC`, `NetworkType.ETH`, `VmMode.Internal`, `VmMode.External` — same qualified paths

---

## Quality Fixes

#### `4907406fe` — M3: FileUtils resource leak (8c batch)
- **What:** `FileUtils` — unclosed streams on abnormal paths
- **Cross-refs:** `storage/db.md` (same commit covers RocksDB batch)
