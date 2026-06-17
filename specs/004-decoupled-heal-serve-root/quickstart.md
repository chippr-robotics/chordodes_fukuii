# Quickstart & Validation: Decoupled Heal Serve-Root

How to validate the feature end-to-end against the spec's success criteria. References [data-model.md](./data-model.md) and [contracts/internal-interfaces.md](./contracts/internal-interfaces.md) rather than duplicating them.

## Prerequisites

- Branch based on `staging` (carries hold-pivot `#1357` and scoped verification `004`'s predecessor, spec 003).
- Deterministic test runner: `sbt testEssential` (Tier-1) / `sbt testStandard` (Tier-1+2). **Do NOT run while the barad-dûr node is active on the same host** (freezes it — see CLAUDE.md); run on CI or an idle host.
- Config (default-on): `sync.snap-sync.decoupled-heal-serve-root = true`, plus the FR-006 threshold `decoupled-heal-max-attempts-no-refresh`.

## What "done" looks like

| Success criterion | How it's proven |
| --- | --- |
| SC-001 / SC-005 — walk completes though it outlives the serve window | Live node: a completeness walk spanning many serve windows reaches `StateHealingComplete` (previously impossible). Metric: serve-root advanced N times during one walk; walk root unchanged throughout. |
| SC-002 — zero false completions | Unit T-4: a task no serve root satisfies never yields completion; no `MissingRootNode` at block import after a decoupled completion. |
| SC-003 — byte-for-byte parity | Unit T-5: decoupled vs coupled completion → identical state root + identical CF `g` marker. |
| SC-004 — content integrity | Unit T-3: a wrong-content node (keccak ≠ requested hash) is dropped, never stored/counted. |
| SC-006 — safe fallback | Unit T-6: feature off ⇒ fetch uses the walk root; behavior byte-identical to today. |

## Validation scenarios (unit / deterministic — Pekko TestKit, no `Thread.sleep`)

Run the C-section test contracts T-1…T-7 (see [contracts](./contracts/internal-interfaces.md)). The correctness-critical ones:

1. **Decoupled fetch (T-1, FR-001/FR-002)**: set `serveRoot ≠ stateRoot`; drive a fetch; assert the `GetTrieNodes.rootHash == serveRoot` while the BFS seed uses `stateRoot`.
2. **Serve-root refresh is side-effect-free (T-2, FR-001/FR-009)**: send `HealingServeRootRefresh(r)`; assert `serveRoot==r` and `stateRoot`, frontier, `verificationPassComplete`, pendingTasks all unchanged.
3. **Content guardrail (T-3, FR-004 — the load-bearing safety test)**: feed a `TrieNodes` response whose node hashes to something other than the requested task hash; assert it is dropped, not stored, not counted, task stays pending.
4. **Unservable node (T-4, FR-006/SC-002)**: a task no serve root can satisfy → never completes; attempt counter increments, resets on `HealingServeRootRefresh`, surfaces past threshold; assert no `StateHealingComplete` and no `HealingForceComplete`.
5. **Parity (T-5, FR-007/SC-003)**: for one fixed final healed state, reach completion decoupled vs coupled (flag flip) → identical state root + identical CF `g` marker bytes.
6. **Fallback (T-6, SC-006)**: feature off ⇒ `GetTrieNodes.rootHash == stateRoot`; byte-identical to today.

## Live validation (on the barad-dûr ETC node, read-only)

After build + deploy, on a node whose walk exceeds the serve window:
- Log/metric shows the **walk root held constant** for the whole walk while the **serve root advances** (e.g. `serveRoot` updated every ~serve-window as the chain moves), via the engagement signal (FR-010).
- Heal-serve `GetTrieNodes` timeouts drop versus the pre-change baseline (the held root no longer ages out of the fetch); `healed` climbs past the prior ~99% plateau to completion.
- The walk reaches 0 frontier against the fixed walk root → a `[HEAL-VERIFY-SCOPED]`/`StateHealingComplete` line → regular sync — **without** a serve-window-aging roll re-seeding the walk.
- Contrast: with the flag off, the node exhibits the prior stall/roll churn (SC-006 / the differentiator).

Use `docker logs --tail N` (the full-log read truncates on this node) and the metrics endpoint (`:9095`). RPC `net_connectToPeer` works for adding peers but `net_peerCount` and most ask-based RPC time out while the walk pegs CPU — verify via `:9095`, not the JSON-RPC reply.

## Out of scope for validation here

- Persisting `serveRoot`/attempt counters across restart (in-memory by design; restart falls back to coupled / re-walk).
- The serve-root *selection policy* (newest- vs oldest-servable, research D4) — a flagged decision; validate whichever is chosen, and watch the FR-006 surfacing metric for shallow-gap liveness if newest-servable is kept.
