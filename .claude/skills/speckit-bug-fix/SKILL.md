# loop: invoked_by=[execute] applicable_recipes=[spec-conformance, test-greening]
---
name: speckit-bug-fix
description: >-
  Apply a bug fix from an existing assessment. Use after speckit-bug-assess has
  produced .specify/bugs/<slug>/assessment.md. Reads the assessment as a contract,
  confirms the plan, applies the remediation, adds/updates tests, and runs
  sbt testEssential. Second step in the assess → fix → test bug triage workflow.
disable-model-invocation: true
user-invokable: true
model: sonnet
argument-hint: "slug (optional if assessment context is present)"
---

# speckit-bug-fix

Apply the remediation defined in `assessment.md`. The assessment is the contract;
stay within its scope unless new evidence demands a deviation (which you document).

## CRITICAL: assessment.md is read-only

Never edit `assessment.md`. If you disagree with the assessment, record the
deviation in `fix.md`. Never delete any file.

## Step 1: Resolve slug

Use the explicit argument, the slug from a prior `speckit-bug-assess` in this
session, or — if exactly one `.specify/bugs/*/assessment.md` exists — infer it.
Otherwise ask. Require `assessment.md` to exist; if missing, say "Run
`/speckit-bug-assess` first."

If `fix.md` already exists, confirm before overwriting.

## Step 2: Confirm plan

Read `assessment.md`. Present a 3–6 bullet confirmation:

```
Plan:
• Change: <brief description>
• Files: <list from assessment>
• Tests to add: <list>
• Consensus-critical? <yes → STOP / no → proceed>
```

If the assessment flagged consensus-critical scope, STOP and say: "This fix
requires `forge` (ETC) or `beacon` (ETH) — see assessment.md."

## Step 3: Apply remediation

- Stay within the files listed in `assessment.md` unless new evidence strictly
  requires touching additional files (document the deviation).
- Write tests before or alongside production changes (TDD: red → green → refactor).
- Run the existing test suite after each file change — do not batch all edits
  then run once.

```bash
sbt compile-all           # must be green after each file
sbt testEssential         # Tier 1 gate — must pass before writing fix.md
```

## Step 4: Write fix.md

```markdown
# Fix: <slug>

## Status
applied | partial | not-applied

## Changes
| File | Lines | Description |
|------|-------|-------------|
| path/to/File.scala | +N -M | What changed and why |

## Tests added
- `path/to/NewSpec.scala` — what it covers

## Verification
VERIFY: ran `sbt compile-all` — result: PASS
VERIFY: ran `sbt testEssential` — result: N passed, 0 failed

## Deviations from assessment
- (none) or list what changed and why

## Follow-ups
- (anything the fix revealed that is out of scope)
```

## Done

Report: "Fix applied and verified. Run `/speckit-bug-test` to validate against
the original symptom."
