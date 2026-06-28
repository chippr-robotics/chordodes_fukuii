# network/snap-server — SNAP Serve Side

**Package:** `network/snapserver/` (also embedded in NPMA handlers)
**Gate:** `herald` on SNAP wire protocol; None on serve-window logic
**Key files:** SNAP server handlers in `NetworkPeerManagerActor.scala` + `network/snapserver/`

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-SNAP2 commits — SNAP server handlers Typed
- **Cross-refs:** `sync/snap.md` (client side), `network/peers.md` (NPMA host)

---

## Open / Deferred

- INFO-8: `refreshFreshRootCache` 128 `getBlockHeaderByNumber` calls in actor loop — monitor SNAP serve latency
- W2: `val _ = peerWithInfo` in 4 SNAP handlers — by-design after HERALD verify (None reachable pre-handshake)
