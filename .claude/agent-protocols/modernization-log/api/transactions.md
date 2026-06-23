# api/transactions — Transaction Types and Signing

**Package:** `transactions/`
**Gate:** `forge` for ETC transaction validation/signing; `beacon` for ETH blob transactions
**Key files:** Tx type definitions, RLP encoding, signing

---

## Pekko Classic → Typed Migration (W2-P2c)

- **Scope:** Transaction-related actors migrated from Classic → Typed
- **Cross-refs:** `node/bootstrap.md` (spawn wiring)

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Open

- RLP codec `given` instances in `transactions/` — §3a scope (syntax-only, DEFERRED)
