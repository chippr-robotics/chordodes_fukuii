# core/ — Foundational Types

**Packages:** `domain/`, `mpt/`, `utils/`
**Gate:** `forge` (ETC) / `beacon` (ETH) on all consensus-touching changes

High blast-radius packages — changes here affect the entire codebase.

| File | Package | Key Changes |
|------|---------|-------------|
| [domain.md](domain.md) | `domain/` | Block, Tx, WorldState, Account — W2-P1 wildcard migration |
| [mpt.md](mpt.md) | `mpt/` | Merkle Patricia Trie — W2-P1 + RLP `given` syntax |
| [utils.md](utils.md) | `utils/` | ~60 reverse deps — W2-P1 wildcard, W2-P3a `given/using` |
