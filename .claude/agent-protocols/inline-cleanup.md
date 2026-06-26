# Inline Cleanup Protocol ("Hunt and Seek")

Every agent that touches a file is also a modernization agent. This protocol defines
what to fix opportunistically in files already opened, what to flag but not touch,
and the discipline rules that keep scope from exploding.

Used by: ALL agents
Referenced by: loom.md, mithril.md, wraith.md, prism.md, herald.md

---

## Core principle

> Each sprint is also continuous modernization. When you open a file to do primary work,
> you catch and fix the cheap things in that same file. You don't go hunting into other files.
> You don't chase calls. You commit the cleanup separately from the primary work.

This turns point-in-time fixes into a ratchet: every file we touch leaves the codebase
slightly more modern than we found it.

---

## What to FIX opportunistically (in files already opened)

These are bucket-A (mechanical) or bucket-B (idiom) items that are safe to fix inline.
Commit separately from primary work using the risk-stratified commit protocol.

### Fix immediately — no judgment needed

| Pattern | Fix | Bucket | Grep to find |
|---------|-----|--------|--------------|
| `log.warning(...)` | `log.warn(...)` | A | `grep -n "log\.warning"` |
| `println(...)` in main source | `ctx.log.info(...)` or `logger.info(...)` | A | `grep -n "println"` |
| Unused imports | Remove | A | Compile errors / IDE |
| `/*` wildcard imports (pre-migration) | Already swept — flag if found | A | `grep -n "import.*\.\*"` |
| `self: A with B =>` self-type syntax | `self: A & B =>` | A | `grep -n "self:.*with\b"` |
| Trailing whitespace | Remove | A | — |
| `return` in a Unit method (clear restructure) | Restructure as expression | B | `grep -n "^\s*return\b"` |

### Fix if the restructure is obvious (bucket B)

- `return x` in a method body where the restructure is 1-2 lines and clearly equivalent
- `var` that is set once and never reassigned → `val`
- `extends` with a now-unused mixin → remove the mixin

When in doubt, FLAG instead of fix (see below).

---

## What to FLAG but NOT fix (write a comment or continuation note)

These patterns require more context or specialist review. Do not fix inline.
Record in the continuation file if doing LOOM work, or surface to the user.

| Pattern | Why not fix | Route to |
|---------|------------|---------|
| `sender()` in a Classic actor you are NOT migrating | Changes message protocol | LOOM when that actor is scheduled |
| `extends Actor` in a file you opened but aren't migrating | Full migration needed | LOOM |
| `implicit val/def` in main sources | Waiting for Part 3a | MITHRIL after Pekko complete |
| `asInstanceOf[T]` | Bucket C — needs proof | FORGE (ETC) or BEACON (ETH) if consensus, MITHRIL otherwise |
| `isInstanceOf[T]` | Bucket C — sealed ADT needed | MITHRIL |
| Any pattern in `consensus/`, `vm/`, `crypto/`, `domain/` | Consensus-critical | FORGE / BEACON |
| Wire message encoding in `network/p2p/messages/` | Protocol-critical | HERALD |
| `Thread.sleep` | Concurrency risk | Separate session (C7 chore) |
| Exception swallowing (bare `case _:` with no logging) | May hide real bugs | PRISM review first |

---

## Discipline rules

### Rule 1: Stay in the file — log cross-file finds in CHASE-QUEUE.md

Fix what you find in the file you're already editing.
Do NOT navigate to callers, imports, or related files to continue the cleanup.
Exception: if a caller file is already in your task's scope, cleanup there too.

When you spot an issue in a file you're NOT editing, append it to:
`.claude/agent-protocols/working-docs/CHASE-QUEUE.md`

```
| path/to/File.scala | line | pattern description | TYPE | AgentName | YYYY-MM-DD |
```

After logging the entry, check whether the pattern is isolated or widespread:
```bash
# How many files share the same pattern?
grep -rn "pattern_to_fix" src/main/ --include="*.scala" | wc -l
```
Include the count in the CHASE-QUEUE description (e.g., `log.warning→warn (N=12)`).
Entries with `N=5+` cluster into sprint tasks faster, and the count helps the sprint
agent decide whether to do a targeted fix or a full sweep.

Public document — code patterns only, no internal dev commentary.
Entries are batched into sprint sessions when a cluster forms (5+ of same type).

### Rule 2: Separate commit

All inline cleanup goes in its own commit, separate from the primary task.

```
Primary work commit:
  feat(snap): add BlockRangeUpdate subscription to SNAPSyncController

Inline cleanup commit (same session):
  chore(cleanup): log.warning→log.warn, remove unused imports in SNAPSyncController
```

If the cleanup is only 2-3 lines, bundle with a comment but still commit separately if
the primary work is a logic change. When in doubt, separate.

### Rule 3: Compile after cleanup commit

```bash
sbt compile-all    # must pass before moving to next phase
```

Never combine a cleanup commit with a failing compile — it makes bisect useless.

### Rule 4: Consensus files — flag only

If a file in `consensus/`, `vm/`, `crypto/`, or `domain/` has cleanup candidates,
write a flag comment in your continuation file. Do NOT fix inline. Route to FORGE (ETC) or BEACON (ETH).

```
# continuation note:
# consensus/pow/EthashMiner.scala:87 — log.warning found, route to FORGE for log.warn fix
```

### Rule 5: Don't downgrade to fix

If fixing a pattern would make the code temporarily worse (e.g., restructuring a `return`
requires adding a new val that clutters the method), leave it. The ratchet works over time;
not every file gets fully cleaned in one pass.

---

## Multi-domain scope

Inline cleanup applies to ALL languages and domains in the fukuii codebase,
not just Scala 3 / Pekko:

| Domain | Common inline finds | Notes |
|--------|--------------------|-|
| Scala 3 / Pekko | log.warning, println, unused imports, self: with | Most common |
| EVM / opcode logic | Hardcoded numbers without named constants | FORGE (ETC) or BEACON (ETH) review before naming |
| devp2p / wire protocol | Hardcoded magic bytes without named constants | HERALD review |
| SNAP protocol | println debugging left in | Safe to remove |
| RLP codecs | Unused `given` instances, duplicate decoders | HERALD if in wire messages |
| Build files | Unused deps in build.sbt | Safe to remove after confirming unused |
| Shell scripts | Debug `echo` statements | Safe to remove |

The scope-discipline rules apply in all domains: fix in the file you're in,
commit separately, don't chase into consensus paths.

---

## The grep-verifiable ratchet for cleanup progress

Run these periodically to see progress across the codebase:

```bash
# log.warning count (target: 0 in all migrated actors)
grep -rn "log\.warning" src/main/ --include="*.scala" | wc -l

# println count (target: 0 in main sources)
grep -rn "println" src/main/ --include="*.scala" | wc -l

# Approximate return count (target: 0)
grep -rn "^\s*return\b" src/main/ --include="*.scala" | wc -l

# self: with count (target: 0)
grep -rn "self:.*with\b" src/main/ --include="*.scala" | wc -l
```

These numbers only go down. If one increases after a session, something regressed.
The warning-ratchet protocol (`warning-ratchet.md`) formalizes promoting categories
to build errors once a count reaches zero.

---

## Integration with LOOM migrations

When LOOM is migrating an actor, inline cleanup runs as a sub-phase:

```
Phase 0: Pre-flight
Phase 0.5: Inline cleanup commit (log.warning, unused imports, println)
Phase 1: returns removal
...
```

The Phase 0.5 cleanup is logged in the LOOM summary and the continuation file.
It is not listed in the migration phase plan — it's opportunistic, not scheduled.

If a migration session runs short on time, Phase 0.5 cleanup is the FIRST thing to drop.
The migration phases are the priority; cleanup is the bonus.
