# Testing Protocol

Test cadence for all agent sessions on the fukuii codebase. The full test suite
takes 24 minutes — running it between phases compounds into hours of stall time.
This protocol keeps feedback fast without sacrificing coverage.

Used by: ALL agents
Referenced by: `fukuii/CLAUDE.md`, loom.md

---

## The three tiers (ADR-017)

| Command | What runs | Time | Baseline |
|---------|-----------|------|---------|
| `sbt testEssential` | 3,621 unit tests | ~24 min | 3,621 / 0 failures |
| `sbt testStandard` | Unit + integration | ~30 min | — |
| `sbt testComprehensive` | Full ethereum/tests compliance | <3 h | — |

---

## Per-phase cadence

### After EVERY file edit
```bash
sbt compile-all    # mandatory, fast — type errors surface immediately
```
Never batch multiple file edits before compiling. One file, one compile.

### After formatting-only phases
(Returns removal, Messages.scala type additions, import cleanup)
```bash
sbt scalafmtAll    # formatting check — no tests needed, no logic changed
```
No tests. These phases change syntax only — compile is the full signal.

### After logic-changing phases
(Main migration, caller updates, behavior changes)
```bash
./local/scripts/fukuii-test <ActorNameSpec>          # actor-specific, seconds
./local/scripts/fukuii-test <SubsystemSuite>         # subsystem if callers touched
```
Run targeted tests only. Do not run testEssential here.

### End of thread — once
```bash
./local/scripts/fukuii-test                          # full testEssential (~24 min)
```
Run exactly once per thread after all phases are complete. This is the regression gate.
Do not run it between phases. Do not run it as a mid-session sanity check.

---

## Targeted test patterns

```bash
# By actor name:
./local/scripts/fukuii-test AccountRangeCoordinatorSpec
./local/scripts/fukuii-test ByteCodeCoordinatorSpec

# By subsystem:
./local/scripts/fukuii-test SNAPSuite      # all SNAP tests (~263)
./local/scripts/fukuii-test NetworkSuite

# By tag (sbt native):
sbt testNetwork
sbt testRLP
sbt testCrypto
sbt testVM
```

---

## Format commands

| Command | What it does | When to use |
|---------|-------------|-------------|
| `sbt scalafmtAll` | scalafmt across ALL modules | After every commit during migrations |
| `sbt scalafmt` | scalafmt ROOT module only | **Never** — misses submodules |
| `sbt formatAll` | scalafixAll + scalafmtAll | Pre-PR on clean codebase ONLY |
| `sbt scalafmtAll` ≠ `sbt formatAll` | formatAll runs scalafix | formatAll aborts on pre-existing violations |

---

## What "baseline holds" means

The thread-end `testEssential` run must show:
- Test count: **3,621** (not fewer — a missing test class = silent deletion)
- Failures: **0**
- If count drops: investigate before closing the thread

**Per-thread delta tracking:** Before starting any migration or sweep thread, record
the current test count from the most recent `testEssential` run (see the test quality
log at `.local/docs/test-quality-log.md` for the last known baseline). At thread end,
compare. A negative delta — even by 1 — means a test class was silently deleted or a
`@Test` annotation was dropped. This commonly happens when a Classic actor spec is
deleted during migration but no Typed replacement spec is written. Investigate before
closing; do not accept a lower count as the new baseline without a recorded reason.

---

## Inline test standards (when writing new tests)

- No `Thread.sleep` — use `eventually(...)` or `TestProbe.expectMsg(duration)`
- No `@Ignore` without a one-line comment explaining the gate condition
- Deterministic: same result on every machine and CI run
- Each test covers one behavior — not a scenario script
- New tests for migrated actors: use `ActorTestKit` (Typed), not `TestActorRef` (Classic)

---

## Protocol for failing tests after migration

1. Run targeted test first: `./local/scripts/fukuii-test <FailingSpec>`
2. Read the failure — understand it before touching anything
3. If failure is in the migrated actor: fix the migration, not the test
4. If failure is in a test that tests Classic behavior: update the test for Typed API
5. If failure is in an unrelated test: note it, do not fix it (out of scope), surface to user
6. Never modify a test to make it pass without understanding why it failed
