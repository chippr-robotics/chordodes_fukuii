# sync/ — Blockchain Sync Protocols

**Packages:** `blockchain/sync/fast/`, `blockchain/sync/regular/`, `blockchain/sync/snap/`, `blockchain/sync/checkpoint/`
**Gate:** None (sync infrastructure, not consensus)

The largest and most complex migration surface. All actors fully Typed as of CAPSTONE.

**2026-06-22 G1 assessment:** `Behavior[Any]` narrowing sprint DONE — all 12 sync actors confirmed `Behavior[Command]` in actual code (pre-existing). Stale Scaladoc comments cleaned. D1 gate lifted. Remaining work: `ctx.toClassic.sender()` OQ-5 bridges (SyncController 13 sites, RegularSync 1, FastSync 3) are by-design until CAPSTONE.

| File | Package | Key Changes |
|------|---------|-------------|
| [fast.md](fast.md) | `blockchain/sync/fast/` | Full Pekko Typed migration (W3-S4/S5/S6); ADT narrowing Ph1/Ph2/Ph3; SyncSession lifecycle fix |
| [regular.md](regular.md) | `blockchain/sync/regular/` | BlockImporter Typed (W3-S2/S3); LCA recovery; RegularSyncCommand sealing |
| [snap.md](snap.md) | `blockchain/sync/snap/` | SNAPSyncController Typed (W3-SNAP1/SNAP2); SSC idle-state design review (7e-P4/P4a) |
| [controller.md](controller.md) | `SyncController`, `SyncProtocol` | CAPSTONE ROOT flip; EC.global removal; HandshakedPeers fallthrough fix |
