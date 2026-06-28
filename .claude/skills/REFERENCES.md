# Skill Reference Repositories

Skills reference the same repo set documented in `.claude/agents/REFERENCES.md`. This file lists the subset directly relevant to skills, with skill-specific lookup guidance.

For clone instructions and the full sync command, see [agents/REFERENCES.md](../agents/REFERENCES.md).

---

## Relevant Repos by Skill

### `speckit-*` (all 10 Spec Kit skills)

| Repo | Clone as | What to check |
|------|----------|--------------|
| Spec Kit | https://github.com/github/spec-kit | `CHANGELOG.md` before starting a new spec/plan session; `templates/` for latest spec templates; `AGENTS.md` for integration architecture updates; `docs/` for workflow guidance |

**Sync before a speckit session:**
```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
git -C "$REFS/spec-kit" pull --ff-only 2>/dev/null | grep -v "Already up to date" || true
```

---

### `fukuii-tech-debt-inventory`

| Repo | Clone as | What to check |
|------|----------|--------------|
| Scala 3 | https://github.com/scala/scala3 | `changelogs/` — new idioms that should now be flagged as modern (remove from debt list); `AGENTS.md` for test-annotation patterns |
| Scala 2 | https://github.com/scala/scala | `src/library/` — stdlib patterns to recognise as legacy during inventory |
| Apache Pekko | https://github.com/apache/pekko | `actor-typed/src/` — current Typed API surface to distinguish from Classic patterns being inventoried |

---

### `fukuii-dependency-audit`

| Repo | Clone as | What to check |
|------|----------|--------------|
| Scala 3 | https://github.com/scala/scala3 | `changelogs/` — compiler-level deprecations to cross-reference against project dependencies |

---

---

### `fukuii-sync-troubleshooting` / `fukuii-peer-management`

| Repo | Clone as | What to check |
|------|----------|--------------|
| Apache Pekko | `repo-references/pekko` | `stream/` — `Source.scala`, `Sink.scala`, materializer internals; `stream-testkit/` — `TestSink`, `TestSource`; `discovery/` — DNS-SD Lookup API |
| Apache Pekko Management | `repo-references/pekko-management` | `discovery/` — DNS-SD implementation; reference when debugging DnsDiscovery peer lookup failures |
| devp2p | `repo-references/ethereum/devp2p` | `discv4.md`, `discv5/` — discovery protocol specs; reference when diagnosing ENR or peer discovery failures |
| Hive | `repo-references/hive` (read `upstream` branch) | `simulators/devp2p/` — wire protocol compliance; `simulators/ethereum/` — block execution compliance; working ETC integration at `/media/dev/2tb/dev/reference-clients-evm/hive/` |

---

## Sync Relevant Refs (skills)

```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
for r in spec-kit scala3 scala2 pekko pekko-management; do
  git -C "$REFS/$r" pull --ff-only 2>/dev/null | grep -v "Already up to date" || true
done
```
