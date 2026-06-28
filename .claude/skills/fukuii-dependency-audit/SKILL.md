# loop: invoked_by=[discover] applicable_recipes=[warning-ratchet]
---
name: fukuii-dependency-audit
description: >-
  Audit Scala, sbt, and key library versions for LTS compliance in the Fukuii
  project. Checks project/Dependencies.scala, project/build.properties, and
  runs sbt dependencyUpdates to surface stale or outdated versions. Use when
  asked to "check deps", "dep audit", "is our scala LTS current", "are
  dependencies up to date", or as part of a monthly LTS review cadence.
  Read-only; safe to run anytime. Does NOT require a running node.
disable-model-invocation: true
---

# Fukuii dependency audit

This skill does **not** require a running Fukuii node and makes no RPC calls.
It is **read-only** — no guarded writes. Run freely at any time.

## When to use
- Monthly LTS cadence check: is Scala 3.x LTS still on the latest patch? Is sbt current?
- Before planning a modernization sprint (confirm version targets before writing specs)
- After a Scala/Pekko/CE3 release announcement
- When asked "are any of our dependencies outdated?"

## Procedure (all 🟢 read-only)

### 1. Read pinned version declarations
```bash
grep -E 'scala-3|pekkoVersion|pekkoHttpVersion' /media/dev/2tb/dev/fukuii/build.sbt /media/dev/2tb/dev/fukuii/project/Dependencies.scala
cat /media/dev/2tb/dev/fukuii/project/build.properties
```

### 2. Check endoflife.date for Scala
```bash
curl -s "https://endoflife.date/api/scala.json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for c in data[:6]:
    print(f'  {c[\"cycle\"]}: latest={c[\"latest\"]}  eol={c.get(\"eol\", \"N/A\")}')
"
```
Report: is the current Scala 3.x LTS patch the latest? Is there a newer patch?

### 3. Run sbt dependencyUpdates
```bash
cd /media/dev/2tb/dev/fukuii && sbt "dependencyUpdates" 2>&1 | grep -E "^\[info\] [^ ]" | grep -v "Loading\|Compiling\|Done\|Setting"
```
The `sbt-updates` plugin (`com.timushev.sbt:sbt-updates:0.6.4`) is installed.
This lists all direct and transitive dependencies with newer versions available.

### 4. BSL guard — zero Akka imports
```bash
grep -rn "import akka\." /media/dev/2tb/dev/fukuii/src/main/scala/ --include="*.scala" 2>/dev/null || echo "✓ Zero Akka imports — BSL-clean"
grep '"com.typesafe.akka"\|"com.lightbend".*akka' /media/dev/2tb/dev/fukuii/project/Dependencies.scala && echo "CRITICAL: BSL 1.1 Akka dep found" || echo "✓ No Akka deps in Dependencies.scala"
```
Any `com.typesafe.akka` or `com.lightbend:akka*` entry is a **CRITICAL** BSL 1.1
violation — incompatible with Fukuii's Apache 2.0 license. Block immediately.

### 5. Cross-check global lts-versions.md
Read `/media/dev/2tb/dev/claude-global-settings/rules/lts-versions.md`.
Note any divergence between what Fukuii pins and what the global LTS file says
is current. If lts-versions.md is stale, update it as a separate edit (not a
Fukuii commit — it lives in a different repo).

## Reference repos

After running `sbt dependencyUpdates`, cross-reference the Scala 3 compiler changelog for deprecations not yet surfaced by sbt:

```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
git -C "$REFS/scala3" pull --ff-only 2>/dev/null | grep -v "Already up to date" || true
# Then review: ls "$REFS/scala3/changelogs/"
```

| Repo | GitHub | What to check |
|------|--------|---------------|
| scala3 | https://github.com/scala/scala3 | `changelogs/` for compiler-level deprecations that affect project dependencies — cross-reference against what `sbt dependencyUpdates` surfaces |

Full index: [`.claude/skills/REFERENCES.md`](../REFERENCES.md)

## Decision guide

| Finding | Action |
| :-- | :-- |
| Scala patch behind (e.g. 3.3.7 vs 3.3.8) | Update `build.sbt` — patch bumps are zero-risk |
| Pekko minor version available | Evaluate changelog; test with `sbt testEssential` |
| Pekko major version available | Spec it via `/speckit-specify` — potential Typed API changes |
| Any `com.typesafe.akka` entry | **CRITICAL** — block; BSL 1.1 violation |
| CE3 / fs2 major update | Spec it — functional concurrency changes are high-impact |
| lts-versions.md stale | Update the global file (separate edit, not a Fukuii commit) |

## Output
Structured report per CONVENTIONS §4:
- **Scala version**: current vs latest LTS patch + EOL date
- **sbt version**: current vs latest
- **Key libraries**: Pekko, CE3, circe, fs2 — current vs available
- **BSL guard**: result of Akka scan (must be zero)
- **Recommended actions**: zero-risk patches vs spec-required upgrades
