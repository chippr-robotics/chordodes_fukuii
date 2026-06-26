---
name: fukuii-pos-node-health
description: >-
  Produce a PoS-specific health verdict for an ETH/Sepolia Fukuii node — the
  CL↔EL Engine API liveness check, EL sync state, CL attestation participation
  (if validating), post-Cancun blob gossip peer count, and Engine API latency.
  Use when checking an ETH/Sepolia node's consensus-side health, after attaching
  a CL, when attestations are missed, or when blob sidecars seem to be dropped.
  ETH/Sepolia only — for the general/ETC health verdict use fukuii-node-health-check;
  for Engine API faults use fukuii-engine-api-debug.
disable-model-invocation: true
user-invokable: true
---

# Fukuii PoS node health — ETH/Sepolia

Read `../CONVENTIONS.md` first. This skill is **read-only** (🟢). It covers the
**PoS/consensus-side** dimensions specific to ETH/Sepolia. For the general
verdict (client version, sync, EL peer count, log scan, disk), run
`fukuii-node-health-check` first — this skill **adds** the four checks below, it
does not repeat them. ETC/Mordor operators: use `fukuii-node-health-check` only.

## When to use
- Routine health of an ETH/Sepolia EL paired with a CL.
- After attaching/replacing a CL, or when attestations are being missed.
- When blob sidecars appear to be dropped (post-Cancun).

## Checks (all 🟢)

1. **CL↔EL Engine API liveness**
   ```bash
   curl -s -o /dev/null -w "%{http_code}" http://localhost:8551/
   ```
   **`401` = live and correctly requiring JWT auth (expected, healthy).** A bare
   curl carries no JWT, so 401 is the right answer. `000` / connection-refused =
   Engine API not bound → EL down, wrong port, or `engine-api.enabled=false`
   (→ `fukuii-engine-api-setup`).

2. **EL sync status**
   ```bash
   cast rpc eth_syncing --rpc-url http://localhost:8545      # false = synced
   ```
   `false` = at the tip. An object = still catching up; confirm `eth_blockNumber`
   is advancing. If stuck, identify the sync phase via `fukuii-sepolia-sync`.

3. **CL attestation participation (validators only)**
   Attestation/proposal effectiveness lives on the **CL**, not the EL — the EL
   exposes none of it. Check the CL's metrics/REST endpoint:
   - Beacon node: `curl http://localhost:5052/eth/v1/node/syncing` (Lighthouse
     REST shape; port/path vary by client).
   - Validator/CL metrics (Prometheus) for attestation hit-rate and missed
     duties. A healthy validator attests every epoch. Missed duties with a
     SYNCING EL → the EL isn't ready (→ `fukuii-engine-api-debug` Case 4).

4. **Blob gossip peer count (post-Cancun)**
   After Cancun, blob sidecars (EIP-4844) propagate on a **separate gossip
   network managed by the CL** — they are *not* visible in the EL's
   `net_peerCount`. A low **CL** peer count → missed blob sidecars → the EL
   receives payloads it can't fully validate. Check both:
   ```bash
   cast rpc net_peerCount --rpc-url http://localhost:8545    # EL peers (hex)
   ```
   and the CL's peer count (e.g. `curl http://localhost:5052/eth/v1/node/peer_count`).
   Low CL peers is the usual root cause of "blob sidecar not found" — fix on the
   CL side.

5. **Engine API latency**
   Target **< 2s** for `engine_newPayloadV*` and `engine_forkchoiceUpdated*`.
   Sample the round-trip from Fukuii's Engine API logs (request → response
   timestamps). Sustained high latency → the CL marks the EL **slow** and may
   withhold timely head updates; on a validator this costs attestation
   effectiveness. High latency usually means the EL host is I/O- or CPU-bound
   (datadir on HDD, swapping, or under load) — check `uptime` and datadir disk
   type, and see `fukuii-engine-api-debug` Case 2.

## Decision guide
| Check | Healthy | Degraded → action |
| :-- | :-- | :-- |
| `:8551` curl | `401` | refused → `fukuii-engine-api-setup` |
| `eth_syncing` | `false` | object & flat → `fukuii-sepolia-sync` |
| CL attestations | every epoch | missed + EL SYNCING → `fukuii-engine-api-debug` C4 |
| Blob/CL peers | CL peers healthy | low CL peers → fix CL peering |
| Engine API latency | < 2s | sustained > 2s → check host I/O/load (C2) |

## Deep reference
- `fukuii-node-health-check` — general/ETC health verdict (run first).
- `fukuii-cl-setup`, `fukuii-engine-api-setup`, `fukuii-engine-api-debug`,
  `fukuii-sepolia-sync`.
- `ops/grafana/Sepolia Consensus/fukuii-sepolia-staking.json` — staking dashboard.

## Output
CONVENTIONS §4 block. Verdict (HEALTHY / DEGRADED / ACTION REQUIRED). Evidence =
the `:8551` status code, `eth_syncing`, EL and CL peer counts, attestation state,
and observed Engine API latency. Actions taken: none (read-only).
