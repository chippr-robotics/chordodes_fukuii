# loop: invoked_by=[verify] applicable_recipes=[spec-conformance, test-greening]
---
name: speckit-bug-test
description: >-
  Validate a bug fix against the original symptom. Use after speckit-bug-fix has
  produced .specify/bugs/<slug>/fix.md. Reproduces the original symptom to confirm
  it is resolved, runs the regression suite, and writes a verdict. Read-only on
  source. Third step in the assess → fix → test bug triage workflow.
disable-model-invocation: true
user-invokable: true
model: sonnet
argument-hint: "slug (optional if fix context is present)"
---

# speckit-bug-test

Validate that the fix resolves the original symptom and introduces no regressions.
Read-only — you do not modify source code or assessment/fix artifacts.

## CRITICAL: never mark verified without running the repro

If the reproduction steps require a live node, network peers, or external state
that cannot be reproduced in this environment, say so explicitly and report
`not-reproducible` rather than claiming `verified`.

## Step 1: Resolve slug

Use the explicit argument, the slug from a prior session step, or the single
candidate in `.specify/bugs/`. Require both `assessment.md` and `fix.md`; if
either is missing, say which is needed and stop.

If `test.md` already exists, confirm before overwriting.

## Step 2: Plan validation

Read `assessment.md` (reproduction steps) and `fix.md` (tests added, verification
commands). Determine:
1. Can the original symptom be reproduced in this environment?
2. Which test(s) in `fix.md` directly cover the symptom?
3. Which test tier covers the changed code?

## Step 3: Run checks

For each check, record the command, exit code, and relevant output excerpt.
Do not skip a check without marking it `skipped: <reason>`.

```bash
# Reproduce original symptom (if possible)
sbt "testOnly *<relevant-spec>*"   # the new test from fix.md

# Regression suite
sbt testEssential                  # Tier 1 — must pass
```

Only run `sbt testStandard` or `sbt testComprehensive` if Tier 1 is clean and
the change touches integration-level code.

Do NOT run network-level reproduction without explicit user consent (it may
affect live peers or mainnet state).

## Step 4: Write test.md

```markdown
# Test: <slug>

## Verdict
verified | partial | failed | not-reproducible

## Checks
| Check | Command | Result | Notes |
|-------|---------|--------|-------|
| Symptom reproduction | sbt testOnly ... | PASS/FAIL | |
| Regression — Tier 1 | sbt testEssential | N passed, 0 failed | |

## Output excerpts
(key lines from the test run that justify the verdict)

## Residual risks
- (any edge cases the fix may not cover)

## Recommendation
- verified → open PR; include assessment + fix + test artifacts as context
- partial → list what still fails; return to speckit-bug-fix
- failed → symptom persists; describe what was observed vs expected
- not-reproducible → environment limitation; describe what manual step is needed
```

## Done

Report the verdict and recommendation. If `verified`, suggest committing per the
project commit workflow and opening a PR referencing `.specify/bugs/<slug>/`.
