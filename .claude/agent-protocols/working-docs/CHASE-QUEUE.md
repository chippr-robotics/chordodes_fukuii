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
| `network/NetworkPeerManagerActor.scala` | 451, 663 | Typed actor may use `classicSystem.scheduler` for fire-and-forget blacklist delay (not `context.system.scheduler` from Typed). Low risk (message goes to dead letters if NPMA stops), but verify scheduler source. If confirmed Classic, log alongside PMA deferred item in DEFERRED-BACKLOG Part 2 NET group. HERALD gate. | CLASSIC | PRISM | 2026-06-21 |
| `blockchain/ledger/BlockExecution.scala` | `applyEip2935` | `applyEip2935` writes to `HistoryStorageAddress` storage without first guaranteeing the account exists (unlike `applyEip4788` which creates the account). Currently masked on ETC by deployment order. Latent correctness gap — if `HistoryStorageAddress` account is absent the storage write may silently no-op or behave incorrectly. FORGE gate required before touching. | EXCEPT | BEACON | 2026-06-21 |

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

| IMPLICIT ×3 / P4a EXCEPT | various | Cleared 2026-06-21: (1) IMPLICIT ×3 — `*Enc extends MessageSerializableImplicit`/`RLPSerializable` subtype polymorphism + `ReceiptBloom*` wildcard collision — permanent deferrals (P3b); no fix planned. (2) P4a SSC `GetProgress` idle-state gap — fixed `74db726d1`. | — | — | 2026-06-21 |

---

## wall-clock-assertion (protocol candidate)

Pattern: unit tests asserting real elapsed time (`elapsed should be < N.millis`).
Recurs in 3 files (`MerkleProofVerifierPhase3Spec`, `SnapServerLimitsSpec`, `WorkNotifierSpec`).
Fix: replace with op-count guard or `@SlowTest` tag. See `test-quality-audit.md §R1-b`.

---

## json4s Jackson 3 gate (R3 — 2026-06-20)

json4s 4.2.0-M5-SNAPSHOT has Jackson 3 (tools.jackson.core 3.2.0, new package namespace).
Stable release still 4.1.1 (Jackson 2). Gate opens when 4.2.0-M5 is tagged.
Check https://github.com/json4s/json4s/tags weekly.
When tagged: bump fukuii pin from 4.0.7 to 4.2.0-M5, unblocks DEFERRED-BACKLOG Part 4b (logstash chain).

---

## Test infrastructure traps

IntegrationTest tag conflict (discovered G2, 2026-06-21): Do not tag IntegrationTest-scoped tests with the IntegrationTest tag — commonSettings injects -l IntegrationTest as a global exclusion, causing zero tests to run. Tests in src/it/ are already in the IntegrationTest config by directory. Tag with the feature tag only (e.g. EthSmoke). Applies to any future sbt task scoped to IntegrationTest / testOptions.

---

## BlockExecution.applyEip2935 — account-existence gap (G5, 2026-06-21)

`BlockExecution.applyEip2935` writes to `HistoryStorageAddress` storage without first guaranteeing the account exists, unlike `applyEip4788` which creates the account if absent. Currently masked on real ETC by deployment order (account pre-exists at activation block). Latent: if activation ordering ever shifts or the account is absent in test conditions, the storage write silently fails or corrupts state. Route to FORGE for ETC consensus review before Olympia activation.
