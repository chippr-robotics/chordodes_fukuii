# Recipe: actor-migration

Migrate one Pekko Classic actor to Pekko Typed (Behaviors.receive, sealed Command ADT,
explicit replyTo, no sender()), clean compile, and passing tests.

**When to reach for it:** For each remaining untyped actor in the migration queue.
Consult `working-docs/SPRINT-QUEUE.md` for the ordered list of next migration targets.
Run one actor per loop invocation — loom is designed for single-actor sessions.

---

```yaml
id: actor-migration-<ActorName>
goal: >
  <ActorName> is fully migrated from Pekko Classic to Typed (Behaviors.receive, sealed
  Command ADT, explicit replyTo, no sender()), sbt compile-all exits 0 with no new
  Pekko Classic E003 warnings, targeted testOnly passes, and bin/verify.sh prints
  "LOOP:actor-migration-<ActorName> ALL_GATES:PASS".
maker: loom
checker: eye
gates: [compile, tests]
refresh_refs: false
constraints:
  - follow pre-migration-checklist.md (13-point grep audit) in DISCOVER before any edits
  - one actor per loop run; do not expand scope to callers mid-run
  - no new Pekko Classic actor references (E003) may appear in the diff
  - if session budget is exhausted mid-migration, write continuation file to
    .local/docs/continuations/loom-<ActorName>.md before stopping
  - follow pekko-typed-api.md preferences P1-P13 throughout
  - compile-all after every file edit (testing-protocol.md cadence)
budget:
  max_iterations: 30
  max_wallclock: 120m
  min_accept_rate: 0.4
stop_on: [gate_pass, budget_exhausted]
```

## LOOP_TEST_TARGET

```
only *<ActorName>*
```

Final gate:
```
essential
```

## DISCOVER Phase (pre-migration-checklist)

Loom runs the 13-point pre-migration checklist before touching any file:

1. LOC count for the actor file
2. `sender()` call count
3. `return` statement count
4. `timers` / `scheduler` usage
5. Worker actor spawns within this actor
6. How callers invoke this actor (ask vs tell vs fire-and-forget)
7. Current test coverage
8. Any `@nowarn` annotations that would be invalidated
9. Cross-subsystem deps (consensus? storage? RPC?)
10. Existing typed sibling or parent actors to follow as style guide
11. Check pekko-typed-api.md P1-P13 for applicable patterns
12. Check if a continuation file exists from a prior partial attempt
13. Estimate: how many phases (0: inline-cleanup, 1: Messages.scala,
    2: main actor, 3: caller updates)?

Record all 13 answers in the ledger before any edits.

## PLAN Phase (each iteration)

Run one migration phase per iteration:
- Phase 0: inline cleanup (log.warning -> log.warn, remove returns)
- Phase 1: Messages.scala (add replyTo fields)
- Phase 2: Main actor migration (Behaviors.receive, cancel sender())
- Phase 3: Caller updates (ask-pattern, type updates)

## VERIFY Phase

After each phase, run compile-all. After phase 3 (callers updated):
```sh
LOOP_TEST_TARGET="only *<ActorName>*" .claude/looping/bin/verify.sh actor-migration-<ActorName> <ledger-dir>
```

Final gate (full regression check):
```sh
LOOP_TEST_TARGET=essential .claude/looping/bin/verify.sh actor-migration-<ActorName> <ledger-dir>
```

Expected transcript:
```
GATE:compile RESULT:PASS
GATE:tests RESULT:PASS
LOOP:actor-migration-<ActorName> ALL_GATES:PASS
```

eye issues: `CONFIRM:DONE`

## Continuation

If budget is exhausted mid-migration, loom writes:
`.local/docs/continuations/loom-<ActorName>.md`

Format (from migration-handoff.md):
- Current phase completed
- Files edited so far
- Remaining phases
- Any compile errors in progress
- Next iteration starting point
