# network/discovery — Peer Discovery

**Package:** `network/discovery/`
**Gate:** `herald` on discovery protocol changes
**Key files:** `PeerDiscoveryManager.scala`, `DnsDiscovery.scala`

---

## Pekko Classic → Typed Migration (Wave 3, Part 6)

#### W3-PLN/NET commits — discovery actors Typed
- **What:** Discovery actor group migrated from Classic → Typed
- **HERALD pre-flight:** HERALD-1 (discovery message path)

---

## W2-P1: Wildcard Import Migration

#### `333aab3fc` — 730-file wildcard `import foo._` → `import foo.*`
- **Cross-refs:** `INDEX.md` (cross-cutting)

---

## Quality Fixes

#### `ff2fc219c` — Part 8f: duplicate StaticNodesLoader deleted
- **What:** DiscoveryConfig redirected to network.StaticNodesLoader (stricter validation: full pubkey + port check vs prefix-only); discovery/StaticNodesLoader.scala and discovery/StaticNodesLoaderSpec.scala removed; stale logback.xml entry cleaned up

---

## Open

- `DnsDiscovery.scala:327` — JNDI `!= null` check (Java interop boundary, keep)
