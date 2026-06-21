# Chase Queue

**Public document — safe-for-public content only.**
This file is tracked in the public repo. Do NOT add internal dev commentary,
private decisions, sensitive architecture notes, or anything not suitable for
public viewing. Entries must be code-pattern observations only.

---

Cross-file work identified during inline sessions that was NOT chased at the time.
Agents append here when they spot an issue outside their current file scope.
Entries are batched into dedicated sprint sessions when a cluster forms.

**Review cadence:** At the start of each sprint, scan for clusters (5+ entries of
the same type or package) that warrant a focused session. Delete cleared entries
and add a dated log entry at the bottom.

---

## Entry format

```
| File | Line(s) | Pattern | Type | Agent | Date |
```

**Type codes:** `LOG` `WARN` `RETURN` `SENDER` `CLASSIC` `MUTABLE` `EXCEPT` `IMPLICIT` `ISINST` `DEAD` `NULL`

---

## Open entries

| File | Line(s) | Pattern | Type | Agent | Date |
|------|---------|---------|------|-------|------|
| `consensus/pow/PoWMining.scala` | 103–132 | `mutex.synchronized` init guard: Bucket A — `startMiningProcess` checks `minerCoordinatorRef.isEmpty && mockedMinerRef.isEmpty` then sets one of them; semantically an AtomicBoolean init flag but is a compound check-then-act on two `@volatile` fields. FORGE gate required before converting to `AtomicBoolean`. | MUTABLE | MITHRIL | 2026-06-20 |
| `network/p2p/messages/ETHPackets.scala`, `SNAP.scala`, `ETH69.scala`, `WireProtocol.scala` | various | `*Enc extends MessageSerializableImplicit` — subtype polymorphism: upcast to `MessageSerializable` at call sites; cannot be replaced by extension methods | IMPLICIT | MITHRIL | 2026-06-20 |
| `network/p2p/messages/ETHPackets.scala:426`, `blockchain/sync/codec/MptNodeCodecs.scala:20` | 426, 20 | `SignedTransactionEnc`/`MptNodeEnc extends RLPSerializable` — `toBytes` inherited via trait and called from cross-file callers (`domain/BlockBody`, SNAP codec); cannot be replaced by extension methods | IMPLICIT | MITHRIL | 2026-06-20 |
| `network/p2p/messages/ETHPackets.scala` | 1224, 1249, 1265 | `TxLogEntryRLPEnc`/`ReceiptBloomEnc`/`ReceiptBloomFreeEnc` — name collision with `ReceiptCodecs` extension under wildcard import; ambiguous implicit search | IMPLICIT | MITHRIL | 2026-06-20 |

---

## Sprint clusters

When 5+ entries share a Type or package, open a dedicated sprint:

| Cluster | Threshold | Sprint agent |
|---------|-----------|-------------|
| `LOG` / `WARN` in a package | 5+ | WRAITH (~30-60 min) |
| `RETURN` | 5+ | MITHRIL (~1h) |
| `SENDER` / `CLASSIC` in a subsystem | 5+ | LOOM (~2-4h) |
| `IMPLICIT` | 10+ | MITHRIL Part 3a (full day) |
| `EXCEPT` | 3+ | PRISM review first |

---

## Cleared entries log

_None yet._

---

## wall-clock-assertion (protocol candidate)

Pattern: unit tests asserting real elapsed time (`elapsed should be < N.millis`).
Recurs in 3 files (`MerkleProofVerifierPhase3Spec`, `SnapServerLimitsSpec`, `WorkNotifierSpec`).
Fix: replace with op-count guard or `@SlowTest` tag. See `test-quality-audit.md §R1-b`.
