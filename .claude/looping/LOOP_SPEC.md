# Loop Spec — Contract and Schema

---

## The Loop Contract

Every loop runs this cycle, in order, for each iteration:

```
DISCOVER  work out the delta between current state and the goal
PLAN      state the single highest-impact next step
EXECUTE   make the smallest change that moves the gate; run it; surface output
VERIFY    run verify.sh; read sentinel lines, not the maker's claim
ITERATE   gate failed or delta remains -> record what failed, fix the weakest
          point first, repeat. gate passed and no open delta -> DONE
```

The VERIFY step is scripted. The maker runs `verify.sh <recipe-id>` and lets
its output land in the transcript. The evaluator and checker read the sentinel
lines from that output. The maker's narrative ("I believe the build is clean")
is never sufficient.

---

## Maker / Checker Invariant

The checker is never the same agent or model that produced the work.

- **Makers** (`wraith`, `mithril`, `loom`): write or transform code. They own EXECUTE.
- **Checkers** (`eye`, `prism`, `forge`, `beacon`, `herald`, `vault`, `conduit`, `flow`):
  review, validate, and gate. They own VERIFY confirmation.
- **Orchestrator** (main session): sequences agents, reads the ledger, resolves role
  conflicts, decides the next delta.

Consensus-critical checkers (`forge` for ETC, `beacon` for ETH) are **proactive** — they
must be consulted in the DISCOVER phase, before the maker executes, not after.

---

## Ralph Guard

A loop may declare DONE only when all three hold simultaneously, and the checker
(not the maker) makes the call:

1. The transcript shows the sentinel line `LOOP:<id> ALL_GATES:PASS`
2. The checker agent explicitly states `CONFIRM:DONE` referencing that line
3. The ledger shows no open delta

A maker asserting "I'm done" or "all gates should pass" is not sufficient.
A transcript without the sentinel line is not sufficient.

---

## Recipe Spec Schema

Every file in `recipes/*.loop.md` must fill in this schema:

```yaml
id:              short-stable-id              # used in sentinel lines and ledger paths
goal:            one verifiable end state      # transcript-provable; references the sentinel line
maker:           agent name                   # from registry.yaml
checker:         agent name or [list]         # must differ from maker
gates:           [subset of: compile, warnings, tests, format, conformance]
refresh_refs:    true|false                   # if true, refresh-refs.sh runs before conformance
constraints:     [list of hard invariants]    # things that must not change on the way there
budget:
  max_iterations: 25
  max_tokens:     500000                      # approximate; see open assumption in DISCOVERY.md
  max_wallclock:  45m
  min_accept_rate: 0.5                        # accepted_changes / iterations; abort below this
stop_on:         [gate_pass, budget_exhausted]
```

Poll recipes (no finish line) use:
```yaml
goal: NONE
stop_on: [budget_exhausted]
```

---

## Sentinel Line Protocol

### Gate scripts (`verify/*.sh`)

Each gate script prints exactly one canonical line and exits nonzero on failure:

```
GATE:<name> RESULT:PASS
GATE:<name> RESULT:FAIL detail=<short reason>
```

### Aggregate (`bin/verify.sh`)

After running all required gates for a recipe, `verify.sh` prints:

```
LOOP:<id> ALL_GATES:PASS
LOOP:<id> ALL_GATES:FAIL failed=[<name>,<name>]
```

### Checker confirmation

After reading the transcript, the checker states:

```
CONFIRM:DONE
CONFIRM:ITERATE reason=<what failed or remains>
```

---

## State Ledger

Per run: `state/<id>-<timestamp>/`

`ledger.md` (append-only):
```markdown
## Iteration N
Plan: <single highest-impact next step>
Change: <diffstat summary or commit SHA>
Gates: <paste every GATE: line from verify.sh output>
Result: PASS|FAIL
Failed: <gate names that failed, or none>
Next delta: <remaining work, or none>
```

`attempts.json` (updated each iteration):
```json
[
  {
    "iteration": 1,
    "approach": "description of what was tried",
    "outcome": "rejected|accepted",
    "reason": "why it was rejected or what it fixed"
  }
]
```

`start_time` — Unix timestamp written at run start; read by `budget-check.sh`

`ref_shas.md` (written by `refresh-refs.sh` when `refresh_refs: true`):
```
REPO:<path> SHA:<commit>
```

---

## Stop Conditions

A loop stops when the first of these is true:

1. `gate_pass` — `LOOP:<id> ALL_GATES:PASS` appears in transcript and checker
   issues `CONFIRM:DONE`
2. `budget_exhausted` — any of `max_iterations`, `max_wallclock`, `max_tokens`
   is hit, or `min_accept_rate` falls below threshold
3. Manual interrupt — user stops the session; write continuation file to
   `.local/docs/continuations/<recipe-id>.md` before exiting

---

## Cost Tracking

`budget-check.sh` computes per run:

- `cost_per_accepted_change` = wall time / accepted_changes
- `accept_rate` = accepted_changes / total_iterations

If `accept_rate < min_accept_rate`, abort and report — the loop is producing
results that are mostly discarded, which means it is doing review work a human
was meant to save.
