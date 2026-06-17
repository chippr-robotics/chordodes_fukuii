# Agent Reference Repositories

Reference repos are cloned locally at `<fukuii-root>/.claude/repo-references/` (gitignored — per-machine, not committed). This file documents which repos exist, their GitHub URLs, and which agents use them.

## Clone Convention

```bash
cd "$(git rev-parse --show-toplevel)/.claude/repo-references"

# Core Language
git clone https://github.com/scala/scala3.git
git clone https://github.com/scala/scala.git scala2
git clone https://github.com/scala/docs.scala-lang.git

# Actor Framework
git clone https://github.com/apache/pekko.git
mkdir -p virtuslab && cd virtuslab
git clone https://github.com/VirtusLab/pekko-serialization-helper.git
git clone https://github.com/VirtusLab/scala-skill.git

# Testing
git clone https://github.com/scalamock/scalamock.git

# Spec-Driven Development
git clone https://github.com/github/spec-kit.git
```

## Sync All Clones

```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
find "$REFS" -maxdepth 3 -name .git -exec dirname {} \; \
  | xargs -I{} git -C {} pull --ff-only 2>/dev/null
```

---

## Repository Index

### Core Language — Scala 3

| | |
|---|---|
| **GitHub** | https://github.com/scala/scala3 |
| **Clone as** | `repo-references/scala3` |
| **Used by** | `mithril`, `wraith`, `prism` |
| **Key paths** | `AGENTS.md` · `changelogs/` · `docs/docs/reference/` · `tests/` |
| **Why** | Canonical Scala 3 idioms, breaking-change log, `// error` test annotation conventions, new migration patterns as they land |

### Core Language — Scala 2 (migration source reference)

| | |
|---|---|
| **GitHub** | https://github.com/scala/scala |
| **Clone as** | `repo-references/scala2` |
| **Used by** | `wraith` |
| **Key paths** | `AGENTS.md` · `src/library/` |
| **Why** | Recognise Scala 2 stdlib patterns during migration; understand what AGENTS.md says is safe to modify |

### Core Language — Scala Documentation

| | |
|---|---|
| **GitHub** | https://github.com/scala/docs.scala-lang |
| **Clone as** | `repo-references/docs.scala-lang` |
| **Used by** | `mithril` |
| **Key paths** | `_overviews/scala3-migration/` · `_scala3-reference/` · `_overviews/scala3-book/` |
| **Why** | Migration cookbook, idiomatic Scala 3 examples, official style guidance |

---

### Actor Framework — Apache Pekko

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko |
| **Clone as** | `repo-references/pekko` |
| **Used by** | `loom`, `herald` |
| **Key paths** | `AGENTS.md` · `actor-typed/src/main/scala/` · `CHANGELOG.md` · `serialization/` |
| **Why** | Canonical Typed actor API patterns; MiMa binary-compat rules; formatting and licensing rules from `AGENTS.md`; serialization marker interfaces |

### Actor Framework — Pekko Serialization Helper (VirtusLab)

| | |
|---|---|
| **GitHub** | https://github.com/VirtusLab/pekko-serialization-helper |
| **Clone as** | `repo-references/virtuslab/pekko-serialization-helper` |
| **Used by** | `loom` |
| **Key paths** | `README.md` · `core/src/main/scala/` |
| **Why** | `@SerializabilityTrait` and companion annotations — **required reading before migrating any actor that crosses a cluster/network boundary** |

---

### Testing — ScalaMock

| | |
|---|---|
| **GitHub** | https://github.com/scalamock/scalamock |
| **Clone as** | `repo-references/scalamock` |
| **Used by** | `prism`, `eye` |
| **Key paths** | `README.md` · `core/src/main/scala/` |
| **Why** | Idiomatic Scala 3 mock patterns; verify fukuii tests use current API |

---

### Spec-Driven Development — Spec Kit

| | |
|---|---|
| **GitHub** | https://github.com/github/spec-kit |
| **Clone as** | `repo-references/spec-kit` |
| **Used by** | all `speckit-*` skills |
| **Key paths** | `AGENTS.md` · `CHANGELOG.md` · `templates/` · `docs/` · `extensions/` |
| **Why** | Upstream SDD workflow — new spec templates, integration patterns, skill updates. Check `CHANGELOG.md` before starting a `speckit-specify` or `speckit-plan` session |

---

### VirtusLab — Scala Skill

| | |
|---|---|
| **GitHub** | https://github.com/VirtusLab/scala-skill |
| **Clone as** | `repo-references/virtuslab/scala-skill` |
| **Used by** | `mithril` |
| **Key paths** | `README.md` |
| **Why** | IDE-integrated Scala development patterns; cross-reference when proposing editor-visible refactors |
