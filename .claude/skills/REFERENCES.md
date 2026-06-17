# Skill Reference Repositories

Skills reference the same repo set documented in `.claude/agents/REFERENCES.md`. This file lists the subset directly relevant to skills, with skill-specific lookup guidance.

For clone instructions and the full sync command, see [agents/REFERENCES.md](../agents/REFERENCES.md).

---

## Relevant Repos by Skill

### `speckit-*` (all 10 Spec Kit skills)

| Repo | Clone as | What to check |
|------|----------|--------------|
| Spec Kit | `repo-references/spec-kit` | `CHANGELOG.md` before starting a new spec/plan session; `templates/` for latest spec templates; `AGENTS.md` for integration architecture updates; `docs/` for workflow guidance |

**Sync before a speckit session:**
```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
git -C "$REFS/spec-kit" pull --ff-only 2>/dev/null | grep -v "Already up to date" || true
```

---

### `fukuii-tech-debt-inventory`

| Repo | Clone as | What to check |
|------|----------|--------------|
| Scala 3 | `repo-references/scala3` | `changelogs/` — new idioms that should now be flagged as modern (remove from debt list); `AGENTS.md` for test-annotation patterns |
| Scala 2 | `repo-references/scala2` | `src/library/` — stdlib patterns to recognise as legacy during inventory |
| Apache Pekko | `repo-references/pekko` | `actor-typed/src/` — current Typed API surface to distinguish from Classic patterns being inventoried |

---

### `fukuii-dependency-audit`

| Repo | Clone as | What to check |
|------|----------|--------------|
| Scala 3 | `repo-references/scala3` | `changelogs/` — compiler-level deprecations to cross-reference against project dependencies |

---

## Sync Relevant Refs (skills)

```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
for r in spec-kit scala3 scala2 pekko; do
  git -C "$REFS/$r" pull --ff-only 2>/dev/null | grep -v "Already up to date" || true
done
```
