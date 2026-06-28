# ext/ — External Submodules

**Packages:** `bytes/`, `crypto/`, `rlp/`, `scalanet/`
**Gate:** None (no consensus logic)

These are separate sbt submodules. W2-P1 (`333aab3fc`) migrated wildcard imports across all of them.

| File | Package | Key Changes |
|------|---------|-------------|
| [bytes.md](bytes.md) | `bytes/` | Wildcard import migration |
| [crypto.md](crypto.md) | `crypto/` | Wildcard import migration |
| [rlp.md](rlp.md) | `rlp/` | Wildcard import migration, RLP codec `given` instances |
| [scalanet.md](scalanet.md) | `scalanet/` | Wildcard import migration |
