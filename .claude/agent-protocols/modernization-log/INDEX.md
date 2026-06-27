# Fukuii Modernization Log — Master Index

**Branch:** `scala3-cleanup-june`
**Scope:** Code evolution from Pekko Classic + Scala 2 idioms → Pekko Typed + Scala 3 LTS
**Status:** ✅ All tracked subsystems Typed — june-sprint complete 2026-06-27
**Next:** Wave 3 Network/P2P (35 Classic actors in devp2p/rlpx — see `SPRINT-QUEUE.md`)

---

## Subsystem Status

| Category | Subsystem | Migration Status | Gate |
|----------|-----------|-----------------|------|
| ext | [bytes](ext/bytes.md) | ✅ Typed | — |
| ext | [crypto](ext/crypto.md) | ✅ Typed | — |
| ext | [rlp](ext/rlp.md) | ✅ Typed | — |
| ext | [scalanet](ext/scalanet.md) | ✅ Typed | — |
| core | [domain](core/domain.md) | ✅ Typed | forge/beacon |
| core | [mpt](core/mpt.md) | ✅ Typed | forge |
| core | [utils](core/utils.md) | ✅ Typed | — |
| consensus | [pow](consensus/pow.md) | ✅ Typed | forge |
| consensus | [engine](consensus/engine.md) | ✅ Typed | beacon |
| consensus | [validators](consensus/validators.md) | ✅ Typed | forge/beacon |
| consensus | [vm](consensus/vm.md) | ✅ Typed | forge/beacon |
| storage | [db](storage/db.md) | ✅ Typed | vault |
| storage | [ledger](storage/ledger.md) | ✅ Typed | forge |
| sync | [fast](sync/fast.md) | ✅ Typed | — |
| sync | [regular](sync/regular.md) | ✅ Typed | — |
| sync | [snap](sync/snap.md) | ✅ Typed | — |
| sync | [controller](sync/controller.md) | ✅ Typed | — |
| network | [peers](network/peers.md) | ✅ Typed | herald |
| network | [rlpx](network/rlpx.md) | ✅ Typed | herald |
| network | [discovery](network/discovery.md) | ✅ Typed | herald |
| network | [snap-server](network/snap-server.md) | ✅ Typed | herald |
| api | [jsonrpc](api/jsonrpc.md) | ✅ Typed | conduit |
| api | [transactions](api/transactions.md) | ✅ Typed | — |
| node | [bootstrap](node/bootstrap.md) | ✅ Typed | — |
| node | [testing-infra](node/testing-infra.md) | ✅ Modernized | — |

---

## Cross-Cutting Commits (3+ subsystems)

| Commit | What | Subsystems |
|--------|------|------------|
| `333aab3fc` | W2-P1: 730-file wildcard `import`→`import *` migration | all |
| `948a25008` | Phase 1: sealed Command traits across S1/S4/S5/S6 actors | sync/fast, sync/regular, network/peers, sync/controller |
| `04615ad43` | Phase 2: Command ADT non-sealed consolidated | sync/fast, sync/regular, network/peers |
| `1f2d3a4b5` | CAPSTONE: root actor flip Classic→Typed | node/bootstrap + all |
| `84aa43575` | style(8g): `removeOptionalBraces = true` — full 957-file braceless sweep | all |

---

## Category Indexes

- [ext/](ext/INDEX.md) — external submodules (bytes, crypto, rlp, scalanet)
- [core/](core/INDEX.md) — foundational types (domain, mpt, utils)
- [consensus/](consensus/INDEX.md) — PoW/PoS engine, validators, VM
- [storage/](storage/INDEX.md) — RocksDB, ledger
- [sync/](sync/INDEX.md) — sync protocols (fast, regular, snap, controller)
- [network/](network/INDEX.md) — P2P (peers, rlpx, discovery, snap-server)
- [api/](api/INDEX.md) — JSON-RPC, transactions
- [node/](node/INDEX.md) — bootstrap, testing infra
