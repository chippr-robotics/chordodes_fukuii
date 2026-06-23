# network/rlpx — RLPx Connection Handler

**Package:** `network/rlpx/`, `network/handshaker/`
**Gate:** `herald` on ALL changes (wire format, handshake protocol)
**Key files:** `RLPxConnectionHandler.scala`, `Handshaker.scala`

Note: `RLPxConnectionHandler` is an intentional Classic TCP bridge (`extends ClassicActor`). Not migrated.

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Open

- 35 remaining Classic actors in rlpx/ — Wave 3 network migration (implementation not started, plan at `network-sync-pekko-migration-plan.md`)
