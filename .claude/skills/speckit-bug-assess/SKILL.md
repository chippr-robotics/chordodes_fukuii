# loop: invoked_by=[discover] applicable_recipes=[spec-conformance]
---
name: speckit-bug-assess
description: >-
  Assess a bug and produce a structured investigation report. Use when given a
  bug description, stack trace, error output, or GitHub issue URL. Reads source
  code, forms a root cause hypothesis, and writes .specify/bugs/<slug>/assessment.md.
  Does NOT modify source. First step in the assess → fix → test bug triage workflow.
disable-model-invocation: true
user-invokable: true
model: sonnet
argument-hint: "bug description or issue URL"
---

# speckit-bug-assess

Produce a structured bug assessment without touching source code. Output goes
to `.specify/bugs/<slug>/assessment.md` for downstream use by `speckit-bug-fix`
and `speckit-bug-test`.

## CRITICAL: read-only discipline

You may read any source file. You MUST NOT write to or edit any `.scala`, `.sbt`,
`.conf`, or test file during assessment. The only file you write is `assessment.md`.

## Step 1: Slug

If the user provided a slug (e.g. `/speckit-bug-assess bfs-queue-tombstone-degradation`),
use it. Otherwise ask: "Short slug for this bug? (kebab-case, max 40 chars)".
Create `.specify/bugs/<slug>/` if it does not exist.
If `assessment.md` already exists, confirm before overwriting.

## Step 2: Ingest the report

Take the bug as pasted text in the argument, or if given a URL, read it via
Bash (`curl -s <url>` for raw text). Trust only these domains: github.com,
gitlab.com, stackoverflow.com, sentry.io. For any other host, ask the user to
confirm before fetching.

## Step 3: Reproduce mentally

Read the suspected code paths. Do NOT invent file:line references — only cite
paths you have actually read. Form a root cause hypothesis; state your confidence
(high / medium / low) and what would change it.

## Step 4: Write assessment.md

```markdown
# Bug: <short title>

## Summary
One-paragraph description of the symptom and business impact.

## Reproduction steps
1. ...
2. ...
Expected: ...
Observed: ...

## Suspected code paths
- `path/to/File.scala:line` — why this is relevant
- (only cite files you actually read)

## Root cause hypothesis
**Confidence: high | medium | low**

Explanation of the mechanism.

What would raise/lower confidence: ...

## Proposed remediation

### Preferred approach
File(s) to change: ...
Change description: ...
Tests to add: ...

### Alternative (if preferred has risks)
...

## Risks and unknowns
- ...

## Scope of fix
Files to modify: (list)
Files NOT to modify: (e.g. consensus-boundary paths — delegate to forge/beacon)
```

## Consensus-critical gate

If the root cause lies in `consensus/`, `vm/`, `crypto/`, or `domain/`:
note this in the assessment and add a section:

```markdown
## Consensus-critical: delegate required
Root cause touches <path>. Before applying any fix, invoke `forge` (ETC/Mordor)
or `beacon` (ETH/Sepolia) per the Consensus-Critical Change Protocol in CLAUDE.md.
```

Do NOT propose a specific remediation for consensus-critical code — that is
`forge`/`beacon`'s role.

## Done

Report: "Assessment written to `.specify/bugs/<slug>/assessment.md`. Run
`/speckit-bug-fix` to apply the remediation."
