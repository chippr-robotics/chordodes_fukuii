# Recipe: spec-conformance

Align a specific Fukuii implementation surface with its EIP/ECIP spec text and
the canonical upstream client behavior, then close with a passing conformance gate.

**When to reach for it:** After ref-parity-audit reports drift on a surface, or
after an upstream reference client release lands a spec change Fukuii needs to track.
**Instantiate per-surface:** copy this recipe and replace `<surface>` before running.

**CRITICAL:** Consensus-critical surfaces (ETC: forge, ETH: beacon) require specialist
consultation in the DISCOVER phase, BEFORE the maker executes. This is a hard protocol
requirement, not a suggestion.

---

```yaml
id: spec-conformance-<surface>
# Examples: spec-conformance-eth70, spec-conformance-olympia-opcodes, spec-conformance-snap
goal: >
  conformance.sh diff for <surface> is empty (or checker confirms no semantic drift),
  sbt compile-all exits 0, the target test suite passes, and bin/verify.sh prints
  "LOOP:spec-conformance-<surface> ALL_GATES:PASS" with checker CONFIRM:DONE.
maker: wraith
checker: <one of: forge (ETC), beacon (ETH), herald (wire/SNAP), vault (storage), conduit (RPC)>
gates: [compile, tests, conformance]
refresh_refs: true
constraints:
  - refresh-refs.sh must succeed before conformance.sh runs
  - consensus-change-protocol.md must be followed:
      forge/beacon consulted in DISCOVER, before maker executes
  - no change to non-surface code; minimal footprint
  - follow risk-stratified-commit: bucket-C (semantic) changes get their own commit
  - do not mix surface fix with unrelated cleanup
budget:
  max_iterations: 20
  max_wallclock: 60m
  min_accept_rate: 0.5
stop_on: [gate_pass, budget_exhausted]
```

## Surface-to-Checker Routing

| Surface | Checker | Ref client | Notes |
|---------|---------|-----------|-------|
| `eth70` | herald | go-ethereum upstream | ETH70 partial receipts |
| `eth69` | herald | go-ethereum upstream | ETH69 PoW extensions |
| `snap` | herald | go-ethereum upstream | SNAP sync wire protocol |
| `olympia-opcodes` | beacon | go-ethereum upstream | EIP-7939 CLZ, Osaka ops |
| `olympia-etc` | forge | core-geth upstream | ETC Olympia block-number forks |
| `fee-floor` | forge | core-geth upstream | ECIP-1122 min miner tip |
| `rpc-eth` | conduit | go-ethereum upstream | eth_* method compliance |
| `storage-path` | vault | go-ethereum upstream | PathScheme storage format |

## LOOP_TEST_TARGET

```
only *<SurfaceName>*
```
Use the suite name matching the surface (e.g., `only *Eth70* *PartialReceipt*`).
Final gate uses `essential`.

## DISCOVER Phase

1. Run `refresh-refs.sh <ledger-dir>` to ensure all reference repos are current.
2. **If surface is ETC or ETH consensus:** invoke forge or beacon in DISCOVER phase
   now, before the maker does anything. The checker produces an impact analysis.
3. Identify the Fukuii source files responsible for the surface (use grep on the
   surface protocol name or EIP/ECIP number).
4. Diff Fukuii implementation against spec text in `.claude/repo-references/ECIPs`
   or `.claude/repo-references/EIPs` and against the reference client `upstream` branch.
5. Record the delta in the ledger.

## VERIFY Phase

Run:
```sh
.claude/looping/bin/verify.sh spec-conformance-<surface> <ledger-dir>
```

The transcript must show:
```
GATE:compile RESULT:PASS
GATE:tests RESULT:PASS
GATE:conformance RESULT:PASS
LOOP:spec-conformance-<surface> ALL_GATES:PASS
NOTE: Invoke the <checker> agent to review...
```

The checker agent then reads the conformance report and issues:
- `CONFIRM:DONE` — no semantic drift found
- `CONFIRM:ITERATE reason=<what differs>` — drift remains

## Continuation

Write `.local/docs/continuations/spec-conformance-<surface>.md` if the session ends
before the checker issues CONFIRM:DONE.
