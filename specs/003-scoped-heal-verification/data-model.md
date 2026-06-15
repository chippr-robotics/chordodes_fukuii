# Phase 1 Data Model: Scoped Post-Heal Verification

**Feature**: `003-scoped-heal-verification` | **Spec**: [spec.md](./spec.md) |
**Research**: [research.md](./research.md)

This feature adds **no new persisted schema**. It introduces one bounded in-memory entity (the
healed-paths set) and a per-round root tag inside `TrieNodeHealingCoordinator`, reuses the existing
durable completeness marker (CF `g`) as the full-coverage precondition, and generalizes the existing
BFS seed from one entry to a set. Citations are against
`src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/actors/TrieNodeHealingCoordinator.scala`
(`TNHC`) unless noted.

---

## Entities

### E1 — Healed-path (the verification seed unit)

The path that locates a single node healed this round, plus the root it was healed against. It is
**not a new type** — it is the existing `HealingEntry` (`TNHC:68`) captured at the heal site.

| Field | Type | Source | Meaning |
|-------|------|--------|---------|
| `hash` | `ByteString` (32 B, keccak-256) | `handleResponse` (`TNHC:1062`) | content address of the healed node; set key + dedup key |
| `pathset` | `Seq[ByteString]` | `task.pathset` (`TNHC:1056,1088`) | HP-encoded path-to-node; `Seq(compact_path)` (account trie) or `Seq(accountHash32, compact_storage_path)` (storage trie) — `Messages.scala:233-237` |
| *(derived)* `isStorage` | `Boolean` | `pathset.size > 1` (`TNHC:1608`) | selects account- vs storage-trie child-path arithmetic in the walk |

**Invariant**: the `(hash, pathset)` pair is byte-identical to the entry the coordinator already
holds and would re-issue via `GetTrieNodes` (`TNHC:1002-1009`), so it is a valid BFS seed with no
re-derivation. The path is the path from the relevant root (state root for account-trie entries;
the account's storage root for storage-trie entries), which is exactly what `rebuildFrontierBFS`
expects as `startPathset` (`TNHC:1434`, `TNHC:1339-1340`).

### E2 — Healed-paths set (the verification scope)

The bounded collection of healed-paths accumulated during one heal round, plus the root it was
recorded against.

| Field | Type | Default/bound | Meaning |
|-------|------|---------------|---------|
| `healedPathsThisRound` | `mutable.LinkedHashMap[ByteString, HealingEntry]` | bounded by `scopedHealMaxPaths` (200000) | hash → entry; insertion-order, dedup by hash (mirrors `pendingHashSet`, `TNHC:278`) |
| `healedPathsRoot` | `ByteString` | `= stateRoot` at first insert of the round | the root these paths were healed against (FR-009 / F5 guard) |
| `healedPathsOverflowed` | `Boolean` | `false` | latched `true` once size would exceed the bound; forces full-root (F4) |

Why `LinkedHashMap` and not a `Set`: it dedups by hash (a node can be re-served/re-queued, cf. the
re-queue logic at `TNHC:1099-1104`) while preserving the `HealingEntry` value and a stable
iteration order for deterministic seeding. It mirrors the existing
`pendingHashSet`/`pendingTasks` pairing.

**Bound enforcement (FR-011)**: on each insert, if `healedPathsThisRound.size >= scopedHealMaxPaths`,
set `healedPathsOverflowed = true` and stop inserting (do NOT grow). Overflow does not lose
correctness — it forces full-root verification (F4), which covers everything.

**Persistence**: **none** — actor field state, lost on restart (like `pendingTasks`,
`pendingHashSet`, `verificationPassComplete`). A restart that lacks this set falls back to full-root
verification (research R4 F3 / Restart-safety).

### E3 — Full-coverage precondition (reused, durable)

The established fact that a prior walk traversed the full trie clean against the current root. **No
new structure** — it is the existing completeness marker in CF `g`.

| Aspect | Detail |
|--------|--------|
| Representation | 21-byte sentinel key `__frontier_complete__` → `0x01` in `Namespaces.HealingFrontierNamespace` (`HealingFrontierStorage.scala:55,64-70`) |
| Read | `healingFrontierStorage.exists(_.isComplete)` (`HealingFrontierStorage.scala:61`) |
| Set | `store.markComplete()` — only at `FrontierRebuildComplete` (`TNHC:514-516`) and the `verificationPassComplete` arm of `HealingCheckCompletion` (`TNHC:726-729`) |
| Clear | `store.clearComplete()` — `HealingForceComplete` (`TNHC:582`) and differing-root `HealingPivotRefreshed` (`TNHC:608`), both via `clearPersistedFrontier` (`TNHC:223-233`) |
| Persistence | durable in CF `g`; survives restart (`StartTrieNodeHealing` reads it, `TNHC:440-458`) |

**Invariant (consensus-adjacent, from spec 002 VR-1)**: `isComplete == true` ⟺ a walk classified
every node reachable from `stateRoot` AND `pendingTasks ∪ activeRequests` was empty at the
set-point, AND the marker has not been invalidated (no abandonment, no differing-root refresh) since.

### E4 — Verification scope (the mode tag)

The mode of the post-heal verification, decided at the completion gate. Not a stored field — a
computed branch.

| Value | When | Seed | Onward |
|-------|------|------|--------|
| `scoped` | all of R4 F1–F6 are FALSE (scoping on, precondition proven, non-empty in-bound same-root set) | `healedPathsThisRound.values` (E2) | `startScopedVerification` (research R3) |
| `full-root` | any of R4 F1–F6 is TRUE (fallback) | `(stateRoot, emptyPath)` | `startVerificationBFS(stateRoot, emptyPath)` (`TNHC:741`) — unchanged |

Both modes converge to the same `VerificationBFSComplete` → `HealingCheckCompletion` gate
(`TNHC:745-754`, `TNHC:715-731`).

---

## State / lifecycle of the healed-paths set (E2)

```
            ┌──────────────────────────────────────────────────────────────────────┐
            │ EMPTY  (round start; restart; after any clear)                         │
            │   gate ⇒ F3 fallback to full-root verification                         │
            └───────────────┬──────────────────────────────────▲─────────────────────┘
   first heal of round      │                                  │  clear on:
   (handleResponse,         │                                  │   • differing-root HealingPivotRefreshed (TNHC:599)
    TNHC:1081):             │                                  │   • HealingForceComplete (TNHC:576)
    record root tag,        ▼                                  │   • after StateHealingComplete declared (round done)
    insert (hash,entry)     │                                  │   • restart (not persisted)
            ┌───────────────┴──────────────────────────────────┴─────────────────────┐
            │ ACCUMULATING  (0 < size ≤ bound, root == healedPathsRoot)               │
            │   each heal: insert if new (dedup by hash)                              │
            │   gate ⇒ scoped IF precondition (E3.isComplete) AND root == stateRoot   │
            └───────────────┬──────────────────────────────────────────────────────────┘
   size would exceed bound  │
   (scopedHealMaxPaths):    ▼
            ┌────────────────────────────────────────────────────────────────────────┐
            │ OVERFLOWED  (healedPathsOverflowed = true; stop inserting)              │
            │   gate ⇒ F4 fallback to full-root verification (FR-011)                 │
            └────────────────────────────────────────────────────────────────────────┘
```

**Transitions**

| From | Event | To | Action |
|------|-------|----|--------|
| EMPTY | first heal of round (`TNHC:1081`) | ACCUMULATING | `healedPathsRoot = stateRoot`; insert |
| ACCUMULATING | further heal, new hash | ACCUMULATING | insert (dedup) |
| ACCUMULATING | further heal, would exceed bound | OVERFLOWED | latch `healedPathsOverflowed = true` |
| ACCUMULATING / OVERFLOWED | differing-root `HealingPivotRefreshed` (`TNHC:599`) | EMPTY | `clear()`; reset overflow flag (stale root) |
| ACCUMULATING / OVERFLOWED | `HealingForceComplete` (`TNHC:576`) | EMPTY | `clear()` (abandonment) |
| ACCUMULATING / OVERFLOWED | `StateHealingComplete` declared (gate, `TNHC:731`) | EMPTY | `clear()` (round closed; next round starts fresh) |
| any | restart | EMPTY | not persisted |

**Round-boundary note**: the set is per-round. A scoped verification that finds NEW missing nodes
(FR-006) keeps the round open — those nodes heal, get inserted into the SAME set (extending the
scope), and the gate re-runs scoped over the extended set until 0 missing (`TNHC:755-761`). Only when
completion is actually declared is the set cleared.

---

## Decision predicate at the completion gate (E4 derivation)

Computed on the actor thread inside `HealingCheckCompletion` (`TNHC:732-742`), replacing the single
`startVerificationBFS(stateRoot, emptyPath)` call when work was done and verification not yet passed:

```
useScoped :=
     snapSyncConfig.scopedHealVerification                 // F1 not disabled
  && healingFrontierStorage.exists(_.isComplete)           // F2/F6 precondition proven (E3)
  && healedPathsThisRound.nonEmpty                          // F3 scope present (not restart-lost)
  && !healedPathsOverflowed                                 // F4 within bound (E2)
  && healedPathsRoot == stateRoot                           // F5 same root (FR-009)

if useScoped then startScopedVerification(healedPathsThisRound.values.toSeq)
else              startVerificationBFS(stateRoot, emptyPath)   // unchanged full-root
```

All five operands are cheap local reads; the predicate is pure and side-effect-free.

---

## What persists across restart

| Item | Persisted? | CF | Restart behavior |
|------|-----------|----|------------------|
| Completeness marker (E3) | **yes** | `g` (`HealingFrontierNamespace`) | read at `StartTrieNodeHealing` (`TNHC:440-458`); may skip full-state rebuild |
| Persisted frontier (still-missing nodes) | yes | `g` | resumed if complete+nonempty; else re-walk |
| BFS level queue | yes (non-durable in practice) | `q` (`BfsQueueNamespace`) | cleared on walk entry (`TNHC:1433`); rebuilt |
| **Healed-paths set (E2)** | **no** | — | EMPTY ⇒ F3 fallback to full-root verification (correct, slower) |
| `healedPathsRoot` / `healedPathsOverflowed` | **no** | — | reset with the set |
| `verificationPassComplete` | no | — | reset; re-established by a verification pass |

The single durable consensus-adjacent fact (E3 marker) is unchanged by this feature; the new
in-memory entities (E2) only ever *narrow* work and always fail safe to full-root when absent.

---

## Configuration entities (new)

| File | Key | Type | Default | Role |
|------|-----|------|---------|------|
| `sync.conf` (`snap-sync` block, beside `heal-hold-pivot-on-stagnation`:155) | `scoped-heal-verification` | boolean | `true` | F1 — enable/disable scoping (mirrors `heal-hold-pivot-on-stagnation`) |
| `sync.conf` | `scoped-heal-max-paths` | int | `200000` | F4 bound on E2 size (FR-011) |
| `SNAPSyncController.SNAPSyncConfig` (`:4781` region) | `scopedHealVerification` | `Boolean` | `true` | parsed via `hasPath` guard (`:4907-4910` idiom) |
| `SNAPSyncController.SNAPSyncConfig` | `scopedHealMaxPaths` | `Int` | `200000` | parsed via `hasPath` guard |

Both are safe to flip without data migration (FR-008): they gate only the in-memory decision
predicate above; disabling restores today's full-root path exactly.

## Validation rules (cross-entity)

- **VR-1 (FR-007, consensus)**: the marker (E3) may be SET only via the existing two clean-walk
  sites; the scoped path adds NO new set-point. Completion routes through the single
  `HealingCheckCompletion` chokepoint for both modes.
- **VR-2 (FR-001/R1)**: `healedPathsThisRound` contains exactly the nodes for which
  `totalNodesHealed` was incremented this round (captured at `TNHC:1081`), with their authoritative
  `task.pathset` — no fewer (no skip), each with a valid BFS-seedable path.
- **VR-3 (FR-009/F5)**: a scoped verification is launched only when `healedPathsRoot == stateRoot`;
  a differing-root refresh clears the set before the next gate.
- **VR-4 (FR-011/F4)**: `healedPathsThisRound.size` never exceeds `scopedHealMaxPaths`; on would-exceed
  the round falls back to full-root, never silently truncates the scope.
- **VR-5 (FR-005/restart)**: an empty `healedPathsThisRound` (restart-lost or pre-first-heal) never
  authorizes scoped completion; the gate falls back to full-root.
