---
name: fukuii-engine-api-debug
description: >-
  Troubleshoot a broken or stalled Engine API channel between a Consensus Layer
  client and Fukuii on ETH/Sepolia — JWT 401 auth failures, forkchoiceUpdated
  timeouts, newPayload INVALID, EL-reports-SYNCING, exchangeCapabilities version
  mismatches — and decide whether the fault is in the EL (Fukuii) or the CL. Use
  when the CL logs Engine API errors, payloads are rejected, attestation duties
  are withheld, or sync won't advance with a connected CL. ETH/Sepolia only;
  ETC/Mordor has no Engine API. For first-time CL bring-up see fukuii-cl-setup;
  for EL config keys see fukuii-engine-api-setup.
disable-model-invocation: true
user-invokable: true
---

# Fukuii Engine API debugging — ETH/Sepolia

Read `../CONVENTIONS.md` first. **ETC/Mordor: no Engine API — skip.** Diagnosis
here is 🟢 read-only (logs, RPC, `df`, `timedatectl`); the NTP/restart fixes are
🟡/🔴 — gate them.

Each section below is a self-contained case: symptom → cause → fix.

## Case 1 — JWT auth failure (HTTP 401 / "unauthorized" in CL logs)
**Symptom:** CL logs `401`, `Unauthorized`, or `invalid JWT`. No
`engine_forkchoiceUpdated` reaches the EL.

**Causes & fixes:**
- **Wrong JWT file path** — the CL and EL must read the *same* file byte-for-byte.
  Diff them: `cmp /path/to/jwt.hex <cl-jwt-path>`. Re-point both at one file.
- **Clock skew > 5s** — Engine API JWTs carry an `iat` claim; servers reject
  tokens more than ~5s out of sync. Check `timedatectl`; fix with
  `sudo timedatectl set-ntp true` (🟡) or `sudo ntpdate pool.ntp.org` (🟡).
- **JWT regenerated on one side only** — regenerating breaks auth instantly.
  Regenerate once, copy to both, restart both.

> A bare `curl http://localhost:8551/` returning **401 is healthy** (no JWT
> supplied). 401 is only a *fault* when the CL — which does supply a JWT — gets it.

## Case 2 — `engine_forkchoiceUpdatedV*` timeout
**Symptom:** CL logs forkchoice timeouts; EL slow or unresponsive to head updates.

**Causes & fixes:**
- **EL busy importing** — check Fukuii `BlockImporter` logs: is it stuck mid-import
  or in a long state operation? A backlogged EL can't answer forkchoice promptly.
- **System load** — check `uptime` / `top`; an I/O- or CPU-starved host stalls the
  EL. Verify the datadir is on SSD/NVMe, not HDD.
- If the EL is healthy and idle but still times out, capture the timing and treat
  as a possible EL bug — see Case 6 to localize EL vs CL.

## Case 3 — `engine_newPayloadV*` returns INVALID
**Symptom:** CL logs payloads marked `INVALID` by the EL.

**Cause:** Usually the payload's **parent is not in the EL chain** — a sync gap.
The EL can't validate a child whose parent it hasn't imported.

**Fix:** This is **normal during initial sync** while the EL is catching up — not
a bug. Let the EL reach the parent height. Persistent INVALID at the chain tip
(EL fully synced) is different: capture the payload's block hash/number and the
exact reason, then escalate as a possible consensus fault (route to BEACON).

## Case 4 — EL reports SYNCING to the CL
**Symptom:** CL withholds attestation/proposal duties; logs "execution client is
syncing" / "EL not ready".

**Cause:** The EL has told the CL (via `newPayload`/`forkchoiceUpdated` →
`SYNCING`) that it is not yet at the head. The CL correctly refuses to attest on
an un-synced EL.

**Fix:** Let the EL catch up. Check `eth_blockNumber` and `net_peerCount`:
```bash
cast rpc eth_blockNumber  --rpc-url http://localhost:8545
cast rpc net_peerCount    --rpc-url http://localhost:8545
```
If `eth_blockNumber` is advancing, wait. If flat with healthy peers, see
`fukuii-sepolia-sync` to identify which sync phase is stuck.

## Case 5 — `engine_exchangeCapabilities` version mismatch
**Symptom:** CL expects `engine_newPayloadV3`/`V4` (or forkchoice `V3`) for the
active fork but the EL advertises a different set; CL errors on capability
exchange or sends an unsupported method.

**Cause:** EL fork configuration disagrees with the CL about which fork is active.
On Sepolia, **Osaka** activates at `osaka-timestamp` (timestamp-gated, not block).
If the EL hasn't activated the right fork, it advertises the wrong Engine API
method versions.

**Fix:** Verify the EL fork schedule. Confirm `OsakaOpCodes` is active on the ETH
chain path and the Sepolia timestamps are present in
`base/chains/sepolia-chain.conf` (`cancun-timestamp`, `prague-timestamp`,
`osaka-timestamp`). Never use `forBlock()` dispatch for these post-Merge ETH
forks — they are timestamp-gated.

## Case 6 — Distinguishing EL vs CL bugs
A symptom that reproduces with **two different CL clients** (e.g. Prysm *and*
Lighthouse) against the same EL points at the **EL (Fukuii)** — escalate to
BEACON. A symptom that appears with **only one** CL points at that CL — consult
its docs/issue tracker. Swapping the CL is the cheapest localization test before
deep EL debugging.

## Decision guide
| Symptom | Case | First fix |
| :-- | :-- | :-- |
| CL 401 / unauthorized | 1 | `cmp` JWT files; check `timedatectl` |
| forkchoiceUpdated timeout | 2 | check `BlockImporter` logs + host load |
| newPayload INVALID during sync | 3 | normal — let EL catch up to parent |
| CL withholds duties, EL SYNCING | 4 | wait for EL; check blockNumber/peers |
| capability/version mismatch | 5 | verify `OsakaOpCodes` + Sepolia fork timestamps |
| reproduces on 2 CLs | 6 | EL bug → BEACON |
| reproduces on 1 CL only | 6 | CL bug → that CL's docs |

## Deep reference
- `fukuii-cl-setup`, `fukuii-engine-api-setup`, `fukuii-sepolia-sync`.
- `src/main/resources/conf/base/chains/sepolia-chain.conf` (fork timestamps).
- BEACON agent (`.claude/agents/beacon.md`) for suspected EL consensus faults.

## Output
CONVENTIONS §4 block. Evidence = the exact CL/EL log lines, the case identified,
`timedatectl`/`eth_blockNumber`/`net_peerCount` values where relevant, and
whether the fault localized to EL or CL.
