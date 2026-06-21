# Documentation Migration — 2026-06-21

## What changed

Four sprint/queue tracking documents have been centralized under the agent-protocols
directory structure. The old copies in `.local/docs/` are **not yet deleted** — pending
a diff review to confirm all information transferred correctly.

---

## New canonical locations

All active sprint/queue work is now tracked here:

| Document | New canonical path | Status |
|----------|--------------------|--------|
| `DEFERRED-BACKLOG.md` | `.claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md` | **Active** — update here |
| `SPRINT-QUEUE.md` | `.claude/agent-protocols/working-docs/SPRINT-QUEUE.md` | **Active** — update here |
| `CHORE-QUEUE.md` | `.claude/agent-protocols/working-docs/CHORE-QUEUE.md` | **Active** — update here |
| `CHASE-QUEUE.md` | `.claude/agent-protocols/working-docs/CHASE-QUEUE.md` | **Active** — was already here |

Completed sprint archives go in:

```
.claude/agent-protocols/completed-sprints/
```

(currently empty — populate as sprints close out)

---

## Old locations (do not update — do not delete yet)

These are the sources that were copied. Keep until diff review is confirmed.

| Document | Old path | Notes |
|----------|----------|-------|
| `DEFERRED-BACKLOG.md` | `.local/docs/moderization-review-june/DEFERRED-BACKLOG.md` | **Superseded** — read-only reference |
| `SPRINT-QUEUE.md` | `.local/docs/moderization-review-june/SPRINT-QUEUE.md` | **Superseded** — read-only reference |
| `CHORE-QUEUE.md` | `.local/docs/moderization-review-june/implementation-sprint/CHORE-QUEUE.md` | **Superseded** — read-only reference |

The entire `.local/docs/moderization-review-june/` tree contains additional research and
audit documents that are **not** being migrated — they stay in `.local/` as internal
reference material (gitignored). Only the four active queue/backlog tracking files move.

---

## Why these locations

`.claude/agent-protocols/` is symlinked from `~/.claude/agent-protocols/` and tracked in
the public repo. Moving the queue docs here means:

1. **Version-controlled** alongside the agent protocol specs they complement
2. **Visible to all agents** via the standard protocol path
3. **Consistent** — `CHASE-QUEUE.md` was already here; the others now join it
4. **Separated from internal-only `.local/` research docs** which remain gitignored

---

## Diff review checklist (before deleting old files)

- [ ] `diff working-docs/DEFERRED-BACKLOG.md .local/docs/moderization-review-june/DEFERRED-BACKLOG.md` → confirm identical
- [ ] `diff working-docs/SPRINT-QUEUE.md .local/docs/moderization-review-june/SPRINT-QUEUE.md` → confirm identical
- [ ] `diff working-docs/CHORE-QUEUE.md .local/docs/moderization-review-june/implementation-sprint/CHORE-QUEUE.md` → confirm identical
- [ ] Confirm no other files in `.local/docs/moderization-review-june/` reference these by path in a way that would break

Once all three diffs are clean and the new files have been updated through at least one
sprint session, delete the old copies and remove refs from MEMORY.md.

---

## For agents starting a new thread

**Read from:**
```
.claude/agent-protocols/working-docs/DEFERRED-BACKLOG.md   ← backlog + gate status
.claude/agent-protocols/working-docs/SPRINT-QUEUE.md       ← thread prompts
.claude/agent-protocols/working-docs/CHORE-QUEUE.md        ← downtime tasks
.claude/agent-protocols/working-docs/CHASE-QUEUE.md        ← cross-file issues
```

**Do not read from** `.local/docs/moderization-review-june/` for these four files —
those copies are frozen as of 2026-06-21 and will diverge.

When a sprint phase closes out, append a summary entry to the relevant file in:
```
.claude/agent-protocols/completed-sprints/
```
