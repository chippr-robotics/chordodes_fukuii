# Internal Interface Contracts: Scoped Post-Heal Verification

This feature exposes **no external/public API** — it is post-SNAP sync infrastructure. Its
"contracts" are internal interfaces inside `TrieNodeHealingCoordinator` (`TNHC`),
`SNAPSyncController`, and the snap config that other code and tests depend on. Each is a behavioral
contract with invariants the implementation MUST satisfy and tests MUST assert. Citations are against
`src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`
unless noted.

The **completeness marker API** (`HealingFrontierStorage.markComplete()` / `isComplete` /
`clearComplete()`, `HealingFrontierStorage.scala:55,61,58`) is **unchanged**; this feature only adds
a new authorized READER of `isComplete` (the scoped-gate predicate) and a new caller-side discipline.

---

## C1 — Healed-path capture (FR-001, consensus-critical scope correctness)

A new bounded in-memory accumulator populated at the single heal site.

```scala
// New TNHC fields (mirroring pendingTasks/pendingHashSet, TNHC:69,278)
private val healedPathsThisRound: mutable.LinkedHashMap[ByteString, HealingEntry] =
  mutable.LinkedHashMap.empty
private var healedPathsRoot: ByteString = ByteString.empty   // root these paths were healed against
private var healedPathsOverflowed: Boolean = false
```

**Capture point**: inside `handleResponse`, in the hash-matched branch, immediately after
`totalNodesHealed += 1` (`TNHC:1081`) and beside the existing
`discoverMissingChildren(nodeData, task.pathset)` call (`TNHC:1088`):

```scala
taskByHash.get(nodeHash).foreach { task =>
  if (healedPathsThisRound.isEmpty) healedPathsRoot = stateRoot     // tag the round's root (F5)
  if (!healedPathsOverflowed && !healedPathsThisRound.contains(task.hash)) {
    if (healedPathsThisRound.size >= scopedHealMaxPaths) healedPathsOverflowed = true   // F4 / FR-011
    else healedPathsThisRound.update(task.hash, task)
  }
}
```

**Contract**:
- After a round, `healedPathsThisRound` contains **exactly** the `HealingEntry` of every node for
  which `totalNodesHealed` was incremented this round (no skip, dedup by hash) — UNLESS
  `healedPathsOverflowed`, in which case it is bounded at `scopedHealMaxPaths` and the round MUST fall
  back to full-root (C4 F4).
- Each captured `pathset` is byte-identical to the `task.pathset` the coordinator already holds
  (`TNHC:1056`) and is a valid BFS seed (C2).
- The accumulator is actor field state, mutated only on the actor thread; it is **not persisted**.

**Cleared** (set to empty, `healedPathsOverflowed = false`) at: differing-root
`HealingPivotRefreshed` (`TNHC:599-661`), `HealingForceComplete` (`TNHC:576-588`), and after a
`StateHealingComplete` is declared via the verified arm (`TNHC:731`). A same-root
`HealingPivotRefreshed` (`TNHC:594-598`) does NOT clear it.

---

## C2 — `rebuildFrontierBFS` multi-seed generalization (FR-002/FR-003, consensus-critical parity)

Generalize the BFS to accept a SET of seeds instead of one. The traversal kernel is otherwise
byte-identical.

```scala
// New overload — the multi-seed core.
private def rebuildFrontierBFS(
    seeds: Seq[(ByteString, Seq[ByteString], Boolean)],   // (startHash, startPathset, isStorage)
    selfRef: ActorRef,
    queue: BfsQueueStorage,
    effectiveParallelism: Int
): Unit

// Existing single-seed signature (TNHC:1257) becomes a thin wrapper, byte-identical behavior:
private def rebuildFrontierBFS(
    startHash: ByteString, startPathset: Seq[ByteString], isStor: Boolean,
    selfRef: ActorRef, queue: BfsQueueStorage, effectiveParallelism: Int
): Unit =
  rebuildFrontierBFS(Seq((startHash, startPathset, isStor)), selfRef, queue, effectiveParallelism)
```

**Implementation delta (the ONLY change to the kernel)**:
- `markIfNew(startHash)` (`TNHC:1287`) → `seeds.foreach { case (h, _, _) => markIfNew(h) }`.
- `queue.enqueueBatch(Seq((startHash.toArray, startPathset.map(_.toArray), isStor)))` (`TNHC:1434`)
  → `queue.enqueueBatch(seeds.map { case (h, ps, s) => (h.toArray, ps.map(_.toArray), s) })`.
- `levelEnd = queue.counter` after seeding (`TNHC:1436`) now reflects `seeds.size` as level 0.

**Contract (FR-007 parity)**:
- For a single-element `seeds`, the multi-seed method is **byte-identical** to the prior single-seed
  method (same visited set, same level expansion `TNHC:1439-1516`, same child-path arithmetic
  `TNHC:1356-1409`, same `FrontierRebuilt` emission `TNHC:1472`, same backpressure `TNHC:1471`).
- For a multi-element `seeds`, the walk visits the union of the subtrees reachable from each seed,
  recursing each to completion (FR-003), and emits as frontier exactly the missing nodes it
  encounters (`TNHC:1342-1345`) — identical classification logic to the full-root walk over those
  same subtrees.
- The walk performs **no state writes** (reads via `mptStorage.multiGetNodes`, `TNHC:1325`); it is a
  pure local read that emits `FrontierRebuilt` and (via the caller) `VerificationBFSComplete`.
- Each seed's `pathset` MUST be the HP-encoded path from the relevant root (state root for
  account-trie seeds, account storage root for storage-trie seeds), so per-child nibble extension
  yields correct descendant paths. A storage seed MUST be `(storageRootHash,
  Seq(accountHash32, compactStoragePath), isStorage = true)`.

---

## C3 — `startScopedVerification` (FR-002/FR-006)

New launcher, sibling of `startVerificationBFS` (`TNHC:1583-1587`).

```scala
/** Launch a SCOPED verification BFS seeded from the healed-paths set. Each healed node's subtree is
  * re-walked to completion; missing descendants are emitted via FrontierRebuilt. Sends
  * VerificationBFSComplete when done — the SAME completion path as the full-root verification.
  */
private def startScopedVerification(seeds: Seq[HealingEntry]): Unit
```

**Contract**:
- Maps each `HealingEntry` to `(e.hash, e.pathset, e.pathset.size > 1)` and launches the multi-seed
  walk (C2) on `healingWriterEc` via the existing `startFrontierBFS` plumbing, reusing
  `verificationBFSRunning` (`TNHC:1557`), the shared `bfsQueue`, and `computeEffectiveParallelism`
  (`TNHC:1546-1552`).
- onComplete sends `VerificationBFSComplete` (`TNHC:745`) — **the same handler** the full-root
  verification uses. The scoped path adds NO new completion message and NO new marker set-point.
- On any walk exception it routes to `FrontierWalkFailed` (`TNHC:1568`) exactly like the existing
  walks (resets flags, sets no marker).
- MUST emit an observability signal on entry (C6 / FR-010): scoped engaged, seed count, start time.

---

## C4 — Completion-gate decision (FR-004/FR-005/FR-009/FR-011, consensus-critical)

The branch inside `HealingCheckCompletion` that today unconditionally calls
`startVerificationBFS(stateRoot, emptyPath)` when work was done and verification not yet passed
(`TNHC:732-742`) gains a scope decision.

**Predicate** (pure, actor-thread, all cheap local reads):

```scala
val useScoped =
     snapSyncConfig.scopedHealVerification        &&   // F1
     healingFrontierStorage.exists(_.isComplete)  &&   // F2/F6 (E3 precondition)
     healedPathsThisRound.nonEmpty                &&   // F3 (restart-lost / pre-first-heal)
     !healedPathsOverflowed                       &&   // F4 (FR-011 bound)
     healedPathsRoot == stateRoot                      // F5 (FR-009 root tag)

if (useScoped) startScopedVerification(healedPathsThisRound.values.toSeq)
else           startVerificationBFS(stateRoot, emptyPath)   // UNCHANGED full-root fallback
```

**Contract**:
- **Fallback is total and unchanged**: when `useScoped == false` for ANY reason, the call is the
  existing full-root verification with no semantic change. The conservative path is the
  already-validated production path (FR-005 / SC-003).
- The predicate MUST be evaluated at gate time (live reads), not cached, so a marker cleared or root
  changed between rounds is honored.
- The completion DECLARATION (`TNHC:715-731`) is **untouched**: both modes set
  `verificationPassComplete` via `VerificationBFSComplete` (`TNHC:745-754`) and declare completion
  through the single `verificationPassComplete`-gated chokepoint, setting the same marker
  (`TNHC:726`) and sending the same `StateHealingComplete` (`TNHC:731`). (FR-007.)

**Invariant (consensus, mirrors spec 002 VR-1)**: `store.markComplete()` is reachable for the scoped
path ONLY through the existing `verificationPassComplete` arm — i.e. only after a scoped walk found 0
missing AND `isComplete` AND the precondition held. There is NO new set-point.

---

## C5 — Configuration contract (FR-008)

Two new keys, mirroring `heal-hold-pivot-on-stagnation` exactly.

| Surface | Name | Type | Default |
|---------|------|------|---------|
| `sync.conf` (`snap-sync` block, after line 155) | `scoped-heal-verification` | boolean | `true` |
| `sync.conf` | `scoped-heal-max-paths` | int | `200000` |
| `SNAPSyncConfig` (`SNAPSyncController.scala:4781` region) | `scopedHealVerification: Boolean` | — | `true` |
| `SNAPSyncConfig` | `scopedHealMaxPaths: Int` | — | `200000` |

**Contract**:
- Parsed with the existing `hasPath`-guarded idiom (`SNAPSyncController.scala:4907-4910`):
  `if (snapConfig.hasPath("scoped-heal-verification")) snapConfig.getBoolean(...) else true`; likewise
  `getInt` else `200000`.
- Threaded into `TrieNodeHealingCoordinator.props`/constructor alongside the existing healing knobs
  (`TNHC:1794-1839`, e.g. beside `healingTraversalParallelism`).
- `scoped-heal-verification = false` ⇒ the gate (C4) always takes the full-root branch — today's
  behavior exactly (FR-008, no migration). The coordinator MUST log "scoped verification disabled by
  config — using full-root verification" once per round when it engages the fallback for this reason
  (US3 AS2).
- Both keys are safe to flip at restart with no data migration (they gate only in-memory decisions).

---

## C6 — Observability signal (FR-010, US3)

When the scoped path engages, the coordinator MUST emit at minimum: that scoping engaged, the number
of healed subtrees (seed count), and the elapsed verification time. Reuse the existing
`[HEAL-VERIFY]` / `[HEAL-BFS]` log channel and the `SNAPSyncMetrics` pattern
(`TNHC:1493-1499`, spec 002 C4).

```scala
// On startScopedVerification entry:
log.info(s"[HEAL-VERIFY-SCOPED] Scoped verification engaged — ${seeds.size} healed subtrees " +
         s"(root ${Hex.toHexString(stateRoot.take(4).toArray)}); skipping full-root re-walk")
SNAPSyncMetrics.setHealingScopedVerification(1)          // gauge: 1 = scoped, 0 = full-root
SNAPSyncMetrics.setHealingScopedSubtrees(seeds.size.toLong)

// On VerificationBFSComplete for a scoped run:
log.info(s"[HEAL-VERIFY-SCOPED] Scoped verification complete in ${elapsedMs}ms " +
         s"over ${seeds.size} subtrees — declaring completion")
SNAPSyncMetrics.setHealingScopedDurationMs(elapsedMs)
```

**Contract**: the gauges are `app_`-prefixed (via `Metrics.mkName`), present and moving in the
correct direction; the full-root fallback sets `setHealingScopedVerification(0)` so a dashboard can
distinguish the two paths and confirm engagement/savings (SC-005). No gauge throws at scrape time.

> New `SNAPSyncMetrics` series (additive, observation-only): `app_snapsync.healing.scoped_verification`
> (gauge 0/1), `…scoped_subtrees` (gauge), `…scoped_duration_ms` (gauge). These never gate any
> consensus decision — they are pure instrumentation.

---

## Summary of changed surfaces

| Surface | Change | Consensus class |
|---------|--------|-----------------|
| `TNHC.handleResponse` (`:1063-1088`) | capture healed-path at heal site (C1) | scope correctness (critical) |
| `TNHC.rebuildFrontierBFS` (`:1257,1287,1434`) | multi-seed overload; single-seed becomes wrapper (C2) | parity (critical) |
| `TNHC.startScopedVerification` (new) | scoped launcher (C3) | parity (critical) |
| `TNHC.HealingCheckCompletion` (`:732-742`) | scope decision predicate + fallback (C4) | gate (critical) |
| `TNHC` clear sites (`:576,599,731`) | clear healed-paths set | safety |
| `SNAPSyncConfig` + `sync.conf` | two new keys (C5) | config only |
| `SNAPSyncMetrics` + log | scoped observability (C6) | observation only |

No change to `SNAPSyncController`'s `StateHealingComplete` handler (`:1143-1153`), the
`HealingFrontierStorage` API, RLP/state-root/EVM/gas/reward code, or any persisted schema.
