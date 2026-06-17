---
name: fukuii-tech-debt-inventory
description: >-
  Scan the Fukuii codebase for Scala 2 legacy patterns and produce a ranked
  tech-debt inventory for MITHRIL sprint planning. Counts implicit val/def,
  implicit class, sealed trait + case object hierarchies, and wildcard imports
  by file. Use when asked for "tech debt", "modernization targets", "implicit
  audit", "what should mithril focus on", or before planning a Scala 3
  modernization sprint. Read-only; safe to run anytime. Does NOT require a
  running node.
disable-model-invocation: true
---

# Fukuii tech-debt inventory

This skill does **not** require a running Fukuii node and makes no RPC calls.
It is **read-only** — no guarded writes. Run freely at any time.

## When to use
- Before planning a MITHRIL modernization sprint (seed the work list with ranked targets)
- To track migration progress after a Scalafix `GivenUsing` pass
- When asked "how much Scala 2 code is left?" or "what's the migration backlog?"
- Before writing `.local/docs/moderization-review-june/scala-implicit-to-given.md`

## Procedure (all 🟢 read-only)

Run each grep against `src/main/scala/`. Results are ranked by occurrence count.

### 1. `implicit val` / `implicit def` → `given` candidates
```bash
echo "=== implicit val/def by file (top 20) ==="
grep -rn "implicit val\|implicit def" /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" | grep -v "^\s*//" | awk -F: '{print $1}' | sort | uniq -c | sort -rn | head -20
echo ""
echo "Total occurrences:"
grep -rc "implicit val\|implicit def" /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" | awk -F: '$2>0 {sum+=$2} END {print sum}'
```

### 2. `implicit class` → `extension` candidates
```bash
echo "=== implicit class (all files) ==="
grep -rn "implicit class" /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" | grep -v "^\s*//" | awk -F: '{print $1}' | sort | uniq -c | sort -rn
```

### 3. Wildcard imports `import x._` → `import x.*`
```bash
echo "=== wildcard imports by file (top 20) ==="
grep -rn "import .*\._$" /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" | grep -v "^\s*//" | awk -F: '{print $1}' | sort | uniq -c | sort -rn | head -20
echo ""
echo "Total wildcard imports:"
grep -rc "import .*\._$" /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" | awk -F: '$2>0 {sum+=$2} END {print sum}'
```

### 4. `sealed trait` + `case object` — enum candidates
```bash
echo "=== sealed traits (total) ==="
grep -rc "sealed trait\|sealed abstract class" /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" | awk -F: '$2>0 {sum+=$2} END {print sum}'
echo "=== case objects (total — these are the safe enum targets) ==="
grep -rc "^  *case object" /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" | awk -F: '$2>0 {sum+=$2} END {print sum}'
```
Note: only `sealed trait` hierarchies where ALL subtypes are `case object` are
safe enum migration targets. `sealed trait` with `case class` subtypes carry
data and must stay as is.

### 5. Pekko Classic actor inventory
```bash
echo "=== Pekko Classic actors still to migrate ==="
grep -rln "extends Actor\b\|extends Actor with\|extends UntypedActor" \
  /media/dev/2tb/dev/fukuii/src/main/scala/ --include="*.scala"
echo ""
echo "=== Already Typed (for reference) ==="
grep -rln "extends AbstractBehavior\|Behaviors\.setup\|Behaviors\.receive" \
  /media/dev/2tb/dev/fukuii/src/main/scala/ --include="*.scala"
```

### 6. BSL guard
```bash
echo "=== BSL guard: Akka imports ==="
grep -rn "import akka\." /media/dev/2tb/dev/fukuii/src/main/scala/ \
  --include="*.scala" 2>/dev/null || echo "✓ Zero Akka imports — BSL-clean"
```

## Decision guide

| Pattern | Risk | MITHRIL approach |
| :-- | :-- | :-- |
| `implicit val/def` in non-consensus code | Low | Mechanical via `sbt scalafix GivenUsing` |
| `implicit val/def` in EVM/consensus code | Medium | MITHRIL + FORGE review before commit |
| `implicit class` | Low | `sbt scalafix` or manual extension rewrite |
| `import x._` | Low | `sbt scalafix` or bulk find-replace |
| `sealed trait` + all-`case object` | Low | Enum migration; check exhaustive match sites |
| `sealed trait` + `case class` subtypes | High | Do NOT migrate to enum — data types stay as is |
| Classic `extends Actor` | Varies | See migration decision table in `pekko-typed-migration-p2.md` |
| Any `import akka.` | **CRITICAL** | BSL 1.1 — block and remove immediately |

## Reference repos

Cross-check the inventory output against upstream for accuracy:

```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
for r in scala3 pekko; do
  git -C "$REFS/$r" pull --ff-only 2>/dev/null | grep -v "Already up to date" || true
done
```

| Repo | GitHub | What to check |
|------|--------|---------------|
| scala3 | https://github.com/scala/scala3 | `changelogs/` — patterns that moved to "idiomatic" since last inventory (may remove from the debt list rather than add to it) |
| pekko | https://github.com/apache/pekko | `CHANGELOG.md` — verify remaining Classic API is still migration-targeted; check if any Typed patterns have changed |

Full index: [`.claude/skills/REFERENCES.md`](../REFERENCES.md)

## Output

Emit a structured inventory table:

```
TECH-DEBT INVENTORY (src/main/scala/)
======================================
Pattern                      Files    Occurrences    Risk     Tool
------------------------------------------------------------------------
implicit val/def             XX       XXX            Low      sbt scalafix GivenUsing
implicit class               XX       XX             Low      sbt scalafix / manual
import x._                   XX       XXX            Low      sbt scalafix / replace
sealed trait (total)         XX       XX             Mixed    case-by-case
case object (enum targets)   XX       XX             Low      manual per hierarchy
Classic actors remaining     XX       —              Varies   MITHRIL (see p2 spec)
------------------------------------------------------------------------
BSL guard (import akka.)     0        0              ✓ CLEAN
```

Append the top-10 files by total legacy pattern count — these are the highest-
value targets for the first MITHRIL session.
