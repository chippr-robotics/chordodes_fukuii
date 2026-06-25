# network/ — P2P Layer

**Packages:** `network/` (NPMA, PeerActor, PeerEventBus, ServerActor), `network/rlpx/`, `network/discovery/`, `network/snapserver/`
**Gate:** `herald` on all wire-protocol and peer-management changes

Classic TCP bridge actors (ServerActor, RLPxConnectionHandler) intentionally remain Classic.

**2026-06-22 G1 assessment:** All `Behavior[Any]` narrowing confirmed complete — 0 actual type-annotation occurrences in production code. Stale Scaladoc cleaned in NPMA (via peers.md), SNAPSyncController, SyncController, FastSync, PivotHeaderBootstrap, BytecodeRecoveryActor, StorageRecoveryActor, NodeBuilder. `ctx.toClassic.sender()` OQ-5 bridges remain by-design until CAPSTONE.

| File | Package | Key Changes |
|------|---------|-------------|
| [peers.md](peers.md) | `network/` | NPMA Typed (W3-NET/NET2); ADT narrowing Ph1/Ph2/Ph3; EventStream→Topic; Command sealing |
| [rlpx.md](rlpx.md) | `network/rlpx/` + `handshaker/` | W3-W1/W2 Classic TCP bridges retained; RLPx connection handler |
| [discovery.md](discovery.md) | `network/discovery/` | W3-PLN/NET DNS/peer discovery Typed migration |
| [snap-server.md](snap-server.md) | `network/snapserver/` | SNAP server (NPMA-embedded); INFO-8/INFO-9 deferred |
| [messages.md](messages.md) | `network/p2p/messages/` | ETH68/69/70 + SNAP/1 codec property-based round-trip tests (8h, 2026-06-25) |
