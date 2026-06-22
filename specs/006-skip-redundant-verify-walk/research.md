# Phase 0 Research: Skip the Redundant Second Verification Walk

Grounded in a 4-agent adversarially-verified `forge` workflow over the deployed `staging` HEAD (v0.7.16, commit `8f80d9505`). Consensus-adjacent — this changes *when* `StateHealingComplete` is declared. File:line citations are `staging`-HEAD anchors; locate by symbol.

## Current-state findings

- **F-A — The two-walk mechanism.** On a restart with a persisted frontier but no completeness marker, the node takes the `loaded.nonEmpty → None` branch (`TrieNodeHealingCoordinator.scala:612-614`) whose walk callback is **`FrontierRebuildComplete`** (a full `rebuildFrontierBFS` = WALK #1). The handler (`:638-645`) ONLY clears `verificationBFSRunning` and calls `markComplete()` — it does NOT send `HealingCheckCompletion`, does NOT send `StateHealingComplete`, and does NOT set `verificationPassComplete`. So a clean walk leaves the node all-idle with `verificationPassComplete=false`. The **dead-pulse watchdog** (`:1012-1027`) then force-starts `startVerificationBFS` = WALK #2 (the same traversal) after ~3 dead pulses (~4-6 min). Walk #2 → `VerificationBFSComplete` (`:949-977`) sets `verificationPassComplete=true` → `self ! HealingCheckCompletion` → the gate (`:896`, `verificationPassComplete || totalNodesHealed==0`) → `markComplete` + `StateHealingComplete` (`:912`). Two full ~16-20h walks on ETC mainnet ⇒ ~30-40h.

- **F-B — Chesterton's Fence (why the verification walk exists).** The post-heal verification walk was introduced in commit **`961f0c9b5`** (PR #1321, "StateHeal fixes + review", BUG 1) to patch a gap in **`discoverMissingChildren`** — the SHALLOW inline per-healed-node child discovery (`:1917`) that skips already-present storage roots WITHOUT recursing into their children (account `888157b2`: StateHeal declared "complete" in 59s with 91,367 healed but the storage sub-trie was still partial). **Key insight**: `rebuildFrontierBFS` (the REBUILD, walk #1) ALREADY fully recurses into account-leaf `storageRoot`s (`:1659-1684`) — it IS the full traversal BUG-1 demanded. So the fence is real but does NOT apply when the deciding walk was a clean full rebuild: walk #2 is then the same full traversal re-run for nothing.

- **F-C — Bounded-visited eviction cannot skip a missing node.** Missing-node detection is at `:1616` (`case None` on dequeue), **unconditional** and not consulting the visited set; `markIfNew` gates only child ENQUEUE (`:1629/1647/1666`); the visited FIFO evicts eldest-inserted (`:2101-2107`) and **never refuses insert**, so eviction only re-enables enqueues = MORE work, never fewer (the `:1543-1548` comment confirms the prior refuse-on-full truncation bug was fixed by switching to eviction). Both walks share the identical (non-blind-spot) detection, so walk #2 adds ZERO detection capability over walk #1.

- **F-D — Marker-trust is already staked on walk #1.** `markComplete()` already fires on the clean `case None` rebuild path (`:643`) BEFORE any verification. So the persisted completeness marker (and thus future-restart resume behavior) already depends on walk #1's correctness today; declaring completion from walk #1 changes nothing about marker correctness.

- **F-E — The stale-root hazard.** `HealingPivotRefreshed` to a different root (`:742`) does NOT cancel the in-flight walk `Future` (it closed over the old root at `:614`). So a stale `FrontierRebuildComplete` can land against a *new* `stateRoot`. Any early-completion guard MUST exclude this (else false completion against the new root).

## Decisions

### D1 — Route the early completion through the single existing chokepoint (byte-parity)

- **Decision**: On a clean rebuild, set `verificationPassComplete = true` and `self ! HealingCheckCompletion` (NOT a direct `StateHealingComplete`). The marker write (`:908`) and the `StateHealingComplete` send (`:912`) then flow through the *same* path the two-walk sequence uses.
- **Rationale**: Guarantees FR-002 byte-parity by construction — identical decision, identical marker bytes (`Array[Byte](1)`), identical state root (the walk does no state writes). No new completion site, no new marker set-point.

### D2 — The clean-rebuild guard predicate

- **Decision**: Declare early completion iff `missingEmitted == 0 && totalNodesHealed == 0 && isComplete && !flushing && walkRoot == stateRoot`, evaluated in the `FrontierRebuildComplete` handler. `isComplete = pendingTasks.isEmpty && activeRequests.isEmpty` (`:1495`). `!flushing` mirrors the existing `HealingCheckCompletion` gate's flush guard (`:890`).
- **Rationale**: Each conjunct excludes a specific false-completion path (D-decisions and the adversarial section below). All five are cheap local reads.

### D3 — Make the rebuild outcome observable (message-shape change)

- **Decision**: `missingEmitted` is the rebuild's `frontierCount` (`:1563`), local to the walk kernel and NOT observable at the payload-free `FrontierRebuildComplete` case object today. Change `FrontierRebuildComplete` from a **case object to a case class** carrying `missingEmitted: Long` and `walkRoot: ByteString`, threaded out via the walk's `onComplete` callback (`:614`).
- **Rationale**: FR-004 — the early-completion condition must be evaluated from the walk's *actual* outcome, not inferred. The callback already exists; this only enriches its payload.

### D4 — Explicit `walkRoot == stateRoot` guard (LOCKED)

- **Decision**: Carry `walkRoot` explicitly in the message and gate on `walkRoot == stateRoot`. Do NOT rely on the incidental "a pivot refresh re-seeds `pendingTasks` so `isComplete` is false" property.
- **Rationale**: FR-005. The incidental property is fragile — a future refactor of the pivot path (`:742`) could silently reintroduce the stale-root false-completion (F-E). The explicit guard is robust to that. User-locked at specify time.

### D5 — Conservative `totalNodesHealed == 0`; unconditional; watchdog as fallback (LOCKED)

- **Decision**: Keep the `totalNodesHealed == 0` conjunct for the first landing (matching the existing `:896` clean-idle arm) rather than widening to a `missingEmitted == 0`-only proof. Ship **unconditionally** (no new config flag). Leave the dead-pulse watchdog (`:1012-1027`) intact and unchanged.
- **Rationale**: `totalNodesHealed` is a lifetime cumulative counter (`:157/:1308`, preserved across pivots per `:766`) — using `== 0` gives only conservative false-negatives, never false-positives; widening (still byte-parity-safe) waits for one clean live validation. Unconditional because the change is byte-parity with the two-walk outcome — a flag would only let an operator re-enable a redundant ~16-20h walk. The watchdog is the built-in free kill-switch: if early completion does not fire (any guard conjunct false), it force-starts walk #2 exactly as today, self-suppressing via `!verificationPassComplete` (no double-start). User-locked at specify time.

## Adversarial consensus-safety verdict (forge)

**No false-completion path exists that the D2 guard does not exclude.** Five attacks, all refuted:
1. **Bounded-visited eviction** — cannot skip a missing node (F-C); walk #2 adds zero eviction safety.
2. **Healing-during-walk-1 mutation** — excluded by `totalNodesHealed == 0 && isComplete && !flushing`.
3. **Stale-root completion** — excluded by `walkRoot == stateRoot` (D4).
4. **Storage subtree under a present account leaf** (the BUG-1 gap) — `rebuildFrontierBFS` fully recurses (`:1659-1684`), so walk #1 IS the full traversal; the fence's protection is preserved for the non-rebuild paths (FR-007).
5. **Marker trust** — already staked on walk #1 today (F-D); the fix changes nothing.

Byte-parity (FR-002) holds by D1 (single chokepoint, no state writes). No EVM/gas/RLP/Ethash/ECIP-1017/block-validation code is touched. Recommendation: **implement** with the D3 message-shape change and the D4 explicit guard.

## No open clarifications

D4 (explicit walkRoot) and D5 (conservative `healed==0`, unconditional) were locked by the user at specify time. Nothing deferred for sign-off.
