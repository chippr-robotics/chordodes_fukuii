# Loop Eligibility

A job qualifies as a loop only when all four boxes are checked. One-off or
taste-driven work stays a manual prompt.

---

## Four-Box Test

1. **Recurs** — the job happens at least roughly weekly, or is large enough
   that the harness setup cost pays back within two or three runs.

2. **Auto-rejectable** — something can automatically reject bad output: a
   compile error, a failing test, a lint violation, a conformance diff, a
   type-check failure. If nothing can fail the work without human judgment,
   it is not a loop.

3. **End-to-end agent capable** — the agent can complete the work from delta
   identification to gate passage without handing half of it back to a human
   mid-task.

4. **Objective done condition** — "done" is provable from the transcript: a
   sentinel line, an exit code, a diff that is empty. Taste-driven ("looks
   good") does not qualify.

---

## Recipe Verdicts

| Recipe | Recurs | Auto-rejectable | Agent capable | Objective done | Verdict |
|--------|--------|-----------------|---------------|----------------|---------|
| `warning-ratchet` | Yes (per sprint) | Yes — compile exits nonzero on new warnings or new @nowarn | Yes — wraith fixes sites systematically | Yes — `LOOP:... ALL_GATES:PASS` | **ELIGIBLE** |
| `spec-conformance` | Yes (per upstream release) | Yes — conformance.sh diff nonzero on drift | Yes — wraith aligns impl to spec | Yes — empty diff + gate pass | **ELIGIBLE** |
| `test-greening` | Yes (after major refactors) | Yes — testEssential exits nonzero on failure | Yes — wraith identifies and fixes failing tests | Yes — 0 failures + gate pass | **ELIGIBLE** |
| `actor-migration` | Yes (one actor per run; queue in SPRINT-QUEUE) | Yes — compile exits nonzero on Classic refs; testOnly exits nonzero on regressions | Yes — loom runs one actor per session per pre-migration-checklist | Yes — compile-all clean + testOnly pass | **ELIGIBLE** |
| `ref-parity-audit` | Yes (weekly scheduled poll) | Yes (poll, not finish-line) — conformance gate flags drift | Yes — checkers audit without code changes | N/A (poll; no finish line) | **ELIGIBLE (poll)** |

---

## `bin/eligible.sh` Verification

Run `./claude/looping/bin/eligible.sh <recipe-id>` before starting any loop.
The script prints `ELIGIBLE:YES` or `ELIGIBLE:NO reason=<text>` based on:
- Whether the recipe file exists in `recipes/`
- Whether all declared gate scripts exist in `verify/`
- Whether the declared maker and checker are in `registry.yaml`
- Whether `refresh_refs: true` recipes can reach the ref repos
