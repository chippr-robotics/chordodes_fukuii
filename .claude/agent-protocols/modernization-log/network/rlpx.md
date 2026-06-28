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

---

## §8a-E6 — computeCapabilityOffsets extraction (2026-06-27)

#### `ec00775b6` — §8a-E6: RLPxCapabilityOffsetsSpec extracted from RLPxConnectionHandlerSpec
- **What:** 6 pure-function `computeCapabilityOffsets` tests moved to new `RLPxCapabilityOffsetsSpec`
  (plain `AnyFlatSpec`, no actor system, no `pekko.testkit` imports). 9 TCP interaction tests remain
  in `RLPxConnectionHandlerSpec` (Classic `TestActorRef` + `lastSender` — Wave 3 gate unchanged).
- **VERIFY:** RLPxCapabilityOffsetsSpec 7/7 · RLPxConnectionHandlerSpec 9/9
- **Cross-refs:** `completed/DEFERRED-BACKLOG.md §8a-E6`

---

## Open

- 35 remaining Classic actors in rlpx/ — Wave 3 network migration (implementation not started, plan at `network-sync-pekko-migration-plan.md`)
- RLPxConnectionHandlerSpec TCP tests (9) — Wave 3 gate: require `TestActorRef` + `lastSender` + Pekko IO TCP (Classic-only)
